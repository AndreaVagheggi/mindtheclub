package com.bolimot.mindtheclub.webrtc.group

import com.bolimot.mindtheclub.functions.debugLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One participant as the room sees it. Everything here is either opaque or
 * addressed to the SFU: `pid` is random per call, and `label` is the identity
 * sealed with the call key, so the relay hosting the room learns neither who is
 * in the call nor what they are saying.
 */
data class RoomParticipant(
    val pid: String,
    val sessionId: String,
    val audioTrack: String,
    val videoTrack: String,
    val label: String,
    val mic: Boolean = true,
    val cam: Boolean = true,
    val hand: Boolean = false
)

/**
 * The presence half of a group call.
 *
 * An SFU forwards media but knows nothing about who should receive it: a phone
 * can only subscribe to a track whose session id and name it already has. This
 * socket is where those arrive — the roster on join, then joined/left/state as
 * the call changes — and it is the only thing standing between "the SFU works"
 * and "people can actually call each other".
 *
 * It reconnects on its own. A call outlives a tunnel change or a lift, and a
 * dropped presence socket must not end it: the media path to Cloudflare survives
 * independently, so a reconnect quietly re-announces and the call carries on.
 */
class CallRoomSocket(
    private val roomId: String,
    private val listener: Listener
) {

    interface Listener {
        fun onRoster(participants: List<RoomParticipant>)
        fun onJoined(participant: RoomParticipant)
        fun onLeft(pid: String)
        fun onState(pid: String, mic: Boolean, cam: Boolean, hand: Boolean, videoTrack: String)
        fun onReaction(pid: String, emoji: String)
        fun onFull()
        fun onDisconnected()
    }

    private val tag = "CallRoomSocket"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var me: RoomParticipant? = null
    @Volatile private var attempts = 0

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Connects and announces this phone. Safe to call once per call. */
    fun connect(self: RoomParticipant) {
        me = self
        open()
    }

    private fun open() {
        if (closed.get()) return

        val request = Request.Builder().url(SfuApi.ROOM_WS_BASE + roomId).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                debugLine(tag, "Room socket open for $roomId")
                announce(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                debugLine(tag, "Room socket closed: $code $reason")
                if (reason == "full") {
                    listener.onFull()
                    closed.set(true)
                    return
                }
                retry()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                debugLine(tag, "Room socket failed: ${t.message}")
                retry()
            }
        })
    }

    private fun announce(webSocket: WebSocket) {
        val self = me ?: return
        val join = JSONObject().apply {
            put("t", "join")
            put("p", self.pid)
            put("s", self.sessionId)
            put("a", self.audioTrack)
            put("v", self.videoTrack)
            put("n", self.label)
            put("mic", self.mic)
            put("cam", self.cam)
        }
        webSocket.send(join.toString())
    }

    private fun retry() {
        if (closed.get()) return
        listener.onDisconnected()

        scope.launch {
            attempts++
            // Capped backoff: a room that refuses for a minute is a room that is
            // gone, and the call screen decides what to do about that, not this.
            val wait = (2_000L * attempts).coerceAtMost(15_000L)
            delay(wait)
            if (!closed.get()) {
                debugLine(tag, "Reconnecting room socket (attempt $attempts)")
                open()
            }
        }
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }

        when (msg.optString("t")) {
            "roster" -> {
                val array = msg.optJSONArray("ps")
                val list = mutableListOf<RoomParticipant>()
                if (array != null) {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { list.add(parse(it)) }
                    }
                }
                debugLine(tag, "Roster: ${list.size} participant(s) already in the call")
                listener.onRoster(list)
            }

            "joined" -> msg.optJSONObject("p")?.let { listener.onJoined(parse(it)) }

            "left" -> listener.onLeft(msg.optString("p"))

            "state" -> listener.onState(
                msg.optString("p"),
                msg.optBoolean("mic", true),
                msg.optBoolean("cam", true),
                msg.optBoolean("hand", false),
                msg.optString("v", "")
            )

            "reaction" -> listener.onReaction(msg.optString("p"), msg.optString("e"))

            "full" -> {
                closed.set(true)
                listener.onFull()
            }
        }
    }

    private fun parse(o: JSONObject) = RoomParticipant(
        pid = o.optString("pid"),
        sessionId = o.optString("sid"),
        audioTrack = o.optString("audio"),
        videoTrack = o.optString("video"),
        label = o.optString("label"),
        mic = o.optBoolean("mic", true),
        cam = o.optBoolean("cam", true),
        hand = o.optBoolean("hand", false)
    )

    /** Publishes a change of mic, camera or raised hand to the others. */
    fun sendState(mic: Boolean, cam: Boolean, hand: Boolean, videoTrack: String) {
        me = me?.copy(mic = mic, cam = cam, hand = hand, videoTrack = videoTrack)
        val payload = JSONObject().apply {
            put("t", "state")
            put("mic", mic)
            put("cam", cam)
            put("hand", hand)
            put("v", videoTrack)
        }
        try { ws?.send(payload.toString()) } catch (e: Exception) {
            debugLine(tag, "sendState failed: ${e.message}")
        }
    }

    fun sendReaction(emoji: String) {
        val payload = JSONObject().apply {
            put("t", "reaction")
            put("e", emoji)
        }
        try { ws?.send(payload.toString()) } catch (e: Exception) {
            debugLine(tag, "sendReaction failed: ${e.message}")
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { ws?.send(JSONObject().put("t", "bye").toString()) } catch (_: Exception) { }
        try { ws?.close(1000, "bye") } catch (_: Exception) { }
        ws = null
        scope.cancel()
        debugLine(tag, "Room socket closed by us")
    }
}
