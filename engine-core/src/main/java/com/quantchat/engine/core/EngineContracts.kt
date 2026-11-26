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

enum class AcceleratorPreference(val label: String, val description: String) {
    AUTO(
        "Auto (Vulkan → CPU)",
        "Probe Vulkan GPU automatically; safely fall back to CPU on any incompatibility."
    ),
    PREFER_VULKAN(
        "Prefer Vulkan",
        "Always probe and attempt Vulkan GPU first; still falls back to CPU if unsafe."
    ),
    CPU_ONLY(
        "CPU Only",
        "Bypass GPU offload and run exclusively on tuned CPU clusters."
    )
}

enum class VulkanProbeStatus(val label: String) {
    AVAILABLE("Vulkan Ready"),
    UNSUPPORTED_DEVICE("No Device Vulkan"),
    DRIVER_CAPABILITY_MISSING("Driver Capability Missing"),
    NATIVE_LIBRARY_MISSING("Native Lib Not Bundled"),
    PROBE_FAILED("Probe Failed")
}

data class VulkanProbeResult(
    val status: VulkanProbeStatus,
    val nativeLibraryLoaded: Boolean = false,
    val systemVulkanSupported: Boolean = false,
    val systemVulkanVersion: String = "Unavailable",
    val deviceName: String? = null,
    val vendorLabel: String? = null,
    val driverVersionLabel: String? = null,
    val vulkanApiVersionLabel: String? = null,
    val supports16BitStorage: Boolean = false,
    val supportsFp16Compute: Boolean = false,
    val supportsCooperativeMatrix: Boolean = false,
    val deviceLocalHeapMb: Long = 0L,
    val hostVisibleHeapMb: Long = 0L,
    val recommendedMaxOffloadMb: Long = 0L,
    val bufferTransferSelfTestPassed: Boolean = false,
    val operatorSelfTestPassed: Boolean = false,
    val selfTestSummary: String = "Not probed",
    val reason: String? = null,
    val probedAtMs: Long = System.currentTimeMillis()
) {
    val isUsableForInference: Boolean
        get() = status == VulkanProbeStatus.AVAILABLE &&
            nativeLibraryLoaded &&
            bufferTransferSelfTestPassed &&
            operatorSelfTestPassed
}

enum class NpuVendorFamily(val label: String, val stackName: String) {
    QUALCOMM_HEXAGON("Qualcomm Hexagon NPU", "Qualcomm AI Engine Direct (QNN HTP)"),
    MEDIATEK_APU("MediaTek APU", "MediaTek NeuroPilot"),
    GOOGLE_TENSOR_TPU("Google Tensor TPU", "Tensor NNAPI / LiteRT Delegate"),
    SAMSUNG_EXYNOS_NPU("Samsung Exynos NPU", "Exynos NPU / ENN SDK"),
    GENERIC_NNAPI("Android NNAPI", "Android NNAPI Runtime"),
    NONE("No Dedicated NPU", "CPU / GPU Runtime Only")
}

enum class NpuProbeStatus(val label: String) {
    RUNTIME_READY("NPU Runtime Accessible"),
    HARDWARE_DETECTED_RUNTIME_UNBUNDLED("NPU Detected · Runtime Unbundled"),
    NNAPI_ONLY_LEGACY("NNAPI Only (Legacy)"),
    UNSUPPORTED_DEVICE("No Dedicated NPU Detected"),
    PROBE_FAILED("Probe Failed")
}

enum class ModelArtifactFormat(val label: String) {
    GGUF("GGUF (llama.cpp)"),
    QNN_CONTEXT_BIN("QNN Context Binary (.bin)"),
    QNN_DLC("Qualcomm DLC (.dlc)"),
    TFLITE_DELEGATE("TFLite / LiteRT (.tflite)"),
    UNKNOWN("Unknown Artifact")
}

data class NpuNativeRuntimeProbe(
    val nativeProbeExecuted: Boolean = false,
    val qnnHtpAccessible: Boolean = false,
    val cdspRpcAccessible: Boolean = false,
    val qnnError: String? = null,
    val cdspError: String? = null
)

