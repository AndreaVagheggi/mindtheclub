package com.bolimot.mindtheclub.crypto

import android.content.Context
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.tasks.await

object KeyManager {

    private const val KEYSET_NAME = "mtc_hybrid_keyset"
    private const val PREF_FILE_NAME = "mtc_crypto_prefs"
    private const val MASTER_KEY_URI = "android-keystore://mtc_master_key"
    private const val KEY_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

    private val CONTEXT_INFO = "mtc-identity".toByteArray(Charsets.UTF_8)

    @Volatile
    private var registered = false

    private fun ensureRegistered() {
        if (!registered) {
            HybridConfig.register()
            registered = true
        }
    }

    suspend fun fetchAndStorePublicKeyVerified(
        userId: String,
        expectedFingerprint: String?,
        context: Context
    ): Boolean {
        return try {
            val doc = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(userId).get().await()
            val publicKey = doc.getString("publicKey")

            if (publicKey.isNullOrEmpty()) {
                debugLine("KeyManager", "No publicKey published yet for $userId.")
                return false
            }
            if (expectedFingerprint.isNullOrEmpty()) {
                debugLine("KeyManager", "REFUSING to store unverified key for $userId.")
                return false
            }
            if (fingerprintOf(publicKey) != expectedFingerprint) {
                // The caller's expectation (an old QR, a stale contact card) can lag behind an
                // identity the user has ALREADY accepted through the key change dialog. If the
                // key we hold for this peer is the very key Firestore serves, there is nothing
                // to verify or store: report success so retrying workers converge instead of
                // looping on a stale expectation for ever (il caso Sofia).
                val storedKey = com.bolimot.mindtheclub.functions.getPeerDao(context)
                    .getPeer(userId)?.publicKey
                if (!storedKey.isNullOrEmpty() && fingerprintOf(storedKey) == fingerprintOf(publicKey)) {
                    debugLine("KeyManager", "Expected fingerprint is stale but stored key matches Firestore for $userId, treating as verified.")
                    return true
                }
                // A mismatch against an EXISTING contact is the changed phone signal: mai
                // salvare in silenzio, record it so the chat surfaces the accept dialog.
                if (storedKey != null) {
                    com.bolimot.mindtheclub.functions.PeerIdentityChange
                        .record(context, userId, fingerprintOf(publicKey))
                }
                debugLine("KeyManager", "Fingerprint MISMATCH for $userId. Not storing.")
                return false
            }
            com.bolimot.mindtheclub.functions.getPeerDao(context)
                .updatePeerPublicKey(userId, publicKey)
            com.bolimot.mindtheclub.transport.PeerIdentityResolver.markStale()
            debugLine("KeyManager", "publicKey verified and stored for $userId.")
            true
        } catch (e: Exception) {
            debugLine("KeyManager", "fetchAndStorePublicKeyVerified failed for $userId: ${e.message}")
            false
        }
    }

