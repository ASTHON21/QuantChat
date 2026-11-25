package com.example.data

import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.CpuScheduleMode
import com.quantchat.engine.core.EngineModelDetails
import com.quantchat.model.GgufHeaderMetadata
import com.quantchat.resource.AdmissionAssessment

// --- Local Chat & Persistence Models ---

enum class ChatRole(val raw: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromRaw(value: String): ChatRole =
            entries.firstOrNull { it.raw == value } ?: USER
    }
}

data class ChatThread(
    val id: String,
    val title: String,
    val preview: String,
    val modelFileName: String?,
    val updatedAtMs: Long,
    val timestampLabel: String
)

data class ChatMessage(
    val id: String,
    val threadId: String,
    val role: ChatRole,
    val text: String,
    val modelLabel: String? = null,
    val latencyMs: Long? = null,
    val tokensPerSec: Float? = null,
    val tokenCount: Int? = null,
    val timestampMs: Long,
    val timestampLabel: String,
    val isStreaming: Boolean = false,
    val isError: Boolean = false
)

// --- On-Device llama.cpp Inference Settings & Runtime State ---

data class InferenceSettings(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val threads: Int = 4,
    val contextSize: Int = 2048,
    val maxTokens: Int = 512,
    val temperature: Float = 0.3f,
    val lastLoadedDiskFileName: String? = null,
    val scheduleMode: CpuScheduleMode = CpuScheduleMode.BALANCED,
    val autoThermalDownshift: Boolean = true,
    val manualThreadsOverride: Int? = null,
    val calibratedOptimalThreads: Int? = null,
    val calibratedDeviceSignature: String? = null,
    val acceleratorPreference: AcceleratorPreference = AcceleratorPreference.AUTO,
    val autoGpuLayerOffload: Boolean = true,
    val manualGpuLayers: Int = 0
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a concise, accurate offline assistant running locally on-device. Give direct, well-structured answers in the user's language."
    }
}

sealed interface LocalRuntimeState {
    data class Unloaded(val hint: String? = null) : LocalRuntimeState
    data class Loading(val file: LocalWeightFile) : LocalRuntimeState
    data class Ready(
        val file: LocalWeightFile,
        val contextId: Int,
        val contextSize: Int,
        val threads: Int,
        val modelDetails: EngineModelDetails? = null,
        val headerMetadata: GgufHeaderMetadata? = null
    ) : LocalRuntimeState
    data class Error(val file: LocalWeightFile?, val message: String) : LocalRuntimeState
}

sealed interface LocalStreamEvent {
    data class Delta(val accumulatedText: String, val tokenCount: Int) : LocalStreamEvent
    data class Completed(
        val finalText: String,
        val elapsedMs: Long,
        val tokenCount: Int,
        val tokensPerSecond: Float,
        val wasStopped: Boolean
    ) : LocalStreamEvent
}

// --- Models Hub & Real OkHttp Streaming Download Models ---

enum class HubModelTab(val label: String) {
    CURATED("Curated (4)"),
    HUB_RESULTS("Hub Results"),
    DOWNLOADED("Downloaded")
}

data class ModelFileArtifact(
    val repoId: String,
    val rfilename: String,
    val sizeBytes: Long,
    val quantTag: String,
    val downloadUrl: String
)

data class HubModelItem(
    val id: String,
    val displayName: String,
    val paramsLabel: String,
    val repoId: String,
    val fallbackOpenRepoId: String? = null,
    val authorOrg: String,
    val summary: String,
    val isGated: Boolean = false,
    val isCurated: Boolean = true,
    val downloadsCount: Long = 0L,
    val likesCount: Long = 0L
) {
    val repoUrl: String
        get() = "https://huggingface.co/$repoId"
}

enum class DownloadPhase {
    IDLE,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    ERROR_AUTH_REQUIRED,
    ERROR_NETWORK
}

