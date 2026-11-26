package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActiveDownloadState
import com.example.data.ChatMessage
import com.example.data.ChatThread
import com.example.data.CuratedModelsCatalog
import com.example.data.FileInspectionUiState
import com.example.data.HfAuthRequiredException
import com.example.data.HfAuthState
import com.example.data.HubModelItem
import com.example.data.HubModelTab
import com.example.data.HubSearchUiState
import com.example.data.InferenceSettings
import com.example.data.LocalRuntimeState
import com.example.data.LocalWeightFile
import com.example.data.ModelFileArtifact
import com.example.data.QuantRuntimeHost
import com.example.data.local.ChatRepository
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.integration.BackgroundServicesStatus
import com.quantchat.integration.LaunchAction
import com.quantchat.integration.SystemIntegrationBridge
import com.quantchat.model.StorageSpaceSnapshot
import com.quantchat.resource.AdmissionDowngradeSuggestion
import com.quantchat.resource.OrchestratorSnapshot
import com.quantchat.server.LocalServerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private const val TAG = "QuantTerminalVM"

typealias BlockedAdmissionPrompt = com.example.data.BlockedAdmissionPrompt

sealed interface PendingExternalNavigation {
    data class OpenChatWithDraft(
        val threadId: String,
        val prefillText: String,
        val sourceLabel: String
    ) : PendingExternalNavigation

    data object OpenModelsTab : PendingExternalNavigation
}

class QuantTerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val host = QuantRuntimeHost.getInstance(application)
    private val hfRepository = host.hfRepository
    private val chatRepository = host.chatRepository
    private val orchestrator = host.orchestrator
    private val sessionManager = host.sessionManager

    val orchestratorSnapshot: StateFlow<OrchestratorSnapshot> = host.orchestratorSnapshot
    val localServerState: StateFlow<LocalServerState> = host.localServerState
    val backgroundServicesStatus: StateFlow<BackgroundServicesStatus> = host.backgroundServicesStatus

    val threads: StateFlow<List<ChatThread>> = host.allThreads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messagesByThread: StateFlow<Map<String, List<ChatMessage>>> = host.allMessagesByThread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    val streamingMessage: StateFlow<ChatMessage?> = host.streamingMessage
    val runtimeState: StateFlow<LocalRuntimeState> = host.runtimeState
    val inferenceSettings: StateFlow<InferenceSettings> = host.inferenceSettings
    val isGenerating: StateFlow<Boolean> = host.isGenerating

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

    val activeDownload: StateFlow<ActiveDownloadState> = host.activeDownload
    val localWeightFiles: StateFlow<List<LocalWeightFile>> = host.localWeightFiles
    val hfAuthState: StateFlow<HfAuthState> = host.hfAuthState
    val isOnline: StateFlow<Boolean> = host.isOnline
    val isImportingLocalFile: StateFlow<Boolean> = host.isImportingLocalFile
    val snackbarMessage: StateFlow<String?> = host.snackbarMessage
    val blockedAdmissionPrompt: StateFlow<BlockedAdmissionPrompt?> = host.blockedAdmissionPrompt
    val storageSnapshot: StateFlow<StorageSpaceSnapshot> = host.storageSnapshot
    val pendingDeleteConfirmation: StateFlow<LocalWeightFile?> = host.pendingDeleteConfirmation

    private val _pendingExternalNavigation = MutableStateFlow<PendingExternalNavigation?>(null)
    val pendingExternalNavigation: StateFlow<PendingExternalNavigation?> = _pendingExternalNavigation.asStateFlow()

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
                    host.postSnackbar("Imported text from ${action.sourceLabel} into local draft.")
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
        host.refreshNotificationPermission()
    }

    fun runMaintenanceNow() {
        host.enqueueImmediateMaintenance()
    }

    fun refreshNotificationPermission() {
        host.refreshNotificationPermission()
    }

    fun runVulkanSelfTest() {
        viewModelScope.launch {
            val probe = orchestrator.runVulkanSelfTest()
            host.postSnackbar(
                if (probe.isUsableForInference) {
                    "Vulkan verified: ${probe.deviceName ?: "GPU"} (${probe.vulkanApiVersionLabel ?: probe.systemVulkanVersion})"
                } else {
                    "GPU probe: ${probe.status.label} — ${probe.reason ?: probe.selfTestSummary}"
                }
            )
        }
    }

    fun runNpuProbe() {
        viewModelScope.launch {
            val probe = orchestrator.runNpuProbe()
            host.postSnackbar("NPU probe: ${probe.summary}")
        }
    }

    fun selectAcceleratorPreference(preference: AcceleratorPreference) {
        viewModelScope.launch {
            val current = inferenceSettings.value
            val updated = current.copy(acceleratorPreference = preference)
            hfRepository.saveInferenceSettings(updated)
            orchestrator.updateAcceleratorConfig(
                preference = updated.acceleratorPreference,
                autoGpuLayerOffload = updated.autoGpuLayerOffload,
                manualGpuLayers = updated.manualGpuLayers
            )
            val ready = runtimeState.value as? LocalRuntimeState.Ready
            if (ready != null && current.acceleratorPreference != preference) {
                sessionManager.invalidateAll()
                host.loadLocalModel(ready.file)
                host.postSnackbar("Switched backend preference to ${preference.label} — remapping ${ready.file.displayFileName}...")
            } else {
                host.postSnackbar("Accelerator preference: ${preference.label}")
            }
        }
    }

    fun runHardwareBenchmark() = host.runHardwareBenchmark()

    fun selectScheduleMode(mode: CpuScheduleMode) {
        viewModelScope.launch {
            val current = inferenceSettings.value
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
            host.postSnackbar(
                if (resolved?.isThermallyThrottled == true) {
                    "Thermal protection active (${resolved.effectiveMode.label} · ${activeLabel}T)"
                } else {
                    "CPU profile: ${mode.label} (${activeLabel}T, instant apply)"
                }
            )
        }
    }

    fun toggleAutoThermalDownshift(enabled: Boolean) {
        viewModelScope.launch {
            val current = inferenceSettings.value
            val updated = current.copy(autoThermalDownshift = enabled)
            hfRepository.saveInferenceSettings(updated)
            orchestrator.updateScheduleConfig(
                mode = updated.scheduleMode,
                autoThermalDownshift = updated.autoThermalDownshift,
                manualThreadsOverride = updated.manualThreadsOverride,
                calibratedOptimalThreads = updated.calibratedOptimalThreads,
                calibratedDeviceSignature = updated.calibratedDeviceSignature
            )
            host.postSnackbar(
                if (enabled) {
                    "Auto Thermal Safe protection enabled."
                } else {
                    "Auto Thermal Safe protection disabled."
                }
            )
        }
    }

    fun calibrateOptimalThreads() = host.calibrateOptimalThreads()

    fun cancelCalibration() = host.cancelCalibration()

    fun refreshLocalWeights() = host.refreshLocalWeights()

    fun clearSnackbar() = host.postSnackbar(null)

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    suspend fun createNewThreadImmediate(): String {
        val id = "chat_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val activeModelName = (runtimeState.value as? LocalRuntimeState.Ready)?.file?.displayFileName
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
            if (streamingMessage.value?.threadId == threadId) {
                host.stopGeneration()
            }
            orchestrator.invalidateThreadSession(threadId)
            chatRepository.deleteThread(threadId)
            host.postSnackbar("Conversation deleted.")
        }
    }

    fun clearThreadMessages(threadId: String) {
        viewModelScope.launch {
            if (streamingMessage.value?.threadId == threadId) {
                host.stopGeneration()
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
            host.postSnackbar("Messages cleared.")
        }
    }

    fun loadLocalModel(file: LocalWeightFile) = host.loadLocalModel(file)

    fun dismissBlockedAdmission() = host.dismissBlockedAdmission()

    fun applyDowngradeAndLoad(prompt: BlockedAdmissionPrompt, suggestion: AdmissionDowngradeSuggestion) =
        host.applyDowngradeAndLoad(prompt, suggestion)

    fun unloadLocalModel() = host.unloadLocalModel()

    fun stopGeneration() = host.stopGeneration()

    fun sendChatMessage(threadId: String, rawText: String) =
        host.sendChatMessage(threadId, rawText, threads.value)

    fun updateInferenceSettings(newSettings: InferenceSettings) {
        val oldSettings = inferenceSettings.value
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
            val ready = runtimeState.value as? LocalRuntimeState.Ready
            if (needsReload && ready != null) {
                host.loadLocalModel(ready.file)
                host.postSnackbar(
                    if (accelChanged) {
                        "Accelerator/offload updated — remapping ${ready.file.displayFileName}..."
                    } else {
                        "Context window updated — reloading ${ready.file.displayFileName}..."
                    }
                )
            } else {
                val decision = orchestrator.snapshot.value.schedulingDecision
                val modeText = decision?.effectiveMode?.label ?: syncedSettings.scheduleMode.label
                host.postSnackbar(
                    String.format(
                        Locale.US,
                        "Saved · %s (%dT), %s, ctx=%d",
                        modeText,
                        resolvedThreads,
                        syncedSettings.acceleratorPreference.label,
                        syncedSettings.contextSize
                    )
                )
            }
            host.refreshLocalWeights()
        }
    }

    fun selectModelTab(tab: HubModelTab) {
        _selectedModelTab.value = tab
        if (tab == HubModelTab.DOWNLOADED) {
            host.refreshLocalWeights()
        }
    }

    fun updateModelSearchQuery(query: String) {
        _modelSearchQuery.value = query
    }

    fun importLocalGguf(uri: Uri) {
        if (isImportingLocalFile.value) return
        viewModelScope.launch {
            host.setImportingLocalFile(true)
            try {
                val imported = hfRepository.importLocalGgufUri(uri)
                host.refreshLocalWeights()
                _selectedModelTab.value = HubModelTab.DOWNLOADED
                host.postSnackbar("Imported ${imported.displayFileName}. Tap Load to run offline.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import GGUF URI", e)
                host.postSnackbar(e.localizedMessage ?: "Unable to import selected GGUF file.")
            } finally {
                host.setImportingLocalFile(false)
            }
        }
    }

    fun searchHuggingFaceHub() {
        val query = _modelSearchQuery.value.trim()
        if (query.isEmpty()) {
            host.postSnackbar("Enter a model name (e.g. Qwen3, Gemma, Llama, DeepSeek) before searching Hub.")
            return
        }
        host.setOnlineState(hfRepository.isNetworkConnected())
        _selectedModelTab.value = HubModelTab.HUB_RESULTS
        _hubSearchState.value = HubSearchUiState.Loading

        viewModelScope.launch {
            try {
                val token = hfAuthState.value.token.takeIf { it.isNotBlank() }
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
        host.setOnlineState(hfRepository.isNetworkConnected())
        _fileInspectionState.value = FileInspectionUiState.Loading(effectiveModel)

        viewModelScope.launch {
            try {
                val token = hfAuthState.value.token.takeIf { it.isNotBlank() }
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
        host.startStreamDownload(
            modelId = model.id,
            modelDisplayName = "${model.displayName} (${artifact.quantTag})",
            repoId = artifact.repoId,
            fallbackOpenRepoId = model.fallbackOpenRepoId,
            fileName = artifact.rfilename,
            downloadUrl = artifact.downloadUrl,
            initialTotalBytes = artifact.sizeBytes,
            expectedSha256 = artifact.sha256
        )
    }

    fun resumeLocalPartialFile(file: LocalWeightFile) {
        val url = file.downloadUrl ?: return
        host.startStreamDownload(
            modelId = file.diskFileName,
            modelDisplayName = file.displayFileName,
            repoId = file.repoId,
            fallbackOpenRepoId = null,
            fileName = file.displayFileName,
            downloadUrl = url,
            initialTotalBytes = file.expectedTotalBytes ?: 0L,
            expectedSha256 = file.expectedSha256
        )
    }

    fun pauseActiveDownload(pausedByThermal: Boolean = false) =
        host.pauseActiveDownload(pausedByThermal)

    fun resumeActiveDownload() {
        val current = activeDownload.value
        if (current.downloadUrl.isBlank()) return
        host.startStreamDownload(
            modelId = current.modelId,
            modelDisplayName = current.modelDisplayName,
            repoId = current.repoId,
            fallbackOpenRepoId = current.fallbackOpenRepoId,
            fileName = current.fileName,
            downloadUrl = current.downloadUrl,
            initialTotalBytes = current.totalBytes,
            expectedSha256 = current.expectedSha256
        )
    }

    fun switchFailedDownloadToOpenMirror() {
        val current = activeDownload.value
        val fallbackRepo = current.fallbackOpenRepoId ?: return
        host.clearActiveDownloadUi()
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

    fun dismissOrCancelDownload() = host.dismissOrCancelDownload()

    fun verifyLocalWeightFile(file: LocalWeightFile) = host.verifyLocalWeightFile(file)

    fun deleteLocalFile(file: LocalWeightFile) = host.deleteLocalFile(file)

    fun confirmUnloadAndDeleteFile(file: LocalWeightFile) = host.confirmUnloadAndDeleteFile(file)

    fun dismissDeleteConfirmation() = host.dismissDeleteConfirmation()

    fun saveHfToken(token: String) {
        viewModelScope.launch {
            hfRepository.saveHfToken(token)
            host.postSnackbar(
                if (token.isBlank()) {
                    "Hugging Face token cleared."
                } else {
                    "Hugging Face Bearer token saved locally."
                }
            )
        }
    }

    // Intentionally NOT cancelling host download/inference/model jobs in ViewModel.onCleared()
    // so long-running ForegroundService tasks survive Activity rotation and recreation.
}
