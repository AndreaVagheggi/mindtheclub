package com.bolimot.mindtheclub.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.start.BaseActivity

/**
 * Onboarding step 3: combined permission priming screen.
 *
 * Explains why the app needs the microphone, camera and notifications, then fires the system
 * dialogs in sequence when the user taps "Allow access". These three are core to a calling app,
 * so we ask here, up front and with a rationale, rather than at call time where a prompt would
 * interrupt an incoming call. Optional ones (contacts, media, Bluetooth) stay in context
 * elsewhere.
 *
 * If everything is already granted the screen is skipped in silenzio.
 * Flow: profile -> this -> battery -> invite.
 */
class OnboardingPermissionsActivity : BaseActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // We do not block on the outcome: whatever the user grants or denies, onboarding
            // finishes and the app opens.
            goToInviteStep()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nothing left to ask for? Skip the screen entirely (onboarding re-run while testing,
        // with the permissions already granted). Debug force mode keeps it visible.
        if (!OnboardingActivity.forcedForTesting() && missingPermissions().isEmpty()) {
            goToInviteStep()
            return
        }

        setContentView(R.layout.activity_onboarding_permissions)

        findViewById<View>(R.id.allowAccess).setOnClickListener {
            val toRequest = missingPermissions()
            if (toRequest.isEmpty()) {
                goToInviteStep()
            } else {
                requestPermissions.launch(toRequest)
            }
        }

        findViewById<View>(R.id.skipAccess).setOnClickListener {
            goToInviteStep()
        }
    }

    /** Core permissions not yet granted; POST_NOTIFICATIONS only exists on Android 13+. */
    private fun missingPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android 9 and below: saving received photos and videos to public storage
        // (Movies/Pictures) uses a raw File write that needs WRITE_EXTERNAL_STORAGE at runtime.
        // From API 29 the save path uses MediaStore and the permission is neither needed nor
        // grantable (the manifest caps it at maxSdkVersion=28).
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun goToInviteStep() {
        // Next: battery priming (skips itself if already exempt), then the invite screen.
        startActivity(Intent(this, OnboardingBatteryActivity::class.java))
        finish()
    }
}
