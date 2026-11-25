package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quantchat.engine.core.AcceleratorPreference
import com.quantchat.engine.core.CpuScheduleMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Context.hfDataStore by preferencesDataStore(name = "quantchat_hf_prefs")
private val KEY_HF_TOKEN = stringPreferencesKey("hf_access_token")
private val KEY_SYSTEM_PROMPT = stringPreferencesKey("llama_system_prompt")
private val KEY_THREADS = intPreferencesKey("llama_threads")
private val KEY_CONTEXT_SIZE = intPreferencesKey("llama_context_size")
private val KEY_MAX_TOKENS = intPreferencesKey("llama_max_tokens")
private val KEY_TEMPERATURE = floatPreferencesKey("llama_temperature")
private val KEY_LAST_LOADED_FILE = stringPreferencesKey("llama_last_loaded_file")
private val KEY_SCHEDULE_MODE = stringPreferencesKey("cpu_schedule_mode")
private val KEY_AUTO_THERMAL_DOWNSHIFT = booleanPreferencesKey("auto_thermal_downshift")
private val KEY_MANUAL_THREADS_OVERRIDE = intPreferencesKey("manual_threads_override")
private val KEY_CALIBRATED_OPTIMAL_THREADS = intPreferencesKey("calibrated_optimal_threads")
private val KEY_CALIBRATED_DEVICE_SIG = stringPreferencesKey("calibrated_device_sig")
private val KEY_ACCELERATOR_PREFERENCE = stringPreferencesKey("accelerator_preference")
private val KEY_AUTO_GPU_OFFLOAD = booleanPreferencesKey("auto_gpu_offload")
private val KEY_MANUAL_GPU_LAYERS = intPreferencesKey("manual_gpu_layers")

class HfAuthRequiredException(message: String) : IOException(message)
class HfNetworkException(message: String) : IOException(message)

data class DownloadProgressSample(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Int,
    val completed: Boolean = false
)

