package com.quantchat.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.quantchat.engine.core.EngineCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class ThermalSeverity(val label: String) {
    NOMINAL("Nominal"),
    LIGHT("Light"),
    MODERATE("Moderate"),
    SEVERE("Severe"),
    CRITICAL("Critical"),
    UNAVAILABLE("Unknown")
}

data class CpuClusterSummary(
    val primeCores: Int,
    val performanceCores: Int,
    val efficiencyCores: Int,
    val totalCores: Int,
    val maxFreqMhz: Int
) {
    val topologyLabel: String
        get() = when {
            primeCores > 0 && performanceCores > 0 && efficiencyCores > 0 ->
                "${totalCores}C (${primeCores}P+${performanceCores}M+${efficiencyCores}E)"
            performanceCores > 0 && efficiencyCores > 0 ->
                "${totalCores}C (${performanceCores}P+${efficiencyCores}E)"
            else -> "${totalCores} Cores"
        }
}

data class LiveDeviceMetrics(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val lowMemory: Boolean,
    val thermalSeverity: ThermalSeverity
)

data class DeviceHardwareProfile(
    val deviceModel: String,
    val socName: String,
    val supportedAbis: List<String>,
    val primaryAbi: String,
    val cpuFeatures: Set<String>,
    val cpuClusters: CpuClusterSummary,
    val hasSystemVulkan: Boolean,
    val hasSystemNnapi: Boolean,
    val activeBackendGpuSupported: Boolean,
    val activeBackendNpuSupported: Boolean,
    val gpuStatusSummary: String,
    val liveMetrics: LiveDeviceMetrics
)

class HardwareCapabilityManager(private val context: Context) {

    suspend fun probeHardware(engineCapabilities: EngineCapabilities): DeviceHardwareProfile =
        withContext(Dispatchers.IO) {
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            val primaryAbi = abis.firstOrNull() ?: "unknown"
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
            } else {
                Build.HARDWARE
            }

            val cpuFlags = readCpuFeatures()
            val clusters = readCpuClusters()
            val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            val hasNnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            val metrics = currentMemoryAndThermalSnapshot()

            val gpuSummary = if (engineCapabilities.supportsGpuOffload) {
                "Vulkan / GPU Offload Ready"
            } else {
                val hwNote = if (hasVulkan) "Device has Vulkan" else "No Vulkan hardware"
                "$hwNote · Runtime CPU-only (${engineCapabilities.gpuUnavailableReason ?: "no GPU backend"})"
            }

            DeviceHardwareProfile(
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                socName = soc,
                supportedAbis = abis,
                primaryAbi = primaryAbi,
                cpuFeatures = cpuFlags,
                cpuClusters = clusters,
                hasSystemVulkan = hasVulkan,
                hasSystemNnapi = hasNnapi,
                activeBackendGpuSupported = engineCapabilities.supportsGpuOffload,
                activeBackendNpuSupported = engineCapabilities.supportsNpuOffload,
                gpuStatusSummary = gpuSummary,
                liveMetrics = metrics
            )
        }

    fun currentMemoryAndThermalSnapshot(): LiveDeviceMetrics {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        if (am != null) {
            am.getMemoryInfo(memInfo)
        }
        val totalMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
        val availMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L)

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalSeverity.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalSeverity.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSeverity.CRITICAL
                else -> ThermalSeverity.UNAVAILABLE
            }
        } else {
            ThermalSeverity.UNAVAILABLE
        }

        return LiveDeviceMetrics(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            lowMemory = memInfo.lowMemory,
            thermalSeverity = thermal
        )
    }

    private fun readCpuFeatures(): Set<String> {
        val detected = linkedSetOf<String>()
        val raw = runCatching { File("/proc/cpuinfo").takeIf { it.canRead() }?.readText() }.getOrNull()
            ?: return detected
        val tokens = raw.lineSequence()
            .filter { line ->
                val lower = line.lowercase(Locale.US)
                lower.startsWith("features") || lower.startsWith("flags")
            }
            .flatMap { it.substringAfter(':', "").trim().split(Regex("\\s+")).asSequence() }
            .map { it.lowercase(Locale.US) }
            .toSet()

        if ("asimd" in tokens || "neon" in tokens) detected += "NEON"
        if ("asimddp" in tokens || "dotprod" in tokens) detected += "DOTPROD"
        if ("i8mm" in tokens) detected += "I8MM"
        if ("fphp" in tokens || "asimdhp" in tokens || "fp16" in tokens) detected += "FP16"
        if ("sve" in tokens) detected += "SVE"
        if ("avx2" in tokens) detected += "AVX2"
        return detected
    }

    private fun readCpuClusters(): CpuClusterSummary {
        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqsKhz = mutableListOf<Int>()
        for (cpuIdx in 0 until coreCount) {
            val freqFile = File("/sys/devices/system/cpu/cpu$cpuIdx/cpufreq/cpuinfo_max_freq")
            val freq = runCatching {
                freqFile.takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
            }.getOrNull()
            if (freq != null && freq > 0) {
                freqsKhz += freq
            }
        }
        if (freqsKhz.isEmpty()) {
            return CpuClusterSummary(
                primeCores = 0,
                performanceCores = 0,
                efficiencyCores = 0,
                totalCores = coreCount,
                maxFreqMhz = 0
            )
        }
        val distinctDesc = freqsKhz.distinct().sortedDescending()
        val maxKhz = distinctDesc.first()
        val maxMhz = maxKhz / 1000
        return when {
            distinctDesc.size >= 3 -> {
                val primeFreq = distinctDesc[0]
                val effFreq = distinctDesc.last()
                val prime = freqsKhz.count { it == primeFreq }
                val eff = freqsKhz.count { it == effFreq }
                val mid = freqsKhz.size - prime - eff
                CpuClusterSummary(prime, mid, eff, coreCount, maxMhz)
            }
            distinctDesc.size == 2 -> {
                val perfFreq = distinctDesc[0]
                val effFreq = distinctDesc[1]
                CpuClusterSummary(
                    primeCores = 0,
                    performanceCores = freqsKhz.count { it == perfFreq },
                    efficiencyCores = freqsKhz.count { it == effFreq },
                    totalCores = coreCount,
                    maxFreqMhz = maxMhz
                )
            }
            else -> CpuClusterSummary(0, freqsKhz.size, 0, coreCount, maxMhz)
        }
    }
}
