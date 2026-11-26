package com.example

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.ChatThread
import com.example.data.LocalRuntimeState
import com.example.ui.components.AppVersionSheet
import com.example.ui.components.InferenceSettingsSheet
import com.example.ui.components.MemoryAdmissionBlockedDialog
import com.example.ui.components.QuantBottomNav
import com.example.ui.components.QuantTab
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.ModelsScreen
import com.example.ui.theme.QuantChatTheme
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantSurface
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.viewmodel.PendingExternalNavigation
import com.example.ui.viewmodel.QuantTerminalViewModel
import kotlinx.coroutines.launch

const val ROUTE_CHAT_DETAIL = "chat/{threadId}"

class MainActivity : ComponentActivity() {

    private val terminalViewModel: QuantTerminalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        if (savedInstanceState == null) {
            terminalViewModel.handleLaunchIntent(intent)
        }

        setContent {
            QuantChatTheme {
                QuantChatApp(viewModel = terminalViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        terminalViewModel.handleLaunchIntent(intent)
    }
}

@Composable
fun QuantChatApp(
    viewModel: QuantTerminalViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val scope = rememberCoroutineScope()

    val threads by viewModel.threads.collectAsStateWithLifecycle()
    val chatSearchQuery by viewModel.chatSearchQuery.collectAsStateWithLifecycle()
    val messagesByThread by viewModel.messagesByThread.collectAsStateWithLifecycle()
    val streamingMessage by viewModel.streamingMessage.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val inferenceSettings by viewModel.inferenceSettings.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val orchestratorSnapshot by viewModel.orchestratorSnapshot.collectAsStateWithLifecycle()
    val backgroundServicesStatus by viewModel.backgroundServicesStatus.collectAsStateWithLifecycle()
    val pendingExternalNav by viewModel.pendingExternalNavigation.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshNotificationPermission()
    }
    var requestedNotificationOnce by remember { mutableStateOf(false) }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !backgroundServicesStatus.notificationsPermissionGranted &&
            !requestedNotificationOnce
        ) {
            requestedNotificationOnce = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val curatedModels by viewModel.curatedModels.collectAsStateWithLifecycle()
    val selectedModelTab by viewModel.selectedModelTab.collectAsStateWithLifecycle()
    val modelSearchQuery by viewModel.modelSearchQuery.collectAsStateWithLifecycle()
    val hubSearchState by viewModel.hubSearchState.collectAsStateWithLifecycle()
    val fileInspectionState by viewModel.fileInspectionState.collectAsStateWithLifecycle()
    val activeDownload by viewModel.activeDownload.collectAsStateWithLifecycle()
    val localWeightFiles by viewModel.localWeightFiles.collectAsStateWithLifecycle()
    val hfAuthState by viewModel.hfAuthState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val isImportingLocalFile by viewModel.isImportingLocalFile.collectAsStateWithLifecycle()
    val blockedAdmissionPrompt by viewModel.blockedAdmissionPrompt.collectAsStateWithLifecycle()
    val storageSnapshot by viewModel.storageSnapshot.collectAsStateWithLifecycle()
    val pendingDeleteConfirmation by viewModel.pendingDeleteConfirmation.collectAsStateWithLifecycle()

    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showVersionSheet by remember { mutableStateOf(false) }
    var activeDraftText by remember { mutableStateOf<String?>(null) }

    val readyLocalFiles = remember(localWeightFiles) {
        localWeightFiles.filter { !it.isPartial }
    }

    fun navigateToTab(tab: QuantTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(pendingExternalNav) {
        when (val nav = pendingExternalNav) {
            is PendingExternalNavigation.OpenChatWithDraft -> {
                activeDraftText = nav.prefillText
                navController.navigate("chat/${nav.threadId}")
                viewModel.consumeExternalNavigation()
            }
            is PendingExternalNavigation.OpenModelsTab -> {
                navigateToTab(QuantTab.MODELS)
                viewModel.consumeExternalNavigation()
            }
            null -> Unit
        }
    }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val showBottomBar = currentRoute in QuantTab.entries.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = QuantSurface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = QuantSurfaceHigh,
                    contentColor = QuantOnSurface,
                    actionColor = QuantEmerald
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                QuantBottomNav(
                    currentRoute = currentRoute,
                    onSelectTab = ::navigateToTab
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = QuantTab.CHATS.route
            ) {
                composable(QuantTab.CHATS.route) {
                    ChatsScreen(
                        threads = threads,
                        searchQuery = chatSearchQuery,
                        runtimeState = runtimeState,
                        readyLocalFiles = readyLocalFiles,
                        onSearchQueryChange = viewModel::updateChatSearchQuery,
                        onOpenThread = { threadId ->
                            navController.navigate("chat/$threadId")
                        },
                        onCreateNewChat = {
                            scope.launch {
                                val newId = viewModel.createNewThreadImmediate()
                                navController.navigate("chat/$newId")
                            }
                        },
                        onDeleteThread = viewModel::deleteThread,
                        onOpenInferenceSettings = { showSettingsSheet = true },
                        onOpenVersionInfo = { showVersionSheet = true },
                        onGoToModels = { navigateToTab(QuantTab.MODELS) }
                    )
                }

                composable(QuantTab.MODELS.route) {
                    ModelsScreen(
                        curatedModels = curatedModels,
                        selectedTab = selectedModelTab,
                        searchQuery = modelSearchQuery,
                        hubSearchState = hubSearchState,
                        fileInspectionState = fileInspectionState,
                        activeDownload = activeDownload,
                        localWeightFiles = localWeightFiles,
                        runtimeState = runtimeState,
                        hfAuthState = hfAuthState,
                        isOnline = isOnline,
                        isImportingLocalFile = isImportingLocalFile,
                        npuCapabilities = orchestratorSnapshot.npuCapabilities,
                        npuCompatibilityReport = orchestratorSnapshot.npuCompatibilityReport,
                        backgroundServicesStatus = backgroundServicesStatus,
                        storageSnapshot = storageSnapshot,
                        mmapLifecycle = orchestratorSnapshot.mmapLifecycle,
                        pendingDeleteConfirmation = pendingDeleteConfirmation,
                        onSelectTab = viewModel::selectModelTab,
                        onSearchQueryChange = viewModel::updateModelSearchQuery,
                        onSearchHubClick = viewModel::searchHuggingFaceHub,
                        onInspectModelFiles = viewModel::inspectModelFiles,
                        onCloseFileInspection = viewModel::closeFileInspection,
                        onSelectArtifactToDownload = { model, artifact ->
                            requestNotificationPermissionIfNeeded()
                            viewModel.startArtifactDownload(model, artifact)
                        },
                        onPauseDownload = { viewModel.pauseActiveDownload() },
                        onResumeDownload = {
                            requestNotificationPermissionIfNeeded()
                            viewModel.resumeActiveDownload()
                        },
                        onCancelDownload = viewModel::dismissOrCancelDownload,
                        onSwitchDownloadToMirror = viewModel::switchFailedDownloadToOpenMirror,
                        onLoadLocalModel = { file ->
                            requestNotificationPermissionIfNeeded()
                            viewModel.loadLocalModel(file)
                        },
                        onUnloadLocalModel = viewModel::unloadLocalModel,
                        onOpenChatWithModel = {
                            scope.launch {
                                val targetId = threads.firstOrNull()?.id ?: viewModel.createNewThreadImmediate()
                                navController.navigate("chat/$targetId")
                            }
                        },
                        onImportLocalGgufUri = viewModel::importLocalGguf,
                        onResumeLocalPartial = { file ->
                            requestNotificationPermissionIfNeeded()
                            viewModel.resumeLocalPartialFile(file)
                        },
                        onVerifyLocalFile = viewModel::verifyLocalWeightFile,
                        onDeleteLocalFile = viewModel::deleteLocalFile,
                        onConfirmUnloadAndDelete = viewModel::confirmUnloadAndDeleteFile,
                        onDismissDeleteConfirmation = viewModel::dismissDeleteConfirmation,
                        onSaveHfToken = viewModel::saveHfToken
                    )
                }

                composable(
                    route = ROUTE_CHAT_DETAIL,
                    arguments = listOf(navArgument("threadId") { type = NavType.StringType })
                ) { entry ->
                    val threadId = entry.arguments?.getString("threadId") ?: return@composable
                    val thread = threads.find { it.id == threadId } ?: ChatThread(
                        id = threadId,
                        title = "New Chat",
                        preview = "",
                        modelFileName = null,
                        updatedAtMs = System.currentTimeMillis(),
                        timestampLabel = ""
                    )
                    val messages = messagesByThread[threadId].orEmpty()

                    ChatDetailScreen(
                        thread = thread,
                        messages = messages,
                        streamingMessage = streamingMessage,
                        runtimeState = runtimeState,
                        readyLocalFiles = readyLocalFiles,
                        inferenceSettings = inferenceSettings,
                        isGenerating = isGenerating,
                        orchestratorSnapshot = orchestratorSnapshot,
                        initialDraftText = activeDraftText,
                        onDraftConsumed = { activeDraftText = null },
                        onBack = { navController.popBackStack() },
                        onLoadLocalModel = viewModel::loadLocalModel,
                        onUnloadLocalModel = viewModel::unloadLocalModel,
                        onClearMessages = { viewModel.clearThreadMessages(threadId) },
                        onOpenInferenceSettings = { showSettingsSheet = true },
                        onGoToModels = {
                            navController.popBackStack()
                            navigateToTab(QuantTab.MODELS)
                        },
                        onSelectScheduleMode = viewModel::selectScheduleMode,
                        onToggleAutoThermal = viewModel::toggleAutoThermalDownshift,
                        onSelectAcceleratorPreference = viewModel::selectAcceleratorPreference,
                        onSendMessage = { text -> viewModel.sendChatMessage(threadId, text) },
                        onStopGeneration = viewModel::stopGeneration,
                        onCopiedMessage = {
                            scope.launch {
                                snackbarHostState.showSnackbar("Copied response to clipboard.")
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        InferenceSettingsSheet(
            currentSettings = inferenceSettings,
            orchestratorSnapshot = orchestratorSnapshot,
            backgroundServicesStatus = backgroundServicesStatus,
            isModelReady = runtimeState is LocalRuntimeState.Ready,
            onRefreshDiagnostics = viewModel::refreshHardwareDiagnostics,
            onRunBenchmark = viewModel::runHardwareBenchmark,
            onCalibrateThreads = viewModel::calibrateOptimalThreads,
            onCancelCalibration = viewModel::cancelCalibration,
            onRunVulkanSelfTest = viewModel::runVulkanSelfTest,
            onRunNpuProbe = viewModel::runNpuProbe,
            onRunMaintenanceNow = viewModel::runMaintenanceNow,
            onRequestNotificationsPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDismiss = { showSettingsSheet = false },
            onApply = viewModel::updateInferenceSettings,
            onOpenVersionInfo = { showVersionSheet = true }
        )
    }

    if (showVersionSheet) {
        AppVersionSheet(
            onDismiss = { showVersionSheet = false }
        )
    }

    blockedAdmissionPrompt?.let { prompt ->
        MemoryAdmissionBlockedDialog(
            file = prompt.file,
            assessment = prompt.assessment,
            onDismiss = viewModel::dismissBlockedAdmission,
            onApplyDowngrade = { suggestion ->
                viewModel.applyDowngradeAndLoad(prompt, suggestion)
            }
        )
    }
}
