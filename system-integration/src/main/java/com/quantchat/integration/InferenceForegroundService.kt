package com.quantchat.integration

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

class InferenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        QuantNotificationChannels.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BackgroundTaskRegistry.host?.onStopInferenceFromNotification()
                stopSelfAndClearForeground("Stopped by user")
                return START_NOT_STICKY
            }

            ACTION_COMPLETE -> {
                stopSelfAndClearForeground("Idle")
                return START_NOT_STICKY
            }

            else -> {
                promoteToForeground()
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Long-running local inference stays alive when Activity task is backgrounded/swiped
        // unless inference is already idle.
        if (!BackgroundTaskRegistry.status.value.inferenceService.isRunning) {
            stopSelfAndClearForeground("Idle")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val current = BackgroundTaskRegistry.status.value.inferenceService
        if (current.foregroundActive) {
            BackgroundTaskRegistry.updateInferenceService(
                current.copy(foregroundActive = false)
            )
        }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val current = BackgroundTaskRegistry.status.value.inferenceService.copy(
            foregroundActive = true,
            lastLaunchOutcome = ForegroundLaunchOutcome.STARTED
        )
        BackgroundTaskRegistry.updateInferenceService(current)

        val notification = QuantNotificationChannels.buildInferenceNotification(this, current)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            QuantNotificationChannels.NOTIFICATION_ID_INFERENCE,
            notification,
            fgsType
        )
    }

    private fun stopSelfAndClearForeground(phaseLabel: String) {
        val current = BackgroundTaskRegistry.status.value.inferenceService
        BackgroundTaskRegistry.updateInferenceService(
            current.copy(
                isRunning = false,
                foregroundActive = false,
                phaseLabel = phaseLabel
            )
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START_OR_UPDATE = "com.quantchat.integration.action.INFERENCE_START_OR_UPDATE"
        const val ACTION_STOP = "com.quantchat.integration.action.INFERENCE_STOP"
        const val ACTION_COMPLETE = "com.quantchat.integration.action.INFERENCE_COMPLETE"
    }
}
