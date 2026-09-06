package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.content.edit
import com.bolimot.mindtheclub.tools.MySelf
import org.json.JSONObject

/**
 * Tracks messages that entered "pending" state (the sender's dispatch worker exhausted its WebRTC
 * attempts, sent a `pending` FCM to the receiver and exited).
 *
 * Used by:
 * - DispatchWorker: records entries when sending the `pending` FCM
 * - PendingRetryWorker: re-sends it periodically with backoff
 * - MyFirebaseMessagingService (ALL_RECEIVED): clears confirmed entries, piggybacks stale ones
 */
object PendingMessageTracker {

    private const val PREFS_NAME = "PendingMessageTrackerPrefs"
    private const val KEY_PREFIX = "pending_"

    data class PendingEntry(
        val messageId: String,
        val toUserId: String,
        val messageKey: String,
        val createdAt: Long,
        val lastRetryAt: Long,
        val retryCount: Int,
        val chatGroupId: String?,
        val originalSenderId: String?,
        val messageDate: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun entryKey(messageId: String, toUserId: String) =
        "$KEY_PREFIX${messageId}_${toUserId}"

    fun record(
        context: Context,
        messageId: String,
        toUserId: String,
        messageKey: String,
        chatGroupId: String? = null,
        originalSenderId: String? = null,
        messageDate: Long = 0L
    ) {
        if (toUserId == MySelf.userId()) {
            debugLine("PendingTracker", "Ignoring self-addressed pending: $messageId")
            return
        }
        val now = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("messageId", messageId)
            put("toUserId", toUserId)
            put("messageKey", messageKey)
            put("createdAt", now)
            put("lastRetryAt", now)
            put("retryCount", 0)
            put("chatGroupId", chatGroupId ?: "")
            put("originalSenderId", originalSenderId ?: "")
            put("messageDate", messageDate)
        }
        prefs(context).edit { putString(entryKey(messageId, toUserId), json.toString()) }
        debugLine("PendingTracker", "Recorded pending: $messageId → $toUserId" +
                if (!chatGroupId.isNullOrEmpty()) " (group: $chatGroupId)" else "")
    }

    fun remove(context: Context, messageId: String, toUserId: String) {
        val key = entryKey(messageId, toUserId)
        if (prefs(context).contains(key)) {
            prefs(context).edit { remove(key) }
            debugLine("PendingTracker", "Cleared pending: $messageId → $toUserId")
        }
    }

    fun removeAllForPeer(context: Context, toUserId: String) {
        val all = prefs(context).all
        val keysToRemove = mutableListOf<String>()
        for ((key, value) in all) {
            if (!key.startsWith(KEY_PREFIX) || value !is String) continue
            try {
                val entry = parseEntry(value)
                if (entry.toUserId == toUserId) {
                    keysToRemove.add(key)
                }
            } catch (_: Exception) {
                keysToRemove.add(key)
            }
        }
        if (keysToRemove.isNotEmpty()) {
            prefs(context).edit {
                keysToRemove.forEach { remove(it) }
            }
            debugLine("PendingTracker", "Removed ${keysToRemove.size} entries for gone peer: $toUserId")
        }
    }

    fun getAll(context: Context): List<PendingEntry> {
        val result = mutableListOf<PendingEntry>()
        val all = prefs(context).all
        for ((key, value) in all) {
            if (!key.startsWith(KEY_PREFIX) || value !is String) continue
            try {
                result.add(parseEntry(value))
            } catch (e: Exception) {
                debugLine("PendingTracker", "Corrupt entry $key, removing: ${e.message}")
                prefs(context).edit { remove(key) }
            }
        }
        return result
    }

    fun getPendingForPeer(context: Context, toUserId: String): List<PendingEntry> =
        getAll(context).filter { it.toUserId == toUserId }

    fun updateRetry(context: Context, entry: PendingEntry) {
        val json = JSONObject().apply {
            put("messageId", entry.messageId)
            put("toUserId", entry.toUserId)
            put("messageKey", entry.messageKey)
            put("createdAt", entry.createdAt)
            put("lastRetryAt", System.currentTimeMillis())
            put("retryCount", entry.retryCount + 1)
            put("chatGroupId", entry.chatGroupId ?: "")
            put("originalSenderId", entry.originalSenderId ?: "")
            put("messageDate", entry.messageDate)
        }
        prefs(context).edit {
            putString(entryKey(entry.messageId, entry.toUserId), json.toString())
        }
    }

    /**
     * Minimum delay (ms) before the next retry, by retry count.
     * Backoff: 10min, 20min, 45min, 1.5h, 3h, 4h (capped).
     *
     * The first steps MUST be short: a message can be lost at the very last hop (the receiver's
     * process frozen or killed right as the WebRTC payload arrived, so nothing was persisted) and
     * this schedule is the sender side net that redelivers it. With the previous 6h first step a
     * lost message looked "never sent" for a whole working day.
     *
     * The tail was tightened after 6 Aug, where the old 6h cap meant a peer that was merely
     * asleep got asked again only the next morning. Modesto apposta: every retry costs a
     * Cloudflare signalling room, and the receiver now retries too (see IncomingPendingTracker),
     * so the two ladders add up.
     */
    fun getBackoffDelay(retryCount: Int): Long = when {
        retryCount <= 0 -> 10 * 60 * 1000L         // 10 min (next worker pass)
        retryCount == 1 -> 20 * 60 * 1000L         // 20 min
        retryCount == 2 -> 45 * 60 * 1000L         // 45 min
        retryCount == 3 -> 90 * 60 * 1000L         // 1.5 hours
        retryCount == 4 -> 3 * 60 * 60 * 1000L     // 3 hours
        else            -> 4 * 60 * 60 * 1000L     // 4 hours (capped)
    }

    private fun parseEntry(json: String): PendingEntry {
        val obj = JSONObject(json)
        return PendingEntry(
            messageId = obj.getString("messageId"),
            toUserId = obj.getString("toUserId"),
            messageKey = obj.getString("messageKey"),
            createdAt = obj.getLong("createdAt"),
            lastRetryAt = obj.getLong("lastRetryAt"),
            retryCount = obj.getInt("retryCount"),
            chatGroupId = obj.optString("chatGroupId", "").ifEmpty { null },
            originalSenderId = obj.optString("originalSenderId", "").ifEmpty { null },
            messageDate = obj.optLong("messageDate", 0L)
        )
    }
}

