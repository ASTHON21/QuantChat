package com.example

import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.EngineConfig
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.EngineVersionInfo
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.PromptTemplateFormatter
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.model.GgufHeaderParser
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

class Phase0ArchitectureTest {

    private class FakeInferenceBackend : InferenceBackend {
        override val versionInfo = EngineVersionInfo("fake-cpu", "FakeEngine", "1.0", "arm64-v8a")
        override val capabilities = EngineCapabilities(
            supportedBackends = setOf(AcceleratorBackendType.CPU),
            supportsStreaming = true,
            supportsKvSession = true,
            supportsTokenize = true,
            supportsEmbedding = true,
            supportsNativeBenchmark = true,
            supportsGpuOffload = false,
            supportsNpuOffload = false
        )
        private val _state = MutableStateFlow<EngineLifecycleState>(
            EngineLifecycleState.Initialized(versionInfo, capabilities)
        )
        override val lifecycleState: StateFlow<EngineLifecycleState> = _state

        override suspend fun initialize(config: EngineConfig) {}
        override suspend fun validateModelFile(fileUri: String): Boolean = true

        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }

        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flowOf(
            GenerationEvent.TokenDelta("Hello ", 1),
            GenerationEvent.Completed("Hello world", 42L, 2, 47.6f, false)
        )

        override suspend fun cancel() {}
        override suspend fun unload() {
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }

        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int): BenchmarkResult =
            BenchmarkResult(120f, 24f, prefillTokens, decodeTokens, 500L, true, "fake")

        override suspend fun tokenize(text: String): IntArray =
            IntArray((text.length / 3).coerceAtLeast(1)) { it }

        override suspend fun detokenize(tokens: IntArray): String = "detok"
        override suspend fun embed(text: String): FloatArray = floatArrayOf(0.25f, 0.75f)

        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int): SessionSaveResult {
            File(path).writeText("kv-cache")
            return SessionSaveResult(sessionId, path, maxTokens)
        }

        override suspend fun loadSession(sessionId: SessionId, path: String): Int = 128

        override suspend fun formatChat(turns: List<ChatTurn>, template: String): String =
            turns.joinToString("\n") { "${it.role}:${it.content}" }
    }

    @Test
    fun `gguf header parser reads architecture and context length`() = runBlocking {
        val tempDir = createTempDirectory("gguf_test").toFile()
        try {
            val ggufFile = File(tempDir, "test_qwen3.gguf")
            ggufFile.writeBytes(buildSyntheticGgufHeader(arch = "qwen3", ctxLen = 40960, blockCount = 36, kvHeads = 8))

            assertTrue(GgufHeaderParser.isValidMagic(ggufFile))
            val meta = GgufHeaderParser.parseHeader(ggufFile)
            assertNotNull(meta)
            assertEquals(3, meta!!.ggufVersion)
            assertEquals("qwen3", meta.architecture)
            assertEquals(40960, meta.declaredContextLength)
            assertEquals(36, meta.blockCount)
            assertEquals(8, meta.kvHeadCount)
            assertTrue(meta.estimateRuntimeMemoryMb(ggufFile.length(), 2048) > 0L)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `session cache manager trims history to fit context budget and persists snapshot`() = runBlocking {
        val tempDir = createTempDirectory("session_test").toFile()
        try {
            val backend = FakeInferenceBackend()
            val manager = SessionCacheManager(tempDir)
            val fp1 = manager.buildFingerprint(ModelId("m1"), 512, "Be concise")
            val fp2 = manager.buildFingerprint(ModelId("m1"), 1024, "Be concise")
            assertNotEquals(fp1, fp2)

            val longHistory = (1..10).map { idx ->
                ChatTurn("user", "Message $idx " + "x".repeat(120))
            }
            val budget = manager.prepareContextAndBudget(
                backend = backend,
                threadId = "thread_1",
                fingerprint = fp1,
                systemPrompt = "Be concise",
                history = longHistory,
                userPrompt = "Final question",
                modelFamilyHint = "qwen3",
                contextSize = 512,
                maxOutputTokens = 256
            )
            assertTrue(budget.trimmedTurnsCount > 0)
            assertTrue(budget.promptTokens <= 256)

            val saved = manager.persistSession(backend, "thread_1", fp1, 512)
            assertNotNull(saved)
            val restored = manager.tryRestoreSession(backend, "thread_1", fp1)
            assertNotNull(restored)
            assertEquals(128, restored!!.cachedTokens)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `prompt template formatter strips control tokens cleanly`() {
        val formatted = PromptTemplateFormatter.formatFallback(
            modelFamilyHint = "gemma-3-4b",
            systemPrompt = "Sys",
            history = emptyList(),
            userPrompt = "Hi"
        )
        assertTrue(formatted.contains("<start_of_turn>user"))
        val cleaned = PromptTemplateFormatter.stripControlTokens("Answer<|im_end|><end_of_turn>", GenerationRequest.DEFAULT_STOP_TOKENS)
        assertEquals("Answer", cleaned)
    }

    private fun buildSyntheticGgufHeader(
        arch: String,
        ctxLen: Int,
        blockCount: Int,
        kvHeads: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeIntLe(v: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        fun writeLongLe(v: Long) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        }
        fun writeGgufString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeLongLe(bytes.size.toLong())
            out.write(bytes)
        }

        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        writeIntLe(3) // version
        writeLongLe(10L) // tensorCount
        writeLongLe(4L) // kvCount

        writeGgufString("general.architecture")
        writeIntLe(8) // STRING
        writeGgufString(arch)

        writeGgufString("$arch.context_length")
        writeIntLe(4) // UINT32
        writeIntLe(ctxLen)

        writeGgufString("$arch.block_count")
        writeIntLe(4) // UINT32
        writeIntLe(blockCount)

        writeGgufString("$arch.attention.head_count_kv")
        writeIntLe(4) // UINT32
        writeIntLe(kvHeads)

        out.write(ByteArray(32))
        return out.toByteArray()
    }
}
