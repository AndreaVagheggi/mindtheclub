package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent registry of 1:1 transfers cancelled by either side (the sender withdrew the message,
 * or the receiver refused the incoming transfer).
 *
 * The send and receive pipeline has several recovery paths that could silently resurrect a
 * cancelled transfer (pending to sendMe, completed to allMissing, InboxRecoveryWorker, deferred
 * network callbacks, a batch build already in flight when the WorkManager cancellation lands).
 * Every one of those chokepoints consults this registry:
 * - receiveData: drops chunks still arriving after a cancel
 * - sendMessageWork / DispatchWorker: aborts builds and dispatches queued before the cancel
 * - reSendMessage plus the SEND_ME / PENDING / COMPLETED handlers: ignore recovery nudges
 *
 * Entries expire after [TTL_MS] and are pruned lazily.
 */
object CancelledTransferRegistry {

    private const val PREFS_NAME = "CancelledTransferRegistryPrefs"
    private const val KEY_PREFIX = "cancelled_"
    private const val CONTENT_PREFIX = "cancelledContent_"
    private const val TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markCancelled(context: Context, messageId: String) {
        if (messageId.isEmpty()) return
        prune(context)
        prefs(context).edit { putLong("$KEY_PREFIX$messageId", System.currentTimeMillis()) }
        debugLine("CancelledTransfers", "Marked cancelled: $messageId")
    }

    /**
     * Marks a whole CONTENT as refused, the only identity that survives a group relay: gossip
     * mints a fresh messageId at every hop, so a cancel recorded against one messageId lets the
     * very same file back in a minute later under another name. The contentKey (group, original
     * sender, date) is stable across all of them.
     */
    fun markContentCancelled(context: Context, contentKey: String) {
        if (contentKey.isEmpty()) return
        prune(context)
        prefs(context).edit { putLong("$CONTENT_PREFIX$contentKey", System.currentTimeMillis()) }
        debugLine("CancelledTransfers", "Marked content cancelled: $contentKey")
    }

    fun isCancelled(context: Context, messageId: String): Boolean =
        isMarked(context, "$KEY_PREFIX$messageId")

    fun isContentCancelled(context: Context, contentKey: String): Boolean =
        isMarked(context, "$CONTENT_PREFIX$contentKey")

    private fun isMarked(context: Context, key: String): Boolean {
        if (key.endsWith("_")) return false
        val ts = prefs(context).getLong(key, 0L)
        if (ts == 0L) return false
        if (System.currentTimeMillis() - ts > TTL_MS) {
            prefs(context).edit { remove(key) }
            return false
        }
        return true
    }

    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        val stale = prefs(context).all.filter { (key, value) ->
            (key.startsWith(KEY_PREFIX) || key.startsWith(CONTENT_PREFIX)) &&
                (value !is Long || now - value > TTL_MS)
        }.keys
        if (stale.isNotEmpty()) {
            prefs(context).edit { stale.forEach { remove(it) } }
        }
    }
}
