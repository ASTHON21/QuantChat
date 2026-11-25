package com.quantchat.server

import com.quantchat.engine.core.ChatTurn
import com.quantchat.engine.core.EngineLifecycleState
import com.quantchat.engine.core.GenerationEvent
import com.quantchat.engine.core.GenerationRequest
import com.quantchat.engine.core.InferenceBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

sealed interface LocalServerState {
    data object Stopped : LocalServerState
    data class Running(val host: String, val port: Int, val requestsServed: Long = 0L) : LocalServerState {
        val baseUrl: String get() = "http://$host:$port/v1"
    }
    data class Error(val message: String) : LocalServerState
}

data class HttpRouteResult(
    val statusCode: Int,
    val statusText: String,
    val contentType: String = "application/json; charset=utf-8",
    val body: String
)

class LocalOpenAiRouter(
    private val backend: InferenceBackend
) {
    suspend fun handleHttpRequest(
        method: String,
        path: String,
        body: String
    ): HttpRouteResult {
        val normalizedPath = path.substringBefore('?')
        return when {
            method.equals("GET", ignoreCase = true) && normalizedPath == "/v1/models" ->
                handleModels()

            method.equals("POST", ignoreCase = true) && normalizedPath == "/v1/chat/completions" ->
                handleChatCompletion(body)

            method.equals("POST", ignoreCase = true) && normalizedPath == "/v1/embeddings" ->
                handleEmbeddings(body)

            else -> HttpRouteResult(
                statusCode = 404,
                statusText = "Not Found",
                body = errorJson("Unknown endpoint $method $normalizedPath", "invalid_request_error")
            )
        }
    }

    private fun handleModels(): HttpRouteResult {
        val data = JSONArray()
        val ready = backend.lifecycleState.value as? EngineLifecycleState.Ready
        if (ready != null) {
            data.put(
                JSONObject().apply {
                    put("id", ready.config.displayName)
                    put("object", "model")
                    put("owned_by", "quantchat-local")
                    put("context_length", ready.config.contextSize)
                }
            )
        }
        val payload = JSONObject().apply {
            put("object", "list")
            put("data", data)
        }
        return HttpRouteResult(200, "OK", body = payload.toString())
    }

    private suspend fun handleChatCompletion(body: String): HttpRouteResult {
        val ready = backend.lifecycleState.value as? EngineLifecycleState.Ready
            ?: return HttpRouteResult(
                statusCode = 503,
                statusText = "Service Unavailable",
                body = errorJson("No local GGUF model loaded in QuantChat.", "model_not_loaded")
            )

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return HttpRouteResult(
                statusCode = 400,
                statusText = "Bad Request",
                body = errorJson("Invalid JSON request body.", "invalid_request_error")
            )

        val messagesJson = json.optJSONArray("messages")
            ?: return HttpRouteResult(
                statusCode = 400,
                statusText = "Bad Request",
                body = errorJson("Missing required 'messages' array.", "invalid_request_error")
            )

        var systemPrompt = ""
        val turns = mutableListOf<ChatTurn>()
        for (i in 0 until messagesJson.length()) {
            val item = messagesJson.optJSONObject(i) ?: continue
            val role = item.optString("role", "user")
            val content = item.optString("content", "")
            if (role == "system") {
                systemPrompt = if (systemPrompt.isBlank()) content else "$systemPrompt\n$content"
            } else if (content.isNotBlank()) {
                turns += ChatTurn(role = role, content = content)
            }
        }

        val lastUserIdx = turns.indexOfLast { it.role == "user" }
        val userPrompt = if (lastUserIdx >= 0) turns[lastUserIdx].content else ""
        val history = if (lastUserIdx > 0) turns.subList(0, lastUserIdx) else emptyList()

        val maxTokens = json.optInt("max_tokens", 512).coerceIn(16, 4096)
        val temperature = json.optDouble("temperature", 0.3).toFloat().coerceIn(0f, 1.5f)

        val request = GenerationRequest(
            modelId = ready.modelId,
            systemPrompt = systemPrompt,
            history = history,
            userPrompt = userPrompt,
            modelFamilyHint = ready.config.displayName,
            maxTokens = maxTokens,
            temperature = temperature
        )

        val completed = runCatching {
            var lastEvent: GenerationEvent.Completed? = null
            backend.generate(request).collect { evt ->
                if (evt is GenerationEvent.Completed) lastEvent = evt
            }
            lastEvent
        }.getOrElse { err ->
            return HttpRouteResult(
                statusCode = 500,
                statusText = "Internal Server Error",
                body = errorJson(err.message ?: "Local inference failed.", "engine_error")
            )
        } ?: return HttpRouteResult(
            statusCode = 500,
            statusText = "Internal Server Error",
            body = errorJson("Local model returned no completion event.", "engine_error")
        )

        val responseJson = JSONObject().apply {
            put("id", "chatcmpl-${UUID.randomUUID().toString().take(12)}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000L)
            put("model", ready.config.displayName)
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", completed.finalText)
                    })
                    put("finish_reason", if (completed.wasStopped) "stop" else "stop")
                })
            })
            put("usage", JSONObject().apply {
                put("completion_tokens", completed.tokenCount)
                put("elapsed_ms", completed.elapsedMs)
            })
        }
        return HttpRouteResult(200, "OK", body = responseJson.toString())
    }

    private suspend fun handleEmbeddings(body: String): HttpRouteResult {
        val ready = backend.lifecycleState.value as? EngineLifecycleState.Ready
            ?: return HttpRouteResult(
                statusCode = 503,
                statusText = "Service Unavailable",
                body = errorJson("No local model loaded.", "model_not_loaded")
            )

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return HttpRouteResult(400, "Bad Request", body = errorJson("Invalid JSON.", "invalid_request_error"))
        val input = json.optString("input", "")
        if (input.isBlank()) {
            return HttpRouteResult(400, "Bad Request", body = errorJson("Missing 'input' text.", "invalid_request_error"))
        }

        val vec = runCatching { backend.embed(input) }.getOrElse { err ->
            return HttpRouteResult(
                statusCode = 400,
                statusText = "Bad Request",
                body = errorJson(err.message ?: "Embedding unsupported", "unsupported_capability")
            )
        }

        val payload = JSONObject().apply {
            put("object", "list")
            put("model", ready.config.displayName)
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("object", "embedding")
                    put("index", 0)
                    put("embedding", JSONArray().apply { vec.forEach { put(it.toDouble()) } })
                })
            })
        }
        return HttpRouteResult(200, "OK", body = payload.toString())
    }

    private fun errorJson(message: String, type: String): String =
        JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", message)
                put("type", type)
            })
        }.toString()
}

