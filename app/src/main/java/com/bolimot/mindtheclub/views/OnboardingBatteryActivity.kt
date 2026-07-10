package com.bolimot.mindtheclub.views

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.openAppSettings
import com.bolimot.mindtheclub.start.BaseActivity

/**
 * Onboarding step: battery-restriction priming screen.
 *
 * MindTheClub has no servers — the phones themselves deliver the messages, so
 * Android's battery optimization (doze) directly delays delivery. This screen
 * explains that and deep-links the user to the battery-optimization settings.
 *
 * Deliberately NOT using ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: the
 * direct dialog requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS manifest
 * permission, which draws Play review scrutiny. A settings deep-link needs no
 * permission at all. Checking the state via PowerManager is also free.
 *
 * If the exemption is already granted the screen is skipped silently. Once the
 * user has been sent to settings, returning advances to the next step — the
 * screen never re-presents itself (this is an optional suggestion, not a gate).
 * Flow: permissions screen -> this screen -> invite screen.
 */
class OnboardingBatteryActivity : BaseActivity() {

    // Set when the user taps "Sure, show me": on the next onResume (i.e. when
    // they return from the settings screen) we move on, whatever they chose.
    private var sentToSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // In debug-force mode always show the screen (don't auto-skip when exempt),
        // so the layout/copy can be reviewed on every launch.
        if (!OnboardingActivity.forcedForTesting() && isExempt()) {
            goToInviteStep()
            return
        }

        setContentView(R.layout.activity_onboarding_battery)

        findViewById<View>(R.id.openBatterySettings).setOnClickListener {
            sentToSettings = true
            openBatteryOptimizationSettings()
        }

        findViewById<View>(R.id.skipBattery).setOnClickListener {
            goToInviteStep()
        }
    }

    /**
     * Opens the system's battery-optimization app list (no permission required),
     * where the user finds MindTheClub and sets it to "Don't optimize". This is
     * the correct screen on stock Android; the generic App-info page hides the
     * control on some OEM skins (e.g. EMUI). Falls back to App info if the OEM
     * doesn't expose the standard settings action.
     */
    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            debugLine("OnboardingBattery", "Battery optimization settings not available: ${e.message}")
            openAppSettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returned from the settings screen we opened: advance rather than showing
        // the identical screen again (which reads as "did nothing happen?"). We
        // don't check whether the exemption was actually granted — it's optional,
        // and re-presenting the same prompt is worse than moving on.
        if (sentToSettings) {
            goToInviteStep()
        }
    }

    private fun isExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun goToInviteStep() {
        startActivity(Intent(this, OnboardingInviteActivity::class.java))
        finish()
    }
}
