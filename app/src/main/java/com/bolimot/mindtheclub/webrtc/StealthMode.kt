package com.bolimot.mindtheclub.webrtc

import android.content.Context
import com.bolimot.mindtheclub.billing.BillingManager
import com.bolimot.mindtheclub.functions.getPreference

/**
 * Single source of truth for whether connections must be relay-only.
 *
 * The user's toggle ([RTCClient.PREF_STEALTH_MODE]) and the paid Stealth
 * entitlement are checked together, so a lapsed subscription silently stops
 * burning TURN traffic even if the preference is still set.
 */
object StealthMode {

    fun isToggledOn(context: Context): Boolean =
        getPreference(RTCClient.PREF_STEALTH_MODE, context) == "true"

    fun isActive(context: Context): Boolean =
        isToggledOn(context) && BillingManager.hasStealthEntitlement(context)
}
