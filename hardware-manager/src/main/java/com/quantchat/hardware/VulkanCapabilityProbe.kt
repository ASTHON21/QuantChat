package com.quantchat.hardware

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import java.io.File
import java.util.Locale

data class SystemVulkanFeatureInfo(
    val supported: Boolean,
    val hardwareLevel: Int,
    val versionLabel: String,
    val passiveGpuName: String?,
    val passiveVendorLabel: String?
)

object VulkanCapabilityProbe {

    fun inspectSystemFeatures(context: Context): SystemVulkanFeatureInfo {
        val pm = context.packageManager
        val hasLevel = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        var rawLevel = -1
        var rawVersion = 0

        runCatching {
            val features = pm.systemAvailableFeatures
            for (feat in features) {
                when (feat.name) {
                    PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL -> rawLevel = feat.version
                    PackageManager.FEATURE_VULKAN_HARDWARE_VERSION -> rawVersion = feat.version
                }
            }
        }

        val versionLabel = if (rawVersion > 0) {
            decodeVulkanPackedVersion(rawVersion)
        } else if (hasLevel) {
            "1.0+"
        } else {
            "Unavailable"
        }

        val (gpuName, vendor) = readPassiveGpuIdentity(Build.HARDWARE)
        return SystemVulkanFeatureInfo(
            supported = hasLevel || rawVersion > 0,
            hardwareLevel = rawLevel,
            versionLabel = versionLabel,
            passiveGpuName = gpuName,
            passiveVendorLabel = vendor
        )
    }

    suspend fun probe(
        context: Context,
        nativeProbe: (suspend () -> VulkanProbeResult)? = null
    ): VulkanProbeResult {
        val sys = inspectSystemFeatures(context)

        if (!sys.supported && nativeProbe == null) {
            return VulkanProbeResult(
                status = VulkanProbeStatus.UNSUPPORTED_DEVICE,
                nativeLibraryLoaded = false,
                systemVulkanSupported = false,
                systemVulkanVersion = sys.versionLabel,
                deviceName = sys.passiveGpuName,
                vendorLabel = sys.passiveVendorLabel,
                selfTestSummary = "Device does not advertise FEATURE_VULKAN_HARDWARE_LEVEL",
                reason = "Vulkan hardware compute is unavailable on this Android device; using safe CPU mode."
            )
        }

        if (nativeProbe == null) {
            return VulkanProbeResult(
                status = VulkanProbeStatus.NATIVE_LIBRARY_MISSING,
                nativeLibraryLoaded = false,
                systemVulkanSupported = sys.supported,
                systemVulkanVersion = sys.versionLabel,
                deviceName = sys.passiveGpuName,
                vendorLabel = sys.passiveVendorLabel,
                selfTestSummary = "System Vulkan ${sys.versionLabel} detected · native lib not attached",
                reason = "Vulkan native engine bridge was not injected; running safe CPU path."
            )
        }

        val nativeResult = runCatching { nativeProbe() }.getOrElse { err ->
            VulkanProbeResult(
                status = VulkanProbeStatus.PROBE_FAILED,
                nativeLibraryLoaded = false,
                systemVulkanSupported = sys.supported,
                systemVulkanVersion = sys.versionLabel,
                deviceName = sys.passiveGpuName,
                vendorLabel = sys.passiveVendorLabel,
                selfTestSummary = "Probe error: ${err.message ?: err::class.simpleName}",
                reason = err.message ?: err::class.simpleName
            )
        }

        val effectiveStatus = if (!sys.supported && nativeResult.status == VulkanProbeStatus.NATIVE_LIBRARY_MISSING) {
            VulkanProbeStatus.UNSUPPORTED_DEVICE
        } else {
            nativeResult.status
        }

        val effectiveReason = when {
            effectiveStatus == VulkanProbeStatus.UNSUPPORTED_DEVICE && nativeResult.reason.isNullOrBlank() ->
                "Device lacks system Vulkan compute hardware."
            else -> nativeResult.reason
        }

        return nativeResult.copy(
            status = effectiveStatus,
            systemVulkanSupported = sys.supported || nativeResult.systemVulkanSupported,
            systemVulkanVersion = if (sys.versionLabel != "Unavailable") sys.versionLabel else nativeResult.systemVulkanVersion,
            deviceName = nativeResult.deviceName ?: sys.passiveGpuName,
            vendorLabel = nativeResult.vendorLabel ?: sys.passiveVendorLabel,
            reason = effectiveReason
        )
    }

    internal fun decodeVulkanPackedVersion(packed: Int): String {
        val major = (packed ushr 22) and 0x7F
        val minor = (packed ushr 12) and 0x3FF
        val patch = packed and 0xFFF
        return "$major.$minor.$patch"
    }

    internal fun readPassiveGpuIdentity(hardwareHint: String): Pair<String?, String?> {
        val kgsl = runCatching {
            File("/sys/class/kgsl/kgsl-3d0/gpu_model")
                .takeIf { it.exists() && it.canRead() }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (kgsl != null) {
            return kgsl to "Qualcomm (Adreno)"
        }

        val lowerHw = hardwareHint.lowercase(Locale.US)
        return when {
            lowerHw.contains("qcom") || lowerHw.contains("qualcomm") ->
                "Adreno GPU ($hardwareHint)" to "Qualcomm (Adreno)"
            lowerHw.contains("mt") || lowerHw.contains("mediatek") ->
                "Mali / Immortalis GPU ($hardwareHint)" to "ARM / MediaTek"
            lowerHw.contains("exynos") || lowerHw.contains("s5e") ->
                "Xclipse / Mali GPU ($hardwareHint)" to "Samsung"
            lowerHw.contains("tensor") || lowerHw.contains("gs") ->
                "Mali GPU ($hardwareHint)" to "Google Tensor"
            else -> null to null
        }
    }
}
