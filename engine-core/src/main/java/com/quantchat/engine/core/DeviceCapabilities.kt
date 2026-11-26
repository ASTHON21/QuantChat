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

    val isCriticalThermal: Boolean
        get() = thermalStatus >= THERMAL_STATUS_CRITICAL

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

enum class ThermalInferenceAction(val title: String, val behaviorLabel: String) {
    NORMAL("Normal", "Normal inference"),
    REDUCE_THREADS_AND_GPU("Throttle Load", "Reduce threads or GPU load"),
    THROTTLE_SPEED_AND_PAUSE_NON_ESSENTIAL("Severe Guard", "Reduce generation speed · pause non-essential tasks"),
    STOP_AND_RELEASE("Critical Stop", "Stop inference & safely release resources")
}

data class ThermalPolicyDecision(
    val thermalStatus: Int,
    val action: ThermalInferenceAction,
    val maxCpuThreads: Int,
    val gpuLoadScaleFactor: Float,
    val allowGpuOffload: Boolean,
    val tokenPacingDelayMs: Long,
    val pauseNonEssentialTasks: Boolean,
    val stopInferenceAndRelease: Boolean,
    val summary: String
)

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
    const val SEVERE_TOKEN_PACING_DELAY_MS = 35L

    fun evaluateThermalPolicy(
        capabilities: DeviceCapabilities,
        requestedThreads: Int = capabilities.cpuCores
    ): ThermalPolicyDecision {
        val cores = capabilities.cpuCores.coerceAtLeast(1)
        val safeRequested = requestedThreads.coerceIn(1, cores)
        return when {
            capabilities.isCriticalThermal -> ThermalPolicyDecision(
                thermalStatus = capabilities.thermalStatus,
                action = ThermalInferenceAction.STOP_AND_RELEASE,
                maxCpuThreads = 1,
                gpuLoadScaleFactor = 0f,
                allowGpuOffload = false,
                tokenPacingDelayMs = 0L,
                pauseNonEssentialTasks = true,
                stopInferenceAndRelease = true,
                summary = "CRITICAL thermal status: stopping active inference and releasing model resources."
            )
            capabilities.isSevereThermal -> ThermalPolicyDecision(
                thermalStatus = capabilities.thermalStatus,
                action = ThermalInferenceAction.THROTTLE_SPEED_AND_PAUSE_NON_ESSENTIAL,
                maxCpuThreads = minOf(safeRequested, minOf(2, cores)).coerceAtLeast(1),
                gpuLoadScaleFactor = 0f,
                allowGpuOffload = false,
                tokenPacingDelayMs = SEVERE_TOKEN_PACING_DELAY_MS,
                pauseNonEssentialTasks = true,
                stopInferenceAndRelease = false,
                summary = "SEVERE thermal status: throttling token speed (${SEVERE_TOKEN_PACING_DELAY_MS}ms pacing), 0 GPU layers, pausing downloads/benchmarks."
            )
            capabilities.isThermallyThrottled -> ThermalPolicyDecision(
                thermalStatus = capabilities.thermalStatus,
                action = ThermalInferenceAction.REDUCE_THREADS_AND_GPU,
                maxCpuThreads = minOf(safeRequested, maxOf(2, cores / 2).coerceAtMost(cores)).coerceAtLeast(1),
                gpuLoadScaleFactor = 0.5f,
                allowGpuOffload = true,
                tokenPacingDelayMs = 0L,
                pauseNonEssentialTasks = false,
                stopInferenceAndRelease = false,
                summary = "MODERATE thermal status: reducing CPU threads & halving GPU offload layers."
            )
            else -> ThermalPolicyDecision(
                thermalStatus = capabilities.thermalStatus,
                action = ThermalInferenceAction.NORMAL,
                maxCpuThreads = safeRequested,
                gpuLoadScaleFactor = 1.0f,
                allowGpuOffload = true,
                tokenPacingDelayMs = 0L,
                pauseNonEssentialTasks = false,
                stopInferenceAndRelease = false,
                summary = "NONE/LIGHT thermal status: normal inference."
            )
        }
    }

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
