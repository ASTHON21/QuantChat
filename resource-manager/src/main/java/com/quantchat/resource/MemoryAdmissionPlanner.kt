package com.quantchat.resource

import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.hardware.ProcessMemoryTelemetry
import com.quantchat.model.GgufHeaderMetadata

data class MemoryBudgetBreakdown(
    val weightsMb: Long,
    val kvCacheMb: Long,
    val computeBufferMb: Long,
    val runtimeMb: Long,
    val reserveMb: Long,
    val totalRequiredMb: Long,
    val systemAvailableMb: Long,
    val nominalTotalRamMb: Long,
    val safeHeadroomMb: Long,
    val activeContextSize: Int,
    val plannedGpuLayers: Int
)

data class AdmissionDowngradeSuggestion(
    val targetContextSize: Int,
    val targetGpuLayers: Int,
    val projectedTotalMb: Long,
    val projectedHeadroomMb: Long,
    val rationale: String
)

object MemoryAdmissionPlanner {

    private const val WARNING_HEADROOM_THRESHOLD_MB = 256L

    fun computeBreakdown(
        fileSizeBytes: Long,
        header: GgufHeaderMetadata?,
        contextSize: Int,
        batchSize: Int = 512,
        plannedGpuLayers: Int = 0,
        totalLayers: Int = header?.blockCount ?: 32,
        metrics: LiveDeviceMetrics
    ): MemoryBudgetBreakdown {
        val safeCtx = contextSize.coerceAtLeast(256)
        val safeLayers = (totalLayers.takeIf { it > 0 } ?: header?.blockCount ?: 32).coerceAtLeast(1)
        val safeGpuLayers = plannedGpuLayers.coerceIn(0, safeLayers)

        // 1. M_weights: GGUF tensor footprint + unified LPDDR Vulkan staging overhead when GPU layers > 0
        val baseWeightsMb = ((fileSizeBytes * 0.96) / (1024.0 * 1024.0)).toLong().coerceAtLeast(16L)
        val gpuStagingMb = if (safeGpuLayers > 0) {
            val fraction = (safeGpuLayers.toDouble() / safeLayers.toDouble()).coerceIn(0.0, 1.0)
            (baseWeightsMb * fraction * 0.08).toLong().coerceAtLeast(8L)
        } else {
            0L
        }
        val weightsMb = baseWeightsMb + gpuStagingMb

        // 2. M_KV: 2 (K + V) * 2B (FP16) * block_count * kv_heads * head_dim * n_ctx
        val modelLayers = (header?.blockCount ?: 32).coerceAtLeast(1)
        val kvHeads = (header?.kvHeadCount ?: 8).coerceAtLeast(1)
        val emb = header?.embeddingLength
        val headDim = if (emb != null && kvHeads > 0) {
            (emb / kvHeads).coerceIn(64, 256)
        } else {
            128
        }
        val kvBytes = 4L * modelLayers * kvHeads * headDim * safeCtx.toLong()
        val kvCacheMb = (kvBytes / (1024L * 1024L)).coerceAtLeast(16L)

        // 3. M_compute: ggml compute scratch graph + batch activation buffers + GPU command scratch
        val hiddenDim = (header?.embeddingLength ?: 2560).coerceIn(512, 8192)
        val baseComputeMb = ((batchSize.coerceAtLeast(128).toLong() * hiddenDim * 12L) / (1024L * 1024L))
            .coerceAtLeast(32L)
        val gpuComputeExtraMb = if (safeGpuLayers > 0) 48L else 0L
        val computeBufferMb = baseComputeMb + gpuComputeExtraMb

        // 4. M_runtime: llama.cpp context + host app baseline grounded in live process RSS/PSS
        val telemetry = metrics.processMemory
        val hostFootprintShare = ((telemetry?.effectiveProcessFootprintMb ?: 0L) / 2L).coerceAtMost(256L)
        val runtimeMb = (96L + hostFootprintShare).coerceAtLeast(96L)

        // 5. M_reserve: Android ActivityManager.MemoryInfo.threshold (LMK danger floor) + app safety cushion
        val lmkThresholdMb = telemetry?.systemThresholdMb ?: 0L
        val appCushionMb = if (metrics.totalRamMb in 1L..4096L) 192L else 256L
        val reserveMb = maxOf(lmkThresholdMb + 128L, appCushionMb)

        val totalRequiredMb = weightsMb + kvCacheMb + computeBufferMb + runtimeMb + reserveMb
        val availMb = metrics.availableRamMb.coerceAtLeast(0L)
        val headroomMb = if (availMb > 0L) availMb - totalRequiredMb else 0L

        return MemoryBudgetBreakdown(
            weightsMb = weightsMb,
            kvCacheMb = kvCacheMb,
            computeBufferMb = computeBufferMb,
            runtimeMb = runtimeMb,
            reserveMb = reserveMb,
            totalRequiredMb = totalRequiredMb,
            systemAvailableMb = availMb,
            nominalTotalRamMb = metrics.totalRamMb.coerceAtLeast(0L),
            safeHeadroomMb = headroomMb,
            activeContextSize = safeCtx,
            plannedGpuLayers = safeGpuLayers
        )
    }

