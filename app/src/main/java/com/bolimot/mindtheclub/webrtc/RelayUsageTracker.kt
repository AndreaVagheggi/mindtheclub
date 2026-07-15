package com.bolimot.mindtheclub.webrtc

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.start.App
import java.util.Calendar


object RelayUsageTracker {

    // Standard tier: relay is the 20% fallback path. Stealth tier costs more,
    // so it buys a larger allowance (see pricing model in the master strategy).
    private const val RELAY_CAP_BYTES = 10L * 1_000_000_000L
    private const val STEALTH_RELAY_CAP_BYTES = 15L * 1_000_000_000L

    private const val PREF_PERIOD = "mtc_relay_period"
    private const val PREF_BYTES = "mtc_relay_bytes"

    private fun currentPeriod(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    @Synchronized
    fun bytesThisMonth(): Long {
        val ctx = App.context()
        if (getPreference(PREF_PERIOD, ctx) != currentPeriod()) return 0L
        return getPreference(PREF_BYTES, ctx)?.toLongOrNull() ?: 0L
    }

    fun isOverCap(stealthTier: Boolean = false): Boolean =
        bytesThisMonth() >= if (stealthTier) STEALTH_RELAY_CAP_BYTES else RELAY_CAP_BYTES

    @Synchronized
    fun addRelayBytes(bytes: Long) {
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
        debugLine("RelayUsageTracker", "Relay +$bytes B -> $updated B this month (cap $RELAY_CAP_BYTES)")
    }
}