data class ActiveDownloadState(
    val modelId: String = "",
    val modelDisplayName: String = "",
    val repoId: String = "",
    val fallbackOpenRepoId: String? = null,
    val fileName: String = "",
    val downloadUrl: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Int = 0,
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt()
}

data class LocalWeightFile(
    val diskFileName: String,
    val displayFileName: String,
    val repoId: String,
    val sizeBytes: Long,
    val isPartial: Boolean,
    val downloadUrl: String? = null,
    val headerMetadata: GgufHeaderMetadata? = null,
    val admission: AdmissionAssessment? = null
) {
    val quantTag: String
        get() {
            val upper = displayFileName.uppercase()
            val regex = Regex("(IQ[1-4]_[A-Z0-9_]+|Q[2-8]_[A-Z0-9_]+|BF16|F16|FP16|F32)")
            return regex.find(upper)?.value ?: "GGUF"
        }
}

data class HfAuthState(
    val token: String = "",
    val isLoaded: Boolean = false
) {
    val hasToken: Boolean
        get() = token.isNotBlank()

    val maskedPreview: String
        get() = when {
            token.isBlank() -> "Not Set"
            token.length <= 8 -> "••••••••"
            else -> "${token.take(5)}••••${token.takeLast(4)}"
        }
}

sealed interface HubSearchUiState {
    data object Idle : HubSearchUiState
    data object Loading : HubSearchUiState
    data class Success(val query: String, val items: List<HubModelItem>) : HubSearchUiState
    data class Error(val message: String) : HubSearchUiState
}

sealed interface FileInspectionUiState {
    data object Closed : FileInspectionUiState
    data class Loading(val model: HubModelItem) : FileInspectionUiState
    data class Ready(val model: HubModelItem, val files: List<ModelFileArtifact>) : FileInspectionUiState
    data class Error(val model: HubModelItem, val message: String, val authRequired: Boolean = false) : FileInspectionUiState
}

object CuratedModelsCatalog {
    val curatedModels: List<HubModelItem> = listOf(
        HubModelItem(
            id = "qwen3_5_4b",
            displayName = "Qwen3.5",
            paramsLabel = "4B",
            repoId = "ggml-org/Qwen3.5-4B-GGUF",
            fallbackOpenRepoId = "unsloth/Qwen3.5-4B-GGUF",
            authorOrg = "Qwen / ggml-org",
            summary = "Official GGUF conversion of Qwen/Qwen3.5-4B hybrid Gated-DeltaNet + Attention model for fast on-device reasoning.",
            isGated = false,
            isCurated = true
        ),
        HubModelItem(
            id = "qwen3_4b",
            displayName = "Qwen3",
            paramsLabel = "4B",
            repoId = "Qwen/Qwen3-4B-GGUF",
            authorOrg = "Qwen",
            summary = "Official Qwen3-4B GGUF weights by Alibaba Qwen team for structured math, coding, and multilingual offline chat.",
            isGated = false,
            isCurated = true
        ),
        HubModelItem(
            id = "gemma3_4b",
            displayName = "Gemma 3",
            paramsLabel = "4B",
            repoId = "google/gemma-3-4b-it-qat-q4_0-gguf",
            fallbackOpenRepoId = "bartowski/google_gemma-3-4b-it-GGUF",
            authorOrg = "Google",
            summary = "Official Google Gemma 3 4B QAT Q4_0 GGUF release (128K context). Official repo may require HF token; open GGUF mirror available.",
            isGated = true,
            isCurated = true
        ),
        HubModelItem(
            id = "phi4_mini_3_8b",
            displayName = "Phi-4-mini",
            paramsLabel = "3.8B",
            repoId = "bartowski/microsoft_Phi-4-mini-instruct-GGUF",
            fallbackOpenRepoId = "unsloth/Phi-4-mini-instruct-GGUF",
            authorOrg = "Microsoft / Bartowski",
            summary = "3.8B dense instruct model (128K context) with imatrix llama.cpp quantizations for low-memory edge inference.",
            isGated = false,
            isCurated = true
        )
    )
}
