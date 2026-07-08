package com.bolimot.mindtheclub.functions

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import androidx.core.net.toUri

/**
 * Deferred contact acquisition via the Play Install Referrer.
 *
 * When a friend who does NOT have MindTheClub installed taps an invite link, the
 * website (www.mindtheclub.com/add) forwards the inviter's profile parameters into
 * the Play Store URL as the `referrer` value:
 *
 *   https://play.google.com/store/apps/details?id=com.bolimot.mindtheclub&referrer=<...>
 *
 * After the app is installed, the Play Store hands that string back to us exactly
 * once, on first run. We parse the inviter's profile out of it and stash it, so that —
 * once onboarding is complete and the main screen opens — the normal
 * "add this contact?" flow can be triggered automatically (see AppTab).
 *
 * The referrer value is expected in one of two shapes:
 *   - the invite-link query form:  n=<name>&u=<userId>&b=<bio>&f=<fingerprint>
 *   - the raw payload form:        mtc;<name>;<userId>;<bio>;<fingerprint>
 * Either is normalised to the canonical "mtc;..." payload before stashing.
 *
 * Requires no dangerous permission and no server: the inviter's seed travels inside
 * the link itself. Works only for installs attributed through Google Play (the
 * intended "tap link -> Play Store -> install" flow); sideloaded installs carry no
 * referrer, in which case nothing happens.
 */

private const val PREF_REFERRER_CHECKED = "mtc_install_referrer_checked"
const val PREF_PENDING_INVITE_SEED = "mtc_pending_invite_seed"

/**
 * Reads the Play Install Referrer once and, if it carries an invite, stashes the
 * inviter's profile for later consumption. Self-guarded: safe to call on every
 * launch — it does real work only until it has succeeded once.
 */
fun captureInstallReferrerOnce(context: Context) {
    // The referrer is a one-shot, install-time signal; only ever look once.
    if (getPreference(PREF_REFERRER_CHECKED, context) == "true") return

    val appContext = context.applicationContext
    val client = InstallReferrerClient.newBuilder(appContext).build()

    client.startConnection(object : InstallReferrerStateListener {
        override fun onInstallReferrerSetupFinished(responseCode: Int) {
            try {
                when (responseCode) {
                    InstallReferrerClient.InstallReferrerResponse.OK -> {
                        val referrer = try {
                            client.installReferrer.installReferrer
                        } catch (e: Exception) {
                            debugLine("InstallReferrer", "Failed to read referrer: ${e.message}")
                            null
                        }

                        // OK is terminal regardless of content: never query again.
                        setPreference(PREF_REFERRER_CHECKED, "true", appContext)

                        val seed = parseInviteReferrer(referrer)
                        if (seed != null) {
                            setPreference(PREF_PENDING_INVITE_SEED, seed, appContext)
                            debugLine("InstallReferrer", "Stashed pending invite seed.")
                        } else {
                            debugLine("InstallReferrer", "No invite payload in referrer.")
                        }
                    }

                    InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                        // Permanently unavailable on this device/store: stop retrying.
                        setPreference(PREF_REFERRER_CHECKED, "true", appContext)
                        debugLine("InstallReferrer", "Feature not supported.")
                    }

                    else -> {
                        // SERVICE_UNAVAILABLE / DEVELOPER_ERROR: transient — leave the
                        // flag unset so a later launch retries.
                        debugLine("InstallReferrer", "Setup finished with code $responseCode; will retry.")
                    }
                }
            } finally {
                try { client.endConnection() } catch (_: Exception) {}
            }
        }

        override fun onInstallReferrerServiceDisconnected() {
            // No-op: a later launch re-attempts (flag stays unset on transient failures).
            debugLine("InstallReferrer", "Service disconnected.")
        }
    })
}

/**
 * Normalises a Play `referrer` value to the canonical
 * "mtc;name;userId;bio;fingerprint" payload, or returns null if it carries no
 * MindTheClub invite (e.g. an organic install's "utm_source=google-play...").
 */
fun parseInviteReferrer(referrer: String?): String? {
    if (referrer.isNullOrBlank()) return null

    // Raw payload form, possibly already complete.
    if (referrer.startsWith("mtc;")) {
        return if (parseQRCode(referrer) != null) referrer else null
    }

    // Invite-link query form: n=..&u=..&b=..&f=..
    val q = "https://x/?$referrer".toUri()
    val userId = q.getQueryParameter("u")
    val name = q.getQueryParameter("n")
    if (userId.isNullOrEmpty() || name.isNullOrEmpty()) return null

    val bio = q.getQueryParameter("b") ?: ""
    val fingerprint = q.getQueryParameter("f") ?: ""

    return "mtc;$name;$userId;$bio;$fingerprint"
}
