package com.quantchat.integration

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

object ForegroundServiceLauncher {

    fun isAppForegroundEligible(context: Context): Boolean {
        if (BackgroundTaskRegistry.status.value.appInForeground) return true
        val info = ActivityManager.RunningAppProcessInfo()
        val hasProcessState = runCatching {
            ActivityManager.getMyMemoryState(info)
            true
        }.getOrDefault(false)
        if (!hasProcessState) return false
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    fun startOrUpdateDownloadService(
        context: Context,
        snapshot: DownloadServiceSnapshot
    ): ForegroundLaunchOutcome {
        QuantNotificationChannels.ensureChannels(context)
        BackgroundTaskRegistry.refreshNotificationPermission(context)

        val current = BackgroundTaskRegistry.status.value.downloadService
        if (current.foregroundActive) {
            val updated = snapshot.copy(
                foregroundActive = true,
                lastLaunchOutcome = ForegroundLaunchOutcome.STARTED
            )
            BackgroundTaskRegistry.updateDownloadService(updated)
            QuantNotificationChannels.postNotificationIfPermitted(
                context = context,
                id = QuantNotificationChannels.NOTIFICATION_ID_DOWNLOAD,
                notification = QuantNotificationChannels.buildDownloadNotification(context, updated)
            )
            return ForegroundLaunchOutcome.STARTED
        }

        if (!isAppForegroundEligible(context)) {
            val blocked = snapshot.copy(
                foregroundActive = false,
                statusText = "Running without FGS (Android background FGS launch restriction)",
                lastLaunchOutcome = ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
            )
            BackgroundTaskRegistry.updateDownloadService(blocked)
            return ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
        }

        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_OR_UPDATE
        }
        BackgroundTaskRegistry.updateDownloadService(
            snapshot.copy(lastLaunchOutcome = ForegroundLaunchOutcome.STARTED)
        )
        return launchForegroundServiceSafely(
            context = context,
            intent = intent,
            onBlocked = {
                BackgroundTaskRegistry.updateDownloadService(
                    snapshot.copy(
                        foregroundActive = false,
                        statusText = "Blocked by Android FGS background start limit",
                        lastLaunchOutcome = ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
                    )
                )
            },
            onFailed = {
                BackgroundTaskRegistry.updateDownloadService(
                    snapshot.copy(
                        foregroundActive = false,
                        lastLaunchOutcome = ForegroundLaunchOutcome.FAILED
                    )
                )
            }
        )
    }

    fun stopDownloadService(
        context: Context,
        finalStatusText: String = "Idle",
        pausedByThermal: Boolean = false
    ) {
        val current = BackgroundTaskRegistry.status.value.downloadService
        BackgroundTaskRegistry.updateDownloadService(
            current.copy(
                isRunning = false,
                foregroundActive = false,
                speedBytesPerSec = 0L,
                statusText = finalStatusText,
                pausedByThermal = pausedByThermal
            )
        )
        QuantNotificationChannels.cancelNotification(context, QuantNotificationChannels.NOTIFICATION_ID_DOWNLOAD)
        runCatching {
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }
    }

    fun startOrUpdateInferenceService(
        context: Context,
        snapshot: InferenceServiceSnapshot
    ): ForegroundLaunchOutcome {
        QuantNotificationChannels.ensureChannels(context)
        BackgroundTaskRegistry.refreshNotificationPermission(context)

        val current = BackgroundTaskRegistry.status.value.inferenceService
        if (current.foregroundActive) {
            val updated = snapshot.copy(
                foregroundActive = true,
                lastLaunchOutcome = ForegroundLaunchOutcome.STARTED
            )
            BackgroundTaskRegistry.updateInferenceService(updated)
            QuantNotificationChannels.postNotificationIfPermitted(
                context = context,
                id = QuantNotificationChannels.NOTIFICATION_ID_INFERENCE,
                notification = QuantNotificationChannels.buildInferenceNotification(context, updated)
            )
            return ForegroundLaunchOutcome.STARTED
        }

        if (!isAppForegroundEligible(context)) {
            val blocked = snapshot.copy(
                foregroundActive = false,
                phaseLabel = "Foreground blocked (background restriction)",
                lastLaunchOutcome = ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
            )
            BackgroundTaskRegistry.updateInferenceService(blocked)
            return ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
        }

        val intent = Intent(context, InferenceForegroundService::class.java).apply {
            action = InferenceForegroundService.ACTION_START_OR_UPDATE
        }
        BackgroundTaskRegistry.updateInferenceService(
            snapshot.copy(lastLaunchOutcome = ForegroundLaunchOutcome.STARTED)
        )
        return launchForegroundServiceSafely(
            context = context,
            intent = intent,
            onBlocked = {
                BackgroundTaskRegistry.updateInferenceService(
                    snapshot.copy(
                        foregroundActive = false,
                        phaseLabel = "Blocked by background FGS policy",
                        lastLaunchOutcome = ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
                    )
                )
            },
            onFailed = {
                BackgroundTaskRegistry.updateInferenceService(
                    snapshot.copy(
                        foregroundActive = false,
                        lastLaunchOutcome = ForegroundLaunchOutcome.FAILED
                    )
                )
            }
        )
    }

    fun stopInferenceService(
        context: Context,
        finalPhaseLabel: String = "Idle"
    ) {
        val current = BackgroundTaskRegistry.status.value.inferenceService
        BackgroundTaskRegistry.updateInferenceService(
            current.copy(
                isRunning = false,
                foregroundActive = false,
                phaseLabel = finalPhaseLabel
            )
        )
        QuantNotificationChannels.cancelNotification(context, QuantNotificationChannels.NOTIFICATION_ID_INFERENCE)
        runCatching {
            context.stopService(Intent(context, InferenceForegroundService::class.java))
        }
    }

    private fun launchForegroundServiceSafely(
        context: Context,
        intent: Intent,
        onBlocked: () -> Unit,
        onFailed: () -> Unit
    ): ForegroundLaunchOutcome {
        return try {
            ContextCompat.startForegroundService(context, intent)
            ForegroundLaunchOutcome.STARTED
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                onBlocked()
                ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
            } else if (e is IllegalStateException) {
                onBlocked()
                ForegroundLaunchOutcome.BLOCKED_BACKGROUND_RESTRICTION
            } else {
                onFailed()
                ForegroundLaunchOutcome.FAILED
            }
        }
    }
}
