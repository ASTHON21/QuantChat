package com.quantchat.integration

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Deferrable background maintenance worker for storage cleanup, GGUF integrity verification,
 * orphaned KV-cache pruning, and lightweight update checks.
 *
 * Intentionally NEVER used for real-time streaming token generation (handled by InferenceForegroundService).
 */
class ModelMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val host = BackgroundTaskRegistry.host
        if (host == null) {
            val report = MaintenanceReport(
                ranAtMs = System.currentTimeMillis(),
                summary = "Maintenance skipped: host runtime not initialized"
            )
            BackgroundTaskRegistry.updateMaintenance(report, "Skipped (host unavailable)")
            return Result.success()
        }

        if (host.isThermalBlockingNonEssentialMaintenance()) {
            val deferredReport = MaintenanceReport(
                ranAtMs = System.currentTimeMillis(),
                deferredByThermal = true,
                summary = "Deferred by Thermal Guard (SEVERE/CRITICAL)"
            )
            BackgroundTaskRegistry.updateMaintenance(deferredReport, "Deferred by Thermal Guard")
            return Result.retry()
        }

        BackgroundTaskRegistry.updateMaintenance(null, "Running maintenance sweep...")
        val checkUpdates = inputData.getBoolean(KEY_CHECK_ONLINE_UPDATES, true)
        val report = runCatching { host.runMaintenanceSweep(checkUpdates) }
            .getOrElse { error ->
                val failedReport = MaintenanceReport(
                    ranAtMs = System.currentTimeMillis(),
                    summary = "Maintenance failed: ${error.localizedMessage ?: "I/O error"}"
                )
                BackgroundTaskRegistry.updateMaintenance(failedReport, "Failed")
                return Result.retry()
            }

        BackgroundTaskRegistry.updateMaintenance(report, "Completed · ${report.summary}")
        if (inputData.getBoolean(KEY_POST_COMPLETION_NOTIFICATION, false) ||
            report.stalePartFilesRemoved > 0 ||
            report.prunedSessionFiles > 0 ||
            report.corruptGgufCount > 0
        ) {
            QuantNotificationChannels.postNotificationIfPermitted(
                context = applicationContext,
                id = QuantNotificationChannels.NOTIFICATION_ID_MAINTENANCE,
                notification = QuantNotificationChannels.buildMaintenanceNotification(
                    context = applicationContext,
                    report = report
                )
            )
        }

        return Result.success(
            workDataOf(
                OUT_STALE_PARTS_REMOVED to report.stalePartFilesRemoved,
                OUT_VERIFIED_GGUF to report.verifiedGgufCount,
                OUT_CORRUPT_GGUF to report.corruptGgufCount,
                OUT_PRUNED_SESSIONS to report.prunedSessionFiles,
                OUT_SUMMARY to report.summary
            )
        )
    }

    companion object {
        const val SUPPORTS_REALTIME_GENERATION = false
        const val WORK_NAME_PERIODIC = "quantchat_model_maintenance_periodic"
        const val WORK_NAME_IMMEDIATE = "quantchat_model_maintenance_immediate"

        const val KEY_CHECK_ONLINE_UPDATES = "check_online_updates"
        const val KEY_POST_COMPLETION_NOTIFICATION = "post_completion_notification"

        const val OUT_STALE_PARTS_REMOVED = "stale_parts_removed"
        const val OUT_VERIFIED_GGUF = "verified_gguf_count"
        const val OUT_CORRUPT_GGUF = "corrupt_gguf_count"
        const val OUT_PRUNED_SESSIONS = "pruned_sessions"
        const val OUT_SUMMARY = "summary"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ModelMaintenanceWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_CHECK_ONLINE_UPDATES to true,
                        KEY_POST_COMPLETION_NOTIFICATION to false
                    )
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

        fun enqueueImmediateCheck(
            context: Context,
            checkOnlineUpdates: Boolean = true
        ) {
            val request = OneTimeWorkRequestBuilder<ModelMaintenanceWorker>()
                .setInputData(
                    workDataOf(
                        KEY_CHECK_ONLINE_UPDATES to checkOnlineUpdates,
                        KEY_POST_COMPLETION_NOTIFICATION to true
                    )
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME_IMMEDIATE,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
