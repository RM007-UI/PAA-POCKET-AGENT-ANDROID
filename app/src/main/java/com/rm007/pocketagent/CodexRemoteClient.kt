package com.rm007.pocketagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental Codex App Server WebSocket client.
 *
 * The Codex WebSocket transport is documented as experimental/unsupported.
 * Use WSS with server-side bearer-token authentication for remote hosts.
 * Plain WS is accepted only for loopback development connections.
 */
class CodexRemoteClient(
    private val onEvent: (JSONObject) -> Unit = {},
    private val onServerRequest: (JSONObject) -> Unit = {},
    private val onConnectionState: (Boolean, String?) -> Unit = { _, _ -> }
) : Closeable {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var initialized = false
    @Volatile private var activeThreadId: String? = null
    private var opened = CompletableDeferred<Unit>()

    val threadId: String?
        get() = activeThreadId

    suspend fun connect(endpoint: String, bearerToken: String) {
        require(!isConnected()) { "از قبل به Codex متصل هستی." }
        val uri = URI(endpoint.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme == "wss" || (scheme == "ws" && isLoopback(uri.host))) {
            "برای اتصال راه دور از wss:// استفاده کن. ws:// فقط برای localhost مجاز است."
        }
        require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
            "آدرس اتصال معتبر نیست."
        }
        require(scheme != "wss" || bearerToken.isNotBlank()) {
            "برای اتصال WSS باید توکن دسترسی سرور را وارد کنی."
        }

        opened = CompletableDeferred()
        val builder = Request.Builder().url(endpoint.trim())
        if (bearerToken.isNotBlank()) builder.header("Authorization", "Bearer $bearerToken")

        socket = http.newWebSocket(builder.build(), listener)
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }

            val init = request(
                "initialize",
                JSONObject().put(
                    "clientInfo",
                    JSONObject()
                        .put("name", "pocket_agent_android")
                        .put("title", "Pocket Agent for Android")
                        .put("version", "0.12.0")
                )
            )
            if (init.has("error")) {
                throw IllegalStateException(init.optJSONObject("error")?.optString("message") ?: "Codex initialization failed")
            }
            check(sendNotificationIfConnected("initialized", JSONObject())) {
                "اعلان پایان راه‌اندازی به سرور ارسال نشد."
            }
            initialized = true
            onConnectionState(true, null)
        } catch (error: Throwable) {
            initialized = false
            socket?.cancel()
            socket = null
            failPending(error.message ?: "راه‌اندازی Codex ناموفق بود.")
            throw error
        }
    }

    suspend fun startThread(
        workingDirectory: String,
        model: String? = null
    ): String {
        check(initialized) { "اول به Codex متصل شو." }
        require(workingDirectory.isNotBlank()) { "مسیر پروژه خالی است." }

        val params = JSONObject()
            .put("cwd", workingDirectory)
            .put("approvalPolicy", "on-request")
            .put("sandbox", "workspace-write")
        if (!model.isNullOrBlank()) params.put("model", model)

        val response = request("thread/start", params)
        response.optJSONObject("error")?.let {
            throw IllegalStateException(it.optString("message", "ساخت گفتگو در Codex ناموفق بود."))
        }
        val id = response.optJSONObject("result")
            ?.optJSONObject("thread")
            ?.optString("id")
            .orEmpty()
        check(id.isNotBlank()) { "سرور شناسه گفتگو برنگرداند." }
        activeThreadId = id
        return id
    }

    suspend fun sendTurn(text: String): JSONObject {
        check(initialized) { "اتصال Codex برقرار نیست." }
        val thread = activeThreadId ?: error("ابتدا یک گفتگوی Codex بساز.")
        require(text.isNotBlank()) { "پیام خالی است." }

        return request(
            "turn/start",
            JSONObject()
                .put("threadId", thread)
                .put(
                    "input",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", text)
                    )
                )
        )
    }

    suspend fun interrupt(turnId: String): JSONObject {
        val thread = activeThreadId ?: error("گفتگوی فعالی وجود ندارد.")
        require(turnId.isNotBlank()) { "شناسه نوبت خالی است." }
        return request(
            "turn/interrupt",
            JSONObject().put("threadId", thread).put("turnId", turnId)
        )
    }

    /**
     * Respond to a server-initiated JSON-RPC request only after the UI has
     * displayed it and the user has made an explicit decision.
     */
    fun respondToServerRequest(requestId: Any, result: JSONObject) {
        val message = JSONObject()
            .put("id", requestId)
            .put("result", result)
        sendRaw(message)
    }

    fun rejectServerRequest(requestId: Any, message: String = "Declined by user") {
        val payload = JSONObject()
            .put("id", requestId)
            .put("error", JSONObject().put("code", -32000).put("message", message))
        sendRaw(payload)
    }

    fun isConnected(): Boolean = socket != null && initialized

    fun disconnect() {
        initialized = false
        activeThreadId = null
        socket?.close(1000, "User disconnected")
        socket = null
        val error = IllegalStateException("اتصال Codex قطع شد.")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        onConnectionState(false, null)
    }

    override fun close() {
        disconnect()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private suspend fun request(method: String, params: JSONObject): JSONObject {
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val sent = sendRaw(
            JSONObject()
                .put("id", id)
                .put("method", method)
                .put("params", params)
        )
        if (!sent) {
            pending.remove(id)
            throw IllegalStateException("ارسال درخواست به Codex ممکن نشد.")
        }
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun sendNotification(method: String, params: JSONObject) {
        sendRaw(JSONObject().put("method", method).put("params", params))
    }

    private fun sendNotificationIfConnected(method: String, params: JSONObject): Boolean =
        sendRaw(JSONObject().put("method", method).put("params", params))

    private fun sendRaw(message: JSONObject): Boolean =
        socket?.send(message.toString()) == true

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                JSONObject(text)
            } catch (_: Exception) {
                onEvent(JSONObject().put("type", "protocol_error").put("message", "پیام نامعتبر از سرور دریافت شد."))
                return
            }

            if (message.has("method")) {
                // Server-initiated requests have a method; handle them before
                // matching response IDs because both peers own an ID namespace.
                onServerRequest(message)
            } else if (message.has("id")) {
                val rawId = message.opt("id")
                val numericId = rawId?.toString()?.toIntOrNull()
                val waiting = numericId?.let { pending.remove(it) }
                if (waiting != null) waiting.complete(message) else onEvent(message)
            } else {
                onEvent(message)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onEvent(JSONObject().put("type", "binary_frame_ignored"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            initialized = false
            socket = null
            activeThreadId = null
            failPending("اتصال Codex بسته شد: $reason")
            onConnectionState(false, reason.takeIf { it.isNotBlank() })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            initialized = false
            socket = null
            activeThreadId = null
            if (!opened.isCompleted) opened.completeExceptionally(t)
            failPending(t.message ?: "خطای ارتباط با Codex")
            onConnectionState(false, t.message)
        }
    }

    private fun failPending(message: String) {
        val error = IllegalStateException(message)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private fun isLoopback(host: String?): Boolean {
        val normalized = host?.lowercase() ?: return false
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
