package com.bolimot.mindtheclub.billing

import android.content.Context
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import java.util.concurrent.TimeUnit

/**
 * Activation-based 30-day free trial.
 *
 * The clock does NOT start at install: it starts the first time the user sends
 * a real peer message (see sendMessage in sending/send.kt). Someone who
 * downloads the app and never engages costs nothing and is never nagged.
 */
object TrialManager {

    private const val PREF_TRIAL_STARTED_AT = "mtc_trial_started_at"
    private const val TRIAL_DAYS = 30L

    enum class State { NOT_STARTED, ACTIVE, EXPIRED }

    /** Called on the first real outgoing message. Idempotent. */
    fun markActivated(context: Context) {
        if (getPreference(PREF_TRIAL_STARTED_AT, context) == null) {
            setPreference(PREF_TRIAL_STARTED_AT, System.currentTimeMillis().toString(), context)
            debugLine("TrialManager", "30-day trial clock started")
        }
    }

    fun state(context: Context): State {
        val startedAt = getPreference(PREF_TRIAL_STARTED_AT, context)?.toLongOrNull()
            ?: return State.NOT_STARTED
        val elapsed = System.currentTimeMillis() - startedAt
        return if (elapsed < TimeUnit.DAYS.toMillis(TRIAL_DAYS)) State.ACTIVE else State.EXPIRED
    }

    /** Whole days remaining, 0 when expired or not started. */
    fun daysLeft(context: Context): Int {
        val startedAt = getPreference(PREF_TRIAL_STARTED_AT, context)?.toLongOrNull() ?: return 0
        val end = startedAt + TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        val left = end - System.currentTimeMillis()
        return if (left <= 0) 0 else TimeUnit.MILLISECONDS.toDays(left).toInt() + 1
    }
}
