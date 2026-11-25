package com.quantchat.engine.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ResilientFailoverBackend(
    private val fallbackCpuBackend: InferenceBackend,
    private val primaryGpuBackend: InferenceBackend? = null,
    private val probeProvider: suspend () -> VulkanProbeResult = {
        VulkanProbeResult(
            status = VulkanProbeStatus.NATIVE_LIBRARY_MISSING,
            reason = "No Vulkan backend configured."
        )
    },
    private val npuBackend: NpuInferenceBackend? = null
) : InferenceBackend {

    fun npuInferenceBackend(): NpuInferenceBackend? = npuBackend

    private val routeMutex = Mutex()

    @Volatile
    private var activeBackend: InferenceBackend = fallbackCpuBackend

    @Volatile
    private var preference: AcceleratorPreference = AcceleratorPreference.AUTO

    @Volatile
    private var lastLoadedModel: ModelConfig? = null

    private val _lifecycleState = MutableStateFlow(fallbackCpuBackend.lifecycleState.value)
    override val lifecycleState: StateFlow<EngineLifecycleState> = _lifecycleState.asStateFlow()

    private val _runtimeStatus = MutableStateFlow(
        BackendRuntimeStatus(
            activeBackend = AcceleratorBackendType.CPU,
            requestedPreference = AcceleratorPreference.AUTO,
            statusSummary = "CPU Runtime Ready",
            activeDeviceLabel = "CPU"
        )
    )
    val runtimeStatus: StateFlow<BackendRuntimeStatus> = _runtimeStatus.asStateFlow()

    private val _lastProbeResult = MutableStateFlow<VulkanProbeResult?>(null)
    val lastProbeResult: StateFlow<VulkanProbeResult?> = _lastProbeResult.asStateFlow()

    override val versionInfo: EngineVersionInfo
        get() = activeBackend.versionInfo

    override val capabilities: EngineCapabilities
        get() {
            val cpuCaps = fallbackCpuBackend.capabilities
            val probe = _lastProbeResult.value
            val gpuSupported = primaryGpuBackend != null && (probe?.isUsableForInference == true)
            val backends = if (gpuSupported) {
                cpuCaps.supportedBackends + AcceleratorBackendType.VULKAN_GPU
            } else {
                cpuCaps.supportedBackends
            }
            val unavailableReason = when {
                gpuSupported -> null
                probe != null -> probe.reason ?: probe.selfTestSummary
                primaryGpuBackend == null -> cpuCaps.gpuUnavailableReason
                else -> "Vulkan capability probe not yet executed."
            }
            return cpuCaps.copy(
                supportedBackends = backends,
                supportsGpuOffload = gpuSupported,
                gpuUnavailableReason = unavailableReason
            )
        }

    fun setPreference(newPreference: AcceleratorPreference) {
        preference = newPreference
        val current = _runtimeStatus.value
        _runtimeStatus.value = current.copy(requestedPreference = newPreference)
    }

    fun currentPreference(): AcceleratorPreference = preference

    fun activeBackendType(): AcceleratorBackendType =
        if (activeBackend === primaryGpuBackend && primaryGpuBackend != null) {
            AcceleratorBackendType.VULKAN_GPU
        } else {
            AcceleratorBackendType.CPU
        }

    suspend fun probeVulkan(): VulkanProbeResult {
        val result = runCatching { probeProvider() }.getOrElse { err ->
            VulkanProbeResult(
                status = VulkanProbeStatus.PROBE_FAILED,
                nativeLibraryLoaded = false,
                selfTestSummary = "Vulkan probe failed: ${err.message ?: err::class.simpleName}",
                reason = err.message ?: err::class.simpleName
            )
        }
        _lastProbeResult.value = result
        return result
    }

    override suspend fun initialize(config: EngineConfig) {
        routeMutex.withLock {
            fallbackCpuBackend.initialize(config.copy(preferredBackend = AcceleratorBackendType.CPU))
            primaryGpuBackend?.let { gpu ->
                runCatching { gpu.initialize(config) }
            }
            val probe = probeVulkan()
            activeBackend = fallbackCpuBackend
            _lifecycleState.value = fallbackCpuBackend.lifecycleState.value
            _runtimeStatus.value = BackendRuntimeStatus(
                activeBackend = AcceleratorBackendType.CPU,
                requestedPreference = preference,
                statusSummary = if (probe.isUsableForInference) {
                    "Vulkan Probe Ready (${probe.deviceName ?: "GPU"})"
                } else {
                    "CPU Ready · ${probe.status.label}"
                },
                fallbackOccurred = false,
                fallbackReason = if (probe.isUsableForInference) null else probe.reason,
                activeDeviceLabel = "CPU"
            )
        }
    }

    override suspend fun validateModelFile(fileUri: String): Boolean =
        fallbackCpuBackend.validateModelFile(fileUri)

    override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready =
        routeMutex.withLock {
            val pref = preference
            val totalLayers = model.totalLayers.coerceAtLeast(0)

            if (pref == AcceleratorPreference.CPU_ONLY) {
                return@withLock loadCpuOnlyLocked(
                    model = model.copy(gpuLayers = 0),
                    requestedPreference = pref,
                    requestedGpuLayers = 0,
                    totalLayers = totalLayers,
                    fallbackOccurred = false,
                    fallbackReason = null,
                    statusSummary = "CPU Only (Selected)"
                )
            }

            if (model.gpuLayers <= 0) {
                return@withLock loadCpuOnlyLocked(
                    model = model.copy(gpuLayers = 0),
                    requestedPreference = pref,
                    requestedGpuLayers = 0,
                    totalLayers = totalLayers,
                    fallbackOccurred = false,
                    fallbackReason = null,
                    statusSummary = "CPU Runtime (0 GPU layers)"
                )
            }

            val gpuBackend = primaryGpuBackend
            if (gpuBackend == null) {
                return@withLock loadCpuOnlyLocked(
                    model = model.copy(gpuLayers = 0),
                    requestedPreference = pref,
                    requestedGpuLayers = model.gpuLayers,
                    totalLayers = totalLayers,
                    fallbackOccurred = true,
                    fallbackReason = "Vulkan engine backend is not attached in this build.",
                    statusSummary = "CPU Fallback (No Vulkan Module)"
                )
            }

            val probe = probeVulkan()
            if (!probe.isUsableForInference) {
                runCatching { gpuBackend.unload() }
                val reason = probe.reason ?: probe.selfTestSummary
                return@withLock loadCpuOnlyLocked(
                    model = model.copy(gpuLayers = 0),
                    requestedPreference = pref,
                    requestedGpuLayers = model.gpuLayers,
                    totalLayers = totalLayers,
                    fallbackOccurred = true,
                    fallbackReason = reason,
                    statusSummary = "CPU Fallback (${probe.status.label})"
                )
            }

            // Unload CPU context before mapping tensors via Vulkan GPU
            runCatching { fallbackCpuBackend.unload() }

            try {
                val ready = gpuBackend.loadModel(model)
                activeBackend = gpuBackend
                lastLoadedModel = model
                _lifecycleState.value = gpuBackend.lifecycleState.value
                val denom = if (totalLayers > 0) totalLayers else model.gpuLayers
                val gpuLabel = probe.deviceName ?: "Vulkan GPU"
                _runtimeStatus.value = BackendRuntimeStatus(
                    activeBackend = AcceleratorBackendType.VULKAN_GPU,
                    requestedPreference = pref,
                    requestedGpuLayers = model.gpuLayers,
                    appliedGpuLayers = model.gpuLayers,
                    totalModelLayers = totalLayers,
                    fallbackOccurred = false,
                    statusSummary = "GPU · VK ${model.gpuLayers}/${denom}L",
                    fallbackReason = null,
                    activeDeviceLabel = gpuLabel
                )
                ready
            } catch (e: CancellationException) {
                _lifecycleState.value = activeBackend.lifecycleState.value
                throw e
            } catch (t: Throwable) {
                runCatching { gpuBackend.unload() }
                val reason = "Vulkan load failed (${t.message ?: t::class.simpleName}); switched to CPU."
                loadCpuOnlyLocked(
                    model = model.copy(gpuLayers = 0),
                    requestedPreference = pref,
                    requestedGpuLayers = model.gpuLayers,
                    totalLayers = totalLayers,
                    fallbackOccurred = true,
                    fallbackReason = reason,
                    statusSummary = "CPU Fallback (Vulkan Error)"
                )
            }
        }

    private suspend fun loadCpuOnlyLocked(
        model: ModelConfig,
        requestedPreference: AcceleratorPreference,
        requestedGpuLayers: Int,
        totalLayers: Int,
        fallbackOccurred: Boolean,
        fallbackReason: String?,
        statusSummary: String
    ): EngineLifecycleState.Ready {
        primaryGpuBackend?.let { gpu ->
            runCatching { gpu.unload() }
        }
        val ready = try {
            fallbackCpuBackend.loadModel(model)
        } finally {
            activeBackend = fallbackCpuBackend
            _lifecycleState.value = fallbackCpuBackend.lifecycleState.value
        }
        lastLoadedModel = model
        _runtimeStatus.value = BackendRuntimeStatus(
            activeBackend = AcceleratorBackendType.CPU,
            requestedPreference = requestedPreference,
            requestedGpuLayers = requestedGpuLayers,
            appliedGpuLayers = 0,
            totalModelLayers = totalLayers,
            fallbackOccurred = fallbackOccurred,
            statusSummary = statusSummary,
            fallbackReason = fallbackReason,
            activeDeviceLabel = "CPU"
        )
        return ready
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val initialBackend = activeBackend
        var emittedAnyToken = false
        var generationSucceeded = false

        try {
            initialBackend.generate(request).collect { event ->
                if (event is GenerationEvent.TokenDelta) {
                    emittedAnyToken = true
                }
                if (event is GenerationEvent.Completed) {
                    generationSucceeded = true
                }
                _lifecycleState.value = initialBackend.lifecycleState.value
                send(event)
            }
        } catch (e: CancellationException) {
            _lifecycleState.value = activeBackend.lifecycleState.value
            throw e
        } catch (t: Throwable) {
            val isGpuActive = primaryGpuBackend != null && initialBackend === primaryGpuBackend
            if (!isGpuActive || generationSucceeded) {
                _lifecycleState.value = activeBackend.lifecycleState.value
                throw t
            }

            val loadedModel = lastLoadedModel
            val failMsg = t.message ?: t::class.simpleName ?: "Vulkan runtime error"

            if (!emittedAnyToken && loadedModel != null) {
                // Safe pre-decode failover: unload broken GPU context, load CPU context, retry seamlessly
                val failoverReady = routeMutex.withLock {
                    runCatching { primaryGpuBackend?.unload() }
                    loadCpuOnlyLocked(
                        model = loadedModel.copy(gpuLayers = 0),
                        requestedPreference = preference,
                        requestedGpuLayers = loadedModel.gpuLayers,
                        totalLayers = loadedModel.totalLayers,
                        fallbackOccurred = true,
                        fallbackReason = "Vulkan prefill/decode error ($failMsg); fell back to CPU.",
                        statusSummary = "CPU Auto-Fallback"
                    )
                }
                _lifecycleState.value = failoverReady
                fallbackCpuBackend.generate(request).collect { event ->
                    _lifecycleState.value = fallbackCpuBackend.lifecycleState.value
                    send(event)
                }
            } else {
                // Stream already started on GPU; clean up GPU and switch future turns to CPU
                routeMutex.withLock {
                    runCatching { primaryGpuBackend?.unload() }
                    activeBackend = fallbackCpuBackend
                    if (loadedModel != null) {
                        runCatching { fallbackCpuBackend.loadModel(loadedModel.copy(gpuLayers = 0)) }
                    }
                    _lifecycleState.value = fallbackCpuBackend.lifecycleState.value
                    _runtimeStatus.value = BackendRuntimeStatus(
                        activeBackend = AcceleratorBackendType.CPU,
                        requestedPreference = preference,
                        requestedGpuLayers = loadedModel?.gpuLayers ?: 0,
                        appliedGpuLayers = 0,
                        totalModelLayers = loadedModel?.totalLayers ?: 0,
                        fallbackOccurred = true,
                        statusSummary = "CPU Auto-Fallback",
                        fallbackReason = "Vulkan interrupted mid-stream ($failMsg); switched to CPU for next turns.",
                        activeDeviceLabel = "CPU"
                    )
                }
                throw EngineError.GpuBackendError(
                    reason = "Vulkan generation interrupted ($failMsg). Switched runtime to safe CPU mode.",
                    fallbackEligible = true,
                    cause = t
                )
            }
        }
        _lifecycleState.value = activeBackend.lifecycleState.value
    }

    override suspend fun cancel() {
        runCatching { activeBackend.cancel() }
        _lifecycleState.value = activeBackend.lifecycleState.value
    }

    override suspend fun unload() {
        routeMutex.withLock {
            runCatching { primaryGpuBackend?.unload() }
            runCatching { fallbackCpuBackend.unload() }
            activeBackend = fallbackCpuBackend
            lastLoadedModel = null
            _lifecycleState.value = fallbackCpuBackend.lifecycleState.value
            _runtimeStatus.value = BackendRuntimeStatus(
                activeBackend = AcceleratorBackendType.CPU,
                requestedPreference = preference,
                statusSummary = "Unloaded",
                activeDeviceLabel = "CPU"
            )
        }
    }

    override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int): BenchmarkResult =
        benchmarkWithThreads(
            threads = lastLoadedModel?.threads ?: 4,
            prefillTokens = prefillTokens,
            decodeTokens = decodeTokens
        )

    override suspend fun benchmarkWithThreads(
        threads: Int,
        prefillTokens: Int,
        decodeTokens: Int
    ): BenchmarkResult {
        val current = activeBackend
        return try {
            val res = current.benchmarkWithThreads(threads, prefillTokens, decodeTokens)
            _lifecycleState.value = current.lifecycleState.value
            res
        } catch (e: CancellationException) {
            _lifecycleState.value = activeBackend.lifecycleState.value
            throw e
        } catch (t: Throwable) {
            val loadedModel = lastLoadedModel
            val gpuBackend = primaryGpuBackend
            if (gpuBackend != null && current === gpuBackend && loadedModel != null) {
                routeMutex.withLock {
                    runCatching { gpuBackend.unload() }
                    loadCpuOnlyLocked(
                        model = loadedModel.copy(gpuLayers = 0),
                        requestedPreference = preference,
                        requestedGpuLayers = loadedModel.gpuLayers,
                        totalLayers = loadedModel.totalLayers,
                        fallbackOccurred = true,
                        fallbackReason = "Vulkan benchmark failed (${t.message ?: t::class.simpleName}); ran on CPU.",
                        statusSummary = "CPU Auto-Fallback"
                    )
                }
                fallbackCpuBackend.benchmarkWithThreads(threads, prefillTokens, decodeTokens)
            } else {
                _lifecycleState.value = activeBackend.lifecycleState.value
                throw t
            }
        }
    }

    override suspend fun tokenize(text: String): IntArray = activeBackend.tokenize(text)

    override suspend fun detokenize(tokens: IntArray): String = activeBackend.detokenize(tokens)

    override suspend fun embed(text: String): FloatArray = activeBackend.embed(text)

    override suspend fun saveSession(
        sessionId: SessionId,
        path: String,
        maxTokens: Int
    ): SessionSaveResult = activeBackend.saveSession(sessionId, path, maxTokens)

    override suspend fun loadSession(sessionId: SessionId, path: String): Int =
        activeBackend.loadSession(sessionId, path)

    override suspend fun formatChat(turns: List<ChatTurn>, template: String): String? =
        activeBackend.formatChat(turns, template)
}
