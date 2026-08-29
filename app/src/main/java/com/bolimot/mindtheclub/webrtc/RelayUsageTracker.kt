package com.bolimot.mindtheclub.webrtc

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.start.App
import java.util.Calendar


object RelayUsageTracker {

    /**
     * Monthly relay budget for this handset, counting sent PLUS received.
     *
     * Raised from 10 GB on 29 Aug 2026. Cloudflare Realtime bills $0.05 per GB
     * of egress only, so a byte this device sends into the relay is free and
     * only a byte it receives is charged: roughly half of what this counter
     * holds for 1:1 TURN, and (N-1)/N of it for a group call of N. At 15 GB a
     * subscriber who saturates it every month still costs less than the
     * subscription nets after VAT and the Play commission. See docs/costs.md.
     *
     * The ceiling is per handset and Cloudflare bills the account, so the real
     * exposure is N x this number and nothing anywhere adds that sum up. The
     * brake for a runaway is DAILY_ICE_BUDGET in mtc-ice, not this constant.
     */
    private const val RELAY_CAP_BYTES = 15L * 1_000_000_000L

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

    fun isOverCap(): Boolean = bytesThisMonth() >= RELAY_CAP_BYTES

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
