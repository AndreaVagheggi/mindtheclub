package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.desktop.backend.Relay
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.FCM
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Carries a signal (allReceived, sendMe, allMissing, someMissing ...) to another user, with
 * retries. The PC's counterpart of the App's SendFcmWorker (works/SendFcmWorker.kt) together
 * with fcmSendWork (firebase/fcmSend.kt).
 *
 * As in the App these travel with the default, long TTL and are NOT "instant": instant means a
 * 15 second TTL, which would let a recovery nudge to a sleeping phone expire instead of waiting
 * for it. Priority follows the same list of types that are allowed to wake a phone.
 *
 * Differences from the App, both temporary and both on the safe side:
 *   - the queue lives in memory. A signal still being retried when the program closes is lost,
 *     where WorkManager would resume it. The recovery layer covers this case by design: an
 *     unconfirmed message is announced again by its sender, and the PENDING handler answers
 *     allReceived for a message already here.
 *   - a recipient with no known public key gets nothing. The App falls back to clear text and
 *     loses the sealed sender property for that signal (docs/arch/identity.md section 7); the
 *     PC has no clear text path at all.
 */
object SignalSender {

    private const val TAG = "SignalSender"

    /** fcmSend.kt WAKE_TYPES: the signals worth waking a sleeping phone for. */
    private val WAKE_TYPES = setOf(
        Notify.PENDING,
        Notify.SEND_ME,
        Notify.CONTACT_REQUEST,
        Notify.CANCEL_TRANSFER,
        Notify.GROUP,
        Notify.GROUP_REMOVED,
        Notify.SOME_MISSING,
        Notify.ALL_MISSING,
    )

    /** WorkManager's linear backoff starts at 10 s; the wait grows by that much per attempt. */
    private const val BACKOFF_STEP_MS = 10_000L
    private const val MAX_BACKOFF_MS = 5 * 60_000L
    private const val MAX_ATTEMPTS = 30

    @Volatile var onSent: ((toUserId: String, type: String, result: String) -> Unit)? = null

    fun enqueue(userId: String, content: String, information: String, collapseKey: String) {
        App.instance!!.applicationScope.launch(Dispatchers.IO) {
            val myUserId = MySelf.userId()
            if (myUserId == null) {
                debugLine(TAG, "No identity on this PC, $information not sent")
                return@launch
            }
            val recipientKey = PeerIdentityResolver.publicKeyForUserId(userId)
            if (recipientKey.isNullOrEmpty()) {
                debugLine(TAG, "No public key for $userId, $information not sent (no clear text path on the PC)")
                report(userId, information, "nessuna chiave")
                return@launch
            }

            for (attempt in 1..MAX_ATTEMPTS) {
                val result = try {
                    Relay.sendSignal(
                        fromUserId = myUserId,
                        toUserId = userId,
                        recipientPublicKey = recipientKey,
                        type = information,
                        content = content,
                        wake = information in WAKE_TYPES
                    )
                } catch (e: Exception) {
                    debugLine(TAG, "Send failed, will retry: ${e.message}")
                    "error"
                }
                debugLine(TAG, "FCM Send Result: $result ($information to $userId, attempt $attempt)")

                when (result) {
                    FCM.SUCCESS -> {
                        report(userId, information, result)
                        return@launch
                    }
                    FCM.TOKEN_NOT_FOUND -> {
                        // The peer has no address any more. The App also drops its pending
                        // queue for that peer here (PendingMessageTracker.removeAllForPeer);
                        // that tracker belongs to sending and arrives with it.
                        report(userId, information, result)
                        return@launch
                    }
                }
                delay((BACKOFF_STEP_MS * attempt).coerceAtMost(MAX_BACKOFF_MS))
            }
            debugLine(TAG, "Giving up on $information to $userId after $MAX_ATTEMPTS attempts")
            report(userId, information, "abbandonato")
        }
    }

    private fun report(toUserId: String, type: String, result: String) {
        try {
            onSent?.invoke(toUserId, type, result)
        } catch (e: Exception) {
            // A listener's failure is never the engine's.
        }
    }
}
