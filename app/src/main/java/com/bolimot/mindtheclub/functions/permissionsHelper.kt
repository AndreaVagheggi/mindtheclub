package com.bolimot.mindtheclub.functions

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Recovery helpers for runtime permissions denied during (or after) onboarding.
 *
 * Android forbids re-showing the permission dialog once the user has permanently
 * denied a permission, so every "fix" path here has two branches:
 *   - the dialog can still be shown  -> request in-app, or
 *   - permanently denied            -> deep-link to the app's system settings.
 */

/** Snooze marker for the notifications-off banner on the main screen. */
const val PREF_NOTIF_BANNER_SNOOZE_UNTIL = "mtc_notif_banner_snooze_until"

/** How long the banner stays away after the user dismisses it. */
const val NOTIF_BANNER_SNOOZE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

/** Request code used by the call-permission guard. */
const val REQ_FIX_PERMISSIONS = 4711

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun areNotificationsEnabled(context: Context): Boolean =
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .areNotificationsEnabled()

/** Opens the system notification settings page for this app. */
fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        debugLine("permissionsHelper", "Notification settings not available, falling back: ${e.message}")
        openAppSettings(context)
    }
}

/** Opens the app's details page in system settings (the road back from "permanently denied"). */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        debugLine("permissionsHelper", "Cannot open app settings: ${e.message}")
    }
}

/**
 * Call-flow guard: returns true when all permissions needed for the call are
 * granted. Otherwise it starts the recovery (in-app request or settings dialog)
 * and returns false — the caller must NOT start the call; the user re-taps the
 * call button after granting.
 */
fun ensureCallPermissions(activity: Activity, isVideo: Boolean): Boolean {
    val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (isVideo) needed.add(Manifest.permission.CAMERA)

    val missing = needed.filter { !hasPermission(activity, it) }
    if (missing.isEmpty()) return true

    debugLine("ensureCallPermissions", "Missing for ${if (isVideo) "video" else "audio"} call: $missing")

    val canStillAsk = missing.any {
        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }

    if (canStillAsk) {
        ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQ_FIX_PERMISSIONS)
    } else {
        MaterialAlertDialogBuilder(activity)
            .setMessage(
                activity.getString(
                    if (isVideo) R.string.perm_call_needs_camera else R.string.perm_call_needs_mic
                )
            )
            .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings(activity) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    return false
}
