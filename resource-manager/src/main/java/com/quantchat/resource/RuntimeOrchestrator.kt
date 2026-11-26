package com.quantchat.resource

import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.BackendRuntimeStatus
import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.engine.core.DeviceCapabilities
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.EngineConfig
import com.quantchat.engine.core.EngineError
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.EngineVersionInfo
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.MmapLifecycleRegistry
import com.quantchat.engine.core.MmapLifecycleSnapshot
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.NpuCompatibilityReport
import com.quantchat.engine.core.NpuInferenceBackend
import com.quantchat.engine.core.NpuProbeResult
import com.quantchat.engine.core.ResilientFailoverBackend
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.WorkloadClass
import com.quantchat.hardware.DeviceHardwareProfile
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.hardware.ThermalSeverity
import com.quantchat.model.DownloadCheckpointStore
import com.quantchat.model.GgufHeaderMetadata
import com.quantchat.model.LocalModelRegistry
import com.quantchat.model.ModelFileVerifier
import com.quantchat.model.ModelVerificationResult
import com.quantchat.session.ContextBudgetSnapshot
import com.quantchat.session.SessionCacheManager
import com.quantchat.session.SessionCacheMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class OrchestratorSnapshot(
    val lifecycleState: EngineLifecycleState,
    val versionInfo: EngineVersionInfo,
    val capabilities: EngineCapabilities,
    val hardwareProfile: DeviceHardwareProfile? = null,
    val liveMetrics: LiveDeviceMetrics? = null,
    val recommendedThreads: Int = 4,
    val requestedScheduleMode: CpuScheduleMode = CpuScheduleMode.BALANCED,
    val autoThermalDownshift: Boolean = true,
    val schedulingDecision: ThreadSchedulingDecision? = null,
    val modeThreadPreview: Map<CpuScheduleMode, Int> = emptyMap(),
    val calibrationProgress: CalibrationProgress = CalibrationProgress(),
    val lastCalibration: ThreadCalibrationResult? = null,
    val lastAdmission: AdmissionAssessment? = null,
    val lastBenchmark: BenchmarkResult? = null,
    val lastContextBudget: ContextBudgetSnapshot? = null,
    val activeSessionCache: SessionCacheMetadata? = null,
    val isBenchmarking: Boolean = false,
    val backendRuntimeStatus: BackendRuntimeStatus = BackendRuntimeStatus(),
    val vulkanCapabilities: VulkanProbeResult? = null,
    val gpuOffloadPlan: GpuOffloadPlan? = null,
    val acceleratorPreference: AcceleratorPreference = AcceleratorPreference.AUTO,
    val autoGpuLayerOffload: Boolean = true,
    val manualGpuLayers: Int = 0,
    val npuCapabilities: NpuProbeResult? = null,
    val npuCompatibilityReport: NpuCompatibilityReport? = null,
    val deviceCapabilities: DeviceCapabilities? = null,
    val thermalState: ThermalRuntimeActionState = ThermalRuntimeActionState(),
    val mmapLifecycle: MmapLifecycleSnapshot = MmapLifecycleSnapshot()
)

data class ModelLoadOutcome(
    val readyState: EngineLifecycleState.Ready,
    val headerMetadata: GgufHeaderMetadata?,
    val admission: AdmissionAssessment
)

