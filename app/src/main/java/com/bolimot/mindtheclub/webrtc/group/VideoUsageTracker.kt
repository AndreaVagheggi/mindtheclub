package com.bolimot.mindtheclub.webrtc.group

import android.content.Context
import com.bolimot.mindtheclub.billing.BillingManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.webrtc.RelayUsageTracker
import java.util.Calendar

/**
 * Monthly group video allowance, metered on this device for this device.
 *
 * Every byte counted here is also added to [RelayUsageTracker], apposta: video draws from the
 * same 15 GB relay meter that TURN already draws from, so the absolute worst case per user stays
 * exactly what it is today instead of being stacked on top of it.
 *
 * On the trial the 500 MB allowance below is the tighter of the two and the one the user actually
 * meets. With a subscription there is no video allowance at all, and the relay meter is the only
 * limit left.
 *
 * No server behind this, consistent with the rest of the app's convenience model: the counter
 * lives in preferences, and what really bounds the spend is DAILY_SFU_BUDGET in the mtc-sfu
 * worker.
 */
object VideoUsageTracker {

    private const val PREF_PERIOD = "mtc_video_period"
    private const val PREF_BYTES = "mtc_video_bytes"
    private const val PREF_WARNED = "mtc_video_warned"

    private fun currentPeriod(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    /** Unlimited with a subscription, 500 MB on the trial. */
    fun allowanceBytes(context: Context = App.context()): Long =
        if (BillingManager.hasSubscription(context)) GroupCallConfig.ALLOWANCE_SUBSCRIBED_BYTES
        else GroupCallConfig.ALLOWANCE_TRIAL_BYTES

    @Synchronized
    fun bytesThisMonth(): Long {
        val ctx = App.context()
        if (getPreference(PREF_PERIOD, ctx) != currentPeriod()) return 0L
        return getPreference(PREF_BYTES, ctx)?.toLongOrNull() ?: 0L
    }

    fun remainingBytes(): Long = (allowanceBytes() - bytesThisMonth()).coerceAtLeast(0L)

    fun fractionUsed(): Double {
        val allowance = allowanceBytes()
        if (allowance <= 0L) return 1.0
        return bytesThisMonth().toDouble() / allowance.toDouble()
    }

    /** No allowance left at all: new calls are refused, running ones are audio only. */
    fun isExhausted(): Boolean = bytesThisMonth() >= allowanceBytes()

    /** Past the point where the picture is dropped and the call continues in audio. */
    fun isAudioOnly(): Boolean = fractionUsed() >= GroupCallConfig.AUDIO_ONLY_AT

    /**
     * True once, the first time the month's usage crosses the warning line. The flag resets with
     * the month, so the user is told once per period and not on every poll of a long call.
     */
    @Synchronized
    fun consumeWarning(): Boolean {
        val ctx = App.context()
        if (fractionUsed() < GroupCallConfig.WARN_AT) return false
        if (getPreference(PREF_WARNED, ctx) == currentPeriod()) return false
        setPreference(PREF_WARNED, currentPeriod(), ctx)
        return true
    }

    @Synchronized
    fun addVideoBytes(bytes: Long) {
        if (bytes <= 0L) return
        val ctx = App.context()
        val period = currentPeriod()

        val base = if (getPreference(PREF_PERIOD, ctx) == period) {
            getPreference(PREF_BYTES, ctx)?.toLongOrNull() ?: 0L
        } else {
            setPreference(PREF_PERIOD, period, ctx)
            0L
        }

        val updated = base + bytes
        setPreference(PREF_BYTES, updated.toString(), ctx)

        // Same bytes, second meter: the relay cap is the money guarantee and it must see
        // everything that leaves through a relay, TURN or SFU alike.
        RelayUsageTracker.addRelayBytes(bytes)

        val cap = allowanceBytes(ctx)
        val capLabel = if (cap == Long.MAX_VALUE) "unlimited" else cap.toString()
        debugLine(
            "VideoUsageTracker",
            "Video +$bytes B -> $updated B this month (allowance $capLabel)"
        )
    }
}
