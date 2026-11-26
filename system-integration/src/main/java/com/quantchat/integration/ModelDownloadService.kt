package com.quantchat.integration

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

class ModelDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        QuantNotificationChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                BackgroundTaskRegistry.host?.onPauseDownloadFromNotification()
                stopSelfAndClearForeground("Paused")
                return START_NOT_STICKY
            }

            ACTION_CANCEL, ACTION_STOP -> {
                BackgroundTaskRegistry.host?.onCancelDownloadFromNotification()
                stopSelfAndClearForeground("Cancelled")
                return START_NOT_STICKY
            }

            else -> {
                promoteToForeground()
                return START_REDELIVER_INTENT
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 14/15+ dataSync FGS timeout guard: pause cleanly instead of crashing.
        BackgroundTaskRegistry.host?.onPauseDownloadFromNotification()
        stopSelfAndClearForeground("Paused (OS dataSync FGS time limit)")
    }

    override fun onDestroy() {
        val current = BackgroundTaskRegistry.status.value.downloadService
        if (current.foregroundActive) {
            BackgroundTaskRegistry.updateDownloadService(
                current.copy(foregroundActive = false)
            )
        }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val current = BackgroundTaskRegistry.status.value.downloadService.copy(
            foregroundActive = true,
            lastLaunchOutcome = ForegroundLaunchOutcome.STARTED
        )
        BackgroundTaskRegistry.updateDownloadService(current)

        val notification = QuantNotificationChannels.buildDownloadNotification(this, current)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            QuantNotificationChannels.NOTIFICATION_ID_DOWNLOAD,
            notification,
            fgsType
        )
    }

    private fun stopSelfAndClearForeground(statusLabel: String) {
        val current = BackgroundTaskRegistry.status.value.downloadService
        BackgroundTaskRegistry.updateDownloadService(
            current.copy(
                isRunning = false,
                foregroundActive = false,
                speedBytesPerSec = 0L,
                statusText = statusLabel
            )
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START_OR_UPDATE = "com.quantchat.integration.action.DOWNLOAD_START_OR_UPDATE"
        const val ACTION_PAUSE = "com.quantchat.integration.action.DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "com.quantchat.integration.action.DOWNLOAD_CANCEL"
        const val ACTION_STOP = "com.quantchat.integration.action.DOWNLOAD_STOP"
    }
}
