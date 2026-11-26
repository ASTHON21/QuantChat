package com.quantchat.engine.vulkan

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

private const val TAG = "VulkanLlamaBackend"

class VulkanLlamaBackend : InferenceBackend {

    private val engineMutex = Mutex()
    private val tokenLock = Any()

    override val versionInfo: EngineVersionInfo = EngineVersionInfo(
        backendId = "llamacpp-vulkan",
        engineName = "llama.cpp (ggml-vulkan)",
        engineVersion = "vulkan-1.x",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    )

    override val capabilities: EngineCapabilities
        get() {
            val available = VulkanNativeBridge.isLibraryAvailable()
            return EngineCapabilities(
                supportedBackends = if (available) {
                    setOf(AcceleratorBackendType.VULKAN_GPU, AcceleratorBackendType.CPU)
                } else {
                    emptySet()
                },
                supportsStreaming = true,
                supportsKvSession = true,
                supportsTokenize = true,
                supportsEmbedding = false,
                supportsNativeBenchmark = true,
                supportsGpuOffload = available,
                supportsNpuOffload = false,
                gpuUnavailableReason = if (available) null else {
                    VulkanNativeBridge.lastLoadError() ?: "libquantchat_vulkan.so is not bundled."
                },
                npuUnavailableReason = "Vulkan module targets GPU shaders only."
            )
        }

    private val _lifecycleState = MutableStateFlow<EngineLifecycleState>(
        EngineLifecycleState.Uninitialized
    )
    override val lifecycleState: StateFlow<EngineLifecycleState> = _lifecycleState.asStateFlow()

    @Volatile
    private var engineConfig: EngineConfig = EngineConfig(preferredBackend = AcceleratorBackendType.VULKAN_GPU)

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var activeModelConfig: ModelConfig? = null

    @Volatile
    private var activeModelDetails: EngineModelDetails? = null

