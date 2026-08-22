package com.bolimot.mindtheclub.functions

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo

/**
 * What the operating system is currently doing to this app.
 *
 * Every symptom chased through August ended up here and could not be told apart
 * from the logs: retries 13 and 19 minutes apart instead of 15 and 30 seconds,
 * wake-ups accepted by Google and never delivered, transfers frozen at 82%.
 * Doze, an app standby bucket that had drifted down, and an exhausted expedited
 * quota all look identical from inside the app, and all three are decided
 * outside it. Guessing which one was in play cost days.
 *
 * None of this needs a permission: an app may always ask about itself.
 */
object AndroidState {

    /**
     * One line, cheap enough to stamp on every dispatch.
     *
     * bucket is the one that matters most and is the least visible: it drives
     * BOTH how fast a high priority FCM is delivered and how much expedited
     * quota is left, so a phone quietly demoted to rare or restricted starts
     * failing in two ways at once. exempt is the battery optimisation setting
     * the user can change; restricted is the harder "Restrict background usage"
     * switch; idle says whether the device is in doze at this instant.
     */
    fun describe(context: Context): String {
        val parts = mutableListOf<String>()

        parts += "bucket=" + standbyBucketName(context)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            parts += "exempt=" + pm.isIgnoringBatteryOptimizations(context.packageName)
            parts += "idle=" + pm.isDeviceIdleMode
            parts += "saver=" + pm.isPowerSaveMode
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) parts += "restricted=" + am.isBackgroundRestricted
        }

        return parts.joinToString(" ")
    }

    /**
     * The standby bucket in words. Below API 28 the concept does not exist, and
     * from API 30 the system may also report RESTRICTED, the harshest one.
     */
    private fun standbyBucketName(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "n/a"
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return "unknown"
            when (val bucket = usm.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                45 -> "RESTRICTED"   // STANDBY_BUCKET_RESTRICTED, API 30+
                else -> "code$bucket"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Why the system stopped a worker. This is the answer that was missing every
     * time a dispatch died mid-transfer: until now a stopped worker and a failed
     * connection looked the same from the log.
     *
     * QUOTA is the one to watch for, it means the expedited allowance ran out
     * and the work was demoted; APP_STANDBY and DEVICE_IDLE mean the bucket or
     * doze took it.
     */
    fun stopReasonName(code: Int): String = when (code) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> "not_stopped"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
        WorkInfo.STOP_REASON_PREEMPT -> "preempted"
        WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "device_state"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery_low"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "not_charging"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "no_connectivity"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "device_idle"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage_low"
        WorkInfo.STOP_REASON_QUOTA -> "QUOTA_EXHAUSTED"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background_restricted"
        WorkInfo.STOP_REASON_APP_STANDBY -> "app_standby"
        WorkInfo.STOP_REASON_USER -> "user"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system_processing"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "launch_time_changed"
        else -> "code$code"
    }
}
