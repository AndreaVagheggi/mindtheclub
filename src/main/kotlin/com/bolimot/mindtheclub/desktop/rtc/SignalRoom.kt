package com.bolimot.mindtheclub.desktop.rtc

import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One signalling message: an SDP ("offer"/"answer") or one ICE candidate ("candidate"). */
class Signal(
    val type: String,
    val sdp: String? = null,
    val candidateMid: String? = null,
    val candidateLineIndex: Int = -1,
    val candidateSdp: String? = null
)

/**
 * The signalling room of one connection attempt, the PC side of the phone's
 * webrtc/CloudflareSignal.kt. Same frames, same sealing, same slots.
 *
 * The relay learns nothing about who is talking: each side is known to it only as
 * Base64url(SHA-256("<channelId>|<userId>")), and the channelId is fresh per attempt.
 * Every signal is sealed to the peer's identity key. As on the phone, a signal that cannot be
 * sealed is NOT sent.
 */
class SignalRoom(
    channelId: String,
    myUserId: String,
    remoteUserId: String,
    private val remotePublicKey: String,
    private val identity: IdentityKeys,
    private val onPeer: () -> Unit,
    private val onSignal: (Signal) -> Unit,
    private val onProblem: (String) -> Unit
) : WebSocket.Listener {

    private val url = SIGNALING_WS_BASE + channelId
    private val slotSelf = slotFor(channelId, myUserId)
    private val slotPeer = slotFor(channelId, remoteUserId)

    private val buffer = StringBuilder()
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null

    // The JDK WebSocket refuses a send while the previous one is still in flight, and ICE
    // candidates arrive in bursts: one thread sends them one after the other.
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mtc-signal-send").apply { isDaemon = true }
    }

    /** Opens the socket and announces our slot. False when the relay cannot be reached. */
    fun connect(timeoutSeconds: Long = 30): Boolean {
        return try {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .buildAsync(URI.create(url), this)
                .get(timeoutSeconds, TimeUnit.SECONDS)
            sendFrame(buildJsonObject { put("t", "join"); put("slot", slotSelf) }.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    fun send(signal: Signal) {
        val inner = buildJsonObject {
            put("type", signal.type)
            signal.sdp?.let { put("sdp", it) }
            if (signal.candidateSdp != null) {
                put("candidate", buildJsonObject {
                    put("sdpMid", signal.candidateMid ?: "")
                    put("sdpMLineIndex", signal.candidateLineIndex)
                    put("sdp", signal.candidateSdp)
                })
            }
        }.toString()

        val sealed = try {
            IdentityKeys.encryptFor(remotePublicKey, inner)
        } catch (e: Exception) {
            onProblem("segnale non sigillabile, non inviato: ${e.message}")
            return
        }
        sendFrame(buildJsonObject {
            put("t", "sig")
            put("to", slotPeer)
            put("enc", 1)
            put("payload", sealed)
        }.toString())
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket?.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
        } catch (e: Exception) {
            // Already gone.
        }
        sender.shutdown()
    }

    private fun sendFrame(text: String) {
        val ws = socket ?: return
        if (closed.get()) return
        try {
            sender.execute {
                try {
                    ws.sendText(text, true).get(10, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    if (!closed.get()) onProblem("invio sul canale di segnalazione fallito: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Sender already shut down by close().
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
                onProblem("frame di segnalazione illeggibile: ${e.message}")
            }
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        if (!closed.get()) onProblem("canale di segnalazione interrotto: ${error.message}")
    }

    /** {"t":"peer"} the other side is in the room. {"t":"sig",...} a sealed signal from it. */
    private fun handle(frame: String) {
        val obj = Json.parseToJsonElement(frame).jsonObject
        when (obj.text("t")) {
            "peer" -> onPeer()
            "full" -> onProblem("stanza di segnalazione piena")
            "sig" -> {
                val payload = obj.text("payload") ?: return
                val plain = if (obj.text("enc") == "1") identity.decrypt(payload) else payload
                val signal = Json.parseToJsonElement(plain).jsonObject
                val type = signal.text("type") ?: return
                val candidate = signal["candidate"] as? JsonObject
                onSignal(
                    Signal(
                        type = type,
                        sdp = signal.text("sdp"),
                        candidateMid = candidate?.text("sdpMid"),
                        candidateLineIndex = candidate?.text("sdpMLineIndex")?.toIntOrNull() ?: -1,
                        candidateSdp = candidate?.text("sdp")
                    )
                )
            }
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val SIGNALING_WS_BASE = "wss://mtc-signal.long-sun-7368.workers.dev/c/"

        fun slotFor(channelId: String, userId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$channelId|$userId".toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
