package com.bolimot.mindtheclub.billing

import android.content.Context
import com.bolimot.mindtheclub.R

/**
 * Shared user facing wording for trial and subscription state.
 *
 * Prices are never hard coded: they come from Google's own `formattedPrice`, already localized to
 * the user's Play country and currency. ProductDetails may not have loaded yet (first launch, no
 * network), so every price bearing string has a price-less fallback.
 */
object SubscriptionCopy {

    /** Localized recurring price of the Standard plan, or null while unknown. */
    fun standardPrice(): String? =
        BillingManager.recurringPrice(BillingManager.PRODUCT_STANDARD)

    /** One-line status, used by the Options entry row. */
    fun statusSummary(context: Context): String = when {
        BillingManager.hasSubscription(context) ->
            context.getString(R.string.sub_status_standard)

        TrialManager.state(context) == TrialManager.State.ACTIVE ->
            daysLeftText(context)

        TrialManager.state(context) == TrialManager.State.NOT_STARTED ->
            context.getString(R.string.sub_trial_not_started_short)

        else -> context.getString(R.string.sub_trial_expired_short)
    }

    /**
     * "Free trial: N days left" as a plural resource: languages inflect the day count differently,
     * and English itself must not say "1 days left".
     */
    fun daysLeftText(context: Context): String {
        val days = TrialManager.daysLeft(context)
        return context.resources.getQuantityString(R.plurals.trial_days_left, days, days)
    }

    /** Body of the one-time "your trial has started" dialog. */
    fun trialStartedBody(context: Context): String {
        val price = standardPrice()
        return if (price != null) context.getString(R.string.trial_started_body, price)
        else context.getString(R.string.trial_started_body_no_price)
    }

    /** Body of the onboarding screen that explains the pricing model. */
    fun onboardingBody(context: Context): String {
        val price = standardPrice()
        return if (price != null) context.getString(R.string.onboarding_plan_body, price)
        else context.getString(R.string.onboarding_plan_body_no_price)
    }
}
