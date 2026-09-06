package com.bolimot.mindtheclub.billing

import android.content.Context
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.functions.NoteToSelf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.tools.Type
import java.util.concurrent.TimeUnit

/**
 * Activation based 30 day free trial.
 *
 * The clock does NOT start at install: it starts the first time the user sends a message of
 * their own to a real person (see sendMessage in sending/send.kt). Chi scarica l'app e non la
 * usa costs nothing and is never nagged, and neither chatting with Clubby nor merely adding a
 * contact counts as engaging: see [startsTrial].
 */
object TrialManager {

    private const val PREF_TRIAL_STARTED_AT = "mtc_trial_started_at"
    private const val PREF_START_NOTICE_PENDING = "mtc_trial_notice_pending"
    private const val TRIAL_DAYS = 30L

    /** Days before expiry at which the countdown banner appears. */
    const val REMINDER_DAYS = 7

    enum class State { NOT_STARTED, ACTIVE, EXPIRED }

    /**
     * Message types the user actually authored. An allow-list apposta: contact acquisition and
     * profile updates exchange Type.PROFILE messages under the hood, and that housekeeping must
     * never start the clock. Anything not listed here is system traffic as far as the trial is
     * concerned.
     */
    private val ACTIVATING_TYPES = setOf(
        Type.TEXT,
        Type.IMAGE,
        Type.VIDEO,
        Type.MULTIPLE_IMAGES,
        Type.STICKER,
        Type.GIF,
        Type.WEB,
        Type.AUDIO,
        Type.FILE,
        Type.CONTACT
    )

    /**
     * True when sending this message counts as the user engaging with the app.
     *
     * Two conditions, both required: the message has to be one the user actually authored, and
     * it has to be addressed to a REAL person. Clubby and Note to self are not people: a new
     * user who tries the assistant out, or writes a note to themselves, would rightly feel
     * cheated to find their 30 days already running. sendMessage() diverts both before this is
     * reached; ripetuto qui so the rule survives any future reordering of that function.
     */
    fun startsTrial(toUserId: String?, messageType: String?): Boolean {
        if (AiAssistant.isAssistant(toUserId)) return false
        if (NoteToSelf.isNoteToSelf(toUserId)) return false
        return messageType != null && ACTIVATING_TYPES.contains(messageType)
    }

    /** Epoch millis when the clock started, or null when never activated. */
    fun startedAt(context: Context): Long? =
        getPreference(PREF_TRIAL_STARTED_AT, context)?.toLongOrNull()

    /**
     * Anchors the trial to the earliest known start, never the latest.
     *
     * Two sources feed it: a restored backup and the copy published on the user's Firestore
     * document. Both travel with the IDENTITY, so changing phone carries the clock along
     * instead of resetting it, which is what used to grant an endless free ride through backup,
     * restore, repeat.
     *
     * Only earlier values are accepted, so a stale or hostile source can never shorten a trial
     * by claiming a later start; values in the future are rejected outright, so a corrupt
     * timestamp cannot expire a legitimate user on the spot. Never sets the pending start
     * notice: quello appartiene alla vera attivazione, not to a sync.
     */
    fun adoptStartedAt(context: Context, candidate: Long?) {
        if (candidate == null || candidate <= 0L) return
        // A minute of slack absorbs clock skew between devices.
        if (candidate > System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)) {
            debugLine("TrialManager", "Ignoring trial start in the future: $candidate")
            return
        }
        val current = startedAt(context)
        if (current != null && current <= candidate) return
        setPreference(PREF_TRIAL_STARTED_AT, candidate.toString(), context)
        debugLine("TrialManager", "Trial start adopted: $candidate (was ${current ?: "not started"})")
    }

    /** Called on the first real outgoing message. Idempotent. */
    fun markActivated(context: Context) {
        if (getPreference(PREF_TRIAL_STARTED_AT, context) == null) {
            setPreference(PREF_TRIAL_STARTED_AT, System.currentTimeMillis().toString(), context)
            // The clock starts on a background thread (message send), so the "your trial
            // started" dialog is queued here and shown by the next resumed screen instead.
            setPreference(PREF_START_NOTICE_PENDING, "true", context)
            debugLine("TrialManager", "30-day trial clock started")
        }
    }

    /** True once, the first time it is called after the trial clock started. */
    fun consumeStartNotice(context: Context): Boolean {
        if (getPreference(PREF_START_NOTICE_PENDING, context) != "true") return false
        setPreference(PREF_START_NOTICE_PENDING, "false", context)
        return true
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
