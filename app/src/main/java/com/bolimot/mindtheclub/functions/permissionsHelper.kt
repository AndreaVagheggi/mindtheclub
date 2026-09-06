package com.bolimot.mindtheclub.functions

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Recovery helpers for runtime permissions denied during or after onboarding.
 *
 * Android forbids re-showing the dialog once a permission is permanently denied, so every
 * "fix" path here has two branches:
 *   - the dialog can still be shown  -> request in-app, or
 *   - permanently denied             -> deep link to the app's system settings.
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

/**
 * Whether this handset can do Picture in Picture at all.
 *
 * PiP is an optional platform feature, not a guarantee: budget ROMs ship without it, and some
 * of those still show the per app PiP toggle in settings with nothing behind it. A device that
 * answers false can never enter PiP, so the control is hidden rather than left to fail.
 */
fun deviceSupportsPip(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/**
 * Everything the system weighs when it decides whether to grant Picture in Picture, sampled at
 * the moment it refused.
 *
 * enterPictureInPictureMode() reports refusal by returning false rather than throwing, so a
 * refusal otherwise leaves no trace at all and the button simply looks dead. Each field maps to
 * one of the conditions in the platform's own check, plus the device identity, perche' un
 * rifiuto su un telefono e non su un altro is the whole question.
 */
fun describePipAvailability(activity: ComponentActivity): String {
    val device = "${Build.MANUFACTURER} ${Build.MODEL} api${Build.VERSION.SDK_INT}"

    val hasFeature = activity.packageManager
        .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    if (!hasFeature) return "$device: device does not declare FEATURE_PICTURE_IN_PICTURE"

    val appOp = try {
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        when (appOps?.let { readPipAppOp(it, activity.packageName) }) {
            null -> "unavailable"
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "DENIED in settings"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            else -> "mode unknown"
        }
    } catch (e: Exception) {
        "unreadable: ${e.message}"
    }

    val keyguardLocked = try {
        (activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked
    } catch (e: Exception) {
        null
    }

    val powerSave = try {
        (activity.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode
    } catch (e: Exception) {
        null
    }

    val lockTask = try {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.lockTaskModeState
    } catch (e: Exception) {
        null
    }

    return "$device: feature=yes, appOp=$appOp, " +
            "lifecycle=${activity.lifecycle.currentState}, " +
            "keyguardLocked=$keyguardLocked, powerSaveMode=$powerSave, " +
            "lockTaskState=$lockTask, alreadyInPip=${activity.isInPictureInPictureMode}, " +
            "multiWindow=${activity.isInMultiWindowMode}"
}

private fun readPipAppOp(appOps: AppOpsManager, packageName: String): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName
        )
    }

/**
 * Opens the system Picture in Picture special access page for this app.
 *
 * The action is spelled out rather than taken from [Settings]: the platform keeps
 * ACTION_PICTURE_IN_PICTURE_SETTINGS out of the public SDK, so the constant will not compile
 * even though the action itself resolves on any device that ships the screen. Devices that do
 * not are covered by the fallback to the app details page.
 */
private const val ACTION_PICTURE_IN_PICTURE_SETTINGS = "android.settings.PICTURE_IN_PICTURE_SETTINGS"

fun openPipSettings(context: Context) {
    val intent = Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        debugLine("permissionsHelper", "PiP settings not available, falling back: ${e.message}")
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
 * Call flow guard: true when every permission the call needs is granted. Otherwise it starts
 * the recovery (in-app request or settings dialog) and returns false, and the caller must NOT
 * start the call; the user re-taps after granting.
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
