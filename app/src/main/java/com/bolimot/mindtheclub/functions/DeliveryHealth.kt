package com.bolimot.mindtheclub.functions

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Detects when the device is throttling the app's background wake-ups.
 *
 * The system exposes one honest signal (isBackgroundRestricted) and it misses the OEM killers,
 * Samsung deep sleep and Xiaomi autostart above all, which are most of the real cases. So the
 * primary detector is behavioural: how long incoming messages actually take to arrive. A
 * healthy device wakes up in seconds, a throttled one in tens of minutes or hours.
 *
 * Deliberately quiet. Not being exempt from battery optimisation is the normal state for
 * almost every app and is never on its own a reason to warn.
 */
object DeliveryHealth {

    private const val PREF_LATENCIES = "mtc_delivery_latencies"
    private const val PREF_SNOOZE_UNTIL = "mtc_battery_banner_snooze_until"
    private const val PREF_HEARTBEAT = "mtc_last_heartbeat"
    private const val PREF_SUPPRESSED = "mtc_was_suppressed"

    /**
     * A gap in the heartbeat longer than this means the app was not merely dozing, non girava
     * proprio.
     *
     * The periodic workers fire every 15 minutes and every incoming FCM refreshes the mark
     * too, so three missed rounds in a row is already well outside what doze does to a phone
     * that is still being reached. On 21 Aug a handset went dark at 11:49:58 and produced
     * nothing until 12:52:25, when it was opened by hand: four wake-ups had been accepted by
     * Google in between and none delivered, which is what force stopped looks like inside.
     */
    private const val SUPPRESSED_GAP_MS = 45L * 60 * 1000

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
     * Records how long an incoming message took to arrive. Called from the one choke point
     * that stamps receivedAt, so every receive path is covered.
     *
     * [sentAt] comes from the sender's clock, so negative and absurd values are dropped rather
     * than trusted. Needing several late messages out of five means one badly set clock cannot
     * raise the warning by itself.
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

    /**
     * Marks that the app is alive and doing background work. Called from the periodic workers
     * and from every incoming FCM, cioe' from everything a phone suppressing the app stops.
     */
    fun recordHeartbeat(context: Context) {
        setPreference(PREF_HEARTBEAT, System.currentTimeMillis().toString(), context)
    }

    /**
     * Called once at start up: closes the books on the period the app was not running and
     * records a verdict, because the gap is only measurable at the moment it ends. Checking it
     * later would always find the app running and conclude everything is fine.
     */
    fun checkForSuppression(context: Context) {
        val last = getPreference(PREF_HEARTBEAT, context)?.toLongOrNull()
        recordHeartbeat(context)
        if (last == null || last <= 0L) return

        val gap = System.currentTimeMillis() - last
        if (gap in SUPPRESSED_GAP_MS..ABSURD_MS) {
            debugLine("DeliveryHealth", "App was not running for ${gap / 60000} minutes, likely suppressed")
            setPreference(PREF_SUPPRESSED, "true", context)
        }
    }

    private fun wasSuppressed(context: Context): Boolean =
        getPreference(PREF_SUPPRESSED, context) == "true"

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
        // The third condition is the one that catches the worst devices. The other two only
        // ever see messages that DID arrive, late; a phone that kills the app outright
        // delivers nothing, measures nothing, and used to look perfectly healthy. And on
        // Android 8, dove l'ho visto la prima volta, backgroundRestricted does not exist.
        return backgroundRestricted(context) || deliveriesAreLate(context) || wasSuppressed(context)
    }

    /** Dismissal: hides the banner for a month. */
    fun snooze(context: Context) {
        setPreference(PREF_SUPPRESSED, "", context)
        setPreference(
            PREF_SNOOZE_UNTIL,
            (System.currentTimeMillis() + SNOOZE_MS).toString(),
            context
        )
    }

    /**
     * Clears the measured history, used when the user goes to fix the setting. The verdict
     * rebuilds from the next messages: really fixed, the banner never returns, otherwise it
     * comes back on its own.
     */
    fun resetHistory(context: Context) {
        setPreference(PREF_LATENCIES, "", context)
        setPreference(PREF_SUPPRESSED, "", context)
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
