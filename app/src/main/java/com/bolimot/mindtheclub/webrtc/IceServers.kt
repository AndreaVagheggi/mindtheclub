package com.bolimot.mindtheclub.webrtc

import com.bolimot.mindtheclub.functions.debugLine
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

suspend fun getIceServers(): List<PeerConnection.IceServer>? {
    if (RelayUsageTracker.isOverCap()) {
        debugLine("getIceServers", "Monthly relay cap reached, withholding TURN, using STUN-only")
        return getAlternativeIceServers()
    }

    val iceServers = try {
        withTimeout(8000L) { getCloudflareIceServers() }
    } catch (e: Exception) {
        debugLine("getIceServers", "Cloudflare TURN timed out or failed: ${e.message}")
        null
    }

    if (iceServers == null) {
        debugLine("getIceServers", "Falling back to Google STUN")
        return getAlternativeIceServers()
    }
    return iceServers
}

private const val ICE_WORKER_URL = "https://mtc-ice.long-sun-7368.workers.dev"

private val iceClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}

/**
 * TURN credentials are metered money: the worker only serves requests carrying
 * a valid Firebase App Check token, so random callers can't mint credentials
 * on our Cloudflare account.
 */
private suspend fun appCheckToken(): String? {
    return try {
        var t = FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
        if (t.isEmpty()) {
            t = FirebaseAppCheck.getInstance().getAppCheckToken(true).await().token
        }
        t.ifEmpty { null }
    } catch (e: Exception) {
        debugLine("getCloudflareIceServers", "App Check token fetch failed: ${e.message}")
        null
    }
}

private suspend fun getCloudflareIceServers(): List<PeerConnection.IceServer>? = withContext(Dispatchers.IO) {
    try {
        val token = appCheckToken() ?: return@withContext null

        val request = Request.Builder()
            .url(ICE_WORKER_URL)
            .header("X-Firebase-AppCheck", token)
            .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
            .build()

        val bodyStr = iceClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                debugLine("getCloudflareIceServers", "Worker HTTP ${resp.code}")
                return@withContext null
            }
            resp.body?.string()
        } ?: return@withContext null

        val iceServersList = JSONObject(bodyStr).optJSONArray("iceServers") ?: return@withContext null

        val servers = mutableListOf<PeerConnection.IceServer>()

        for (i in 0 until iceServersList.length()) {
            val serverObj = iceServersList.optJSONObject(i) ?: continue
            val urlsArray = serverObj.optJSONArray("urls") ?: continue
            val username = serverObj.optString("username", "")
            val credential = serverObj.optString("credential", "")

            val filteredUrls = (0 until urlsArray.length())
                .mapNotNull { urlsArray.optString(it, null) }
                .filter { !it.contains(":53") }

            if (filteredUrls.isEmpty()) continue

            val builder = PeerConnection.IceServer.builder(filteredUrls)
            if (username.isNotEmpty() && credential.isNotEmpty()) {
                builder.setUsername(username)
                builder.setPassword(credential)
            }
            servers.add(builder.createIceServer())
        }

        if (servers.isEmpty()) {
            debugLine("getCloudflareIceServers", "No ICE servers parsed from response")
            return@withContext null
        }

        debugLine("getCloudflareIceServers", "Got ${servers.size} ICE server entries from Cloudflare")
        servers

    } catch (e: Exception) {
        debugLine("getCloudflareIceServers", "Error: ${e.message}")
        null
    }
}

fun getAlternativeIceServers(): List<PeerConnection.IceServer> {
    return listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.l.google.com:5349").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:5349").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:5349").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:5349").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:5349").createIceServer()
    )
}
