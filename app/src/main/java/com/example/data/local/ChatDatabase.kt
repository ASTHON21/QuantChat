package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.ChatMessage
import com.example.data.ChatRole
import com.example.data.ChatThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "chat_threads")
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String,
    val modelFileName: String?,
    val updatedAtMs: Long
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["threadId", "timestampMs"])]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val role: String,
    val text: String,
    val modelLabel: String?,
    val latencyMs: Long?,
    val tokensPerSec: Float?,
    val tokenCount: Int?,
    val timestampMs: Long,
    val isError: Boolean
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_threads ORDER BY updatedAtMs DESC")
    fun observeAllThreads(): Flow<List<ChatThreadEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestampMs ASC")
    fun observeAllMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY timestampMs ASC")
    suspend fun getMessagesForThreadSync(threadId: String): List<ChatMessageEntity>

    @Query("SELECT id FROM chat_threads")
    suspend fun getAllThreadIdsSync(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertThreadIfAbsent(thread: ChatThreadEntity): Long

    @Update
    suspend fun updateThread(thread: ChatThreadEntity)

    @Transaction
    suspend fun upsertThread(thread: ChatThreadEntity) {
        if (insertThreadIfAbsent(thread) == -1L) {
            updateThread(thread)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: ChatMessageEntity): Long

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Transaction
    suspend fun upsertMessage(message: ChatMessageEntity) {
        if (insertMessageIfAbsent(message) == -1L) {
            updateMessage(message)
        }
    }

    @Query("DELETE FROM chat_threads WHERE id = :threadId")
    suspend fun deleteThreadById(threadId: String)

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
    suspend fun clearThreadMessages(threadId: String)
}

@Database(
    entities = [ChatThreadEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppChatDatabase? = null

        fun getInstance(context: Context): AppChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppChatDatabase::class.java,
                    "quantchat_local.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class ChatRepository(private val chatDao: ChatDao) {

    val allThreads: Flow<List<ChatThread>> = chatDao.observeAllThreads().map { list ->
        list.map { it.toDomain() }
    }

    val allMessagesByThread: Flow<Map<String, List<ChatMessage>>> =
        chatDao.observeAllMessages().map { list ->
            list.map { it.toDomain() }.groupBy { it.threadId }
        }

    suspend fun upsertThread(thread: ChatThread) {
        chatDao.upsertThread(
            ChatThreadEntity(
                id = thread.id,
                title = thread.title,
                preview = thread.preview,
                modelFileName = thread.modelFileName,
                updatedAtMs = thread.updatedAtMs
            )
        )
    }

    suspend fun upsertMessage(message: ChatMessage) {
        chatDao.upsertMessage(
            ChatMessageEntity(
                id = message.id,
                threadId = message.threadId,
                role = message.role.raw,
                text = message.text,
                modelLabel = message.modelLabel,
                latencyMs = message.latencyMs,
                tokensPerSec = message.tokensPerSec,
                tokenCount = message.tokenCount,
                timestampMs = message.timestampMs,
                isError = message.isError
            )
        )
    }

    suspend fun deleteThread(threadId: String) {
        chatDao.deleteThreadById(threadId)
    }

    suspend fun clearThreadMessages(threadId: String) {
        chatDao.clearThreadMessages(threadId)
    }

    suspend fun getRecentMessagesSync(threadId: String): List<ChatMessage> {
        return chatDao.getMessagesForThreadSync(threadId).map { it.toDomain() }
    }

    suspend fun getAllThreadIdsSync(): Set<String> {
        return chatDao.getAllThreadIdsSync().toSet()
    }

    private fun ChatThreadEntity.toDomain(): ChatThread = ChatThread(
        id = id,
        title = title,
        preview = preview,
        modelFileName = modelFileName,
        updatedAtMs = updatedAtMs,
        timestampLabel = formatTimestamp(updatedAtMs)
    )

    private fun ChatMessageEntity.toDomain(): ChatMessage = ChatMessage(
        id = id,
        threadId = threadId,
        role = ChatRole.fromRaw(role),
        text = text,
        modelLabel = modelLabel,
        latencyMs = latencyMs,
        tokensPerSec = tokensPerSec,
        tokenCount = tokenCount,
        timestampMs = timestampMs,
        timestampLabel = formatTimestamp(timestampMs),
        isStreaming = false,
        isError = isError
    )

    companion object {
        fun formatTimestamp(ms: Long): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }
}
