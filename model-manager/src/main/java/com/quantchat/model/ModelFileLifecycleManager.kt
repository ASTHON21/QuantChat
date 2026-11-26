package com.quantchat.model

import android.os.StatFs
import com.quantchat.engine.core.MmapLifecycleRegistry
import com.quantchat.engine.core.SafeDeleteCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

enum class ModelIntegrityVerdict(val label: String) {
    VERIFIED_SHA256("SHA-256 + GGUF Verified"),
    VERIFIED_GGUF("GGUF Structure Verified"),
    CORRUPT_HEADER("Corrupt GGUF Header"),
    SIZE_MISMATCH("Incomplete / Size Mismatch"),
    SHA256_MISMATCH("SHA-256 Mismatch"),
    FILE_MISSING("File Missing")
}

data class ModelVerificationResult(
    val isValid: Boolean,
    val verdict: ModelIntegrityVerdict,
    val fileSizeBytes: Long,
    val expectedSizeBytes: Long? = null,
    val actualSha256: String? = null,
    val expectedSha256: String? = null,
    val headerMetadata: GgufHeaderMetadata? = null,
    val failureReason: String? = null,
    val verifiedAtMs: Long = System.currentTimeMillis()
)

data class DownloadCheckpointManifest(
    val repoId: String,
    val rfilename: String,
    val modelId: String,
    val modelDisplayName: String,
    val downloadUrl: String,
    val expectedTotalBytes: Long,
    val expectedSha256: String? = null,
    val etag: String? = null,
    val bytesOnDisk: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class VerifiedArtifactManifest(
    val fileName: String,
    val sizeBytes: Long,
    val expectedSizeBytes: Long?,
    val actualSha256: String?,
    val expectedSha256: String?,
    val verdict: ModelIntegrityVerdict,
    val architecture: String? = null,
    val verifiedAtMs: Long = System.currentTimeMillis()
)

data class StorageSpaceSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val modelsUsedBytes: Long,
    val partialUsedBytes: Long,
    val lowStorageThresholdBytes: Long = DEFAULT_LOW_STORAGE_THRESHOLD_BYTES
) {
    val isLowStorage: Boolean
        get() = availableBytes in 0L until lowStorageThresholdBytes

    companion object {
        const val DEFAULT_LOW_STORAGE_THRESHOLD_BYTES = 1024L * 1024L * 1024L // 1 GiB
    }
}

data class StorageCheckResult(
    val hasEnoughSpace: Boolean,
    val availableBytes: Long,
    val requiredAdditionalBytes: Long,
    val safetyBufferBytes: Long,
    val totalRequiredWithBufferBytes: Long,
    val deficitBytes: Long
)

sealed interface SafeDeleteOutcome {
    data object Deleted : SafeDeleteOutcome
    data object NotFound : SafeDeleteOutcome
    data class BlockedActiveMapping(
        val activeLeaseCount: Int,
        val backendLabels: List<String>
    ) : SafeDeleteOutcome
    data object BlockedWriteLock : SafeDeleteOutcome
    data class Failed(val reason: String) : SafeDeleteOutcome
}

object ModelFileVerifier {

    private const val GGUF_MAGIC_LE = 0x46554747 // "GGUF" little-endian
    private const val MIN_HEADER_BYTES = 24L
    const val RECOMMENDED_MIN_REAL_GGUF_BYTES = 1024L

