package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.net.toUri

/**
 * Deferred contact acquisition via the Play Install Referrer.
 *
 * When a friend who does NOT have MindTheClub installed taps an invite link, the website
 * (www.mindtheclub.com/add) forwards the inviter's profile parameters into the Play Store URL
 * as the `referrer` value:
 *
 *   https://play.google.com/store/apps/details?id=com.bolimot.mindtheclub&referrer=<...>
 *
 * After the install the Play Store hands that string back exactly once, on first run. We parse
 * the inviter's profile out of it and stash it, so that once onboarding is complete and the
 * main screen opens the normal "add this contact?" flow can fire (see AppTab).
 *
 * The value comes in one of two shapes:
 *   - the invite-link query form:  n=<name>&u=<userId>&b=<bio>&f=<fingerprint>
 *   - the raw payload form:        mtc;<name>;<userId>;<bio>;<fingerprint>
 * Either is normalised to the canonical "mtc;..." payload before stashing.
 *
 * No dangerous permission and no server: the inviter's seed travels inside the link. Only for
 * installs attributed through Google Play; a sideloaded install carries no referrer and nothing
 * happens.
 */

const val PREF_PENDING_INVITE_SEED = "mtc_pending_invite_seed"

/**
 * mtcx: nothing to read. This build is never installed through the Play Store, so there is no
 * Play Install Referrer to carry an invite across the install; the library is not included.
 * Kept with the same name so MainActivity calls it exactly as the Play build does.
 */
@Suppress("UNUSED_PARAMETER")
fun captureInstallReferrerOnce(context: Context) = Unit

/**
 * Normalises a Play `referrer` value to the canonical "mtc;name;userId;bio;fingerprint"
 * payload, or null when it carries no MindTheClub invite (an organic install's
 * "utm_source=google-play...").
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
