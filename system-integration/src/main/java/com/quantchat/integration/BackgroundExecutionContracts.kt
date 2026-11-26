package com.quantchat.integration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ForegroundLaunchOutcome(val label: String) {
    STARTED("Foreground Active"),
    BLOCKED_BACKGROUND_RESTRICTION("Blocked by Background FGS Policy"),
    FAILED("Launch Failed")
}

data class DownloadServiceSnapshot(
    val isRunning: Boolean = false,
    val foregroundActive: Boolean = false,
    val modelName: String = "",
    val fileName: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Int = 0,
    val statusText: String = "Idle",
    val pausedByThermal: Boolean = false,
    val lastLaunchOutcome: ForegroundLaunchOutcome? = null
)

data class InferenceServiceSnapshot(
    val isRunning: Boolean = false,
    val foregroundActive: Boolean = false,
    val modelName: String = "",
    val phaseLabel: String = "Idle",
    val tokenCount: Int = 0,
    val tokensPerSec: Float = 0f,
    val backendLabel: String = "",
    val lastLaunchOutcome: ForegroundLaunchOutcome? = null
)

data class MaintenanceReport(
    val ranAtMs: Long = 0L,
    val stalePartFilesRemoved: Int = 0,
    val verifiedGgufCount: Int = 0,
    val corruptGgufCount: Int = 0,
    val prunedSessionFiles: Int = 0,
    val updateCheckSummary: String = "Not checked yet",
    val deferredByThermal: Boolean = false,
    val summary: String = "Scheduled (24h periodic)"
)

data class BackgroundServicesStatus(
    val notificationsPermissionGranted: Boolean = true,
    val appInForeground: Boolean = true,
    val downloadService: DownloadServiceSnapshot = DownloadServiceSnapshot(),
    val inferenceService: InferenceServiceSnapshot = InferenceServiceSnapshot(),
    val lastMaintenanceReport: MaintenanceReport? = null,
    val maintenanceStateLabel: String = "Scheduled (24h periodic)",
    val nextScheduledMaintenanceNote: String = "Periodic cleanup & update check via WorkManager (never used for streaming tokens)"
)

interface BackgroundExecutionHost {
    fun isThermalBlockingNonEssentialMaintenance(): Boolean
    fun onPauseDownloadFromNotification()
    fun onCancelDownloadFromNotification()
    fun onStopInferenceFromNotification()
    suspend fun runMaintenanceSweep(checkUpdatesOnline: Boolean): MaintenanceReport
    fun createLaunchMainIntent(openModelsTab: Boolean = false): Intent
}

object BackgroundTaskRegistry {

    @Volatile
    var host: BackgroundExecutionHost? = null
        private set

    private val _status = MutableStateFlow(BackgroundServicesStatus())
    val status: StateFlow<BackgroundServicesStatus> = _status.asStateFlow()

    fun registerHost(executionHost: BackgroundExecutionHost) {
        host = executionHost
    }

    fun setAppInForeground(inForeground: Boolean) {
        _status.update { it.copy(appInForeground = inForeground) }
    }

    fun refreshNotificationPermission(context: Context) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _status.update { it.copy(notificationsPermissionGranted = granted) }
    }

    fun updateDownloadService(snapshot: DownloadServiceSnapshot) {
        _status.update { it.copy(downloadService = snapshot) }
    }

    fun updateInferenceService(snapshot: InferenceServiceSnapshot) {
        _status.update { it.copy(inferenceService = snapshot) }
    }

    fun updateMaintenance(report: MaintenanceReport?, stateLabel: String) {
        _status.update {
            it.copy(
                lastMaintenanceReport = report ?: it.lastMaintenanceReport,
                maintenanceStateLabel = stateLabel
            )
        }
    }
}
