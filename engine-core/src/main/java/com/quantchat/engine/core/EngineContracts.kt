package com.quantchat.engine.core

@JvmInline
value class ModelId(val value: String)

@JvmInline
value class SessionId(val value: String)

enum class AcceleratorBackendType(val label: String) {
    CPU("CPU"),
    VULKAN_GPU("Vulkan GPU"),
    OPENCL_GPU("OpenCL GPU"),
    NPU("NPU")
}

data class EngineVersionInfo(
    val backendId: String,
    val engineName: String,
    val engineVersion: String,
    val abi: String
)

data class EngineCapabilities(
    val supportedBackends: Set<AcceleratorBackendType>,
    val supportsStreaming: Boolean,
    val supportsKvSession: Boolean,
    val supportsTokenize: Boolean,
    val supportsEmbedding: Boolean,
    val supportsNativeBenchmark: Boolean,
    val supportsGpuOffload: Boolean,
    val supportsNpuOffload: Boolean,
    val gpuUnavailableReason: String? = null,
    val npuUnavailableReason: String? = null
)

data class EngineModelDetails(
    val architecture: String? = null,
    val description: String? = null,
    val parameterCount: Long? = null,
    val contextLength: Int? = null,
    val embeddingSize: Int? = null,
    val gpuActive: Boolean = false,
    val gpuFallbackReason: String? = null,
    val rawMetadata: Map<String, String> = emptyMap()
)

sealed interface EngineLifecycleState {
    data object Uninitialized : EngineLifecycleState

    data class Initialized(
        val versionInfo: EngineVersionInfo,
        val capabilities: EngineCapabilities
    ) : EngineLifecycleState

    data class LoadingModel(
        val modelId: ModelId,
        val displayName: String
    ) : EngineLifecycleState

    data class Ready(
        val modelId: ModelId,
        val contextId: Int,
        val config: ModelConfig,
        val activeSessionId: SessionId? = null,
        val modelDetails: EngineModelDetails? = null
    ) : EngineLifecycleState

    data class Generating(
        val modelId: ModelId,
        val sessionId: SessionId? = null,
        val tokensEmitted: Int = 0
    ) : EngineLifecycleState

    data class Benchmarking(
        val modelId: ModelId
    ) : EngineLifecycleState

    data class Unloading(
        val modelId: ModelId?
    ) : EngineLifecycleState

    data class Faulted(
        val modelId: ModelId? = null,
        val error: EngineError
    ) : EngineLifecycleState
}

sealed class EngineError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    class InvalidModelFormat(
        val fileName: String,
        val reason: String
    ) : EngineError("$fileName is not a valid model artifact: $reason")

    class InsufficientMemory(
        val requiredMb: Long,
        val availableMb: Long,
        val reason: String
    ) : EngineError("Insufficient memory (~${requiredMb}MB needed, ${availableMb}MB available): $reason")

    class BackendInitFailed(
        val reason: String,
        cause: Throwable? = null
    ) : EngineError(reason, cause)

    class ContextOverflow(
        val promptTokens: Int,
        val contextLimit: Int
    ) : EngineError("Prompt ($promptTokens tokens) exceeds context window ($contextLimit tokens).")

    class GenerationFailed(
        val reason: String,
        cause: Throwable? = null
    ) : EngineError(reason, cause)

    class SessionCorrupted(
        val sessionId: SessionId,
        val reason: String,
        cause: Throwable? = null
    ) : EngineError("Session ${sessionId.value} error: $reason", cause)

    class UnsupportedCapability(
        val capability: String,
        val reason: String
    ) : EngineError("$capability is not supported: $reason")

    class Cancelled(
        val reason: String = "Operation cancelled"
    ) : EngineError(reason)
}

data class EngineConfig(
    val preferredBackend: AcceleratorBackendType = AcceleratorBackendType.CPU,
    val enableMmap: Boolean = true,
    val enableMlock: Boolean = false
)

data class ModelConfig(
    val modelId: ModelId,
    val displayName: String,
    val filePath: String,
    val fileUri: String,
    val contextSize: Int,
    val batchSize: Int = 512,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val enableEmbedding: Boolean = false
)

data class ChatTurn(
    val role: String,
    val content: String
)

data class GenerationRequest(
    val modelId: ModelId,
    val sessionId: SessionId? = null,
    val systemPrompt: String,
    val history: List<ChatTurn>,
    val userPrompt: String,
    val modelFamilyHint: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.3f,
    val topK: Int = 40,
    val topP: Float = 0.92f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val stopTokens: List<String> = DEFAULT_STOP_TOKENS
) {
    companion object {
        val DEFAULT_STOP_TOKENS = listOf(
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

sealed interface GenerationEvent {
    data class TokenDelta(
        val accumulatedText: String,
        val tokenCount: Int
    ) : GenerationEvent

    data class Completed(
        val finalText: String,
        val elapsedMs: Long,
        val tokenCount: Int,
        val tokensPerSecond: Float,
        val wasStopped: Boolean
    ) : GenerationEvent
}

data class BenchmarkResult(
    val prefillTokensPerSec: Float,
    val decodeTokensPerSec: Float,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val elapsedMs: Long,
    val isNativeBench: Boolean,
    val notes: String
)

data class SessionSaveResult(
    val sessionId: SessionId,
    val path: String,
    val tokensSaved: Int
)