    @Volatile
    private var activeSessionId: SessionId? = null

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
        val path = fileUri.removePrefix("file://")
        val file = File(path)
        if (!file.exists() || !file.isFile || file.length() < 24L) return@withContext false
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                bytes[0] == 'G'.code.toByte() &&
                    bytes[1] == 'G'.code.toByte() &&
                    bytes[2] == 'U'.code.toByte() &&
                    bytes[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                unloadLocked()
                if (!VulkanNativeBridge.isLibraryAvailable()) {
                    val err = EngineError.GpuBackendError(
                        reason = VulkanNativeBridge.lastLoadError() ?: "libquantchat_vulkan.so not bundled.",
                        fallbackEligible = true
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

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

                val validGguf = validateModelFile(model.fileUri)
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
                    val err = EngineError.GpuBackendError(
                        "Unable to open read-only FD for ${model.displayName}",
                        fallbackEligible = true,
                        cause = e
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

                val handle = try {
                    VulkanNativeBridge.nativeLoadModelFd(
                        fd = pfd.fd,
                        nCtx = model.contextSize,
                        nBatch = model.batchSize,
                        nThreads = model.threads,
                        nGpuLayers = model.gpuLayers,
                        useMmap = effectiveMmap,
                        useMlock = model.useMlock || engineConfig.enableMlock
                    )
                } catch (e: CancellationException) {
                    runCatching { pfd.close() }
                    MmapLifecycleRegistry.releaseMapping(lease)
                    _lifecycleState.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
                    throw e
                } catch (t: Throwable) {
                    runCatching { pfd.close() }
                    MmapLifecycleRegistry.releaseMapping(lease)
                    val err = EngineError.GpuBackendError(
                        t.localizedMessage ?: "Vulkan native load failed",
                        fallbackEligible = true,
                        cause = t
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                } finally {
                    runCatching { pfd.close() }
                }

                if (handle == 0L) {
                    MmapLifecycleRegistry.releaseMapping(lease)
                    val err = EngineError.GpuBackendError(
                        "ggml-vulkan failed to map ${model.displayName} (n_gpu_layers=${model.gpuLayers}).",
                        fallbackEligible = true
                    )
                    _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, err)
                    throw err
                }

                val details = EngineModelDetails(
                    description = model.displayName,
                    contextLength = model.contextSize,
                    gpuActive = model.gpuLayers > 0,
                    gpuFallbackReason = null
                )
                nativeHandle = handle
                activeModelConfig = model
                activeModelDetails = details
                activeSessionId = null
                activeMmapLease = lease

                val ready = EngineLifecycleState.Ready(
                    modelId = model.modelId,
                    contextId = (handle and 0x7FFFFFFFL).toInt().coerceAtLeast(1),
                    config = model,
                    activeSessionId = null,
                    modelDetails = details
                )
                _lifecycleState.value = ready
                Log.i(TAG, "Loaded Vulkan model ${model.displayName} ngl=${model.gpuLayers} n_ctx=${model.contextSize} mmap=$effectiveMmap")
                ready
            }
        }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        stopRequested = false
        val startMs = System.currentTimeMillis()
        val accumulated = StringBuilder()
        var chunkCount = 0

        val job = launch(Dispatchers.IO) {
            try {
                val generatedCount = engineMutex.withLock {
                    val handle = nativeHandle
                    val modelCfg = activeModelConfig
                    if (handle == 0L || modelCfg == null || modelCfg.modelId != request.modelId) {
                        throw EngineError.GpuBackendError(
                            "Target Vulkan model ${request.modelId.value} is not loaded.",
                            fallbackEligible = true
                        )
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
                    val formattedPrompt = formatChatInternal(handle, turns, "")
                        ?: PromptTemplateFormatter.formatFallback(
                            modelFamilyHint = request.modelFamilyHint,
                            systemPrompt = request.systemPrompt,
                            history = request.history,
                            userPrompt = request.userPrompt
                        )

                    val effectiveThreads = (request.threadsOverride ?: modelCfg.threads).coerceIn(1, 16)
                    val prevPriority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
                    applyLegalThreadPriority(request.threadPriority)
                    try {
                        val callback = VulkanTokenCallback { chunk ->
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
                        VulkanNativeBridge.nativeLaunchCompletion(
                            handlePtr = handle,
                            prompt = formattedPrompt,
                            nPredict = request.maxTokens,
                            nThreads = effectiveThreads,
                            temperature = request.temperature,
                            topK = request.topK,
                            topP = request.topP,
                            minP = request.minP,
                            repeatPenalty = request.repeatPenalty,
                            callback = callback
                        )
                    } finally {
                        if (prevPriority != null) {
                            runCatching { Process.setThreadPriority(prevPriority) }
                        }
                        if (nativeHandle == handle) {
                            _lifecycleState.value = EngineLifecycleState.Ready(
                                modelId = modelCfg.modelId,
                                contextId = (handle and 0x7FFFFFFFL).toInt().coerceAtLeast(1),
                                config = modelCfg,
                                activeSessionId = activeSessionId,
                                modelDetails = activeModelDetails
                            )
                        }
                    }
                }

                if (generatedCount < 0 && !stopRequested) {
                    throw EngineError.GpuBackendError(
                        "Vulkan decode failed with native code $generatedCount.",
                        fallbackEligible = true
                    )
                }

                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val (streamedRaw, streamedCount) = synchronized(tokenLock) {
                    accumulated.toString() to chunkCount
                }
                val finalText = PromptTemplateFormatter
                    .stripControlTokens(streamedRaw, request.stopTokens)
                    .trim()
                val effectiveTokens = maxOf(streamedCount, generatedCount.coerceAtLeast(0))
                val tokPerSec = if (effectiveTokens > 0) {
                    (effectiveTokens * 1000f) / elapsed.toFloat()
                } else 0f

                send(
                    GenerationEvent.Completed(
                        finalText = finalText.ifEmpty {
                            if (stopRequested) "[Generation stopped]" else "(Empty response from Vulkan model)"
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
            } catch (t: Throwable) {
                throw EngineError.GpuBackendError(
                    t.localizedMessage ?: "Vulkan inference error",
                    fallbackEligible = true,
                    cause = t
                )
            } finally {
                close()
            }
        }

        awaitClose {
            if (job.isActive) {
                job.cancel()
            }
        }
    }

    override suspend fun cancel() {
        stopRequested = true
        val handle = nativeHandle
        if (handle != 0L) {
            withContext(Dispatchers.IO) {
                runCatching { VulkanNativeBridge.nativeCancel(handle) }
            }
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
            val handle = nativeHandle
            val modelCfg = activeModelConfig
            if (handle == 0L || modelCfg == null) {
                throw EngineError.GpuBackendError("Load a GGUF model on Vulkan before benchmarking.")
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
                val targetDecode = decodeTokens.coerceIn(8, 64)
                val callback = VulkanTokenCallback { chunk ->
                    if (firstTokenAtMs == 0L && chunk.isNotEmpty()) {
                        firstTokenAtMs = System.currentTimeMillis()
                    }
                }
                val generated = VulkanNativeBridge.nativeLaunchCompletion(
                    handlePtr = handle,
                    prompt = syntheticPrompt,
                    nPredict = targetDecode,
                    nThreads = effectiveThreads,
                    temperature = 0f,
                    topK = 1,
                    topP = 1f,
                    minP = 0f,
                    repeatPenalty = 1f,
                    callback = callback
                )
                val endMs = System.currentTimeMillis()
                if (generated < 0) {
                    throw EngineError.GpuBackendError("Vulkan benchmark returned code $generated")
                }

                val actualPrefillTokens = runCatching {
                    VulkanNativeBridge.nativeTokenize(handle, syntheticPrompt).size
                }.getOrDefault(prefillTokens).coerceAtLeast(1)
                val actualDecodeTokens = generated.coerceAtLeast(1)

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

                BenchmarkResult(
                    prefillTokensPerSec = (actualPrefillTokens * 1000f) / prefillMs.toFloat(),
                    decodeTokensPerSec = (actualDecodeTokens * 1000f) / decodeMs.toFloat(),
                    prefillTokens = actualPrefillTokens,
                    decodeTokens = actualDecodeTokens,
                    elapsedMs = totalMs,
                    isNativeBench = true,
                    notes = String.format(
                        Locale.US,
                        "Measured Vulkan GPU (%dL · %dT)",
                        modelCfg.gpuLayers,
                        effectiveThreads
                    )
                )
            } finally {
                if (prevPriority != null) {
                    runCatching { Process.setThreadPriority(prevPriority) }
                }
                if (nativeHandle == handle) {
                    _lifecycleState.value = EngineLifecycleState.Ready(
                        modelId = modelCfg.modelId,
                        contextId = (handle and 0x7FFFFFFFL).toInt().coerceAtLeast(1),
                        config = modelCfg,
                        activeSessionId = activeSessionId,
                        modelDetails = activeModelDetails
                    )
                }
            }
        }
    }

    override suspend fun tokenize(text: String): IntArray = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val handle = nativeHandle
            if (handle == 0L) throw EngineError.BackendInitFailed("No active Vulkan context for tokenize.")
            VulkanNativeBridge.nativeTokenize(handle, text)
        }
    }

    override suspend fun detokenize(tokens: IntArray): String = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val handle = nativeHandle
            if (handle == 0L) throw EngineError.BackendInitFailed("No active Vulkan context for detokenize.")
            VulkanNativeBridge.nativeDetokenize(handle, tokens)
        }
    }

    override suspend fun embed(text: String): FloatArray {
        throw EngineError.UnsupportedCapability(
            "Embedding",
            "Vulkan backend currently targets generative completion."
        )
    }

    override suspend fun saveSession(
        sessionId: SessionId,
        path: String,
        maxTokens: Int
    ): SessionSaveResult = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val handle = nativeHandle
            if (handle == 0L) throw EngineError.BackendInitFailed("No active Vulkan context to save session.")
            val ok = runCatching { VulkanNativeBridge.nativeSaveSession(handle, path) }.getOrDefault(false)
            if (!ok) {
                throw EngineError.SessionCorrupted(sessionId, "Vulkan saveSession failed.")
            }
            activeSessionId = sessionId
            SessionSaveResult(sessionId = sessionId, path = path, tokensSaved = maxTokens)
        }
    }

