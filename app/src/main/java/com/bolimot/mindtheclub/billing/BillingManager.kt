package com.bolimot.mindtheclub.billing

import android.app.Activity
import android.content.Context
import com.bolimot.mindtheclub.BuildConfig

/**
 * mtcx: no Google Play Billing. The subscription here is a licence bought on the web and
 * activated in the app (LicenseManager).
 *
 * This object keeps exactly the interface of the Play build's BillingManager, so every screen
 * and the access gate that call it stay identical in both branches; only this file differs.
 * With no Play price the wording falls back to the price-less strings (SubscriptionCopy), which
 * is what a de-Googled phone already saw when Play billing answered BILLING_UNAVAILABLE.
 */
@Suppress("UNUSED_PARAMETER")
object BillingManager {

    const val PRODUCT_STANDARD = "mtc_standard"

    fun init(context: Context) = Unit

    fun addListener(l: () -> Unit) = Unit
    fun removeListener(l: () -> Unit) = Unit

    fun hasSubscription(context: Context): Boolean = LicenseManager.hasValidLicense(context)

    /** Always true: there is no Play billing on this build. */
    val billingUnavailable: Boolean get() = true

    /**
     * Master gate for using the app: an active licence, an unfinished trial, or a trial that has
     * not started yet (it starts at the first message).
     */
    fun hasAccess(context: Context): Boolean =
        BuildConfig.NO_PAY ||
                hasSubscription(context) ||
                TrialManager.state(context) != TrialManager.State.EXPIRED

    fun refreshPurchases() = Unit
    fun queryProducts() = Unit
    fun launchPurchase(activity: Activity, productId: String) = Unit

    /** No Play price on this build. */
    fun recurringPrice(productId: String): String? = null
}