    suspend fun verifyStructure(
        file: File,
        expectedSizeBytes: Long? = null,
        minFileSizeBytes: Long = MIN_HEADER_BYTES
    ): ModelVerificationResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) {
            return@withContext ModelVerificationResult(
                isValid = false,
                verdict = ModelIntegrityVerdict.FILE_MISSING,
                fileSizeBytes = 0L,
                expectedSizeBytes = expectedSizeBytes,
                failureReason = "Model file does not exist on device storage."
            )
        }

        val actualSize = file.length()
        if (actualSize < minFileSizeBytes) {
            return@withContext ModelVerificationResult(
                isValid = false,
                verdict = ModelIntegrityVerdict.SIZE_MISMATCH,
                fileSizeBytes = actualSize,
                expectedSizeBytes = expectedSizeBytes,
                failureReason = "File is truncated (${actualSize}B < minimum ${minFileSizeBytes}B)."
            )
        }

        if (expectedSizeBytes != null && expectedSizeBytes > 0L && actualSize != expectedSizeBytes) {
            return@withContext ModelVerificationResult(
                isValid = false,
                verdict = ModelIntegrityVerdict.SIZE_MISMATCH,
                fileSizeBytes = actualSize,
                expectedSizeBytes = expectedSizeBytes,
                failureReason = "File size mismatch: found ${actualSize}B, expected ${expectedSizeBytes}B."
            )
        }

        val headerCheck = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(24)
                raf.readFully(buf)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val magic = bb.int
                val version = bb.int
                val tensorCount = bb.long
                val kvCount = bb.long
                Triple(magic, version, tensorCount to kvCount)
            }
        }.getOrNull()

        if (headerCheck == null ||
            headerCheck.first != GGUF_MAGIC_LE ||
            headerCheck.second !in 1..4 ||
            headerCheck.third.first < 0L ||
            headerCheck.third.second < 0L
        ) {
            return@withContext ModelVerificationResult(
                isValid = false,
                verdict = ModelIntegrityVerdict.CORRUPT_HEADER,
                fileSizeBytes = actualSize,
                expectedSizeBytes = expectedSizeBytes,
                failureReason = "Invalid GGUF binary header (magic/version/tensor count check failed)."
            )
        }

        val parsed = GgufHeaderParser.parseHeader(file)
        if (parsed == null) {
            return@withContext ModelVerificationResult(
                isValid = false,
                verdict = ModelIntegrityVerdict.CORRUPT_HEADER,
                fileSizeBytes = actualSize,
                expectedSizeBytes = expectedSizeBytes,
                failureReason = "GGUF metadata table is truncated or malformed."
            )
        }

        ModelVerificationResult(
            isValid = true,
            verdict = ModelIntegrityVerdict.VERIFIED_GGUF,
            fileSizeBytes = actualSize,
            expectedSizeBytes = expectedSizeBytes,
            headerMetadata = parsed
        )
    }

    suspend fun verifyComplete(
        file: File,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        computeSha256: Boolean = !expectedSha256.isNullOrBlank(),
        minFileSizeBytes: Long = MIN_HEADER_BYTES
    ): ModelVerificationResult = withContext(Dispatchers.IO) {
        val structural = verifyStructure(
            file = file,
            expectedSizeBytes = expectedSizeBytes,
            minFileSizeBytes = minFileSizeBytes
        )
        if (!structural.isValid) {
            return@withContext structural
        }

        val normalizedExpected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (!computeSha256 && normalizedExpected == null) {
            return@withContext structural
        }

        val digestHex = computeSha256(file)
        if (normalizedExpected != null && digestHex != normalizedExpected) {
            return@withContext structural.copy(
                isValid = false,
                verdict = ModelIntegrityVerdict.SHA256_MISMATCH,
                actualSha256 = digestHex,
                expectedSha256 = normalizedExpected,
                failureReason = "SHA-256 checksum mismatch (expected ${normalizedExpected.take(12)}…, got ${digestHex.take(12)}…)."
            )
        }

        structural.copy(
            isValid = true,
            verdict = ModelIntegrityVerdict.VERIFIED_SHA256,
            actualSha256 = digestHex,
            expectedSha256 = normalizedExpected
        )
    }

    suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object DownloadCheckpointStore {

    fun checkpointFileForPart(partFile: File): File =
        File(partFile.parentFile ?: File("."), "${partFile.name}.checkpoint.json")

    fun verifiedManifestFileForModel(modelFile: File): File =
        File(modelFile.parentFile ?: File("."), "${modelFile.name}.verified.json")

    fun writeCheckpoint(partFile: File, manifest: DownloadCheckpointManifest) {
        runCatching {
            val json = JSONObject().apply {
                put("repoId", manifest.repoId)
                put("rfilename", manifest.rfilename)
                put("modelId", manifest.modelId)
                put("modelDisplayName", manifest.modelDisplayName)
                put("downloadUrl", manifest.downloadUrl)
                put("expectedTotalBytes", manifest.expectedTotalBytes)
                put("expectedSha256", manifest.expectedSha256 ?: JSONObject.NULL)
                put("etag", manifest.etag ?: JSONObject.NULL)
                put("bytesOnDisk", manifest.bytesOnDisk)
                put("updatedAtMs", manifest.updatedAtMs)
            }
            checkpointFileForPart(partFile).writeText(json.toString())
        }
    }

    fun readCheckpoint(partFile: File): DownloadCheckpointManifest? {
        val file = checkpointFileForPart(partFile)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            DownloadCheckpointManifest(
                repoId = obj.optString("repoId"),
                rfilename = obj.optString("rfilename"),
                modelId = obj.optString("modelId"),
                modelDisplayName = obj.optString("modelDisplayName"),
                downloadUrl = obj.optString("downloadUrl"),
                expectedTotalBytes = obj.optLong("expectedTotalBytes", 0L),
                expectedSha256 = obj.optString("expectedSha256").takeIf {
                    it.isNotBlank() && it != "null"
                },
                etag = obj.optString("etag").takeIf {
                    it.isNotBlank() && it != "null"
                },
                bytesOnDisk = obj.optLong("bytesOnDisk", partFile.length()),
                updatedAtMs = obj.optLong("updatedAtMs", file.lastModified())
            )
        }.getOrNull()
    }

    fun writeVerifiedManifest(modelFile: File, result: ModelVerificationResult) {
        runCatching {
            val json = JSONObject().apply {
                put("fileName", modelFile.name)
                put("sizeBytes", result.fileSizeBytes)
                put("expectedSizeBytes", result.expectedSizeBytes ?: JSONObject.NULL)
                put("actualSha256", result.actualSha256 ?: JSONObject.NULL)
                put("expectedSha256", result.expectedSha256 ?: JSONObject.NULL)
                put("verdict", result.verdict.name)
                put("architecture", result.headerMetadata?.architecture ?: JSONObject.NULL)
                put("verifiedAtMs", result.verifiedAtMs)
            }
            verifiedManifestFileForModel(modelFile).writeText(json.toString())
        }
    }

    fun readVerifiedManifest(modelFile: File): VerifiedArtifactManifest? {
        val file = verifiedManifestFileForModel(modelFile)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            val savedSize = obj.optLong("sizeBytes", -1L)
            if (savedSize > 0L && modelFile.exists() && modelFile.length() != savedSize) {
                return null
            }
            val verdictName = obj.optString("verdict")
            val verdict = ModelIntegrityVerdict.entries.firstOrNull { it.name == verdictName }
                ?: ModelIntegrityVerdict.VERIFIED_GGUF
            VerifiedArtifactManifest(
                fileName = obj.optString("fileName", modelFile.name),
                sizeBytes = savedSize.coerceAtLeast(0L),
                expectedSizeBytes = obj.optLong("expectedSizeBytes", -1L).takeIf { it > 0L },
                actualSha256 = obj.optString("actualSha256").takeIf {
                    it.isNotBlank() && it != "null"
                },
                expectedSha256 = obj.optString("expectedSha256").takeIf {
                    it.isNotBlank() && it != "null"
                },
                verdict = verdict,
                architecture = obj.optString("architecture").takeIf {
                    it.isNotBlank() && it != "null"
                },
                verifiedAtMs = obj.optLong("verifiedAtMs", file.lastModified())
            )
        }.getOrNull()
    }

    fun deleteSidecars(targetFile: File) {
        runCatching {
            checkpointFileForPart(targetFile).takeIf { it.exists() }?.delete()
            verifiedManifestFileForModel(targetFile).takeIf { it.exists() }?.delete()
            val lockFile = File(targetFile.parentFile ?: File("."), "${targetFile.name}.lck")
            lockFile.takeIf { it.exists() }?.delete()
        }
    }
}

