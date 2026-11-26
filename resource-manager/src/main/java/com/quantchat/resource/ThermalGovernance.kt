package com.quantchat.resource

import com.quantchat.engine.core.DeviceCapabilities
import com.quantchat.engine.core.DeviceCapabilitiesPolicy
import com.quantchat.engine.core.ThermalInferenceAction
import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.hardware.ThermalSeverity

data class ThermalRuntimeActionState(
    val severity: ThermalSeverity = ThermalSeverity.NOMINAL,
    val action: ThermalInferenceAction = ThermalInferenceAction.NORMAL,
    val autoProtectionEnabled: Boolean = true,
    val threadCap: Int = 8,
    val gpuLoadScaleFactor: Float = 1.0f,
    val tokenPacingDelayMs: Long = 0L,
    val nonEssentialTasksPaused: Boolean = false,
    val inferenceBlocked: Boolean = false,
    val emergencyReleaseCount: Int = 0,
    val lastThermalEventNote: String? = null,
    val thermalHeadroomForecast: Float? = null
)

object ThermalGovernor {

    fun evaluate(
        severity: ThermalSeverity,
        deviceCapabilities: DeviceCapabilities?,
        liveMetrics: LiveDeviceMetrics?,
        autoProtectionEnabled: Boolean,
        requestedThreads: Int,
        emergencyReleaseCount: Int = 0,
        lastThermalEventNote: String? = null
    ): ThermalRuntimeActionState {
        val effectiveStatusCode = if (autoProtectionEnabled) {
            severity.statusCode
        } else {
            DeviceCapabilities.THERMAL_STATUS_NOMINAL
        }
        val caps = (deviceCapabilities ?: DeviceCapabilities(
            cpuCores = requestedThreads.coerceAtLeast(1),
            cpuFeatures = emptySet(),
            gpuBackends = emptySet(),
            npuAvailable = false,
            availableMemoryBytes = liveMetrics?.availableMemoryBytes ?: 0L,
            thermalStatus = effectiveStatusCode
        )).copy(thermalStatus = effectiveStatusCode)

        val policy = DeviceCapabilitiesPolicy.evaluateThermalPolicy(
            capabilities = caps,
            requestedThreads = requestedThreads
        )

        val note = lastThermalEventNote ?: when {
            !autoProtectionEnabled && severity.statusCode >= DeviceCapabilities.THERMAL_STATUS_MODERATE ->
                "Auto thermal protection disabled by user (${severity.label})."
            else -> policy.summary
        }

        return ThermalRuntimeActionState(
            severity = severity,
            action = policy.action,
            autoProtectionEnabled = autoProtectionEnabled,
            threadCap = policy.maxCpuThreads,
            gpuLoadScaleFactor = policy.gpuLoadScaleFactor,
            tokenPacingDelayMs = policy.tokenPacingDelayMs,
            nonEssentialTasksPaused = policy.pauseNonEssentialTasks,
            inferenceBlocked = policy.stopInferenceAndRelease,
            emergencyReleaseCount = emergencyReleaseCount,
            lastThermalEventNote = note,
            thermalHeadroomForecast = liveMetrics?.thermalHeadroomForecast
        )
    }
}
