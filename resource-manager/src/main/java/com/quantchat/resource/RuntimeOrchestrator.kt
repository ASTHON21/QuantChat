package com.quantchat.resource

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
import com.quantchat.hardware.DeviceHardwareProfile
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.hardware.LiveDeviceMetrics
import com.quantchat.model.GgufHeaderMetadata
import com.quantchat.model.LocalModelRegistry
import com.quantchat.session.ContextBudgetSnapshot
import com.quantchat.session.SessionCacheManager
import com.quantchat.session.SessionCacheMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class OrchestratorSnapshot(
    val lifecycleState: EngineLifecycleState,
    val versionInfo: EngineVersionInfo,
    val capabilities: EngineCapabilities,
    val hardwareProfile: DeviceHardwareProfile? = null,
    val liveMetrics: LiveDeviceMetrics? = null,
    val recommendedThreads: Int = 4,
    val lastAdmission: AdmissionAssessment? = null,
    val lastBenchmark: BenchmarkResult? = null,
    val lastContextBudget: ContextBudgetSnapshot? = null,
    val activeSessionCache: SessionCacheMetadata? = null,
    val isBenchmarking: Boolean = false
)

data class ModelLoadOutcome(
    val readyState: EngineLifecycleState.Ready,
    val headerMetadata: GgufHeaderMetadata?,
    val admission: AdmissionAssessment
)

