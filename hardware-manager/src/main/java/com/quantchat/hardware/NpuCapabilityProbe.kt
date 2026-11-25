package com.quantchat.hardware

import android.content.Context
import android.os.Build
import com.quantchat.engine.core.NpuNativeRuntimeProbe
import com.quantchat.engine.core.NpuProbeResult
import com.quantchat.engine.core.NpuProbeStatus
import com.quantchat.engine.core.NpuVendorFamily
import java.util.Locale

object NpuCapabilityProbe {

    suspend fun probe(
        context: Context,
        nativeProbe: (suspend () -> NpuNativeRuntimeProbe)? = null
    ): NpuProbeResult {
        val sdkInt = Build.VERSION.SDK_INT
        val socManufacturer = if (sdkInt >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.takeUnless { it.isBlank() || it == Build.UNKNOWN }.orEmpty()
        } else {
            ""
        }
        val socModel = if (sdkInt >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeUnless { it.isBlank() || it == Build.UNKNOWN }.orEmpty()
        } else {
            ""
        }
        val boardPlatform = readSystemPropertySafe("ro.board.platform")
            .ifBlank { readSystemPropertySafe("ro.soc.model") }
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val nativeResult = if (nativeProbe != null) {
            runCatching { nativeProbe() }.getOrElse { err ->
                val msg = err.message ?: err::class.simpleName ?: "Native probe failed"
                NpuNativeRuntimeProbe(
                    nativeProbeExecuted = false,
                    qnnError = msg,
                    cdspError = msg
                )
            }
        } else {
            null
        }

        // Context parameter kept for PackageManager/Android feature expansion while remaining Robolectric-safe.
        val hasArm64 = abis.any { it.contains("arm64", ignoreCase = true) || it.contains("armeabi", ignoreCase = true) }
        val hasNnapi = sdkInt >= Build.VERSION_CODES.O_MR1 && (hasArm64 || socModel.isNotBlank())

        return classifyNpuHardware(
            socManufacturer = socManufacturer,
            socModel = socModel,
            hardware = Build.HARDWARE.orEmpty(),
            boardPlatform = boardPlatform,
            supportedAbis = abis,
            sdkInt = sdkInt,
            nativeProbe = nativeResult,
            hasNnapi = hasNnapi
        )
    }

    fun classifyNpuHardware(
        socManufacturer: String,
        socModel: String,
        hardware: String,
        boardPlatform: String = "",
        supportedAbis: List<String> = emptyList(),
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        nativeProbe: NpuNativeRuntimeProbe? = null,
        hasNnapi: Boolean = sdkInt >= Build.VERSION_CODES.O_MR1
    ): NpuProbeResult {
        val combined = listOf(socManufacturer, socModel, hardware, boardPlatform)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .uppercase(Locale.US)

        val isEmulatorOrX86 = supportedAbis.isNotEmpty() &&
            supportedAbis.none { it.contains("arm", ignoreCase = true) } ||
            combined.contains("RANCHU") ||
            combined.contains("GOLDFISH") ||
            combined.contains("EMULATOR")

        val chipsetLabel = listOf(socManufacturer, socModel.ifBlank { boardPlatform.ifBlank { hardware } })
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { hardware.ifBlank { "Unknown SoC" } }

        val nnapiDeprecated = sdkInt >= 35
        val qnnReady = nativeProbe?.qnnHtpAccessible == true
        val cdspReady = nativeProbe?.cdspRpcAccessible == true

        val vendorFamily = when {
            isEmulatorOrX86 -> NpuVendorFamily.NONE
            isQualcomm(combined) -> NpuVendorFamily.QUALCOMM_HEXAGON
            isMediaTek(combined) -> NpuVendorFamily.MEDIATEK_APU
            isGoogleTensor(combined) -> NpuVendorFamily.GOOGLE_TENSOR_TPU
            isSamsungExynos(combined) -> NpuVendorFamily.SAMSUNG_EXYNOS_NPU
            hasNnapi -> NpuVendorFamily.GENERIC_NNAPI
            else -> NpuVendorFamily.NONE
        }

        val hexagonHint = if (vendorFamily == NpuVendorFamily.QUALCOMM_HEXAGON) {
            resolveHexagonHint(combined)
        } else {
            null
        }

        val precisionHints = when (vendorFamily) {
            NpuVendorFamily.QUALCOMM_HEXAGON -> listOf("INT8", "INT4", "FP16")
            NpuVendorFamily.MEDIATEK_APU -> listOf("INT8", "INT4", "FP16")
            NpuVendorFamily.GOOGLE_TENSOR_TPU -> listOf("INT8", "FP16")
            NpuVendorFamily.SAMSUNG_EXYNOS_NPU -> listOf("INT8", "FP16")
            NpuVendorFamily.GENERIC_NNAPI -> listOf("INT8", "FP16")
            NpuVendorFamily.NONE -> emptyList()
        }

        val status = when {
            vendorFamily == NpuVendorFamily.NONE -> NpuProbeStatus.UNSUPPORTED_DEVICE
            vendorFamily == NpuVendorFamily.QUALCOMM_HEXAGON && qnnReady -> NpuProbeStatus.RUNTIME_READY
            vendorFamily == NpuVendorFamily.GENERIC_NNAPI -> NpuProbeStatus.NNAPI_ONLY_LEGACY
            else -> NpuProbeStatus.HARDWARE_DETECTED_RUNTIME_UNBUNDLED
        }

        val summary = when (status) {
            NpuProbeStatus.RUNTIME_READY ->
                "${vendorFamily.label} (${hexagonHint ?: chipsetLabel}) · QNN HTP Accessible"
            NpuProbeStatus.HARDWARE_DETECTED_RUNTIME_UNBUNDLED -> {
                val hintSuffix = hexagonHint?.let { " ($it)" } ?: ""
                "${vendorFamily.label}$hintSuffix · ${vendorFamily.stackName} Not Bundled"
            }
            NpuProbeStatus.NNAPI_ONLY_LEGACY ->
                if (nnapiDeprecated) "NNAPI Legacy Only (API 35+ Deprecated)" else "Android NNAPI Available (No Vendor SDK)"
            NpuProbeStatus.UNSUPPORTED_DEVICE ->
                "No Dedicated NPU Detected ($chipsetLabel)"
            NpuProbeStatus.PROBE_FAILED ->
                "NPU Probe Failed"
        }

        val reason = when (status) {
            NpuProbeStatus.RUNTIME_READY ->
                if (cdspReady) {
                    "QNN HTP & FastRPC runtime verified. Note: standard .gguf files still require Vulkan GPU or CPU."
                } else {
                    "QNN HTP runtime loaded; vendor FastRPC namespace restricted. Standard .gguf models use Vulkan GPU or CPU."
                }
            NpuProbeStatus.HARDWARE_DETECTED_RUNTIME_UNBUNDLED -> {
                val runtimeDetail = when {
                    vendorFamily == NpuVendorFamily.QUALCOMM_HEXAGON && nativeProbe?.nativeProbeExecuted == true ->
                        "Non-root Android linker namespace isolates /vendor QNN libs (${if (cdspReady) "cdsprpc visible" else "QNN SDK not bundled"})."
                    else ->
                        "${vendorFamily.stackName} SDK is not bundled in QuantChat."
                }
                "$runtimeDetail Standard .gguf models route automatically to Vulkan GPU or CPU."
            }
            NpuProbeStatus.NNAPI_ONLY_LEGACY ->
                if (nnapiDeprecated) {
                    "Android 15+ (API 35) deprecated NNAPI; uncompiled GGUF weights execute via Vulkan GPU / CPU."
                } else {
                    "Device exposes generic Android NNAPI without vendor LLM NPU libraries; GGUF models use Vulkan GPU / CPU."
                }
            NpuProbeStatus.UNSUPPORTED_DEVICE ->
                "Emulator / non-NPU platform ($chipsetLabel). QuantChat routes all GGUF inference through Vulkan GPU or CPU."
            NpuProbeStatus.PROBE_FAILED ->
                nativeProbe?.qnnError ?: "Unable to query NPU device identity."
        }

        return NpuProbeResult(
            status = status,
            vendorFamily = vendorFamily,
            chipsetLabel = chipsetLabel,
            socManufacturer = socManufacturer,
            hexagonGenerationHint = hexagonHint,
            supportsNnapi = hasNnapi && !isEmulatorOrX86,
            nnapiDeprecatedOnOs = nnapiDeprecated,
            qnnRuntimePresent = qnnReady,
            cdspRpcAccessible = cdspReady,
            supportedPrecisionHints = precisionHints,
            summary = summary,
            reason = reason
        )
    }

