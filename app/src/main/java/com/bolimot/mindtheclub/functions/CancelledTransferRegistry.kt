package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent registry of 1:1 transfers cancelled by either side (sender withdrew
 * the message, or receiver refused the incoming transfer).
 *
 * The send/receive pipeline has several recovery paths that could silently
 * resurrect a cancelled transfer (pending→sendMe, completed→allMissing,
 * InboxRecoveryWorker, deferred network callbacks, a batch build already in
 * flight when WorkManager cancellation lands). Every one of those chokepoints
 * consults this registry:
 * - receiveData: drops chunks still arriving after a cancel
 * - sendMessageWork / DispatchWorker: aborts builds/dispatches queued before the cancel
 * - reSendMessage + SEND_ME / PENDING / COMPLETED FCM handlers: ignores recovery nudges
 *
 * Entries expire after [TTL_MS] and are pruned lazily.
 */
object CancelledTransferRegistry {

    private const val PREFS_NAME = "CancelledTransferRegistryPrefs"
    private const val KEY_PREFIX = "cancelled_"
    private const val TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markCancelled(context: Context, messageId: String) {
        if (messageId.isEmpty()) return
        prune(context)
        prefs(context).edit { putLong("$KEY_PREFIX$messageId", System.currentTimeMillis()) }
        debugLine("CancelledTransfers", "Marked cancelled: $messageId")
    }

    fun isCancelled(context: Context, messageId: String): Boolean {
        if (messageId.isEmpty()) return false
        val ts = prefs(context).getLong("$KEY_PREFIX$messageId", 0L)
        if (ts == 0L) return false
        if (System.currentTimeMillis() - ts > TTL_MS) {
            prefs(context).edit { remove("$KEY_PREFIX$messageId") }
            return false
        }
        return true
    }

    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        val stale = prefs(context).all.filter { (key, value) ->
            key.startsWith(KEY_PREFIX) && (value !is Long || now - value > TTL_MS)
        }.keys
        if (stale.isNotEmpty()) {
            prefs(context).edit { stale.forEach { remove(it) } }
        }
    }
}
