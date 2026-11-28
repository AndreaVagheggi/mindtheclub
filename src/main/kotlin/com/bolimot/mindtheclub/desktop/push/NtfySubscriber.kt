package com.bolimot.mindtheclub.desktop.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Receives push messages on a PC by holding a WebSocket open to an ntfy server.
 *
 * On a de-Google phone the ntfy app is the UnifiedPush "distributor": it owns the connection
 * and hands messages to MindTheClub. A PC has no distributor, so this class plays that part
 * for itself: it invents a topic, subscribes to it, and the topic's URL is the endpoint the
 * backend posts to.
 *
 * FOR THE FEASIBILITY CHECKS ONLY. Whether production uses ntfy or a worker of our own is not
 * decided. Two properties of ntfy matter for that decision:
 *   - it keeps a message for 12 hours and no longer;
 *   - it accepts a message for a UnifiedPush topic only while somebody is subscribed.
 *
 * ntfy sees an opaque random topic and an encrypted body, nothing else.
 *
 * A connection can die without saying so: the network drops with no closing handshake and
 * the socket simply goes quiet (27 Nov 2026, 23:58: a group message never reached the PC,
 * which kept running deaf until it was switched off). Measured on ntfy.sh, 28 Nov 2026: on a
 * websocket it sends no "keepalive" event, but a websocket ping every 45 seconds, and it
 * answers the client's own ping within a fraction of a second. Both are used: Engine sends a
 * ping every half minute ([ping]), and every frame, ntfy's ping, the pong to ours or any
 * message, moves [lastFrameAt]. A dead connection answers nothing; Engine then opens a new subscriber. The new
 * one takes the same [topic], so the endpoint registered for the identity stays valid, and
 * asks with "since" for what ntfy kept after the last message received here ([lastMessageId]).
 */
class NtfySubscriber(
    private val onMessage: (ByteArray) -> Unit,
    private val onClosed: (String) -> Unit,
    /** "up" plus 12 random characters, the shape the ntfy app itself uses for UnifiedPush. */
    val topic: String = "up" + randomText(12),
    previousMessageId: String? = null
) : WebSocket.Listener {

    /** What goes to updateMyPushEndpoint. "up=1" tells ntfy the body is a UnifiedPush message. */
    val endpoint: String = "https://$HOST/$topic?up=1"

    private val buffer = StringBuilder()
    private val opened = CompletableFuture<Boolean>()
    @Volatile private var socket: WebSocket? = null

    /** When the last frame of any kind arrived (or this subscriber was made). */
    @Volatile var lastFrameAt: Long = System.currentTimeMillis()
        private set

    /** The id of the last message received, handed on to the next subscriber's "since". */
    @Volatile var lastMessageId: String? = previousMessageId
        private set

    /** True once the connection closed or failed; a closed subscriber is never reopened. */
    @Volatile var closed: Boolean = false
        private set

    /**
     * Connects and waits until ntfy confirms the subscription. False when it does not. With
     * [since] (a message id, or a Unix time in seconds) ntfy first sends what it kept after it.
     */
    fun connect(timeoutSeconds: Long = 20, since: String? = null): Boolean {
        val query = if (since.isNullOrEmpty()) "" else "?since=" + URLEncoder.encode(since, Charsets.UTF_8)
        return try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .buildAsync(URI.create("wss://$HOST/$topic/ws$query"), this)
                .get(timeoutSeconds, TimeUnit.SECONDS)
            opened.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    /** A websocket ping: the server's pong moves [lastFrameAt]. A failure means a dead socket. */
    fun ping() {
        try {
            socket?.sendPing(ByteBuffer.allocate(0))
        } catch (e: Exception) {
            // Nothing answers: the silence is what Engine looks at.
        }
    }

    override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
        lastFrameAt = System.currentTimeMillis()
        webSocket.request(1)
        return null
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
        lastFrameAt = System.currentTimeMillis()
        // The Java client answers a ping with a pong by itself.
        webSocket.request(1)
        return null
    }

    /** Drops the connection without waiting for the server: used on a connection gone quiet. */
    fun abort() {
        closed = true
        try {
            socket?.abort()
        } catch (e: Exception) {
            // Already gone.
        }
    }

    override fun onOpen(webSocket: WebSocket) {
        socket = webSocket
        lastFrameAt = System.currentTimeMillis()
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        lastFrameAt = System.currentTimeMillis()
        buffer.append(data)
        if (last) {
            val frame = buffer.toString()
            buffer.setLength(0)
            try {
                handle(frame)
            } catch (e: Exception) {
                // One unreadable frame must not end the subscription.
            }
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        opened.complete(false)
        val wasOpen = !closed
        closed = true
        if (wasOpen) onClosed("chiusa dal server ($statusCode)")
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        opened.complete(false)
        val wasOpen = !closed
        closed = true
        if (wasOpen) onClosed(error.message ?: error.javaClass.simpleName)
    }

    /**
     * One ntfy event, a JSON object. "open" confirms the subscription, "keepalive" is noise,
     * "message" carries a push. A binary body, which an encrypted push always is, arrives as
     * base64 with "encoding":"base64".
     */
    private fun handle(frame: String) {
        val event = Json.parseToJsonElement(frame).jsonObject
        when ((event["event"] as? JsonPrimitive)?.contentOrNull) {
            "open" -> opened.complete(true)
            "message" -> {
                (event["id"] as? JsonPrimitive)?.contentOrNull?.let { lastMessageId = it }
                val message = (event["message"] as? JsonPrimitive)?.contentOrNull ?: return
                val encoding = (event["encoding"] as? JsonPrimitive)?.contentOrNull
                val bytes = if (encoding == "base64") {
                    try {
                        Base64.getDecoder().decode(message)
                    } catch (e: IllegalArgumentException) {
                        Base64.getUrlDecoder().decode(message)
                    }
                } else {
                    message.toByteArray(Charsets.UTF_8)
                }
                onMessage(bytes)
            }
        }
    }

    private companion object {
        const val HOST = "ntfy.sh"
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun randomText(length: Int): String {
            val random = SecureRandom()
            return (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }
}
