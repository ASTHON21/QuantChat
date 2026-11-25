package com.example

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.UnifiedCapabilitiesCard
import com.example.ui.theme.QuantChatTheme
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.BackendRuntimeStatus
import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.DeviceCapabilities
import com.quantchat.engine.core.DeviceCapabilitiesPolicy
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.EngineConfig
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.EngineVersionInfo
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.HardwareCapabilityProvider
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.InferenceThreadPriority
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.NpuInferenceBackend
import com.quantchat.engine.core.NpuNativeRuntimeProbe
import com.quantchat.engine.core.ResilientFailoverBackend
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.model.LocalModelRegistry
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1_4DeviceCapabilitiesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingBackend(
        private val backendType: AcceleratorBackendType
    ) : InferenceBackend {
        override val versionInfo = EngineVersionInfo(
            backendId = if (backendType == AcceleratorBackendType.VULKAN_GPU) "rec-vulkan" else "rec-cpu",
            engineName = "Recording",
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

        var loadCount = 0
        var lastRequest: GenerationRequest? = null

        override suspend fun initialize(config: EngineConfig) {}
        override suspend fun validateModelFile(fileUri: String) = true
        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            loadCount++
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
            lastRequest = request
            return flowOf(
                GenerationEvent.TokenDelta("OK", 1),
                GenerationEvent.Completed("OK", 10L, 1, 50f, false)
            )
        }
        override suspend fun cancel() {}
        override suspend fun unload() {
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }
        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int) =
            BenchmarkResult(100f, 30f, prefillTokens, decodeTokens, 50L, true, backendType.label)
        override suspend fun tokenize(text: String) = IntArray(4) { it }
        override suspend fun detokenize(tokens: IntArray) = ""
        override suspend fun embed(text: String) = floatArrayOf()
        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int) =
            SessionSaveResult(sessionId, path, maxTokens)
        override suspend fun loadSession(sessionId: SessionId, path: String) = 0
        override suspend fun formatChat(turns: List<ChatTurn>, template: String) = null
    }

    private class FakeCapabilityProvider(initial: DeviceCapabilities) : HardwareCapabilityProvider {
        private val _flow = MutableStateFlow(initial)
        override val deviceCapabilities: StateFlow<DeviceCapabilities> = _flow.asStateFlow()
        override fun getDeviceCapabilities(): DeviceCapabilities = _flow.value
        fun update(next: DeviceCapabilities) {
            _flow.value = next
        }
    }

    private fun usableVulkanProbe() = VulkanProbeResult(
        status = VulkanProbeStatus.AVAILABLE,
        nativeLibraryLoaded = true,
        systemVulkanSupported = true,
        systemVulkanVersion = "1.3.0",
        deviceName = "Adreno (TM) 750",
        vendorLabel = "Qualcomm (Adreno)",
        vulkanApiVersionLabel = "1.3.0",
        supports16BitStorage = true,
        supportsFp16Compute = true,
        deviceLocalHeapMb = 6144L,
        recommendedMaxOffloadMb = 4096L,
        bufferTransferSelfTestPassed = true,
        operatorSelfTestPassed = true,
        selfTestSummary = "OK"
    )

    private fun sampleModel(gpuLayers: Int = 24) = ModelConfig(
        modelId = ModelId("qwen3.gguf"),
        displayName = "Qwen3-4B",
        filePath = "/tmp/qwen3.gguf",
        fileUri = "file:///tmp/qwen3.gguf",
        contextSize = 2048,
        threads = 4,
        gpuLayers = gpuLayers,
        totalLayers = 32
    )

    @Test
    fun `hardware capability manager provides DeviceCapabilities via StateFlow and synchronous getter`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024L * 1024L * 1024L
            availMem = 3072L * 1024L * 1024L
            lowMemory = false
        })
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_LIGHT)

        val hw = HardwareCapabilityManager(
            context = context,
            vulkanNativeProbe = { usableVulkanProbe() },
            npuNativeProbe = {
                NpuNativeRuntimeProbe(
                    nativeProbeExecuted = true,
                    qnnHtpAccessible = true,
                    cdspRpcAccessible = true
                )
            }
        )

        val provider: HardwareCapabilityProvider = hw
        val initialSync = provider.getDeviceCapabilities()
        assertTrue(initialSync.cpuCores >= 1)
        assertEquals(3072L * 1024L * 1024L, initialSync.availableMemoryBytes)
        assertEquals(DeviceCapabilities.THERMAL_STATUS_LIGHT, initialSync.thermalStatus)
        assertFalse(initialSync.hasVulkanGpu)
        assertEquals(initialSync, provider.deviceCapabilities.value)

        hw.probeVulkanCapabilities()
        val afterVk = provider.deviceCapabilities.value
        assertTrue(afterVk.gpuBackends.contains(DeviceCapabilities.GPU_BACKEND_VULKAN))
        assertTrue(afterVk.hasVulkanGpu)

        val prevManufacturer = Build.SOC_MANUFACTURER
        val prevModel = Build.SOC_MODEL
        val prevHardware = Build.HARDWARE
        val prevAbis = Build.SUPPORTED_ABIS
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "SOC_MANUFACTURER", "QTI")
            ReflectionHelpers.setStaticField(Build::class.java, "SOC_MODEL", "SM8650")
            ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "qcom")
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))

            hw.probeNpuCapabilities()
            val afterNpu = provider.getDeviceCapabilities()
            assertTrue(afterNpu.npuAvailable)
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "SOC_MANUFACTURER", prevManufacturer)
            ReflectionHelpers.setStaticField(Build::class.java, "SOC_MODEL", prevModel)
            ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", prevHardware)
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", prevAbis)
        }
    }

    @Test
    fun `resilient failover backend enforces DeviceCapabilities GPU eligibility and low-memory or severe-thermal CPU fallback`() = runBlocking {
        val cpuBackend = RecordingBackend(AcceleratorBackendType.CPU)
        val gpuBackend = RecordingBackend(AcceleratorBackendType.VULKAN_GPU)

        val capProvider = FakeCapabilityProvider(
            DeviceCapabilities(
                cpuCores = 8,
                cpuFeatures = setOf("NEON", "DOTPROD"),
                gpuBackends = emptySet(), // No Vulkan in DeviceCapabilities -> blocked even if probe passed
                npuAvailable = false,
                availableMemoryBytes = 4096L * 1024L * 1024L,
                thermalStatus = DeviceCapabilities.THERMAL_STATUS_NOMINAL
            )
        )

        val failover = ResilientFailoverBackend(
            fallbackCpuBackend = cpuBackend,
            primaryGpuBackend = gpuBackend,
            probeProvider = { usableVulkanProbe() },
            capabilityProvider = capProvider
        )
        failover.initialize(EngineConfig())

        // 1. Missing VULKAN in DeviceCapabilities -> CPU Policy Fallback
        failover.loadModel(sampleModel(gpuLayers = 20))
        assertEquals(0, gpuBackend.loadCount)
        assertEquals(1, cpuBackend.loadCount)
        assertEquals(AcceleratorBackendType.CPU, failover.activeBackendType())
        assertTrue(failover.runtimeStatus.value.fallbackOccurred)

        // 2. Low available memory (< 384MB) -> CPU Policy Fallback
        capProvider.update(
            DeviceCapabilities(
                cpuCores = 8,
                cpuFeatures = setOf("NEON"),
                gpuBackends = setOf(DeviceCapabilities.GPU_BACKEND_VULKAN),
                npuAvailable = false,
                availableMemoryBytes = 256L * 1024L * 1024L,
                thermalStatus = DeviceCapabilities.THERMAL_STATUS_NOMINAL
            )
        )
        failover.loadModel(sampleModel(gpuLayers = 20))
        assertEquals(0, gpuBackend.loadCount)
        assertEquals(2, cpuBackend.loadCount)
        assertTrue(failover.runtimeStatus.value.fallbackReason?.contains("Low available memory") == true)

        // 3. Severe thermal (>= 3) -> CPU Thermal Guard fallback
        capProvider.update(
            DeviceCapabilities(
                cpuCores = 8,
                cpuFeatures = setOf("NEON"),
                gpuBackends = setOf(DeviceCapabilities.GPU_BACKEND_VULKAN),
                npuAvailable = false,
                availableMemoryBytes = 4096L * 1024L * 1024L,
                thermalStatus = DeviceCapabilities.THERMAL_STATUS_SEVERE
            )
        )
        failover.loadModel(sampleModel(gpuLayers = 20))
        assertEquals(0, gpuBackend.loadCount)
        assertEquals(3, cpuBackend.loadCount)
        assertEquals("CPU Thermal Guard", failover.runtimeStatus.value.statusSummary)

        // 4. Nominal capabilities -> GPU load succeeds
        capProvider.update(
            DeviceCapabilities(
                cpuCores = 8,
                cpuFeatures = setOf("NEON", "DOTPROD"),
                gpuBackends = setOf(DeviceCapabilities.GPU_BACKEND_VULKAN),
                npuAvailable = true,
                availableMemoryBytes = 4096L * 1024L * 1024L,
                thermalStatus = DeviceCapabilities.THERMAL_STATUS_NOMINAL
            )
        )
        failover.loadModel(sampleModel(gpuLayers = 24))
        assertEquals(1, gpuBackend.loadCount)
        assertEquals(AcceleratorBackendType.VULKAN_GPU, failover.activeBackendType())
        assertFalse(failover.runtimeStatus.value.fallbackOccurred)
    }

    @Test
    fun `resilient failover backend applies automatic thermal thread downshift during generate`() = runBlocking {
        val cpuBackend = RecordingBackend(AcceleratorBackendType.CPU)
        val capProvider = FakeCapabilityProvider(
            DeviceCapabilities(
                cpuCores = 8,
                cpuFeatures = setOf("NEON", "DOTPROD"),
                gpuBackends = emptySet(),
                npuAvailable = false,
                availableMemoryBytes = 4096L * 1024L * 1024L,
                thermalStatus = DeviceCapabilities.THERMAL_STATUS_MODERATE
            )
        )
        val failover = ResilientFailoverBackend(
            fallbackCpuBackend = cpuBackend,
            capabilityProvider = capProvider
        )
        failover.initialize(EngineConfig())
        failover.setPreference(AcceleratorPreference.CPU_ONLY)
        failover.loadModel(sampleModel(gpuLayers = 0))

        val events = failover.generate(
            GenerationRequest(
                modelId = ModelId("qwen3.gguf"),
                sessionId = SessionId("s1"),
                systemPrompt = "You are helpful.",
                history = emptyList(),
                userPrompt = "hello",
                modelFamilyHint = "qwen3",
                threadsOverride = 8,
                threadPriority = InferenceThreadPriority.FAVORABLE
            )
        ).toList()

        assertTrue(events.any { it is GenerationEvent.Completed })
        val seen = cpuBackend.lastRequest
        assertNotNull(seen)
        assertEquals(4, seen!!.threadsOverride)
        assertEquals(InferenceThreadPriority.BACKGROUND, seen.threadPriority)
        assertTrue(failover.runtimeStatus.value.statusSummary.contains("Thermal Guard"))

        // Verify policy directly at CRITICAL thermal status -> 1 thread
        val criticalReq = DeviceCapabilitiesPolicy.applyThermalDownshift(
            request = GenerationRequest(
                modelId = ModelId("qwen3.gguf"),
                sessionId = SessionId("s2"),
                systemPrompt = "",
                history = emptyList(),
                userPrompt = "hi",
                modelFamilyHint = "qwen3",
                threadsOverride = 6
            ),
            capabilities = capProvider.getDeviceCapabilities().copy(thermalStatus = DeviceCapabilities.THERMAL_STATUS_CRITICAL)
        )
        assertEquals(1, criticalReq.threadsOverride)
        assertEquals(InferenceThreadPriority.BACKGROUND, criticalReq.threadPriority)
    }

    @Test
    fun `runtime orchestrator keeps OrchestratorSnapshot deviceCapabilities synced across lifecycle`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempModels = createTempDirectory("orch_caps_models").toFile()
        val tempSessions = createTempDirectory("orch_caps_sessions").toFile()
        try {
            val dummyGguf = File(tempModels, "qwen3.gguf").apply { writeBytes(ByteArray(2048)) }
            val hw = HardwareCapabilityManager(
                context = context,
                vulkanNativeProbe = { usableVulkanProbe() },
                npuNativeProbe = { NpuNativeRuntimeProbe(nativeProbeExecuted = true, qnnHtpAccessible = true, cdspRpcAccessible = true) }
            )
            val orch = RuntimeOrchestrator(
                primaryBackend = RecordingBackend(AcceleratorBackendType.CPU),
                hardwareManager = hw,
                resourceManager = RuntimeResourceManager(hw),
                modelRegistry = LocalModelRegistry(tempModels),
                sessionManager = SessionCacheManager(tempSessions),
                vulkanBackend = RecordingBackend(AcceleratorBackendType.VULKAN_GPU),
                npuBackend = NpuInferenceBackend()
            )
            val prevManufacturer = Build.SOC_MANUFACTURER
            val prevModel = Build.SOC_MODEL
            val prevHardware = Build.HARDWARE
            val prevAbis = Build.SUPPORTED_ABIS
            try {
                ReflectionHelpers.setStaticField(Build::class.java, "SOC_MANUFACTURER", "QTI")
                ReflectionHelpers.setStaticField(Build::class.java, "SOC_MODEL", "SM8650")
                ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "qcom")
                ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))

                orch.initialize()
                val caps = orch.snapshot.value.deviceCapabilities
                assertNotNull(caps)
                assertTrue(caps!!.hasVulkanGpu)
                assertTrue(caps.npuAvailable)

                orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, 2048, 4)
                assertNotNull(orch.snapshot.value.deviceCapabilities)
            } finally {
                ReflectionHelpers.setStaticField(Build::class.java, "SOC_MANUFACTURER", prevManufacturer)
                ReflectionHelpers.setStaticField(Build::class.java, "SOC_MODEL", prevModel)
                ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", prevHardware)
                ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", prevAbis)
                orch.shutdown()
            }
        } finally {
            tempModels.deleteRecursively()
            tempSessions.deleteRecursively()
        }
    }

    @Test
    fun `unified capabilities card renders all 6 DeviceCapabilities fields and handles refresh click`() {
        var refreshClicks = 0
        val sampleCaps = DeviceCapabilities(
            cpuCores = 8,
            cpuFeatures = setOf("NEON", "DOTPROD", "I8MM"),
            gpuBackends = setOf(DeviceCapabilities.GPU_BACKEND_VULKAN),
            npuAvailable = true,
            availableMemoryBytes = 2048L * 1024L * 1024L,
            thermalStatus = DeviceCapabilities.THERMAL_STATUS_MODERATE
        )

        composeRule.setContent {
            QuantChatTheme {
                UnifiedCapabilitiesCard(
                    deviceCapabilities = sampleCaps,
                    backendStatus = BackendRuntimeStatus(
                        activeBackend = AcceleratorBackendType.VULKAN_GPU,
                        requestedPreference = AcceleratorPreference.AUTO,
                        statusSummary = "GPU · VK 24/32L"
                    ),
                    acceleratorPreference = AcceleratorPreference.AUTO,
                    onRefreshCapabilities = { refreshClicks++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("unified_device_capabilities_card").assertIsDisplayed()
        composeRule.onNodeWithText("8 cores").assertIsDisplayed()
        composeRule.onNodeWithText("DOTPROD · I8MM · NEON").assertIsDisplayed()
        composeRule.onNodeWithText("VULKAN").assertIsDisplayed()
        composeRule.onNodeWithText("true (Vendor Runtime Ready)").assertIsDisplayed()
        composeRule.onNodeWithText("Moderate (2)").assertIsDisplayed()
        composeRule.onNodeWithText("SCHEDULER · THERMAL GUARD ACTIVE").assertIsDisplayed()

        composeRule.onNodeWithTag("refresh_device_capabilities_button").performClick()
        assertEquals(1, refreshClicks)
    }
}
