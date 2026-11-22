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
}
