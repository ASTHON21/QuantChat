package com.quantchat.engine.llamacpp

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.EngineConfig
import com.quantchat.engine.core.EngineError
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.EngineModelDetails
import com.quantchat.engine.core.EngineVersionInfo
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.InferenceThreadPriority
import com.quantchat.engine.core.MmapLeaseHandle
import com.quantchat.engine.core.MmapLifecycleRegistry
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.PromptTemplateFormatter
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.util.Locale

private const val TAG = "LlamaCppBackend"

class LlamaCppBackend(
    contentResolver: ContentResolver
) : InferenceBackend {

    private val llama = LlamaAndroid(contentResolver)
    private val engineMutex = Mutex()
    private val tokenLock = Any()

    override val versionInfo: EngineVersionInfo = EngineVersionInfo(
        backendId = "llamacpp-cpu",
        engineName = "llama.cpp (llamacpp-kotlin)",
        engineVersion = "0.4.0",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    )

    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportedBackends = setOf(AcceleratorBackendType.CPU),
        supportsStreaming = true,
        supportsKvSession = true,
        supportsTokenize = true,
        supportsEmbedding = true,
        supportsNativeBenchmark = false,
        supportsGpuOffload = false,
        supportsNpuOffload = false,
        gpuUnavailableReason = "llamacpp-kotlin:0.4.0 bundles CPU-only binaries (n_gpu_layers=0).",
        npuUnavailableReason = "No NNAPI / Hexagon NPU delegate in llamacpp-kotlin:0.4.0."
    )

    private val _lifecycleState = MutableStateFlow<EngineLifecycleState>(
        EngineLifecycleState.Uninitialized
    )
    override val lifecycleState: StateFlow<EngineLifecycleState> = _lifecycleState.asStateFlow()

    @Volatile
    private var engineConfig: EngineConfig = EngineConfig()

    @Volatile
    private var activeContextId: Int? = null

    @Volatile
    private var activeModelConfig: ModelConfig? = null

    @Volatile
    private var activeModelDetails: EngineModelDetails? = null

    @Volatile
    private var activeSessionId: SessionId? = null

    @Volatile
    private var activeTokenSink: ((String) -> Unit)? = null

    @Volatile
    private var activeMmapLease: MmapLeaseHandle? = null

    @Volatile
    private var stopRequested: Boolean = false

    override suspend fun initialize(config: EngineConfig) {
        engineMutex.withLock {
            engineConfig = config
            if (_lifecycleState.value is EngineLifecycleState.Uninitialized) {
                _lifecycleState.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
            }
        }
    }

    override suspend fun validateModelFile(fileUri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { llama.isGGUF(Uri.parse(fileUri)) }.getOrDefault(false)
    }

    override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                unloadLocked()
                _lifecycleState.value = EngineLifecycleState.LoadingModel(
                    modelId = model.modelId,
                    displayName = model.displayName
                )

                val diskFile = File(model.filePath)
                if (!diskFile.exists() || diskFile.length() < 1024L) {
                    val err = EngineError.InvalidModelFormat(
                        model.displayName,
                        "File is missing or incomplete on storage."
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

                val uri = Uri.parse(model.fileUri)
                val validGguf = runCatching { llama.isGGUF(uri) }.getOrDefault(false)
                if (!validGguf) {
                    val err = EngineError.InvalidModelFormat(
                        model.displayName,
                        "Failed GGUF magic header verification."
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

                val effectiveMmap = model.useMmap && engineConfig.enableMmap
                val lease = try {
                    MmapLifecycleRegistry.acquireReadMapping(
                        file = diskFile,
                        modelId = model.modelId.value,
                        backendId = versionInfo.backendId,
                        useMmap = effectiveMmap
                    )
                } catch (e: EngineError) {
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, e)
                    throw e
                }

                val pfd = try {
                    ParcelFileDescriptor.open(diskFile, ParcelFileDescriptor.MODE_READ_ONLY).also {
                        MmapLifecycleRegistry.attachPfd(lease, it)
                    }
                } catch (e: Exception) {
                    MmapLifecycleRegistry.releaseMapping(lease)
                    val err = EngineError.BackendInitFailed(
                        "Unable to open read-only file descriptor for ${model.displayName}.",
                        e
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }
                val fd = pfd.detachFd()
                MmapLifecycleRegistry.markPfdDetachedToNative(lease)

                val engineParams = mapOf<String, Any>(
                    "model" to model.fileUri,
                    "model_fd" to fd,
                    "n_ctx" to model.contextSize,
                    "n_batch" to model.batchSize,
                    "n_threads" to model.threads,
                    "n_gpu_layers" to 0,
                    "use_mmap" to effectiveMmap,
                    "use_mlock" to (model.useMlock || engineConfig.enableMlock),
                    "embedding" to model.enableEmbedding
                )

                val result = try {
                    llama.startEngine(engineParams) { tokenChunk ->
                        activeTokenSink?.invoke(tokenChunk)
                    }
                } catch (e: CancellationException) {
                    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                    MmapLifecycleRegistry.releaseMapping(lease)
                    _lifecycleState.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
                    throw e
                } catch (e: Exception) {
                    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                    MmapLifecycleRegistry.releaseMapping(lease)
                    val err = EngineError.BackendInitFailed(
                        e.localizedMessage ?: "Native llama.cpp startEngine threw an error.",
                        e
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

                if (result == null) {
                    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                    MmapLifecycleRegistry.releaseMapping(lease)
                    val err = EngineError.BackendInitFailed(
                        "Native llama.cpp failed to initialize ${model.displayName}. Verify available RAM and 64-bit ABI support."
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

                val ctxId = (result["contextId"] as? Number)?.toInt() ?: run {
                    MmapLifecycleRegistry.releaseMapping(lease)
                    val err = EngineError.BackendInitFailed("Native engine returned an invalid context ID.")
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

                val details = parseModelDetails(result)
                activeContextId = ctxId
                activeModelConfig = model
                activeModelDetails = details
                activeSessionId = null
                activeMmapLease = lease

                val ready = EngineLifecycleState.Ready(
                    modelId = model.modelId,
                    contextId = ctxId,
                    config = model,
                    activeSessionId = null,
                    modelDetails = details
                )
                _lifecycleState.value = ready
                Log.d(TAG, "Loaded ${model.displayName} ctx=$ctxId n_ctx=${model.contextSize} T=${model.threads} mmap=$effectiveMmap")
                ready
            }
        }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        stopRequested = false
        val startMs = System.currentTimeMillis()
        val accumulated = StringBuilder()
        var chunkCount = 0

        activeTokenSink = { chunk ->
            if (chunk.isNotEmpty()) {
                val (cleaned, count) = synchronized(tokenLock) {
                    chunkCount++
                    accumulated.append(chunk)
                    PromptTemplateFormatter.stripControlTokens(
                        accumulated.toString(),
                        request.stopTokens
                    ) to chunkCount
                }
                trySend(GenerationEvent.TokenDelta(accumulatedText = cleaned, tokenCount = count))
            }
        }

        val job = launch(Dispatchers.IO) {
            try {
                val completionResult = engineMutex.withLock {
                    val ctxId = activeContextId
                    val modelCfg = activeModelConfig
                    if (ctxId == null || modelCfg == null || modelCfg.modelId != request.modelId) {
                        throw EngineError.BackendInitFailed("Target model ${request.modelId.value} is not loaded.")
                    }

                    activeSessionId = request.sessionId
                    _lifecycleState.value = EngineLifecycleState.Generating(
                        modelId = modelCfg.modelId,
                        sessionId = request.sessionId,
                        tokensEmitted = 0
                    )

                    val turns = PromptTemplateFormatter.buildTurns(
                        systemPrompt = request.systemPrompt,
                        history = request.history,
                        userPrompt = request.userPrompt
                    )
                    val formattedPrompt = formatChatInternal(ctxId, turns, "")
                        ?: PromptTemplateFormatter.formatFallback(
                            modelFamilyHint = request.modelFamilyHint,
                            systemPrompt = request.systemPrompt,
                            history = request.history,
                            userPrompt = request.userPrompt
                        )

                    val effectiveThreads = (request.threadsOverride ?: modelCfg.threads).coerceIn(1, 16)
                    val completionParams = mapOf<String, Any>(
                        "prompt" to formattedPrompt,
                        "n_predict" to request.maxTokens,
                        "n_threads" to effectiveThreads,
                        "temperature" to request.temperature.toDouble(),
                        "top_k" to request.topK,
                        "top_p" to request.topP.toDouble(),
                        "min_p" to request.minP.toDouble(),
                        "penalty_repeat" to request.repeatPenalty.toDouble(),
                        "emit_partial_completion" to true,
                        "stop" to request.stopTokens
                    )

                    val prevPriority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
                    applyLegalThreadPriority(request.threadPriority)
                    try {
                        llama.launchCompletion(ctxId, completionParams)
                    } finally {
                        if (prevPriority != null) {
                            runCatching { Process.setThreadPriority(prevPriority) }
                        }
                        if (activeContextId == ctxId) {
                            _lifecycleState.value = EngineLifecycleState.Ready(
                                modelId = modelCfg.modelId,
                                contextId = ctxId,
                                config = modelCfg,
                                activeSessionId = activeSessionId,
                                modelDetails = activeModelDetails
                            )
                        }
                    }
                }

                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val (streamedRaw, streamedCount) = synchronized(tokenLock) {
                    accumulated.toString() to chunkCount
                }

                if (completionResult == null && streamedRaw.isBlank() && !stopRequested) {
                    throw EngineError.GenerationFailed(
                        "Local llama.cpp completion failed. Try reducing context size or reloading the model."
                    )
                }

                val resultText = (completionResult?.get("text") as? String).orEmpty()
                val rawFinal = streamedRaw.ifBlank { resultText }
                val finalText = PromptTemplateFormatter
                    .stripControlTokens(rawFinal, request.stopTokens)
                    .trim()
                val predictedCount = (completionResult?.get("tokens_predicted") as? Number)?.toInt() ?: 0
                val effectiveTokens = maxOf(streamedCount, predictedCount)
                val tokPerSec = if (effectiveTokens > 0) {
                    (effectiveTokens * 1000f) / elapsed.toFloat()
                } else 0f

                send(
                    GenerationEvent.Completed(
                        finalText = finalText.ifEmpty {
                            if (stopRequested) "[Generation stopped]" else "(Empty response from local model)"
                        },
                        elapsedMs = elapsed,
                        tokenCount = effectiveTokens,
                        tokensPerSecond = tokPerSec,
                        wasStopped = stopRequested
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineError) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Local inference failed", e)
                throw EngineError.GenerationFailed(e.localizedMessage ?: "Inference failed", e)
            } finally {
                activeTokenSink = null
                close()
            }
        }

        awaitClose {
            activeTokenSink = null
            if (job.isActive) {
                job.cancel()
            }
        }
    }

    override suspend fun cancel() {
        stopRequested = true
        val ctxId = activeContextId ?: return
        withContext(Dispatchers.IO) {
            runCatching { llama.stopCompletion(ctxId) }
                .onFailure { Log.w(TAG, "stopCompletion warning", it) }
        }
    }

    override suspend fun unload() {
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                unloadLocked()
            }
        }
    }

    override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int): BenchmarkResult =
        benchmarkWithThreads(
            threads = activeModelConfig?.threads ?: 4,
            prefillTokens = prefillTokens,
            decodeTokens = decodeTokens
        )

    override suspend fun benchmarkWithThreads(
        threads: Int,
        prefillTokens: Int,
        decodeTokens: Int
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val ctxId = activeContextId
            val modelCfg = activeModelConfig
            if (ctxId == null || modelCfg == null) {
                throw EngineError.BackendInitFailed("Load a GGUF model before running hardware benchmark.")
            }
            val effectiveThreads = threads.coerceIn(1, 16)
            _lifecycleState.value = EngineLifecycleState.Benchmarking(modelCfg.modelId)
            val prevPriority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
            applyLegalThreadPriority(InferenceThreadPriority.FAVORABLE)
            try {
                val syntheticPrompt = buildString {
                    append("Benchmark sequence: ")
                    repeat(prefillTokens.coerceIn(16, 128)) { idx ->
                        append("token").append(idx % 17).append(' ')
                    }
                }

                var firstTokenAtMs = 0L
                val benchStartMs = System.currentTimeMillis()
                activeTokenSink = { chunk ->
                    if (firstTokenAtMs == 0L && chunk.isNotEmpty()) {
                        firstTokenAtMs = System.currentTimeMillis()
                    }
                }

                val targetDecode = decodeTokens.coerceIn(8, 64)
                val params = mapOf<String, Any>(
                    "prompt" to syntheticPrompt,
                    "n_predict" to targetDecode,
                    "n_threads" to effectiveThreads,
                    "temperature" to 0.0,
                    "emit_partial_completion" to true
                )

                val result = llama.launchCompletion(ctxId, params)
                val endMs = System.currentTimeMillis()
                activeTokenSink = null

                val actualPrefillTokens = runCatching { tokenizeInternal(ctxId, syntheticPrompt).size }
                    .getOrDefault(prefillTokens)
                    .coerceAtLeast(1)
                val actualDecodeTokens = ((result?.get("tokens_predicted") as? Number)?.toInt() ?: targetDecode)
                    .coerceAtLeast(1)

                val totalMs = (endMs - benchStartMs).coerceAtLeast(1L)
                val prefillMs = if (firstTokenAtMs > benchStartMs) {
                    (firstTokenAtMs - benchStartMs).coerceAtLeast(1L)
                } else {
                    (totalMs / 3L).coerceAtLeast(1L)
                }
                val decodeMs = if (firstTokenAtMs in (benchStartMs + 1) until endMs) {
                    (endMs - firstTokenAtMs).coerceAtLeast(1L)
                } else {
                    totalMs
                }

                val ppRate = (actualPrefillTokens * 1000f) / prefillMs.toFloat()
                val tgRate = (actualDecodeTokens * 1000f) / decodeMs.toFloat()

                BenchmarkResult(
                    prefillTokensPerSec = ppRate,
                    decodeTokensPerSec = tgRate,
                    prefillTokens = actualPrefillTokens,
                    decodeTokens = actualDecodeTokens,
                    elapsedMs = totalMs,
                    isNativeBench = false,
                    notes = "Measured real completion (${effectiveThreads}T CPU)"
                )
            } finally {
                if (prevPriority != null) {
                    runCatching { Process.setThreadPriority(prevPriority) }
                }
                activeTokenSink = null
                if (activeContextId == ctxId) {
                    _lifecycleState.value = EngineLifecycleState.Ready(
                        modelId = modelCfg.modelId,
                        contextId = ctxId,
                        config = modelCfg,
                        activeSessionId = activeSessionId,
                        modelDetails = activeModelDetails
                    )
                }
            }
        }
    }

    private fun applyLegalThreadPriority(priority: InferenceThreadPriority) {
        val target = when (priority) {
            InferenceThreadPriority.BACKGROUND -> Process.THREAD_PRIORITY_BACKGROUND
            InferenceThreadPriority.DEFAULT -> Process.THREAD_PRIORITY_DEFAULT
            InferenceThreadPriority.FAVORABLE -> Process.THREAD_PRIORITY_MORE_FAVORABLE
        }
        runCatching { Process.setThreadPriority(target) }
    }

    override suspend fun tokenize(text: String): IntArray = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val ctxId = activeContextId
                ?: throw EngineError.BackendInitFailed("No active model context for tokenize.")
            tokenizeInternal(ctxId, text)
        }
    }

    override suspend fun detokenize(tokens: IntArray): String = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val ctxId = activeContextId
                ?: throw EngineError.BackendInitFailed("No active model context for detokenize.")
            runCatching {
                llama.detokenize(ctxId, tokens.toList()).firstOrNull().orEmpty()
            }.getOrElse { e ->
                throw EngineError.GenerationFailed("detokenize failed", e)
            }
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val ctxId = activeContextId
                ?: throw EngineError.BackendInitFailed("No active model context for embedding.")
            val cfg = activeModelConfig
            if (cfg?.enableEmbedding != true) {
                throw EngineError.UnsupportedCapability(
                    "Embedding",
                    "Current context was loaded with embedding=false."
                )
            }
            runCatching {
                val map = llama.embedding(ctxId, text).firstOrNull()
                val raw = map?.get("embedding") ?: map?.values?.firstOrNull {
                    it is FloatArray || it is DoubleArray || it is List<*>
                }
                when (raw) {
                    is FloatArray -> raw
                    is DoubleArray -> FloatArray(raw.size) { raw[it].toFloat() }
                    is List<*> -> raw.mapNotNull { (it as? Number)?.toFloat() }.toFloatArray()
                    else -> floatArrayOf()
                }
            }.getOrElse { e ->
                throw EngineError.GenerationFailed("embedding failed", e)
            }
        }
    }

    override suspend fun saveSession(
        sessionId: SessionId,
        path: String,
        maxTokens: Int
    ): SessionSaveResult = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val ctxId = activeContextId
                ?: throw EngineError.BackendInitFailed("No active model context to save session.")
            try {
                val rawResult: Any? = llama.saveSession(ctxId, path, maxTokens)
                val collected = if (rawResult is Flow<*>) rawResult.firstOrNull() else rawResult
                val savedTokens = ((collected as? Map<*, *>)?.get("tokens_saved") as? Number)?.toInt()
                    ?: maxTokens
                activeSessionId = sessionId
                SessionSaveResult(sessionId = sessionId, path = path, tokensSaved = savedTokens)
            } catch (e: Exception) {
                throw EngineError.SessionCorrupted(sessionId, e.localizedMessage ?: "saveSession failed", e)
            }
        }
    }

    override suspend fun loadSession(sessionId: SessionId, path: String): Int =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                val ctxId = activeContextId
                    ?: throw EngineError.BackendInitFailed("No active model context to load session.")
                try {
                    val resultMap = llama.loadSession(ctxId, path).firstOrNull()
                    val loadedTokens = (resultMap?.get("tokens_loaded") as? Number)?.toInt()
                        ?: (resultMap?.get("n_tokens") as? Number)?.toInt()
                        ?: (resultMap?.get("count") as? Number)?.toInt()
                        ?: if (resultMap != null && resultMap["error"] == null) 0 else -1
                    if (loadedTokens < 0) {
                        throw IllegalStateException(resultMap?.get("error")?.toString() ?: "loadSession returned error")
                    }
                    activeSessionId = sessionId
                    val modelCfg = activeModelConfig
                    if (modelCfg != null) {
                        _lifecycleState.value = EngineLifecycleState.Ready(
                            modelId = modelCfg.modelId,
                            contextId = ctxId,
                            config = modelCfg,
                            activeSessionId = sessionId,
                            modelDetails = activeModelDetails
                        )
                    }
                    loadedTokens
                } catch (e: Exception) {
                    throw EngineError.SessionCorrupted(sessionId, e.localizedMessage ?: "loadSession failed", e)
                }
            }
        }

    override suspend fun formatChat(turns: List<ChatTurn>, template: String): String? =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                val ctxId = activeContextId ?: return@withLock null
                formatChatInternal(ctxId, turns, template)
            }
        }

    private suspend fun formatChatInternal(
        ctxId: Int,
        turns: List<ChatTurn>,
        template: String
    ): String? {
        val chatMaps = turns.map { mapOf("role" to it.role, "content" to it.content) }
        return runCatching {
            llama.getFormattedChat(ctxId, chatMaps, template).firstOrNull()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun tokenizeInternal(ctxId: Int, text: String): IntArray =
        runCatching {
            val resultMap = llama.tokenize(ctxId, text).firstOrNull()
            val rawTokens = resultMap?.get("tokens") ?: resultMap?.values?.firstOrNull {
                it is IntArray || it is List<*>
            }
            when (rawTokens) {
                is IntArray -> rawTokens
                is List<*> -> rawTokens.mapNotNull { (it as? Number)?.toInt() }.toIntArray()
                else -> intArrayOf()
            }
        }.getOrElse { e ->
            throw EngineError.GenerationFailed("tokenize failed", e)
        }

    private suspend fun unloadLocked() {
        val prevModel = activeModelConfig?.modelId
        val ctxId = activeContextId
        val leaseToRelease = activeMmapLease
        if (ctxId != null || leaseToRelease != null) {
            _lifecycleState.value = EngineLifecycleState.Unloading(prevModel)
        }
        activeContextId = null
        activeModelConfig = null
        activeModelDetails = null
        activeSessionId = null
        activeTokenSink = null
        activeMmapLease = null
        if (ctxId != null) {
            runCatching {
                llama.stopCompletion(ctxId)
                llama.releaseContext(ctxId)
            }.onFailure { Log.w(TAG, "releaseContext warning", it) }
        }
        // Release tracked mmap lease & shared FileLock before completing unload
        MmapLifecycleRegistry.releaseMapping(leaseToRelease)
        _lifecycleState.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
    }

    private fun parseModelDetails(result: Map<String, Any>): EngineModelDetails {
        val gpuActive = result["gpu"] as? Boolean ?: false
        val reasonNoGpu = result["reasonNoGPU"] as? String
        val rawMap = mutableMapOf<String, String>()
        val modelObj = result["model"] as? Map<*, *>
        modelObj?.forEach { (k, v) ->
            if (k != null && v != null) {
                rawMap[k.toString()] = v.toString()
            }
        }
        val metadataMap = (modelObj?.get("metadata") as? Map<*, *>)
        metadataMap?.forEach { (k, v) ->
            if (k != null && v != null) {
                rawMap[k.toString()] = v.toString()
            }
        }
        val arch = rawMap["general.architecture"]
        val desc = (modelObj?.get("desc") as? String) ?: rawMap["general.name"]
        val params = (modelObj?.get("nParams") as? Number)?.toLong()
        val ctxLen = (rawMap["$arch.context_length"]?.toIntOrNull())
        val embLen = (rawMap["$arch.embedding_length"]?.toIntOrNull())
        return EngineModelDetails(
            architecture = arch,
            description = desc,
            parameterCount = params,
            contextLength = ctxLen,
            embeddingSize = embLen,
            gpuActive = gpuActive,
            gpuFallbackReason = reasonNoGpu,
            rawMetadata = rawMap
        )
    }
}
