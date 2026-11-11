package com.bolimot.mindtheclub.desktop.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
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
 */
class NtfySubscriber(
    private val onMessage: (ByteArray) -> Unit,
    private val onClosed: (String) -> Unit
) : WebSocket.Listener {

    /** "up" plus 12 random characters, the shape the ntfy app itself uses for UnifiedPush. */
    val topic: String = "up" + randomText(12)

    /** What goes to updateMyPushEndpoint. "up=1" tells ntfy the body is a UnifiedPush message. */
    val endpoint: String = "https://$HOST/$topic?up=1"

    private val buffer = StringBuilder()
    private val opened = CompletableFuture<Boolean>()

    /** Connects and waits until ntfy confirms the subscription. False when it does not. */
    fun connect(timeoutSeconds: Long = 20): Boolean {
        return try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .buildAsync(URI.create("wss://$HOST/$topic/ws"), this)
                .get(timeoutSeconds, TimeUnit.SECONDS)
            opened.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
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
        onClosed("chiusa dal server ($statusCode)")
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        opened.complete(false)
        onClosed(error.message ?: error.javaClass.simpleName)
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
