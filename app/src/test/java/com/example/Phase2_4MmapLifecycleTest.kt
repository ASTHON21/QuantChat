package com.example

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.ActiveDownloadState
import com.example.data.FileInspectionUiState
import com.example.data.HfAuthState
import com.example.data.HubModelTab
import com.example.data.HubSearchUiState
import com.example.data.HuggingFaceModelRepository
import com.example.data.InsufficientStorageException
import com.example.data.LocalRuntimeState
import com.example.data.LocalWeightFile
import com.example.ui.screens.ModelsScreen
import com.example.ui.theme.QuantChatTheme
import com.quantchat.engine.core.AcceleratorBackendType
import com.quantchat.engine.core.ActiveMmapLeaseInfo
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
import com.quantchat.engine.core.MmapLeaseHandle
import com.quantchat.engine.core.MmapLifecycleRegistry
import com.quantchat.engine.core.MmapLifecycleSnapshot
import com.quantchat.engine.core.ModelConfig
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.SafeDeleteCheck
import com.quantchat.engine.core.SessionId
import com.quantchat.engine.core.SessionSaveResult
import com.quantchat.engine.core.VulkanProbeResult
import com.quantchat.engine.core.VulkanProbeStatus
import com.quantchat.hardware.HardwareCapabilityManager
import com.quantchat.model.DownloadCheckpointStore
import com.quantchat.model.LocalModelRegistry
import com.quantchat.model.ModelFileLifecycleManager
import com.quantchat.model.ModelFileVerifier
import com.quantchat.model.ModelIntegrityVerdict
import com.quantchat.model.SafeDeleteOutcome
import com.quantchat.model.StorageSpaceChecker
import com.quantchat.model.StorageSpaceSnapshot
import com.quantchat.model.VerifiedArtifactManifest
import com.quantchat.resource.RuntimeOrchestrator
import com.quantchat.resource.RuntimeResourceManager
import com.quantchat.session.SessionCacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2_4MmapLifecycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        MmapLifecycleRegistry.resetForTests()
        StorageSpaceChecker.statOverrideForTests = null
        tempDir = createTempDirectory("phase2_4_mmap").toFile()
    }

    @After
    fun tearDown() {
        MmapLifecycleRegistry.resetForTests()
        StorageSpaceChecker.statOverrideForTests = null
        tempDir.deleteRecursively()
    }

    private fun buildValidGgufBytes(totalSize: Int = 2048, arch: String = "llama"): ByteArray {
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

        writeIntLe(0x46554747) // GGUF magic
        writeIntLe(3) // GGUF v3
        writeLongLe(1L) // tensor count
        writeLongLe(1L) // kv count
        writeGgufString("general.architecture")
        writeIntLe(8) // GGUF_TYPE_STRING
        writeGgufString(arch)

        val current = out.toByteArray()
        if (current.size >= totalSize) return current
        val padded = ByteArray(totalSize)
        System.arraycopy(current, 0, padded, 0, current.size)
        for (i in current.size until totalSize) {
            padded[i] = ((i * 31) and 0xFF).toByte()
        }
        return padded
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun configureOnlineWifi(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (network != null) {
            val caps = ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(cm).setNetworkCapabilities(network, caps)
        }
    }

    private class MmapTrackingFakeBackend : InferenceBackend {
        override val versionInfo = EngineVersionInfo("llamacpp", "FakeMmap", "1.0", "arm64-v8a")
        override val capabilities = EngineCapabilities(
            supportedBackends = setOf(AcceleratorBackendType.CPU),
            supportsStreaming = true,
            supportsKvSession = false,
            supportsTokenize = true,
            supportsEmbedding = false,
            supportsNativeBenchmark = false,
            supportsGpuOffload = false,
            supportsNpuOffload = false
        )
        private val _state = MutableStateFlow<EngineLifecycleState>(
            EngineLifecycleState.Initialized(versionInfo, capabilities)
        )
        override val lifecycleState: StateFlow<EngineLifecycleState> = _state

        private var activeLease: MmapLeaseHandle? = null

        override suspend fun initialize(config: EngineConfig) {}
        override suspend fun validateModelFile(fileUri: String): Boolean = true

        override suspend fun loadModel(model: ModelConfig): EngineLifecycleState.Ready {
            val file = File(model.filePath)
            val lease = MmapLifecycleRegistry.acquireReadMapping(
                file = file,
                modelId = model.modelId.value,
                backendId = "llamacpp",
                useMmap = model.useMmap
            )
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            MmapLifecycleRegistry.attachPfd(lease, pfd)
            activeLease = lease
            val ready = EngineLifecycleState.Ready(model.modelId, 1, model)
            _state.value = ready
            return ready
        }

        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flowOf()
        override suspend fun cancel() {}

        override suspend fun unload() {
            MmapLifecycleRegistry.releaseMapping(activeLease)
            activeLease = null
            _state.value = EngineLifecycleState.Initialized(versionInfo, capabilities)
        }

        override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int) =
            BenchmarkResult(0f, 0f, prefillTokens, decodeTokens, 0L, true, "CPU")
        override suspend fun tokenize(text: String) = intArrayOf()
        override suspend fun detokenize(tokens: IntArray) = ""
        override suspend fun embed(text: String) = floatArrayOf()
        override suspend fun saveSession(sessionId: SessionId, path: String, maxTokens: Int) =
            SessionSaveResult(sessionId, path, maxTokens)
        override suspend fun loadSession(sessionId: SessionId, path: String) = 0
        override suspend fun formatChat(turns: List<ChatTurn>, template: String) = null
    }

    @Test
    fun `file verification validates GGUF header, size, SHA-256 and gates RuntimeOrchestrator load`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024L * 1024L * 1024L
            availMem = 4096L * 1024L * 1024L
            threshold = 256L * 1024L * 1024L
            lowMemory = false
        })

        val modelsDir = File(tempDir, "models").apply { mkdirs() }
        val sessionsDir = File(tempDir, "sessions").apply { mkdirs() }

        val validBytes = buildValidGgufBytes(totalSize = 2048)
        val expectedSha = sha256Hex(validBytes)
        val validFile = File(modelsDir, "test__qwen3-valid.gguf").apply { writeBytes(validBytes) }

        val validResult = ModelFileVerifier.verifyComplete(
            file = validFile,
            expectedSizeBytes = validBytes.size.toLong(),
            expectedSha256 = expectedSha,
            computeSha256 = true
        )
        assertTrue(validResult.isValid)
        assertEquals(ModelIntegrityVerdict.VERIFIED_SHA256, validResult.verdict)
        assertEquals("llama", validResult.headerMetadata?.architecture)
        assertEquals(expectedSha, validResult.actualSha256)

        // Mismatched SHA-256 fails
        val wrongSha = ModelFileVerifier.verifyComplete(
            file = validFile,
            expectedSizeBytes = validBytes.size.toLong(),
            expectedSha256 = "0".repeat(64)
        )
        assertFalse(wrongSha.isValid)
        assertEquals(ModelIntegrityVerdict.SHA256_MISMATCH, wrongSha.verdict)

        // Corrupt GGUF magic fails and is blocked by RuntimeOrchestrator when strictArtifactVerification=true
        val corruptFile = File(modelsDir, "test__corrupt.gguf").apply {
            writeBytes(ByteArray(2048) { 0x11 })
        }
        val corruptResult = ModelFileVerifier.verifyComplete(corruptFile)
        assertFalse(corruptResult.isValid)
        assertEquals(ModelIntegrityVerdict.CORRUPT_HEADER, corruptResult.verdict)

        val hwManager = HardwareCapabilityManager(
            context = context,
            vulkanNativeProbe = {
                VulkanProbeResult(status = VulkanProbeStatus.NATIVE_LIBRARY_MISSING, reason = "test")
            }
        )
        val orchestrator = RuntimeOrchestrator(
            primaryBackend = MmapTrackingFakeBackend(),
            hardwareManager = hwManager,
            resourceManager = RuntimeResourceManager(hwManager),
            modelRegistry = LocalModelRegistry(modelsDir),
            sessionManager = SessionCacheManager(sessionsDir),
            strictArtifactVerification = true
        )

        var blockedByFormat: EngineError.InvalidModelFormat? = null
        try {
            orchestrator.loadModel(
                modelId = ModelId("corrupt"),
                displayName = "corrupt.gguf",
                diskFile = corruptFile,
                contextSize = 2048,
                threads = 4
            )
        } catch (e: EngineError.InvalidModelFormat) {
            blockedByFormat = e
        }
        assertNotNull(blockedByFormat)

        // Valid model passes verification gate, loads, acquires mmap lease, and writes .verified.json
        val outcome = orchestrator.loadModel(
            modelId = ModelId("valid"),
            displayName = "qwen3-valid.gguf",
            diskFile = validFile,
            contextSize = 2048,
            threads = 4
        )
        assertEquals("llama", outcome.headerMetadata?.architecture)
        assertTrue(MmapLifecycleRegistry.hasActiveMappings(validFile))
        assertNotNull(DownloadCheckpointStore.readVerifiedManifest(validFile))

        // Unloading releases mmap lease and FileLock cleanly
        orchestrator.unloadModel()
        assertFalse(MmapLifecycleRegistry.hasActiveMappings(validFile))
        assertEquals(0, MmapLifecycleRegistry.snapshot.value.totalActiveMappings)
        assertNotNull(MmapLifecycleRegistry.snapshot.value.lastReleasedPath)
    }

    @Test
    fun `file lock coordinates shared read leases against exclusive write locks and blocks delete while mapped`() {
        val modelFile = File(tempDir, "locked__model.gguf").apply {
            writeBytes(buildValidGgufBytes(2048))
        }

        // Concurrent read mappings share OS read FileLock and block exclusive write / delete
        val lease1 = MmapLifecycleRegistry.acquireReadMapping(
            file = modelFile,
            modelId = "m1",
            backendId = "llamacpp",
            useMmap = true
        )
        val lease2 = MmapLifecycleRegistry.acquireReadMapping(
            file = modelFile,
            modelId = "m1",
            backendId = "vulkan",
            useMmap = true
        )
        assertEquals(2, MmapLifecycleRegistry.activeMappingCount(modelFile))
        assertTrue(lease1.sharedFileLockHeld)
        assertNull(MmapLifecycleRegistry.acquireExclusiveWriteLock(modelFile))
        assertTrue(MmapLifecycleRegistry.canDeleteSafely(modelFile) is SafeDeleteCheck.BlockedActiveMapping)

        val blockedOutcome = ModelFileLifecycleManager.deleteSafely(modelFile)
        assertTrue(blockedOutcome is SafeDeleteOutcome.BlockedActiveMapping)
        assertTrue(modelFile.exists())
        assertNotNull(MmapLifecycleRegistry.snapshot.value.lastBlockedDeleteReason)

        // Releasing first lease leaves 1 lease active -> delete still blocked
        MmapLifecycleRegistry.releaseMapping(lease1)
        assertEquals(1, MmapLifecycleRegistry.activeMappingCount(modelFile))
        assertTrue(ModelFileLifecycleManager.deleteSafely(modelFile) is SafeDeleteOutcome.BlockedActiveMapping)

        // Releasing second lease frees FileLock -> exclusive write lock and deletion succeed
        MmapLifecycleRegistry.releaseMapping(lease2)
        assertEquals(0, MmapLifecycleRegistry.activeMappingCount(modelFile))

        val writeLock = MmapLifecycleRegistry.acquireExclusiveWriteLock(modelFile)
        assertNotNull(writeLock)
        var lockedLoadErr: EngineError.FileLocked? = null
        try {
            MmapLifecycleRegistry.acquireReadMapping(modelFile, "m1", "llamacpp", true)
        } catch (e: EngineError.FileLocked) {
            lockedLoadErr = e
        }
        assertNotNull(lockedLoadErr)
        MmapLifecycleRegistry.releaseExclusiveWriteLock(writeLock)

        val deletedOutcome = ModelFileLifecycleManager.deleteSafely(modelFile)
        assertEquals(SafeDeleteOutcome.Deleted, deletedOutcome)
        assertFalse(modelFile.exists())
    }

    @Test
    fun `download interruption checkpoint saves partial progress and resumes via Range and If-Range with SHA-256 verification`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configureOnlineWifi(context)
        val repo = HuggingFaceModelRepository(context)
        repo.modelsDirectory.listFiles()?.forEach { it.delete() }

        val fullPayload = buildValidGgufBytes(totalSize = 8192, arch = "qwen3")
        val fullSha = sha256Hex(fullPayload)
        val splitOffset = 3072
        val requestCounter = AtomicInteger(0)
        var seenRangeHeader: String? = null
        var seenIfRangeHeader: String? = null

        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(start = true, isDaemon = true) {
            try {
                while (!serverSocket.isClosed) {
                    val client = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    val reqNum = requestCounter.incrementAndGet()
                    client.use { sock ->
                        val reader = BufferedInputStream(sock.getInputStream()).bufferedReader()
                        val headers = mutableMapOf<String, String>()
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            val idx = line.indexOf(':')
                            if (idx > 0) {
                                headers[line.substring(0, idx).trim().lowercase()] =
                                    line.substring(idx + 1).trim()
                            }
                            line = reader.readLine()
                        }

                        val out = sock.getOutputStream()
                        if (reqNum == 1) {
                            // First attempt sends full Content-Length + ETag, writes only splitOffset bytes, and drops connection
                            val headerBytes = (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${fullPayload.size}\r\n" +
                                    "ETag: \"gguf-v1\"\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                            out.write(headerBytes)
                            out.write(fullPayload, 0, splitOffset)
                            out.flush()
                        } else {
                            seenRangeHeader = headers["range"]
                            seenIfRangeHeader = headers["if-range"]
                            val start = headers["range"]
                                ?.removePrefix("bytes=")
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                                ?: 0
                            val remaining = fullPayload.size - start
                            val headerBytes = (
                                "HTTP/1.1 206 Partial Content\r\n" +
                                    "Content-Length: $remaining\r\n" +
                                    "Content-Range: bytes $start-${fullPayload.size - 1}/${fullPayload.size}\r\n" +
                                    "ETag: \"gguf-v1\"\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                            out.write(headerBytes)
                            out.write(fullPayload, start, remaining)
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            val url = "http://127.0.0.1:${serverSocket.localPort}/Qwen_slash_Qwen3-4B-GGUF/qwen3-4b-q4_k_m.gguf"

            // Ensure sufficient storage reported
            StorageSpaceChecker.statOverrideForTests = { (512L * 1024L * 1024L) to (1024L * 1024L * 1024L) }

            // Attempt 1 fails mid-stream due to premature EOF from server
            var firstError: Throwable? = null
            try {
                repo.streamDownloadFile(
                    repoId = "Qwen/Qwen3-4B-GGUF",
                    rfilename = "qwen3-4b-q4_k_m.gguf",
                    downloadUrl = url,
                    token = null,
                    expectedTotalBytes = fullPayload.size.toLong(),
                    expectedSha256 = fullSha,
                    modelId = "qwen3_test",
                    modelDisplayName = "Qwen3 4B (Q4_K_M)"
                ) {}
            } catch (t: Throwable) {
                firstError = t
            }
            assertNotNull("Expected interrupted download exception on partial stream", firstError)

            // Cold-start recovery reads persisted .part.checkpoint.json
            val restored = repo.restorePendingPartialDownload()
            assertNotNull(restored)
            assertEquals("Qwen/Qwen3-4B-GGUF", restored?.repoId)
            assertEquals(fullPayload.size.toLong(), restored?.expectedTotalBytes)
            assertEquals(fullSha, restored?.expectedSha256)
            assertEquals("\"gguf-v1\"", restored?.etag)
            assertEquals(splitOffset.toLong(), restored?.bytesOnDisk)

            // Attempt 2 resumes from byte offset 3072 with If-Range and completes + verifies SHA-256
            var completedSha: String? = null
            repo.streamDownloadFile(
                repoId = restored!!.repoId,
                rfilename = restored.rfilename,
                downloadUrl = restored.downloadUrl,
                token = null,
                expectedTotalBytes = restored.expectedTotalBytes,
                expectedSha256 = restored.expectedSha256,
                modelId = restored.modelId,
                modelDisplayName = restored.modelDisplayName
            ) { sample ->
                if (sample.completed) {
                    completedSha = sample.verifiedSha256
                }
            }

            assertEquals("bytes=$splitOffset-", seenRangeHeader)
            assertEquals("\"gguf-v1\"", seenIfRangeHeader)
            assertEquals(fullSha, completedSha)

            val finalFile = File(repo.modelsDirectory, "Qwen_slash_Qwen3-4B-GGUF__qwen3-4b-q4_k_m.gguf")
            assertTrue(finalFile.exists())
            assertArrayEquals(fullPayload, finalFile.readBytes())
            assertFalse(File(repo.modelsDirectory, "${finalFile.name}.part").exists())
            assertFalse(File(repo.modelsDirectory, "${finalFile.name}.part.checkpoint.json").exists())

            val verifiedManifest = DownloadCheckpointStore.readVerifiedManifest(finalFile)
            assertNotNull(verifiedManifest)
            assertEquals(ModelIntegrityVerdict.VERIFIED_SHA256, verifiedManifest?.verdict)
            assertEquals("qwen3", verifiedManifest?.architecture)
        } finally {
            serverSocket.close()
            serverThread.interrupt()
        }
    }

    @Test
    fun `storage space checker enforces 256MiB or 5pct safety buffer before download`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configureOnlineWifi(context)
        val repo = HuggingFaceModelRepository(context)
        repo.modelsDirectory.listFiles()?.forEach { it.delete() }

        // Available 300 MiB; requesting 200 MiB model requires 200 + 256 = 456 MiB -> blocked!
        StorageSpaceChecker.statOverrideForTests = { (300L * 1024L * 1024L) to (2048L * 1024L * 1024L) }

        val check = repo.checkStorageForBytes(
            totalTargetBytes = 200L * 1024L * 1024L,
            existingPartialBytes = 0L
        )
        assertFalse(check.hasEnoughSpace)
        assertEquals(256L * 1024L * 1024L, check.safetyBufferBytes)

        // When 160 MiB is already in .part, remaining is 40 MiB + 256 MiB = 296 MiB <= 300 MiB -> allowed!
        val resumeCheck = repo.checkStorageForBytes(
            totalTargetBytes = 200L * 1024L * 1024L,
            existingPartialBytes = 160L * 1024L * 1024L
        )
        assertTrue(resumeCheck.hasEnoughSpace)

        var caughtStorage: InsufficientStorageException? = null
        try {
            repo.streamDownloadFile(
                repoId = "Qwen/Qwen3-4B-GGUF",
                rfilename = "too-large.gguf",
                downloadUrl = "http://127.0.0.1:1/unused.gguf",
                token = null,
                expectedTotalBytes = 200L * 1024L * 1024L
            ) {}
        } catch (e: InsufficientStorageException) {
            caughtStorage = e
        }
        assertNotNull(caughtStorage)
    }

    @Test
    fun `ModelsScreen shows storage card, integrity and mmap badges, and unmap-before-delete confirmation dialog`() {
        val mappedFile = LocalWeightFile(
            diskFileName = "Qwen_slash_Qwen3-4B-GGUF__qwen3.gguf",
            displayFileName = "qwen3.gguf",
            repoId = "Qwen/Qwen3-4B-GGUF",
            sizeBytes = 2_400L * 1024L * 1024L,
            isPartial = false,
            expectedSha256 = "a".repeat(64),
            verifiedManifest = VerifiedArtifactManifest(
                fileName = "Qwen_slash_Qwen3-4B-GGUF__qwen3.gguf",
                sizeBytes = 2_400L * 1024L * 1024L,
                expectedSizeBytes = 2_400L * 1024L * 1024L,
                actualSha256 = "a".repeat(64),
                expectedSha256 = "a".repeat(64),
                verdict = ModelIntegrityVerdict.VERIFIED_SHA256,
                architecture = "qwen3"
            ),
            hasActiveMmapLease = true
        )
        val idleFile = LocalWeightFile(
            diskFileName = "local__phi4.gguf",
            displayFileName = "phi4.gguf",
            repoId = "Local Import",
            sizeBytes = 1_800L * 1024L * 1024L,
            isPartial = false
        )
        val snapshot = StorageSpaceSnapshot(
            totalBytes = 128L * 1024L * 1024L * 1024L,
            availableBytes = 42L * 1024L * 1024L * 1024L,
            modelsUsedBytes = 4_200L * 1024L * 1024L,
            partialUsedBytes = 256L * 1024L * 1024L
        )
        val mmapState = MmapLifecycleSnapshot(
            activeLeases = listOf(
                ActiveMmapLeaseInfo(
                    leaseId = "lease-1",
                    canonicalPath = "/models/qwen3.gguf",
                    fileName = "qwen3.gguf",
                    modelId = mappedFile.diskFileName,
                    backendId = "llamacpp",
                    useMmap = true,
                    sharedFileLockHeld = true,
                    acquiredAtMs = 1000L
                )
            ),
            totalActiveMappings = 1
        )

        var verifiedClicked: LocalWeightFile? = null
        var confirmedDeleteFile: LocalWeightFile? = null

        composeRule.setContent {
            var pendingDelete by remember { mutableStateOf<LocalWeightFile?>(mappedFile) }
            QuantChatTheme {
                ModelsScreen(
                    curatedModels = emptyList(),
                    selectedTab = HubModelTab.DOWNLOADED,
                    searchQuery = "",
                    hubSearchState = HubSearchUiState.Idle,
                    fileInspectionState = FileInspectionUiState.Closed,
                    activeDownload = ActiveDownloadState(),
                    localWeightFiles = listOf(mappedFile, idleFile),
                    runtimeState = LocalRuntimeState.Unloaded(),
                    hfAuthState = HfAuthState(),
                    isOnline = true,
                    isImportingLocalFile = false,
                    storageSnapshot = snapshot,
                    mmapLifecycle = mmapState,
                    pendingDeleteConfirmation = pendingDelete,
                    onSelectTab = {},
                    onSearchQueryChange = {},
                    onSearchHubClick = {},
                    onInspectModelFiles = { _, _ -> },
                    onCloseFileInspection = {},
                    onSelectArtifactToDownload = { _, _ -> },
                    onPauseDownload = {},
                    onResumeDownload = {},
                    onCancelDownload = {},
                    onSwitchDownloadToMirror = {},
                    onLoadLocalModel = {},
                    onUnloadLocalModel = {},
                    onOpenChatWithModel = {},
                    onImportLocalGgufUri = {},
                    onResumeLocalPartial = {},
                    onVerifyLocalFile = { verifiedClicked = it },
                    onDeleteLocalFile = {},
                    onConfirmUnloadAndDelete = {
                        confirmedDeleteFile = it
                        pendingDelete = null
                    },
                    onDismissDeleteConfirmation = { pendingDelete = null },
                    onSaveHfToken = {}
                )
            }
        }

        // Dialog for deleting mapped file is shown and triggers onConfirmUnloadAndDelete
        composeRule.onNodeWithTag("confirm_unmap_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_unmap_delete_button").performClick()
        assertEquals(mappedFile.diskFileName, confirmedDeleteFile?.diskFileName)

        // Storage & mmap lifecycle card + verified/locked badges + Verify action
        composeRule.onNodeWithTag("models_lazy_column")
            .performScrollToNode(hasTestTag("storage_mmap_lifecycle_card"))
        composeRule.onNodeWithTag("storage_mmap_lifecycle_card").assertIsDisplayed()
        composeRule.onNodeWithText("1 active mmap").assertIsDisplayed()

        composeRule.onNodeWithTag("models_lazy_column")
            .performScrollToNode(hasTestTag("local_weight_verify_badge_${mappedFile.diskFileName}"))
        composeRule.onNodeWithTag("local_weight_verify_badge_${mappedFile.diskFileName}").assertIsDisplayed()
        composeRule.onNodeWithTag("local_weight_mmap_badge_${mappedFile.diskFileName}").assertIsDisplayed()

        composeRule.onNodeWithTag("models_lazy_column")
            .performScrollToNode(hasTestTag("verify_weight_${idleFile.diskFileName}"))
        composeRule.onNodeWithTag("verify_weight_${idleFile.diskFileName}").performClick()
        assertEquals(idleFile.diskFileName, verifiedClicked?.diskFileName)
    }
}