class RuntimeOrchestrator(
    val backend: InferenceBackend,
    private val hardwareManager: HardwareCapabilityManager,
    private val resourceManager: RuntimeResourceManager,
    private val modelRegistry: LocalModelRegistry,
    private val sessionManager: SessionCacheManager
) {
    private val operationMutex = Mutex()

    private val _snapshot = MutableStateFlow(
        OrchestratorSnapshot(
            lifecycleState = backend.lifecycleState.value,
            versionInfo = backend.versionInfo,
            capabilities = backend.capabilities
        )
    )
    val snapshot: StateFlow<OrchestratorSnapshot> = _snapshot.asStateFlow()

    suspend fun initialize() {
        backend.initialize(EngineConfig())
        refreshDiagnostics()
    }

    suspend fun refreshDiagnostics(): DeviceHardwareProfile {
        val profile = hardwareManager.probeHardware(backend.capabilities)
        val recThreads = resourceManager.recommendThreadCount(profile)
        _snapshot.update { current ->
            current.copy(
                lifecycleState = backend.lifecycleState.value,
                hardwareProfile = profile,
                liveMetrics = profile.liveMetrics,
                recommendedThreads = recThreads
            )
        }
        return profile
    }

    suspend fun inspectModelMetadata(diskFileName: String): GgufHeaderMetadata? =
        modelRegistry.inspectHeader(diskFileName)

    fun assessAdmission(
        diskFile: File,
        header: GgufHeaderMetadata?,
        contextSize: Int
    ): AdmissionAssessment {
        val metrics = resourceManager.liveMetrics()
        val assessment = resourceManager.assessModelAdmission(diskFile, header, contextSize, metrics)
        _snapshot.update { it.copy(liveMetrics = metrics, lastAdmission = assessment) }
        return assessment
    }

    suspend fun loadModel(
        modelId: ModelId,
        displayName: String,
        diskFile: File,
        contextSize: Int,
        threads: Int
    ): ModelLoadOutcome = operationMutex.withLock {
        val header = modelRegistry.inspectHeader(diskFile.name)
        val admission = assessAdmission(diskFile, header, contextSize)

        val config = ModelConfig(
            modelId = modelId,
            displayName = displayName,
            filePath = diskFile.absolutePath,
            fileUri = "file://${diskFile.absolutePath}",
            contextSize = contextSize,
            batchSize = 512,
            threads = threads,
            gpuLayers = 0,
            useMmap = true,
            useMlock = false,
            enableEmbedding = false
        )

        val ready = try {
            backend.loadModel(config)
        } finally {
            _snapshot.update {
                it.copy(
                    lifecycleState = backend.lifecycleState.value,
                    liveMetrics = resourceManager.liveMetrics(),
                    lastAdmission = admission
                )
            }
        }

        ModelLoadOutcome(
            readyState = ready,
            headerMetadata = header,
            admission = admission
        )
    }

    suspend fun streamChat(
        threadId: String,
        modelId: ModelId,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        modelFamilyHint: String,
        contextSize: Int,
        maxTokens: Int,
        temperature: Float
    ): Flow<GenerationEvent> {
        val fingerprint = sessionManager.buildFingerprint(modelId, contextSize, systemPrompt)
        val restored = sessionManager.tryRestoreSession(backend, threadId, fingerprint)
        val budget = sessionManager.prepareContextAndBudget(
            backend = backend,
            threadId = threadId,
            fingerprint = fingerprint,
            systemPrompt = systemPrompt,
            history = history,
            userPrompt = userPrompt,
            modelFamilyHint = modelFamilyHint,
            contextSize = contextSize,
            maxOutputTokens = maxTokens
        ).copy(kvCacheHit = restored != null)

        _snapshot.update {
            it.copy(
                lastContextBudget = budget,
                activeSessionCache = restored,
                liveMetrics = resourceManager.liveMetrics()
            )
        }

        val request = GenerationRequest(
            modelId = modelId,
            sessionId = restored?.sessionId,
            systemPrompt = systemPrompt,
            history = budget.keptTurns,
            userPrompt = userPrompt,
            modelFamilyHint = modelFamilyHint,
            maxTokens = maxTokens,
            temperature = temperature
        )

        return backend.generate(request).onEach { event ->
            _snapshot.update { it.copy(lifecycleState = backend.lifecycleState.value) }
            if (event is GenerationEvent.Completed) {
                val saved = sessionManager.persistSession(
                    backend = backend,
                    threadId = threadId,
                    fingerprint = fingerprint,
                    maxTokens = contextSize
                )
                _snapshot.update {
                    it.copy(
                        lifecycleState = backend.lifecycleState.value,
                        activeSessionCache = saved,
                        liveMetrics = resourceManager.liveMetrics()
                    )
                }
            }
        }
    }

    suspend fun stopGeneration() {
        backend.cancel()
        _snapshot.update { it.copy(lifecycleState = backend.lifecycleState.value) }
    }

    suspend fun unloadModel() {
        operationMutex.withLock {
            backend.unload()
            _snapshot.update {
                it.copy(
                    lifecycleState = backend.lifecycleState.value,
                    activeSessionCache = null,
                    lastContextBudget = null,
                    liveMetrics = resourceManager.liveMetrics()
                )
            }
        }
    }

    suspend fun runBenchmark(): BenchmarkResult = operationMutex.withLock {
        _snapshot.update { it.copy(isBenchmarking = true) }
        try {
            val result = backend.benchmark(prefillTokens = 64, decodeTokens = 24)
            _snapshot.update {
                it.copy(
                    lifecycleState = backend.lifecycleState.value,
                    lastBenchmark = result,
                    liveMetrics = resourceManager.liveMetrics(),
                    isBenchmarking = false
                )
            }
            result
        } catch (e: EngineError) {
            _snapshot.update {
                it.copy(
                    lifecycleState = backend.lifecycleState.value,
                    isBenchmarking = false
                )
            }
            throw e
        } catch (e: Exception) {
            _snapshot.update { it.copy(isBenchmarking = false) }
            throw e
        }
    }

    fun invalidateThreadSession(threadId: String) {
        sessionManager.invalidateThread(threadId)
        _snapshot.update { current ->
            if (current.activeSessionCache?.threadId == threadId) {
                current.copy(activeSessionCache = null, lastContextBudget = null)
            } else {
                current
            }
        }
    }

    fun syncBackendState() {
        _snapshot.update {
            it.copy(
                lifecycleState = backend.lifecycleState.value,
                liveMetrics = resourceManager.liveMetrics()
            )
        }
    }
}
