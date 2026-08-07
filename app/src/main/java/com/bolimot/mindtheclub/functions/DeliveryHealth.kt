package com.bolimot.mindtheclub.functions

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Detects when the device is throttling the app's background wake-ups.
 *
 * The system only exposes one honest signal (isBackgroundRestricted) and it
 * misses the OEM killers, Samsung deep sleep and Xiaomi autostart above all,
 * which cause most real cases. So the primary detector is behavioural: how
 * long incoming messages actually take to arrive. A healthy device delivers a
 * wake-up in seconds, a throttled one in tens of minutes or hours.
 *
 * Deliberately quiet. Not being exempt from battery optimisation is the normal
 * state for almost every app and is never on its own a reason to warn.
 */
object DeliveryHealth {

    private const val PREF_LATENCIES = "mtc_delivery_latencies"
    private const val PREF_SNOOZE_UNTIL = "mtc_battery_banner_snooze_until"

    /** How many recent incoming messages the verdict looks at. */
    private const val WINDOW = 5

    /** Above this delay a message counts as late. */
    private const val SLOW_MS = 10L * 60 * 1000

    /** Beyond this the value is clock skew between devices, not throttling. */
    private const val ABSURD_MS = 7L * 24 * 60 * 60 * 1000

    /** How many of the last [WINDOW] must be late before warning. */
    private const val SLOW_TO_WARN = 3

    /** How long the banner stays away after the user dismisses it. */
    private const val SNOOZE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Records how long an incoming message took to arrive. Called from the one
     * choke point that stamps receivedAt, so every receive path is covered.
     *
     * [sentAt] comes from the sender's clock, so negative and absurd values are
     * dropped instead of trusted. Requiring several late messages out of five
     * means a single badly set clock cannot raise the warning on its own.
     */
    fun recordIncoming(sentAt: Long, receivedAt: Long, context: Context) {
        val latency = receivedAt - sentAt
        if (latency < 0L || latency > ABSURD_MS) return

        val history = readHistory(context)
        val updated = (history + latency).takeLast(WINDOW)
        setPreference(PREF_LATENCIES, updated.joinToString(","), context)
    }

    private fun readHistory(context: Context): List<Long> =
        getPreference(PREF_LATENCIES, context)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()

    /** True when recent deliveries have been consistently late. */
    private fun deliveriesAreLate(context: Context): Boolean {
        val history = readHistory(context)
        if (history.size < WINDOW) return false
        return history.count { it > SLOW_MS } >= SLOW_TO_WARN
    }

    /** True when the user explicitly restricted the app's background work. */
    private fun backgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.isBackgroundRestricted
    }

    /** Whether the main screen should show the "messages may be delayed" banner. */
    fun shouldWarn(context: Context): Boolean {
        val snoozeUntil = getPreference(PREF_SNOOZE_UNTIL, context)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() < snoozeUntil) return false
        return backgroundRestricted(context) || deliveriesAreLate(context)
    }

    /** Dismissal: hides the banner for a month. */
    fun snooze(context: Context) {
        setPreference(
            PREF_SNOOZE_UNTIL,
            (System.currentTimeMillis() + SNOOZE_MS).toString(),
            context
        )
    }

    /**
     * Clears the measured history, used when the user goes to fix the setting.
     * The verdict then rebuilds from the next messages: if the device is really
     * fixed the banner never returns, otherwise it comes back on its own.
     */
    fun resetHistory(context: Context) {
        setPreference(PREF_LATENCIES, "", context)
    }

    /** Opens the battery optimisation settings, falling back to the app page. */
    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            debugLine("DeliveryHealth", "Battery settings not available: ${e.message}")
            openAppSettings(context)
        }
    }
}