class LocalHttpServer(
    private val router: LocalOpenAiRouter
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _state = MutableStateFlow<LocalServerState>(LocalServerState.Stopped)
    val state: StateFlow<LocalServerState> = _state.asStateFlow()

    fun start(port: Int = 11435) {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            try {
                val socket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                var count = 0L
                _state.value = LocalServerState.Running("127.0.0.1", socket.localPort, count)
                while (isActive && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    count++
                    _state.value = LocalServerState.Running("127.0.0.1", socket.localPort, count)
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _state.value = LocalServerState.Error(e.localizedMessage ?: "Failed to bind loopback server.")
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.value = LocalServerState.Stopped
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }

            var contentLength = 0
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val bodyChars = if (contentLength > 0 && contentLength <= 1_048_576) {
                CharArray(contentLength).also { buf ->
                    var offset = 0
                    while (offset < contentLength) {
                        val read = reader.read(buf, offset, contentLength - offset)
                        if (read == -1) break
                        offset += read
                    }
                }
            } else {
                CharArray(0)
            }

            val result = router.handleHttpRequest(method, path, String(bodyChars))
            val bodyBytes = result.body.toByteArray(Charsets.UTF_8)
            val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
            writer.write("HTTP/1.1 ${result.statusCode} ${result.statusText}\r\n")
            writer.write("Content-Type: ${result.contentType}\r\n")
            writer.write("Content-Length: ${bodyBytes.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(result.body)
            writer.flush()
        }
    }
}