class RuntimeOrchestrator(
    primaryBackend: InferenceBackend,
    private val hardwareManager: HardwareCapabilityManager,
    private val resourceManager: RuntimeResourceManager,
    private val modelRegistry: LocalModelRegistry,
    private val sessionManager: SessionCacheManager,
    vulkanBackend: InferenceBackend? = null,
    vulkanProbeProvider: (suspend () -> VulkanProbeResult)? = null,
    private val npuBackend: NpuInferenceBackend? = null,
    severeThermalPacingMs: Long = com.quantchat.engine.core.DeviceCapabilitiesPolicy.SEVERE_TOKEN_PACING_DELAY_MS,
    private val strictArtifactVerification: Boolean = false
) {
    private val resilientBackend: ResilientFailoverBackend = if (primaryBackend is ResilientFailoverBackend) {
        primaryBackend
    } else {
        ResilientFailoverBackend(
            fallbackCpuBackend = primaryBackend,
            primaryGpuBackend = vulkanBackend,
            probeProvider = vulkanProbeProvider ?: { hardwareManager.probeVulkanCapabilities() },
            npuBackend = npuBackend,
            capabilityProvider = hardwareManager,
            severeThermalPacingMs = severeThermalPacingMs
        )
    }

    val backend: InferenceBackend get() = resilientBackend

    private val operationMutex = Mutex()
    private val orchestratorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val configLock = Any()

    private var requestedMode: CpuScheduleMode = CpuScheduleMode.BALANCED
    private var autoThermalDownshift: Boolean = true
    private var manualThreadsOverride: Int? = null
    private var calibratedOptimalThreads: Int? = null
    private var calibratedDeviceSignature: String? = null
    private var lastWorkload: WorkloadClass = WorkloadClass.STANDARD

    private var acceleratorPreference: AcceleratorPreference = AcceleratorPreference.AUTO
    private var autoGpuLayerOffload: Boolean = true
    private var manualGpuLayers: Int = 0
    private var activeModelId: String? = null
    private var activeModelFilePath: String? = null
    private var activeModelFileSizeBytes: Long = 0L
    private var activeModelHeader: GgufHeaderMetadata? = null
    private var activeModelContextSize: Int = 2048

    @Volatile
    private var calibrationCancelRequested: Boolean = false

    @Volatile
    private var emergencyReleaseCount: Int = 0

    @Volatile
    private var lastThermalEventNote: String? = null

    private var emergencyThermalJob: Job? = null

    private val _snapshot = MutableStateFlow(
        OrchestratorSnapshot(
            lifecycleState = resilientBackend.lifecycleState.value,
            versionInfo = resilientBackend.versionInfo,
            capabilities = resilientBackend.capabilities,
            backendRuntimeStatus = resilientBackend.runtimeStatus.value,
            deviceCapabilities = hardwareManager.deviceCapabilities.value,
            thermalState = ThermalGovernor.evaluate(
                severity = hardwareManager.thermalSeverityFlow.value,
                deviceCapabilities = hardwareManager.deviceCapabilities.value,
                liveMetrics = null,
                autoProtectionEnabled = true,
                requestedThreads = 4
            ),
            mmapLifecycle = MmapLifecycleRegistry.snapshot.value
        )
    )
    val snapshot: StateFlow<OrchestratorSnapshot> = _snapshot.asStateFlow()

    init {
        orchestratorScope.launch {
            hardwareManager.thermalSeverityFlow.collect { severity ->
                updateThermalInSnapshot(severity)
            }
        }
        orchestratorScope.launch {
            hardwareManager.deviceCapabilities.collect { caps ->
                _snapshot.update { current ->
                    current.copy(deviceCapabilities = caps)
                }
            }
        }
        orchestratorScope.launch {
            MmapLifecycleRegistry.snapshot.collect { mmapState ->
                _snapshot.update { current ->
                    current.copy(mmapLifecycle = mmapState)
                }
            }
        }
        orchestratorScope.launch {
            resilientBackend.runtimeStatus.collect { status ->
                _snapshot.update { current ->
                    current.copy(
                        backendRuntimeStatus = status,
                        versionInfo = resilientBackend.versionInfo,
                        capabilities = resilientBackend.capabilities
                    )
                }
            }
        }
    }

    suspend fun initialize() {
        hardwareManager.startThermalMonitoring()
        resilientBackend.initialize(EngineConfig())
        refreshDiagnostics()
    }

    fun shutdown() {
        hardwareManager.stopThermalMonitoring()
        orchestratorScope.cancel()
    }

    fun updateScheduleConfig(
        mode: CpuScheduleMode,
        autoThermalDownshift: Boolean,
        manualThreadsOverride: Int?,
        calibratedOptimalThreads: Int?,
        calibratedDeviceSignature: String?
    ) {
        synchronized(configLock) {
            this.requestedMode = mode
            this.autoThermalDownshift = autoThermalDownshift
            this.manualThreadsOverride = manualThreadsOverride
            this.calibratedOptimalThreads = calibratedOptimalThreads
            this.calibratedDeviceSignature = calibratedDeviceSignature
        }
        recomputeSchedulingSnapshot()
    }

    fun updateAcceleratorConfig(
        preference: AcceleratorPreference,
        autoGpuLayerOffload: Boolean,
        manualGpuLayers: Int
    ) {
        synchronized(configLock) {
            this.acceleratorPreference = preference
            this.autoGpuLayerOffload = autoGpuLayerOffload
            this.manualGpuLayers = manualGpuLayers.coerceAtLeast(0)
        }
        resilientBackend.setPreference(preference)
        recomputeSchedulingSnapshot()
    }

    suspend fun runVulkanSelfTest(): VulkanProbeResult {
        val result = resilientBackend.probeVulkan()
        refreshDiagnostics()
        return result
    }

    suspend fun runNpuProbe(): NpuProbeResult {
        val result = hardwareManager.probeNpuCapabilities()
        refreshDiagnostics()
        return result
    }

    suspend fun refreshDiagnostics(): DeviceHardwareProfile {
        val profile = hardwareManager.probeHardware(resilientBackend.capabilities)
        val probe = profile.vulkanCapabilities ?: resilientBackend.lastProbeResult.value
        val npuProbe = profile.npuCapabilities
        val caps = hardwareManager.getDeviceCapabilities()
        _snapshot.update { current ->
            val decision = computeDecisionLocked(
                profile = profile,
                thermal = profile.liveMetrics.thermalSeverity,
                workload = lastWorkload
            )
            val previews = computeModePreviewsLocked(profile, profile.liveMetrics.thermalSeverity)
            val offloadPlan = computeOffloadPlanLocked(profile.liveMetrics, probe)
            val compatReport = computeCompatibilityReportLocked(npuProbe, probe)
            val thermalState = buildThermalStateLocked(
                severity = profile.liveMetrics.thermalSeverity,
                caps = caps,
                metrics = profile.liveMetrics,
                fallbackThreads = decision.effectiveThreads
            )
            current.copy(
                lifecycleState = resilientBackend.lifecycleState.value,
                versionInfo = resilientBackend.versionInfo,
                capabilities = resilientBackend.capabilities,
                hardwareProfile = profile,
                liveMetrics = profile.liveMetrics,
                recommendedThreads = decision.effectiveThreads,
                requestedScheduleMode = requestedMode,
                autoThermalDownshift = autoThermalDownshift,
                schedulingDecision = decision,
                modeThreadPreview = previews,
                backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                vulkanCapabilities = probe,
                gpuOffloadPlan = offloadPlan,
                acceleratorPreference = acceleratorPreference,
                autoGpuLayerOffload = autoGpuLayerOffload,
                manualGpuLayers = manualGpuLayers,
                npuCapabilities = npuProbe,
                npuCompatibilityReport = compatReport,
                deviceCapabilities = caps,
                thermalState = thermalState
            )
        }
        return profile
    }

    suspend fun inspectModelMetadata(diskFileName: String): GgufHeaderMetadata? =
        modelRegistry.inspectHeader(diskFileName)

    suspend fun verifyModelFile(
        diskFile: File,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        computeSha256: Boolean = true
    ): ModelVerificationResult {
        val savedManifest = DownloadCheckpointStore.readVerifiedManifest(diskFile)
        val result = ModelFileVerifier.verifyComplete(
            file = diskFile,
            expectedSizeBytes = expectedSizeBytes ?: savedManifest?.expectedSizeBytes,
            expectedSha256 = expectedSha256 ?: savedManifest?.expectedSha256,
            computeSha256 = computeSha256
        )
        if (result.isValid) {
            DownloadCheckpointStore.writeVerifiedManifest(diskFile, result)
        }
        return result
    }

    fun assessAdmission(
        diskFile: File,
        header: GgufHeaderMetadata?,
        contextSize: Int,
        plannedGpuLayers: Int? = null,
        totalLayers: Int? = null
    ): AdmissionAssessment {
        val metrics = resourceManager.liveMetrics()
        val probe = _snapshot.value.vulkanCapabilities ?: _snapshot.value.hardwareProfile?.vulkanCapabilities
        val candidatePlan = if (plannedGpuLayers == null) {
            synchronized(configLock) {
                GpuOffloadPlanner.planOffload(
                    fileSizeBytes = diskFile.length().coerceAtLeast(0L),
                    header = header,
                    contextSize = contextSize,
                    availableRamMb = metrics.availableRamMb,
                    lowMemory = metrics.lowMemory,
                    vulkanProbe = probe,
                    preference = acceleratorPreference,
                    autoOffload = autoGpuLayerOffload,
                    manualLayersOverride = manualGpuLayers,
                    thermalSeverity = if (autoThermalDownshift) metrics.thermalSeverity else ThermalSeverity.NOMINAL
                )
            }
        } else {
            null
        }
        val effectiveGpu = plannedGpuLayers ?: candidatePlan?.effectiveLayers ?: 0
        val resolvedTotalLayers = totalLayers ?: candidatePlan?.totalLayers ?: header?.blockCount ?: 32
        val assessment = resourceManager.assessModelAdmission(
            file = diskFile,
            header = header,
            contextSize = contextSize,
            metrics = metrics,
            plannedGpuLayers = effectiveGpu,
            totalLayers = resolvedTotalLayers
        )
        _snapshot.update { it.copy(liveMetrics = metrics, lastAdmission = assessment) }
        return assessment
    }

    suspend fun loadModel(
        modelId: ModelId,
        displayName: String,
        diskFile: File,
        contextSize: Int,
        threads: Int
    ): ModelLoadOutcome = operationMutex.withLock {
        if (MmapLifecycleRegistry.isWriteLocked(diskFile)) {
            throw EngineError.FileLocked(
                fileName = displayName,
                reason = "Model file is locked by an ongoing write or integrity check."
            )
        }
        if (strictArtifactVerification) {
            val savedManifest = DownloadCheckpointStore.readVerifiedManifest(diskFile)
            val verification = ModelFileVerifier.verifyComplete(
                file = diskFile,
                expectedSizeBytes = savedManifest?.expectedSizeBytes,
                expectedSha256 = savedManifest?.expectedSha256,
                computeSha256 = false
            )
            if (!verification.isValid) {
                throw EngineError.InvalidModelFormat(
                    fileName = displayName,
                    reason = verification.failureReason ?: "Model file failed integrity check (${verification.verdict.label})."
                )
            }
            if (savedManifest == null) {
                DownloadCheckpointStore.writeVerifiedManifest(diskFile, verification)
            }
        }
        val header = modelRegistry.inspectHeader(diskFile.name)
        val profile = _snapshot.value.hardwareProfile ?: hardwareManager.probeHardware(resilientBackend.capabilities)
        val live = resourceManager.liveMetrics()
        val thermalAuto = synchronized(configLock) { autoThermalDownshift }
        if (thermalAuto && live.thermalSeverity == ThermalSeverity.CRITICAL) {
            throw EngineError.ThermalShutdown(
                thermalStatus = live.thermalSeverity.statusCode,
                reason = "Device is in CRITICAL thermal state; model loading is paused to protect hardware."
            )
        }
        val probe = profile.vulkanCapabilities ?: resilientBackend.probeVulkan()
        val candidateOffloadPlan = synchronized(configLock) {
            GpuOffloadPlanner.planOffload(
                fileSizeBytes = diskFile.length().coerceAtLeast(0L),
                header = header,
                contextSize = contextSize,
                availableRamMb = live.availableRamMb,
                lowMemory = live.lowMemory,
                vulkanProbe = probe,
                preference = acceleratorPreference,
                autoOffload = autoGpuLayerOffload,
                manualLayersOverride = manualGpuLayers,
                thermalSeverity = if (autoThermalDownshift) live.thermalSeverity else ThermalSeverity.NOMINAL
            )
        }

        val admission = assessAdmission(
            diskFile = diskFile,
            header = header,
            contextSize = contextSize,
            plannedGpuLayers = candidateOffloadPlan.effectiveLayers,
            totalLayers = candidateOffloadPlan.totalLayers
        )
        if (admission.verdict == AdmissionVerdict.BLOCKED) {
            _snapshot.update {
                it.copy(
                    liveMetrics = live,
                    lastAdmission = admission,
                    gpuOffloadPlan = candidateOffloadPlan
                )
            }
            throw EngineError.InsufficientMemory(
                requiredMb = admission.estimatedMemoryMb,
                availableMb = admission.availableMemoryMb,
                reason = admission.blockReason ?: admission.summary
            )
        }

        val decision = computeDecisionLocked(
            profile = profile,
            thermal = live.thermalSeverity,
            workload = WorkloadClass.STANDARD
        )
        val initThreads = (decision.effectiveThreads.takeIf { it > 0 } ?: threads).coerceIn(1, 16)

        synchronized(configLock) {
            activeModelId = modelId.value
            activeModelFilePath = diskFile.absolutePath
            activeModelFileSizeBytes = diskFile.length()
            activeModelHeader = header
            activeModelContextSize = contextSize
        }

        val npuProbe = profile.npuCapabilities ?: hardwareManager.probeNpuCapabilities()
        val offloadPlan = computeOffloadPlanLocked(live, probe)

        val config = ModelConfig(
            modelId = modelId,
            displayName = displayName,
            filePath = diskFile.absolutePath,
            fileUri = "file://${diskFile.absolutePath}",
            contextSize = contextSize,
            batchSize = 512,
            threads = initThreads,
            gpuLayers = offloadPlan.effectiveLayers,
            totalLayers = offloadPlan.totalLayers,
            useMmap = true,
            useMlock = false,
            enableEmbedding = false
        )

        val ready = try {
            resilientBackend.loadModel(config)
        } finally {
            val postMetrics = resourceManager.liveMetrics()
            val finalProbe = resilientBackend.lastProbeResult.value ?: probe
            val compatReport = computeCompatibilityReportLocked(npuProbe, finalProbe)
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    versionInfo = resilientBackend.versionInfo,
                    capabilities = resilientBackend.capabilities,
                    hardwareProfile = profile,
                    liveMetrics = postMetrics,
                    recommendedThreads = decision.effectiveThreads,
                    schedulingDecision = decision,
                    modeThreadPreview = computeModePreviewsLocked(profile, postMetrics.thermalSeverity),
                    lastAdmission = admission,
                    backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                    vulkanCapabilities = finalProbe,
                    gpuOffloadPlan = offloadPlan,
                    npuCapabilities = npuProbe,
                    npuCompatibilityReport = compatReport,
                    deviceCapabilities = hardwareManager.getDeviceCapabilities()
                )
            }
        }

        ModelLoadOutcome(
            readyState = ready,
            headerMetadata = header,
            admission = admission
        )
    }

    suspend fun streamChat(
        threadId: String,
        modelId: ModelId,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        modelFamilyHint: String,
        contextSize: Int,
        maxTokens: Int,
        temperature: Float
    ): Flow<GenerationEvent> {
        val fingerprint = sessionManager.buildFingerprint(modelId, contextSize, systemPrompt)
        val restored = sessionManager.tryRestoreSession(resilientBackend, threadId, fingerprint)
        val budget = sessionManager.prepareContextAndBudget(
            backend = resilientBackend,
            threadId = threadId,
            fingerprint = fingerprint,
            systemPrompt = systemPrompt,
            history = history,
            userPrompt = userPrompt,
            modelFamilyHint = modelFamilyHint,
            contextSize = contextSize,
            maxOutputTokens = maxTokens
        ).copy(kvCacheHit = restored != null)

        val workload = resourceManager.classifyWorkload(
            promptChars = userPrompt.length,
            historyTurns = budget.keptTurns.size,
            estimatedPromptTokens = budget.promptTokens,
            contextSize = contextSize
        )
        val metrics = resourceManager.liveMetrics()
        val thermalAuto = synchronized(configLock) { autoThermalDownshift }
        if (thermalAuto && metrics.thermalSeverity == ThermalSeverity.CRITICAL) {
            throw EngineError.ThermalShutdown(
                thermalStatus = metrics.thermalSeverity.statusCode,
                reason = "Inference stopped: device reached CRITICAL thermal status."
            )
        }
        val profile = _snapshot.value.hardwareProfile ?: hardwareManager.probeHardware(resilientBackend.capabilities)
        val decision = synchronized(configLock) {
            lastWorkload = workload
            computeDecisionLocked(
                profile = profile,
                thermal = metrics.thermalSeverity,
                workload = workload
            )
        }
        val caps = hardwareManager.getDeviceCapabilities()
        val thermalState = buildThermalStateLocked(metrics.thermalSeverity, caps, metrics, decision.effectiveThreads)

        _snapshot.update {
            it.copy(
                hardwareProfile = profile,
                lastContextBudget = budget,
                activeSessionCache = restored,
                liveMetrics = metrics,
                recommendedThreads = decision.effectiveThreads,
                schedulingDecision = decision,
                modeThreadPreview = computeModePreviewsLocked(profile, metrics.thermalSeverity),
                backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                deviceCapabilities = caps,
                thermalState = thermalState
            )
        }

        val request = GenerationRequest(
            modelId = modelId,
            sessionId = restored?.sessionId,
            systemPrompt = systemPrompt,
            history = budget.keptTurns,
            userPrompt = userPrompt,
            modelFamilyHint = modelFamilyHint,
            maxTokens = maxTokens,
            temperature = temperature,
            threadsOverride = decision.effectiveThreads,
            threadPriority = decision.priorityHint
        )

        return resilientBackend.generate(request).onEach { event ->
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                    versionInfo = resilientBackend.versionInfo,
                    capabilities = resilientBackend.capabilities
                )
            }
            if (event is GenerationEvent.Completed) {
                val saved = sessionManager.persistSession(
                    backend = resilientBackend,
                    threadId = threadId,
                    fingerprint = fingerprint,
                    maxTokens = contextSize
                )
                val postMetrics = resourceManager.liveMetrics()
                _snapshot.update {
                    val postDecision = computeDecisionLocked(
                        profile = profile,
                        thermal = postMetrics.thermalSeverity,
                        workload = lastWorkload
                    )
                    it.copy(
                        lifecycleState = resilientBackend.lifecycleState.value,
                        activeSessionCache = saved,
                        liveMetrics = postMetrics,
                        recommendedThreads = postDecision.effectiveThreads,
                        schedulingDecision = postDecision,
                        modeThreadPreview = computeModePreviewsLocked(profile, postMetrics.thermalSeverity),
                        backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                        versionInfo = resilientBackend.versionInfo,
                        capabilities = resilientBackend.capabilities
                    )
                }
            }
        }
    }

    suspend fun stopGeneration() {
        resilientBackend.cancel()
        _snapshot.update {
            it.copy(
                lifecycleState = resilientBackend.lifecycleState.value,
                backendRuntimeStatus = resilientBackend.runtimeStatus.value
            )
        }
    }

    suspend fun unloadModel() {
        operationMutex.withLock {
            resilientBackend.unload()
            synchronized(configLock) {
                activeModelId = null
                activeModelFilePath = null
                activeModelFileSizeBytes = 0L
                activeModelHeader = null
            }
            val metrics = resourceManager.liveMetrics()
            val probe = _snapshot.value.vulkanCapabilities
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    versionInfo = resilientBackend.versionInfo,
                    capabilities = resilientBackend.capabilities,
                    activeSessionCache = null,
                    lastContextBudget = null,
                    liveMetrics = metrics,
                    backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                    gpuOffloadPlan = computeOffloadPlanLocked(metrics, probe),
                    npuCompatibilityReport = null,
                    deviceCapabilities = hardwareManager.getDeviceCapabilities(),
                    mmapLifecycle = MmapLifecycleRegistry.snapshot.value
                )
            }
        }
    }

    suspend fun runBenchmark(): BenchmarkResult = operationMutex.withLock {
        val preMetrics = resourceManager.liveMetrics()
        val thermalAuto = synchronized(configLock) { autoThermalDownshift }
        if (thermalAuto && (preMetrics.thermalSeverity == ThermalSeverity.SEVERE ||
                preMetrics.thermalSeverity == ThermalSeverity.CRITICAL)
        ) {
            throw EngineError.GenerationFailed(
                "Benchmark paused by Thermal Guard (${preMetrics.thermalSeverity.label}). Wait for device to cool."
            )
        }
        _snapshot.update { it.copy(isBenchmarking = true) }
        try {
            val activeThreads = _snapshot.value.schedulingDecision?.effectiveThreads
                ?: _snapshot.value.recommendedThreads
            val result = resilientBackend.benchmarkWithThreads(
                threads = activeThreads,
                prefillTokens = 64,
                decodeTokens = 24
            )
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    lastBenchmark = result,
                    liveMetrics = resourceManager.liveMetrics(),
                    isBenchmarking = false,
                    backendRuntimeStatus = resilientBackend.runtimeStatus.value
                )
            }
            result
        } catch (e: EngineError) {
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    isBenchmarking = false,
                    backendRuntimeStatus = resilientBackend.runtimeStatus.value
                )
            }
            throw e
        } catch (e: Exception) {
            _snapshot.update { it.copy(isBenchmarking = false) }
            throw e
        }
    }

    suspend fun calibrateOptimalThreads(): ThreadCalibrationResult = operationMutex.withLock {
        val preMetrics = resourceManager.liveMetrics()
        val thermalAuto = synchronized(configLock) { autoThermalDownshift }
        if (thermalAuto && (preMetrics.thermalSeverity == ThermalSeverity.SEVERE ||
                preMetrics.thermalSeverity == ThermalSeverity.CRITICAL)
        ) {
            throw EngineError.GenerationFailed(
                "Calibration paused by Thermal Guard (${preMetrics.thermalSeverity.label}). Non-essential sweeps suspended."
            )
        }
        calibrationCancelRequested = false
        val profile = _snapshot.value.hardwareProfile ?: hardwareManager.probeHardware(resilientBackend.capabilities)
        val candidates = resourceManager.buildCalibrationCandidates(profile)
        val collected = mutableListOf<ThreadCalibrationEntry>()

        _snapshot.update {
            it.copy(
                isBenchmarking = true,
                hardwareProfile = profile,
                calibrationProgress = CalibrationProgress(
                    running = true,
                    currentThreads = candidates.firstOrNull(),
                    stepIndex = 0,
                    totalSteps = candidates.size,
                    completedEntries = emptyList()
                )
            )
        }

        try {
            candidates.forEachIndexed { index, threadCount ->
                val loopThermal = resourceManager.liveMetrics().thermalSeverity
                if (calibrationCancelRequested || (synchronized(configLock) { autoThermalDownshift } &&
                        (loopThermal == ThermalSeverity.SEVERE || loopThermal == ThermalSeverity.CRITICAL))
                ) {
                    throw CancellationException("Thread calibration paused due to ${loopThermal.label} thermal state.")
                }
                _snapshot.update {
                    it.copy(
                        calibrationProgress = CalibrationProgress(
                            running = true,
                            currentThreads = threadCount,
                            stepIndex = index + 1,
                            totalSteps = candidates.size,
                            completedEntries = collected.toList()
                        )
                    )
                }

                val benchResult = resilientBackend.benchmarkWithThreads(
                    threads = threadCount,
                    prefillTokens = 48,
                    decodeTokens = 20
                )

                if (calibrationCancelRequested) {
                    throw CancellationException("Thread calibration cancelled.")
                }

                val entry = resourceManager.scoreCalibrationEntry(threadCount, benchResult)
                collected += entry
                _snapshot.update {
                    it.copy(
                        lastBenchmark = benchResult,
                        liveMetrics = resourceManager.liveMetrics(),
                        calibrationProgress = CalibrationProgress(
                            running = true,
                            currentThreads = threadCount,
                            stepIndex = index + 1,
                            totalSteps = candidates.size,
                            completedEntries = collected.toList()
                        )
                    )
                }
            }

            val best = collected.maxWithOrNull(
                compareBy<ThreadCalibrationEntry> { it.compositeScore }
                    .thenByDescending { -it.threads }
            ) ?: throw EngineError.GenerationFailed("No calibration results collected.")

            val calibrationResult = ThreadCalibrationResult(
                deviceSignature = profile.deviceTopologySignature,
                optimalThreads = best.threads,
                entries = collected.toList(),
                calibratedAtMs = System.currentTimeMillis()
            )

            synchronized(configLock) {
                calibratedOptimalThreads = best.threads
                calibratedDeviceSignature = profile.deviceTopologySignature
            }

            val updatedDecision = computeDecisionLocked(
                profile = profile,
                thermal = resourceManager.liveMetrics().thermalSeverity,
                workload = lastWorkload
            )
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    isBenchmarking = false,
                    lastCalibration = calibrationResult,
                    calibrationProgress = CalibrationProgress(
                        running = false,
                        currentThreads = null,
                        stepIndex = candidates.size,
                        totalSteps = candidates.size,
                        completedEntries = collected.toList()
                    ),
                    recommendedThreads = updatedDecision.effectiveThreads,
                    schedulingDecision = updatedDecision,
                    modeThreadPreview = computeModePreviewsLocked(profile, resourceManager.liveMetrics().thermalSeverity)
                )
            }
            calibrationResult
        } catch (e: CancellationException) {
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    isBenchmarking = false,
                    calibrationProgress = CalibrationProgress(
                        running = false,
                        completedEntries = collected.toList()
                    )
                )
            }
            throw e
        } catch (e: EngineError) {
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    isBenchmarking = false,
                    calibrationProgress = CalibrationProgress(running = false)
                )
            }
            throw e
        } catch (e: Exception) {
            _snapshot.update {
                it.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    isBenchmarking = false,
                    calibrationProgress = CalibrationProgress(running = false)
                )
            }
            throw e
        } finally {
            calibrationCancelRequested = false
        }
    }

    suspend fun cancelCalibration() {
        calibrationCancelRequested = true
        resilientBackend.cancel()
    }

    fun invalidateThreadSession(threadId: String) {
        sessionManager.invalidateThread(threadId)
        _snapshot.update { current ->
            if (current.activeSessionCache?.threadId == threadId) {
                current.copy(activeSessionCache = null, lastContextBudget = null)
            } else {
                current
            }
        }
    }

    fun syncBackendState() {
        recomputeSchedulingSnapshot()
    }

    private fun updateThermalInSnapshot(severity: ThermalSeverity) {
        val thermalAuto = synchronized(configLock) { autoThermalDownshift }
        if (thermalAuto && (severity == ThermalSeverity.SEVERE || severity == ThermalSeverity.CRITICAL)) {
            calibrationCancelRequested = true
        }
        val caps = hardwareManager.getDeviceCapabilities()
        _snapshot.update { current ->
            val currentMetrics = current.liveMetrics ?: resourceManager.liveMetrics()
            val updatedMetrics = currentMetrics.copy(thermalSeverity = severity)
            val profile = current.hardwareProfile
            val probe = profile?.vulkanCapabilities ?: current.vulkanCapabilities
            val offloadPlan = computeOffloadPlanLocked(updatedMetrics, probe)
            if (profile == null) {
                val thermalState = buildThermalStateLocked(severity, caps, updatedMetrics, current.recommendedThreads)
                current.copy(
                    liveMetrics = updatedMetrics,
                    requestedScheduleMode = requestedMode,
                    autoThermalDownshift = autoThermalDownshift,
                    gpuOffloadPlan = offloadPlan,
                    deviceCapabilities = caps,
                    thermalState = thermalState
                )
            } else {
                val decision = computeDecisionLocked(profile, severity, lastWorkload)
                val thermalState = buildThermalStateLocked(severity, caps, updatedMetrics, decision.effectiveThreads)
                current.copy(
                    liveMetrics = updatedMetrics,
                    recommendedThreads = decision.effectiveThreads,
                    requestedScheduleMode = requestedMode,
                    autoThermalDownshift = autoThermalDownshift,
                    schedulingDecision = decision,
                    modeThreadPreview = computeModePreviewsLocked(profile, severity),
                    gpuOffloadPlan = offloadPlan,
                    deviceCapabilities = caps,
                    thermalState = thermalState
                )
            }
        }
        if (thermalAuto && severity == ThermalSeverity.CRITICAL) {
            triggerCriticalThermalRelease()
        }
    }

    private fun triggerCriticalThermalRelease() {
        if (emergencyThermalJob?.isActive == true) return
        emergencyThermalJob = orchestratorScope.launch {
            runCatching { resilientBackend.cancel() }
            val hasLoadedResources = synchronized(configLock) { activeModelId != null } ||
                resilientBackend.lifecycleState.value is EngineLifecycleState.Ready ||
                resilientBackend.lifecycleState.value is EngineLifecycleState.Generating ||
                resilientBackend.lifecycleState.value is EngineLifecycleState.LoadingModel
            if (hasLoadedResources) {
                runCatching { unloadModel() }
            }
            emergencyReleaseCount++
            lastThermalEventNote = "CRITICAL thermal status: stopped active inference and released model memory."
            val metrics = resourceManager.liveMetrics()
            val caps = hardwareManager.getDeviceCapabilities()
            _snapshot.update { current ->
                current.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    liveMetrics = metrics,
                    deviceCapabilities = caps,
                    thermalState = buildThermalStateLocked(
                        severity = metrics.thermalSeverity,
                        caps = caps,
                        metrics = metrics,
                        fallbackThreads = 1
                    )
                )
            }
        }
    }

    private fun recomputeSchedulingSnapshot() {
        val metrics = resourceManager.liveMetrics()
        val caps = hardwareManager.getDeviceCapabilities()
        _snapshot.update { current ->
            val profile = current.hardwareProfile
            val probe = profile?.vulkanCapabilities ?: current.vulkanCapabilities
            val npuProbe = profile?.npuCapabilities ?: current.npuCapabilities
            val offloadPlan = computeOffloadPlanLocked(metrics, probe)
            val compatReport = computeCompatibilityReportLocked(npuProbe, probe)
            if (profile == null) {
                val thermalState = buildThermalStateLocked(metrics.thermalSeverity, caps, metrics, current.recommendedThreads)
                current.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    liveMetrics = metrics,
                    requestedScheduleMode = requestedMode,
                    autoThermalDownshift = autoThermalDownshift,
                    backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                    gpuOffloadPlan = offloadPlan,
                    acceleratorPreference = acceleratorPreference,
                    autoGpuLayerOffload = autoGpuLayerOffload,
                    manualGpuLayers = manualGpuLayers,
                    npuCapabilities = npuProbe,
                    npuCompatibilityReport = compatReport,
                    deviceCapabilities = caps,
                    thermalState = thermalState
                )
            } else {
                val decision = computeDecisionLocked(profile, metrics.thermalSeverity, lastWorkload)
                val thermalState = buildThermalStateLocked(metrics.thermalSeverity, caps, metrics, decision.effectiveThreads)
                current.copy(
                    lifecycleState = resilientBackend.lifecycleState.value,
                    versionInfo = resilientBackend.versionInfo,
                    capabilities = resilientBackend.capabilities,
                    liveMetrics = metrics,
                    recommendedThreads = decision.effectiveThreads,
                    requestedScheduleMode = requestedMode,
                    autoThermalDownshift = autoThermalDownshift,
                    schedulingDecision = decision,
                    modeThreadPreview = computeModePreviewsLocked(profile, metrics.thermalSeverity),
                    backendRuntimeStatus = resilientBackend.runtimeStatus.value,
                    vulkanCapabilities = probe,
                    gpuOffloadPlan = offloadPlan,
                    acceleratorPreference = acceleratorPreference,
                    autoGpuLayerOffload = autoGpuLayerOffload,
                    manualGpuLayers = manualGpuLayers,
                    npuCapabilities = npuProbe,
                    npuCompatibilityReport = compatReport,
                    deviceCapabilities = caps,
                    thermalState = thermalState
                )
            }
        }
    }

    private fun buildThermalStateLocked(
        severity: ThermalSeverity,
        caps: DeviceCapabilities?,
        metrics: LiveDeviceMetrics?,
        fallbackThreads: Int
    ): ThermalRuntimeActionState = synchronized(configLock) {
        ThermalGovernor.evaluate(
            severity = severity,
            deviceCapabilities = caps,
            liveMetrics = metrics,
            autoProtectionEnabled = autoThermalDownshift,
            requestedThreads = manualThreadsOverride ?: fallbackThreads.coerceAtLeast(1),
            emergencyReleaseCount = emergencyReleaseCount,
            lastThermalEventNote = lastThermalEventNote
        )
    }

    private fun computeCompatibilityReportLocked(
        npuProbe: NpuProbeResult?,
        vulkanProbe: VulkanProbeResult?
    ): NpuCompatibilityReport? = synchronized(configLock) {
        val modelId = activeModelId ?: return@synchronized null
        val path = activeModelFilePath ?: return@synchronized null
        val routedTarget = if (acceleratorPreference != AcceleratorPreference.CPU_ONLY &&
            vulkanProbe?.isUsableForInference == true
        ) {
            "Vulkan GPU (${vulkanProbe.deviceName ?: "GPU"})"
        } else {
            "CPU"
        }
        NpuCompatibilityEvaluator.evaluate(
            modelId = modelId,
            filePath = path,
            header = activeModelHeader,
            npuProbe = npuProbe,
            routedFallbackTarget = routedTarget,
            npuBackend = npuBackend ?: resilientBackend.npuInferenceBackend()
        )
    }

    private fun computeOffloadPlanLocked(
        metrics: LiveDeviceMetrics,
        probe: VulkanProbeResult?
    ): GpuOffloadPlan = synchronized(configLock) {
        GpuOffloadPlanner.planOffload(
            fileSizeBytes = activeModelFileSizeBytes,
            header = activeModelHeader,
            contextSize = activeModelContextSize,
            availableRamMb = metrics.availableRamMb,
            lowMemory = metrics.lowMemory,
            vulkanProbe = probe,
            preference = acceleratorPreference,
            autoOffload = autoGpuLayerOffload,
            manualLayersOverride = manualGpuLayers,
            thermalSeverity = if (autoThermalDownshift) metrics.thermalSeverity else ThermalSeverity.NOMINAL
        )
    }

    private fun computeDecisionLocked(
        profile: DeviceHardwareProfile,
        thermal: ThermalSeverity,
        workload: WorkloadClass
    ): ThreadSchedulingDecision = synchronized(configLock) {
        val validCalibration = if (calibratedDeviceSignature == null ||
            calibratedDeviceSignature == profile.deviceTopologySignature
        ) {
            calibratedOptimalThreads
        } else {
            null
        }
        resourceManager.resolveSchedulingDecision(
            profile = profile,
            requestedMode = requestedMode,
            autoThermalDownshift = autoThermalDownshift,
            thermal = thermal,
            workload = workload,
            calibratedOptimalThreads = validCalibration,
            manualThreadsOverride = manualThreadsOverride
        )
    }

    private fun computeModePreviewsLocked(
        profile: DeviceHardwareProfile,
        thermal: ThermalSeverity
    ): Map<CpuScheduleMode, Int> = synchronized(configLock) {
        val validCalibration = if (calibratedDeviceSignature == null ||
            calibratedDeviceSignature == profile.deviceTopologySignature
        ) {
            calibratedOptimalThreads
        } else {
            null
        }
        CpuScheduleMode.entries.associateWith { mode ->
            resourceManager.resolveSchedulingDecision(
                profile = profile,
                requestedMode = mode,
                autoThermalDownshift = false,
                thermal = thermal,
                workload = WorkloadClass.STANDARD,
                calibratedOptimalThreads = validCalibration,
                manualThreadsOverride = null
            ).effectiveThreads
        }
    }
}
