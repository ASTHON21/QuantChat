package com.quantchat.resource

import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import com.quantchat.model.GgufHeaderMetadata
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class GpuOffloadPlan(
    val totalLayers: Int,
    val recommendedLayers: Int,
    val effectiveLayers: Int,
    val estimatedGpuMemoryMb: Long,
    val estimatedCpuMemoryMb: Long,
    val gpuBudgetMb: Long,
    val isAutoMode: Boolean,
    val policySummary: String
)

object GpuOffloadPlanner {

    private const val DEFAULT_LAYER_COUNT = 32
    private const val MIN_OS_HEADROOM_MB = 1024L
    private const val SHADER_PIPELINE_OVERHEAD_MB = 128L

    fun planOffload(
        fileSizeBytes: Long,
        header: GgufHeaderMetadata?,
        contextSize: Int,
        availableRamMb: Long,
        lowMemory: Boolean,
        vulkanProbe: VulkanProbeResult?,
        preference: AcceleratorPreference,
        autoOffload: Boolean,
        manualLayersOverride: Int
    ): GpuOffloadPlan {
        val totalLayers = (header?.blockCount ?: DEFAULT_LAYER_COUNT).coerceIn(1, 128)
        val clampedManual = manualLayersOverride.coerceIn(0, totalLayers)

        val weightsMb = if (fileSizeBytes > 0L) {
            ((fileSizeBytes * 0.95) / (1024.0 * 1024.0)).toLong().coerceAtLeast(16L)
        } else {
            0L
        }
        val kvAndScratchMb = if (fileSizeBytes > 0L) {
            val totalEst = header?.estimateRuntimeMemoryMb(fileSizeBytes, contextSize)
                ?: (weightsMb + 256L)
            (totalEst - weightsMb).coerceAtLeast(160L)
        } else {
            160L
        }

        val osAndCpuHeadroomMb = max(MIN_OS_HEADROOM_MB, (availableRamMb * 35L) / 100L)
        val systemSafeBudgetMb = (availableRamMb - osAndCpuHeadroomMb - (kvAndScratchMb / 2L)).coerceAtLeast(0L)
        val driverBudgetMb = vulkanProbe?.recommendedMaxOffloadMb?.takeIf { it > 0L } ?: systemSafeBudgetMb
        val rawGpuBudgetMb = if (lowMemory || availableRamMb < 1400L) {
            0L
        } else {
            min(systemSafeBudgetMb, driverBudgetMb)
        }

        val vulkanUsable = vulkanProbe?.isUsableForInference == true
        val perLayerMb = if (weightsMb > 0L) {
            (weightsMb.toDouble() / totalLayers.toDouble()).coerceAtLeast(8.0)
        } else {
            48.0
        }

        val budgetForLayersMb = (rawGpuBudgetMb - SHADER_PIPELINE_OVERHEAD_MB).coerceAtLeast(0L)
        val memoryRecommendedLayers = if (weightsMb <= 0L && preference != AcceleratorPreference.PREFER_VULKAN) {
            0
        } else {
            (budgetForLayersMb.toDouble() / perLayerMb).toInt().coerceIn(0, totalLayers)
        }

        val recommendedLayers = when {
            preference == AcceleratorPreference.CPU_ONLY -> 0
            vulkanUsable -> memoryRecommendedLayers
            preference == AcceleratorPreference.PREFER_VULKAN -> memoryRecommendedLayers
            else -> 0
        }

        val effectiveLayers = when {
            preference == AcceleratorPreference.CPU_ONLY -> 0
            autoOffload -> recommendedLayers
            else -> clampedManual
        }

        val offloadWeightMb = (effectiveLayers * perLayerMb).toLong().coerceAtMost(weightsMb)
        val estimatedGpuMb = if (effectiveLayers > 0) offloadWeightMb + SHADER_PIPELINE_OVERHEAD_MB else 0L
        val estimatedCpuMb = (weightsMb - offloadWeightMb).coerceAtLeast(0L) + kvAndScratchMb

        val summary = when {
            preference == AcceleratorPreference.CPU_ONLY ->
                "CPU Only selected · 0/${totalLayers}L offloaded"
            lowMemory ->
                "Low memory flag active · holding 0/${totalLayers}L on GPU"
            !vulkanUsable -> {
                val reason = vulkanProbe?.status?.label ?: VulkanProbeStatus.NATIVE_LIBRARY_MISSING.label
                "$reason · CPU safe fallback ready"
            }
            autoOffload -> String.format(
                Locale.US,
                "Auto RAM/VRAM: %d/%dL GPU (~%dMB VRAM · %dMB CPU headroom)",
                effectiveLayers,
                totalLayers,
                estimatedGpuMb,
                osAndCpuHeadroomMb
            )
            else -> String.format(
                Locale.US,
                "Manual override: %d/%dL GPU (~%dMB GPU · ~%dMB CPU)",
                effectiveLayers,
                totalLayers,
                estimatedGpuMb,
                estimatedCpuMb
            )
        }

        return GpuOffloadPlan(
            totalLayers = totalLayers,
            recommendedLayers = recommendedLayers,
            effectiveLayers = effectiveLayers,
            estimatedGpuMemoryMb = estimatedGpuMb,
            estimatedCpuMemoryMb = estimatedCpuMb,
            gpuBudgetMb = rawGpuBudgetMb,
            isAutoMode = autoOffload,
            policySummary = summary
        )
    }
}
