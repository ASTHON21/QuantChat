package com.example.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.MainActivity
import com.example.data.local.AppChatDatabase
import com.example.data.local.ChatRepository
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.engine.core.EngineError
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.MmapLifecycleRegistry
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.NpuInferenceBackend
import com.quantchat.engine.core.SafeDeleteCheck
import com.quantchat.engine.llamacpp.LlamaCppBackend
import com.quantchat.engine.vulkan.VulkanLlamaBackend
import com.quantchat.engine.vulkan.VulkanNativeBridge
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.integration.BackgroundExecutionHost
import com.quantchat.integration.BackgroundServicesStatus
import com.quantchat.integration.BackgroundTaskRegistry
import com.quantchat.integration.DownloadServiceSnapshot
import com.quantchat.integration.ForegroundServiceLauncher
import com.quantchat.integration.InferenceServiceSnapshot
import com.quantchat.integration.MaintenanceReport
import com.quantchat.integration.ModelMaintenanceWorker
import com.quantchat.integration.SystemIntegrationBridge
import com.quantchat.model.GgufHeaderParser
import com.quantchat.model.LocalModelRegistry
import com.quantchat.model.ModelFileLifecycleManager
import com.quantchat.model.SafeDeleteOutcome
import com.quantchat.model.StorageSpaceSnapshot
import com.quantchat.resource.AdmissionDowngradeSuggestion
import com.quantchat.resource.AdmissionVerdict
import com.quantchat.resource.OrchestratorSnapshot
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.server.LocalHttpServer
import com.quantchat.server.LocalOpenAiRouter
import com.quantchat.server.LocalServerState
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

private const val HOST_TAG = "QuantRuntimeHost"
private const val STALE_PART_FILE_AGE_MS = 7L * 24L * 3600L * 1000L
private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L

data class BlockedAdmissionPrompt(
    val file: LocalWeightFile,
    val assessment: com.quantchat.resource.AdmissionAssessment
)

internal object StorageMaintenanceRunner {
    suspend fun sweep(
        hfRepository: HuggingFaceModelRepository,
        chatRepository: ChatRepository,
        sessionManager: SessionCacheManager,
        activeDownloadPartFileName: String? = null,
        hfToken: String? = null,
        checkUpdatesOnline: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): MaintenanceReport = withContext(Dispatchers.IO) {
        val stalePartsRemoved = ModelFileLifecycleManager.pruneExpiredPartFilesSafely(
            modelsDir = hfRepository.modelsDirectory,
            activePartFileName = activeDownloadPartFileName,
            maxAgeMs = STALE_PART_FILE_AGE_MS,
            nowMs = nowMs
        )
        var verifiedGguf = 0
        var corruptGguf = 0

        val files = hfRepository.modelsDirectory.listFiles().orEmpty()
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(".gguf", ignoreCase = true)) {
                if (GgufHeaderParser.isValidMagic(file)) {
                    verifiedGguf++
                } else {
                    corruptGguf++
                }
            }
        }

        val validThreads = runCatching { chatRepository.getAllThreadIdsSync() }.getOrNull()
        val prunedSessions = sessionManager.pruneOrphanedCaches(
            validThreadIds = validThreads,
            nowMs = nowMs
        )

        val updateNote = if (!checkUpdatesOnline) {
            "Online update check skipped"
        } else if (!hfRepository.isNetworkConnected()) {
            "Offline — deferred remote update check"
        } else {
            val trackedCurated = CuratedModelsCatalog.curatedModels.firstOrNull()
            if (trackedCurated != null) {
                runCatching {
                    val remoteFiles = hfRepository.fetchModelGgufFiles(trackedCurated.repoId, hfToken)
                    "Catalog synced (${remoteFiles.size} GGUF builds checked)"
                }.getOrElse {
                    "Remote catalog check skipped (${it.javaClass.simpleName})"
                }
            } else {
                "Up to date"
            }
        }

        val summary = buildString {
            append("$verifiedGguf GGUF verified")
            if (corruptGguf > 0) append(", $corruptGguf invalid")
            if (stalePartsRemoved > 0) append(", $stalePartsRemoved stale .part removed")
            if (prunedSessions > 0) append(", $prunedSessions orphan KV pruned")
            append(" · ").append(updateNote)
        }

        MaintenanceReport(
            ranAtMs = nowMs,
            stalePartFilesRemoved = stalePartsRemoved,
            verifiedGgufCount = verifiedGguf,
            corruptGgufCount = corruptGguf,
            prunedSessionFiles = prunedSessions,
            updateCheckSummary = updateNote,
            deferredByThermal = false,
            summary = summary
        )
    }
}

