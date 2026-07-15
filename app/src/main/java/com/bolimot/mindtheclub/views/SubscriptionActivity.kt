package com.bolimot.mindtheclub.views

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.billing.BillingManager
import com.bolimot.mindtheclub.billing.TrialManager
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * Paywall / plan management screen.
 *
 * Launched (a) as a blocking gate from AppTab when the 30-day trial has ended
 * and no subscription is active (EXTRA_REQUIRED = true: back sends the task to
 * the background instead of dismissing the gate), and (b) voluntarily from the
 * Stealth toggle in OptionsActivity to upgrade.
 */
class SubscriptionActivity : BaseActivity() {

    companion object {
        const val EXTRA_REQUIRED = "required"
    }

    private var required = false

    private val billingListener: () -> Unit = {
        runOnUiThread { if (!isFinishing && !isDestroyed) renderState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        required = intent.getBooleanExtra(EXTRA_REQUIRED, false)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.subscription_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(!required)

        toolbar.setNavigationOnClickListener { if (!required) finish() }

        findViewById<MaterialButton>(R.id.standardButton).setOnClickListener {
            BillingManager.launchPurchase(this, BillingManager.PRODUCT_STANDARD)
        }
        findViewById<MaterialButton>(R.id.stealthButton).setOnClickListener {
            BillingManager.launchPurchase(this, BillingManager.PRODUCT_STEALTH)
        }
        findViewById<MaterialButton>(R.id.manageButton).setOnClickListener {
            openPlaySubscriptions()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (required && !BillingManager.hasAccess(this@SubscriptionActivity)) {
                    // Access gate: don't dismiss, just leave the app.
                    moveTaskToBack(true)
                } else {
                    finish()
                }
            }
        })

        BillingManager.addListener(billingListener)
        BillingManager.queryProducts()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from the Play purchase sheet.
        BillingManager.refreshPurchases()
        renderState()
    }

    override fun onDestroy() {
        BillingManager.removeListener(billingListener)
        super.onDestroy()
    }

    private fun renderState() {
        val entitlement = BillingManager.entitlement(this)

        val statusText: TextView = findViewById(R.id.subscriptionStatus)
        statusText.text = when {
            entitlement == BillingManager.Entitlement.STEALTH ->
                getString(R.string.sub_status_stealth)
            entitlement == BillingManager.Entitlement.STANDARD ->
                getString(R.string.sub_status_standard)
            TrialManager.state(this) == TrialManager.State.ACTIVE ->
                getString(R.string.sub_trial_active, TrialManager.daysLeft(this))
            TrialManager.state(this) == TrialManager.State.NOT_STARTED ->
                getString(R.string.sub_trial_not_started)
            else -> getString(R.string.sub_trial_expired)
        }

        val standardPrice: TextView = findViewById(R.id.standardPrice)
        val stealthPrice: TextView = findViewById(R.id.stealthPrice)
        standardPrice.text = BillingManager.recurringPrice(BillingManager.PRODUCT_STANDARD)
            ?.let { getString(R.string.sub_price_per_month, it) }
            ?: getString(R.string.sub_price_loading)
        stealthPrice.text = BillingManager.recurringPrice(BillingManager.PRODUCT_STEALTH)
            ?.let { getString(R.string.sub_price_per_month, it) }
            ?: getString(R.string.sub_price_loading)

        val standardButton: MaterialButton = findViewById(R.id.standardButton)
        val stealthButton: MaterialButton = findViewById(R.id.stealthButton)
        val manageButton: MaterialButton = findViewById(R.id.manageButton)

        when (entitlement) {
            BillingManager.Entitlement.NONE -> {
                standardButton.isEnabled = true
                standardButton.text = getString(R.string.sub_subscribe)
                stealthButton.isEnabled = true
                stealthButton.text = getString(R.string.sub_subscribe)
                manageButton.isEnabled = false
            }
            BillingManager.Entitlement.STANDARD -> {
                standardButton.isEnabled = false
                standardButton.text = getString(R.string.sub_current_plan)
                stealthButton.isEnabled = true
                stealthButton.text = getString(R.string.sub_upgrade)
                manageButton.isEnabled = true
            }
            BillingManager.Entitlement.STEALTH -> {
                standardButton.isEnabled = true
                standardButton.text = getString(R.string.sub_downgrade)
                stealthButton.isEnabled = false
                stealthButton.text = getString(R.string.sub_current_plan)
                manageButton.isEnabled = true
            }
        }
    }

    private fun openPlaySubscriptions() {
        val url = "https://play.google.com/store/account/subscriptions?package=$packageName"
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}
