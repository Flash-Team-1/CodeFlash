package dev.ide.core.collab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Supabase Realtime over a Phoenix socket (protocol v1): joins private channels with the signed-in token,
 * sends broadcasts, and reconnects when the socket drops. A broadcast waits until its channel has acknowledged
 * the join, so nothing is sent into a channel that is not open yet.
 */
internal class CollabRealtime(
    private val url: () -> String?,
    private val token: () -> String?,
    private val scope: CoroutineScope,
    private val onMessage: (topic: String, event: String, payload: JsonObject) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var open = false
    private var refCounter = 0
    private var heartbeat: Job? = null
    private val wanted = HashSet<String>()          // channels we should be in
    private val acked = HashSet<String>()           // channels joined on this socket
    private val queued = ArrayList<Pair<String, JsonObject>>() // broadcasts waiting for their join

    fun join(topic: String) {
        synchronized(lock) {
            wanted.add(topic)
            if (socket == null) {
                connect()
            } else if (open) {
                sendJoin(topic)
            }
        }
    }

    fun leave(topic: String) {
        synchronized(lock) {
            wanted.remove(topic)
            acked.remove(topic)
            queued.removeAll { it.first == topic }
            if (open) push(topic, "phx_leave", JsonObject(emptyMap()))
        }
    }

    fun broadcast(topic: String, event: String, payload: JsonObject) {
        val body = buildJsonObject {
            put("type", "broadcast")
            put("event", event)
            put("payload", payload)
        }
        synchronized(lock) {
            if (acked.contains(topic)) {
                push(topic, "broadcast", body)
            } else {
                queued.add(topic to body)
            }
        }
    }

    private fun connect() {
        val u = url() ?: return
        socket = http.newWebSocket(Request.Builder().url(u).build(), listener)
    }

    private fun sendJoin(topic: String) {
        push(topic, "phx_join", buildJsonObject {
            put("config", buildJsonObject {
                put("broadcast", buildJsonObject { put("self", false) })
                put("private", true)
            })
            put("access_token", token() ?: "")
        })
    }

    /** Sends one message. Call with [lock] held. */
    private fun push(topic: String, event: String, payload: JsonObject) {
        val s = socket ?: return
        val msg = buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", (++refCounter).toString())
        }
        s.send(msg.toString())
    }

    private fun flush(topic: String) {
        val it = queued.iterator()
        while (it.hasNext()) {
            val (t, body) = it.next()
            if (t == topic) {
                it.remove()
                push(t, "broadcast", body)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(25_000)
                synchronized(lock) {
                    if (open) push("phoenix", "heartbeat", JsonObject(emptyMap()))
                }
            }
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val topic = msg["topic"]?.jsonPrimitive?.contentOrNull ?: return
        when (msg["event"]?.jsonPrimitive?.contentOrNull) {
            "phx_reply" -> {
                val status = msg["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                synchronized(lock) {
                    if (status == "ok" && wanted.contains(topic)) {
                        acked.add(topic)
                        flush(topic)
                    }
                }
            }
            "broadcast" -> {
                val p = msg["payload"]?.jsonObject ?: return
                val event = p["event"]?.jsonPrimitive?.contentOrNull ?: return
                val inner = p["payload"]?.jsonObject ?: return
                onMessage(topic, event, inner)
            }
            "phx_error", "phx_close" -> synchronized(lock) { acked.remove(topic) }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            open = true
            acked.clear()
            wanted.forEach { sendJoin(it) }
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
    }

    private fun dropped(webSocket: WebSocket) {
        synchronized(lock) {
            if (socket !== webSocket) return
            open = false
            socket = null
            acked.clear()
            heartbeat?.cancel()
        }
        scope.launch {
            delay(3_000)
            synchronized(lock) {
                if (socket == null && wanted.isNotEmpty()) connect()
            }
        }
    }
}
