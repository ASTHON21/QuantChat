package com.quantchat.engine.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * Vendor-agnostic adapter hook for future NPU SDK integrations
 * (e.g., Qualcomm AI Engine Direct QNN HTP, MediaTek NeuroPilot, LiteRT/ExecuTorch).
 */
interface NpuVendorDriverAdapter {
    val vendorFamily: NpuVendorFamily
    val driverName: String
    fun isRuntimeBundled(): Boolean
    fun evaluateArtifact(
        filePath: String,
        format: ModelArtifactFormat
    ): Pair<Boolean, String?>
}

/**
 * Clean NPU InferenceBackend abstraction and GGUF compatibility gate.
 * Rejects incompatible .gguf artifacts or unbundled vendor runtimes safely with
 * [EngineError.NpuBackendError] so [ResilientFailoverBackend] keeps inference on Vulkan GPU / CPU.
 */
class NpuInferenceBackend(
    private val driverAdapter: NpuVendorDriverAdapter? = null
) : InferenceBackend {

    override val versionInfo: EngineVersionInfo = EngineVersionInfo(
        backendId = "npu-gate",
        engineName = driverAdapter?.driverName ?: "NPU Compatibility Gate",
        engineVersion = "1.3.0-contract",
        abi = "arm64-v8a"
    )

    override val capabilities: EngineCapabilities
        get() {
            val bundled = driverAdapter?.isRuntimeBundled() == true
            return EngineCapabilities(
                supportedBackends = if (bundled) setOf(AcceleratorBackendType.NPU) else emptySet(),
                supportsStreaming = bundled,
                supportsKvSession = false,
                supportsTokenize = false,
                supportsEmbedding = false,
                supportsNativeBenchmark = bundled,
                supportsGpuOffload = false,
                supportsNpuOffload = bundled,
                npuUnavailableReason = if (bundled) {
                    null
                } else {
                    "Vendor NPU runtime (e.g., Qualcomm QNN HTP / NeuroPilot) is not bundled; GGUF models use Vulkan GPU or CPU."
                }
            )
        }

    private val _lifecycleState = MutableStateFlow<EngineLifecycleState>(
        EngineLifecycleState.Initialized(versionInfo, capabilities)
    )
    override val lifecycleState: StateFlow<EngineLifecycleState> = _lifecycleState.asStateFlow()

    fun detectArtifactFormat(filePath: String): ModelArtifactFormat {
        val lower = filePath.lowercase(Locale.US)
        return when {
            lower.endsWith(".gguf") -> ModelArtifactFormat.GGUF
            lower.endsWith(".bin") || lower.endsWith(".qnn") -> ModelArtifactFormat.QNN_CONTEXT_BIN
            lower.endsWith(".dlc") -> ModelArtifactFormat.QNN_DLC
            lower.endsWith(".tflite") -> ModelArtifactFormat.TFLITE_DELEGATE
            else -> ModelArtifactFormat.UNKNOWN
        }
    }

    fun evaluateArtifactCompatibility(
        modelId: String,
        filePath: String,
        probeResult: NpuProbeResult? = null,
        routedFallbackTarget: String = "CPU"
    ): NpuCompatibilityReport {
        val format = detectArtifactFormat(filePath)
        val operatorSummary = "NPU execution requires static-shape vendor context binaries (.bin/.dlc) with INT8/INT4 quantized ops."

        if (format == ModelArtifactFormat.GGUF) {
            return NpuCompatibilityReport(
                modelId = modelId,
                artifactFormat = format,
                compatibleWithNpu = false,
                verdictCode = NpuCompatibilityVerdict.INCOMPATIBLE_FORMAT,
                operatorRequirementsSummary = operatorSummary,
                blockingReason = "GGUF dynamic-shape weights are executed by llama.cpp (Vulkan GPU / CPU) and cannot run natively on Hexagon/NPU without pre-compiled QNN/NeuroPilot context conversion.",
                routedFallbackTarget = routedFallbackTarget
            )
        }

        if (probeResult != null && !probeResult.isHardwareLikelyPresent && !probeResult.supportsNnapi) {
            return NpuCompatibilityReport(
                modelId = modelId,
                artifactFormat = format,
                compatibleWithNpu = false,
                verdictCode = NpuCompatibilityVerdict.UNSUPPORTED_DEVICE,
                operatorRequirementsSummary = operatorSummary,
                blockingReason = probeResult.reason ?: "No supported NPU hardware detected on this device.",
                routedFallbackTarget = routedFallbackTarget
            )
        }

        val adapter = driverAdapter
        if (adapter == null || !adapter.isRuntimeBundled()) {
            val vendorStack = probeResult?.vendorFamily?.stackName ?: "Vendor NPU SDK"
            return NpuCompatibilityReport(
                modelId = modelId,
                artifactFormat = format,
                compatibleWithNpu = false,
                verdictCode = NpuCompatibilityVerdict.RUNTIME_UNAVAILABLE,
                operatorRequirementsSummary = operatorSummary,
                blockingReason = "$vendorStack runtime libraries are not bundled in this build.",
                routedFallbackTarget = routedFallbackTarget
            )
        }

        val (adapterOk, adapterReason) = adapter.evaluateArtifact(filePath, format)
        return if (adapterOk) {
            NpuCompatibilityReport(
                modelId = modelId,
                artifactFormat = format,
                compatibleWithNpu = true,
                verdictCode = NpuCompatibilityVerdict.COMPATIBLE,
                operatorRequirementsSummary = operatorSummary,
                blockingReason = null,
                routedFallbackTarget = adapter.driverName
            )
        } else {
            NpuCompatibilityReport(
                modelId = modelId,
                artifactFormat = format,
                compatibleWithNpu = false,
                verdictCode = NpuCompatibilityVerdict.INCOMPATIBLE_FORMAT,
                operatorRequirementsSummary = operatorSummary,
                blockingReason = adapterReason ?: "Artifact failed vendor NPU operator/precision verification.",
                routedFallbackTarget = routedFallbackTarget
            )
        }
    }

    override suspend fun initialize(config: EngineConfig) {
        _lifecycleState.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
    }

    override suspend fun validateModelFile(fileUri: String): Boolean {
        val format = detectArtifactFormat(fileUri)
        if (format == ModelArtifactFormat.GGUF) return false
        val adapter = driverAdapter ?: return false
        return adapter.isRuntimeBundled() && adapter.evaluateArtifact(fileUri, format).first
    }

    override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
        val report = evaluateArtifactCompatibility(
            modelId = model.modelId.value,
            filePath = model.filePath
        )
        val error = EngineError.NpuBackendError(
            reason = report.blockingReason ?: "NPU backend cannot load ${model.displayName}.",
            fallbackEligible = true
        )
        _lifecycleState.value = EngineLifecycleState.Faulted(model.modelId, error)
        throw error
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        throw EngineError.NpuBackendError(
            reason = "NPU generation is gated for GGUF models; use Vulkan GPU or CPU backend.",
            fallbackEligible = true
        )
    }

    override suspend fun cancel() = Unit

    override suspend fun unload() {
        _lifecycleState.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
    }

    override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int): BenchmarkResult {
        throw EngineError.NpuBackendError(
            reason = "NPU runtime benchmark requires a loaded vendor-compiled NPU context.",
            fallbackEligible = true
        )
    }

    override suspend fun tokenize(text: String): IntArray {
        throw EngineError.UnsupportedCapability("NPU tokenize", "Tokenization runs on CPU.")
    }

    override suspend fun detokenize(tokens: IntArray): String {
        throw EngineError.UnsupportedCapability("NPU detokenize", "Detokenization runs on CPU.")
    }

    override suspend fun embed(text: String): FloatArray {
        throw EngineError.UnsupportedCapability("NPU embed", "Embeddings are gated on NPU.")
    }

    override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int): SessionSaveResult {
        throw EngineError.UnsupportedCapability("NPU saveSession", "KV-cache sessions are not exposed for NPU.")
    }

    override suspend fun loadSession(sessionId: SessionId, path: String): Int {
        throw EngineError.UnsupportedCapability("NPU loadSession", "KV-cache sessions are not exposed for NPU.")
    }

    override suspend fun formatChat(turns: List<ChatTurn>, template: String): String? = null
}
