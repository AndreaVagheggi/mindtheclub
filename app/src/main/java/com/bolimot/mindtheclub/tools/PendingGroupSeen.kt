package com.bolimot.mindtheclub.tools

import android.content.Context
import androidx.core.content.edit

object PendingGroupSeen {
    private const val PREFS_NAME = "pending_group_seen"
    private const val KEY = "message_ids"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun add(context: Context, messageId: String) {
        val set = prefs(context).getStringSet(KEY, emptySet())!!.toMutableSet()
        set.add(messageId)
        prefs(context).edit { putStringSet(KEY, set) }
    }

    fun consumeIfPending(context: Context, messageId: String): Boolean {
        val set = prefs(context).getStringSet(KEY, emptySet())!!.toMutableSet()
        val removed = set.remove(messageId)
        if (removed) {
            prefs(context).edit { putStringSet(KEY, set) }
        }
        return removed
    }
}
