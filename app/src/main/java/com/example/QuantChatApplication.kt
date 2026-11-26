package com.example

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.example.data.QuantRuntimeHost
import com.quantchat.integration.BackgroundExecutionHost
import com.quantchat.integration.BackgroundTaskRegistry
import com.quantchat.integration.MaintenanceReport
import com.quantchat.integration.ModelMaintenanceWorker
import com.quantchat.integration.QuantNotificationChannels
import com.quantchat.integration.SystemIntegrationBridge

class QuantChatApplication : Application() {

    private var startedActivityCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        QuantNotificationChannels.ensureChannels(this)
        BackgroundTaskRegistry.refreshNotificationPermission(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                BackgroundTaskRegistry.setAppInForeground(startedActivityCount > 0)
            }
            override fun onActivityResumed(activity: Activity) {
                BackgroundTaskRegistry.refreshNotificationPermission(this@QuantChatApplication)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                BackgroundTaskRegistry.setAppInForeground(startedActivityCount > 0)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        BackgroundTaskRegistry.registerHost(object : BackgroundExecutionHost {
            private val host by lazy { QuantRuntimeHost.getInstance(this@QuantChatApplication) }

            override fun isThermalBlockingNonEssentialMaintenance(): Boolean =
                host.isThermalBlockingNonEssentialMaintenance()

            override fun onPauseDownloadFromNotification() =
                host.onPauseDownloadFromNotification()

            override fun onCancelDownloadFromNotification() =
                host.onCancelDownloadFromNotification()

            override fun onStopInferenceFromNotification() =
                host.onStopInferenceFromNotification()

            override suspend fun runMaintenanceSweep(checkUpdatesOnline: Boolean): MaintenanceReport =
                host.runMaintenanceSweep(checkUpdatesOnline)

            override fun createLaunchMainIntent(openModelsTab: Boolean): Intent {
                return Intent(this@QuantChatApplication, MainActivity::class.java).apply {
                    if (openModelsTab) {
                        action = SystemIntegrationBridge.ACTION_OPEN_MODELS
                    }
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
        })

        ModelMaintenanceWorker.schedulePeriodic(this)
    }
}
