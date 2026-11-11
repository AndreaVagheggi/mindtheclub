package com.bolimot.mindtheclub.desktop.backend

import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The two Cloudflare relays a device talks to besides the signalling room: mtc-fcm, to send a
 * signal to another user, and mtc-ice, to get STUN and TURN servers. Same requests as the
 * phone's firebase/fcmSend.kt and webrtc/IceServers.kt.
 */
object Relay {

    private const val FCM_WORKER_URL = "https://mtc-fcm.long-sun-7368.workers.dev"
    private const val ICE_WORKER_URL = "https://mtc-ice.long-sun-7368.workers.dev"

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    data class IceServer(val urls: List<String>, val username: String, val credential: String)

    /**
     * Sends one signal (allReceived, sendMe, ...) to another user. Returns the relay's answer:
     * "ok", "not-found" or "error".
     *
     * SEALED SENDER (C:\mtc\docs\arch\identity.md section 7): only toUserId leaves in clear.
     * Who sends, the type and the content are sealed to the recipient's identity key. The phone
     * falls back to clear text when it has no key for the recipient; this does NOT, it has no
     * such path at all: a signal that cannot be sealed is not sent.
     *
     * [wake] is the one value outside the seal, a boolean with no identity in it, so the cloud
     * function can pick the push priority (fcmSend.kt, WAKE_TYPES).
     */
    fun sendSignal(
        fromUserId: String,
        toUserId: String,
        recipientPublicKey: String,
        type: String,
        content: String,
        wake: Boolean,
        // Same collapse key as notifyRemotePeer (sending/notify.kt) unless the caller has its own.
        collapseKey: String = content.substringBefore('#') + type,
        // "Instant" is the real time path: high priority and a 15 second life, for an invitation
        // that is worthless once late (dataCall, ping). Everything durable is NOT instant.
        instant: Boolean = false,
        callId: String = "notACall",
        // More fields for the recipient, sealed with the rest (e.g. contentKey on a dataCall).
        extraData: Map<String, String> = emptyMap()
    ): String {
        val inner = buildJsonObject {
            put("fromUserId", fromUserId)
            put("content", content)
            put("callId", callId)
            put("type", type)
            for ((key, value) in extraData) put(key, value)
        }.toString()
        val sealed = IdentityKeys.encryptFor(recipientPublicKey, inner)

        val body = buildJsonObject {
            put("payload", buildJsonObject {
                put("data", buildJsonObject {
                    put("toUserId", toUserId)
                    put("enc", "1")
                    put("payload", sealed)
                })
                put("collapseKey", collapseKey)
                put("instant", instant)
                put("isOffer", false)
                put("wake", wake)
            })
        }.toString()

        val request = HttpRequest.newBuilder(URI.create(FCM_WORKER_URL))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return try {
            (Json.parseToJsonElement(response.body()).jsonObject["result"] as? JsonPrimitive)?.contentOrNull ?: "error"
        } catch (e: Exception) {
            "error"
        }
    }

    /**
     * STUN and TURN servers from mtc-ice, or public STUN only when the worker does not answer
     * (webrtc/IceServers.kt, getAlternativeIceServers). Port 53 URLs are dropped as on the phone.
     */
    fun iceServers(): List<IceServer> {
        try {
            val request = HttpRequest.newBuilder(URI.create(ICE_WORKER_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val list = Json.parseToJsonElement(response.body()).jsonObject["iceServers"] as? JsonArray
                val servers = ArrayList<IceServer>()
                for (entry in list.orEmpty()) {
                    val server = entry as? JsonObject ?: continue
                    val urls = (server["urls"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .filter { !it.contains(":53") }
                    if (urls.isEmpty()) continue
                    servers.add(
                        IceServer(
                            urls,
                            (server["username"] as? JsonPrimitive)?.contentOrNull ?: "",
                            (server["credential"] as? JsonPrimitive)?.contentOrNull ?: ""
                        )
                    )
                }
                if (servers.isNotEmpty()) return servers
            }
        } catch (e: Exception) {
            // Fall through to STUN only.
        }
        return listOf(
            IceServer(listOf("stun:stun.l.google.com:19302"), "", ""),
            IceServer(listOf("stun:stun1.l.google.com:3478"), "", "")
        )
    }
}
