package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
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
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.ResilientFailoverBackend
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import com.quantchat.engine.vulkan.VulkanLlamaBackend
import com.quantchat.engine.vulkan.VulkanNativeBridge
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.VulkanCapabilityProbe
import com.quantchat.model.GgufHeaderMetadata
import com.quantchat.model.LocalModelRegistry
import com.quantchat.resource.GpuOffloadPlanner
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1_2VulkanSchedulingTest {

    private class FakeBackend(
        private val backendType: AcceleratorBackendType,
        var failOnLoad: Throwable? = null,
        var failPreDecode: Throwable? = null
    ) : InferenceBackend {
        override val versionInfo = EngineVersionInfo(
            backendId = if (backendType == AcceleratorBackendType.VULKAN_GPU) "fake-vulkan" else "fake-cpu",
            engineName = "Fake",
            engineVersion = "1.0",
            abi = "arm64-v8a"
        )
        override val capabilities = EngineCapabilities(
            supportedBackends = setOf(backendType),
            supportsStreaming = true,
            supportsKvSession = true,
            supportsTokenize = true,
            supportsEmbedding = false,
            supportsNativeBenchmark = true,
            supportsGpuOffload = backendType == AcceleratorBackendType.VULKAN_GPU,
            supportsNpuOffload = false
        )
        private val _state = MutableStateFlow<EngineLifecycleState>(
            EngineLifecycleState.Initialized(versionInfo, capabilities)
        )
        override val lifecycleState: StateFlow<EngineLifecycleState> = _state

        var loadCalls = 0
        var unloadCalls = 0
        var lastLoadedConfig: ModelConfig? = null

        override suspend fun initialize(config: EngineConfig) {
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }

        override suspend fun validateModelFile(fileUri: String): Boolean = true

        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            loadCalls++
            failOnLoad?.let { throw it }
            lastLoadedConfig = model
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }

        override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
            val err = failPreDecode
            if (err != null) {
                return flow { throw err }
            }
            val label = if (backendType == AcceleratorBackendType.VULKAN_GPU) "GPU_OK" else "CPU_OK"
            return flowOf(
                GenerationEvent.TokenDelta(label, 1),
                GenerationEvent.Completed(label, 15L, 1, 66.6f, false)
            )
        }

        override suspend fun cancel() {}

        override suspend fun unload() {
            unloadCalls++
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }

        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int): BenchmarkResult =
            BenchmarkResult(120f, 30f, prefillTokens, decodeTokens, 100L, true, backendType.label)

        override suspend fun tokenize(text: String): IntArray = IntArray(4) { it }
        override suspend fun detokenize(tokens: IntArray): String = ""
        override suspend fun embed(text: String): FloatArray = floatArrayOf()
        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int) =
            SessionSaveResult(sessionId, path, maxTokens)
        override suspend fun loadSession(sessionId: SessionId, path: String): Int = 0
        override suspend fun formatChat(turns: List<ChatTurn>, template: String): String? = null
    }

    private fun verifiedVulkanProbe(
        deviceName: String = "Adreno (TM) 750",
        recommendedMaxOffloadMb: Long = 4096L
    ) = VulkanProbeResult(
        status = VulkanProbeStatus.AVAILABLE,
        nativeLibraryLoaded = true,
        systemVulkanSupported = true,
        systemVulkanVersion = "1.3.0",
        deviceName = deviceName,
        vendorLabel = "Qualcomm (Adreno)",
        vulkanApiVersionLabel = "1.3.0",
        supports16BitStorage = true,
        supportsFp16Compute = true,
        deviceLocalHeapMb = 6144L,
        recommendedMaxOffloadMb = recommendedMaxOffloadMb,
        bufferTransferSelfTestPassed = true,
        operatorSelfTestPassed = true,
        selfTestSummary = "Buffer copy/readback OK · ggml-vulkan 1 dev"
    )

    private fun sampleHeader(blockCount: Int = 36) = GgufHeaderMetadata(
        ggufVersion = 3,
        tensorCount = 300L,
        kvCount = 24L,
        architecture = "qwen3",
        modelName = "Qwen3-4B-Q4_K_M",
        declaredContextLength = 32768,
        embeddingLength = 2560,
        blockCount = blockCount,
        kvHeadCount = 8,
        tokenizerModel = "gpt2"
    )

    @Test
    fun `gpu offload planner computes safe auto layers, caps low memory, and honors cpu_only and manual override`() {
        val modelBytes = 2400L * 1024L * 1024L // ~2.4GB GGUF
        val header = sampleHeader(blockCount = 36)
        val probe = verifiedVulkanProbe(recommendedMaxOffloadMb = 5000L)

        // Plenty of free RAM (7000MB) -> offloads significant or full layers with headroom preserved
        val abundantPlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            availableRamMb = 7000L,
            lowMemory = false,
            vulkanProbe = probe,
            preference = AcceleratorPreference.AUTO,
            autoOffload = true,
            manualLayersOverride = 0
        )
        assertEquals(36, abundantPlan.totalLayers)
        assertEquals(36, abundantPlan.recommendedLayers)
        assertEquals(36, abundantPlan.effectiveLayers)
        assertTrue(abundantPlan.estimatedGpuMemoryMb > 1800L)

        // Tight RAM (2500MB free -> ~1024MB reserved -> partial offload)
        val tightPlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            availableRamMb = 2500L,
            lowMemory = false,
            vulkanProbe = probe,
            preference = AcceleratorPreference.AUTO,
            autoOffload = true,
            manualLayersOverride = 0
        )
        assertTrue(tightPlan.effectiveLayers in 1 until 36)
        assertTrue(tightPlan.estimatedCpuMemoryMb > 0L)

        // Low memory flag -> safely keeps 0 GPU layers
        val lowMemPlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            availableRamMb = 1200L,
            lowMemory = true,
            vulkanProbe = probe,
            preference = AcceleratorPreference.AUTO,
            autoOffload = true,
            manualLayersOverride = 20
        )
        assertEquals(0, lowMemPlan.recommendedLayers)
        assertEquals(0, lowMemPlan.effectiveLayers)

        // Manual override active
        val manualPlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            availableRamMb = 7000L,
            lowMemory = false,
            vulkanProbe = probe,
            preference = AcceleratorPreference.AUTO,
            autoOffload = false,
            manualLayersOverride = 18
        )
        assertEquals(18, manualPlan.effectiveLayers)
        assertFalse(manualPlan.isAutoMode)

        // CPU_ONLY forces 0 even if manual override was set
        val cpuOnlyPlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            availableRamMb = 7000L,
            lowMemory = false,
            vulkanProbe = probe,
            preference = AcceleratorPreference.CPU_ONLY,
            autoOffload = false,
            manualLayersOverride = 24
        )
        assertEquals(0, cpuOnlyPlan.effectiveLayers)
    }

    @Test
    fun `vulkan native bridge handles missing jni library safely and parses probe payload accurately`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // In JVM/Robolectric builds, libquantchat_vulkan.so is not loaded -> must NOT throw UnsatisfiedLinkError
        val localProbe = VulkanNativeBridge.probeVulkanCapabilities()
        assertEquals(VulkanProbeStatus.NATIVE_LIBRARY_MISSING, localProbe.status)
        assertFalse(localProbe.nativeLibraryLoaded)
        assertFalse(localProbe.isUsableForInference)

        val rawPayload = """
            status=AVAILABLE
            deviceName=Mali-G720 Immortalis MC12
            vendorLabel=ARM (Mali / Immortalis)
            driverVersion=490123
            apiVersion=1.3.264
            supports16BitStorage=true
            supportsFp16Compute=true
            supportsCoopMatrix=false
            deviceLocalMb=8192
            hostVisibleMb=8192
            recommendedOffloadMb=4915
            bufferSelfTest=true
            operatorSelfTest=true
            summary=Buffer copy/readback OK · ggml-vulkan 1 dev
            reason=
        """.trimIndent()
        val parsed = VulkanNativeBridge.parseProbeResponse(rawPayload)
        assertEquals(VulkanProbeStatus.AVAILABLE, parsed.status)
        assertEquals("Mali-G720 Immortalis MC12", parsed.deviceName)
        assertEquals("1.3.264", parsed.vulkanApiVersionLabel)
        assertTrue(parsed.supports16BitStorage)
        assertTrue(parsed.supportsFp16Compute)
        assertTrue(parsed.isUsableForInference)
        assertEquals(4915L, parsed.recommendedMaxOffloadMb)

        // Capability probe merges cleanly without crashing on Robolectric context
        val hwProbe = VulkanCapabilityProbe.probe(context) { localProbe }
        assertFalse(hwProbe.isUsableForInference)
        assertTrue(hwProbe.status == VulkanProbeStatus.UNSUPPORTED_DEVICE || hwProbe.status == VulkanProbeStatus.NATIVE_LIBRARY_MISSING)
    }

    @Test
    fun `vulkan backend safely rejects load when native binary missing instead of crashing`() = runBlocking {
        val backend = VulkanLlamaBackend()
        assertFalse(backend.capabilities.supportsGpuOffload)
        var thrown: EngineError.GpuBackendError? = null
        try {
            backend.loadModel(
                ModelConfig(
                    modelId = ModelId("m1"),
                    displayName = "Test",
                    filePath = "/tmp/nonexistent.gguf",
                    fileUri = "file:///tmp/nonexistent.gguf",
                    contextSize = 1024,
                    gpuLayers = 12
                )
            )
        } catch (e: EngineError.GpuBackendError) {
            thrown = e
        }
        assertNotNull(thrown)
        assertTrue(thrown!!.fallbackEligible)
    }

    @Test
    fun `resilient failover handles probe rejection, gpu load exception, pre-decode error, and cpu_only`() = runBlocking {
        val model = ModelConfig(
            modelId = ModelId("qwen.gguf"),
            displayName = "Qwen3-4B",
            filePath = "/tmp/qwen.gguf",
            fileUri = "file:///tmp/qwen.gguf",
            contextSize = 2048,
            threads = 4,
            gpuLayers = 24,
            totalLayers = 36
        )
        val genReq = GenerationRequest(
            modelId = ModelId("qwen.gguf"),
            systemPrompt = "Be concise",
            history = emptyList(),
            userPrompt = "Hello",
            modelFamilyHint = "qwen3"
        )

        // 1. Probe missing 16-bit storage capability -> falls back cleanly to CPU
        val cpu1 = FakeBackend(AcceleratorBackendType.CPU)
        val gpu1 = FakeBackend(AcceleratorBackendType.VULKAN_GPU)
        val badProbe = verifiedVulkanProbe().copy(
            status = VulkanProbeStatus.DRIVER_CAPABILITY_MISSING,
            supports16BitStorage = false,
            operatorSelfTestPassed = false,
            reason = "Driver lacks VK_KHR_16bit_storage"
        )
        val failover1 = ResilientFailoverBackend(cpu1, gpu1, probeProvider = { badProbe })
        failover1.loadModel(model)
        assertEquals(0, gpu1.loadCalls)
        assertEquals(1, cpu1.loadCalls)
        assertEquals(0, cpu1.lastLoadedConfig?.gpuLayers)
        assertTrue(failover1.runtimeStatus.value.fallbackOccurred)
        assertEquals(AcceleratorBackendType.CPU, failover1.runtimeStatus.value.activeBackend)
        assertTrue(failover1.runtimeStatus.value.fallbackReason!!.contains("16bit"))

        // 2. Probe passes, but Vulkan loadModel throws -> unloads GPU & loads CPU
        val cpu2 = FakeBackend(AcceleratorBackendType.CPU)
        val gpu2 = FakeBackend(AcceleratorBackendType.VULKAN_GPU, failOnLoad = EngineError.GpuBackendError("VRAM allocation OOM"))
        val failover2 = ResilientFailoverBackend(cpu2, gpu2, probeProvider = { verifiedVulkanProbe() })
        failover2.loadModel(model)
        assertEquals(1, gpu2.loadCalls)
        assertTrue(gpu2.unloadCalls >= 1)
        assertEquals(1, cpu2.loadCalls)
        assertTrue(failover2.runtimeStatus.value.fallbackOccurred)
        assertEquals(AcceleratorBackendType.CPU, failover2.runtimeStatus.value.activeBackend)

        // 3. Vulkan load succeeds, then fails pre-decode -> seamless CPU failover mid-request
        val cpu3 = FakeBackend(AcceleratorBackendType.CPU)
        val gpu3 = FakeBackend(AcceleratorBackendType.VULKAN_GPU, failPreDecode = EngineError.GpuBackendError("Shader pipeline error"))
        val failover3 = ResilientFailoverBackend(cpu3, gpu3, probeProvider = { verifiedVulkanProbe() })
        failover3.loadModel(model)
        assertEquals(AcceleratorBackendType.VULKAN_GPU, failover3.runtimeStatus.value.activeBackend)
        assertEquals(24, failover3.runtimeStatus.value.appliedGpuLayers)

        val events = failover3.generate(genReq).toList()
        val completed = events.filterIsInstance<GenerationEvent.Completed>().single()
        assertEquals("CPU_OK", completed.finalText)
        assertTrue(failover3.runtimeStatus.value.fallbackOccurred)
        assertEquals(AcceleratorBackendType.CPU, failover3.runtimeStatus.value.activeBackend)
        assertEquals(0, failover3.runtimeStatus.value.appliedGpuLayers)

        // 4. CPU_ONLY bypasses Vulkan probe & GPU load completely
        val cpu4 = FakeBackend(AcceleratorBackendType.CPU)
        val gpu4 = FakeBackend(AcceleratorBackendType.VULKAN_GPU)
        val failover4 = ResilientFailoverBackend(cpu4, gpu4, probeProvider = { verifiedVulkanProbe() })
        failover4.setPreference(AcceleratorPreference.CPU_ONLY)
        failover4.loadModel(model)
        assertEquals(0, gpu4.loadCalls)
        assertEquals(1, cpu4.loadCalls)
        assertFalse(failover4.runtimeStatus.value.fallbackOccurred)
    }

    @Test
    fun `orchestrator exposes gpu offload plan and runs seamless failover end-to-end`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempModels = createTempDirectory("vk_orch_models").toFile()
        val tempSessions = createTempDirectory("vk_orch_sessions").toFile()
        try {
            val dummyGguf = File(tempModels, "qwen3.gguf").apply { writeBytes(ByteArray(4096)) }
            val cpu = FakeBackend(AcceleratorBackendType.CPU)
            val gpu = FakeBackend(
                AcceleratorBackendType.VULKAN_GPU,
                failOnLoad = EngineError.GpuBackendError("Simulated driver compile fault")
            )
            val probe = verifiedVulkanProbe()
            val hw = HardwareCapabilityManager(context, vulkanNativeProbe = { probe })
            val rm = RuntimeResourceManager(hw)
            val orch = RuntimeOrchestrator(
                primaryBackend = cpu,
                hardwareManager = hw,
                resourceManager = rm,
                modelRegistry = LocalModelRegistry(tempModels),
                sessionManager = SessionCacheManager(tempSessions),
                vulkanBackend = gpu,
                vulkanProbeProvider = { probe }
            )
            try {
                orch.initialize()
                orch.updateAcceleratorConfig(
                    preference = AcceleratorPreference.PREFER_VULKAN,
                    autoGpuLayerOffload = false,
                    manualGpuLayers = 16
                )
                orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, 2048, 4)

                val snap = orch.snapshot.value
                assertNotNull(snap.gpuOffloadPlan)
                assertEquals(16, snap.gpuOffloadPlan?.effectiveLayers)
                assertTrue(snap.backendRuntimeStatus.fallbackOccurred)
                assertEquals(AcceleratorBackendType.CPU, snap.backendRuntimeStatus.activeBackend)
                assertTrue(snap.hardwareProfile?.openClStatusNote?.contains("Phase 2") == true)
            } finally {
                orch.shutdown()
            }
        } finally {
            tempModels.deleteRecursively()
            tempSessions.deleteRecursively()
        }
    }
}
