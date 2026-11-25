package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActiveDownloadState
import com.example.data.ChatMessage
import com.example.data.ChatRole
import com.example.data.ChatThread
import com.example.data.CuratedModelsCatalog
import com.example.data.DownloadPhase
import com.example.data.FileInspectionUiState
import com.example.data.HfAuthRequiredException
import com.example.data.HfAuthState
import com.example.data.HfNetworkException
import com.example.data.HubModelItem
import com.example.data.HubModelTab
import com.example.data.HubSearchUiState
import com.example.data.HuggingFaceModelRepository
import com.example.data.InferenceSettings
import com.example.data.LocalRuntimeState
import com.example.data.LocalWeightFile
import com.example.data.ModelFileArtifact
import com.example.data.local.AppChatDatabase
import com.example.data.local.ChatRepository
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.engine.core.EngineError
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.NpuInferenceBackend
import com.quantchat.engine.llamacpp.LlamaCppBackend
import com.quantchat.engine.vulkan.VulkanLlamaBackend
import com.quantchat.engine.vulkan.VulkanNativeBridge
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.integration.LaunchAction
import com.quantchat.integration.SystemIntegrationBridge
import com.quantchat.model.LocalModelRegistry
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

private const val TAG = "QuantTerminalVM"

sealed interface PendingExternalNavigation {
    data class OpenChatWithDraft(
        val threadId: String,
        val prefillText: String,
        val sourceLabel: String
    ) : PendingExternalNavigation

    data object OpenModelsTab : PendingExternalNavigation
}

class QuantTerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val hfRepository = HuggingFaceModelRepository(appContext)
    private val chatRepository = ChatRepository(
        AppChatDatabase.getInstance(appContext).chatDao()
    )

    private val cpuBackend: InferenceBackend = LlamaCppBackend(appContext.contentResolver)
    private val vulkanBackend: InferenceBackend = VulkanLlamaBackend()
    private val npuBackend = NpuInferenceBackend()
    private val hardwareManager = HardwareCapabilityManager(
        context = appContext,
        vulkanNativeProbe = { VulkanNativeBridge.probeVulkanCapabilities() },
        npuNativeProbe = { VulkanNativeBridge.probeNpuRuntime() }
    )
    private val resourceManager = RuntimeResourceManager(hardwareManager)
    private val modelRegistry = LocalModelRegistry(hfRepository.modelsDirectory)
    private val sessionManager = SessionCacheManager(File(appContext.filesDir, "sessions"))

    private val orchestrator = RuntimeOrchestrator(
        primaryBackend = cpuBackend,
        hardwareManager = hardwareManager,
        resourceManager = resourceManager,
        modelRegistry = modelRegistry,
        sessionManager = sessionManager,
        vulkanBackend = vulkanBackend,
        vulkanProbeProvider = { hardwareManager.probeVulkanCapabilities() },
        npuBackend = npuBackend
    )

    private val localHttpServer = LocalHttpServer(LocalOpenAiRouter(orchestrator.backend))

    val orchestratorSnapshot: StateFlow<OrchestratorSnapshot> = orchestrator.snapshot
    val localServerState: StateFlow<LocalServerState> = localHttpServer.state

    val threads: StateFlow<List<ChatThread>> = chatRepository.allThreads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messagesByThread: StateFlow<Map<String, List<ChatMessage>>> = chatRepository.allMessagesByThread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    private val _runtimeState = MutableStateFlow<LocalRuntimeState>(LocalRuntimeState.Unloaded())
    val runtimeState: StateFlow<LocalRuntimeState> = _runtimeState.asStateFlow()

    private val _inferenceSettings = MutableStateFlow(InferenceSettings())
    val inferenceSettings: StateFlow<InferenceSettings> = _inferenceSettings.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // --- Models Directory & Real OkHttp Streaming State ---
    private val _curatedModels = MutableStateFlow(CuratedModelsCatalog.curatedModels)
    val curatedModels: StateFlow<List<HubModelItem>> = _curatedModels.asStateFlow()

    private val _selectedModelTab = MutableStateFlow(HubModelTab.CURATED)
    val selectedModelTab: StateFlow<HubModelTab> = _selectedModelTab.asStateFlow()

    private val _modelSearchQuery = MutableStateFlow("")
    val modelSearchQuery: StateFlow<String> = _modelSearchQuery.asStateFlow()

    private val _hubSearchState = MutableStateFlow<HubSearchUiState>(HubSearchUiState.Idle)
    val hubSearchState: StateFlow<HubSearchUiState> = _hubSearchState.asStateFlow()

    private val _fileInspectionState = MutableStateFlow<FileInspectionUiState>(FileInspectionUiState.Closed)
    val fileInspectionState: StateFlow<FileInspectionUiState> = _fileInspectionState.asStateFlow()

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

    private val _pendingExternalNavigation = MutableStateFlow<PendingExternalNavigation?>(null)
    val pendingExternalNavigation: StateFlow<PendingExternalNavigation?> = _pendingExternalNavigation.asStateFlow()

    private var downloadJob: Job? = null
    private var loadJob: Job? = null
    private var generationJob: Job? = null
    private var metadataJob: Job? = null
    private var calibrationJob: Job? = null

    init {
        viewModelScope.launch {
            orchestrator.initialize()
        }
        viewModelScope.launch {
            hfRepository.hfTokenFlow.collect { token ->
                _hfAuthState.value = HfAuthState(token = token, isLoaded = true)
            }
        }
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    fun handleLaunchIntent(intent: Intent?) {
        when (val action = SystemIntegrationBridge.parseLaunchIntent(intent)) {
            is LaunchAction.PrefillSharedPrompt -> {
                viewModelScope.launch {
                    val threadId = createNewThreadImmediate()
                    _pendingExternalNavigation.value = PendingExternalNavigation.OpenChatWithDraft(
                        threadId = threadId,
                        prefillText = action.text,
                        sourceLabel = action.sourceLabel
                    )
                    _snackbarMessage.value = "Imported text from ${action.sourceLabel} into local draft."
                }
            }

            is LaunchAction.OpenModelsTab -> {
                _pendingExternalNavigation.value = PendingExternalNavigation.OpenModelsTab
            }

            LaunchAction.None -> Unit
        }
    }

    fun consumeExternalNavigation() {
        _pendingExternalNavigation.value = null
    }

    fun refreshHardwareDiagnostics() {
        viewModelScope.launch {
            orchestrator.refreshDiagnostics()
        }
    }

    fun runVulkanSelfTest() {
        viewModelScope.launch {
            val probe = orchestrator.runVulkanSelfTest()
            _snackbarMessage.value = if (probe.isUsableForInference) {
                "Vulkan verified: ${probe.deviceName ?: "GPU"} (${probe.vulkanApiVersionLabel ?: probe.systemVulkanVersion})"
            } else {
                "GPU probe: ${probe.status.label} — ${probe.reason ?: probe.selfTestSummary}"
            }
        }
    }

    fun runNpuProbe() {
        viewModelScope.launch {
            val probe = orchestrator.runNpuProbe()
            _snackbarMessage.value = "NPU probe: ${probe.summary}"
        }
    }

    fun selectAcceleratorPreference(preference: AcceleratorPreference) {
        viewModelScope.launch {
            val current = _inferenceSettings.value
            val updated = current.copy(acceleratorPreference = preference)
            hfRepository.saveInferenceSettings(updated)
            orchestrator.updateAcceleratorConfig(
                preference = updated.acceleratorPreference,
                autoGpuLayerOffload = updated.autoGpuLayerOffload,
                manualGpuLayers = updated.manualGpuLayers
            )
            val ready = _runtimeState.value as? LocalRuntimeState.Ready
            if (ready != null && current.acceleratorPreference != preference) {
                sessionManager.invalidateAll()
                loadLocalModel(ready.file)
                _snackbarMessage.value = "Switched backend preference to ${preference.label} — remapping ${ready.file.displayFileName}..."
            } else {
                _snackbarMessage.value = "Accelerator preference: ${preference.label}"
            }
        }
    }

    fun runHardwareBenchmark() {
        if (_isGenerating.value) {
            _snackbarMessage.value = "Wait for active generation to finish before benchmarking."
            return
        }
        if (_runtimeState.value !is LocalRuntimeState.Ready) {
            _snackbarMessage.value = "Load a local GGUF model first to benchmark real hardware throughput."
            return
        }
        viewModelScope.launch {
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
                Log.e(TAG, "Benchmark failed", e)
                _snackbarMessage.value = e.localizedMessage ?: "Hardware benchmark failed."
            }
        }
    }

    fun selectScheduleMode(mode: CpuScheduleMode) {
        viewModelScope.launch {
            val current = _inferenceSettings.value
            val updated = current.copy(
                scheduleMode = mode,
                manualThreadsOverride = null
            )
            hfRepository.saveInferenceSettings(updated)
            orchestrator.updateScheduleConfig(
                mode = updated.scheduleMode,
                autoThermalDownshift = updated.autoThermalDownshift,
                manualThreadsOverride = updated.manualThreadsOverride,
                calibratedOptimalThreads = updated.calibratedOptimalThreads,
                calibratedDeviceSignature = updated.calibratedDeviceSignature
            )
            val resolved = orchestrator.snapshot.value.schedulingDecision
            val activeLabel = resolved?.effectiveThreads ?: updated.threads
            _snackbarMessage.value = if (resolved?.isThermallyThrottled == true) {
                "Thermal protection active (${resolved.effectiveMode.label} · ${activeLabel}T)"
            } else {
                "CPU profile: ${mode.label} (${activeLabel}T, instant apply)"
            }
        }
    }

    fun toggleAutoThermalDownshift(enabled: Boolean) {
        viewModelScope.launch {
            val current = _inferenceSettings.value
            val updated = current.copy(autoThermalDownshift = enabled)
            hfRepository.saveInferenceSettings(updated)
            orchestrator.updateScheduleConfig(
                mode = updated.scheduleMode,
                autoThermalDownshift = updated.autoThermalDownshift,
                manualThreadsOverride = updated.manualThreadsOverride,
                calibratedOptimalThreads = updated.calibratedOptimalThreads,
                calibratedDeviceSignature = updated.calibratedDeviceSignature
            )
            _snackbarMessage.value = if (enabled) {
                "Auto Thermal Safe protection enabled."
            } else {
                "Auto Thermal Safe protection disabled."
            }
        }
    }

    fun calibrateOptimalThreads() {
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
        calibrationJob = viewModelScope.launch {
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
                Log.e(TAG, "Thread calibration failed", e)
                _snackbarMessage.value = e.localizedMessage ?: "Thread calibration failed."
            }
        }
    }

    fun cancelCalibration() {
        viewModelScope.launch {
            orchestrator.cancelCalibration()
            calibrationJob?.cancel()
        }
    }

    fun refreshLocalWeights() {
        val baseFiles = hfRepository.listLocalWeightFiles()
        _localWeightFiles.value = baseFiles
        _isOnline.value = hfRepository.isNetworkConnected()

        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
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
                    file.copy(headerMetadata = header, admission = admission)
                }
            }
            _localWeightFiles.value = enriched
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    // --- Chats Actions (Room Persisted) ---

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    suspend fun createNewThreadImmediate(): String {
        val id = "chat_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val activeModelName = (_runtimeState.value as? LocalRuntimeState.Ready)?.file?.displayFileName
        val thread = ChatThread(
            id = id,
            title = "New Chat",
            preview = if (activeModelName != null) "Ready with $activeModelName" else "Offline local chat",
            modelFileName = activeModelName,
            updatedAtMs = now,
            timestampLabel = ChatRepository.formatTimestamp(now)
        )
        chatRepository.upsertThread(thread)
        return id
    }

    fun deleteThread(threadId: String) {
        viewModelScope.launch {
            if (_streamingMessage.value?.threadId == threadId) {
                stopGeneration()
            }
            orchestrator.invalidateThreadSession(threadId)
            chatRepository.deleteThread(threadId)
            _snackbarMessage.value = "Conversation deleted."
        }
    }

    fun clearThreadMessages(threadId: String) {
        viewModelScope.launch {
            if (_streamingMessage.value?.threadId == threadId) {
                stopGeneration()
            }
            orchestrator.invalidateThreadSession(threadId)
            chatRepository.clearThreadMessages(threadId)
            val existing = threads.value.firstOrNull { it.id == threadId } ?: return@launch
            val now = System.currentTimeMillis()
            chatRepository.upsertThread(
                existing.copy(
                    preview = "Conversation cleared",
                    updatedAtMs = now,
                    timestampLabel = ChatRepository.formatTimestamp(now)
                )
            )
            _snackbarMessage.value = "Messages cleared."
        }
    }

    // --- On-Device llama.cpp Model Lifecycle ---

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
        loadJob = viewModelScope.launch {
            if (_isGenerating.value || generationJob?.isActive == true) {
                orchestrator.stopGeneration()
                generationJob?.join()
            }
            _runtimeState.value = LocalRuntimeState.Loading(file)
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
            } catch (e: EngineError) {
                Log.e(TAG, "EngineError loading local GGUF model", e)
                _runtimeState.value = LocalRuntimeState.Error(
                    file = file,
                    message = e.message
                )
                _snackbarMessage.value = "Failed to load ${file.displayFileName}."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load local GGUF model", e)
                _runtimeState.value = LocalRuntimeState.Error(
                    file = file,
                    message = e.localizedMessage ?: "Failed to initialize native llama.cpp context."
                )
                _snackbarMessage.value = "Failed to load ${file.displayFileName}."
            }
        }
    }

    fun unloadLocalModel() {
        loadJob?.cancel()
        viewModelScope.launch {
            if (_isGenerating.value || generationJob?.isActive == true) {
                orchestrator.stopGeneration()
                generationJob?.join()
            }
            orchestrator.unloadModel()
            _runtimeState.value = LocalRuntimeState.Unloaded("Model unloaded from memory.")
            hfRepository.saveLastLoadedModel(null)
            _snackbarMessage.value = "Unloaded local model to free RAM."
        }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            orchestrator.stopGeneration()
        }
    }

    fun sendChatMessage(threadId: String, rawText: String) {
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

        val currentThread = threads.value.firstOrNull { it.id == threadId }
        val updatedTitle = if (currentThread == null || currentThread.title == "New Chat") {
            promptText.replace('\n', ' ').trim().take(40)
        } else {
            currentThread.title
        }

        generationJob?.cancel()
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
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
            } catch (e: Exception) {
                Log.e(TAG, "Local llama.cpp generation failed", e)
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
                orchestrator.syncBackendState()
            }
        }
    }

    fun updateInferenceSettings(newSettings: InferenceSettings) {
        val oldSettings = _inferenceSettings.value
        viewModelScope.launch {
            orchestrator.updateScheduleConfig(
                mode = newSettings.scheduleMode,
                autoThermalDownshift = newSettings.autoThermalDownshift,
                manualThreadsOverride = newSettings.manualThreadsOverride,
                calibratedOptimalThreads = newSettings.calibratedOptimalThreads,
                calibratedDeviceSignature = newSettings.calibratedDeviceSignature
            )
            orchestrator.updateAcceleratorConfig(
                preference = newSettings.acceleratorPreference,
                autoGpuLayerOffload = newSettings.autoGpuLayerOffload,
                manualGpuLayers = newSettings.manualGpuLayers
            )
            val resolvedThreads = orchestrator.snapshot.value.schedulingDecision?.effectiveThreads
                ?: newSettings.manualThreadsOverride
                ?: newSettings.threads
            val syncedSettings = newSettings.copy(threads = resolvedThreads)
            hfRepository.saveInferenceSettings(syncedSettings)

            val accelChanged = oldSettings.acceleratorPreference != syncedSettings.acceleratorPreference ||
                oldSettings.autoGpuLayerOffload != syncedSettings.autoGpuLayerOffload ||
                (!syncedSettings.autoGpuLayerOffload && oldSettings.manualGpuLayers != syncedSettings.manualGpuLayers)
            val needsReload = oldSettings.contextSize != syncedSettings.contextSize || accelChanged
            if (needsReload || oldSettings.systemPrompt != syncedSettings.systemPrompt) {
                sessionManager.invalidateAll()
            }
            val ready = _runtimeState.value as? LocalRuntimeState.Ready
            if (needsReload && ready != null) {
                loadLocalModel(ready.file)
                _snackbarMessage.value = if (accelChanged) {
                    "Accelerator/offload updated — remapping ${ready.file.displayFileName}..."
                } else {
                    "Context window updated — reloading ${ready.file.displayFileName}..."
                }
            } else {
                val decision = orchestrator.snapshot.value.schedulingDecision
                val modeText = decision?.effectiveMode?.label ?: syncedSettings.scheduleMode.label
                _snackbarMessage.value = String.format(
                    Locale.US,
                    "Saved · %s (%dT), %s, ctx=%d",
                    modeText,
                    resolvedThreads,
                    syncedSettings.acceleratorPreference.label,
                    syncedSettings.contextSize
                )
            }
            refreshLocalWeights()
        }
    }

    // --- Models Directory, Local Import & Hugging Face Hub Actions ---

    fun selectModelTab(tab: HubModelTab) {
        _selectedModelTab.value = tab
        if (tab == HubModelTab.DOWNLOADED) {
            refreshLocalWeights()
        }
    }

    fun updateModelSearchQuery(query: String) {
        _modelSearchQuery.value = query
    }

    fun importLocalGguf(uri: Uri) {
        if (_isImportingLocalFile.value) return
        viewModelScope.launch {
            _isImportingLocalFile.value = true
            try {
                val imported = hfRepository.importLocalGgufUri(uri)
                refreshLocalWeights()
                _selectedModelTab.value = HubModelTab.DOWNLOADED
                _snackbarMessage.value = "Imported ${imported.displayFileName}. Tap Load to run offline."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import GGUF URI", e)
                _snackbarMessage.value = e.localizedMessage ?: "Unable to import selected GGUF file."
            } finally {
                _isImportingLocalFile.value = false
            }
        }
    }

    fun searchHuggingFaceHub() {
        val query = _modelSearchQuery.value.trim()
        if (query.isEmpty()) {
            _snackbarMessage.value = "Enter a model name (e.g. Qwen3, Gemma, Llama, DeepSeek) before searching Hub."
            return
        }
        _isOnline.value = hfRepository.isNetworkConnected()
        _selectedModelTab.value = HubModelTab.HUB_RESULTS
        _hubSearchState.value = HubSearchUiState.Loading

        viewModelScope.launch {
            try {
                val token = _hfAuthState.value.token.takeIf { it.isNotBlank() }
                val results = hfRepository.searchGgufModels(query, token)
                _hubSearchState.value = HubSearchUiState.Success(query = query, items = results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "HF Hub search failed", e)
                _hubSearchState.value = HubSearchUiState.Error(
                    e.localizedMessage ?: "Failed to query Hugging Face Hub."
                )
            }
        }
    }

    fun inspectModelFiles(model: HubModelItem, useOpenFallback: Boolean = false) {
        val targetRepo = if (useOpenFallback && !model.fallbackOpenRepoId.isNullOrBlank()) {
            model.fallbackOpenRepoId
        } else {
            model.repoId
        }
        val effectiveModel = model.copy(repoId = targetRepo)
        _isOnline.value = hfRepository.isNetworkConnected()
        _fileInspectionState.value = FileInspectionUiState.Loading(effectiveModel)

        viewModelScope.launch {
            try {
                val token = _hfAuthState.value.token.takeIf { it.isNotBlank() }
                val files = hfRepository.fetchModelGgufFiles(targetRepo, token)
                if (files.isEmpty()) {
                    _fileInspectionState.value = FileInspectionUiState.Error(
                        model = effectiveModel,
                        message = "No .gguf files found in $targetRepo.",
                        authRequired = false
                    )
                } else {
                    _fileInspectionState.value = FileInspectionUiState.Ready(effectiveModel, files)
                }
            } catch (e: HfAuthRequiredException) {
                _fileInspectionState.value = FileInspectionUiState.Error(
                    model = model,
                    message = e.message ?: "Hugging Face token required.",
                    authRequired = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inspect model files", e)
                _fileInspectionState.value = FileInspectionUiState.Error(
                    model = effectiveModel,
                    message = e.localizedMessage ?: "Unable to fetch repository file list.",
                    authRequired = false
                )
            }
        }
    }

    fun closeFileInspection() {
        _fileInspectionState.value = FileInspectionUiState.Closed
    }

    fun startArtifactDownload(model: HubModelItem, artifact: ModelFileArtifact) {
        _fileInspectionState.value = FileInspectionUiState.Closed
        startStreamDownload(
            modelId = model.id,
            modelDisplayName = "${model.displayName} (${artifact.quantTag})",
            repoId = artifact.repoId,
            fallbackOpenRepoId = model.fallbackOpenRepoId,
            fileName = artifact.rfilename,
            downloadUrl = artifact.downloadUrl,
            initialTotalBytes = artifact.sizeBytes
        )
    }

    fun resumeLocalPartialFile(file: LocalWeightFile) {
        val url = file.downloadUrl ?: return
        startStreamDownload(
            modelId = file.diskFileName,
            modelDisplayName = file.displayFileName,
            repoId = file.repoId,
            fallbackOpenRepoId = null,
            fileName = file.displayFileName,
            downloadUrl = url,
            initialTotalBytes = 0L
        )
    }

    private fun startStreamDownload(
        modelId: String,
        modelDisplayName: String,
        repoId: String,
        fallbackOpenRepoId: String?,
        fileName: String,
        downloadUrl: String,
        initialTotalBytes: Long
    ) {
        downloadJob?.cancel()
        hfRepository.cancelActiveHttpCall()
        _isOnline.value = hfRepository.isNetworkConnected()

        _activeDownload.value = ActiveDownloadState(
            modelId = modelId,
            modelDisplayName = modelDisplayName,
            repoId = repoId,
            fallbackOpenRepoId = fallbackOpenRepoId,
            fileName = fileName,
            downloadUrl = downloadUrl,
            bytesDownloaded = 0L,
            totalBytes = initialTotalBytes,
            speedBytesPerSec = 0L,
            etaSeconds = 0,
            phase = DownloadPhase.DOWNLOADING,
            errorMessage = null
        )

        downloadJob = viewModelScope.launch {
            try {
                val token = _hfAuthState.value.token.takeIf { it.isNotBlank() }
                hfRepository.streamDownloadFile(
                    repoId = repoId,
                    rfilename = fileName,
                    downloadUrl = downloadUrl,
                    token = token
                ) { sample ->
                    _activeDownload.update { current ->
                        current.copy(
                            bytesDownloaded = sample.bytesDownloaded,
                            totalBytes = if (sample.totalBytes > 0L) sample.totalBytes else current.totalBytes,
                            speedBytesPerSec = sample.speedBytesPerSec,
                            etaSeconds = sample.etaSeconds,
                            phase = if (sample.completed) DownloadPhase.COMPLETED else DownloadPhase.DOWNLOADING,
                            errorMessage = null
                        )
                    }
                    if (sample.completed) {
                        refreshLocalWeights()
                        _snackbarMessage.value = "Downloaded $fileName. Ready to load in Downloaded tab."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HfAuthRequiredException) {
                Log.w(TAG, "HF auth required for $repoId", e)
                _activeDownload.update {
                    it.copy(
                        phase = DownloadPhase.ERROR_AUTH_REQUIRED,
                        errorMessage = e.message ?: "Hugging Face token required for gated repo."
                    )
                }
            } catch (e: HfNetworkException) {
                Log.e(TAG, "Network error downloading $fileName", e)
                _activeDownload.update {
                    it.copy(
                        phase = DownloadPhase.ERROR_NETWORK,
                        errorMessage = e.message ?: "Network error during download."
                    )
                }
                refreshLocalWeights()
            } catch (e: Exception) {
                Log.e(TAG, "Stream download failed for $fileName", e)
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

    fun pauseActiveDownload() {
        downloadJob?.cancel()
        downloadJob = null
        hfRepository.cancelActiveHttpCall()
        _activeDownload.update {
            if (it.phase == DownloadPhase.DOWNLOADING) it.copy(phase = DownloadPhase.PAUSED, speedBytesPerSec = 0L)
            else it
        }
        refreshLocalWeights()
    }

    fun resumeActiveDownload() {
        val current = _activeDownload.value
        if (current.downloadUrl.isBlank()) return
        startStreamDownload(
            modelId = current.modelId,
            modelDisplayName = current.modelDisplayName,
            repoId = current.repoId,
            fallbackOpenRepoId = current.fallbackOpenRepoId,
            fileName = current.fileName,
            downloadUrl = current.downloadUrl,
            initialTotalBytes = current.totalBytes
        )
    }

    fun switchFailedDownloadToOpenMirror() {
        val current = _activeDownload.value
        val fallbackRepo = current.fallbackOpenRepoId ?: return
        _activeDownload.value = ActiveDownloadState()
        val fallbackItem = HubModelItem(
            id = "${current.modelId}_mirror",
            displayName = current.modelDisplayName,
            paramsLabel = "4B",
            repoId = fallbackRepo,
            authorOrg = fallbackRepo.substringBefore("/"),
            summary = "Open GGUF community mirror",
            isGated = false,
            isCurated = false
        )
        inspectModelFiles(fallbackItem, useOpenFallback = false)
    }

    fun dismissOrCancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        hfRepository.cancelActiveHttpCall()
        _activeDownload.value = ActiveDownloadState()
        refreshLocalWeights()
    }

    fun deleteLocalFile(file: LocalWeightFile) {
        viewModelScope.launch {
            val active = _runtimeState.value
            val isCurrentlyLoaded = (active as? LocalRuntimeState.Ready)?.file?.diskFileName == file.diskFileName ||
                (active as? LocalRuntimeState.Loading)?.file?.diskFileName == file.diskFileName
            if (isCurrentlyLoaded) {
                loadJob?.cancel()
                if (_isGenerating.value) {
                    orchestrator.stopGeneration()
                }
                orchestrator.unloadModel()
                _runtimeState.value = LocalRuntimeState.Unloaded()
                hfRepository.saveLastLoadedModel(null)
            }
            if (hfRepository.deleteLocalWeightFile(file.diskFileName)) {
                refreshLocalWeights()
                _snackbarMessage.value = "Removed ${file.displayFileName} from device storage."
            }
        }
    }

    fun saveHfToken(token: String) {
        viewModelScope.launch {
            hfRepository.saveHfToken(token)
            _snackbarMessage.value = if (token.isBlank()) {
                "Hugging Face token cleared."
            } else {
                "Hugging Face Bearer token saved locally."
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        loadJob?.cancel()
        generationJob?.cancel()
        metadataJob?.cancel()
        calibrationJob?.cancel()
        hfRepository.cancelActiveHttpCall()
        localHttpServer.stop()
        orchestrator.shutdown()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            orchestrator.unloadModel()
        }
    }
}
