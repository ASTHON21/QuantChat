package com.example

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.LocalWeightFile
import com.example.ui.components.MemoryAdmissionBlockedDialog
import com.example.ui.components.MemoryAdmissionBreakdownStrip
import com.example.ui.theme.QuantChatTheme
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
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.hardware.ProcessMemoryProbe
import com.quantchat.hardware.ProcessMemoryTelemetry
import com.quantchat.hardware.ThermalSeverity
import com.quantchat.model.GgufHeaderMetadata
import com.quantchat.model.LocalModelRegistry
import com.quantchat.resource.AdmissionDowngradeSuggestion
import com.quantchat.resource.AdmissionVerdict
import com.quantchat.resource.MemoryAdmissionPlanner
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class Phase2_1MemoryAdmissionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class CountingFakeBackend : InferenceBackend {
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
        var loadCalls = 0

        override suspend fun initialize(config: EngineConfig) {}
        override suspend fun validateModelFile(fileUri: String) = true
        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            loadCalls++
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flowOf(
            GenerationEvent.Completed("ok", 10L, 1, 50f, false)
        )
        override suspend fun cancel() {}
        override suspend fun unload() {
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }
        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int) =
            BenchmarkResult(100f, 30f, prefillTokens, decodeTokens, 50L, true, "CPU")
        override suspend fun tokenize(text: String) = IntArray(4) { it }
        override suspend fun detokenize(tokens: IntArray) = ""
        override suspend fun embed(text: String) = floatArrayOf()
        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int) =
            SessionSaveResult(sessionId, path, maxTokens)
        override suspend fun loadSession(sessionId: SessionId, path: String) = 0
        override suspend fun formatChat(turns: List<ChatTurn>, template: String) = null
    }

    private fun qwen3Header() = GgufHeaderMetadata(
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

    @Test
    fun `process memory probe parses VmRSS from proc status and samples ActivityManager MemoryInfo`() {
        val statusSample = """
            Name:   com.aistudio.quantchat
            VmSize:  2457600 kB
            VmRSS:    419840 kB
            VmData:   180000 kB
        """.trimIndent()
        assertEquals(410L, ProcessMemoryProbe.parseVmRssMbFromProcStatus(statusSample))
        assertEquals(0L, ProcessMemoryProbe.parseVmRssMbFromProcStatus(null))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = 12L * 1024L * 1024L * 1024L
            availMem = 2048L * 1024L * 1024L
            threshold = 384L * 1024L * 1024L
            lowMemory = false
        })

        val sampled = ProcessMemoryProbe.sample(context, am)
        assertEquals(2048L, sampled.systemAvailMemMb)
        assertEquals(12288L, sampled.systemTotalMemMb)
        assertEquals(384L, sampled.systemThresholdMb)
    }

    @Test
    fun `five term memory admission planner blocks large load despite high nominal RAM and suggests safe context downgrade`() {
        val modelBytes = 2300L * 1024L * 1024L // ~2.3GB GGUF
        val header = qwen3Header()

        // Device advertises 12GB nominal RAM, but only 3400MB live availMem with 350MB LMK threshold & 300MB RSS
        val proc = ProcessMemoryTelemetry(
            processTotalPssMb = 300L,
            processVmRssMb = 290L,
            nativeHeapAllocatedMb = 140L,
            memoryClassMb = 256,
            largeMemoryClassMb = 512,
            systemThresholdMb = 350L,
            systemAvailMemMb = 3400L,
            systemTotalMemMb = 12288L,
            lowMemoryFlag = false
        )
        val tightMetrics = LiveDeviceMetrics(
            totalRamMb = 12288L,
            availableRamMb = 3400L,
            lowMemory = false,
            thermalSeverity = ThermalSeverity.NOMINAL,
            processMemory = proc
        )

        // With n_ctx = 8192 + GPU offload, M_KV + M_weights + M_compute + M_runtime + M_reserve exceeds 3400MB
        val blocked = MemoryAdmissionPlanner.evaluate(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 8192,
            batchSize = 512,
            plannedGpuLayers = 36,
            totalLayers = 36,
            metrics = tightMetrics
        )
        assertEquals(AdmissionVerdict.BLOCKED, blocked.verdict)
        val bd = blocked.breakdown!!
        assertEquals(
            bd.weightsMb + bd.kvCacheMb + bd.computeBufferMb + bd.runtimeMb + bd.reserveMb,
            bd.totalRequiredMb
        )
        assertTrue(bd.reserveMb >= 350L + 128L)
        assertTrue(bd.runtimeMb > 96L)
        assertTrue(bd.totalRequiredMb > 3400L)
        assertNotNull(blocked.downgradeSuggestion)
        assertTrue(blocked.downgradeSuggestion!!.targetContextSize < 8192)
        assertTrue(blocked.downgradeSuggestion!!.projectedTotalMb <= 3400L)

        // Compare CPU-only vs GPU offload staging cost at same context
        val cpuBreakdown = MemoryAdmissionPlanner.computeBreakdown(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            plannedGpuLayers = 0,
            totalLayers = 36,
            metrics = tightMetrics
        )
        val gpuBreakdown = MemoryAdmissionPlanner.computeBreakdown(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            plannedGpuLayers = 36,
            totalLayers = 36,
            metrics = tightMetrics
        )
        assertTrue(gpuBreakdown.weightsMb > cpuBreakdown.weightsMb)
        assertTrue(gpuBreakdown.computeBufferMb > cpuBreakdown.computeBufferMb)

        // Plenty of free RAM (7000MB) -> ALLOWED
        val allowed = MemoryAdmissionPlanner.evaluate(
            fileSizeBytes = modelBytes,
            header = header,
            contextSize = 2048,
            plannedGpuLayers = 0,
            totalLayers = 36,
            metrics = tightMetrics.copy(availableRamMb = 7000L)
        )
        assertEquals(AdmissionVerdict.ALLOWED, allowed.verdict)
        assertTrue(allowed.breakdown!!.safeHeadroomMb > 256L)
    }

    @Test
    fun `runtime orchestrator enforces BLOCKED gate before invoking native backend loadModel`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempModels = createTempDirectory("adm_orch_models").toFile()
        val tempSessions = createTempDirectory("adm_orch_sessions").toFile()
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply {
                totalMem = 8L * 1024L * 1024L * 1024L
                availMem = 900L * 1024L * 1024L // Tight: below n_ctx=8192 M_total
                threshold = 300L * 1024L * 1024L
                lowMemory = false
            })

            val dummyGguf = File(tempModels, "qwen3.gguf").apply { writeBytes(ByteArray(4096)) }
            val fakeCpu = CountingFakeBackend()
            val hw = HardwareCapabilityManager(
                context = context,
                vulkanNativeProbe = {
                    VulkanProbeResult(VulkanProbeStatus.NATIVE_LIBRARY_MISSING, reason = "CPU test")
                },
                processMemorySampler = { _, info ->
                    ProcessMemoryTelemetry(
                        processTotalPssMb = 180L,
                        processVmRssMb = 190L,
                        nativeHeapAllocatedMb = 90L,
                        systemThresholdMb = info.threshold / (1024L * 1024L),
                        systemAvailMemMb = info.availMem / (1024L * 1024L),
                        systemTotalMemMb = info.totalMem / (1024L * 1024L),
                        lowMemoryFlag = info.lowMemory
                    )
                }
            )
            val orch = RuntimeOrchestrator(
                primaryBackend = fakeCpu,
                hardwareManager = hw,
                resourceManager = RuntimeResourceManager(hw),
                modelRegistry = LocalModelRegistry(tempModels),
                sessionManager = SessionCacheManager(tempSessions)
            )
            try {
                orch.initialize()

                var caught: EngineError.InsufficientMemory? = null
                try {
                    orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, contextSize = 8192, threads = 4)
                } catch (e: EngineError.InsufficientMemory) {
                    caught = e
                }

                assertNotNull(caught)
                assertEquals(0, fakeCpu.loadCalls) // Native loadModel never invoked when BLOCKED!
                val snapAdmission = orch.snapshot.value.lastAdmission
                assertNotNull(snapAdmission)
                assertEquals(AdmissionVerdict.BLOCKED, snapAdmission!!.verdict)
                assertNotNull(snapAdmission.downgradeSuggestion)

                // Retry with downgraded context (512) -> fits inside 900MB with tight headroom (WARNING, load permitted)
                val safeCtx = snapAdmission.downgradeSuggestion!!.targetContextSize
                orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, contextSize = safeCtx, threads = 4)
                assertEquals(1, fakeCpu.loadCalls)
                assertEquals(AdmissionVerdict.WARNING, orch.snapshot.value.lastAdmission?.verdict)
            } finally {
                orch.shutdown()
            }
        } finally {
            tempModels.deleteRecursively()
            tempSessions.deleteRecursively()
        }
    }

    @Test
    fun `memory admission strip and blocked dialog render 5-term breakdown and handle downgrade click`() {
        val sampleFile = LocalWeightFile(
            diskFileName = "qwen3.gguf",
            displayFileName = "Qwen3-4B-Q4_K_M.gguf",
            repoId = "Qwen/Qwen3",
            sizeBytes = 2400L * 1024L * 1024L,
            isPartial = false
        )
        val blockedAssessment = MemoryAdmissionPlanner.evaluate(
            fileSizeBytes = sampleFile.sizeBytes,
            header = qwen3Header(),
            contextSize = 8192,
            plannedGpuLayers = 32,
            totalLayers = 36,
            metrics = LiveDeviceMetrics(
                totalRamMb = 12288L,
                availableRamMb = 3500L,
                lowMemory = false,
                thermalSeverity = ThermalSeverity.NOMINAL,
                processMemory = ProcessMemoryTelemetry(
                    processTotalPssMb = 280L,
                    processVmRssMb = 295L,
                    systemThresholdMb = 350L,
                    systemAvailMemMb = 3500L,
                    systemTotalMemMb = 12288L
                )
            )
        )

        var appliedSuggestion: AdmissionDowngradeSuggestion? = null
        composeRule.setContent {
            QuantChatTheme {
                MemoryAdmissionBreakdownStrip(assessment = blockedAssessment)
                MemoryAdmissionBlockedDialog(
                    file = sampleFile,
                    assessment = blockedAssessment,
                    onDismiss = {},
                    onApplyDowngrade = { appliedSuggestion = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("memory_admission_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("memory_admission_blocked_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("M_weights").assertIsDisplayed()
        composeRule.onNodeWithText("M_KV (n_ctx=8192)").assertIsDisplayed()
        composeRule.onNodeWithText("M_compute").assertIsDisplayed()
        composeRule.onNodeWithText("M_runtime").assertIsDisplayed()
        composeRule.onNodeWithText("M_reserve (LMK + safety)").assertIsDisplayed()

        composeRule.onNodeWithTag("apply_admission_downgrade_button").performClick()
        assertNotNull(appliedSuggestion)
        assertTrue(appliedSuggestion!!.targetContextSize < 8192)
    }
}
