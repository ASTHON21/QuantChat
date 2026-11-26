package com.quantchat.resource

import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.engine.core.InferenceThreadPriority
import com.quantchat.engine.core.WorkloadClass
import com.quantchat.hardware.DeviceHardwareProfile
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.hardware.ProcessMemoryTelemetry
import com.quantchat.hardware.ThermalSeverity
import com.quantchat.model.GgufHeaderMetadata
import java.io.File

enum class AdmissionVerdict {
    ALLOWED,
    WARNING,
    BLOCKED
}

data class AdmissionAssessment(
    val verdict: AdmissionVerdict,
    val estimatedMemoryMb: Long,
    val availableMemoryMb: Long,
    val summary: String,
    val breakdown: MemoryBudgetBreakdown? = null,
    val processTelemetry: ProcessMemoryTelemetry? = null,
    val downgradeSuggestion: AdmissionDowngradeSuggestion? = null,
    val blockReason: String? = null
)

data class ThreadSchedulingDecision(
    val requestedMode: CpuScheduleMode,
    val effectiveMode: CpuScheduleMode,
    val effectiveThreads: Int,
    val isThermallyThrottled: Boolean,
    val workloadHint: WorkloadClass,
    val priorityHint: InferenceThreadPriority,
    val rationale: String
)

data class ThreadCalibrationEntry(
    val threads: Int,
    val prefillTokPerSec: Float,
    val decodeTokPerSec: Float,
    val compositeScore: Float
)

data class ThreadCalibrationResult(
    val deviceSignature: String,
    val optimalThreads: Int,
    val entries: List<ThreadCalibrationEntry>,
    val calibratedAtMs: Long
)

data class CalibrationProgress(
    val running: Boolean = false,
    val currentThreads: Int? = null,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val completedEntries: List<ThreadCalibrationEntry> = emptyList()
)

