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
 * Onboarding step: battery restriction priming screen.
 *
 * MindTheClub has no servers, the phones themselves deliver the messages, so Android's battery
 * optimization (doze) directly delays delivery. This screen explains that and deep links the
 * user to the battery optimization settings.
 *
 * Deliberately NOT using ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: the direct dialog needs
 * the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS manifest permission, which draws Play review
 * scrutiny. A settings deep link needs no permission at all, and checking the state via
 * PowerManager is free.
 *
 * If the exemption is already granted the screen is skipped in silenzio. Once the user has been
 * sent to settings, returning advances to the next step and the screen never re-presents itself
 * (an optional suggestion, not a gate). Flow: permissions -> this -> plan -> invite.
 */
class OnboardingBatteryActivity : BaseActivity() {

    // Set when the user taps "Sure, show me": on the next onResume, cioe' when they come back
    // from the settings screen, we move on whatever they chose.
    private var sentToSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // In debug force mode always show the screen (no auto-skip when exempt), so the layout
        // and copy can be reviewed on every launch.
        if (!OnboardingActivity.forcedForTesting() && isExempt()) {
            goToPlanStep()
            return
        }

        setContentView(R.layout.activity_onboarding_battery)

        findViewById<View>(R.id.openBatterySettings).setOnClickListener {
            sentToSettings = true
            openBatteryOptimizationSettings()
        }

        findViewById<View>(R.id.skipBattery).setOnClickListener {
            goToPlanStep()
        }
    }

    /**
     * Opens the system battery optimization app list (no permission required), where the user
     * finds MindTheClub and sets it to "Don't optimize". The right screen on stock Android; the
     * generic App info page hides the control on some OEM skins (EMUI). Falls back to App info
     * when the OEM does not expose the standard settings action.
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
        // Came back from the settings screen we opened: advance rather than showing the
        // identical screen again (which reads as "did nothing happen?"). We do not check whether
        // the exemption was granted, e' opzionale, and re-presenting is worse than moving on.
        if (sentToSettings) {
            goToPlanStep()
        }
    }

    private fun isExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun goToPlanStep() {
        startActivity(Intent(this, OnboardingPlanActivity::class.java))
        finish()
    }
}