    @Synchronized
    private fun keysetHandle(context: Context = App.context()): KeysetHandle {
        ensureRegistered()
        val deviceContext = context.createDeviceProtectedStorageContext()
        return AndroidKeysetManager.Builder()
            .withSharedPref(deviceContext, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
    }

    fun ensureKeyPair() {
        try {
            keysetHandle()
            debugLine("KeyManager", "Key pair ready")
        } catch (e: Exception) {
            debugLine("KeyManager", "ensureKeyPair failed: ${e.message}")
        }
    }

    fun getMyPublicKey(): String? {
        return try {
            val publicHandle = keysetHandle().publicKeysetHandle
            TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(publicHandle)
        } catch (e: Exception) {
            debugLine("KeyManager", "getMyPublicKey failed: ${e.message}")
            null
        }
    }

    /**
     * Serializes the full identity keyset, PRIVATE key included, for the app's password
     * encrypted backup. The one piece a plain data backup can never carry: the keyset at rest is
     * sealed by an Android Keystore master key that never leaves the device, so restoring the
     * database alone yields a phone that reads its old messages and owns a brand new identity
     * (visto di persona il 7 Aug). InsecureSecretKeyAccess is Tink's official escape hatch for
     * exactly this; the caller MUST only ever put the result inside the AES-GCM backup envelope,
     * never on disk in the clear.
     */
    @Synchronized
    fun exportIdentityKeyset(): String? {
        return try {
            ensureRegistered()
            TinkJsonProtoKeysetFormat.serializeKeyset(
                keysetHandle(),
                com.google.crypto.tink.InsecureSecretKeyAccess.get()
            )
        } catch (e: Exception) {
            debugLine("KeyManager", "exportIdentityKeyset failed: ${e.message}")
            null
        }
    }

    /**
     * Replaces this device's identity with the keyset carried by a restored backup, so the user
     * keeps being who they were on the old phone.
     *
     * The keyset is re-sealed with THIS device's Keystore master key and written into the exact
     * SharedPreferences slot AndroidKeysetManager reads, in the hex-of-EncryptedKeyset format
     * its reader expects (serializeEncryptedKeyset with empty associated data is the documented
     * equivalent of the legacy writer). The next keysetHandle() picks it up.
     *
     * Returns true only after the round trip verifies: the public key now derived on this
     * device must match the imported keyset's.
     */
    @Synchronized
    fun importIdentityKeyset(keysetJson: String, context: Context = App.context()): Boolean {
        return try {
            ensureRegistered()
            val handle = TinkJsonProtoKeysetFormat.parseKeyset(
                keysetJson,
                com.google.crypto.tink.InsecureSecretKeyAccess.get()
            )
            val expectedPublic = TinkJsonProtoKeysetFormat
                .serializeKeysetWithoutSecret(handle.publicKeysetHandle)

            // Touching the current keyset first guarantees the Keystore master key exists
            // before we ask for its Aead.
            keysetHandle(context)
            val masterAead = com.google.crypto.tink.integration.android
                .AndroidKeystoreKmsClient().getAead(MASTER_KEY_URI)

            val encrypted = com.google.crypto.tink.TinkProtoKeysetFormat
                .serializeEncryptedKeyset(handle, masterAead, ByteArray(0))

            // MUST be applicationContext, not the device protected context keysetHandle() uses:
            // AndroidKeysetManager resolves the prefs file through getApplicationContext()
            // internally, and calling that on a device protected context yields the Application,
            // cioe' CREDENTIAL protected storage. Writing to the device protected file put the
            // imported keyset where Tink never looks, so the import silently did nothing and the
            // phone kept its own identity (the 8 Aug transfer failure: every signal from the
            // peer then failed to decrypt). Same file, same lowercase hex-of-EncryptedKeyset as
            // SharedPrefKeysetWriter.
            val written = context.applicationContext
                .getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEYSET_NAME, toHex(encrypted))
                .commit()
            if (!written) {
                debugLine("KeyManager", "importIdentityKeyset: prefs write failed")
                return false
            }

            val roundTrip = getMyPublicKey()
            val ok = roundTrip != null && fingerprintOf(roundTrip) == fingerprintOf(expectedPublic)
            debugLine(
                "KeyManager",
                if (ok) "Identity keyset imported, fingerprint ${fingerprintOf(expectedPublic)}"
                else "importIdentityKeyset round trip FAILED"
            )
            ok
        } catch (e: Exception) {
            debugLine("KeyManager", "importIdentityKeyset failed: ${e.message}")
            false
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun fingerprintOf(publicKey: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(publicKey.toByteArray(Charsets.UTF_8))
        val truncated = digest.copyOfRange(0, 16)
        return android.util.Base64.encodeToString(
            truncated,
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
        )
    }

    fun getMyPublicKeyFingerprint(): String? {
        val key = getMyPublicKey() ?: return null
        return fingerprintOf(key)
    }

    fun encryptFor(recipientPublicKey: String, plaintext: String): String? {
        return try {
            ensureRegistered()
            val publicHandle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(recipientPublicKey)
            val hybrid = publicHandle.getPrimitive(
                com.google.crypto.tink.RegistryConfiguration.get(),
                com.google.crypto.tink.HybridEncrypt::class.java
            )
            val ciphertext = hybrid.encrypt(plaintext.toByteArray(Charsets.UTF_8), CONTEXT_INFO)
            android.util.Base64.encodeToString(
                ciphertext,
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            )
        } catch (e: Exception) {
            debugLine("KeyManager", "encryptFor failed: ${e.message}")
            null
        }
    }

    fun decrypt(ciphertextB64: String): String? {
        return try {
            ensureRegistered()
            val ciphertext = android.util.Base64.decode(
                ciphertextB64,
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            )
            val hybrid = keysetHandle().getPrimitive(
                com.google.crypto.tink.RegistryConfiguration.get(),
                com.google.crypto.tink.HybridDecrypt::class.java
            )
            val plaintext = hybrid.decrypt(ciphertext, CONTEXT_INFO)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            debugLine("KeyManager", "decrypt failed: ${e.message}")
            null
        }
    }
}
