package com.quantchat.hardware

import java.io.File

enum class CpuCoreTier(val label: String) {
    PRIME("Prime"),
    BIG("Big"),
    LITTLE("LITTLE"),
    UNKNOWN("Standard")
}

data class CpuCoreInfo(
    val coreId: Int,
    val clusterId: Int?,
    val online: Boolean,
    val capacity: Int?,
    val maxFreqKhz: Int?,
    val currentFreqKhz: Int?,
    val tier: CpuCoreTier
)

data class CpuClusterGroup(
    val clusterId: Int,
    val tier: CpuCoreTier,
    val coreIds: List<Int>,
    val maxFreqMhz: Int,
    val capacity: Int?
)

data class RawCpuCoreSample(
    val coreId: Int,
    val clusterId: Int?,
    val online: Boolean,
    val capacity: Int?,
    val maxFreqKhz: Int?,
    val currentFreqKhz: Int?,
    val readRestricted: Boolean = false
)

object CpuTopologyParser {

    private val CPU_DIR_REGEX = Regex("^cpu(\\d+)$")

    fun parseCpuRangeMask(mask: String?): Set<Int> {
        if (mask.isNullOrBlank()) return emptySet()
        val result = sortedSetOf<Int>()
        mask.trim().split(',').forEach { token ->
            val part = token.trim()
            if (part.isEmpty()) return@forEach
            val dashIdx = part.indexOf('-')
            if (dashIdx > 0) {
                val start = part.substring(0, dashIdx).trim().toIntOrNull()
                val end = part.substring(dashIdx + 1).trim().toIntOrNull()
                if (start != null && end != null && end >= start && (end - start) <= 256) {
                    for (i in start..end) {
                        result += i
                    }
                }
            } else {
                part.toIntOrNull()?.let { result += it }
            }
        }
        return result
    }

