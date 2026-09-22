package com.bolimot.mindtheclub.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.billing.BillingManager
import com.bolimot.mindtheclub.billing.LicenseManager
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.bolimot.mindtheclub.billing.SubscriptionCopy
import com.bolimot.mindtheclub.billing.TrialManager
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * Paywall and subscription management screen.
 *
 * Launched (a) as a blocking gate from BaseActivity when the 30 day trial has ended and no
 * subscription is active (EXTRA_REQUIRED = true: back sends the task to the background instead of
 * dismissing the gate), and (b) voluntarily from the Subscription row in OptionsActivity.
 */
class SubscriptionActivity : BaseActivity() {

    companion object {
        const val EXTRA_REQUIRED = "required"
    }

    private var required = false

    /** This IS the gate's destination, it must never gate itself. */
    override fun isSubscriptionGateExempt(): Boolean = true

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
        findViewById<MaterialButton>(R.id.manageButton).setOnClickListener {
            openPlaySubscriptions()
        }

        // Licence bought outside Google Play (see LicenseManager).
        findViewById<MaterialButton>(R.id.webBuyButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, LicenseManager.PURCHASE_URL.toUri()))
        }
        findViewById<MaterialButton>(R.id.licenseManageButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, LicenseManager.MANAGE_URL.toUri()))
        }
        findViewById<MaterialButton>(R.id.licenseActivateButton).setOnClickListener {
            activateLicense()
        }
        findViewById<MaterialButton>(R.id.licenseCopyButton).setOnClickListener {
            copyLicenseCode()
        }
        // Arrived from https://www.mindtheclub.com/license?code=... after paying.
        LicenseManager.takePendingCode(this)?.let { code ->
            findViewById<TextInputEditText>(R.id.licenseInput).setText(code)
            activateLicense()
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
        val subscribed = BillingManager.hasSubscription(this)

        val statusText: TextView = findViewById(R.id.subscriptionStatus)
        statusText.text = when {
            subscribed -> getString(R.string.sub_status_standard)
            TrialManager.state(this) == TrialManager.State.ACTIVE ->
                SubscriptionCopy.daysLeftText(this)
            TrialManager.state(this) == TrialManager.State.NOT_STARTED ->
                getString(R.string.sub_trial_not_started)
            else -> getString(R.string.sub_trial_expired)
        }

        val standardPrice: TextView = findViewById(R.id.standardPrice)
        standardPrice.text = BillingManager.recurringPrice(BillingManager.PRODUCT_STANDARD)
            ?.let { getString(R.string.sub_price_per_month, it) }
            ?: getString(R.string.sub_price_loading)

        val standardButton: MaterialButton = findViewById(R.id.standardButton)
        val manageButton: MaterialButton = findViewById(R.id.manageButton)

        standardButton.isEnabled = !subscribed
        standardButton.text = getString(
            if (subscribed) R.string.sub_current_plan else R.string.sub_subscribe
        )
        manageButton.isEnabled = subscribed

        // Outside Google Play: the de-Google build always, a Play install only when this phone
        // has no Play Store. A Play user with a working Play Store sees the screen as before,
        // with no link or price for another payment method (Play payments policy).
        val deGoogle = LicenseManager.isDeGoogleBuild
        val noPlay = deGoogle || BillingManager.billingUnavailable
        val licenceCode = LicenseManager.code(this)
        findViewById<android.view.View>(R.id.playCard).visibility = if (noPlay) View.GONE else View.VISIBLE
        manageButton.visibility = if (noPlay) View.GONE else View.VISIBLE
        findViewById<android.view.View>(R.id.webCard).visibility =
            if (deGoogle && LicenseManager.PURCHASE_URL.isNotEmpty() && !subscribed) View.VISIBLE else View.GONE
        findViewById<android.view.View>(R.id.licenseCard).visibility =
            if (noPlay || licenceCode != null) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.licenseManageButton).visibility =
            if (deGoogle && licenceCode != null) View.VISIBLE else View.GONE

        // The code is shown only where it exists, and it is the only copy the user has:
        // it is not mailed out, and without it a licence cannot be moved to another phone.
        findViewById<View>(R.id.licenseCodeBox).visibility =
            if (licenceCode != null) View.VISIBLE else View.GONE
        licenceCode?.let { findViewById<TextView>(R.id.licenseCodeText).text = it }

        if (LicenseManager.hasValidLicense(this)) {
            showLicenseStatus(getString(R.string.license_active_until,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(LicenseManager.paidUntil(this)))))
        }
    }

    /**
     * Android 13 and later show their own confirmation when something is copied, so a toast
     * here would say the same thing twice.
     */
    private fun copyLicenseCode() {
        val code = LicenseManager.code(this) ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.license_your_code), code))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.license_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLicenseStatus(text: String) {
        findViewById<TextView>(R.id.licenseStatus).apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun activateLicense() {
        val input = findViewById<TextInputEditText>(R.id.licenseInput)
        val code = input.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) return
        val button = findViewById<MaterialButton>(R.id.licenseActivateButton)
        button.isEnabled = false
        lifecycleScope.launch {
            val status = LicenseManager.redeem(this@SubscriptionActivity, code)
            button.isEnabled = true
            showLicenseStatus(getString(when (status) {
                LicenseManager.Status.VALID -> R.string.license_activated
                LicenseManager.Status.EXPIRED -> R.string.license_expired
                LicenseManager.Status.INVALID -> R.string.license_invalid
                LicenseManager.Status.MOVED, LicenseManager.Status.TOO_MANY_MOVES -> R.string.license_too_many_moves
                LicenseManager.Status.REVOKED -> R.string.license_revoked
                LicenseManager.Status.NETWORK_ERROR -> R.string.license_network_error
            }))
            if (status == LicenseManager.Status.VALID) renderState()
        }
    }

    private fun openPlaySubscriptions() {
        val url = "https://play.google.com/store/account/subscriptions?package=$packageName"
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}
