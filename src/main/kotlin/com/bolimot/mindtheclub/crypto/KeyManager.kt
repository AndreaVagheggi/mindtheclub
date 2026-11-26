package com.bolimot.mindtheclub.crypto

// DESKTOP: this file is NOT a copy of the App's KeyManager.kt. Same object and the same public
// functions the engine's files call, so they compile unchanged; behind them the keyset comes
// from the Windows vault (desktop/storage/KeyVault.kt) instead of the Android Keystore, and
// the Tink work is done by desktop/crypto/IdentityKeys.kt.
//
// Not here, on purpose:
//   - ensureKeyPair: a PC never CREATES an identity, it receives one from the phone;
//   - exportIdentityKeyset / importIdentityKeyset: the handover in and out is BackupImporter's
//     job (and, later, the direct handover's).
//
// fetchAndStorePublicKeyVerified is the App's, with the document read over REST (Backend).

import android.content.Context
import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.functions.PeerIdentityChange
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KeyManager {

    @Volatile
    private var loaded: IdentityKeys? = null

    /**
     * The identity of this PC, opened from the vault on first use and kept in memory.
     * Null when the PC holds no identity or Windows refuses to open it.
     */
    @Synchronized
    fun identity(): IdentityKeys? {
        loaded?.let { return it }
        return try {
            val keyset = KeyVault.load() ?: return null
            IdentityKeys.fromBackupKeyset(keyset).also { loaded = it }
        } catch (e: Throwable) {
            debugLine("KeyManager", "identity not available: ${e.message}")
            null
        }
    }

    /** After a load that replaced the identity: the next use reads the vault again. */
    @Synchronized
    fun forget() {
        loaded = null
    }

    /**
     * The App's fetchAndStorePublicKeyVerified: reads the public key [userId] published, and
     * stores it in that contact's row only if its fingerprint is [expectedFingerprint], the one
     * a QR, a shared contact card or a sealed contact request vouches for. Never stores a key
     * that nothing vouches for.
     */
    suspend fun fetchAndStorePublicKeyVerified(
        userId: String,
        expectedFingerprint: String?,
        context: Context
    ): Boolean {
        return try {
            val publicKey = withContext(Dispatchers.IO) { Backend.publishedPublicKey(userId) }

            if (publicKey.isNullOrEmpty()) {
                debugLine("KeyManager", "No publicKey published yet for $userId.")
                return false
            }
            if (expectedFingerprint.isNullOrEmpty()) {
                debugLine("KeyManager", "REFUSING to store unverified key for $userId.")
                return false
            }
            if (fingerprintOf(publicKey) != expectedFingerprint) {
                // The caller's expectation can lag behind an identity the user has ALREADY
                // accepted: if the key we hold is the very key the server serves, there is
                // nothing to verify or store (see the App's file, il caso Sofia).
                val storedKey = getPeerDao(context).getPeer(userId)?.publicKey
                if (!storedKey.isNullOrEmpty() && fingerprintOf(storedKey) == fingerprintOf(publicKey)) {
                    debugLine("KeyManager", "Expected fingerprint is stale but stored key matches Firestore for $userId, treating as verified.")
                    return true
                }
                // A mismatch against an EXISTING contact is the changed phone signal: never
                // stored in silence, recorded so the chat offers the key change dialog.
                if (storedKey != null) {
                    PeerIdentityChange.record(context, userId, fingerprintOf(publicKey))
                }
                debugLine("KeyManager", "Fingerprint MISMATCH for $userId. Not storing.")
                return false
            }
            getPeerDao(context).updatePeerPublicKey(userId, publicKey)
            PeerIdentityResolver.markStale()
            debugLine("KeyManager", "publicKey verified and stored for $userId.")
            true
        } catch (e: Exception) {
            debugLine("KeyManager", "fetchAndStorePublicKeyVerified failed for $userId: ${e.message}")
            false
        }
    }

    fun getMyPublicKey(): String? = identity()?.publicKey

    fun fingerprintOf(publicKey: String): String = IdentityKeys.fingerprintOf(publicKey)

    fun getMyPublicKeyFingerprint(): String? = identity()?.fingerprint

    fun encryptFor(recipientPublicKey: String, plaintext: String): String? {
        return try {
            IdentityKeys.encryptFor(recipientPublicKey, plaintext)
        } catch (e: Exception) {
            debugLine("KeyManager", "encryptFor failed: ${e.message}")
            null
        }
    }

    fun decrypt(ciphertextB64: String): String? {
        return try {
            identity()?.decrypt(ciphertextB64)
        } catch (e: Exception) {
            debugLine("KeyManager", "decrypt failed: ${e.message}")
            null
        }
    }
}