    fun probeFromSysfs(
        cpuRoot: File = File("/sys/devices/system/cpu"),
        fallbackCoreCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ): CpuClusterSummary {
        var anyRestricted = false
        val onlineMaskFile = File(cpuRoot, "online")
        val onlineMaskText = readSmallIntOrText(onlineMaskFile) { anyRestricted = true }
        val onlineMask = parseCpuRangeMask(onlineMaskText)

        val discoveredIndices = runCatching {
            cpuRoot.listFiles()
                ?.mapNotNull { entry ->
                    if (!entry.isDirectory) return@mapNotNull null
                    CPU_DIR_REGEX.matchEntire(entry.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                ?.sorted()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: (0 until fallbackCoreCount.coerceAtLeast(1)).toList()

        val samples = discoveredIndices.map { cpuIdx ->
            val cpuDir = File(cpuRoot, "cpu$cpuIdx")
            val onlineFile = File(cpuDir, "online")
            val isOnline = when {
                cpuIdx in onlineMask -> true
                onlineMask.isNotEmpty() -> false
                onlineFile.exists() -> {
                    val v = readSmallIntOrText(onlineFile) { anyRestricted = true }?.toIntOrNull()
                    v == null || v != 0
                }
                else -> cpuIdx < fallbackCoreCount
            }

            val cap = readSmallIntOrText(File(cpuDir, "cpu_capacity")) { anyRestricted = true }
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

            val maxFreq = (
                readSmallIntOrText(File(cpuDir, "cpufreq/cpuinfo_max_freq")) { anyRestricted = true }
                    ?: readSmallIntOrText(File(cpuDir, "cpufreq/scaling_max_freq")) { anyRestricted = true }
                )?.toIntOrNull()?.takeIf { it > 0 }

            val curFreq = readSmallIntOrText(File(cpuDir, "cpufreq/scaling_cur_freq")) { anyRestricted = true }
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

            val clusterId = (
                readSmallIntOrText(File(cpuDir, "topology/cluster_id")) { anyRestricted = true }
                    ?: readSmallIntOrText(File(cpuDir, "topology/physical_package_id")) { anyRestricted = true }
                )?.toIntOrNull()

            RawCpuCoreSample(
                coreId = cpuIdx,
                clusterId = clusterId,
                online = isOnline,
                capacity = cap,
                maxFreqKhz = maxFreq,
                currentFreqKhz = curFreq,
                readRestricted = anyRestricted
            )
        }

        return classifyAndSummarize(
            samples = samples,
            fallbackTotalCores = fallbackCoreCount,
            sysfsRestrictedHint = anyRestricted
        )
    }

    fun classifyAndSummarize(
        samples: List<RawCpuCoreSample>,
        fallbackTotalCores: Int = samples.size.coerceAtLeast(1),
        sysfsRestrictedHint: Boolean = false
    ): CpuClusterSummary {
        if (samples.isEmpty()) {
            return CpuClusterSummary(
                primeCores = 0,
                performanceCores = 0,
                efficiencyCores = 0,
                totalCores = fallbackTotalCores.coerceAtLeast(1),
                maxFreqMhz = 0,
                onlineCores = fallbackTotalCores.coerceAtLeast(1),
                cores = emptyList(),
                clusters = emptyList(),
                capacitySupported = false,
                sysfsRestricted = true
            )
        }

        val distinctCapacities = samples.mapNotNull { it.capacity }.filter { it > 0 }.distinct().sortedDescending()
        val distinctFreqs = samples.mapNotNull { it.maxFreqKhz }.filter { it > 0 }.distinct().sortedDescending()
        val capacitySupported = distinctCapacities.size >= 2
        val anyRestricted = sysfsRestrictedHint || samples.any { it.readRestricted } ||
            (!capacitySupported && distinctFreqs.isEmpty())

        val classifiedCores = samples.map { sample ->
            val tier = when {
                capacitySupported && sample.capacity != null -> {
                    classifyValueTier(sample.capacity, distinctCapacities)
                }
                distinctFreqs.size >= 2 && sample.maxFreqKhz != null -> {
                    classifyValueTier(sample.maxFreqKhz, distinctFreqs)
                }
                distinctFreqs.size == 1 || distinctCapacities.size == 1 -> {
                    CpuCoreTier.BIG
                }
                else -> CpuCoreTier.UNKNOWN
            }
            CpuCoreInfo(
                coreId = sample.coreId,
                clusterId = sample.clusterId,
                online = sample.online,
                capacity = sample.capacity,
                maxFreqKhz = sample.maxFreqKhz,
                currentFreqKhz = sample.currentFreqKhz,
                tier = tier
            )
        }

        val groups = classifiedCores
            .groupBy { core ->
                Triple(
                    core.tier,
                    core.maxFreqKhz?.div(1000) ?: 0,
                    core.capacity ?: -1
                )
            }
            .entries
            .mapIndexed { idx, (key, members) ->
                val rawClusterId = members.mapNotNull { it.clusterId }.distinct()
                    .singleOrNull() ?: idx
                CpuClusterGroup(
                    clusterId = rawClusterId,
                    tier = key.first,
                    coreIds = members.map { it.coreId }.sorted(),
                    maxFreqMhz = key.second,
                    capacity = key.third.takeIf { it > 0 }
                )
            }
            .sortedWith(
                compareBy<CpuClusterGroup> { tierSortOrder(it.tier) }
                    .thenByDescending { it.capacity ?: 0 }
                    .thenByDescending { it.maxFreqMhz }
            )

        val primeCount = classifiedCores.count { it.tier == CpuCoreTier.PRIME }
        val bigCount = classifiedCores.count { it.tier == CpuCoreTier.BIG }
        val littleCount = classifiedCores.count { it.tier == CpuCoreTier.LITTLE }
        val totalCores = maxOf(classifiedCores.size, fallbackTotalCores).coerceAtLeast(1)
        val onlineCores = classifiedCores.count { it.online }.coerceAtLeast(1)
        val maxMhz = (distinctFreqs.firstOrNull() ?: 0) / 1000

        return CpuClusterSummary(
            primeCores = primeCount,
            performanceCores = bigCount,
            efficiencyCores = littleCount,
            totalCores = totalCores,
            maxFreqMhz = maxMhz,
            onlineCores = onlineCores,
            cores = classifiedCores,
            clusters = groups,
            capacitySupported = capacitySupported,
            sysfsRestricted = anyRestricted
        )
    }

    private fun classifyValueTier(value: Int, distinctDesc: List<Int>): CpuCoreTier = when {
        distinctDesc.size >= 3 -> when (value) {
            distinctDesc.first() -> CpuCoreTier.PRIME
            distinctDesc.last() -> CpuCoreTier.LITTLE
            else -> CpuCoreTier.BIG
        }
        distinctDesc.size == 2 -> when (value) {
            distinctDesc.first() -> CpuCoreTier.BIG
            else -> CpuCoreTier.LITTLE
        }
        else -> CpuCoreTier.BIG
    }

    private fun tierSortOrder(tier: CpuCoreTier): Int = when (tier) {
        CpuCoreTier.PRIME -> 0
        CpuCoreTier.BIG -> 1
        CpuCoreTier.LITTLE -> 2
        CpuCoreTier.UNKNOWN -> 3
    }

    private inline fun readSmallIntOrText(file: File, onRestricted: () -> Unit): String? {
        return try {
            if (!file.exists()) return null
            if (!file.canRead()) {
                onRestricted()
                return null
            }
            file.readText().trim().takeIf { it.isNotEmpty() }
        } catch (_: SecurityException) {
            onRestricted()
            null
        } catch (_: Exception) {
            null
        }
    }
}
