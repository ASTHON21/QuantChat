package com.example

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.ChatThermalGuardBanner
import com.example.ui.components.ThermalStatusMatrixCard
import com.example.ui.theme.QuantChatTheme
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.DeviceCapabilities
import com.quantchat.engine.core.DeviceCapabilitiesPolicy
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.EngineConfig
import com.quantchat.engine.core.EngineError
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.EngineVersionInfo
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.HardwareCapabilityProvider
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.ResilientFailoverBackend
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.engine.core.ThermalInferenceAction
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.ThermalSeverity
import com.quantchat.model.LocalModelRegistry
import com.quantchat.resource.GpuOffloadPlanner
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.resource.ThermalRuntimeActionState
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2_2ThermalMonitoringTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeProvider(initial: DeviceCapabilities) : HardwareCapabilityProvider {
        private val _flow = MutableStateFlow(initial)
        override val deviceCapabilities: StateFlow<DeviceCapabilities> = _flow.asStateFlow()
        override fun getDeviceCapabilities(): DeviceCapabilities = _flow.value
        fun set(next: DeviceCapabilities) {
            _flow.value = next
        }
    }

    private class TrackingBackend : InferenceBackend {
        override val versionInfo = EngineVersionInfo("fake-cpu", "Fake", "1.0", "arm64-v8a")
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

        var unloadCalls = 0
        var cancelCalls = 0
        var onTokenEmitHook: ((Int) -> Unit)? = null

        override suspend fun initialize(config: EngineConfig) {}
        override suspend fun validateModelFile(fileUri: String) = true
        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }

        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
            for (i in 1..4) {
                onTokenEmitHook?.invoke(i)
                emit(GenerationEvent.TokenDelta("tok$i ", i))
            }
            emit(GenerationEvent.Completed("tok1 tok2 tok3 tok4", 20L, 4, 100f, false))
        }

        override suspend fun cancel() {
            cancelCalls++
        }

        override suspend fun unload() {
            unloadCalls++
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }

        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int) =
            BenchmarkResult(120f, 35f, prefillTokens, decodeTokens, 40L, true, "CPU")

        override suspend fun tokenize(text: String) = IntArray(4) { it }
        override suspend fun detokenize(tokens: IntArray) = ""
        override suspend fun embed(text: String) = floatArrayOf()
        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int) =
            SessionSaveResult(sessionId, path, maxTokens)
        override suspend fun loadSession(sessionId: SessionId, path: String) = 0
        override suspend fun formatChat(turns: List<ChatTurn>, template: String) = null
    }

    @Test
    fun `four-tier thermal policy and GpuOffloadPlanner enforce expected actions across all PowerManager tiers`() {
        val baseCaps = DeviceCapabilities(
            cpuCores = 8,
            cpuFeatures = setOf("NEON", "DOTPROD"),
            gpuBackends = setOf(DeviceCapabilities.GPU_BACKEND_VULKAN),
            npuAvailable = false,
            availableMemoryBytes = 6144L * 1024L * 1024L,
            thermalStatus = DeviceCapabilities.THERMAL_STATUS_NOMINAL
        )
        val probe = VulkanProbeResult(
            status = VulkanProbeStatus.AVAILABLE,
            nativeLibraryLoaded = true,
            systemVulkanSupported = true,
            deviceName = "Adreno 750",
            recommendedMaxOffloadMb = 4096L,
            bufferTransferSelfTestPassed = true,
            operatorSelfTestPassed = true
        )

        val light = DeviceCapabilitiesPolicy.evaluateThermalPolicy(
            baseCaps.copy(thermalStatus = DeviceCapabilities.THERMAL_STATUS_LIGHT),
            requestedThreads = 8
        )
        assertEquals(ThermalInferenceAction.NORMAL, light.action)
        assertEquals(8, light.maxCpuThreads)
        assertEquals(1.0f, light.gpuLoadScaleFactor)
        assertFalse(light.pauseNonEssentialTasks)
        assertFalse(light.stopInferenceAndRelease)

        val moderate = DeviceCapabilitiesPolicy.evaluateThermalPolicy(
            baseCaps.copy(thermalStatus = DeviceCapabilities.THERMAL_STATUS_MODERATE),
            requestedThreads = 8
        )
        assertEquals(ThermalInferenceAction.REDUCE_THREADS_AND_GPU, moderate.action)
        assertEquals(4, moderate.maxCpuThreads)
        assertEquals(0.5f, moderate.gpuLoadScaleFactor)
        assertFalse(moderate.pauseNonEssentialTasks)

        val normalPlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = 1500L * 1024L * 1024L,
            header = null,
            contextSize = 2048,
            availableRamMb = 6144L,
            lowMemory = false,
            vulkanProbe = probe,
            preference = AcceleratorPreference.AUTO,
            autoOffload = true,
            manualLayersOverride = 0,
            thermalSeverity = ThermalSeverity.NOMINAL
        )
        val moderatePlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = 1500L * 1024L * 1024L,
            header = null,
            contextSize = 2048,
            availableRamMb = 6144L,
            lowMemory = false,
            vulkanProbe = probe,
            preference = AcceleratorPreference.AUTO,
            autoOffload = true,
            manualLayersOverride = 0,
            thermalSeverity = ThermalSeverity.MODERATE
        )
        assertTrue(normalPlan.effectiveLayers > 4)
        assertEquals(normalPlan.effectiveLayers / 2, moderatePlan.effectiveLayers)
        assertTrue(moderatePlan.policySummary.contains("Thermal Moderate"))

        val severe = DeviceCapabilitiesPolicy.evaluateThermalPolicy(
            baseCaps.copy(thermalStatus = DeviceCapabilities.THERMAL_STATUS_SEVERE),
            requestedThreads = 8
        )
        assertEquals(ThermalInferenceAction.THROTTLE_SPEED_AND_PAUSE_NON_ESSENTIAL, severe.action)
        assertEquals(2, severe.maxCpuThreads)
        assertEquals(0f, severe.gpuLoadScaleFactor)
        assertTrue(severe.tokenPacingDelayMs > 0L)
        assertTrue(severe.pauseNonEssentialTasks)
        assertFalse(severe.stopInferenceAndRelease)

        val severePlan = GpuOffloadPlanner.planOffload(
            fileSizeBytes = 1500L * 1024L * 1024L,
            header = null,
            contextSize = 2048,
            availableRamMb = 6144L,
            lowMemory = false,
            vulkanProbe = probe,
            preference = AcceleratorPreference.AUTO,
            autoOffload = true,
            manualLayersOverride = 0,
            thermalSeverity = ThermalSeverity.SEVERE
        )
        assertEquals(0, severePlan.effectiveLayers)

        val critical = DeviceCapabilitiesPolicy.evaluateThermalPolicy(
            baseCaps.copy(thermalStatus = DeviceCapabilities.THERMAL_STATUS_CRITICAL),
            requestedThreads = 8
        )
        assertEquals(ThermalInferenceAction.STOP_AND_RELEASE, critical.action)
        assertTrue(critical.pauseNonEssentialTasks)
        assertTrue(critical.stopInferenceAndRelease)
    }

    @Test
    fun `resilient failover backend paces tokens at SEVERE and halts with ThermalShutdown at CRITICAL`() = runBlocking {
        val backend = TrackingBackend()
        val capProvider = FakeProvider(
            DeviceCapabilities(
                cpuCores = 8,
                cpuFeatures = setOf("NEON"),
                gpuBackends = emptySet(),
                npuAvailable = false,
                availableMemoryBytes = 4096L * 1024L * 1024L,
                thermalStatus = DeviceCapabilities.THERMAL_STATUS_SEVERE
            )
        )
        val failover = ResilientFailoverBackend(
            fallbackCpuBackend = backend,
            capabilityProvider = capProvider,
            severeThermalPacingMs = 20L
        )
        failover.initialize(EngineConfig())
        failover.loadModel(
            ModelConfig(
                modelId = ModelId("m1"),
                displayName = "M1",
                filePath = "/tmp/m1.gguf",
                fileUri = "file:///tmp/m1.gguf",
                contextSize = 2048,
                threads = 8
            )
        )

        val t0 = System.currentTimeMillis()
        val severeEvents = failover.generate(
            GenerationRequest(
                modelId = ModelId("m1"),
                systemPrompt = "",
                history = emptyList(),
                userPrompt = "hi",
                modelFamilyHint = "qwen3"
            )
        ).toList()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue(severeEvents.any { it is GenerationEvent.Completed })
        assertTrue("Expected pacing delay >= 60ms across 4 tokens, got ${elapsed}ms", elapsed >= 60L)
        assertTrue(failover.runtimeStatus.value.statusSummary.contains("Paced"))

        // Escalate to CRITICAL mid-stream after token 2 -> cancel + ThermalShutdown
        capProvider.set(capProvider.getDeviceCapabilities().copy(thermalStatus = DeviceCapabilities.THERMAL_STATUS_SEVERE))
        backend.onTokenEmitHook = { idx ->
            if (idx == 2) {
                capProvider.set(capProvider.getDeviceCapabilities().copy(thermalStatus = DeviceCapabilities.THERMAL_STATUS_CRITICAL))
            }
        }
        var caught: EngineError.ThermalShutdown? = null
        try {
            failover.generate(
                GenerationRequest(
                    modelId = ModelId("m1"),
                    systemPrompt = "",
                    history = emptyList(),
                    userPrompt = "hi",
                    modelFamilyHint = "qwen3"
                )
            ).toList()
        } catch (e: EngineError.ThermalShutdown) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(backend.cancelCalls >= 1)
    }

    @Test
    fun `runtime orchestrator pauses non-essential tasks at SEVERE and safely unloads active model at CRITICAL`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempModels = createTempDirectory("thermal_models").toFile()
        val tempSessions = createTempDirectory("thermal_sessions").toFile()
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply {
                totalMem = 8L * 1024L * 1024L * 1024L
                availMem = 4096L * 1024L * 1024L
                lowMemory = false
            })
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            shadowOf(pm).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)

            val dummyGguf = File(tempModels, "qwen3.gguf").apply { writeBytes(ByteArray(2048)) }
            val backend = TrackingBackend()
            val hw = HardwareCapabilityManager(context)
            val orch = RuntimeOrchestrator(
                primaryBackend = backend,
                hardwareManager = hw,
                resourceManager = RuntimeResourceManager(hw),
                modelRegistry = LocalModelRegistry(tempModels),
                sessionManager = SessionCacheManager(tempSessions)
            )
            try {
                orch.initialize()
                orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, 2048, 4)

                // Transition to SEVERE -> non-essential tasks paused, benchmark & calibration blocked
                hw.onPlatformThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE)
                delay(50L)
                assertTrue(orch.snapshot.value.thermalState.nonEssentialTasksPaused)
                assertEquals(
                    ThermalInferenceAction.THROTTLE_SPEED_AND_PAUSE_NON_ESSENTIAL,
                    orch.snapshot.value.thermalState.action
                )

                var benchBlocked = false
                try {
                    orch.runBenchmark()
                } catch (e: EngineError.GenerationFailed) {
                    benchBlocked = e.message.contains("Thermal Guard")
                }
                assertTrue(benchBlocked)

                var calBlocked = false
                try {
                    orch.calibrateOptimalThreads()
                } catch (e: EngineError.GenerationFailed) {
                    calBlocked = e.message.contains("Thermal Guard")
                }
                assertTrue(calBlocked)

                // Transition to CRITICAL -> auto-cancels & unloads active model safely
                hw.onPlatformThermalStatusChanged(PowerManager.THERMAL_STATUS_CRITICAL)
                for (attempt in 0..20) {
                    if (backend.unloadCalls >= 1 && orch.snapshot.value.thermalState.emergencyReleaseCount >= 1) break
                    delay(25L)
                }
                assertTrue(backend.unloadCalls >= 1)
                assertTrue(orch.snapshot.value.thermalState.inferenceBlocked)
                assertTrue(orch.snapshot.value.thermalState.emergencyReleaseCount >= 1)

                var loadBlocked = false
                try {
                    orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, 2048, 4)
                } catch (_: EngineError.ThermalShutdown) {
                    loadBlocked = true
                }
                assertTrue(loadBlocked)
            } finally {
                orch.shutdown()
            }
        } finally {
            tempModels.deleteRecursively()
            tempSessions.deleteRecursively()
        }
    }

    @Test
    fun `thermal monitor card and chat guard banner render 4-tier matrix and active severe alert`() {
        val severeState = ThermalRuntimeActionState(
            severity = ThermalSeverity.SEVERE,
            action = ThermalInferenceAction.THROTTLE_SPEED_AND_PAUSE_NON_ESSENTIAL,
            autoProtectionEnabled = true,
            threadCap = 2,
            gpuLoadScaleFactor = 0f,
            tokenPacingDelayMs = 35L,
            nonEssentialTasksPaused = true,
            inferenceBlocked = false,
            emergencyReleaseCount = 0,
            lastThermalEventNote = "SEVERE thermal status: throttling token speed (35ms pacing), 0 GPU layers, pausing downloads/benchmarks.",
            thermalHeadroomForecast = 0.84f
        )

        composeRule.setContent {
            QuantChatTheme {
                ThermalStatusMatrixCard(thermalState = severeState)
                ChatThermalGuardBanner(thermalState = severeState)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("thermal_governance_card").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_thermal_guard_banner").assertIsDisplayed()
        composeRule.onNodeWithText("NONE / LIGHT").assertIsDisplayed()
        composeRule.onNodeWithText("MODERATE").assertIsDisplayed()
        composeRule.onNodeWithText("▶ SEVERE").assertIsDisplayed()
        composeRule.onNodeWithText("CRITICAL").assertIsDisplayed()
        composeRule.onNodeWithText("Pacing 35ms/tok · Downloads/Bench paused").assertIsDisplayed()
        composeRule.onNodeWithText("SEVERE TEMP · SPEED THROTTLED & NON-ESSENTIAL PAUSED").assertIsDisplayed()
    }
}