class RuntimeResourceManager(
    private val hardwareManager: HardwareCapabilityManager
) {

    fun liveMetrics(): LiveDeviceMetrics = hardwareManager.currentMemoryAndThermalSnapshot()

    fun classifyWorkload(
        promptChars: Int,
        historyTurns: Int,
        estimatedPromptTokens: Int,
        contextSize: Int
    ): WorkloadClass {
        val longThreshold = (contextSize * 0.45f).toInt().coerceAtLeast(512)
        return when {
            estimatedPromptTokens >= longThreshold || promptChars >= 1200 -> WorkloadClass.LONG_CONTEXT
            historyTurns <= 1 && estimatedPromptTokens < 160 && promptChars < 180 -> WorkloadClass.LIGHT
            else -> WorkloadClass.STANDARD
        }
    }

    fun resolveSchedulingDecision(
        profile: DeviceHardwareProfile,
        requestedMode: CpuScheduleMode = CpuScheduleMode.BALANCED,
        autoThermalDownshift: Boolean = true,
        thermal: ThermalSeverity = profile.liveMetrics.thermalSeverity,
        workload: WorkloadClass = WorkloadClass.STANDARD,
        calibratedOptimalThreads: Int? = null,
        manualThreadsOverride: Int? = null
    ): ThreadSchedulingDecision {
        val clusters = profile.cpuClusters
        val totalCap = clusters.totalCores.coerceAtLeast(1)
        val onlineCap = clusters.onlineCores.coerceIn(1, totalCap)
        val rawFastCores = clusters.primeCores + clusters.performanceCores
        val fastCores = if (rawFastCores > 0) {
            minOf(rawFastCores, onlineCap)
        } else {
            minOf(4, onlineCap)
        }

        val thermalHot = thermal == ThermalSeverity.MODERATE ||
            thermal == ThermalSeverity.SEVERE ||
            thermal == ThermalSeverity.CRITICAL

        val autoTriggered = autoThermalDownshift && thermalHot && requestedMode != CpuScheduleMode.THERMAL_SAFE
        val effectiveMode = if (autoTriggered) CpuScheduleMode.THERMAL_SAFE else requestedMode
        val isThermallyThrottled = effectiveMode == CpuScheduleMode.THERMAL_SAFE && thermalHot

        val validCalibrated = calibratedOptimalThreads?.coerceIn(1, minOf(8, onlineCap))

        val modeResult: Pair<Int, InferenceThreadPriority> = when (effectiveMode) {
            CpuScheduleMode.ECO -> {
                val effPool = if (clusters.efficiencyCores > 0) clusters.efficiencyCores else onlineCap
                val baseEco = (effPool / 2).coerceIn(2, 3).coerceAtMost(onlineCap)
                val adapted = if (workload == WorkloadClass.LIGHT) {
                    minOf(2, baseEco).coerceAtLeast(1)
                } else {
                    baseEco
                }
                adapted to InferenceThreadPriority.BACKGROUND
            }

            CpuScheduleMode.BALANCED -> {
                val anchor = validCalibrated ?: fastCores.coerceIn(minOf(2, onlineCap), minOf(6, onlineCap))
                val adapted = when (workload) {
                    WorkloadClass.LIGHT -> if (anchor >= 4) (anchor - 1).coerceAtLeast(2) else anchor
                    WorkloadClass.STANDARD -> anchor
                    WorkloadClass.LONG_CONTEXT -> (anchor + 1).coerceAtMost(minOf(8, minOf(onlineCap, maxOf(fastCores + 1, anchor))))
                }.coerceIn(1, minOf(8, onlineCap))
                adapted to InferenceThreadPriority.DEFAULT
            }

            CpuScheduleMode.PERFORMANCE -> {
                val perfHeadroom = when {
                    workload == WorkloadClass.LONG_CONTEXT && totalCap >= 8 -> fastCores + 2
                    clusters.efficiencyCores >= 4 && fastCores <= 4 -> fastCores + 1
                    else -> fastCores
                }
                val perfBase = maxOf(perfHeadroom, validCalibrated ?: perfHeadroom)
                val capped = perfBase.coerceIn(minOf(2, onlineCap), minOf(8, onlineCap))
                capped to InferenceThreadPriority.FAVORABLE
            }

            CpuScheduleMode.THERMAL_SAFE -> {
                val safeThreads = when (thermal) {
                    ThermalSeverity.CRITICAL -> 1
                    ThermalSeverity.SEVERE -> minOf(2, onlineCap)
                    ThermalSeverity.MODERATE -> minOf(3, maxOf(2, fastCores / 2)).coerceIn(1, onlineCap)
                    else -> minOf(3, maxOf(2, onlineCap / 2)).coerceIn(1, onlineCap)
                }
                safeThreads to InferenceThreadPriority.BACKGROUND
            }
        }

        val usingManualOverride = manualThreadsOverride != null &&
            !isThermallyThrottled &&
            effectiveMode != CpuScheduleMode.THERMAL_SAFE

        val effectiveThreads = if (usingManualOverride) {
            manualThreadsOverride.coerceIn(1, minOf(12, onlineCap))
        } else {
            modeResult.first
        }
        val priorityHint = modeResult.second

        val rationale = when {
            autoTriggered -> "Auto Thermal Safe (${thermal.label}) · ${effectiveThreads}T"
            isThermallyThrottled -> "Thermal Safe (${thermal.label}) · ${effectiveThreads}T"
            usingManualOverride -> "Manual override ${effectiveThreads}T · ${effectiveMode.label} (${workload.label})"
            validCalibrated != null && (effectiveMode == CpuScheduleMode.BALANCED || effectiveMode == CpuScheduleMode.PERFORMANCE) ->
                "Calibrated ${validCalibrated}T base · ${effectiveMode.label} (${workload.label})"
            else -> "${clusters.topologyLabel} · ${effectiveMode.label} (${workload.label})"
        }

        return ThreadSchedulingDecision(
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
            effectiveThreads = effectiveThreads,
            isThermallyThrottled = isThermallyThrottled,
            workloadHint = workload,
            priorityHint = priorityHint,
            rationale = rationale
        )
    }

    fun buildCalibrationCandidates(profile: DeviceHardwareProfile): List<Int> {
        val onlineCap = profile.cpuClusters.onlineCores.coerceAtLeast(1)
        if (onlineCap == 1) return listOf(1)

        val fastCores = (profile.cpuClusters.primeCores + profile.cpuClusters.performanceCores)
        val candidates = linkedSetOf<Int>()
        candidates += minOf(2, onlineCap)
        if (fastCores in 2..minOf(8, onlineCap)) {
            candidates += fastCores
        } else if (onlineCap >= 4) {
            candidates += 4
        }
        candidates += (onlineCap / 2).coerceIn(2, minOf(6, onlineCap))
        if (fastCores in 2..7 && fastCores + 2 <= minOf(8, onlineCap)) {
            candidates += (fastCores + 2)
        }
        candidates += minOf(8, maxOf(fastCores, (onlineCap * 3) / 4)).coerceIn(2, onlineCap)
        return candidates.sorted().take(4)
    }

    fun scoreCalibrationEntry(threads: Int, result: BenchmarkResult): ThreadCalibrationEntry {
        val composite = (0.35f * result.prefillTokensPerSec) + (0.65f * result.decodeTokensPerSec)
        return ThreadCalibrationEntry(
            threads = threads,
            prefillTokPerSec = result.prefillTokensPerSec,
            decodeTokPerSec = result.decodeTokensPerSec,
            compositeScore = composite
        )
    }

    fun recommendThreadCount(
        profile: DeviceHardwareProfile,
        thermal: ThermalSeverity = profile.liveMetrics.thermalSeverity
    ): Int = resolveSchedulingDecision(
        profile = profile,
        requestedMode = CpuScheduleMode.BALANCED,
        autoThermalDownshift = true,
        thermal = thermal,
        workload = WorkloadClass.STANDARD
    ).effectiveThreads

    fun assessModelAdmission(
        file: File,
        header: GgufHeaderMetadata?,
        contextSize: Int,
        metrics: LiveDeviceMetrics = hardwareManager.currentMemoryAndThermalSnapshot(),
        plannedGpuLayers: Int = 0,
        totalLayers: Int = header?.blockCount ?: 32,
        batchSize: Int = 512
    ): AdmissionAssessment = MemoryAdmissionPlanner.evaluate(
        fileSizeBytes = file.length().coerceAtLeast(0L),
        header = header,
        contextSize = contextSize,
        batchSize = batchSize,
        plannedGpuLayers = plannedGpuLayers,
        totalLayers = totalLayers,
        metrics = metrics
    )
}
