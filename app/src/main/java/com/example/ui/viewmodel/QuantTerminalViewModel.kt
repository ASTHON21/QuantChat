package com.example.ui.viewmodel

import android.app.Application
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
import com.example.data.LocalLlamaRepository
import com.example.data.LocalRuntimeState
import com.example.data.LocalStreamEvent
import com.example.data.LocalWeightFile
import com.example.data.ModelFileArtifact
import com.example.data.local.AppChatDatabase
import com.example.data.local.ChatRepository
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
import java.util.Locale
import java.util.UUID

private const val TAG = "QuantTerminalVM"

class QuantTerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val hfRepository = HuggingFaceModelRepository(application.applicationContext)
    private val llamaRepository = LocalLlamaRepository(application.applicationContext)
    private val chatRepository = ChatRepository(
        AppChatDatabase.getInstance(application.applicationContext).chatDao()
    )

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

    private var downloadJob: Job? = null
    private var loadJob: Job? = null
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            hfRepository.hfTokenFlow.collect { token ->
                _hfAuthState.value = HfAuthState(token = token, isLoaded = true)
            }
        }
        viewModelScope.launch {
            hfRepository.inferenceSettingsFlow.collect { settings ->
                _inferenceSettings.value = settings
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

    fun refreshLocalWeights() {
        _localWeightFiles.value = hfRepository.listLocalWeightFiles()
        _isOnline.value = hfRepository.isNetworkConnected()
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
            chatRepository.deleteThread(threadId)
            _snackbarMessage.value = "Conversation deleted."
        }
    }

    fun clearThreadMessages(threadId: String) {
        viewModelScope.launch {
            if (_streamingMessage.value?.threadId == threadId) {
                stopGeneration()
            }
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
                llamaRepository.stopGeneration()
                generationJob?.join()
            }
            _runtimeState.value = LocalRuntimeState.Loading(file)
            try {
                val settings = _inferenceSettings.value
                val contextId = llamaRepository.loadModel(
                    weightFile = file,
                    diskFile = diskFile,
                    settings = settings
                )
                _runtimeState.value = LocalRuntimeState.Ready(
                    file = file,
                    contextId = contextId,
                    contextSize = settings.contextSize,
                    threads = settings.threads
                )
                hfRepository.saveLastLoadedModel(file.diskFileName)
                _snackbarMessage.value = "Loaded ${file.displayFileName} into offline runtime."
            } catch (e: CancellationException) {
                throw e
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
                llamaRepository.stopGeneration()
                generationJob?.join()
            }
            llamaRepository.unloadModel()
            _runtimeState.value = LocalRuntimeState.Unloaded("Model unloaded from memory.")
            hfRepository.saveLastLoadedModel(null)
            _snackbarMessage.value = "Unloaded local model to free RAM."
        }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            llamaRepository.stopGeneration()
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

                val settings = _inferenceSettings.value
                llamaRepository.streamCompletion(
                    contextId = readyState.contextId,
                    weightFile = readyState.file,
                    history = priorHistory,
                    userPrompt = promptText,
                    settings = settings
                ).collect { event ->
                    when (event) {
                        is LocalStreamEvent.Delta -> {
                            _streamingMessage.update { current ->
                                current?.copy(
                                    text = event.accumulatedText,
                                    tokenCount = event.tokenCount,
                                    isStreaming = true
                                )
                            }
                        }

                        is LocalStreamEvent.Completed -> {
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
            }
        }
    }

    fun updateInferenceSettings(newSettings: InferenceSettings) {
        val oldSettings = _inferenceSettings.value
        viewModelScope.launch {
            hfRepository.saveInferenceSettings(newSettings)
            val needsReload = (oldSettings.threads != newSettings.threads ||
                oldSettings.contextSize != newSettings.contextSize)
            val ready = _runtimeState.value as? LocalRuntimeState.Ready
            if (needsReload && ready != null) {
                loadLocalModel(ready.file)
                _snackbarMessage.value = "Settings saved — reloading ${ready.file.displayFileName}..."
            } else {
                _snackbarMessage.value = String.format(
                    Locale.US,
                    "Inference settings saved (T=%d, ctx=%d, temp=%.2f)",
                    newSettings.threads,
                    newSettings.contextSize,
                    newSettings.temperature
                )
            }
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
                    llamaRepository.stopGeneration()
                }
                llamaRepository.unloadModel()
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
        hfRepository.cancelActiveHttpCall()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            llamaRepository.unloadModel()
        }
    }
}
