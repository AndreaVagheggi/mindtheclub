package com.bolimot.mindtheclub.firebase

import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import org.json.JSONObject
import com.bolimot.mindtheclub.tools.FCM
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.views.AppTab
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ── Configuration ────────────────────────────────────────────────────────────
// Set this to your deployed FCM relay Worker (printed by `wrangler deploy`):
private const val FCM_WORKER_URL = "https://mtc-fcm.long-sun-7368.workers.dev"

suspend fun fcmSendInstant(
    userId: String,
    content: String,
    callId: String,
    type: String,
    collapseKey: String,
    extraData: Map<String, String> = emptyMap(),
): Boolean {

    val myUserId = MySelf.userId() ?: return false

    if(userId.startsWith("group")) {
        return false
    }

    debugLine("fcmSend", "SENDING Instant FCM: content: $content, callId = $callId, type: $type")

    val dataPayload = buildMap {
        put("toUserId", userId)
        put("fromUserId", myUserId)
        put("content", content)
        put("callId", callId)
        put("type", type)
        putAll(extraData)
    }

    return FcmMessageSender.sendFcmMessage(dataPayload, collapseKey, true, collapseKey == "offer") == FCM.SUCCESS
}

suspend fun fcmSendWork(
    userId: String,
    content: String,
    type: String,
    collapseKey: String,
): String {

    if(userId.startsWith("group")) {
        return FCM.TOKEN_NOT_FOUND
    }

    val myUserId = MySelf.userId() ?: return FCM.FAILURE

    val dataPayload = mapOf(
        "toUserId" to userId,
        "fromUserId" to myUserId,
        "content" to content,
        "callId" to "notACall",
        "type" to type,
    )

    return FcmMessageSender.sendFcmMessage(dataPayload, collapseKey, false, collapseKey == "offer")
}

object FcmMessageSender {

    private val ROUTING_KEYS = setOf("toUserId")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun sealPayload(data: Map<String, String?>): Map<String, String?> {
        val toUserId = data["toUserId"] ?: return data
        val recipientKey = PeerIdentityResolver.publicKeyForUserId(toUserId)
        if (recipientKey.isNullOrEmpty()) {
            debugLine("fcmMessageSender", "No public key for $toUserId, sending clear text")
            return data
        }

        val contentJson = JSONObject().apply {
            for ((k, v) in data) {
                if (k !in ROUTING_KEYS && v != null) put(k, v)
            }
        }.toString()

        val sealed = KeyManager.encryptFor(recipientKey, contentJson)
        if (sealed == null) {
            debugLine("fcmMessageSender", "encryptFor failed for $toUserId, sending clear text")
            return data
        }

        return buildMap {
            put("toUserId", toUserId)
            put("enc", "1")
            put("payload", sealed)
        }
    }

    suspend fun sendFcmMessage(
        data: Map<String, String?>,
        collapseKey: String,
        instant: Boolean,
        isOffer: Boolean = false
    ): String = withContext(Dispatchers.IO) {

        AppTab.fcmSending = true
        try {
            val outgoing = sealPayload(data)

            // Build the callable's `data` envelope: { data, collapseKey, instant, isOffer }
            val inner = JSONObject().apply {
                val dataObj = JSONObject()
                for ((k, v) in outgoing) if (v != null) dataObj.put(k, v)
                put("data", dataObj)
                put("collapseKey", collapseKey)
                put("instant", instant)
                put("isOffer", isOffer)
            }

            // App Check token (cached by the SDK unless expired). Proves "genuine MTC
            // app" to Google; it is app-level, not a per-user/per-install identifier.
            val appCheckToken = try {
                var t = FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
                if (t.isEmpty()) {
                    t = FirebaseAppCheck.getInstance().getAppCheckToken(true).await().token
                }
                t
            } catch (e: Exception) {
                debugLine("fcmMessageSender", "App Check token fetch failed: ${e.message}")
                return@withContext FCM.FAILURE
            }

            if (appCheckToken.isEmpty()) {
                debugLine("fcmMessageSender", "App Check token empty after refresh, aborting send")
                return@withContext FCM.FAILURE
            }

            val requestBody = JSONObject().apply {
                put("appCheckToken", appCheckToken)
                put("payload", inner)
            }.toString()

            var attempts = 0
            val maxAttempts = 3
            var retryDelay = 2000L

            while (attempts < maxAttempts) {
                try {
                    val request = Request.Builder()
                        .url(FCM_WORKER_URL)
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                        .build()

                    httpClient.newCall(request).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        val result = try { JSONObject(respBody).optString("result") } catch (e: Exception) { "" }
                        debugLine("fcmMessageSender", "Worker response: $result")
                        when (result) {
                            "ok" -> return@withContext FCM.SUCCESS
                            "not-found" -> return@withContext FCM.TOKEN_NOT_FOUND
                            else -> {
                                attempts++
                                if (attempts < maxAttempts) { delay(retryDelay); retryDelay *= 2 }
                            }
                        }
                    }
                } catch (e: Exception) {
                    debugLine("fcmMessageSender", "Send error (attempt $attempts): ${e.message}")
                    attempts++
                    if (attempts < maxAttempts) { delay(retryDelay); retryDelay *= 2 }
                }
            }

            debugLine("fcmMessageSender", "Max attempts reached, returning failed")
            return@withContext FCM.FAILURE

        } finally {
            AppTab.fcmSending = false
        }
    }
}