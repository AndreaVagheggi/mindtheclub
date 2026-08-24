package com.bolimot.mindtheclub.webrtc.group

import com.bolimot.mindtheclub.functions.debugLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The four Cloudflare Realtime calls a phone needs, taken through the mtc-sfu
 * Worker. The Realtime App secret signs the upstream request there; nothing in
 * this file could be used to talk to Cloudflare directly, which is the point.
 *
 * Every body here is SDP and track names. The Worker forwards them untouched,
 * so the shapes below are Cloudflare's own, verbatim.
 */
object SfuApi {

    private const val SFU_WORKER_URL = "https://mtc-sfu.long-sun-7368.workers.dev"

    /** WebSocket base for the call room (presence), same Worker. */
    const val ROOM_WS_BASE = "wss://mtc-sfu.long-sun-7368.workers.dev/r/"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val jsonType = "application/json".toMediaType()

    /** A track published by somebody else, addressed by its owner's session. */
    data class RemoteTrackRef(val sessionId: String, val trackName: String)

    /** What a tracks/new call returned, publishing or subscribing alike. */
    data class TracksResult(
        val requiresImmediateRenegotiation: Boolean,
        val offerSdp: String?,
        /** trackName to mid, for matching the transceivers that onTrack delivers. */
        val mids: Map<String, String>,
        /**
         * Track names the SFU refused. Usually `empty_track_error`, which means
         * the publisher has not started sending that track yet: worth asking
         * again in a moment rather than leaving a permanently blank tile.
         */
        val failed: List<String> = emptyList()
    )

    /** A session and the SFU's answer to the offer that opened it. */
    data class SessionResult(val sessionId: String, val answerSdp: String)

    // ------------------------------------------------------------------ session

    /**
     * Opens a session. One per phone per call.
     *
     * The offer goes in here rather than into a later call: the API refuses a
     * session created without a session description and answers this one
     * directly. Verified against the live API on 24 Aug 2026, where an empty
     * body comes back as `decoding_error: sessionDescription`.
     */
    suspend fun newSession(offerSdp: String): SessionResult? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("sessionDescription", sdp(offerSdp, "offer")) }

        val body = post("$SFU_WORKER_URL/s/new", "POST", payload.toString())
            ?: return@withContext null

        val id = body.optString("sessionId", "")
        val answer = body.optJSONObject("sessionDescription")?.optString("sdp", "").orEmpty()

        if (id.isEmpty() || answer.isEmpty()) {
            debugLine("SfuApi", "newSession failed: ${body.optString("errorDescription")}")
            return@withContext null
        }

        debugLine("SfuApi", "Session created")
        SessionResult(id, answer)
    }

    // --------------------------------------------------------------- publishing

    /**
     * Gives the already negotiated local transceivers the names the other
     * participants will pull them by.
     *
     * No session description here: the transceivers exist from the offer that
     * opened the session, so this only binds a name to each mid. The connection
     * has to be up first, or the API answers "Session is not ready yet".
     *
     * @param tracks mid to trackName for each local transceiver being published.
     */
    suspend fun publishTracks(
        sessionId: String,
        tracks: Map<String, String>
    ): TracksResult? = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext null

        val array = JSONArray()
        for ((mid, name) in tracks) {
            array.put(JSONObject().apply {
                put("location", "local")
                put("mid", mid)
                put("trackName", name)
            })
        }

        val payload = JSONObject().apply { put("tracks", array) }

        val body = post("$SFU_WORKER_URL/s/$sessionId/tracks-new", "POST", payload.toString())
            ?: return@withContext null

        val error = body.optString("errorCode", "")
        if (error.isNotEmpty()) {
            debugLine("SfuApi", "publishTracks failed: $error ${body.optString("errorDescription")}")
            return@withContext null
        }

        debugLine("SfuApi", "Published ${tracks.size} local track(s)")
        parseTracks(body)
    }

    // -------------------------------------------------------------- subscribing

    /**
     * Subscribes to tracks published by other sessions. When the SFU has to add
     * transceivers for them it answers with an offer and sets
     * requiresImmediateRenegotiation, and the caller must answer it before the
     * media flows.
     */
    suspend fun pullTracks(
        sessionId: String,
        tracks: List<RemoteTrackRef>
    ): TracksResult? = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext null

        val array = JSONArray()
        for (t in tracks) {
            array.put(JSONObject().apply {
                put("location", "remote")
                put("sessionId", t.sessionId)
                put("trackName", t.trackName)
            })
        }

        val payload = JSONObject().apply { put("tracks", array) }

        val body = post("$SFU_WORKER_URL/s/$sessionId/tracks-new", "POST", payload.toString())
            ?: return@withContext null

        parseTracks(body)
    }

    private fun parseTracks(body: JSONObject): TracksResult {
        val mids = mutableMapOf<String, String>()
        val failed = mutableListOf<String>()
        val returned = body.optJSONArray("tracks")
        if (returned != null) {
            for (i in 0 until returned.length()) {
                val t = returned.optJSONObject(i) ?: continue
                val name = t.optString("trackName", "")
                val error = t.optString("errorCode", "")
                if (error.isNotEmpty()) {
                    debugLine("SfuApi", "track error $error ${t.optString("errorDescription")}")
                    if (name.isNotEmpty()) failed.add(name)
                    continue
                }
                val mid = t.optString("mid", "")
                if (name.isNotEmpty() && mid.isNotEmpty()) mids[name] = mid
            }
        }

        return TracksResult(
            requiresImmediateRenegotiation = body.optBoolean("requiresImmediateRenegotiation", false),
            offerSdp = body.optJSONObject("sessionDescription")?.optString("sdp", "")?.ifEmpty { null },
            mids = mids,
            failed = failed
        )
    }

    /** Answers the offer the SFU produced while adding subscribed tracks. */
    suspend fun renegotiate(sessionId: String, answerSdp: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("sessionDescription", sdp(answerSdp, "answer")) }
        val body = post("$SFU_WORKER_URL/s/$sessionId/renegotiate", "PUT", payload.toString())
        val ok = body != null && body.optString("errorCode", "").isEmpty()
        if (!ok) debugLine("SfuApi", "renegotiate failed: ${body?.optString("errorDescription")}")
        ok
    }

    /**
     * Drops subscribed tracks when their owner leaves, so this phone stops paying
     * for a picture nobody is sending. `force` closes them without a renegotiation
     * round trip, which is what a departure calls for.
     */
    suspend fun closeTracks(sessionId: String, mids: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (mids.isEmpty()) return@withContext true

        val array = JSONArray()
        for (mid in mids) array.put(JSONObject().apply { put("mid", mid) })

        val payload = JSONObject().apply {
            put("tracks", array)
            put("force", true)
        }

        val body = post("$SFU_WORKER_URL/s/$sessionId/tracks-close", "PUT", payload.toString())
        body != null
    }

    // ------------------------------------------------------------------ helpers

    private fun sdp(text: String, type: String) = JSONObject().apply {
        put("sdp", text)
        put("type", type)
    }

    private fun post(url: String, method: String, body: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .method(method, body.toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    debugLine("SfuApi", "$method $url -> HTTP ${resp.code} $text")
                    return null
                }
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            }
        } catch (e: Exception) {
            debugLine("SfuApi", "$method $url failed: ${e.message}")
            null
        }
    }
}