data class NpuProbeResult(
    val status: NpuProbeStatus,
    val vendorFamily: NpuVendorFamily,
    val chipsetLabel: String,
    val socManufacturer: String = "",
    val hexagonGenerationHint: String? = null,
    val supportsNnapi: Boolean = false,
    val nnapiDeprecatedOnOs: Boolean = false,
    val qnnRuntimePresent: Boolean = false,
    val cdspRpcAccessible: Boolean = false,
    val supportedPrecisionHints: List<String> = emptyList(),
    val summary: String,
    val reason: String? = null,
    val probedAtMs: Long = System.currentTimeMillis()
) {
    val isHardwareLikelyPresent: Boolean
        get() = vendorFamily != NpuVendorFamily.NONE

    val isVendorRuntimeReady: Boolean
        get() = status == NpuProbeStatus.RUNTIME_READY && qnnRuntimePresent
}

enum class NpuCompatibilityVerdict(val label: String) {
    COMPATIBLE("NPU Compatible"),
    INCOMPATIBLE_FORMAT("GGUF Gated · CPU/Vulkan"),
    RUNTIME_UNAVAILABLE("NPU Runtime Unbundled"),
    UNSUPPORTED_DEVICE("NPU Not Available")
}

data class NpuCompatibilityReport(
    val modelId: String,
    val artifactFormat: ModelArtifactFormat,
    val compatibleWithNpu: Boolean,
    val verdictCode: NpuCompatibilityVerdict,
    val operatorRequirementsSummary: String,
    val blockingReason: String?,
    val routedFallbackTarget: String
)

data class BackendRuntimeStatus(
    val activeBackend: AcceleratorBackendType = AcceleratorBackendType.CPU,
    val requestedPreference: AcceleratorPreference = AcceleratorPreference.AUTO,
    val requestedGpuLayers: Int = 0,
    val appliedGpuLayers: Int = 0,
    val totalModelLayers: Int = 0,
    val fallbackOccurred: Boolean = false,
    val statusSummary: String = "CPU Runtime",
    val fallbackReason: String? = null,
    val activeDeviceLabel: String = "CPU"
)

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

    class GpuBackendError(
        val reason: String,
        val fallbackEligible: Boolean = true,
        cause: Throwable? = null
    ) : EngineError(reason, cause)

    class NpuBackendError(
        val reason: String,
        val fallbackEligible: Boolean = true,
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

    class ThermalShutdown(
        val thermalStatus: Int,
        val reason: String
    ) : EngineError("Thermal shutdown (status=$thermalStatus): $reason")

    class FileLocked(
        val fileName: String,
        val reason: String
    ) : EngineError("$fileName is currently locked: $reason")

    class InsufficientStorage(
        val requiredMb: Long,
        val availableMb: Long,
        val reason: String
    ) : EngineError("Insufficient storage (~${requiredMb}MB needed, ${availableMb}MB available): $reason")
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
    val totalLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val enableEmbedding: Boolean = false
)

data class ChatTurn(
    val role: String,
    val content: String
)

enum class CpuScheduleMode(val label: String, val scenarioHint: String) {
    ECO("Eco", "少量效率核 · 后台轻量"),
    BALANCED("Balanced", "混合核心 · 普通聊天"),
    PERFORMANCE("Performance", "高性能核 · 长文本"),
    THERMAL_SAFE("Thermal Safe", "动态降线程 · 高温保护")
}

enum class WorkloadClass(val label: String) {
    LIGHT("Light"),
    STANDARD("Standard"),
    LONG_CONTEXT("Long Context")
}

enum class InferenceThreadPriority(val label: String) {
    BACKGROUND("Background"),
    DEFAULT("Standard"),
    FAVORABLE("Favorable")
}

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
    val threadsOverride: Int? = null,
    val threadPriority: InferenceThreadPriority = InferenceThreadPriority.DEFAULT,
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