object StorageSpaceChecker {

    private const val MIN_SAFETY_BUFFER_BYTES = 256L * 1024L * 1024L // 256 MiB
    private const val SAFETY_BUFFER_RATIO = 0.05

    @Volatile
    var statOverrideForTests: ((File) -> Pair<Long, Long>)? = null

    fun calculateSafetyBufferBytes(requiredAdditionalBytes: Long): Long {
        if (requiredAdditionalBytes <= 0L) return 0L
        val ratioBytes = (requiredAdditionalBytes.toDouble() * SAFETY_BUFFER_RATIO).toLong()
        return maxOf(MIN_SAFETY_BUFFER_BYTES, ratioBytes)
    }

    fun queryAvailableAndTotalBytes(directory: File): Pair<Long, Long> {
        statOverrideForTests?.let { override ->
            return override(directory)
        }
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return runCatching {
            val stat = StatFs(directory.absolutePath)
            stat.availableBytes to stat.totalBytes
        }.getOrDefault(0L to 0L)
    }

    fun inspectStorage(modelsDir: File): StorageSpaceSnapshot {
        val (availableBytes, totalBytes) = queryAvailableAndTotalBytes(modelsDir)
        var modelsUsed = 0L
        var partialUsed = 0L
        modelsDir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            when {
                file.name.endsWith(".gguf", ignoreCase = true) -> modelsUsed += file.length()
                file.name.endsWith(".gguf.part", ignoreCase = true) -> partialUsed += file.length()
            }
        }
        return StorageSpaceSnapshot(
            totalBytes = totalBytes,
            availableBytes = availableBytes,
            modelsUsedBytes = modelsUsed,
            partialUsedBytes = partialUsed
        )
    }

    fun checkSpaceForBytes(
        modelsDir: File,
        totalTargetBytes: Long,
        existingPartialBytes: Long = 0L,
        safetyBufferOverrideBytes: Long? = null
    ): StorageCheckResult {
        val (availableBytes, _) = queryAvailableAndTotalBytes(modelsDir)
        val remaining = (totalTargetBytes - existingPartialBytes).coerceAtLeast(0L)
        val buffer = safetyBufferOverrideBytes ?: calculateSafetyBufferBytes(remaining)
        val totalRequired = remaining + buffer
        val hasEnough = remaining == 0L || availableBytes >= totalRequired
        val deficit = (totalRequired - availableBytes).coerceAtLeast(0L)
        return StorageCheckResult(
            hasEnoughSpace = hasEnough,
            availableBytes = availableBytes,
            requiredAdditionalBytes = remaining,
            safetyBufferBytes = buffer,
            totalRequiredWithBufferBytes = totalRequired,
            deficitBytes = deficit
        )
    }
}

