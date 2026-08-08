package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.content.edit
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Tracks contacts whose identity key changed, and carries the one explicit
 * user decision that resolves it.
 *
 * A key change is what a peer looks like after reinstalling the app or moving
 * to a new phone without restoring a backup: their Firestore document serves a
 * key whose fingerprint no longer matches the one verified at pairing. Until
 * now the app refused the new key silently (Fingerprint MISMATCH, not storing)
 * and the contact died with no error shown to either side, retrying for ever
 * in the background (the Sofia case).
 *
 * Policy, deliberately stricter than WhatsApp's silent auto accept: with no
 * server to trust, the only defence against someone rotating a key on a
 * compromised Firestore document is the user's eyes. A recorded change
 * therefore NEVER updates the stored key by itself: the chat surfaces a
 * dialog, and only the user's explicit accept calls [acceptNewIdentity].
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
     * The user accepted: fetch the peer's current key from Firestore, make sure
     * it is still the one that was shown to the user (same fingerprint that was
     * recorded), and only then store it. If the key rotated AGAIN in between,
     * the recorded fingerprint is refreshed and the user must confirm once more.
     */
    suspend fun acceptNewIdentity(context: Context, peerId: String): Boolean {
        val expected = pending(context, peerId) ?: return false
        return try {
            val doc = FirebaseFirestore.getInstance()
                .collection("users").document(peerId).get().await()
            val publicKey = doc.getString("publicKey")
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
