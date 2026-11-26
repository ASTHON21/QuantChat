package com.quantchat.session

import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.InferenceBackend
import com.quantchat.engine.core.ModelId
import com.quantchat.engine.core.PromptTemplateFormatter
import com.quantchat.engine.core.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ContextBudgetSnapshot(
    val promptTokens: Int,
    val contextLimit: Int,
    val trimmedTurnsCount: Int,
    val keptTurns: List<ChatTurn>,
    val kvCacheHit: Boolean
)

data class SessionCacheMetadata(
    val threadId: String,
    val sessionId: SessionId,
    val fingerprint: String,
    val cachedTokens: Int,
    val cacheFilePath: String
)

class SessionCacheManager(private val sessionsDir: File) {

    init {
        if (!sessionsDir.exists()) {
            sessionsDir.mkdirs()
        }
    }

    fun buildFingerprint(
        modelId: ModelId,
        contextSize: Int,
        systemPrompt: String
    ): String {
        val input = "${modelId.value}|$contextSize|${systemPrompt.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(Locale.US, it) }
    }

    suspend fun prepareContextAndBudget(
        backend: InferenceBackend,
        threadId: String,
        fingerprint: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        modelFamilyHint: String,
        contextSize: Int,
        maxOutputTokens: Int
    ): ContextBudgetSnapshot {
        val maxPromptBudget = (contextSize - maxOutputTokens.coerceAtMost(contextSize / 2))
            .coerceAtLeast(256)

        var candidateHistory = history.takeLast(12)
        var trimmed = (history.size - candidateHistory.size).coerceAtLeast(0)

        var currentTokens = countPromptTokens(
            backend = backend,
            systemPrompt = systemPrompt,
            history = candidateHistory,
            userPrompt = userPrompt,
            modelFamilyHint = modelFamilyHint
        )

        while (currentTokens > maxPromptBudget && candidateHistory.isNotEmpty()) {
            candidateHistory = candidateHistory.drop(1)
            trimmed++
            currentTokens = countPromptTokens(
                backend = backend,
                systemPrompt = systemPrompt,
                history = candidateHistory,
                userPrompt = userPrompt,
                modelFamilyHint = modelFamilyHint
            )
        }

        val sessionHit = hasValidSnapshot(threadId, fingerprint)
        return ContextBudgetSnapshot(
            promptTokens = currentTokens,
            contextLimit = contextSize,
            trimmedTurnsCount = trimmed,
            keptTurns = candidateHistory,
            kvCacheHit = sessionHit
        )
    }

    suspend fun tryRestoreSession(
        backend: InferenceBackend,
        threadId: String,
        fingerprint: String
    ): SessionCacheMetadata? = withContext(Dispatchers.IO) {
        val sessionFile = snapshotFile(threadId)
        val metaFile = metaFile(threadId)
        if (!sessionFile.exists() || !metaFile.exists()) return@withContext null

        val savedFingerprint = runCatching { metaFile.readText().trim() }.getOrNull()
        if (savedFingerprint != fingerprint) {
            return@withContext null
        }

        val sessionId = SessionId("${threadId}_$fingerprint")
        val loadedCount = runCatching {
            backend.loadSession(sessionId, sessionFile.absolutePath)
        }.getOrNull()

        if (loadedCount == null || loadedCount < 0) {
            invalidateThread(threadId)
            return@withContext null
        }

        SessionCacheMetadata(
            threadId = threadId,
            sessionId = sessionId,
            fingerprint = fingerprint,
            cachedTokens = loadedCount,
            cacheFilePath = sessionFile.absolutePath
        )
    }

    suspend fun persistSession(
        backend: InferenceBackend,
        threadId: String,
        fingerprint: String,
        maxTokens: Int
    ): SessionCacheMetadata? = withContext(Dispatchers.IO) {
        val sessionFile = snapshotFile(threadId)
        val metaFile = metaFile(threadId)
        val sessionId = SessionId("${threadId}_$fingerprint")

        val result = runCatching {
            backend.saveSession(sessionId, sessionFile.absolutePath, maxTokens)
        }.getOrNull() ?: return@withContext null

        runCatching { metaFile.writeText(fingerprint) }
        SessionCacheMetadata(
            threadId = threadId,
            sessionId = sessionId,
            fingerprint = fingerprint,
            cachedTokens = result.tokensSaved,
            cacheFilePath = result.path
        )
    }

    fun invalidateThread(threadId: String) {
        runCatching { snapshotFile(threadId).delete() }
        runCatching { metaFile(threadId).delete() }
    }

    fun invalidateAll() {
        sessionsDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun pruneOrphanedCaches(
        validThreadIds: Set<String>?,
        maxAgeMs: Long = 14L * 24L * 3600L * 1000L,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val files = sessionsDir.listFiles() ?: return 0
        val validSafeIds = validThreadIds?.map { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }?.toSet()
        var deleted = 0
        for (file in files) {
            val baseName = when {
                file.name.endsWith(".llama_session") -> file.name.removeSuffix(".llama_session")
                file.name.endsWith(".meta") -> file.name.removeSuffix(".meta")
                else -> file.name
            }
            val orphaned = validSafeIds != null && baseName !in validSafeIds
            val expired = maxAgeMs > 0L && (nowMs - file.lastModified()) > maxAgeMs
            if (orphaned || expired) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deleted++
                }
            }
        }
        return deleted
    }

    private fun hasValidSnapshot(threadId: String, fingerprint: String): Boolean {
        val sessionFile = snapshotFile(threadId)
        val metaFile = metaFile(threadId)
        if (!sessionFile.exists() || !metaFile.exists()) return false
        return runCatching { metaFile.readText().trim() == fingerprint }.getOrDefault(false)
    }

    private suspend fun countPromptTokens(
        backend: InferenceBackend,
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        modelFamilyHint: String
    ): Int {
        val turns = PromptTemplateFormatter.buildTurns(systemPrompt, history, userPrompt)
        val formatted = runCatching { backend.formatChat(turns, "") }.getOrNull()
            ?: PromptTemplateFormatter.formatFallback(modelFamilyHint, systemPrompt, history, userPrompt)
        return runCatching { backend.tokenize(formatted).size }
            .getOrElse { (formatted.length / 4).coerceAtLeast(1) }
    }

    private fun snapshotFile(threadId: String): File {
        val safeId = threadId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(sessionsDir, "$safeId.llama_session")
    }

    private fun metaFile(threadId: String): File {
        val safeId = threadId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(sessionsDir, "$safeId.meta")
    }
}