    private fun isQualcomm(upper: String): Boolean =
        upper.contains("QUALCOMM") ||
            upper.contains("QCOM") ||
            upper.contains("SNAPDRAGON") ||
            upper.contains("HEXAGON") ||
            Regex("\\b(SM|QCS|QCM|SDM|MSM)\\d{3,4}").containsMatchIn(upper) ||
            Regex("\\b(KALAMA|PINEAPPLE|SUN|LANAI|TARO|LAHAINA|KONA|WAIPIO|CLIFFS)\\b")
                .containsMatchIn(upper)

    private fun isMediaTek(upper: String): Boolean =
        upper.contains("MEDIATEK") ||
            upper.contains("MTK") ||
            upper.contains("DIMENSITY") ||
            Regex("\\bMT6\\d{3}").containsMatchIn(upper)

    private fun isGoogleTensor(upper: String): Boolean =
        upper.contains("TENSOR") ||
            upper.contains("GOOGLE") ||
            upper.contains("ZUMA") ||
            upper.contains("CLOUDRIPPER") ||
            upper.contains("WHITECHAPEL") ||
            Regex("\\bGS\\d{3}").containsMatchIn(upper)

    private fun isSamsungExynos(upper: String): Boolean =
        upper.contains("EXYNOS") ||
            upper.contains("SAMSUNG") && upper.contains("S5E") ||
            upper.contains("S5E") ||
            upper.contains("ERD")

    private fun resolveHexagonHint(upper: String): String = when {
        upper.contains("SM8750") || Regex("\\bSUN\\b").containsMatchIn(upper) -> "Hexagon NPU (8 Elite / v79)"
        upper.contains("SM8650") || upper.contains("PINEAPPLE") -> "Hexagon HTP v75 (8 Gen 3)"
        upper.contains("SM8550") || upper.contains("KALAMA") -> "Hexagon HTP v73 (8 Gen 2)"
        upper.contains("SM8450") || upper.contains("SM8475") || upper.contains("TARO") -> "Hexagon HTP v69 (8 Gen 1)"
        upper.contains("SM8635") || upper.contains("CLIFFS") -> "Hexagon HTP v73+ (8s Gen 3)"
        Regex("\\bSM7\\d{3}").containsMatchIn(upper) -> "Hexagon HTP (Snapdragon 7-Series)"
        Regex("\\bSM6\\d{3}").containsMatchIn(upper) -> "Hexagon DSP (Snapdragon 6-Series)"
        else -> "Qualcomm Hexagon HTP"
    }

    private fun readSystemPropertySafe(key: String): String = runCatching {
        val spClass = Class.forName("android.os.SystemProperties")
        val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
        (getMethod.invoke(null, key, "") as? String).orEmpty().trim()
    }.getOrDefault("")
}
