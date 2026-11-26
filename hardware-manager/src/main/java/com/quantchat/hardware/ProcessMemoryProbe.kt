package com.quantchat.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import java.io.File
import java.util.Locale

data class ProcessMemoryTelemetry(
    val processTotalPssMb: Long = 0L,
    val processVmRssMb: Long = 0L,
    val nativeHeapAllocatedMb: Long = 0L,
    val dalvikPssMb: Long = 0L,
    val memoryClassMb: Int = 0,
    val largeMemoryClassMb: Int = 0,
    val systemThresholdMb: Long = 0L,
    val systemAvailMemMb: Long = 0L,
    val systemTotalMemMb: Long = 0L,
    val lowMemoryFlag: Boolean = false
) {
    val effectiveProcessFootprintMb: Long
        get() = maxOf(processVmRssMb, processTotalPssMb, nativeHeapAllocatedMb)
}

object ProcessMemoryProbe {

    fun sample(
        context: Context,
        activityManager: ActivityManager? = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager,
        memInfo: ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }
    ): ProcessMemoryTelemetry {
        val availMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L)
        val totalMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
        val thresholdMb = (memInfo.threshold / (1024L * 1024L)).coerceAtLeast(0L)

        val memClass = runCatching { activityManager?.memoryClass ?: 0 }.getOrDefault(0)
        val largeMemClass = runCatching { activityManager?.largeMemoryClass ?: 0 }.getOrDefault(0)

        var totalPssMb = 0L
        var dalvikPssMb = 0L
        if (activityManager != null) {
            val procInfos = runCatching {
                activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            }.getOrNull()
            val procMem = procInfos?.firstOrNull()
            if (procMem != null) {
                totalPssMb = (procMem.totalPss.toLong() / 1024L).coerceAtLeast(0L)
                dalvikPssMb = (procMem.dalvikPss.toLong() / 1024L).coerceAtLeast(0L)
            }
        }
        if (totalPssMb == 0L) {
            val debugPssKb = runCatching { Debug.getPss() }.getOrDefault(0L)
            totalPssMb = (debugPssKb / 1024L).coerceAtLeast(0L)
        }

        val nativeHeapMb = runCatching {
            Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        }.getOrDefault(0L).coerceAtLeast(0L)

        val vmRssMb = readProcVmRssMb()

        return ProcessMemoryTelemetry(
            processTotalPssMb = totalPssMb,
            processVmRssMb = vmRssMb,
            nativeHeapAllocatedMb = nativeHeapMb,
            dalvikPssMb = dalvikPssMb,
            memoryClassMb = memClass,
            largeMemoryClassMb = largeMemClass,
            systemThresholdMb = thresholdMb,
            systemAvailMemMb = availMb,
            systemTotalMemMb = totalMb,
            lowMemoryFlag = memInfo.lowMemory
        )
    }

    fun parseVmRssMbFromProcStatus(statusContent: String?): Long {
        if (statusContent.isNullOrBlank()) return 0L
        val line = statusContent.lineSequence()
            .firstOrNull { it.trimStart().startsWith("VmRSS:", ignoreCase = true) }
            ?: return 0L
        val kb = Regex("(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return 0L
        return (kb / 1024L).coerceAtLeast(0L)
    }

    private fun readProcVmRssMb(): Long {
        val raw = runCatching {
            File("/proc/self/status").takeIf { it.canRead() }?.readText()
        }.getOrNull()
        return parseVmRssMbFromProcStatus(raw)
    }
}
