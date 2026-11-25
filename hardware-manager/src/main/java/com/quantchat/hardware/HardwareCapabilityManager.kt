package com.quantchat.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val maxFreqMhz: Int,
    val onlineCores: Int = totalCores,
    val cores: List<CpuCoreInfo> = emptyList(),
    val clusters: List<CpuClusterGroup> = emptyList(),
    val capacitySupported: Boolean = false,
    val sysfsRestricted: Boolean = false
) {
    val topologyLabel: String
        get() = when {
            primeCores > 0 && performanceCores > 0 && efficiencyCores > 0 ->
                "${totalCores}C (${primeCores}P+${performanceCores}B+${efficiencyCores}E)"
            performanceCores > 0 && efficiencyCores > 0 ->
                "${totalCores}C (${performanceCores}B+${efficiencyCores}E)"
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
    val liveMetrics: LiveDeviceMetrics,
    val vulkanCapabilities: VulkanProbeResult? = null,
    val openClStatusNote: String = "OpenCL deferred to Phase 2 compatibility track"
) {
    val deviceTopologySignature: String
        get() = "$socName|${cpuClusters.totalCores}|${cpuClusters.primeCores}-${cpuClusters.performanceCores}-${cpuClusters.efficiencyCores}"
}

class HardwareCapabilityManager(
    private val context: Context,
    private val vulkanNativeProbe: (suspend () -> VulkanProbeResult)? = null
) {

    private val _thermalSeverityFlow = MutableStateFlow(readCurrentThermalStatus())
    val thermalSeverityFlow: StateFlow<ThermalSeverity> = _thermalSeverityFlow.asStateFlow()

    private val thermalListenerLock = Any()
    private var registeredThermalListener: Any? = null

    fun startThermalMonitoring() {
        _thermalSeverityFlow.value = readCurrentThermalStatus()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        synchronized(thermalListenerLock) {
            if (registeredThermalListener != null) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                _thermalSeverityFlow.value = mapPowerThermalStatus(status)
            }
            val registered = runCatching {
                pm.addThermalStatusListener(context.mainExecutor, listener)
                true
            }.getOrElse {
                runCatching {
                    pm.addThermalStatusListener(listener)
                    true
                }.getOrDefault(false)
            }
            if (registered) {
                registeredThermalListener = listener
            }
        }
    }

    fun stopThermalMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        synchronized(thermalListenerLock) {
            val listener = registeredThermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                runCatching { pm.removeThermalStatusListener(listener) }
            }
            registeredThermalListener = null
        }
    }

    suspend fun probeVulkanCapabilities(): VulkanProbeResult = withContext(Dispatchers.IO) {
        VulkanCapabilityProbe.probe(context, vulkanNativeProbe)
    }

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
            val clusters = CpuTopologyParser.probeFromSysfs()
            val vulkanProbe = VulkanCapabilityProbe.probe(context, vulkanNativeProbe)
            val hasVulkan = vulkanProbe.systemVulkanSupported ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            val hasNnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            val metrics = currentMemoryAndThermalSnapshot()

            val gpuSummary = when {
                vulkanProbe.isUsableForInference ->
                    "${vulkanProbe.deviceName ?: "Vulkan GPU"} · ${vulkanProbe.vulkanApiVersionLabel ?: vulkanProbe.systemVulkanVersion} Ready"
                vulkanProbe.status == VulkanProbeStatus.NATIVE_LIBRARY_MISSING && hasVulkan ->
                    "System Vulkan ${vulkanProbe.systemVulkanVersion} · Native Vulkan lib not bundled (CPU Safe)"
                else -> {
                    val reason = vulkanProbe.reason ?: engineCapabilities.gpuUnavailableReason ?: vulkanProbe.status.label
                    "$reason · CPU Safe"
                }
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
                activeBackendGpuSupported = vulkanProbe.isUsableForInference || engineCapabilities.supportsGpuOffload,
                activeBackendNpuSupported = engineCapabilities.supportsNpuOffload,
                gpuStatusSummary = gpuSummary,
                liveMetrics = metrics,
                vulkanCapabilities = vulkanProbe
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

        val thermal = readCurrentThermalStatus()
        _thermalSeverityFlow.value = thermal

        return LiveDeviceMetrics(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            lowMemory = memInfo.lowMemory,
            thermalSeverity = thermal
        )
    }

    private fun readCurrentThermalStatus(): ThermalSeverity {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalSeverity.UNAVAILABLE
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return ThermalSeverity.UNAVAILABLE
        val status = runCatching { pm.currentThermalStatus }.getOrNull() ?: return ThermalSeverity.UNAVAILABLE
        return mapPowerThermalStatus(status)
    }

    private fun mapPowerThermalStatus(status: Int): ThermalSeverity = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalSeverity.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalSeverity.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSeverity.CRITICAL
        else -> ThermalSeverity.UNAVAILABLE
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
}