class HuggingFaceModelRepository(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply {
            if (!exists()) mkdirs()
        }

    val modelsDirectory: File
        get() = modelsDir

    @Volatile
    private var activeCall: Call? = null

    val hfTokenFlow: Flow<String> = context.hfDataStore.data.map { prefs ->
        prefs[KEY_HF_TOKEN].orEmpty()
    }

    val inferenceSettingsFlow: Flow<InferenceSettings> = context.hfDataStore.data.map { prefs ->
        val savedModeName = prefs[KEY_SCHEDULE_MODE]
        val scheduleMode = CpuScheduleMode.entries.firstOrNull { it.name == savedModeName }
            ?: CpuScheduleMode.BALANCED
        val savedAccelName = prefs[KEY_ACCELERATOR_PREFERENCE]
        val acceleratorPref = AcceleratorPreference.entries.firstOrNull { it.name == savedAccelName }
            ?: AcceleratorPreference.AUTO
        val manualOverride = prefs[KEY_MANUAL_THREADS_OVERRIDE]?.coerceIn(1, 12)
        val calibratedThreads = prefs[KEY_CALIBRATED_OPTIMAL_THREADS]?.coerceIn(1, 12)
        InferenceSettings(
            systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: InferenceSettings.DEFAULT_SYSTEM_PROMPT,
            threads = (prefs[KEY_THREADS] ?: 4).coerceIn(1, 12),
            contextSize = (prefs[KEY_CONTEXT_SIZE] ?: 2048).coerceIn(512, 8192),
            maxTokens = (prefs[KEY_MAX_TOKENS] ?: 512).coerceIn(64, 4096),
            temperature = (prefs[KEY_TEMPERATURE] ?: 0.3f).coerceIn(0f, 1.5f),
            lastLoadedDiskFileName = prefs[KEY_LAST_LOADED_FILE],
            scheduleMode = scheduleMode,
            autoThermalDownshift = prefs[KEY_AUTO_THERMAL_DOWNSHIFT] ?: true,
            manualThreadsOverride = manualOverride,
            calibratedOptimalThreads = calibratedThreads,
            calibratedDeviceSignature = prefs[KEY_CALIBRATED_DEVICE_SIG],
            acceleratorPreference = acceleratorPref,
            autoGpuLayerOffload = prefs[KEY_AUTO_GPU_OFFLOAD] ?: true,
            manualGpuLayers = (prefs[KEY_MANUAL_GPU_LAYERS] ?: 0).coerceIn(0, 128)
        )
    }

    suspend fun saveHfToken(token: String) {
        context.hfDataStore.edit { prefs ->
            val trimmed = token.trim()
            if (trimmed.isEmpty()) {
                prefs.remove(KEY_HF_TOKEN)
            } else {
                prefs[KEY_HF_TOKEN] = trimmed
            }
        }
    }

    suspend fun saveInferenceSettings(settings: InferenceSettings) {
        context.hfDataStore.edit { prefs ->
            prefs[KEY_SYSTEM_PROMPT] = settings.systemPrompt.trim().ifEmpty {
                InferenceSettings.DEFAULT_SYSTEM_PROMPT
            }
            prefs[KEY_THREADS] = settings.threads.coerceIn(1, 12)
            prefs[KEY_CONTEXT_SIZE] = settings.contextSize
            prefs[KEY_MAX_TOKENS] = settings.maxTokens
            prefs[KEY_TEMPERATURE] = settings.temperature
            if (settings.lastLoadedDiskFileName.isNullOrBlank()) {
                prefs.remove(KEY_LAST_LOADED_FILE)
            } else {
                prefs[KEY_LAST_LOADED_FILE] = settings.lastLoadedDiskFileName
            }
            prefs[KEY_SCHEDULE_MODE] = settings.scheduleMode.name
            prefs[KEY_AUTO_THERMAL_DOWNSHIFT] = settings.autoThermalDownshift
            if (settings.manualThreadsOverride == null) {
                prefs.remove(KEY_MANUAL_THREADS_OVERRIDE)
            } else {
                prefs[KEY_MANUAL_THREADS_OVERRIDE] = settings.manualThreadsOverride.coerceIn(1, 12)
            }
            if (settings.calibratedOptimalThreads == null || settings.calibratedDeviceSignature.isNullOrBlank()) {
                prefs.remove(KEY_CALIBRATED_OPTIMAL_THREADS)
                prefs.remove(KEY_CALIBRATED_DEVICE_SIG)
            } else {
                prefs[KEY_CALIBRATED_OPTIMAL_THREADS] = settings.calibratedOptimalThreads.coerceIn(1, 12)
                prefs[KEY_CALIBRATED_DEVICE_SIG] = settings.calibratedDeviceSignature
            }
            prefs[KEY_ACCELERATOR_PREFERENCE] = settings.acceleratorPreference.name
            prefs[KEY_AUTO_GPU_OFFLOAD] = settings.autoGpuLayerOffload
            prefs[KEY_MANUAL_GPU_LAYERS] = settings.manualGpuLayers.coerceIn(0, 128)
        }
    }

    suspend fun saveCalibratedThreads(optimalThreads: Int, deviceSignature: String) {
        context.hfDataStore.edit { prefs ->
            prefs[KEY_CALIBRATED_OPTIMAL_THREADS] = optimalThreads.coerceIn(1, 12)
            prefs[KEY_CALIBRATED_DEVICE_SIG] = deviceSignature
            prefs[KEY_THREADS] = optimalThreads.coerceIn(1, 12)
        }
    }

    suspend fun saveLastLoadedModel(diskFileName: String?) {
        context.hfDataStore.edit { prefs ->
            if (diskFileName.isNullOrBlank()) {
                prefs.remove(KEY_LAST_LOADED_FILE)
            } else {
                prefs[KEY_LAST_LOADED_FILE] = diskFileName
            }
        }
    }

    fun resolveLocalFile(diskFileName: String): File? {
        val target = File(modelsDir, diskFileName)
        return target.takeIf { it.exists() && it.isFile }
    }

    suspend fun importLocalGgufUri(uri: Uri): LocalWeightFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "imported_model.gguf"
        var declaredSize = 0L

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) {
                    cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                }
                if (sizeIdx >= 0) {
                    declaredSize = cursor.getLong(sizeIdx)
                }
            }
        }

        if (!displayName.endsWith(".gguf", ignoreCase = true)) {
            throw IOException("Selected file \"$displayName\" is not a .gguf model file.")
        }

        val safeLeaf = displayName.substringAfterLast("/").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val diskName = "local__$safeLeaf"
        val finalFile = File(modelsDir, diskName)
        val partFile = File(modelsDir, "$diskName.part")

        resolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(4)
            val headerRead = input.read(header)
            val isGgufMagic = headerRead == 4 &&
                header[0] == 'G'.code.toByte() &&
                header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            if (!isGgufMagic) {
                throw IOException("Invalid GGUF header in \"$displayName\". Make sure this is an uncorrupted GGUF checkpoint.")
            }

            FileOutputStream(partFile, false).use { out ->
                out.write(header, 0, 4)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        } ?: throw IOException("Unable to open selected file stream.")

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            throw IOException("Failed to finalize imported model in app storage.")
        }

        LocalWeightFile(
            diskFileName = finalFile.name,
            displayFileName = safeLeaf,
            repoId = "Local Import",
            sizeBytes = finalFile.length().coerceAtLeast(declaredSize),
            isPartial = false,
            downloadUrl = null
        )
    }

    fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun searchGgufModels(query: String, token: String?): List<HubModelItem> =
        withContext(Dispatchers.IO) {
            if (!isNetworkConnected()) {
                throw HfNetworkException("No active internet connection. Connect to Wi-Fi or cellular data to search Hugging Face Hub.")
            }
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://huggingface.co/api/models?search=$encoded&filter=gguf&sort=downloads&direction=-1&limit=15"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${token.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw HfNetworkException("Hugging Face Hub returned HTTP ${resp.code}.")
                }
                val body = resp.body?.string().orEmpty()
                val arr = JSONArray(body)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val repoId = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val author = repoId.substringBefore("/", "huggingface")
                        val shortName = repoId.substringAfter("/", repoId)
                        val gatedRaw = obj.opt("gated")
                        val isGated = gatedRaw != null && gatedRaw != false && gatedRaw != JSONObject.NULL && gatedRaw.toString() != "false"
                        val downloads = obj.optLong("downloads", 0L)
                        val likes = obj.optLong("likes", 0L)
                        val inferredParams = inferParamsLabel(shortName)
                        add(
                            HubModelItem(
                                id = "hub_${repoId.replace("/", "_")}",
                                displayName = shortName,
                                paramsLabel = inferredParams,
                                repoId = repoId,
                                authorOrg = author,
                                summary = "Official / Hub GGUF repository · ${formatCompactCount(downloads)} downloads · ${formatCompactCount(likes)} likes",
                                isGated = isGated,
                                isCurated = false,
                                downloadsCount = downloads,
                                likesCount = likes
                            )
                        )
                    }
                }
            }
        }

    suspend fun fetchModelGgufFiles(repoId: String, token: String?): List<ModelFileArtifact> =
        withContext(Dispatchers.IO) {
            if (!isNetworkConnected()) {
                throw HfNetworkException("Network offline. Connect to inspect repository files.")
            }
            val url = "https://huggingface.co/api/models/$repoId?blobs=true"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${token.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw HfAuthRequiredException("Repository $repoId requires a Hugging Face Access Token (HTTP ${resp.code}).")
                }
                if (!resp.isSuccessful) {
                    throw HfNetworkException("Failed to inspect $repoId (HTTP ${resp.code}).")
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val gatedVal = json.opt("gated")
                val isGated = gatedVal != null && gatedVal != false && gatedVal != JSONObject.NULL && gatedVal.toString() != "false"

                val siblings = json.optJSONArray("siblings") ?: JSONArray()
                val artifacts = mutableListOf<ModelFileArtifact>()
                for (i in 0 until siblings.length()) {
                    val fileObj = siblings.optJSONObject(i) ?: continue
                    val rfilename = fileObj.optString("rfilename")
                    if (!rfilename.endsWith(".gguf", ignoreCase = true)) continue
                    val lfsObj = fileObj.optJSONObject("lfs")
                    val sizeBytes = lfsObj?.optLong("size", 0L)?.takeIf { it > 0L }
                        ?: fileObj.optLong("size", 0L)
                    val quantTag = extractQuantTag(rfilename)
                    val downloadUrl = "https://huggingface.co/$repoId/resolve/main/$rfilename"
                    artifacts.add(
                        ModelFileArtifact(
                            repoId = repoId,
                            rfilename = rfilename,
                            sizeBytes = sizeBytes,
                            quantTag = quantTag,
                            downloadUrl = downloadUrl
                        )
                    )
                }

                if (artifacts.isEmpty() && isGated && token.isNullOrBlank()) {
                    throw HfAuthRequiredException("Repository $repoId is gated. Add a Hugging Face Access Token to view/download files.")
                }

                artifacts.sortedWith(
                    compareBy<ModelFileArtifact> { quantSortRank(it.quantTag) }
                        .thenBy { it.sizeBytes }
                )
            }
        }

    suspend fun streamDownloadFile(
        repoId: String,
        rfilename: String,
        downloadUrl: String,
        token: String?,
        onProgress: (DownloadProgressSample) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isNetworkConnected()) {
            throw HfNetworkException("Network unavailable. Check connection and resume download.")
        }

        val safeDiskBase = buildDiskFileName(repoId, rfilename)
        val finalFile = File(modelsDir, safeDiskBase)
        val partFile = File(modelsDir, "$safeDiskBase.part")

        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val reqBuilder = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "QuantChat-Android/1.0")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${token.trim()}")
        }
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        val call = client.newCall(reqBuilder.build())
        activeCall = call

        try {
            call.execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw HfAuthRequiredException(
                        "Authorization required (HTTP ${resp.code}) for $repoId. Save your Hugging Face Access Token with gated model access, or use the open GGUF mirror."
                    )
                }
                if (resp.code == 416 && existingBytes > 0L) {
                    if (partFile.renameTo(finalFile)) {
                        onProgress(
                            DownloadProgressSample(
                                bytesDownloaded = finalFile.length(),
                                totalBytes = finalFile.length(),
                                speedBytesPerSec = 0L,
                                etaSeconds = 0,
                                completed = true
                            )
                        )
                    }
                    return@withContext
                }
                if (!resp.isSuccessful) {
                    throw HfNetworkException("Download failed with HTTP ${resp.code}.")
                }

                val isPartialAppend = resp.code == 206 && existingBytes > 0L
                val startOffset = if (isPartialAppend) existingBytes else 0L
                val body = resp.body ?: throw HfNetworkException("Empty response stream from server.")
                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0L) startOffset + contentLength else 0L

                val buffer = ByteArray(32 * 1024)
                var downloaded = startOffset
                var windowStartNs = System.nanoTime()
                var windowBytes = 0L
                var lastEmitMs = 0L
                var latestSpeed = 0L

                FileOutputStream(partFile, isPartialAppend).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            windowBytes += read

                            val nowMs = System.currentTimeMillis()
                            val elapsedNs = System.nanoTime() - windowStartNs
                            if (elapsedNs >= 500_000_000L) {
                                latestSpeed = ((windowBytes * 1_000_000_000.0) / elapsedNs).toLong()
                                windowStartNs = System.nanoTime()
                                windowBytes = 0L
                            }

                            if (nowMs - lastEmitMs >= 200L) {
                                lastEmitMs = nowMs
                                val remaining = (totalBytes - downloaded).coerceAtLeast(0L)
                                val eta = if (latestSpeed > 0L && totalBytes > 0L) {
                                    (remaining / latestSpeed).toInt()
                                } else 0
                                onProgress(
                                    DownloadProgressSample(
                                        bytesDownloaded = downloaded,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = latestSpeed,
                                        etaSeconds = eta,
                                        completed = false
                                    )
                                )
                            }
                        }
                        out.flush()
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    throw IOException("Failed to finalize downloaded weight file.")
                }

                onProgress(
                    DownloadProgressSample(
                        bytesDownloaded = finalFile.length(),
                        totalBytes = finalFile.length(),
                        speedBytesPerSec = latestSpeed,
                        etaSeconds = 0,
                        completed = true
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            activeCall = null
        }
    }

    fun cancelActiveHttpCall() {
        activeCall?.cancel()
        activeCall = null
    }

    fun listLocalWeightFiles(): List<LocalWeightFile> {
        val files = modelsDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && (it.name.endsWith(".gguf") || it.name.endsWith(".gguf.part")) }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val isPart = file.name.endsWith(".part")
                val stripped = if (isPart) file.name.removeSuffix(".part") else file.name
                val repoPart = stripped.substringBefore("__", "local")
                val filePart = stripped.substringAfter("__", stripped)
                val repoId = when {
                    repoPart == "local" -> "Local Import"
                    else -> repoPart.replace("_slash_", "/")
                }
                val url = if (repoId.contains("/")) {
                    "https://huggingface.co/$repoId/resolve/main/$filePart"
                } else null
                LocalWeightFile(
                    diskFileName = file.name,
                    displayFileName = filePart,
                    repoId = repoId,
                    sizeBytes = file.length(),
                    isPartial = isPart,
                    downloadUrl = url
                )
            }
    }

    fun deleteLocalWeightFile(diskFileName: String): Boolean {
        val target = File(modelsDir, diskFileName)
        return target.exists() && target.delete()
    }

    private fun buildDiskFileName(repoId: String, rfilename: String): String {
        val safeRepo = repoId.replace("/", "_slash_")
        val leaf = rfilename.substringAfterLast("/")
        return "${safeRepo}__$leaf"
    }

    private fun extractQuantTag(filename: String): String {
        val upper = filename.uppercase(Locale.US)
        val regex = Regex("(IQ[1-4]_[A-Z0-9_]+|Q[2-8]_[A-Z0-9_]+|BF16|F16|FP16|F32)")
        return regex.find(upper)?.value ?: "GGUF"
    }

    private fun quantSortRank(quantTag: String): Int = when {
        quantTag.contains("Q4_K_M") -> 0
        quantTag.contains("Q4_0") -> 1
        quantTag.contains("Q4_K_S") -> 2
        quantTag.contains("Q5_K_M") -> 3
        quantTag.contains("Q8_0") -> 4
        quantTag.contains("Q3_K") -> 5
        quantTag.contains("F16") || quantTag.contains("BF16") -> 8
        else -> 6
    }

    private fun inferParamsLabel(name: String): String {
        val regex = Regex("(\\d+(?:\\.\\d+)?[Bb])")
        return regex.find(name)?.value?.uppercase(Locale.US) ?: "GGUF"
    }

    private fun formatCompactCount(count: Long): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
