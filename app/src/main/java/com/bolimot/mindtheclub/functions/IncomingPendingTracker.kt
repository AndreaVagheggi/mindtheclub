package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Tracks messages a peer announced through a `pending` FCM and that have not
 * arrived here yet.
 *
 * Until this existed the knowledge was thrown away: the PENDING handler answered
 * with one sendMe and forgot. If that single round failed, nothing on this side
 * remembered anything was owed and recovery depended entirely on the sender's own
 * backoff ladder. On 6 Aug that cost 13 hours for a one-line text: the signalling
 * socket dropped mid negotiation at 21:53 and the message only landed at 10:55 the
 * next morning, while this device's retry worker ran 35 times in between reporting
 * nothing to do.
 *
 * Deliberately capped. Every retry costs a Cloudflare signalling room, and an
 * uncapped loop here would be a traffic generator: see the Durable Objects
 * exhaustion of 1 Aug. At most [MAX_RETRIES] requests spread over about seven
 * hours, then the entry is dropped and the sender's ladder takes over.
 *
 * Companion of [PendingMessageTracker], which tracks the opposite direction.
 */
object IncomingPendingTracker {

    private const val PREFS_NAME = "IncomingPendingTrackerPrefs"
    private const val KEY_PREFIX = "incoming_"

    /** Hard ceiling on sendMe requests per message. */
    const val MAX_RETRIES = 6

    /** Entries older than this are dropped whatever their retry count. */
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    data class IncomingEntry(
        val messageId: String,
        val fromUserId: String,
        val createdAt: Long,
        val lastRetryAt: Long,
        val retryCount: Int
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun entryKey(messageId: String, fromUserId: String) =
        "$KEY_PREFIX${messageId}_${fromUserId}"

    /**
     * Idempotent. A peer re-sending its `pending` must not reset the retry count,
     * or two devices retrying each other would never reach the cap.
     */
    fun record(context: Context, messageId: String, fromUserId: String) {
        if (messageId.isEmpty() || fromUserId.isEmpty()) return
        val key = entryKey(messageId, fromUserId)
        if (prefs(context).contains(key)) return

        val now = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("messageId", messageId)
            put("fromUserId", fromUserId)
            put("createdAt", now)
            put("lastRetryAt", now)
            put("retryCount", 0)
        }
        prefs(context).edit { putString(key, json.toString()) }
        debugLine("IncomingPending", "Recorded owed message: $messageId from $fromUserId")
    }

    fun remove(context: Context, messageId: String, fromUserId: String, reason: String) {
        val key = entryKey(messageId, fromUserId)
        if (prefs(context).contains(key)) {
            prefs(context).edit { remove(key) }
            debugLine("IncomingPending", "Cleared $messageId from $fromUserId ($reason)")
        }
    }

    fun getAll(context: Context): List<IncomingEntry> {
        val result = mutableListOf<IncomingEntry>()
        for ((key, value) in prefs(context).all) {
            if (!key.startsWith(KEY_PREFIX) || value !is String) continue
            try {
                result.add(parseEntry(value))
            } catch (e: Exception) {
                debugLine("IncomingPending", "Corrupt entry $key, removing: ${e.message}")
                prefs(context).edit { remove(key) }
            }
        }
        return result
    }

    fun updateRetry(context: Context, entry: IncomingEntry) {
        val json = JSONObject().apply {
            put("messageId", entry.messageId)
            put("fromUserId", entry.fromUserId)
            put("createdAt", entry.createdAt)
            put("lastRetryAt", System.currentTimeMillis())
            put("retryCount", entry.retryCount + 1)
        }
        prefs(context).edit {
            putString(entryKey(entry.messageId, entry.fromUserId), json.toString())
        }
    }

    /**
     * Delay before the next sendMe. Short at first, because the usual cause is a
     * transient connection failure and the peer is still around, then widening so
     * a peer that is genuinely away is not hammered. The six allowed steps span
     * roughly seven hours in total.
     */
    fun getBackoffDelay(retryCount: Int): Long = when {
        retryCount <= 2 -> 15 * 60 * 1000L        // 15 min, the worker's own period
        retryCount <= 4 -> 60 * 60 * 1000L        // 1 hour
        else            -> 4 * 60 * 60 * 1000L    // 4 hours
    }

    private fun parseEntry(json: String): IncomingEntry {
        val obj = JSONObject(json)
        return IncomingEntry(
            messageId = obj.getString("messageId"),
            fromUserId = obj.getString("fromUserId"),
            createdAt = obj.getLong("createdAt"),
            lastRetryAt = obj.getLong("lastRetryAt"),
            retryCount = obj.getInt("retryCount")
        )
    }
}
