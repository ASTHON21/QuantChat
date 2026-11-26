package com.quantchat.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

object QuantNotificationChannels {

    const val CHANNEL_DOWNLOADS = "quantchat_model_downloads"
    const val CHANNEL_INFERENCE = "quantchat_local_inference"
    const val CHANNEL_MAINTENANCE = "quantchat_model_maintenance"

    const val NOTIFICATION_ID_DOWNLOAD = 2301
    const val NOTIFICATION_ID_INFERENCE = 2302
    const val NOTIFICATION_ID_MAINTENANCE = 2303

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val downloadChannel = NotificationChannel(
            CHANNEL_DOWNLOADS,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground progress notifications for GGUF model downloads."
            setShowBadge(false)
        }

        val inferenceChannel = NotificationChannel(
            CHANNEL_INFERENCE,
            "On-Device LLM Inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active local model loading and token generation status."
            setShowBadge(false)
        }

        val maintenanceChannel = NotificationChannel(
            CHANNEL_MAINTENANCE,
            "Model Storage & Maintenance",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Periodic GGUF verification, temporary cache cleanup, and update checks."
            setShowBadge(false)
        }

        nm.createNotificationChannels(listOf(downloadChannel, inferenceChannel, maintenanceChannel))
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildDownloadNotification(
        context: Context,
        snapshot: DownloadServiceSnapshot
    ): Notification {
        ensureChannels(context)
        val total = snapshot.totalBytes
        val downloaded = snapshot.bytesDownloaded.coerceAtLeast(0L)
        val indeterminate = total <= 0L
        val progressPct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0

        val title = if (snapshot.modelName.isNotBlank()) {
            "Downloading ${snapshot.modelName}"
        } else {
            "Downloading GGUF Weights"
        }
        val contentText = buildString {
            if (snapshot.pausedByThermal) {
                append("Paused by Thermal Guard")
            } else {
                append(formatBytesMb(downloaded))
                if (total > 0L) {
                    append(" / ").append(formatBytesMb(total))
                    append(" (").append(progressPct).append("%)")
                }
                if (snapshot.speedBytesPerSec > 0L) {
                    val mbSec = snapshot.speedBytesPerSec / (1024.0 * 1024.0)
                    append(String.format(Locale.US, " · %.1f MB/s", mbSec))
                }
            }
        }

        val openModelsPending = buildLaunchPendingIntent(
            context = context,
            requestCode = 301,
            openModelsTab = true
        )
        val pauseIntent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_PAUSE
        }
        val pausePending = PendingIntent.getService(
            context,
            302,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            context,
            303,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.stat_sys_download

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.isRunning)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progressPct, indeterminate)
            .setContentIntent(openModelsPending)
            .addAction(0, "Pause", pausePending)
            .addAction(0, "Cancel", cancelPending)
            .build()
    }

    fun buildInferenceNotification(
        context: Context,
        snapshot: InferenceServiceSnapshot
    ): Notification {
        ensureChannels(context)
        val modelLabel = snapshot.modelName.ifBlank { "Local GGUF Model" }
        val title = "QuantChat Inference · $modelLabel"
        val details = buildString {
            append(snapshot.phaseLabel.ifBlank { "Running" })
            if (snapshot.tokenCount > 0) {
                append(" · ").append(snapshot.tokenCount).append(" tok")
            }
            if (snapshot.tokensPerSec > 0f) {
                append(String.format(Locale.US, " · %.1f tok/s", snapshot.tokensPerSec))
            }
            if (snapshot.backendLabel.isNotBlank()) {
                append(" · ").append(snapshot.backendLabel)
            }
        }

        val openChatPending = buildLaunchPendingIntent(
            context = context,
            requestCode = 401,
            openModelsTab = false
        )
        val stopIntent = Intent(context, InferenceForegroundService::class.java).apply {
            action = InferenceForegroundService.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            context,
            402,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_sync

        return NotificationCompat.Builder(context, CHANNEL_INFERENCE)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(details)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.isRunning)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openChatPending)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    fun buildMaintenanceNotification(
        context: Context,
        report: MaintenanceReport
    ): Notification {
        ensureChannels(context)
        val openModelsPending = buildLaunchPendingIntent(
            context = context,
            requestCode = 501,
            openModelsTab = true
        )
        val smallIcon = context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_sync

        return NotificationCompat.Builder(context, CHANNEL_MAINTENANCE)
            .setSmallIcon(smallIcon)
            .setContentTitle("Model Storage Maintenance Complete")
            .setContentText(report.summary)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openModelsPending)
            .build()
    }

    fun postNotificationIfPermitted(context: Context, id: Int, notification: Notification) {
        if (!hasPostNotificationsPermission(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    fun cancelNotification(context: Context, id: Int) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(id)
        }
    }

    private fun buildLaunchPendingIntent(
        context: Context,
        requestCode: Int,
        openModelsTab: Boolean
    ): PendingIntent? {
        val intent = BackgroundTaskRegistry.host?.createLaunchMainIntent(openModelsTab)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                if (openModelsTab) action = SystemIntegrationBridge.ACTION_OPEN_MODELS
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatBytesMb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
