package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.BenchmarkResult
import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.engine.core.EngineCapabilities
import com.quantchat.engine.core.EngineConfig
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.EngineVersionInfo
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.InferenceThreadPriority
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.engine.core.WorkloadClass
import com.quantchat.hardware.CpuClusterSummary
import com.quantchat.hardware.CpuCoreTier
import com.quantchat.hardware.CpuTopologyParser
import com.quantchat.hardware.DeviceHardwareProfile
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.hardware.RawCpuCoreSample
import com.quantchat.hardware.ThermalSeverity
import com.quantchat.model.LocalModelRegistry
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1SchedulingTest {

    private class RecordingFakeBackend : InferenceBackend {
        override val versionInfo = EngineVersionInfo("fake-cpu", "FakeEngine", "1.0", "arm64-v8a")
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

        var lastGenerationRequest: GenerationRequest? = null
        val benchmarkedThreads = mutableListOf<Int>()
        var preferredOptimalThreads: Int = 4

        override suspend fun initialize(config: EngineConfig) {}
        override suspend fun validateModelFile(fileUri: String): Boolean = true

        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }

        override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
            lastGenerationRequest = request
            return flowOf(
                GenerationEvent.TokenDelta("OK", 1),
                GenerationEvent.Completed("OK", 20L, 1, 50f, false)
            )
        }

        override suspend fun cancel() {}
        override suspend fun unload() {
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }

        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int): BenchmarkResult =
            benchmarkWithThreads(preferredOptimalThreads, prefillTokens, decodeTokens)

        override suspend fun benchmarkWithThreads(
            threads: Int,
            prefillTokens: Int,
            decodeTokens: Int
        ): BenchmarkResult {
            benchmarkedThreads += threads
            val isWinner = threads == preferredOptimalThreads
            val pp = if (isWinner) 170f else 90f + threads * 5f
            val tg = if (isWinner) 34f else 15f + threads.toFloat()
            return BenchmarkResult(pp, tg, prefillTokens, decodeTokens, 100L, true, "fake-${threads}T")
        }

        override suspend fun tokenize(text: String): IntArray = IntArray((text.length / 4).coerceAtLeast(1)) { it }
        override suspend fun detokenize(tokens: IntArray): String = ""
        override suspend fun embed(text: String): FloatArray = floatArrayOf()
        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int) =
            SessionSaveResult(sessionId, path, maxTokens)
        override suspend fun loadSession(sessionId: SessionId, path: String): Int = 0
        override suspend fun formatChat(turns: List<ChatTurn>, template: String): String? = null
    }

    private fun buildFlagshipProfile(thermal: ThermalSeverity = ThermalSeverity.NOMINAL): DeviceHardwareProfile {
        val summary = CpuClusterSummary(
            primeCores = 1,
            performanceCores = 3,
            efficiencyCores = 4,
            totalCores = 8,
            maxFreqMhz = 3300,
            onlineCores = 8,
            capacitySupported = true,
            sysfsRestricted = false
        )
        return DeviceHardwareProfile(
            deviceModel = "Test Flagship",
            socName = "SM8650",
            supportedAbis = listOf("arm64-v8a"),
            primaryAbi = "arm64-v8a",
            cpuFeatures = setOf("NEON", "DOTPROD", "I8MM"),
            cpuClusters = summary,
            hasSystemVulkan = true,
            hasSystemNnapi = true,
            activeBackendGpuSupported = false,
            activeBackendNpuSupported = false,
            gpuStatusSummary = "CPU",
            liveMetrics = LiveDeviceMetrics(12288L, 6144L, false, thermal)
        )
    }

    @Test
    fun `cpu topology parser parses online mask and sysfs 1 plus 3 plus 4 capacity clusters`() {
        assertEquals(setOf(0, 1, 2, 3, 6, 7), CpuTopologyParser.parseCpuRangeMask("0-3,6-7"))

        val tempRoot = createTempDirectory("sysfs_cpu_test").toFile()
        try {
            File(tempRoot, "online").writeText("0-7\n")
            val specs = listOf(
                Triple(0..3, 340, 2020000),
                Triple(4..6, 768, 2800000),
                Triple(7..7, 1024, 3300000)
            )
            for ((range, cap, freq) in specs) {
                for (id in range) {
                    val cpuDir = File(tempRoot, "cpu$id")
                    File(cpuDir, "cpufreq").mkdirs()
                    File(cpuDir, "topology").mkdirs()
                    File(cpuDir, "cpu_capacity").writeText("$cap\n")
                    File(cpuDir, "cpufreq/cpuinfo_max_freq").writeText("$freq\n")
                    File(cpuDir, "topology/cluster_id").writeText("0\n") // shared DynamIQ DSU id
                }
            }

            val summary = CpuTopologyParser.probeFromSysfs(cpuRoot = tempRoot, fallbackCoreCount = 8)
            assertEquals(8, summary.totalCores)
            assertEquals(8, summary.onlineCores)
            assertEquals(1, summary.primeCores)
            assertEquals(3, summary.performanceCores)
            assertEquals(4, summary.efficiencyCores)
            assertEquals(3300, summary.maxFreqMhz)
            assertTrue(summary.capacitySupported)
            assertFalse(summary.sysfsRestricted)
            assertEquals(3, summary.clusters.size)
            assertEquals(CpuCoreTier.PRIME, summary.clusters[0].tier)
            assertEquals(CpuCoreTier.BIG, summary.clusters[1].tier)
            assertEquals(CpuCoreTier.LITTLE, summary.clusters[2].tier)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `cpu topology parser falls back to frequency when capacity is missing and handles restricted nodes`() {
        val samples2Plus6 = (0..7).map { id ->
            RawCpuCoreSample(
                coreId = id,
                clusterId = if (id < 6) 0 else 1,
                online = true,
                capacity = null,
                maxFreqKhz = if (id < 6) 1800000 else 2600000,
                currentFreqKhz = null
            )
        }
        val summary = CpuTopologyParser.classifyAndSummarize(samples2Plus6)
        assertFalse(summary.capacitySupported)
        assertEquals(0, summary.primeCores)
        assertEquals(2, summary.performanceCores)
        assertEquals(6, summary.efficiencyCores)
        assertEquals(2600, summary.maxFreqMhz)

        val restrictedSamples = (0..3).map { id ->
            RawCpuCoreSample(id, null, true, null, null, null, readRestricted = true)
        }
        val fallbackSummary = CpuTopologyParser.classifyAndSummarize(restrictedSamples)
        assertTrue(fallbackSummary.sysfsRestricted)
        assertEquals(4, fallbackSummary.totalCores)
    }

    @Test
    fun `resource manager resolves 4 modes thermal downshift and manual override correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hw = HardwareCapabilityManager(context)
        val rm = RuntimeResourceManager(hw)
        val profile = buildFlagshipProfile(ThermalSeverity.NOMINAL)

        val eco = rm.resolveSchedulingDecision(profile, CpuScheduleMode.ECO, true, ThermalSeverity.NOMINAL, WorkloadClass.LIGHT)
        assertEquals(CpuScheduleMode.ECO, eco.effectiveMode)
        assertEquals(2, eco.effectiveThreads)
        assertEquals(InferenceThreadPriority.BACKGROUND, eco.priorityHint)

        val balanced = rm.resolveSchedulingDecision(profile, CpuScheduleMode.BALANCED, true, ThermalSeverity.NOMINAL, WorkloadClass.STANDARD)
        assertEquals(CpuScheduleMode.BALANCED, balanced.effectiveMode)
        assertEquals(4, balanced.effectiveThreads)
        assertEquals(InferenceThreadPriority.DEFAULT, balanced.priorityHint)

        val perfLong = rm.resolveSchedulingDecision(profile, CpuScheduleMode.PERFORMANCE, true, ThermalSeverity.NOMINAL, WorkloadClass.LONG_CONTEXT)
        assertEquals(CpuScheduleMode.PERFORMANCE, perfLong.effectiveMode)
        assertEquals(6, perfLong.effectiveThreads)
        assertEquals(InferenceThreadPriority.FAVORABLE, perfLong.priorityHint)

        // Thermal downshift at MODERATE & CRITICAL
        val hotModerate = rm.resolveSchedulingDecision(profile, CpuScheduleMode.PERFORMANCE, true, ThermalSeverity.MODERATE, WorkloadClass.LONG_CONTEXT)
        assertEquals(CpuScheduleMode.THERMAL_SAFE, hotModerate.effectiveMode)
        assertTrue(hotModerate.isThermallyThrottled)
        assertEquals(2, hotModerate.effectiveThreads)

        val hotCritical = rm.resolveSchedulingDecision(profile, CpuScheduleMode.BALANCED, true, ThermalSeverity.CRITICAL, WorkloadClass.STANDARD)
        assertEquals(CpuScheduleMode.THERMAL_SAFE, hotCritical.effectiveMode)
        assertEquals(1, hotCritical.effectiveThreads)

        // Auto downshift disabled allows Performance even at SEVERE
        val manualHot = rm.resolveSchedulingDecision(profile, CpuScheduleMode.PERFORMANCE, false, ThermalSeverity.SEVERE, WorkloadClass.STANDARD)
        assertEquals(CpuScheduleMode.PERFORMANCE, manualHot.effectiveMode)
        assertFalse(manualHot.isThermallyThrottled)

        // Manual thread override respected when cool
        val overridden = rm.resolveSchedulingDecision(profile, CpuScheduleMode.BALANCED, true, ThermalSeverity.NOMINAL, WorkloadClass.STANDARD, manualThreadsOverride = 7)
        assertEquals(7, overridden.effectiveThreads)

        val candidates = rm.buildCalibrationCandidates(profile)
        assertTrue(candidates.size in 2..4)
        assertTrue(candidates.contains(2))
        assertTrue(candidates.contains(4))
    }

    @Test
    fun `orchestrator calibrates optimal threads and injects per-request n_threads into backend`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempModels = createTempDirectory("orch_models").toFile()
        val tempSessions = createTempDirectory("orch_sessions").toFile()
        try {
            val dummyGguf = File(tempModels, "qwen3.gguf").apply { writeBytes(ByteArray(2048)) }
            val backend = RecordingFakeBackend()
            val hw = HardwareCapabilityManager(context)
            val rm = RuntimeResourceManager(hw)
            val orch = RuntimeOrchestrator(
                primaryBackend = backend,
                hardwareManager = hw,
                resourceManager = rm,
                modelRegistry = LocalModelRegistry(tempModels),
                sessionManager = SessionCacheManager(tempSessions)
            )
            try {
                orch.initialize()
                orch.loadModel(ModelId("qwen3.gguf"), "Qwen3", dummyGguf, 2048, 4)

                val probedCandidates = rm.buildCalibrationCandidates(orch.snapshot.value.hardwareProfile!!)
                val expectedOptimal = probedCandidates.last()
                backend.preferredOptimalThreads = expectedOptimal

                val calResult = orch.calibrateOptimalThreads()
                assertEquals(expectedOptimal, calResult.optimalThreads)
                assertEquals(probedCandidates, backend.benchmarkedThreads)
                assertEquals(expectedOptimal, orch.snapshot.value.lastCalibration?.optimalThreads)

                orch.updateScheduleConfig(
                    mode = CpuScheduleMode.ECO,
                    autoThermalDownshift = true,
                    manualThreadsOverride = null,
                    calibratedOptimalThreads = calResult.optimalThreads,
                    calibratedDeviceSignature = calResult.deviceSignature
                )

                val events = orch.streamChat(
                    threadId = "t1",
                    modelId = ModelId("qwen3.gguf"),
                    systemPrompt = "Be brief",
                    history = emptyList(),
                    userPrompt = "Hi",
                    modelFamilyHint = "qwen3",
                    contextSize = 2048,
                    maxTokens = 128,
                    temperature = 0.2f
                ).toList()

                assertTrue(events.any { it is GenerationEvent.Completed })
                val sentReq = backend.lastGenerationRequest!!
                assertEquals(2, sentReq.threadsOverride)
                assertEquals(InferenceThreadPriority.BACKGROUND, sentReq.threadPriority)
            } finally {
                orch.shutdown()
            }
        } finally {
            tempModels.deleteRecursively()
            tempSessions.deleteRecursively()
        }
    }
}
