package com.bolimot.mindtheclub.crypto

// DESKTOP: this file is NOT a copy of the App's KeyManager.kt. Same object and the same public
// functions the engine's files call, so they compile unchanged; behind them the keyset comes
// from the Windows vault (desktop/storage/KeyVault.kt) instead of the Android Keystore, and
// the Tink work is done by desktop/crypto/IdentityKeys.kt.
//
// Not here, on purpose:
//   - ensureKeyPair: a PC never CREATES an identity, it receives one from the phone;
//   - exportIdentityKeyset / importIdentityKeyset: the handover in and out is BackupImporter's
//     job (and, later, the direct handover's);
//   - fetchAndStorePublicKeyVerified: contact acquisition is not on the PC yet.

import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.functions.debugLine

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
