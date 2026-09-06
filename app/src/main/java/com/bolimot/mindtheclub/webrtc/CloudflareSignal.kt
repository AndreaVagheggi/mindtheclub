package com.bolimot.mindtheclub.webrtc

import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val SIGNALING_WS_BASE = "wss://mtc-signal.long-sun-7368.workers.dev/c/"
private const val PEER_TIMEOUT_MS = 45_000L
private const val WS_CONNECT_TIMEOUT_S = 30L

// Handshake retry: a Cloudflare Durable Object can transiently 500/503 (cold start,
// eviction, platform hiccup). Retry the WS handshake before surfacing an error.
private const val WS_MAX_CONNECT_ATTEMPTS = 3
private const val WS_RETRY_BASE_DELAY_MS = 2_000L

@Volatile private var cachedClient: OkHttpClient? = null

private fun signalClient(): OkHttpClient {
    cachedClient?.let { return it }
    val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(WS_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    cachedClient = client
    return client
}

private suspend fun recoverPeerPublicKey(userId: String): String? {
    return try {
        val doc = FirebaseFirestore.getInstance()
            .collection("users").document(userId).get().await()
        val publicKey = doc.getString("publicKey")
        if (!publicKey.isNullOrEmpty()) {
            getPeerDao(App.context()).updatePeerPublicKey(userId, publicKey)
            PeerIdentityResolver.markStale()
            debugLine("CloudflareSignal", "Recovered publicKey for $userId")
            publicKey
        } else {
            debugLine("CloudflareSignal", "No publicKey in Firestore for $userId yet")
            null
        }
    } catch (e: Exception) {
        debugLine("CloudflareSignal", "recoverPeerPublicKey failed: ${e.message}")
        null
    }
}

private fun slotFor(channelId: String, userId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$channelId|$userId".toByteArray(Charsets.UTF_8))
    return android.util.Base64.encodeToString(
        digest,
        android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
    )
}

class Socket(channelId: String, private val remoteUserId: String) {

    private val mySelf = MySelf.userId() ?: ""
    private val slotSelf = slotFor(channelId, mySelf)
    private val slotPeer = slotFor(channelId, remoteUserId)
    private val wsUrl = SIGNALING_WS_BASE + channelId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var wsClient: OkHttpClient? = null
    private val connectAttempts = AtomicInteger(0)
    private var remotePublicKey: String? = null

    private var onSignalCb: ((Map<String, Any>) -> Unit)? = null
    private var onPeerJoinCb: ((String) -> Unit)? = null
    private var onJoinCb: ((Socket) -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null
    private var onTimeoutCb: (() -> Unit)? = null

    private var timeoutJob: Job? = null
    private val peerSeen = AtomicBoolean(false)
    private val joined = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val recovering = AtomicBoolean(false)
    private val recoveryFailed = AtomicBoolean(false)

    internal fun connect(
        client: OkHttpClient,
        remotePublicKey: String?,
        onJoin: (Socket) -> Unit,
        onPeerJoin: (String) -> Unit,
        onSignal: (Map<String, Any>) -> Unit,
        onError: (String) -> Unit,
        onTimeout: () -> Unit
    ) {
        this.remotePublicKey = remotePublicKey
        this.onJoinCb = onJoin
        this.onPeerJoinCb = onPeerJoin
        this.onSignalCb = onSignal
        this.onErrorCb = onError
        this.onTimeoutCb = onTimeout

        this.wsClient = client
        connectAttempts.set(1)
        openWebSocket()
    }

    private fun openWebSocket() {
        val client = wsClient ?: return
        if (closed.get()) return
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, Listener())
    }

    fun sendSignal(data: SignalingMessage) {
        val socket = ws
        if (socket == null || closed.get()) {
            debugLine("CloudflareSignal", "sendSignal: socket not ready, dropping ${data.type}")
            return
        }

        val inner = JSONObject().apply {
            put("type", data.type)
            data.sdp?.let { put("sdp", it) }
            data.candidate?.let { c ->
                put("candidate", JSONObject().apply {
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                    put("sdp", c.sdp)
                })
            }
        }.toString()

        val key = remotePublicKey
        if (key.isNullOrEmpty()) {
            if (recoveryFailed.get()) return
            if (recovering.compareAndSet(false, true)) {
                debugLine("CloudflareSignal", "No peer public key — recovering once")
                scope.launch {
                    val recovered = recoverPeerPublicKey(remoteUserId)
                    if (recovered.isNullOrEmpty()) {
                        recoveryFailed.set(true)
                    } else {
                        remotePublicKey = recovered
                        sendSignal(data)
                    }
                    recovering.set(false)
                }
            }
            return
        }
        val sealed = KeyManager.encryptFor(key, inner)
        if (sealed == null) {
            debugLine("CloudflareSignal", "encryptFor failed — refusing to send unencrypted signal")
            return
        }

        val frame = JSONObject().put("t", "sig").put("to", slotPeer)
            .put("enc", 1).put("payload", sealed)

        socket.send(frame.toString())
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        debugLine("CloudflareSignal", "Closing socket")
        timeoutJob?.cancel()
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        scope.cancel()
    }

    private fun decodeSignal(frame: JSONObject): Map<String, Any>? {
        val payloadStr = frame.optString("payload", "")
        if (payloadStr.isEmpty()) return null

        val plain = if (frame.optInt("enc", 0) == 1) {
            KeyManager.decrypt(payloadStr) ?: run {
                debugLine("CloudflareSignal", "decrypt failed, dropping signal")
                return null
            }
        } else {
            payloadStr
        }

        val obj = JSONObject(plain)
        val type = obj.optString("type", "")
        if (type.isEmpty()) return null

        val map = HashMap<String, Any>()
        map["type"] = type
        if (obj.has("sdp")) map["sdp"] = obj.getString("sdp")
        if (obj.has("candidate")) {
            val c = obj.getJSONObject("candidate")
            map["candidate"] = HashMap<String, Any>().apply {
                put("sdpMid", c.optString("sdpMid", ""))
                put("sdpMLineIndex", c.optInt("sdpMLineIndex", -1))
                put("sdp", c.optString("sdp", ""))
            }
        }
        return map
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            debugLine("CloudflareSignal", "WS open, announcing slot")
            webSocket.send(JSONObject().put("t", "join").put("slot", slotSelf).toString())

            timeoutJob = scope.launch {
                delay(PEER_TIMEOUT_MS)
                if (!peerSeen.get() && !closed.get()) {
                    debugLine("CloudflareSignal", "Peer timeout")
                    onTimeoutCb?.invoke()
                }
            }

            if (joined.compareAndSet(false, true)) {
                onJoinCb?.invoke(this@Socket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try { JSONObject(text) } catch (e: Exception) {
                debugLine("CloudflareSignal", "bad frame: ${e.message}")
                return
            }
            when (frame.optString("t")) {
                "peer" -> {
                    if (peerSeen.compareAndSet(false, true)) {
                        debugLine("CloudflareSignal", "Remote peer present")
                        onPeerJoinCb?.invoke(remoteUserId)
                    }
                }
                "sig" -> {
                    timeoutJob?.cancel()
                    decodeSignal(frame)?.let { onSignalCb?.invoke(it) }
                }
                "full" -> onErrorCb?.invoke("Signaling room full")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed.get()) return

            // Handshake never completed (we never joined): treat it as transient, the signalling
            // Worker or DO can momentarily 500/503, and retry with backoff before giving up.
            // Failures after a successful open keep the old behaviour (RTCClient handles mid call
            // recovery separately).
            if (!joined.get()) {
                val attempt = connectAttempts.get()
                if (attempt < WS_MAX_CONNECT_ATTEMPTS) {
                    connectAttempts.incrementAndGet()
                    val backoffMs = WS_RETRY_BASE_DELAY_MS * attempt
                    debugLine(
                        "CloudflareSignal",
                        "WS handshake failed (HTTP ${response?.code}, ${t.message}); " +
                            "retry ${attempt + 1}/$WS_MAX_CONNECT_ATTEMPTS in ${backoffMs}ms"
                    )
                    ws = null
                    scope.launch {
                        delay(backoffMs)
                        openWebSocket()
                    }
                    return
                }
                debugLine("CloudflareSignal", "WS handshake failed after $WS_MAX_CONNECT_ATTEMPTS attempts")
            }

            debugLine("CloudflareSignal", "WS failure: ${t.message}")
            onErrorCb?.invoke("Signaling transport failure: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            debugLine("CloudflareSignal", "WS closed: $code $reason")
        }
    }
}

fun closeSignal(socket: Socket?): Socket? {
    socket?.close()
    return null
}

suspend fun openSignal(
    channelId: String,
    remoteUserId: String,
    initiator: Boolean,
    onPeerJoin: (remoteUserId: String) -> Unit,
    onSignal: (data: Map<String, Any>) -> Unit,
    onJoin: (socket: Socket) -> Unit = {},
    onIceServersFetched: (iceServers: List<PeerConnection.IceServer>) -> Unit = {},
    onError: (error: String) -> Unit = {},
    onTimeout: () -> Unit = {}
): List<PeerConnection.IceServer>? {
    val tag = "openSignal"

    val iceServers = getIceServers()
    if (iceServers == null) {
        debugLine(tag, "Failed to fetch ICE servers")
        onError("Failed to fetch ICE servers")
        return null
    }

    val mySelf = MySelf.userId()
    if (mySelf.isNullOrEmpty()) {
        debugLine(tag, "MySelf is empty")
        onError("MySelf is empty")
        return null
    }

    onIceServersFetched(iceServers)

    val remoteKey = try {
        PeerIdentityResolver.publicKeyForUserId(remoteUserId)
    } catch (e: Exception) {
        debugLine(tag, "publicKeyForUserId failed: ${e.message}")
        null
    }

    val socket = Socket(channelId, remoteUserId)
    socket.connect(
        client = signalClient(),
        remotePublicKey = remoteKey,
        onJoin = { sk ->
            debugLine(tag, "JOIN: I have joined the channel")
            onJoin(sk)
        },
        onPeerJoin = { peerId ->
            debugLine(tag, "JOIN: Remote peer joined the channel")
            onPeerJoin(peerId)
        },
        onSignal = { signalMap -> onSignal(signalMap) },
        onError = { err -> onError(err) },
        onTimeout = {
            debugLine(tag, "JOIN: Timeout waiting for remote peer")
            onTimeout()
        }
    )

    return iceServers
}
