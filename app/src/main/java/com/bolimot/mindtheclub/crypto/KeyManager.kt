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
