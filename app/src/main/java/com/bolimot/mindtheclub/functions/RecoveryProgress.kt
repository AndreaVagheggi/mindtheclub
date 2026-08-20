package com.bolimot.mindtheclub.functions

import android.content.Context

/**
 * Stops the receiver asking for a transfer that is not moving.
 *
 * Every incoming `pending` announcement made this device answer with a sendMe,
 * with no memory of whether the previous ones had achieved anything. On 20 Aug a
 * phone sent round 45 for an album it had been missing 27 chunks of since the
 * morning: the same 27 at round 1 and at round 45, seven hours and forty five
 * wake-ups later. The announcer could not know it was announcing into a hole,
 * and the asker could not know it was asking into one.
 *
 * The criterion is deliberately NOT a ceiling on the number of attempts. Rounds
 * are counted per announcement, not per hour, so a sender that was switched off
 * for a week would come back to find its budget already spent by somebody else's
 * fruitless announcements, and its message would never be collected. What is
 * counted here is rounds that produced NOTHING: a slow transfer that keeps
 * gaining chunks resets the count and is never interrupted, whatever its size or
 * however long it takes.
 *
 * And the give up is a pause, never a verdict. After [REOPEN_AFTER_MS] the count
 * clears itself, so no content can be abandoned for good by this class. The cost
 * of that choice is a little residual traffic; the alternative cost would be a
 * lost message, which is not recoverable.
 */
object RecoveryProgress {

    private const val PREFS_NAME = "RecoveryProgressPrefs"
    private const val KEY_PREFIX = "progress_"

    /** Fruitless rounds allowed before the pause. */
    const val MAX_FRUITLESS_ROUNDS = 5

    /** After this the counter clears itself and asking resumes. */
    const val REOPEN_AFTER_MS = 6L * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(messageId: String) = "$KEY_PREFIX$messageId"

    /**
     * Whether a sendMe for [messageId] is worth sending, given that [heldChunks]
     * chunks of it are present right now.
     *
     * Call once per announcement: it records state as a side effect.
     */
    fun shouldAsk(context: Context, messageId: String, heldChunks: Int): Boolean {
        if (messageId.isEmpty()) return true

        val now = System.currentTimeMillis()
        val raw = prefs(context).getString(key(messageId), null)
        val parts = raw?.split("|")

        val fruitless = parts?.getOrNull(0)?.toIntOrNull() ?: 0
        val lastHeld = parts?.getOrNull(1)?.toIntOrNull() ?: -1
        val streakStart = parts?.getOrNull(2)?.toLongOrNull() ?: 0L

        // First sight of this transfer, or it gained ground since last time.
        if (lastHeld < 0 || heldChunks > lastHeld) {
            store(context, messageId, 0, heldChunks, 0L)
            return true
        }

        if (fruitless < MAX_FRUITLESS_ROUNDS) {
            val start = if (fruitless == 0) now else streakStart
            store(context, messageId, fruitless + 1, heldChunks, start)
            return true
        }

        if (streakStart > 0L && now - streakStart >= REOPEN_AFTER_MS) {
            debugLine("RecoveryProgress", "Reopening $messageId after the pause, asking again")
            store(context, messageId, 0, heldChunks, 0L)
            return true
        }

        debugLine(
            "RecoveryProgress",
            "Not asking for $messageId: $fruitless rounds gained nothing, still $heldChunks chunk(s)"
        )
        return false
    }

    /** Called when the transfer is done, so nothing is kept for it. */
    fun clear(context: Context, messageId: String) {
        if (messageId.isEmpty()) return
        val k = key(messageId)
        if (prefs(context).contains(k)) {
            prefs(context).edit().remove(k).apply()
        }
    }

    private fun store(
        context: Context,
        messageId: String,
        fruitless: Int,
        heldChunks: Int,
        streakStart: Long
    ) {
        prefs(context).edit()
            .putString(key(messageId), "$fruitless|$heldChunks|$streakStart")
            .apply()
    }
}
