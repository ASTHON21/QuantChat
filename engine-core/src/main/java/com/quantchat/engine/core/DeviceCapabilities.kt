package com.quantchat.engine.core

import kotlinx.coroutines.flow.StateFlow

data class DeviceCapabilities(
    val cpuCores: Int,
    val cpuFeatures: Set<String>,
    val gpuBackends: Set<String>,
    val npuAvailable: Boolean,
    val availableMemoryBytes: Long,
    val thermalStatus: Int
) {
    val availableMemoryMb: Long
        get() = (availableMemoryBytes / (1024L * 1024L)).coerceAtLeast(0L)

    val hasVulkanGpu: Boolean
        get() = GPU_BACKEND_VULKAN in gpuBackends

    val isThermallyThrottled: Boolean
        get() = thermalStatus >= THERMAL_STATUS_MODERATE

    val isSevereThermal: Boolean
        get() = thermalStatus >= THERMAL_STATUS_SEVERE

    companion object {
        const val GPU_BACKEND_VULKAN = "VULKAN"
        const val GPU_BACKEND_OPENCL = "OPENCL"

        const val THERMAL_STATUS_UNKNOWN = -1
        const val THERMAL_STATUS_NOMINAL = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4
    }
}

interface HardwareCapabilityProvider {
    val deviceCapabilities: StateFlow<DeviceCapabilities>
    fun getDeviceCapabilities(): DeviceCapabilities
}

data class CapabilityBackendGateResult(
    val gpuEligible: Boolean,
    val reason: String? = null
)

object DeviceCapabilitiesPolicy {
    private const val MIN_GPU_SAFE_MEMORY_BYTES = 384L * 1024L * 1024L

    fun evaluateGpuEligibility(
        capabilities: DeviceCapabilities,
        preference: AcceleratorPreference
    ): CapabilityBackendGateResult {
        if (preference == AcceleratorPreference.CPU_ONLY) {
            return CapabilityBackendGateResult(gpuEligible = false, reason = "CPU Only preference selected.")
        }
        if (capabilities.isSevereThermal) {
            return CapabilityBackendGateResult(
                gpuEligible = false,
                reason = "Severe thermal status (${capabilities.thermalStatus}); routing inference to thermal-safe CPU."
            )
        }
        if (capabilities.availableMemoryBytes in 1L until MIN_GPU_SAFE_MEMORY_BYTES) {
            return CapabilityBackendGateResult(
                gpuEligible = false,
                reason = "Low available memory (${capabilities.availableMemoryMb}MB); keeping weights on safe CPU."
            )
        }
        if (!capabilities.hasVulkanGpu) {
            return CapabilityBackendGateResult(
                gpuEligible = false,
                reason = "DeviceCapabilities reports no verified Vulkan GPU backend."
            )
        }
        return CapabilityBackendGateResult(gpuEligible = true)
    }

    fun applyThermalDownshift(
        request: GenerationRequest,
        capabilities: DeviceCapabilities
    ): GenerationRequest {
        if (!capabilities.isThermallyThrottled) return request
        val cores = capabilities.cpuCores.coerceAtLeast(1)
        val requestedThreads = (request.threadsOverride ?: cores).coerceAtLeast(1)
        val cappedThreads = when {
            capabilities.thermalStatus >= DeviceCapabilities.THERMAL_STATUS_CRITICAL -> 1
            capabilities.thermalStatus >= DeviceCapabilities.THERMAL_STATUS_SEVERE -> minOf(requestedThreads, minOf(2, cores))
            else -> minOf(requestedThreads, maxOf(2, cores / 2).coerceAtMost(cores))
        }.coerceAtLeast(1)

        return request.copy(
            threadsOverride = cappedThreads,
            threadPriority = InferenceThreadPriority.BACKGROUND
        )
    }
}
