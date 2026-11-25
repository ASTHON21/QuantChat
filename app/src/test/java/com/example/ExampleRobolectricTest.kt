package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChatMessage
import com.example.data.ChatRole
import com.example.data.ChatThread
import com.example.data.local.AppChatDatabase
import com.example.data.local.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("QuantChat", appName)
  }

  @Test
  fun `updating thread metadata preserves existing chat messages`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppChatDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ChatRepository(db.chatDao())
      val threadId = "chat_test123"

      repo.upsertThread(
        ChatThread(
          id = threadId,
          title = "New Chat",
          preview = "Ready",
          modelFileName = "Qwen3.5-4B-Q4_K_M.gguf",
          updatedAtMs = 1000L,
          timestampLabel = "22:02"
        )
      )
      repo.upsertMessage(
        ChatMessage(
          id = "usr_1",
          threadId = threadId,
          role = ChatRole.USER,
          text = "你好",
          timestampMs = 1001L,
          timestampLabel = "22:02"
        )
      )
      repo.upsertMessage(
        ChatMessage(
          id = "ai_1",
          threadId = threadId,
          role = ChatRole.ASSISTANT,
          text = "你好！有什么我可以帮你的？",
          modelLabel = "Qwen3.5-4B-Q4_K_M.gguf",
          timestampMs = 1002L,
          timestampLabel = "22:02"
        )
      )
      repo.upsertThread(
        ChatThread(
          id = threadId,
          title = "你好",
          preview = "你好！有什么我可以帮你的？",
          modelFileName = "Qwen3.5-4B-Q4_K_M.gguf",
          updatedAtMs = 1003L,
          timestampLabel = "22:02"
        )
      )

      val saved = repo.getRecentMessagesSync(threadId)
      assertEquals(2, saved.size)
      assertEquals("你好", saved[0].text)
      assertEquals("你好！有什么我可以帮你的？", saved[1].text)

      repo.deleteThread(threadId)
      assertEquals(0, repo.getRecentMessagesSync(threadId).size)
    } finally {
      db.close()
    }
  }

  @Test
  fun `system integration bridge parses ACTION_SEND and PROCESS_TEXT`() {
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(android.content.Intent.EXTRA_TEXT, "Explain GGUF Q4_K_M")
    }
    val action = com.quantchat.integration.SystemIntegrationBridge.parseLaunchIntent(sendIntent)
    assertEquals(
      com.quantchat.integration.LaunchAction.PrefillSharedPrompt("Explain GGUF Q4_K_M", "Android Share"),
      action
    )
  }

  @Test
  fun `local openai router serves models and 503 when unloaded`() = runBlocking {
    val fakeInfo = com.quantchat.engine.core.EngineVersionInfo("test-cpu", "TestEngine", "1.0", "arm64-v8a")
    val fakeCaps = com.quantchat.engine.core.EngineCapabilities(
      supportedBackends = setOf(com.quantchat.engine.core.AcceleratorBackendType.CPU),
      supportsStreaming = true,
      supportsKvSession = true,
      supportsTokenize = true,
      supportsEmbedding = false,
      supportsNativeBenchmark = false,
      supportsGpuOffload = false,
      supportsNpuOffload = false
    )
    val stateFlow = kotlinx.coroutines.flow.MutableStateFlow<com.quantchat.engine.core.EngineLifecycleState>(
      com.quantchat.engine.core.EngineLifecycleState.Initialized(fakeInfo, fakeCaps)
    )
    val backend = object : com.quantchat.engine.core.InferenceBackend {
      override val lifecycleState: kotlinx.coroutines.flow.StateFlow<com.quantchat.engine.core.EngineLifecycleState> = stateFlow
      override val versionInfo = fakeInfo
      override val capabilities = fakeCaps
      override suspend fun initialize(config: com.quantchat.engine.core.EngineConfig) {}
      override suspend fun validateModelFile(fileUri: String) = true
      override suspend fun loadModel(model: com.quantchat.engine.core.ModelConfig): com.quantchat.engine.core.EngineLifecycleState.Ready {
        val ready = com.quantchat.engine.core.EngineLifecycleState.Ready(model.modelId, 1, model)
        stateFlow.value = ready
        return ready
      }
      override fun generate(request: com.quantchat.engine.core.GenerationRequest) = kotlinx.coroutines.flow.flowOf(
        com.quantchat.engine.core.GenerationEvent.Completed("Local reply", 10L, 2, 200f, false)
      )
      override suspend fun cancel() {}
      override suspend fun unload() { stateFlow.value = com.quantchat.engine.core.EngineLifecycleState.Initialized(fakeInfo, fakeCaps) }
      override suspend fun benchmark(prefillTokens: Int, decodeTokens: Int) =
        com.quantchat.engine.core.BenchmarkResult(0f, 0f, 0, 0, 0L, false, "")
      override suspend fun tokenize(text: String) = intArrayOf(1)
      override suspend fun detokenize(tokens: IntArray) = ""
      override suspend fun embed(text: String) = floatArrayOf()
      override suspend fun saveSession(sessionId: com.quantchat.engine.core.SessionId, path: String, maxTokens: Int) =
        com.quantchat.engine.core.SessionSaveResult(sessionId, path, 0)
      override suspend fun loadSession(sessionId: com.quantchat.engine.core.SessionId, path: String) = 0
      override suspend fun formatChat(turns: List<com.quantchat.engine.core.ChatTurn>, template: String) = null
    }
    val router = com.quantchat.server.LocalOpenAiRouter(backend)

    val unloadedChat = router.handleHttpRequest(
      "POST",
      "/v1/chat/completions",
      """{"messages":[{"role":"user","content":"hello"}]}"""
    )
    assertEquals(503, unloadedChat.statusCode)

    backend.loadModel(
      com.quantchat.engine.core.ModelConfig(
        modelId = com.quantchat.engine.core.ModelId("qwen3.gguf"),
        displayName = "Qwen3-4B.gguf",
        filePath = "/tmp/qwen3.gguf",
        fileUri = "file:///tmp/qwen3.gguf",
        contextSize = 2048
      )
    )
    val modelsRes = router.handleHttpRequest("GET", "/v1/models", "")
    assertEquals(200, modelsRes.statusCode)
    org.junit.Assert.assertTrue(modelsRes.body.contains("Qwen3-4B.gguf"))

    val chatRes = router.handleHttpRequest(
      "POST",
      "/v1/chat/completions",
      """{"messages":[{"role":"user","content":"hello"}]}"""
    )
    assertEquals(200, chatRes.statusCode)
    org.junit.Assert.assertTrue(chatRes.body.contains("Local reply"))
  }
}
