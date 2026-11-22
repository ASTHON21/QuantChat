package com.example.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.util.Locale

private const val TAG = "LocalLlamaRepo"

class LocalLlamaRepository(context: Context) {

    private val llama = LlamaAndroid(context.contentResolver)
    private val engineMutex = Mutex()
    private val tokenLock = Any()

    @Volatile
    private var activeContextId: Int? = null

    @Volatile
    private var activeTokenSink: ((String) -> Unit)? = null

    @Volatile
    private var stopRequested: Boolean = false

    suspend fun loadModel(
        weightFile: LocalWeightFile,
        diskFile: File,
        settings: InferenceSettings
    ): Int = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            unloadModelLocked()

            if (!diskFile.exists() || diskFile.length() < 1024L) {
                throw IllegalStateException("Model file ${weightFile.displayFileName} is missing or incomplete.")
            }

            val uri = Uri.fromFile(diskFile)
            val validGguf = runCatching { llama.isGGUF(uri) }.getOrDefault(false)
            if (!validGguf) {
                throw IllegalArgumentException("${weightFile.displayFileName} failed GGUF magic header verification.")
            }

            val pfd = ParcelFileDescriptor.open(diskFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val fd = pfd.detachFd()

            val engineParams = mapOf<String, Any>(
                "model" to uri.toString(),
                "model_fd" to fd,
                "n_ctx" to settings.contextSize,
                "n_batch" to 512,
                "n_threads" to settings.threads,
                "n_gpu_layers" to 0,
                "use_mmap" to true,
                "use_mlock" to false,
                "embedding" to false
            )

            val result = llama.startEngine(engineParams) { tokenChunk ->
                activeTokenSink?.invoke(tokenChunk)
            } ?: run {
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                throw IllegalStateException(
                    "Native llama.cpp failed to initialize ${weightFile.displayFileName}. Verify device RAM and 64-bit ABI support."
                )
            }

            val ctxId = (result["contextId"] as? Int)
                ?: throw IllegalStateException("Native engine returned an invalid context ID.")
            activeContextId = ctxId
            Log.d(TAG, "Loaded ${weightFile.displayFileName} into native contextId=$ctxId (n_ctx=${settings.contextSize}, threads=${settings.threads})")
            ctxId
        }
    }

    fun streamCompletion(
        contextId: Int,
        weightFile: LocalWeightFile,
        history: List<ChatMessage>,
        userPrompt: String,
        settings: InferenceSettings
    ): Flow<LocalStreamEvent> = channelFlow {
        stopRequested = false
        val startMs = System.currentTimeMillis()
        val accumulated = StringBuilder()
        var chunkCount = 0

        activeTokenSink = { chunk ->
            if (chunk.isNotEmpty()) {
                val (cleaned, count) = synchronized(tokenLock) {
                    chunkCount++
                    accumulated.append(chunk)
                    stripControlTokens(accumulated.toString()) to chunkCount
                }
                trySend(LocalStreamEvent.Delta(accumulatedText = cleaned, tokenCount = count))
            }
        }

        val job = launch(Dispatchers.IO) {
            try {
                val formattedPrompt = formatPrompt(
                    contextId = contextId,
                    weightFile = weightFile,
                    systemPrompt = settings.systemPrompt,
                    history = history,
                    userPrompt = userPrompt
                )

                val completionParams = mapOf<String, Any>(
                    "prompt" to formattedPrompt,
                    "n_predict" to settings.maxTokens,
                    "n_threads" to settings.threads,
                    "temperature" to settings.temperature.toDouble(),
                    "top_k" to 40,
                    "top_p" to 0.92,
                    "min_p" to 0.05,
                    "penalty_repeat" to 1.1,
                    "emit_partial_completion" to true,
                    "stop" to STOP_TOKENS
                )

                val completionResult = engineMutex.withLock {
                    if (activeContextId != contextId) {
                        throw IllegalStateException("Local model context was unloaded before inference started.")
                    }
                    llama.launchCompletion(contextId, completionParams)
                }

                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val (streamedRaw, streamedCount) = synchronized(tokenLock) {
                    accumulated.toString() to chunkCount
                }

                if (completionResult == null && streamedRaw.isBlank() && !stopRequested) {
                    throw IllegalStateException(
                        "Local llama.cpp completion failed for ${weightFile.displayFileName}. Try reducing context size or reloading the model."
                    )
                }

                val resultText = (completionResult?.get("text") as? String).orEmpty()
                val rawFinal = streamedRaw.ifBlank { resultText }
                val finalText = stripControlTokens(rawFinal).trim()
                val predictedCount = (completionResult?.get("tokens_predicted") as? Number)?.toInt() ?: 0
                val effectiveTokens = maxOf(streamedCount, predictedCount)
                val tokPerSec = if (effectiveTokens > 0) {
                    (effectiveTokens * 1000f) / elapsed.toFloat()
                } else 0f

                send(
                    LocalStreamEvent.Completed(
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
            } catch (e: Exception) {
                Log.e(TAG, "Local inference failed", e)
                throw e
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

    suspend fun stopGeneration() {
        stopRequested = true
        val ctxId = activeContextId ?: return
        withContext(Dispatchers.IO) {
            runCatching { llama.stopCompletion(ctxId) }
                .onFailure { Log.w(TAG, "stopCompletion warning", it) }
        }
    }

    suspend fun unloadModel() {
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                unloadModelLocked()
            }
        }
    }

    private suspend fun unloadModelLocked() {
        val ctxId = activeContextId
        activeContextId = null
        activeTokenSink = null
        if (ctxId != null) {
            runCatching {
                llama.stopCompletion(ctxId)
                llama.releaseContext(ctxId)
            }.onFailure { Log.w(TAG, "releaseContext warning", it) }
        }
    }

    private suspend fun formatPrompt(
        contextId: Int,
        weightFile: LocalWeightFile,
        systemPrompt: String,
        history: List<ChatMessage>,
        userPrompt: String
    ): String {
        val recentTurns = history
            .filter { !it.isError && it.text.isNotBlank() }
            .takeLast(6)

        val chatMaps = buildList {
            if (systemPrompt.isNotBlank()) {
                add(mapOf("role" to "system", "content" to systemPrompt))
            }
            recentTurns.forEach { msg ->
                add(mapOf("role" to msg.role.raw, "content" to msg.text))
            }
            add(mapOf("role" to "user", "content" to userPrompt))
        }

        val nativeFormatted = runCatching {
            llama.getFormattedChat(contextId, chatMaps, "").firstOrNull()
        }.getOrNull()

        if (!nativeFormatted.isNullOrBlank()) {
            return nativeFormatted
        }

        return buildFallbackTemplatePrompt(
            modelName = "${weightFile.repoId} ${weightFile.displayFileName}".lowercase(Locale.US),
            systemPrompt = systemPrompt,
            history = recentTurns,
            userPrompt = userPrompt
        )
    }

    private fun buildFallbackTemplatePrompt(
        modelName: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        userPrompt: String
    ): String = buildString {
        when {
            modelName.contains("gemma") -> {
                if (systemPrompt.isNotBlank()) {
                    append("<start_of_turn>user\n").append(systemPrompt).append("\n\n")
                }
                history.forEachIndexed { idx, msg ->
                    val roleTag = if (msg.role == ChatRole.USER) "user" else "model"
                    if (idx == 0 && msg.role == ChatRole.USER && systemPrompt.isNotBlank()) {
                        append(msg.text).append("<end_of_turn>\n")
                    } else {
                        if (idx == 0 && systemPrompt.isNotBlank()) {
                            append("<end_of_turn>\n")
                        }
                        append("<start_of_turn>").append(roleTag).append("\n")
                        append(msg.text).append("<end_of_turn>\n")
                    }
                }
                if (history.isEmpty() && systemPrompt.isNotBlank()) {
                    append(userPrompt).append("<end_of_turn>\n")
                } else {
                    append("<start_of_turn>user\n").append(userPrompt).append("<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }

            modelName.contains("phi") -> {
                if (systemPrompt.isNotBlank()) {
                    append("<|system|>\n").append(systemPrompt).append("<|end|>\n")
                }
                history.forEach { msg ->
                    val roleTag = if (msg.role == ChatRole.USER) "user" else "assistant"
                    append("<|").append(roleTag).append("|>\n").append(msg.text).append("<|end|>\n")
                }
                append("<|user|>\n").append(userPrompt).append("<|end|>\n<|assistant|>\n")
            }

            else -> {
                // ChatML (Qwen3, Qwen3.5, and general GGUF fallback)
                if (systemPrompt.isNotBlank()) {
                    append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
                }
                history.forEach { msg ->
                    append("<|im_start|>").append(msg.role.raw).append("\n")
                    append(msg.text).append("<|im_end|>\n")
                }
                append("<|im_start|>user\n").append(userPrompt).append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }
        }
    }

    private fun stripControlTokens(raw: String): String {
        var text = raw
        for (token in STOP_TOKENS) {
            text = text.replace(token, "")
        }
        return text
    }

    companion object {
        private val STOP_TOKENS = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<|endoftext|>",
            "<end_of_turn>",
            "<|end|>",
            "<|eot_id|>",
            "</s>"
        )
    }
}