    override suspend fun loadSession(sessionId: SessionId, path: String): Int =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                val handle = nativeHandle
                if (handle == 0L) throw EngineError.BackendInitFailed("No active Vulkan context to load session.")
                val count = runCatching { VulkanNativeBridge.nativeLoadSession(handle, path) }.getOrDefault(-1)
                if (count < 0) {
                    throw EngineError.SessionCorrupted(sessionId, "Vulkan loadSession returned $count.")
                }
                activeSessionId = sessionId
                count
            }
        }

    override suspend fun formatChat(turns: List<ChatTurn>, template: String): String? =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                val handle = nativeHandle
                if (handle == 0L) return@withLock null
                formatChatInternal(handle, turns, template)
            }
        }

    private fun formatChatInternal(handle: Long, turns: List<ChatTurn>, template: String): String? {
        val roles = turns.map { it.role }.toTypedArray()
        val contents = turns.map { it.content }.toTypedArray()
        return runCatching {
            VulkanNativeBridge.nativeFormatChat(handle, roles, contents, template)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun applyLegalThreadPriority(priority: InferenceThreadPriority) {
        val target = when (priority) {
            InferenceThreadPriority.BACKGROUND -> Process.THREAD_PRIORITY_BACKGROUND
            InferenceThreadPriority.DEFAULT -> Process.THREAD_PRIORITY_DEFAULT
            InferenceThreadPriority.FAVORABLE -> Process.THREAD_PRIORITY_MORE_FAVORABLE
        }
        runCatching { Process.setThreadPriority(target) }
    }

    private fun unloadLocked() {
        val prevModel = activeModelConfig?.modelId
        val handle = nativeHandle
        val leaseToRelease = activeMmapLease
        if (handle != 0L || leaseToRelease != null) {
            _lifecycleState.value = EngineLifecycleState.Unloading(prevModel)
            nativeHandle = 0L
            if (handle != 0L) {
                runCatching { VulkanNativeBridge.nativeUnload(handle) }
            }
        }
        activeMmapLease = null
        MmapLifecycleRegistry.releaseMapping(leaseToRelease)
        activeModelConfig = null
        activeModelDetails = null
        activeSessionId = null
        _lifecycleState.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
    }
}
