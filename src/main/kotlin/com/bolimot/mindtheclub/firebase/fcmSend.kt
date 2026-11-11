package com.bolimot.mindtheclub.firebase

// DESKTOP: this file is NOT a copy of the App's fcmSend.kt. fcmSendInstant keeps its name,
// signature and meaning; the sealing and the request to the relay are done by
// desktop/backend/Relay.kt. fcmSendWork is not here: its job on the PC is SignalSender's.
//
// One difference, on the safe side: with no public key for the recipient the App sends the
// signal in clear text and loses the sealed sender property for it (docs/arch/identity.md
// section 7). The PC has no clear text path: the signal is not sent and false is returned.

import com.bolimot.mindtheclub.desktop.backend.Relay
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.FCM
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A real time signal: high priority, 15 second life. Used for dataCall, which is an invitation
 * to a connection and is MEANT to expire if the peer is asleep; the durable announcement is
 * pending. Never lengthen this.
 */
suspend fun fcmSendInstant(
    userId: String,
    content: String,
    callId: String,
    type: String,
    collapseKey: String,
    extraData: Map<String, String> = emptyMap(),
): Boolean = withContext(Dispatchers.IO) {

    val myUserId = MySelf.userId() ?: return@withContext false

    if (userId.startsWith("group")) {
        return@withContext false
    }

    val recipientKey = PeerIdentityResolver.publicKeyForUserId(userId)
    if (recipientKey.isNullOrEmpty()) {
        debugLine("fcmSend", "No public key for $userId, instant $type not sent (no clear text path on the PC)")
        return@withContext false
    }

    debugLine("fcmSend", "SENDING Instant FCM: content: $content, callId = $callId, type: $type")

    // FcmMessageSender.sendFcmMessage: three attempts, backoff 2 s doubling.
    var retryDelay = 2000L
    for (attempt in 1..3) {
        val result = try {
            Relay.sendSignal(
                fromUserId = myUserId,
                toUserId = userId,
                recipientPublicKey = recipientKey,
                type = type,
                content = content,
                wake = false,
                collapseKey = collapseKey,
                instant = true,
                callId = callId,
                extraData = extraData
            )
        } catch (e: Exception) {
            debugLine("fcmSend", "Instant send failed: ${e.message}")
            "error"
        }
        debugLine("fcmMessageSender", "Worker response: $result")
        when (result) {
            FCM.SUCCESS -> return@withContext true
            FCM.TOKEN_NOT_FOUND -> return@withContext false
        }
        if (attempt < 3) {
            delay(retryDelay)
            retryDelay *= 2
        }
    }
    false
}