object ModelFileLifecycleManager {

    fun deleteSafely(file: File): SafeDeleteOutcome {
        if (!file.exists()) {
            DownloadCheckpointStore.deleteSidecars(file)
            return SafeDeleteOutcome.NotFound
        }

        when (val check = MmapLifecycleRegistry.canDeleteSafely(file)) {
            is SafeDeleteCheck.BlockedActiveMapping -> {
                MmapLifecycleRegistry.recordBlockedDeleteAttempt(
                    file = file,
                    reason = "Blocked deletion of ${file.name}: ${check.activeLeaseCount} active mmap mapping(s) on ${check.backendLabels.joinToString()}."
                )
                return SafeDeleteOutcome.BlockedActiveMapping(
                    activeLeaseCount = check.activeLeaseCount,
                    backendLabels = check.backendLabels
                )
            }

            is SafeDeleteCheck.BlockedWriteLock -> {
                MmapLifecycleRegistry.recordBlockedDeleteAttempt(
                    file = file,
                    reason = "Blocked deletion of ${file.name}: exclusive write lock active."
                )
                return SafeDeleteOutcome.BlockedWriteLock
            }

            SafeDeleteCheck.Allowed -> Unit
        }

        val writeLock = MmapLifecycleRegistry.acquireExclusiveWriteLock(file)
        if (writeLock == null) {
            return if (MmapLifecycleRegistry.hasActiveMappings(file)) {
                SafeDeleteOutcome.BlockedActiveMapping(
                    activeLeaseCount = MmapLifecycleRegistry.activeMappingCount(file),
                    backendLabels = emptyList()
                )
            } else {
                SafeDeleteOutcome.BlockedWriteLock
            }
        }

        return try {
            if (MmapLifecycleRegistry.hasActiveMappings(file)) {
                SafeDeleteOutcome.BlockedActiveMapping(
                    activeLeaseCount = MmapLifecycleRegistry.activeMappingCount(file),
                    backendLabels = emptyList()
                )
            } else if (!file.exists()) {
                DownloadCheckpointStore.deleteSidecars(file)
                SafeDeleteOutcome.NotFound
            } else if (file.delete()) {
                DownloadCheckpointStore.deleteSidecars(file)
                SafeDeleteOutcome.Deleted
            } else {
                SafeDeleteOutcome.Failed("OS refused to delete ${file.name}.")
            }
        } finally {
            MmapLifecycleRegistry.releaseExclusiveWriteLock(writeLock)
        }
    }

    fun pruneExpiredPartFilesSafely(
        modelsDir: File,
        activePartFileName: String?,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        var removed = 0
        val files = modelsDir.listFiles().orEmpty()
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".part")) continue
            val isActive = file.name == activePartFileName
            val isExpired = (nowMs - file.lastModified()) > maxAgeMs
            if (!isActive && isExpired) {
                if (deleteSafely(file) is SafeDeleteOutcome.Deleted) {
                    removed++
                }
            }
        }
        return removed
    }
}
