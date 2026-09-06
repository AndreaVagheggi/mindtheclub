package com.bolimot.mindtheclub.views

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.billing.SubscriptionCopy
import com.bolimot.mindtheclub.start.BaseActivity

/**
 * Onboarding step: how MindTheClub is paid for.
 *
 * Placed right before the invite screen apposta: setup is done and the user is about to send their
 * first message, which is exactly when the 30 day trial clock starts. Saying so here also makes
 * the "invite your friends" step land better ("you have 30 days to bring them over").
 *
 * The price is never hard coded: it comes from Google Play already localized to the user's country
 * and currency. If ProductDetails have not loaded yet (no network on first launch), a price-less
 * wording is shown rather than a blank or a wrong number.
 *
 * Flow: battery -> this -> invite.
 */
class OnboardingPlanActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding_plan)

        findViewById<TextView>(R.id.planBody).text = SubscriptionCopy.onboardingBody(this)

        findViewById<View>(R.id.planContinue).setOnClickListener {
            startActivity(Intent(this, OnboardingInviteActivity::class.java))
            finish()
        }
    }
}
