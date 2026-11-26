package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.data.HuggingFaceModelRepository
import com.example.data.StorageMaintenanceRunner
import com.example.data.local.AppChatDatabase
import com.example.data.local.ChatRepository
import com.example.ui.components.BackgroundServicesCard
import com.example.ui.theme.QuantChatTheme
import com.quantchat.integration.BackgroundExecutionHost
import com.quantchat.integration.BackgroundServicesStatus
import com.quantchat.integration.BackgroundTaskRegistry
import com.quantchat.integration.DownloadServiceSnapshot
import com.quantchat.integration.ForegroundLaunchOutcome
import com.quantchat.integration.ForegroundServiceLauncher
import com.quantchat.integration.InferenceForegroundService
import com.quantchat.integration.InferenceServiceSnapshot
import com.quantchat.integration.MaintenanceReport
import com.quantchat.integration.ModelDownloadService
import com.quantchat.integration.ModelMaintenanceWorker
import com.quantchat.integration.QuantNotificationChannels
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2_3ForegroundServiceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `manifest declares foreground service types, specialUse property, and permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )
        val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)

        val servicesByName = pkgInfo.services?.associateBy { it.name }.orEmpty()
        val downloadSvc = servicesByName[ModelDownloadService::class.java.name]
        val inferenceSvc = servicesByName[InferenceForegroundService::class.java.name]
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, downloadSvc?.foregroundServiceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, inferenceSvc?.foregroundServiceType)
    }

    @Test
    fun `ModelDownloadService and InferenceForegroundService promote foreground notifications and route actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var pauseCalled = 0
        var cancelCalled = 0
        var stopInfCalled = 0

        BackgroundTaskRegistry.registerHost(object : BackgroundExecutionHost {
            override fun isThermalBlockingNonEssentialMaintenance(): Boolean = false
            override fun onPauseDownloadFromNotification() { pauseCalled++ }
            override fun onCancelDownloadFromNotification() { cancelCalled++ }
            override fun onStopInferenceFromNotification() { stopInfCalled++ }
            override suspend fun runMaintenanceSweep(checkUpdatesOnline: Boolean) = MaintenanceReport()
            override fun createLaunchMainIntent(openModelsTab: Boolean) = Intent(context, MainActivity::class.java)
        })

        // Start ModelDownloadService and promote foreground
        BackgroundTaskRegistry.updateDownloadService(
            DownloadServiceSnapshot(
                isRunning = true,
                modelName = "Qwen3-4B (Q4_K_M)",
                fileName = "Qwen3-4B-Q4_K_M.gguf",
                bytesDownloaded = 512L * 1024L * 1024L,
                totalBytes = 2048L * 1024L * 1024L,
                speedBytesPerSec = 12L * 1024L * 1024L,
                statusText = "Downloading"
            )
        )
        val dlController = Robolectric.buildService(
            ModelDownloadService::class.java,
            Intent(context, ModelDownloadService::class.java).apply {
                action = ModelDownloadService.ACTION_START_OR_UPDATE
            }
        ).create().startCommand(0, 1)

        val dlShadow = shadowOf(dlController.get())
        assertTrue(dlShadow.isLastForegroundNotificationAttached)
        assertEquals(
            "Downloading Qwen3-4B (Q4_K_M)",
            shadowOf(dlShadow.lastForegroundNotification).contentTitle.toString()
        )
        assertTrue(BackgroundTaskRegistry.status.value.downloadService.foregroundActive)

        // Pause action from notification
        dlController.get().onStartCommand(
            Intent(context, ModelDownloadService::class.java).apply {
                action = ModelDownloadService.ACTION_PAUSE
            },
            0,
            2
        )
        assertEquals(1, pauseCalled)
        assertFalse(BackgroundTaskRegistry.status.value.downloadService.foregroundActive)

        // Cancel action
        dlController.get().onStartCommand(
            Intent(context, ModelDownloadService::class.java).apply {
                action = ModelDownloadService.ACTION_CANCEL
            },
            0,
            3
        )
        assertEquals(1, cancelCalled)

        // Start InferenceForegroundService and promote foreground
        BackgroundTaskRegistry.updateInferenceService(
            InferenceServiceSnapshot(
                isRunning = true,
                modelName = "Qwen3-4B.gguf",
                phaseLabel = "Generating tokens",
                tokenCount = 48,
                tokensPerSec = 16.5f,
                backendLabel = "Vulkan"
            )
        )
        val infController = Robolectric.buildService(
            InferenceForegroundService::class.java,
            Intent(context, InferenceForegroundService::class.java).apply {
                action = InferenceForegroundService.ACTION_START_OR_UPDATE
            }
        ).create().startCommand(0, 1)

        val infShadow = shadowOf(infController.get())
        assertTrue(infShadow.isLastForegroundNotificationAttached)
        assertTrue(
            shadowOf(infShadow.lastForegroundNotification).contentText.toString().contains("Generating tokens")
        )
        assertTrue(BackgroundTaskRegistry.status.value.inferenceService.foregroundActive)

        // Swiping Activity task away while inference is running keeps foreground service alive
        infController.get().onTaskRemoved(Intent(Intent.ACTION_MAIN))
        assertTrue(BackgroundTaskRegistry.status.value.inferenceService.foregroundActive)

        // Stop action from notification
        infController.get().onStartCommand(
            Intent(context, InferenceForegroundService::class.java).apply {
                action = InferenceForegroundService.ACTION_STOP
            },
            0,
            2
        )
        assertEquals(1, stopInfCalled)
        assertFalse(BackgroundTaskRegistry.status.value.inferenceService.foregroundActive)
    }

    @Test
    fun `ForegroundServiceLauncher guards background start restrictions cleanly without crashing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BackgroundTaskRegistry.setAppInForeground(false)
        BackgroundTaskRegistry.updateDownloadService(DownloadServiceSnapshot())
        BackgroundTaskRegistry.updateInferenceService(InferenceServiceSnapshot())

        val dlOutcome = ForegroundServiceLauncher.startOrUpdateDownloadService(
            context,
            DownloadServiceSnapshot(
                isRunning = true,
                modelName = "TestModel",
                fileName = "test.gguf"
            )
        )
        val infOutcome = ForegroundServiceLauncher.startOrUpdateInferenceService(
            context,
            InferenceServiceSnapshot(
                isRunning = true,
                modelName = "test.gguf",
                phaseLabel = "Generating"
            )
        )

        // Verify launcher records background restriction outcome cleanly when process is backgrounded
        if (dlOutcome == ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION) {
            assertEquals(
                ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION,
                BackgroundTaskRegistry.status.value.downloadService.lastLaunchOutcome
            )
        }
        if (infOutcome == ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION) {
            assertEquals(
                ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION,
                BackgroundTaskRegistry.status.value.inferenceService.lastLaunchOutcome
            )
        }
        BackgroundTaskRegistry.setAppInForeground(true)
    }

    @Test
    fun `ModelMaintenanceWorker verifies GGUF, prunes expired parts and orphan KV caches, and defers under severe thermal`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(ModelMaintenanceWorker.SUPPORTS_REALTIME_GENERATION)

        val hfRepository = HuggingFaceModelRepository(context)
        val chatRepository = ChatRepository(AppChatDatabase.getInstance(context).chatDao())
        val modelsDir = hfRepository.modelsDirectory
        modelsDir.mkdirs()

        // Create valid GGUF header (24 bytes with magic "GGUF" little endian + version 3)
        val validGguf = File(modelsDir, "test_valid.gguf").apply {
            writeBytes(
                byteArrayOf(
                    0x47, 0x47, 0x55, 0x46, // GGUF
                    0x03, 0x00, 0x00, 0x00, // version 3
                    0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0
                )
            )
        }
        val corruptGguf = File(modelsDir, "test_corrupt.gguf").apply {
            writeBytes(ByteArray(32) { 0x11 })
        }
        val stalePart = File(modelsDir, "old_incomplete.gguf.part").apply {
            writeBytes(ByteArray(64))
            setLastModified(System.currentTimeMillis() - 10L * 24L * 3600L * 1000L)
        }

        val tempSessions = createTempDirectory("maint_sessions").toFile()
        try {
            val mgr = SessionCacheManager(tempSessions)
            File(tempSessions, "orphan_thread.llama_session").writeBytes(ByteArray(16))
            File(tempSessions, "orphan_thread.meta").writeText("abc")
            File(tempSessions, "active_thread.llama_session").writeBytes(ByteArray(16))
            val pruned = mgr.pruneOrphanedCaches(validThreadIds = setOf("active_thread"))
            assertEquals(2, pruned)
            assertTrue(File(tempSessions, "active_thread.llama_session").exists())

            var thermalBlocking = false
            BackgroundTaskRegistry.registerHost(object : BackgroundExecutionHost {
                override fun isThermalBlockingNonEssentialMaintenance(): Boolean = thermalBlocking
                override fun onPauseDownloadFromNotification() = Unit
                override fun onCancelDownloadFromNotification() = Unit
                override fun onStopInferenceFromNotification() = Unit
                override suspend fun runMaintenanceSweep(checkUpdatesOnline: Boolean): MaintenanceReport =
                    StorageMaintenanceRunner.sweep(
                        hfRepository = hfRepository,
                        chatRepository = chatRepository,
                        sessionManager = mgr,
                        checkUpdatesOnline = checkUpdatesOnline
                    )
                override fun createLaunchMainIntent(openModelsTab: Boolean) = Intent(context, MainActivity::class.java)
            })

            // 1. Severe thermal -> Worker defers with Result.retry()
            thermalBlocking = true
            val deferredWorker = TestListenableWorkerBuilder<ModelMaintenanceWorker>(context)
                .setInputData(workDataOf(ModelMaintenanceWorker.KEY_CHECK_ONLINE_UPDATES to false))
                .build()
            val retryResult = deferredWorker.doWork()
            assertEquals(ListenableWorker.Result.retry(), retryResult)
            assertTrue(BackgroundTaskRegistry.status.value.lastMaintenanceReport?.deferredByThermal == true)

            // 2. Nominal thermal -> Worker verifies GGUFs and cleans expired .part
            thermalBlocking = false
            val worker = TestListenableWorkerBuilder<ModelMaintenanceWorker>(context)
                .setInputData(workDataOf(ModelMaintenanceWorker.KEY_CHECK_ONLINE_UPDATES to false))
                .build()
            val successResult = worker.doWork()
            assertTrue(successResult is ListenableWorker.Result.Success)
            val output = (successResult as ListenableWorker.Result.Success).outputData
            assertTrue(output.getInt(ModelMaintenanceWorker.OUT_STALE_PARTS_REMOVED, 0) >= 1)
            assertTrue(output.getInt(ModelMaintenanceWorker.OUT_VERIFIED_GGUF, 0) >= 1)
            assertTrue(output.getInt(ModelMaintenanceWorker.OUT_CORRUPT_GGUF, 0) >= 1)
            assertFalse(stalePart.exists())
        } finally {
            validGguf.delete()
            corruptGguf.delete()
            stalePart.delete()
            tempSessions.deleteRecursively()
        }
    }

    @Test
    fun `BackgroundServicesCard displays FGS statuses, WorkManager maintenance, and permission CTA`() {
        var maintenanceClicks = 0
        var permissionClicks = 0
        val status = BackgroundServicesStatus(
            notificationsPermissionGranted = false,
            appInForeground = true,
            downloadService = DownloadServiceSnapshot(
                isRunning = true,
                foregroundActive = true,
                statusText = "Downloading 42%"
            ),
            inferenceService = InferenceServiceSnapshot(
                isRunning = true,
                foregroundActive = true,
                phaseLabel = "Generating tokens",
                tokenCount = 64
            ),
            lastMaintenanceReport = MaintenanceReport(
                ranAtMs = 1000L,
                verifiedGgufCount = 2,
                stalePartFilesRemoved = 1,
                summary = "2 GGUF verified, 1 stale .part removed"
            ),
            maintenanceStateLabel = "Completed"
        )

        composeRule.setContent {
            QuantChatTheme {
                BackgroundServicesCard(
                    status = status,
                    onRunMaintenanceNow = { maintenanceClicks++ },
                    onRequestNotificationsPermission = { permissionClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("background_services_card").assertIsDisplayed()
        composeRule.onNodeWithText("FGS dataSync Active · Downloading 42%").assertIsDisplayed()
        composeRule.onNodeWithText("FGS specialUse Active · Generating tokens").assertIsDisplayed()
        composeRule.onNodeWithText("2 GGUF verified, 1 stale .part removed").assertIsDisplayed()

        composeRule.onNodeWithTag("run_maintenance_button").performClick()
        composeRule.onNodeWithTag("enable_notifications_button").performClick()
        assertEquals(1, maintenanceClicks)
        assertEquals(1, permissionClicks)
    }
}