class QuantRuntimeHost internal constructor(
    context: Context,
    private val autoInitialize: Boolean = true
) : BackgroundExecutionHost {

    val appContext: Context = context.applicationContext
    val hostScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val hfRepository = HuggingFaceModelRepository(appContext)
    val chatRepository = ChatRepository(AppChatDatabase.getInstance(appContext).chatDao())

    private val cpuBackend: InferenceBackend = LlamaCppBackend(appContext.contentResolver)
    private val vulkanBackend: InferenceBackend = VulkanLlamaBackend()
    private val npuBackend = NpuInferenceBackend()

    val hardwareManager = HardwareCapabilityManager(
        context = appContext,
        vulkanNativeProbe = { VulkanNativeBridge.probeVulkanCapabilities() },
        npuNativeProbe = { VulkanNativeBridge.probeNpuRuntime() }
    )
    val resourceManager = RuntimeResourceManager(hardwareManager)
    val modelRegistry = LocalModelRegistry(hfRepository.modelsDirectory)
    val sessionManager = SessionCacheManager(File(appContext.filesDir, "sessions"))

    val orchestrator = RuntimeOrchestrator(
        primaryBackend = cpuBackend,
        hardwareManager = hardwareManager,
        resourceManager = resourceManager,
        modelRegistry = modelRegistry,
        sessionManager = sessionManager,
        vulkanBackend = vulkanBackend,
        vulkanProbeProvider = { hardwareManager.probeVulkanCapabilities() },
        npuBackend = npuBackend,
        strictArtifactVerification = true
    )

    val localHttpServer = LocalHttpServer(LocalOpenAiRouter(orchestrator.backend))

    val orchestratorSnapshot: StateFlow<OrchestratorSnapshot> = orchestrator.snapshot
    val localServerState: StateFlow<LocalServerState> = localHttpServer.state
    val backgroundServicesStatus: StateFlow<BackgroundServicesStatus> = BackgroundTaskRegistry.status

    val allThreads: Flow<List<ChatThread>> = chatRepository.allThreads
    val allMessagesByThread: Flow<Map<String, List<ChatMessage>>> = chatRepository.allMessagesByThread
    val hfTokenFlow: Flow<String> = hfRepository.hfTokenFlow
    val inferenceSettingsFlow: Flow<InferenceSettings> = hfRepository.inferenceSettingsFlow

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    private val _runtimeState = MutableStateFlow<LocalRuntimeState>(LocalRuntimeState.Unloaded())
    val runtimeState: StateFlow<LocalRuntimeState> = _runtimeState.asStateFlow()

    private val _inferenceSettings = MutableStateFlow(InferenceSettings())
    val inferenceSettings: StateFlow<InferenceSettings> = _inferenceSettings.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _activeDownload = MutableStateFlow(ActiveDownloadState())
    val activeDownload: StateFlow<ActiveDownloadState> = _activeDownload.asStateFlow()

    private val _localWeightFiles = MutableStateFlow<List<LocalWeightFile>>(emptyList())
    val localWeightFiles: StateFlow<List<LocalWeightFile>> = _localWeightFiles.asStateFlow()

    private val _hfAuthState = MutableStateFlow(HfAuthState())
    val hfAuthState: StateFlow<HfAuthState> = _hfAuthState.asStateFlow()

    private val _isOnline = MutableStateFlow(hfRepository.isNetworkConnected())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isImportingLocalFile = MutableStateFlow(false)
    val isImportingLocalFile: StateFlow<Boolean> = _isImportingLocalFile.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _blockedAdmissionPrompt = MutableStateFlow<BlockedAdmissionPrompt?>(null)
    val blockedAdmissionPrompt: StateFlow<BlockedAdmissionPrompt?> = _blockedAdmissionPrompt.asStateFlow()

    private val _storageSnapshot = MutableStateFlow(hfRepository.inspectStorageSpace())
    val storageSnapshot: StateFlow<StorageSpaceSnapshot> = _storageSnapshot.asStateFlow()

    private val _pendingDeleteConfirmation = MutableStateFlow<LocalWeightFile?>(null)
    val pendingDeleteConfirmation: StateFlow<LocalWeightFile?> = _pendingDeleteConfirmation.asStateFlow()

    @Volatile
    var downloadJob: Job? = null
        private set

    @Volatile
    var loadJob: Job? = null
        private set

    @Volatile
    var generationJob: Job? = null
        private set

    @Volatile
    var calibrationJob: Job? = null
        private set

    private var metadataJob: Job? = null
    private var lastDownloadNotifyMs: Long = 0L
    private var lastInferenceNotifyMs: Long = 0L

    init {
        BackgroundTaskRegistry.registerHost(this)
        BackgroundTaskRegistry.refreshNotificationPermission(appContext)
        if (autoInitialize) {
            startObserversAndRestore()
        }
    }

    private fun startObserversAndRestore() {
        hostScope.launch {
            orchestrator.initialize()
        }
        hostScope.launch {
            var lastHandledEmergencyCount = 0
            orchestrator.snapshot
                .map { it.thermalState }
                .distinctUntilChanged()
                .collect { thermal ->
                    if (thermal.nonEssentialTasksPaused && thermal.autoProtectionEnabled) {
                        if (_activeDownload.value.phase == DownloadPhase.DOWNLOADING) {
                            pauseActiveDownload(pausedByThermal = true)
                            _snackbarMessage.value =
                                "Thermal Guard (${thermal.severity.label}): paused active model download."
                        }
                        if (calibrationJob?.isActive == true) {
                            calibrationJob?.cancel()
                            _snackbarMessage.value =
                                "Thermal Guard (${thermal.severity.label}): paused thread calibration."
                        }
                    }
                    if (thermal.inferenceBlocked && thermal.autoProtectionEnabled) {
                        loadJob?.cancel()
                        if (_isGenerating.value || generationJob?.isActive == true) {
                            generationJob?.cancel()
                            _streamingMessage.value = null
                            _isGenerating.value = false
                            ForegroundServiceLauncher.stopInferenceService(
                                appContext,
                                "Stopped (Critical thermal protection)"
                            )
                        }
                        if (_runtimeState.value is LocalRuntimeState.Ready ||
                            _runtimeState.value is LocalRuntimeState.Loading ||
                            thermal.emergencyReleaseCount > lastHandledEmergencyCount
                        ) {
                            lastHandledEmergencyCount = thermal.emergencyReleaseCount
                            _runtimeState.value = LocalRuntimeState.Unloaded(
                                "Stopped & released due to Critical device temperature."
                            )
                            _snackbarMessage.value =
                                "CRITICAL temperature: stopped inference and safely released model resources."
                        }
                    }
                }
        }
        hostScope.launch {
            hfRepository.hfTokenFlow.collect { token ->
                _hfAuthState.value = HfAuthState(token = token, isLoaded = true)
            }
        }
        hostScope.launch {
            hfRepository.inferenceSettingsFlow.collect { settings ->
                _inferenceSettings.value = settings
                orchestrator.updateScheduleConfig(
                    mode = settings.scheduleMode,
                    autoThermalDownshift = settings.autoThermalDownshift,
                    manualThreadsOverride = settings.manualThreadsOverride,
                    calibratedOptimalThreads = settings.calibratedOptimalThreads,
                    calibratedDeviceSignature = settings.calibratedDeviceSignature
                )
                orchestrator.updateAcceleratorConfig(
                    preference = settings.acceleratorPreference,
                    autoGpuLayerOffload = settings.autoGpuLayerOffload,
                    manualGpuLayers = settings.manualGpuLayers
                )
            }
        }
        refreshLocalWeights()
        val pendingPart = hfRepository.restorePendingPartialDownload()
        if (pendingPart != null && _activeDownload.value.phase == DownloadPhase.IDLE) {
            _activeDownload.value = ActiveDownloadState(
                modelId = pendingPart.modelId.ifBlank { pendingPart.repoId },
                modelDisplayName = pendingPart.modelDisplayName.ifBlank { pendingPart.rfilename },
                repoId = pendingPart.repoId,
                fallbackOpenRepoId = null,
                fileName = pendingPart.rfilename,
                downloadUrl = pendingPart.downloadUrl,
                expectedSha256 = pendingPart.expectedSha256,
                bytesDownloaded = pendingPart.bytesOnDisk,
                totalBytes = pendingPart.expectedTotalBytes,
                speedBytesPerSec = 0L,
                etaSeconds = 0,
                phase = DownloadPhase.PAUSED,
                errorMessage = null,
                pausedByThermal = false
            )
        }
        hostScope.launch {
            val savedSettings = hfRepository.inferenceSettingsFlow.first()
            val lastFile = savedSettings.lastLoadedDiskFileName
            if (!lastFile.isNullOrBlank()) {
                val match = hfRepository.listLocalWeightFiles()
                    .firstOrNull { !it.isPartial && it.diskFileName == lastFile }
                if (match != null) {
                    loadLocalModel(match)
                }
            }
        }
    }

    // --- BackgroundExecutionHost implementation ---

    override fun isThermalBlockingNonEssentialMaintenance(): Boolean {
        val thermal = orchestrator.snapshot.value.thermalState
        return thermal.nonEssentialTasksPaused && thermal.autoProtectionEnabled
    }

    override fun onPauseDownloadFromNotification() {
        hostScope.launch {
            pauseActiveDownload(pausedByThermal = false)
        }
    }

    override fun onCancelDownloadFromNotification() {
        hostScope.launch {
            dismissOrCancelDownload()
        }
    }

    override fun onStopInferenceFromNotification() {
        hostScope.launch {
            stopGeneration()
        }
    }

    override suspend fun runMaintenanceSweep(checkUpdatesOnline: Boolean): MaintenanceReport {
        val activeDownloadName = _activeDownload.value
            .takeIf { it.phase == DownloadPhase.DOWNLOADING }
            ?.let { "${it.repoId.replace("/", "_slash_")}__${it.fileName.substringAfterLast("/")}.part" }
        val token = _hfAuthState.value.token.takeIf { it.isNotBlank() }

        val report = StorageMaintenanceRunner.sweep(
            hfRepository = hfRepository,
            chatRepository = chatRepository,
            sessionManager = sessionManager,
            activeDownloadPartFileName = activeDownloadName,
            hfToken = token,
            checkUpdatesOnline = checkUpdatesOnline
        )
        withContext(Dispatchers.Main.immediate) {
            refreshLocalWeights()
        }
        return report
    }

    override fun createLaunchMainIntent(openModelsTab: Boolean): Intent {
        return Intent(appContext, MainActivity::class.java).apply {
            if (openModelsTab) {
                action = SystemIntegrationBridge.ACTION_OPEN_MODELS
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun enqueueImmediateMaintenance() {
        ModelMaintenanceWorker.enqueueImmediateCheck(appContext, checkOnlineUpdates = true)
        _snackbarMessage.value = "Scheduled ModelMaintenanceWorker sweep."
    }

    fun refreshNotificationPermission() {
        BackgroundTaskRegistry.refreshNotificationPermission(appContext)
    }

    fun postSnackbar(message: String?) {
        _snackbarMessage.value = message
    }

    // --- Runtime & Orchestrator Controls ---

    fun loadLocalModel(file: LocalWeightFile) {
        if (file.isPartial) {
            _snackbarMessage.value = "Cannot load incomplete .part file. Resume download first."
            return
        }
        val diskFile = hfRepository.resolveLocalFile(file.diskFileName)
        if (diskFile == null) {
            _runtimeState.value = LocalRuntimeState.Error(file, "Weight file not found on device storage.")
            refreshLocalWeights()
            return
        }

        loadJob?.cancel()
        loadJob = hostScope.launch {
            if (_isGenerating.value || generationJob?.isActive == true) {
                orchestrator.stopGeneration()
                generationJob?.join()
            }
            _runtimeState.value = LocalRuntimeState.Loading(file)
            ForegroundServiceLauncher.startOrUpdateInferenceService(
                appContext,
                InferenceServiceSnapshot(
                    isRunning = true,
                    modelName = file.displayFileName,
                    phaseLabel = "Loading GGUF into native memory",
                    backendLabel = _inferenceSettings.value.acceleratorPreference.label
                )
            )
            try {
                val settings = _inferenceSettings.value
                val outcome = orchestrator.loadModel(
                    modelId = ModelId(file.diskFileName),
                    displayName = file.displayFileName,
                    diskFile = diskFile,
                    contextSize = settings.contextSize,
                    threads = settings.threads
                )
                _runtimeState.value = LocalRuntimeState.Ready(
                    file = file.copy(
                        headerMetadata = outcome.headerMetadata,
                        admission = outcome.admission
                    ),
                    contextId = outcome.readyState.contextId,
                    contextSize = settings.contextSize,
                    threads = settings.threads,
                    modelDetails = outcome.readyState.modelDetails,
                    headerMetadata = outcome.headerMetadata
                )
                hfRepository.saveLastLoadedModel(file.diskFileName)
                val backendStatus = orchestrator.snapshot.value.backendRuntimeStatus
                _snackbarMessage.value = when {
                    backendStatus.fallbackOccurred ->
                        "Vulkan fallback → safe CPU mode (${backendStatus.fallbackReason ?: "incompatible driver"})"
                    backendStatus.activeBackend == AcceleratorBackendType.VULKAN_GPU ->
                        "Loaded ${file.displayFileName} on ${backendStatus.activeDeviceLabel} (${backendStatus.appliedGpuLayers}/${backendStatus.totalModelLayers}L)"
                    outcome.admission.verdict == AdmissionVerdict.WARNING ->
                        "Loaded ${file.displayFileName} (${outcome.admission.summary})"
                    else ->
                        "Loaded ${file.displayFileName} into offline CPU runtime."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineError.InsufficientMemory) {
                Log.w(HOST_TAG, "Memory admission blocked model load: ${e.message}")
                val blocked = orchestrator.snapshot.value.lastAdmission
                if (blocked != null) {
                    _blockedAdmissionPrompt.value = BlockedAdmissionPrompt(
                        file = file.copy(admission = blocked),
                        assessment = blocked
                    )
                }
                _runtimeState.value = LocalRuntimeState.Unloaded("Blocked by memory admission guard")
                _snackbarMessage.value = blocked?.summary ?: e.message
                refreshLocalWeights()
            } catch (e: EngineError.ThermalShutdown) {
                Log.w(HOST_TAG, "Thermal guard blocked model load: ${e.message}")
                _runtimeState.value = LocalRuntimeState.Unloaded("Blocked by Critical thermal guard")
                _snackbarMessage.value = e.reason
            } catch (e: EngineError.InvalidModelFormat) {
                Log.w(HOST_TAG, "Model verification blocked load: ${e.message}")
                _runtimeState.value = LocalRuntimeState.Error(file = file, message = e.reason)
                _snackbarMessage.value = "Integrity check failed: ${e.reason}"
            } catch (e: EngineError.FileLocked) {
                Log.w(HOST_TAG, "File lock blocked model load: ${e.message}")
                _runtimeState.value = LocalRuntimeState.Unloaded(e.reason)
                _snackbarMessage.value = e.reason
            } catch (e: EngineError) {
                Log.e(HOST_TAG, "EngineError loading local GGUF model", e)
                _runtimeState.value = LocalRuntimeState.Error(file = file, message = e.message)
                _snackbarMessage.value = "Failed to load ${file.displayFileName}."
            } catch (e: Exception) {
                Log.e(HOST_TAG, "Failed to load local GGUF model", e)
                _runtimeState.value = LocalRuntimeState.Error(
                    file = file,
                    message = e.localizedMessage ?: "Failed to initialize native llama.cpp context."
                )
                _snackbarMessage.value = "Failed to load ${file.displayFileName}."
            } finally {
                if (!_isGenerating.value) {
                    ForegroundServiceLauncher.stopInferenceService(appContext, "Model ready / Idle")
                }
            }
        }
    }

    fun dismissBlockedAdmission() {
        _blockedAdmissionPrompt.value = null
    }

    fun applyDowngradeAndLoad(prompt: BlockedAdmissionPrompt, suggestion: AdmissionDowngradeSuggestion) {
        _blockedAdmissionPrompt.value = null
        hostScope.launch {
            val current = _inferenceSettings.value
            val downgraded = if (suggestion.targetGpuLayers == 0 && current.acceleratorPreference != AcceleratorPreference.CPU_ONLY) {
                current.copy(
                    contextSize = suggestion.targetContextSize,
                    autoGpuLayerOffload = false,
                    manualGpuLayers = 0,
                    acceleratorPreference = AcceleratorPreference.CPU_ONLY
                )
            } else {
                current.copy(contextSize = suggestion.targetContextSize)
            }
            _inferenceSettings.value = downgraded
            orchestrator.updateAcceleratorConfig(
                preference = downgraded.acceleratorPreference,
                autoGpuLayerOffload = downgraded.autoGpuLayerOffload,
                manualGpuLayers = downgraded.manualGpuLayers
            )
            hfRepository.saveInferenceSettings(downgraded)
            refreshLocalWeights()
            loadLocalModel(prompt.file)
        }
    }

    fun unloadLocalModel() {
        loadJob?.cancel()
        hostScope.launch {
            if (_isGenerating.value || generationJob?.isActive == true) {
                orchestrator.stopGeneration()
                generationJob?.join()
            }
            ForegroundServiceLauncher.stopInferenceService(appContext, "Unloaded")
            orchestrator.unloadModel()
            _runtimeState.value = LocalRuntimeState.Unloaded("Model unloaded from memory.")
            hfRepository.saveLastLoadedModel(null)
            _snackbarMessage.value = "Unloaded local model to free RAM."
        }
    }

    fun stopGeneration() {
        hostScope.launch {
            orchestrator.stopGeneration()
            ForegroundServiceLauncher.stopInferenceService(appContext, "Stopped by user")
        }
    }

    fun sendChatMessage(threadId: String, rawText: String, recentThreads: List<ChatThread>) {
        val promptText = rawText.trim()
        if (promptText.isEmpty() || _isGenerating.value) return

        val readyState = _runtimeState.value as? LocalRuntimeState.Ready
        if (readyState == null) {
            _snackbarMessage.value = "Load a downloaded .gguf model first to run offline chat."
            return
        }

        val nowMs = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = "usr_${UUID.randomUUID().toString().take(8)}",
            threadId = threadId,
            role = ChatRole.USER,
            text = promptText,
            timestampMs = nowMs,
            timestampLabel = ChatRepository.formatTimestamp(nowMs)
        )

        val currentThread = recentThreads.firstOrNull { it.id == threadId }
        val updatedTitle = if (currentThread == null || currentThread.title == "New Chat") {
            promptText.replace('\n', ' ').trim().take(40)
        } else {
            currentThread.title
        }

        generationJob?.cancel()
        _isGenerating.value = true
        lastInferenceNotifyMs = 0L

        ForegroundServiceLauncher.startOrUpdateInferenceService(
            appContext,
            InferenceServiceSnapshot(
                isRunning = true,
                modelName = readyState.file.displayFileName,
                phaseLabel = "Evaluating prompt",
                tokenCount = 0,
                backendLabel = orchestrator.snapshot.value.backendRuntimeStatus.activeBackend.label
            )
        )

        generationJob = hostScope.launch {
            try {
                val priorHistory = chatRepository.getRecentMessagesSync(threadId)
                    .filter { !it.isError && it.text.isNotBlank() }
                    .map { ChatTurn(role = it.role.raw, content = it.text) }

                chatRepository.upsertThread(
                    ChatThread(
                        id = threadId,
                        title = updatedTitle,
                        preview = promptText.replace('\n', ' ').take(90),
                        modelFileName = readyState.file.displayFileName,
                        updatedAtMs = nowMs,
                        timestampLabel = ChatRepository.formatTimestamp(nowMs)
                    )
                )
                chatRepository.upsertMessage(userMsg)

                val aiMsgId = "ai_${UUID.randomUUID().toString().take(8)}"
                val aiStartMs = System.currentTimeMillis()
                _streamingMessage.value = ChatMessage(
                    id = aiMsgId,
                    threadId = threadId,
                    role = ChatRole.ASSISTANT,
                    text = "",
                    modelLabel = readyState.file.displayFileName,
                    timestampMs = aiStartMs,
                    timestampLabel = ChatRepository.formatTimestamp(aiStartMs),
                    isStreaming = true
                )

                val wasFallbackBefore = orchestrator.snapshot.value.backendRuntimeStatus.fallbackOccurred
                val settings = _inferenceSettings.value
                val modelFamilyHint = "${readyState.file.repoId} ${readyState.file.displayFileName}"
                orchestrator.streamChat(
                    threadId = threadId,
                    modelId = ModelId(readyState.file.diskFileName),
                    systemPrompt = settings.systemPrompt,
                    history = priorHistory,
                    userPrompt = promptText,
                    modelFamilyHint = modelFamilyHint,
                    contextSize = settings.contextSize,
                    maxTokens = settings.maxTokens,
                    temperature = settings.temperature
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.TokenDelta -> {
                            _streamingMessage.update { current ->
                                current?.copy(
                                    text = event.accumulatedText,
                                    tokenCount = event.tokenCount,
                                    isStreaming = true
                                )
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastInferenceNotifyMs >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                                lastInferenceNotifyMs = now
                                ForegroundServiceLauncher.startOrUpdateInferenceService(
                                    appContext,
                                    InferenceServiceSnapshot(
                                        isRunning = true,
                                        modelName = readyState.file.displayFileName,
                                        phaseLabel = "Generating tokens",
                                        tokenCount = event.tokenCount,
                                        backendLabel = orchestrator.snapshot.value.backendRuntimeStatus.activeBackend.label
                                    )
                                )
                            }
                        }

                        is GenerationEvent.Completed -> {
                            val completedAt = System.currentTimeMillis()
                            val finalMsg = ChatMessage(
                                id = aiMsgId,
                                threadId = threadId,
                                role = ChatRole.ASSISTANT,
                                text = event.finalText,
                                modelLabel = readyState.file.displayFileName,
                                latencyMs = event.elapsedMs,
                                tokensPerSec = event.tokensPerSecond,
                                tokenCount = event.tokenCount,
                                timestampMs = completedAt,
                                timestampLabel = ChatRepository.formatTimestamp(completedAt),
                                isStreaming = false,
                                isError = false
                            )
                            chatRepository.upsertMessage(finalMsg)
                            chatRepository.upsertThread(
                                ChatThread(
                                    id = threadId,
                                    title = updatedTitle,
                                    preview = event.finalText.replace('\n', ' ').take(90),
                                    modelFileName = readyState.file.displayFileName,
                                    updatedAtMs = completedAt,
                                    timestampLabel = ChatRepository.formatTimestamp(completedAt)
                                )
                            )
                            _streamingMessage.value = null
                        }
                    }
                }
                val afterStatus = orchestrator.snapshot.value.backendRuntimeStatus
                if (!wasFallbackBefore && afterStatus.fallbackOccurred) {
                    _snackbarMessage.value =
                        "Vulkan fallback → CPU (${afterStatus.fallbackReason ?: "auto failover"})"
                }
            } catch (e: CancellationException) {
                _streamingMessage.value = null
                throw e
            } catch (e: EngineError.ThermalShutdown) {
                Log.w(HOST_TAG, "Generation halted by Critical thermal guard: ${e.message}")
                _streamingMessage.value = null
                _runtimeState.value = LocalRuntimeState.Unloaded("Stopped & released due to Critical device temperature.")
                _snackbarMessage.value = e.reason
            } catch (e: Exception) {
                Log.e(HOST_TAG, "Local llama.cpp generation failed", e)
                val errAt = System.currentTimeMillis()
                val errText = e.localizedMessage ?: "Local GGUF inference failed."
                val errMsg = ChatMessage(
                    id = "err_${UUID.randomUUID().toString().take(8)}",
                    threadId = threadId,
                    role = ChatRole.ASSISTANT,
                    text = errText,
                    modelLabel = readyState.file.displayFileName,
                    timestampMs = errAt,
                    timestampLabel = ChatRepository.formatTimestamp(errAt),
                    isStreaming = false,
                    isError = true
                )
                chatRepository.upsertMessage(errMsg)
                chatRepository.upsertThread(
                    ChatThread(
                        id = threadId,
                        title = updatedTitle,
                        preview = errText.replace('\n', ' ').take(90),
                        modelFileName = readyState.file.displayFileName,
                        updatedAtMs = errAt,
                        timestampLabel = ChatRepository.formatTimestamp(errAt)
                    )
                )
                _streamingMessage.value = null
            } finally {
                _streamingMessage.value = null
                _isGenerating.value = false
                ForegroundServiceLauncher.stopInferenceService(appContext, "Idle")
                orchestrator.syncBackendState()
            }
        }
    }

    fun startStreamDownload(
        modelId: String,
        modelDisplayName: String,
        repoId: String,
        fallbackOpenRepoId: String?,
        fileName: String,
        downloadUrl: String,
        initialTotalBytes: Long,
        expectedSha256: String? = null
    ) {
        val thermal = orchestrator.snapshot.value.thermalState
        if (thermal.nonEssentialTasksPaused && thermal.autoProtectionEnabled) {
            _snackbarMessage.value =
                "Download paused by Thermal Guard (${thermal.severity.label}). Wait for device to cool."
            return
        }

        val partDiskName = "${repoId.replace("/", "_slash_")}__${fileName.substringAfterLast("/")}.part"
        val existingPart = hfRepository.resolveLocalFile(partDiskName)
        val existingPartBytes = existingPart?.length() ?: 0L
        if (initialTotalBytes > 0L) {
            val spaceCheck = hfRepository.checkStorageForBytes(
                totalTargetBytes = initialTotalBytes,
                existingPartialBytes = existingPartBytes
            )
            _storageSnapshot.value = hfRepository.inspectStorageSpace()
            if (!spaceCheck.hasEnoughSpace) {
                val reqMb = (spaceCheck.totalRequiredWithBufferBytes / (1024L * 1024L)).coerceAtLeast(1L)
                val availMb = (spaceCheck.availableBytes / (1024L * 1024L)).coerceAtLeast(0L)
                val msg = "Insufficient storage for $fileName (~${reqMb}MB needed incl. safety buffer, ${availMb}MB free)."
                _activeDownload.value = ActiveDownloadState(
                    modelId = modelId,
                    modelDisplayName = modelDisplayName,
                    repoId = repoId,
                    fallbackOpenRepoId = fallbackOpenRepoId,
                    fileName = fileName,
                    downloadUrl = downloadUrl,
                    expectedSha256 = expectedSha256,
                    bytesDownloaded = existingPartBytes,
                    totalBytes = initialTotalBytes,
                    phase = DownloadPhase.ERROR_NETWORK,
                    errorMessage = msg
                )
                _snackbarMessage.value = msg
                return
            }
        }

        downloadJob?.cancel()
        hfRepository.cancelActiveHttpCall()
        _isOnline.value = hfRepository.isNetworkConnected()
        lastDownloadNotifyMs = 0L

        _activeDownload.value = ActiveDownloadState(
            modelId = modelId,
            modelDisplayName = modelDisplayName,
            repoId = repoId,
            fallbackOpenRepoId = fallbackOpenRepoId,
            fileName = fileName,
            downloadUrl = downloadUrl,
            expectedSha256 = expectedSha256,
            bytesDownloaded = existingPartBytes,
            totalBytes = initialTotalBytes,
            speedBytesPerSec = 0L,
            etaSeconds = 0,
            phase = DownloadPhase.DOWNLOADING,
            errorMessage = null,
            pausedByThermal = false
        )

        ForegroundServiceLauncher.startOrUpdateDownloadService(
            appContext,
            DownloadServiceSnapshot(
                isRunning = true,
                modelName = modelDisplayName,
                fileName = fileName,
                bytesDownloaded = existingPartBytes,
                totalBytes = initialTotalBytes,
                statusText = if (existingPartBytes > 0L) "Resuming download..." else "Starting download..."
            )
        )

        downloadJob = hostScope.launch {
            try {
                val token = _hfAuthState.value.token.takeIf { it.isNotBlank() }
                hfRepository.streamDownloadFile(
                    repoId = repoId,
                    rfilename = fileName,
                    downloadUrl = downloadUrl,
                    token = token,
                    expectedTotalBytes = initialTotalBytes,
                    expectedSha256 = expectedSha256,
                    modelId = modelId,
                    modelDisplayName = modelDisplayName
                ) { sample ->
                    _activeDownload.update { current ->
                        current.copy(
                            bytesDownloaded = sample.bytesDownloaded,
                            totalBytes = if (sample.totalBytes > 0L) sample.totalBytes else current.totalBytes,
                            speedBytesPerSec = sample.speedBytesPerSec,
                            etaSeconds = sample.etaSeconds,
                            phase = if (sample.completed) DownloadPhase.COMPLETED else DownloadPhase.DOWNLOADING,
                            errorMessage = null,
                            pausedByThermal = false
                        )
                    }
                    if (sample.completed) {
                        ForegroundServiceLauncher.stopDownloadService(
                            appContext,
                            finalStatusText = "Completed: $fileName"
                        )
                        refreshLocalWeights()
                        _snackbarMessage.value = "Downloaded $fileName. Ready to load in Downloaded tab."
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastDownloadNotifyMs >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                            lastDownloadNotifyMs = now
                            val current = _activeDownload.value
                            ForegroundServiceLauncher.startOrUpdateDownloadService(
                                appContext,
                                DownloadServiceSnapshot(
                                    isRunning = true,
                                    modelName = current.modelDisplayName,
                                    fileName = current.fileName,
                                    bytesDownloaded = sample.bytesDownloaded,
                                    totalBytes = if (sample.totalBytes > 0L) sample.totalBytes else current.totalBytes,
                                    speedBytesPerSec = sample.speedBytesPerSec,
                                    etaSeconds = sample.etaSeconds,
                                    statusText = "Downloading"
                                )
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HfAuthRequiredException) {
                Log.w(HOST_TAG, "HF auth required for $repoId", e)
                ForegroundServiceLauncher.stopDownloadService(appContext, "Auth required")
                _activeDownload.update {
                    it.copy(
                        phase = DownloadPhase.ERROR_AUTH_REQUIRED,
                        errorMessage = e.message ?: "Hugging Face token required for gated repo."
                    )
                }
            } catch (e: InsufficientStorageException) {
                Log.w(HOST_TAG, "Insufficient storage downloading $fileName: ${e.message}")
                ForegroundServiceLauncher.stopDownloadService(appContext, "Insufficient storage")
                _activeDownload.update {
                    it.copy(
                        phase = DownloadPhase.ERROR_NETWORK,
                        errorMessage = e.message ?: "Insufficient device storage."
                    )
                }
                _snackbarMessage.value = e.message
                refreshLocalWeights()
            } catch (e: HfNetworkException) {
                Log.e(HOST_TAG, "Network error downloading $fileName", e)
                ForegroundServiceLauncher.stopDownloadService(appContext, "Network error")
                _activeDownload.update {
                    it.copy(
                        phase = DownloadPhase.ERROR_NETWORK,
                        errorMessage = e.message ?: "Network error during download."
                    )
                }
                refreshLocalWeights()
            } catch (e: Exception) {
                Log.e(HOST_TAG, "Stream download failed for $fileName", e)
                ForegroundServiceLauncher.stopDownloadService(appContext, "Download failed")
                _activeDownload.update {
                    it.copy(
                        phase = DownloadPhase.ERROR_NETWORK,
                        errorMessage = e.localizedMessage ?: "Download failed."
                    )
                }
                refreshLocalWeights()
            }
        }
    }

    fun pauseActiveDownload(pausedByThermal: Boolean = false) {
        downloadJob?.cancel()
        downloadJob = null
        hfRepository.cancelActiveHttpCall()
        _activeDownload.update {
            if (it.phase == DownloadPhase.DOWNLOADING) {
                it.copy(
                    phase = DownloadPhase.PAUSED,
                    speedBytesPerSec = 0L,
                    pausedByThermal = pausedByThermal
                )
            } else {
                it
            }
        }
        ForegroundServiceLauncher.stopDownloadService(
            appContext,
            finalStatusText = if (pausedByThermal) "Paused by Thermal Guard" else "Paused",
            pausedByThermal = pausedByThermal
        )
        refreshLocalWeights()
    }

    fun dismissOrCancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        hfRepository.cancelActiveHttpCall()
        _activeDownload.value = ActiveDownloadState()
        ForegroundServiceLauncher.stopDownloadService(appContext, "Cancelled")
        refreshLocalWeights()
    }

    fun runHardwareBenchmark() {
        val thermal = orchestrator.snapshot.value.thermalState
        if (thermal.nonEssentialTasksPaused && thermal.autoProtectionEnabled) {
            _snackbarMessage.value = "Benchmark paused by Thermal Guard (${thermal.severity.label})."
            return
        }
        if (_isGenerating.value) {
            _snackbarMessage.value = "Wait for active generation to finish before benchmarking."
            return
        }
        if (_runtimeState.value !is LocalRuntimeState.Ready) {
            _snackbarMessage.value = "Load a local GGUF model first to benchmark real hardware throughput."
            return
        }
        hostScope.launch {
            try {
                val result = orchestrator.runBenchmark()
                _snackbarMessage.value = String.format(
                    Locale.US,
                    "Benchmark complete: PP %.1f tok/s · TG %.1f tok/s",
                    result.prefillTokensPerSec,
                    result.decodeTokensPerSec
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(HOST_TAG, "Benchmark failed", e)
                _snackbarMessage.value = e.localizedMessage ?: "Hardware benchmark failed."
            }
        }
    }

    fun calibrateOptimalThreads() {
        val thermal = orchestrator.snapshot.value.thermalState
        if (thermal.nonEssentialTasksPaused && thermal.autoProtectionEnabled) {
            _snackbarMessage.value = "Calibration paused by Thermal Guard (${thermal.severity.label})."
            return
        }
        if (_isGenerating.value) {
            _snackbarMessage.value = "Wait for active generation to finish before calibrating threads."
            return
        }
        if (_runtimeState.value !is LocalRuntimeState.Ready) {
            _snackbarMessage.value = "Load a local GGUF model first to calibrate optimal thread count."
            return
        }
        if (orchestrator.snapshot.value.calibrationProgress.running) return

        calibrationJob?.cancel()
        calibrationJob = hostScope.launch {
            try {
                val result = orchestrator.calibrateOptimalThreads()
                hfRepository.saveCalibratedThreads(result.optimalThreads, result.deviceSignature)
                val bestEntry = result.entries.firstOrNull { it.threads == result.optimalThreads }
                _snackbarMessage.value = if (bestEntry != null) {
                    String.format(
                        Locale.US,
                        "Calibrated optimal: %dT (PP %.1f · TG %.1f tok/s)",
                        result.optimalThreads,
                        bestEntry.prefillTokPerSec,
                        bestEntry.decodeTokPerSec
                    )
                } else {
                    "Calibrated optimal threads: ${result.optimalThreads}T"
                }
            } catch (_: CancellationException) {
                _snackbarMessage.value = "Thread calibration cancelled."
            } catch (e: Exception) {
                Log.e(HOST_TAG, "Thread calibration failed", e)
                _snackbarMessage.value = e.localizedMessage ?: "Thread calibration failed."
            }
        }
    }

    fun cancelCalibration() {
        hostScope.launch {
            orchestrator.cancelCalibration()
            calibrationJob?.cancel()
        }
    }

    fun refreshLocalWeights() {
        val baseFiles = hfRepository.listLocalWeightFiles()
        _localWeightFiles.value = baseFiles
        _storageSnapshot.value = hfRepository.inspectStorageSpace()
        _isOnline.value = hfRepository.isNetworkConnected()

        metadataJob?.cancel()
        metadataJob = hostScope.launch {
            val ctxSize = _inferenceSettings.value.contextSize
            val enriched = baseFiles.map { file ->
                if (file.isPartial) {
                    file
                } else {
                    val diskFile = hfRepository.resolveLocalFile(file.diskFileName)
                    val header = orchestrator.inspectModelMetadata(file.diskFileName)
                    val admission = diskFile?.let {
                        orchestrator.assessAdmission(it, header, ctxSize)
                    }
                    val hasLease = diskFile?.let { MmapLifecycleRegistry.hasActiveMappings(it) } ?: false
                    file.copy(
                        headerMetadata = header,
                        admission = admission,
                        hasActiveMmapLease = hasLease
                    )
                }
            }
            _localWeightFiles.value = enriched
        }
    }

    fun verifyLocalWeightFile(file: LocalWeightFile) {
        if (file.isPartial) {
            _snackbarMessage.value = "Cannot verify partial .part file until download completes."
            return
        }
        val diskFile = hfRepository.resolveLocalFile(file.diskFileName)
        if (diskFile == null) {
            _snackbarMessage.value = "${file.displayFileName} no longer exists on disk."
            refreshLocalWeights()
            return
        }
        hostScope.launch {
            _snackbarMessage.value = "Verifying GGUF header & SHA-256 for ${file.displayFileName}..."
            val result = orchestrator.verifyModelFile(
                diskFile = diskFile,
                expectedSizeBytes = file.expectedTotalBytes,
                expectedSha256 = file.expectedSha256,
                computeSha256 = true
            )
            refreshLocalWeights()
            _snackbarMessage.value = if (result.isValid) {
                val digestPreview = result.actualSha256?.take(12)?.let { " · sha256:$it…" } ?: ""
                "${result.verdict.label} (${file.displayFileName}$digestPreview)"
            } else {
                result.failureReason ?: "Verification failed for ${file.displayFileName}."
            }
        }
    }

    fun deleteLocalFile(file: LocalWeightFile) {
        val diskFile = hfRepository.resolveLocalFile(file.diskFileName)
        val active = _runtimeState.value
        val isCurrentlyLoaded = (active as? LocalRuntimeState.Ready)?.file?.diskFileName == file.diskFileName ||
            (active as? LocalRuntimeState.Loading)?.file?.diskFileName == file.diskFileName
        val hasMappings = diskFile != null && MmapLifecycleRegistry.hasActiveMappings(diskFile)

        if (isCurrentlyLoaded || hasMappings) {
            if (diskFile != null) {
                MmapLifecycleRegistry.recordBlockedDeleteAttempt(
                    file = diskFile,
                    reason = "Deletion paused for ${file.displayFileName}: active mmap mapping must be unloaded first."
                )
            }
            _pendingDeleteConfirmation.value = file
            return
        }

        executeSafeDelete(file)
    }

    fun dismissDeleteConfirmation() {
        _pendingDeleteConfirmation.value = null
    }

    fun confirmUnloadAndDeleteFile(file: LocalWeightFile) {
        _pendingDeleteConfirmation.value = null
        hostScope.launch {
            val active = _runtimeState.value
            val isCurrentlyLoaded = (active as? LocalRuntimeState.Ready)?.file?.diskFileName == file.diskFileName ||
                (active as? LocalRuntimeState.Loading)?.file?.diskFileName == file.diskFileName
            val diskFile = hfRepository.resolveLocalFile(file.diskFileName)
            val hasMappings = diskFile != null && MmapLifecycleRegistry.hasActiveMappings(diskFile)

            if (isCurrentlyLoaded || hasMappings) {
                loadJob?.cancel()
                if (_isGenerating.value || generationJob?.isActive == true) {
                    orchestrator.stopGeneration()
                    generationJob?.join()
                }
                ForegroundServiceLauncher.stopInferenceService(appContext, "Unloaded before delete")
                orchestrator.unloadModel()
                _runtimeState.value = LocalRuntimeState.Unloaded("Unloaded and unmapped before deletion.")
                hfRepository.saveLastLoadedModel(null)
            }

            if (diskFile != null && MmapLifecycleRegistry.canDeleteSafely(diskFile) !is SafeDeleteCheck.Allowed) {
                _snackbarMessage.value =
                    "Active mmap or lock still held on ${file.displayFileName}; retry after inference stops."
                refreshLocalWeights()
                return@launch
            }

            executeSafeDelete(file)
        }
    }

    private fun executeSafeDelete(file: LocalWeightFile) {
        hostScope.launch {
            when (val outcome = hfRepository.deleteLocalWeightFileSafely(file.diskFileName)) {
                is SafeDeleteOutcome.Deleted -> {
                    refreshLocalWeights()
                    _snackbarMessage.value = "Removed ${file.displayFileName} from device storage."
                }
                is SafeDeleteOutcome.BlockedActiveMapping -> {
                    _pendingDeleteConfirmation.value = file
                    _snackbarMessage.value =
                        "Blocked deletion: ${outcome.activeLeaseCount} active mmap lease(s) still held."
                }
                is SafeDeleteOutcome.BlockedWriteLock -> {
                    _snackbarMessage.value =
                        "Cannot delete ${file.displayFileName} while a download/write lock is active."
                }
                is SafeDeleteOutcome.NotFound -> {
                    refreshLocalWeights()
                }
                is SafeDeleteOutcome.Failed -> {
                    _snackbarMessage.value = outcome.reason
                }
            }
        }
    }

    fun setImportingLocalFile(importing: Boolean) {
        _isImportingLocalFile.value = importing
    }

    fun setOnlineState(online: Boolean) {
        _isOnline.value = online
    }

    fun clearActiveDownloadUi() {
        _activeDownload.value = ActiveDownloadState()
    }

    companion object {
        @Volatile
        private var INSTANCE: QuantRuntimeHost? = null

        fun getInstance(context: Context): QuantRuntimeHost {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: QuantRuntimeHost(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
