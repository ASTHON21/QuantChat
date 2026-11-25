package com.quantchat.resource

import com.quantchat.hardware.DeviceHardwareProfile
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.LiveDeviceMetrics
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
    val summary: String
)

class RuntimeResourceManager(
    private val hardwareManager: HardwareCapabilityManager
) {

    fun liveMetrics(): LiveDeviceMetrics = hardwareManager.currentMemoryAndThermalSnapshot()

    fun recommendThreadCount(
        profile: DeviceHardwareProfile,
        thermal: ThermalSeverity = profile.liveMetrics.thermalSeverity
    ): Int {
        val clusters = profile.cpuClusters
        val fastCores = clusters.primeCores + clusters.performanceCores
        val base = when {
            fastCores in 2..8 -> fastCores
            clusters.totalCores >= 8 -> 4
            clusters.totalCores >= 4 -> 3
            else -> clusters.totalCores.coerceAtLeast(1)
        }
        val thermalPenalty = when (thermal) {
            ThermalSeverity.MODERATE -> 1
            ThermalSeverity.SEVERE, ThermalSeverity.CRITICAL -> 2
            else -> 0
        }
        return (base - thermalPenalty).coerceIn(1, minOf(8, clusters.totalCores.coerceAtLeast(1)))
    }

    fun assessModelAdmission(
        file: File,
        header: GgufHeaderMetadata?,
        contextSize: Int,
        metrics: LiveDeviceMetrics = hardwareManager.currentMemoryAndThermalSnapshot()
    ): AdmissionAssessment {
        val fileSize = file.length().coerceAtLeast(0L)
        val estimatedMb = header?.estimateRuntimeMemoryMb(fileSize, contextSize)
            ?: (((fileSize * 0.95) / (1024.0 * 1024.0)).toLong() + (contextSize / 4L) + 128L)

        val avail = metrics.availableRamMb
        if (avail <= 0L) {
            return AdmissionAssessment(
                verdict = AdmissionVerdict.ALLOWED,
                estimatedMemoryMb = estimatedMb,
                availableMemoryMb = avail,
                summary = "Est. ~${estimatedMb}MB required (device RAM telemetry unavailable)."
            )
        }

        val verdict = when {
            metrics.lowMemory || avail < (estimatedMb * 0.55).toLong() -> AdmissionVerdict.BLOCKED
            avail < (estimatedMb * 1.10).toLong() -> AdmissionVerdict.WARNING
            else -> AdmissionVerdict.ALLOWED
        }

        val summary = when (verdict) {
            AdmissionVerdict.ALLOWED ->
                "RAM OK · Est. ~${estimatedMb}MB / ${avail}MB free"
            AdmissionVerdict.WARNING ->
                "Tight RAM · Est. ~${estimatedMb}MB vs ${avail}MB free"
            AdmissionVerdict.BLOCKED ->
                "Low RAM · Est. ~${estimatedMb}MB exceeds ${avail}MB free"
        }

        return AdmissionAssessment(
            verdict = verdict,
            estimatedMemoryMb = estimatedMb,
            availableMemoryMb = avail,
            summary = summary
        )
    }
}