    fun evaluate(
        fileSizeBytes: Long,
        header: GgufHeaderMetadata?,
        contextSize: Int,
        batchSize: Int = 512,
        plannedGpuLayers: Int = 0,
        totalLayers: Int = header?.blockCount ?: 32,
        metrics: LiveDeviceMetrics
    ): AdmissionAssessment {
        val breakdown = computeBreakdown(
            fileSizeBytes = fileSizeBytes,
            header = header,
            contextSize = contextSize,
            batchSize = batchSize,
            plannedGpuLayers = plannedGpuLayers,
            totalLayers = totalLayers,
            metrics = metrics
        )
        val telemetry = metrics.processMemory
        val avail = metrics.availableRamMb

        if (avail <= 0L) {
            return AdmissionAssessment(
                verdict = AdmissionVerdict.ALLOWED,
                estimatedMemoryMb = breakdown.totalRequiredMb,
                availableMemoryMb = avail,
                summary = "Est. ~${breakdown.totalRequiredMb}MB (OS memory telemetry unavailable)",
                breakdown = breakdown,
                processTelemetry = telemetry
            )
        }

        val verdict = when {
            metrics.lowMemory || avail < breakdown.totalRequiredMb -> AdmissionVerdict.BLOCKED
            breakdown.safeHeadroomMb < WARNING_HEADROOM_THRESHOLD_MB -> AdmissionVerdict.WARNING
            else -> AdmissionVerdict.ALLOWED
        }

        val suggestion = if (verdict == AdmissionVerdict.BLOCKED && !metrics.lowMemory) {
            findSafeDowngrade(
                fileSizeBytes = fileSizeBytes,
                header = header,
                currentContextSize = contextSize,
                batchSize = batchSize,
                currentGpuLayers = plannedGpuLayers,
                totalLayers = totalLayers,
                metrics = metrics
            )
        } else {
            null
        }

        val blockReason = if (verdict == AdmissionVerdict.BLOCKED) {
            buildString {
                if (metrics.lowMemory) {
                    append("Android OS reported lowMemory=true (${avail}MB free). ")
                } else {
                    append(
                        "Required M_total (${breakdown.totalRequiredMb}MB = " +
                            "W:${breakdown.weightsMb} + KV:${breakdown.kvCacheMb} + " +
                            "C:${breakdown.computeBufferMb} + Rt:${breakdown.runtimeMb} + Res:${breakdown.reserveMb}) " +
                            "exceeds live ActivityManager availMem (${avail}MB)."
                    )
                }
                if (suggestion != null) {
                    append(" Try ${suggestion.rationale}.")
                }
            }.trim()
        } else {
            null
        }

        val summary = when (verdict) {
            AdmissionVerdict.ALLOWED ->
                "SAFE · M_total ${breakdown.totalRequiredMb}MB / ${avail}MB free (+${breakdown.safeHeadroomMb}MB)"
            AdmissionVerdict.WARNING ->
                "TIGHT · M_total ${breakdown.totalRequiredMb}MB vs ${avail}MB free (+${breakdown.safeHeadroomMb}MB cushion)"
            AdmissionVerdict.BLOCKED ->
                "BLOCKED · M_total ${breakdown.totalRequiredMb}MB > ${avail}MB free"
        }

        return AdmissionAssessment(
            verdict = verdict,
            estimatedMemoryMb = breakdown.totalRequiredMb,
            availableMemoryMb = avail,
            summary = summary,
            breakdown = breakdown,
            processTelemetry = telemetry,
            downgradeSuggestion = suggestion,
            blockReason = blockReason
        )
    }

    private fun findSafeDowngrade(
        fileSizeBytes: Long,
        header: GgufHeaderMetadata?,
        currentContextSize: Int,
        batchSize: Int,
        currentGpuLayers: Int,
        totalLayers: Int,
        metrics: LiveDeviceMetrics
    ): AdmissionDowngradeSuggestion? {
        val ctxCandidates = listOf(currentContextSize, 4096, 2048, 1024, 512)
            .filter { it <= currentContextSize }
            .distinct()
            .sortedDescending()

        val candidates = buildList {
            if (currentGpuLayers > 0) {
                for (ctx in ctxCandidates) {
                    if (ctx < currentContextSize) {
                        add(ctx to currentGpuLayers)
                    }
                }
            }
            for (ctx in ctxCandidates) {
                if (currentGpuLayers > 0 || ctx < currentContextSize) {
                    add(ctx to 0)
                }
            }
        }

        for ((ctx, gpu) in candidates) {
            val candidateBreakdown = computeBreakdown(
                fileSizeBytes = fileSizeBytes,
                header = header,
                contextSize = ctx,
                batchSize = batchSize,
                plannedGpuLayers = gpu,
                totalLayers = totalLayers,
                metrics = metrics
            )
            if (candidateBreakdown.totalRequiredMb <= metrics.availableRamMb) {
                val parts = buildList {
                    if (ctx < currentContextSize) add("n_ctx=$ctx")
                    if (gpu == 0 && currentGpuLayers > 0) add("CPU-only (0 GPU layers)")
                }
                val rationale = if (parts.isEmpty()) {
                    "n_ctx=$ctx (${candidateBreakdown.totalRequiredMb}MB)"
                } else {
                    "${parts.joinToString(" + ")} (~${candidateBreakdown.totalRequiredMb}MB)"
                }
                return AdmissionDowngradeSuggestion(
                    targetContextSize = ctx,
                    targetGpuLayers = gpu,
                    projectedTotalMb = candidateBreakdown.totalRequiredMb,
                    projectedHeadroomMb = candidateBreakdown.safeHeadroomMb,
                    rationale = rationale
                )
            }
        }
        return null
    }
}
