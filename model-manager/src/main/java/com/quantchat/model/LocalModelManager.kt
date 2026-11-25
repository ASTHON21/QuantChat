package com.quantchat.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class GgufHeaderMetadata(
    val ggufVersion: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val architecture: String?,
    val modelName: String?,
    val declaredContextLength: Int?,
    val embeddingLength: Int?,
    val blockCount: Int?,
    val kvHeadCount: Int?,
    val tokenizerModel: String?
) {
    fun estimateRuntimeMemoryMb(fileSizeBytes: Long, activeContextSize: Int): Long {
        val weightsMb = ((fileSizeBytes * 0.95) / (1024.0 * 1024.0)).toLong().coerceAtLeast(16L)
        val layers = blockCount ?: 32
        val kvHeads = kvHeadCount ?: 8
        val headDim = if (embeddingLength != null && kvHeads > 0) {
            (embeddingLength / maxOf(kvHeads, 1)).coerceIn(64, 256)
        } else {
            128
        }
        // F16 KV cache ~ 2 (K+V) * 2 bytes * layers * kvHeads * headDim * n_ctx
        val kvBytes = 4L * layers * kvHeads * headDim * activeContextSize.toLong()
        val kvMb = (kvBytes / (1024L * 1024L)).coerceAtLeast(32L)
        val runtimeScratchMb = 128L
        return weightsMb + kvMb + runtimeScratchMb
    }
}

object GgufHeaderParser {

    private const val GGUF_MAGIC_LE = 0x46554747 // "GGUF" in little-endian
    private const val MAX_KV_SCAN = 256
    private const val MAX_STRING_BYTES = 16 * 1024

    fun isValidMagic(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 24L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = readIntLe(raf)
                magic == GGUF_MAGIC_LE
            }
        }.getOrDefault(false)
    }

    suspend fun parseHeader(file: File): GgufHeaderMetadata? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile || file.length() < 24L) return@withContext null
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = readIntLe(raf)
                if (magic != GGUF_MAGIC_LE) return@withContext null
                val version = readIntLe(raf)
                if (version !in 1..4) return@withContext null
                val tensorCount = readLongLe(raf)
                val kvCount = readLongLe(raf)

                val props = mutableMapOf<String, String>()
                val limit = kvCount.coerceAtMost(MAX_KV_SCAN.toLong()).toInt()
                for (i in 0 until limit) {
                    if (raf.filePointer >= raf.length()) break
                    val key = readGgufString(raf) ?: break
                    val valueType = readIntLe(raf)
                    val valueStr = readGgufValueAsString(raf, valueType) ?: break
                    if (key.length <= 128) {
                        props[key] = valueStr
                    }
                }

                val arch = props["general.architecture"]
                val name = props["general.name"]
                val ctxLen = arch?.let { props["$it.context_length"]?.toIntOrNull() }
                val embLen = arch?.let { props["$it.embedding_length"]?.toIntOrNull() }
                val blocks = arch?.let { props["$it.block_count"]?.toIntOrNull() }
                val kvHeads = arch?.let { props["$it.attention.head_count_kv"]?.toIntOrNull() }
                val tokModel = props["tokenizer.ggml.model"]

                GgufHeaderMetadata(
                    ggufVersion = version,
                    tensorCount = tensorCount,
                    kvCount = kvCount,
                    architecture = arch,
                    modelName = name,
                    declaredContextLength = ctxLen,
                    embeddingLength = embLen,
                    blockCount = blocks,
                    kvHeadCount = kvHeads,
                    tokenizerModel = tokModel
                )
            }
        }.getOrNull()
    }

    private fun readGgufString(raf: RandomAccessFile): String? {
        val lenLong = readLongLe(raf)
        if (lenLong < 0 || lenLong > 64 * 1024) return null
        val len = lenLong.toInt()
        if (len == 0) return ""
        val readLen = len.coerceAtMost(MAX_STRING_BYTES)
        val bytes = ByteArray(readLen)
        raf.readFully(bytes)
        if (len > readLen) {
            raf.seek(raf.filePointer + (len - readLen))
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readGgufValueAsString(raf: RandomAccessFile, type: Int): String? {
        return when (type) {
            0 -> raf.readUnsignedByte().toString() // UINT8
            1 -> raf.readByte().toString() // INT8
            2 -> readShortLe(raf).toUShort().toString() // UINT16
            3 -> readShortLe(raf).toString() // INT16
            4 -> readIntLe(raf).toUInt().toString() // UINT32
            5 -> readIntLe(raf).toString() // INT32
            6 -> java.lang.Float.intBitsToFloat(readIntLe(raf)).toString() // FLOAT32
            7 -> (raf.readUnsignedByte() != 0).toString() // BOOL
            8 -> readGgufString(raf) // STRING
            9 -> { // ARRAY
                val elemType = readIntLe(raf)
                val count = readLongLe(raf)
                if (count < 0 || count > 2_000_000L) return null
                skipGgufArray(raf, elemType, count)
                "[array:$count]"
            }
            10 -> readLongLe(raf).toULong().toString() // UINT64
            11 -> readLongLe(raf).toString() // INT64
            12 -> java.lang.Double.longBitsToDouble(readLongLe(raf)).toString() // FLOAT64
            else -> null
        }
    }

    private fun skipGgufArray(raf: RandomAccessFile, elemType: Int, count: Long) {
        val fixedSize = when (elemType) {
            0, 1, 7 -> 1L
            2, 3 -> 2L
            4, 5, 6 -> 4L
            10, 11, 12 -> 8L
            else -> -1L
        }
        if (fixedSize > 0) {
            raf.seek(raf.filePointer + fixedSize * count)
            return
        }
        for (i in 0 until count) {
            readGgufValueAsString(raf, elemType) ?: break
        }
    }

    private fun readShortLe(raf: RandomAccessFile): Short {
        val b = ByteArray(2)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readLongLe(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }
}

class LocalModelRegistry(private val modelsDir: File) {

    private data class CacheKey(val fileName: String, val size: Long, val lastModified: Long)
    private val headerCache = ConcurrentHashMap<CacheKey, GgufHeaderMetadata>()

    suspend fun inspectHeader(fileName: String): GgufHeaderMetadata? {
        val file = File(modelsDir, fileName)
        if (!file.exists() || !file.isFile) return null
        val key = CacheKey(file.name, file.length(), file.lastModified())
        headerCache[key]?.let { return it }
        val parsed = GgufHeaderParser.parseHeader(file) ?: return null
        headerCache[key] = parsed
        return parsed
    }

    fun extractQuantTag(fileName: String): String {
        val upper = fileName.uppercase(Locale.US)
        val regex = Regex("(IQ[1-4]_[A-Z0-9_]+|Q[2-8]_[A-Z0-9_]+|BF16|F16|FP16|F32)")
        return regex.find(upper)?.value ?: "GGUF"
    }
}
