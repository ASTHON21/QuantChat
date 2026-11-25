package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.EngineConfig
import com.quantchat.engine.core.EngineError
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.EngineVersionInfo
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.ModelArtifactFormat
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.NpuCompatibilityVerdict
import com.quantchat.engine.core.NpuInferenceBackend
import com.quantchat.engine.core.NpuNativeRuntimeProbe
import com.quantchat.engine.core.NpuProbeStatus
import com.quantchat.engine.core.NpuVendorDriverAdapter
import com.quantchat.engine.core.NpuVendorFamily
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import com.quantchat.engine.vulkan.VulkanNativeBridge
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.NpuCapabilityProbe
import com.quantchat.model.GgufHeaderMetadata
import com.quantchat.model.LocalModelRegistry
import com.quantchat.resource.NpuCompatibilityEvaluator
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1_3NpuCapabilityTest {

    private class FakeCpuBackend : InferenceBackend {
        override val versionInfo = EngineVersionInfo("llama-cpu", "llama.cpp", "1.0", "arm64-v8a")
        override val capabilities = EngineCapabilities(
            supportedBackends = setOf(AcceleratorBackendType.CPU),
            supportsStreaming = true,
            supportsKvSession = true,
            supportsTokenize = true,
            supportsEmbedding = false,
            supportsNativeBenchmark = true,
            supportsGpuOffload = false,
            supportsNpuOffload = false
        )
        private val _state = MutableStateFlow<EngineLifecycleState>(
            EngineLifecycleState.Initialized(versionInfo, capabilities)
        )
        override val lifecycleState: StateFlow<EngineLifecycleState> = _state

        override suspend fun initialize(config: EngineConfig) {}
        override suspend fun validateModelFile(fileUri: String) = true
        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flowOf(
            GenerationEvent.Completed("ok", 10L, 1, 100f, false)
        )
        override suspend fun cancel() {}
        override suspend fun unload() {
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }
        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int) =
            BenchmarkResult(100f, 25f, prefillTokens, decodeTokens, 50L, true, "CPU")
        override suspend fun tokenize(text: String) = IntArray(4) { it }
        override suspend fun detokenize(tokens: IntArray) = ""
        override suspend fun embed(text: String) = floatArrayOf()
        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int) =
            SessionSaveResult(sessionId, path, maxTokens)
        override suspend fun loadSession(sessionId: SessionId, path: String) = 0
        override suspend fun formatChat(turns: List<ChatTurn>, template: String) = null
    }

    @Test
    fun `npu capability probe accurately classifies Snapdragon Hexagon, MediaTek APU, Tensor, Exynos, and Emulator`() {
        val snapdragon8Gen3 = NpuCapabilityProbe.classifyNpuHardware(
            socManufacturer = "QTI",
            socModel = "SM8650",
            hardware = "qcom",
            boardPlatform = "pineapple",
            supportedAbis = listOf("arm64-v8a"),
            sdkInt = 35,
            nativeProbe = NpuNativeRuntimeProbe(
                nativeProbeExecuted = true,
                qnnHtpAccessible = false,
                cdspRpcAccessible = false
            )
        )
        assertEquals(NpuVendorFamily.QUALCOMM_HEXAGON, snapdragon8Gen3.vendorFamily)
        assertEquals(NpuProbeStatus.HARDWARE_DETECTED_RUNTIME_UNBUNDLED, snapdragon8Gen3.status)
        assertTrue(snapdragon8Gen3.hexagonGenerationHint?.contains("v75") == true)
        assertTrue(snapdragon8Gen3.nnapiDeprecatedOnOs)
        assertTrue(snapdragon8Gen3.supportedPrecisionHints.containsAll(listOf("INT8", "INT4")))

        val snapdragonReady = NpuCapabilityProbe.classifyNpuHardware(
            socManufacturer = "Qualcomm",
            socModel = "SM8750",
            hardware = "qcom",
            supportedAbis = listOf("arm64-v8a"),
            sdkInt = 35,
            nativeProbe = NpuNativeRuntimeProbe(
                nativeProbeExecuted = true,
                qnnHtpAccessible = true,
                cdspRpcAccessible = true
            )
        )
        assertEquals(NpuProbeStatus.RUNTIME_READY, snapdragonReady.status)
        assertTrue(snapdragonReady.isVendorRuntimeReady)
        assertTrue(snapdragonReady.hexagonGenerationHint?.contains("v79") == true)

        val dimensity = NpuCapabilityProbe.classifyNpuHardware(
            socManufacturer = "Mediatek",
            socModel = "MT6989",
            hardware = "mt6989",
            supportedAbis = listOf("arm64-v8a"),
            sdkInt = 34
        )
        assertEquals(NpuVendorFamily.MEDIATEK_APU, dimensity.vendorFamily)
        assertEquals(NpuProbeStatus.HARDWARE_DETECTED_RUNTIME_UNBUNDLED, dimensity.status)
        assertFalse(dimensity.nnapiDeprecatedOnOs)

        val tensor = NpuCapabilityProbe.classifyNpuHardware(
            socManufacturer = "Google",
            socModel = "Tensor G3",
            hardware = "zuma",
            supportedAbis = listOf("arm64-v8a"),
            sdkInt = 34
        )
        assertEquals(NpuVendorFamily.GOOGLE_TENSOR_TPU, tensor.vendorFamily)

        val exynos = NpuCapabilityProbe.classifyNpuHardware(
            socManufacturer = "Samsung",
            socModel = "S5E9945",
            hardware = "s5e9945",
            supportedAbis = listOf("arm64-v8a"),
            sdkInt = 34
        )
        assertEquals(NpuVendorFamily.SAMSUNG_EXYNOS_NPU, exynos.vendorFamily)

        val emulatorX86 = NpuCapabilityProbe.classifyNpuHardware(
            socManufacturer = "",
            socModel = "",
            hardware = "ranchu",
            supportedAbis = listOf("x86_64"),
            sdkInt = 35
        )
        assertEquals(NpuVendorFamily.NONE, emulatorX86.vendorFamily)
        assertEquals(NpuProbeStatus.UNSUPPORTED_DEVICE, emulatorX86.status)
        assertFalse(emulatorX86.supportsNnapi)
    }

    @Test
    fun `native npu runtime probe handles missing jni library safely and parses payload`() {
        val localProbe = VulkanNativeBridge.probeNpuRuntime()
        assertFalse(localProbe.nativeProbeExecuted)
        assertFalse(localProbe.qnnHtpAccessible)

        val parsed = VulkanNativeBridge.parseNpuProbeResponse(
            """
            qnnHtpAccessible=true
            cdspRpcAccessible=false
            qnnError=
            cdspError=library "/vendor/lib64/libcdsprpc.so" not accessible
            """.trimIndent()
        )
        assertTrue(parsed.nativeProbeExecuted)
        assertTrue(parsed.qnnHtpAccessible)
        assertFalse(parsed.cdspRpcAccessible)
        assertNotNull(parsed.cdspError)
    }

    @Test
    fun `npu backend gates gguf models cleanly and supports future vendor adapter contract`() = runBlocking {
        val backend = NpuInferenceBackend()
        assertFalse(backend.capabilities.supportsNpuOffload)

        var thrown: EngineError.NpuBackendError? = null
        try {
            backend.loadModel(
                ModelConfig(
                    modelId = ModelId("qwen3.gguf"),
                    displayName = "Qwen3-4B",
                    filePath = "/tmp/qwen3.gguf",
                    fileUri = "file:///tmp/qwen3.gguf",
                    contextSize = 2048
                )
            )
        } catch (e: EngineError.NpuBackendError) {
            thrown = e
        }
        assertNotNull(thrown)
        assertTrue(thrown!!.fallbackEligible)
        assertTrue(thrown.reason.contains("GGUF", ignoreCase = true))

        val fakeQnnAdapter = object : NpuVendorDriverAdapter {
            override val vendorFamily = NpuVendorFamily.QUALCOMM_HEXAGON
            override val driverName = "QNN HTP v75"
            override fun isRuntimeBundled() = true
            override fun evaluateArtifact(filePath: String, format: ModelArtifactFormat): Pair<Boolean, String?> =
                if (format == ModelArtifactFormat.QNN_CONTEXT_BIN) true to null
                else false to "Unsupported format"
        }
        val adapterBackend = NpuInferenceBackend(fakeQnnAdapter)
        assertTrue(adapterBackend.capabilities.supportsNpuOffload)

        val qnnReport = adapterBackend.evaluateArtifactCompatibility(
            modelId = "llama3_ctx.bin",
            filePath = "/tmp/llama3_ctx.bin",
            probeResult = NpuCapabilityProbe.classifyNpuHardware("QTI", "SM8650", "qcom", supportedAbis = listOf("arm64-v8a"))
        )
        assertTrue(qnnReport.compatibleWithNpu)
        assertEquals(NpuCompatibilityVerdict.COMPATIBLE, qnnReport.verdictCode)

        val ggufHeader = GgufHeaderMetadata(
            ggufVersion = 3,
            tensorCount = 300L,
            kvCount = 24L,
            architecture = "qwen3",
            modelName = "Qwen3-4B",
            declaredContextLength = 32768,
            embeddingLength = 2560,
            blockCount = 36,
            kvHeadCount = 8,
            tokenizerModel = "gpt2"
        )
        val ggufReport = NpuCompatibilityEvaluator.evaluate(
            modelId = "qwen3.gguf",
            filePath = "/tmp/qwen3.gguf",
            header = ggufHeader,
            npuProbe = NpuCapabilityProbe.classifyNpuHardware("QTI", "SM8650", "qcom", supportedAbis = listOf("arm64-v8a")),
            routedFallbackTarget = "Vulkan GPU (Adreno 750)",
            npuBackend = adapterBackend
        )
        assertFalse(ggufReport.compatibleWithNpu)
        assertEquals(NpuCompatibilityVerdict.INCOMPATIBLE_FORMAT, ggufReport.verdictCode)
        assertEquals("Vulkan GPU (Adreno 750)", ggufReport.routedFallbackTarget)
        assertTrue(ggufReport.blockingReason?.contains("qwen3") == true)
    }

    @Test
    fun `runtime orchestrator populates npu capabilities and gguf gate report on initialize, probe, load, and unload`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempModels = createTempDirectory("npu_orch_models").toFile()
        val tempSessions = createTempDirectory("npu_orch_sessions").toFile()
        try {
            val dummyGguf = File(tempModels, "qwen3.gguf").apply { writeBytes(ByteArray(2048)) }
            val hw = HardwareCapabilityManager(
                context = context,
                vulkanNativeProbe = {
                    VulkanProbeResult(
                        status = VulkanProbeStatus.NATIVE_LIBRARY_MISSING,
                        reason = "CPU fallback test"
                    )
                },
                npuNativeProbe = {
                    NpuNativeRuntimeProbe(nativeProbeExecuted = true, qnnHtpAccessible = false, cdspRpcAccessible = false)
                }
            )
            val orch = RuntimeOrchestrator(
                primaryBackend = FakeCpuBackend(),
                hardwareManager = hw,
                resourceManager = RuntimeResourceManager(hw),
                modelRegistry = LocalModelRegistry(tempModels),
                sessionManager = SessionCacheManager(tempSessions),
                npuBackend = NpuInferenceBackend()
            )
            try {
                orch.initialize()
                assertNotNull(orch.snapshot.value.npuCapabilities)
                assertNull(orch.snapshot.value.npuCompatibilityReport)

                val probed = orch.runNpuProbe()
                assertNotNull(probed)
                assertNotNull(orch.snapshot.value.npuCapabilities)

                orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, 2048, 4)
                val loadedSnap = orch.snapshot.value
                val compat = loadedSnap.npuCompatibilityReport
                assertNotNull(compat)
                assertFalse(compat!!.compatibleWithNpu)
                assertEquals(NpuCompatibilityVerdict.INCOMPATIBLE_FORMAT, compat.verdictCode)
                assertEquals(ModelArtifactFormat.GGUF, compat.artifactFormat)
                assertEquals("CPU", compat.routedFallbackTarget)

                orch.unloadModel()
                assertNull(orch.snapshot.value.npuCompatibilityReport)
            } finally {
                orch.shutdown()
            }
        } finally {
            tempModels.deleteRecursively()
            tempSessions.deleteRecursively()
        }
    }
}
