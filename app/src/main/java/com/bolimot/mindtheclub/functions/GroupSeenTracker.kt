package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Tracks GROUP_SEEN notifications that have not been acknowledged yet.
 *
 * A group seen used to be a single fire and forget FCM, sent once when the chat opened, with the
 * message marked seen LOCALLY in the same breath, so it left the unseen set for ever. If that one
 * notification died in transit, the sender stayed at "Delivered" for that member with no second
 * chance (16 Aug: two of Gio's messages stuck at Delivered because Raoul's seen FCMs were lost in
 * the 13 Aug notification flood, while every later message showed Seen fine).
 *
 * Entries are recorded when the GROUP_SEEN is sent, removed when the original sender replies
 * GROUP_SEEN_ACK, and retried by PendingRetryWorker with backoff in between. Against senders on
 * older versions, which never ack, the cap spends a handful of tiny FCMs over two days and the
 * entry dies quietly: no worse than before, where the first loss was already final.
 *
 * Same shape as [IncomingPendingTracker], apposta.
 */
object GroupSeenTracker {

    private const val PREFS_NAME = "GroupSeenTrackerPrefs"
    private const val KEY_PREFIX = "groupSeen_"

    const val MAX_RETRIES = 6

    /** Entries older than this are dropped whatever their retry count. */
    const val MAX_AGE_MS = 48 * 60 * 60 * 1000L

    data class SeenEntry(
        val messageId: String,
        val originalSenderId: String,
        val createdAt: Long,
        val lastRetryAt: Long,
        val retryCount: Int
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun entryKey(messageId: String, originalSenderId: String) =
        "$KEY_PREFIX${messageId}_${originalSenderId}"

    /** Idempotent: re-opening the chat must not reset a running retry ladder. */
    fun record(context: Context, messageId: String, originalSenderId: String) {
        if (messageId.isEmpty() || originalSenderId.isEmpty()) return
        val key = entryKey(messageId, originalSenderId)
        if (prefs(context).contains(key)) return

        val now = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("messageId", messageId)
            put("originalSenderId", originalSenderId)
            put("createdAt", now)
            put("lastRetryAt", now)
            put("retryCount", 0)
        }
        prefs(context).edit { putString(key, json.toString()) }
        debugLine("GroupSeen", "Recorded unacked seen: $messageId to $originalSenderId")
    }

    fun remove(context: Context, messageId: String, originalSenderId: String, reason: String) {
        val key = entryKey(messageId, originalSenderId)
        if (prefs(context).contains(key)) {
            prefs(context).edit { remove(key) }
            debugLine("GroupSeen", "Cleared seen for $messageId to $originalSenderId ($reason)")
        }
    }

    fun getAll(context: Context): List<SeenEntry> {
        val result = mutableListOf<SeenEntry>()
        for ((key, value) in prefs(context).all) {
            if (!key.startsWith(KEY_PREFIX) || value !is String) continue
            try {
                result.add(parseEntry(value))
            } catch (e: Exception) {
                debugLine("GroupSeen", "Corrupt entry $key, removing: ${e.message}")
                prefs(context).edit { remove(key) }
            }
        }
        return result
    }

    fun updateRetry(context: Context, entry: SeenEntry) {
        val json = JSONObject().apply {
            put("messageId", entry.messageId)
            put("originalSenderId", entry.originalSenderId)
            put("createdAt", entry.createdAt)
            put("lastRetryAt", System.currentTimeMillis())
            put("retryCount", entry.retryCount + 1)
        }
        prefs(context).edit {
            putString(entryKey(entry.messageId, entry.originalSenderId), json.toString())
        }
    }

    /** Widening ladder, six steps over roughly two days. */
    fun getBackoffDelay(retryCount: Int): Long = when {
        retryCount <= 2 -> 15 * 60 * 1000L        // 15 min, the worker's own period
        retryCount <= 4 -> 2 * 60 * 60 * 1000L    // 2 hours
        else            -> 12 * 60 * 60 * 1000L   // 12 hours
    }

    private fun parseEntry(json: String): SeenEntry {
        val obj = JSONObject(json)
        return SeenEntry(
            messageId = obj.getString("messageId"),
            originalSenderId = obj.getString("originalSenderId"),
            createdAt = obj.getLong("createdAt"),
            lastRetryAt = obj.getLong("lastRetryAt"),
            retryCount = obj.getInt("retryCount")
        )
    }
}
