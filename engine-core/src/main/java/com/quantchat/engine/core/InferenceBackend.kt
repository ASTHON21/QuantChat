package com.quantchat.engine.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

interface InferenceBackend {
    val lifecycleState: StateFlow<EngineLifecycleState>
    val versionInfo: EngineVersionInfo
    val capabilities: EngineCapabilities

    suspend fun initialize(config: EngineConfig)
    suspend fun validateModelFile(fileUri: String): Boolean
    suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready
    fun generate(request: GenerationRequest): Flow<GenerationEvent>
    suspend fun cancel()
    suspend fun unload()
    suspend fun benchmark(prefillTokens: Int = 64, decodeTokens: Int = 32): BenchmarkResult

    suspend fun tokenize(text: String): IntArray
    suspend fun detokenize(tokens: IntArray): String
    suspend fun embed(text: String): FloatArray

    suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int = 2048): SessionSaveResult
    suspend fun loadSession(sessionId: SessionId, path: String): Int
    suspend fun formatChat(turns: List<ChatTurn>, template: String = ""): String?
}

object PromptTemplateFormatter {

    fun buildTurns(
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String
    ): List<ChatTurn> = buildList {
        if (systemPrompt.isNotBlank()) {
            add(ChatTurn(role = "system", content = systemPrompt))
        }
        addAll(history.filter { it.content.isNotBlank() })
        if (userPrompt.isNotBlank()) {
            add(ChatTurn(role = "user", content = userPrompt))
        }
    }

    fun formatFallback(
        modelFamilyHint: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String
    ): String = buildString {
        val hint = modelFamilyHint.lowercase(Locale.US)
        when {
            hint.contains("gemma") -> {
                if (systemPrompt.isNotBlank()) {
                    append("<start_of_turn>user\n").append(systemPrompt).append("\n\n")
                }
                history.forEachIndexed { idx, msg ->
                    val roleTag = if (msg.role == "user") "user" else "model"
                    if (idx == 0 && msg.role == "user" && systemPrompt.isNotBlank()) {
                        append(msg.content).append("<end_of_turn>\n")
                    } else {
                        if (idx == 0 && systemPrompt.isNotBlank()) {
                            append("<end_of_turn>\n")
                        }
                        append("<start_of_turn>").append(roleTag).append("\n")
                        append(msg.content).append("<end_of_turn>\n")
                    }
                }
                if (history.isEmpty() && systemPrompt.isNotBlank()) {
                    append(userPrompt).append("<end_of_turn>\n")
                } else {
                    append("<start_of_turn>user\n").append(userPrompt).append("<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }

            hint.contains("phi") -> {
                if (systemPrompt.isNotBlank()) {
                    append("<|system|>\n").append(systemPrompt).append("<|end|>\n")
                }
                history.forEach { msg ->
                    val roleTag = if (msg.role == "user") "user" else "assistant"
                    append("<|").append(roleTag).append("|>\n").append(msg.content).append("<|end|>\n")
                }
                append("<|user|>\n").append(userPrompt).append("<|end|>\n<|assistant|>\n")
            }

            else -> {
                // ChatML (Qwen3, Qwen3.5, and general GGUF fallback)
                if (systemPrompt.isNotBlank()) {
                    append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
                }
                history.forEach { msg ->
                    append("<|im_start|>").append(msg.role).append("\n")
                    append(msg.content).append("<|im_end|>\n")
                }
                append("<|im_start|>user\n").append(userPrompt).append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }
        }
    }

    fun stripControlTokens(raw: String, stopTokens: List<String>): String {
        var text = raw
        for (token in stopTokens) {
            text = text.replace(token, "")
        }
        return text
    }
}
