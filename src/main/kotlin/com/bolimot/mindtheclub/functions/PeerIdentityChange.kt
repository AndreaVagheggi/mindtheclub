package com.bolimot.mindtheclub.functions

// DESKTOP: the App's file, with one difference: acceptNewIdentity reads the peer's published key
// over REST (Backend.publishedPublicKey) instead of with the Firestore SDK. The rest, the policy
// included, is the App's: a changed key is never taken without the user's explicit accept.

import android.content.Context
import androidx.core.content.edit
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tracks contacts whose identity key changed, and carries the one explicit user decision that
 * resolves it.
 *
 * A key change is what a peer looks like after restoring a backup that carried no keyset, or
 * whose keyset could not be imported: their Firestore document serves a key whose fingerprint no
 * longer matches the one verified at pairing. Refused silently, the contact dies with no error
 * shown to either side, retrying for ever in the background (il caso Sofia).
 *
 * Policy, deliberately stricter than WhatsApp's silent auto accept: with no server to trust, the
 * only defence against someone rotating a key on a compromised Firestore document is the user's
 * eyes. A recorded change NEVER updates the stored key by itself: the chat surfaces a dialog, and
 * only the user's explicit accept calls [acceptNewIdentity].
 */
object PeerIdentityChange {

    private const val PREFS_NAME = "PeerIdentityChangePrefs"
    private const val KEY_PREFIX = "keychange_"
    private const val TAG = "PeerIdentityChange"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records that [peerId]'s published key now has [newFingerprint]. Idempotent. */
    fun record(context: Context, peerId: String, newFingerprint: String) {
        if (peerId.isEmpty() || newFingerprint.isEmpty()) return
        val key = "$KEY_PREFIX$peerId"
        if (prefs(context).getString(key, null) == newFingerprint) return
        prefs(context).edit { putString(key, newFingerprint) }
        debugLine(TAG, "Key change recorded for $peerId (new fingerprint $newFingerprint), awaiting user approval")
    }

    /** The pending new fingerprint for [peerId], or null when nothing is pending. */
    fun pending(context: Context, peerId: String): String? =
        prefs(context).getString("$KEY_PREFIX$peerId", null)

    fun clear(context: Context, peerId: String) {
        if (prefs(context).contains("$KEY_PREFIX$peerId")) {
            prefs(context).edit { remove("$KEY_PREFIX$peerId") }
        }
    }

    /**
     * The user accepted: fetch the peer's current key from Firestore, make sure it is still the
     * one that was shown (the same recorded fingerprint), and only then store it. If the key
     * rotated AGAIN in between, the recorded fingerprint is refreshed and the user confirms once
     * more.
     */
    suspend fun acceptNewIdentity(context: Context, peerId: String): Boolean {
        val expected = pending(context, peerId) ?: return false
        return try {
            // DESKTOP: the document read over REST, see the top of the file.
            val publicKey = withContext(Dispatchers.IO) { Backend.publishedPublicKey(peerId) }
            if (publicKey.isNullOrEmpty()) {
                debugLine(TAG, "Accept failed for $peerId: no key published")
                return false
            }
            val actual = KeyManager.fingerprintOf(publicKey)
            if (actual != expected) {
                debugLine(TAG, "Key for $peerId rotated again ($expected -> $actual), re-recording")
                record(context, peerId, actual)
                return false
            }
            getPeerDao(context).updatePeerPublicKey(peerId, publicKey)
            PeerIdentityResolver.markStale()
            clear(context, peerId)
            debugLine(TAG, "New identity accepted and stored for $peerId")
            true
        } catch (e: Exception) {
            debugLine(TAG, "Accept failed for $peerId: ${e.message}")
            false
        }
    }
}
