package com.quantchat.engine.vulkan

import com.quantchat.engine.core.NpuNativeRuntimeProbe
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus

fun interface VulkanTokenCallback {
    fun onToken(chunk: String)
}

object VulkanNativeBridge {

    private const val LIB_NAME = "quantchat_vulkan"

    @Volatile
    private var loadAttempted: Boolean = false

    @Volatile
    private var libraryLoaded: Boolean = false

    @Volatile
    private var loadError: String? = null

    private val loadLock = Any()

    fun isLibraryAvailable(): Boolean {
        if (loadAttempted) return libraryLoaded
        synchronized(loadLock) {
            if (!loadAttempted) {
                loadAttempted = true
                try {
                    System.loadLibrary(LIB_NAME)
                    libraryLoaded = true
                    loadError = null
                } catch (t: Throwable) {
                    libraryLoaded = false
                    loadError = "${t::class.simpleName}: ${t.message ?: "lib$LIB_NAME.so not bundled for this ABI"}"
                }
            }
            return libraryLoaded
        }
    }

    fun lastLoadError(): String? = loadError

    fun probeVulkanCapabilities(): VulkanProbeResult {
        if (!isLibraryAvailable()) {
            return VulkanProbeResult(
                status = VulkanProbeStatus.NATIVE_LIBRARY_MISSING,
                nativeLibraryLoaded = false,
                selfTestSummary = "Native Vulkan binary not bundled in this build",
                reason = loadError ?: "lib$LIB_NAME.so missing; using safe CPU inference."
            )
        }
        return runCatching {
            parseProbeResponse(nativeProbeVulkan())
        }.getOrElse { err ->
            VulkanProbeResult(
                status = VulkanProbeStatus.PROBE_FAILED,
                nativeLibraryLoaded = true,
                selfTestSummary = "JNI probe exception: ${err.message ?: err::class.simpleName}",
                reason = err.message ?: err::class.simpleName
            )
        }
    }

    fun probeNpuRuntime(): NpuNativeRuntimeProbe {
        if (!isLibraryAvailable()) {
            return NpuNativeRuntimeProbe(
                nativeProbeExecuted = false,
                qnnHtpAccessible = false,
                cdspRpcAccessible = false,
                qnnError = loadError ?: "Native bridge not bundled",
                cdspError = loadError ?: "Native bridge not bundled"
            )
        }
        return runCatching {
            parseNpuProbeResponse(nativeProbeNpuRuntime())
        }.getOrElse { err ->
            val msg = err.message ?: err::class.simpleName ?: "JNI probe exception"
            NpuNativeRuntimeProbe(
                nativeProbeExecuted = false,
                qnnError = msg,
                cdspError = msg
            )
        }
    }

    fun parseNpuProbeResponse(raw: String): NpuNativeRuntimeProbe {
        val map = raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
        return NpuNativeRuntimeProbe(
            nativeProbeExecuted = true,
            qnnHtpAccessible = map["qnnHtpAccessible"] == "true",
            cdspRpcAccessible = map["cdspRpcAccessible"] == "true",
            qnnError = map["qnnError"]?.takeIf { it.isNotBlank() },
            cdspError = map["cdspError"]?.takeIf { it.isNotBlank() }
        )
    }

    fun parseProbeResponse(raw: String): VulkanProbeResult {
        val map = raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val status = when (map["status"]) {
            "AVAILABLE" -> VulkanProbeStatus.AVAILABLE
            "UNSUPPORTED_DEVICE" -> VulkanProbeStatus.UNSUPPORTED_DEVICE
            "DRIVER_CAPABILITY_MISSING" -> VulkanProbeStatus.DRIVER_CAPABILITY_MISSING
            "NATIVE_LIBRARY_MISSING" -> VulkanProbeStatus.NATIVE_LIBRARY_MISSING
            else -> VulkanProbeStatus.PROBE_FAILED
        }
        val apiVer = map["apiVersion"]?.takeIf { it.isNotBlank() } ?: "Unavailable"
        return VulkanProbeResult(
            status = status,
            nativeLibraryLoaded = true,
            systemVulkanSupported = status != VulkanProbeStatus.UNSUPPORTED_DEVICE,
            systemVulkanVersion = apiVer,
            deviceName = map["deviceName"]?.takeIf { it.isNotBlank() },
            vendorLabel = map["vendorLabel"]?.takeIf { it.isNotBlank() },
            driverVersionLabel = map["driverVersion"]?.takeIf { it.isNotBlank() },
            vulkanApiVersionLabel = apiVer,
            supports16BitStorage = map["supports16BitStorage"] == "true",
            supportsFp16Compute = map["supportsFp16Compute"] == "true",
            supportsCooperativeMatrix = map["supportsCoopMatrix"] == "true",
            deviceLocalHeapMb = map["deviceLocalMb"]?.toLongOrNull() ?: 0L,
            hostVisibleHeapMb = map["hostVisibleMb"]?.toLongOrNull() ?: 0L,
            recommendedMaxOffloadMb = map["recommendedOffloadMb"]?.toLongOrNull() ?: 0L,
            bufferTransferSelfTestPassed = map["bufferSelfTest"] == "true",
            operatorSelfTestPassed = map["operatorSelfTest"] == "true",
            selfTestSummary = map["summary"]?.takeIf { it.isNotBlank() } ?: status.label,
            reason = map["reason"]?.takeIf { it.isNotBlank() }
        )
    }

    @JvmStatic
    external fun nativeProbeVulkan(): String

    @JvmStatic
    external fun nativeProbeNpuRuntime(): String

    @JvmStatic
    external fun nativeLoadModelFd(
        fd: Int,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean
    ): Long

    @JvmStatic
    external fun nativeUnload(handlePtr: Long)

    @JvmStatic
    external fun nativeCancel(handlePtr: Long)

    @JvmStatic
    external fun nativeFormatChat(
        handlePtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        templateOverride: String
    ): String?

    @JvmStatic
    external fun nativeTokenize(handlePtr: Long, text: String): IntArray

    @JvmStatic
    external fun nativeDetokenize(handlePtr: Long, tokens: IntArray): String

    @JvmStatic
    external fun nativeLaunchCompletion(
        handlePtr: Long,
        prompt: String,
        nPredict: Int,
        nThreads: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        callback: VulkanTokenCallback
    ): Int

    @JvmStatic
    external fun nativeSaveSession(handlePtr: Long, path: String): Boolean

    @JvmStatic
    external fun nativeLoadSession(handlePtr: Long, path: String): Int
}
