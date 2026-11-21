package com.bolimot.mindtheclub.functions

import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.bolimot.mindtheclub.R
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

private const val TAG = "AppUpdater"

/**
 * "Check for updates" in the About dialog (OptionsActivity).
 *
 * PLAY BUILD. Uses Google Play In-App Updates, immediate flow: Play shows its own full-screen
 * update, downloads, installs and restarts the App. Play policy forbids an app from updating
 * itself by any other means, so this file never downloads anything itself.
 *
 * mtcx has its OWN version of this file (F-Droid archive + PackageInstaller), with the same
 * class name and the same public API, so OptionsActivity is identical in both apps. When
 * merging main into degoogle, keep the mtcx version of this file.
 *
 * Create it as a property of the activity: it registers an activity result launcher, which
 * must happen before the activity is STARTED.
 */
class AppUpdater(private val activity: ComponentActivity) {

    private val manager by lazy { AppUpdateManagerFactory.create(activity.applicationContext) }

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // RESULT_CANCELED: the user declined, nothing to say. For an immediate update RESULT_OK
        // usually never arrives, because the App has already been restarted by then.
        debugLine(TAG, "update flow result ${result.resultCode}")
        if (result.resultCode ==
            com.google.android.play.core.install.model.ActivityResult.RESULT_IN_APP_UPDATE_FAILED
        ) {
            showToast(activity.getString(R.string.update_failed), activity)
        }
    }

    /**
     * Asks Play whether a newer version exists and, if so, starts the update.
     * [onChecked] runs once the answer is known (to re-enable the button).
     */
    fun check(onChecked: () -> Unit = {}) {
        manager.appUpdateInfo
            .addOnSuccessListener(activity) { info ->
                onChecked()
                when (info.updateAvailability()) {
                    UpdateAvailability.UPDATE_AVAILABLE ->
                        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            startUpdate(info)
                        } else {
                            debugLine(TAG, "immediate update not allowed")
                            showToast(activity.getString(R.string.update_check_failed), activity)
                        }

                    // An update started earlier and interrupted: resume it, as the docs require.
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> startUpdate(info)

                    UpdateAvailability.UPDATE_NOT_AVAILABLE ->
                        showToast(activity.getString(R.string.update_up_to_date), activity)

                    else -> {
                        debugLine(TAG, "update availability unknown")
                        showToast(activity.getString(R.string.update_check_failed), activity)
                    }
                }
            }
            .addOnFailureListener(activity) { e ->
                // Typically the App was not installed from Play (sideloaded build).
                onChecked()
                debugLine(TAG, "appUpdateInfo failed: ${e.message}")
                showToast(activity.getString(R.string.update_check_failed), activity)
            }
    }

    private fun startUpdate(info: AppUpdateInfo) {
        try {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            )
        } catch (e: IntentSender.SendIntentException) {
            debugLine(TAG, "startUpdateFlowForResult failed: ${e.message}")
            showToast(activity.getString(R.string.update_check_failed), activity)
        }
    }
}
