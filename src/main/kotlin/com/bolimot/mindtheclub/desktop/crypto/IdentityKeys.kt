package com.bolimot.mindtheclub.desktop.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import java.security.MessageDigest
import java.util.Base64

/**
 * The identity key pair on the PC, held in memory only.
 *
 * Mirror of the parts of KeyManager in the Android app (C:\mtc, crypto/KeyManager.kt) that
 * do not depend on Android: same Tink calls, same context info, same fingerprint, same
 * base64 flavour. What is NOT here yet is storage: on the phone the keyset rests sealed by
 * an Android Keystore master key, on Windows it will rest sealed by the user's Windows
 * account. Until that exists nothing in this project writes a keyset to disk.
 */
class IdentityKeys private constructor(private val handle: KeysetHandle) {

    /**
     * The public keyset JSON, exactly what the phone publishes in users/{id}.publicKey.
     *
     * "Exactly" includes the last character. Tink ends the JSON with the platform's line
     * separator: "\n" on Android, "\r\n" on Windows. A fingerprint is the SHA-256 of this
     * TEXT (fingerprintOf), so the one extra carriage return gave the same key a different
     * fingerprint (found 11 Nov 2026: phone YpMrqFeh..., PC armO9BGA..., key identical).
     * Published like that, every contact would have seen a key change dialog for a key that
     * had not changed. The Android form is the canonical one, so it is forced here, in the
     * only place this project serialises a public key. Never serialise one anywhere else.
     */
    val publicKey: String =
        TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle)
            .replace("\r\n", "\n")

    val fingerprint: String = fingerprintOf(publicKey)

    /** KeyManager.decrypt. Throws when the ciphertext was not sealed to this identity. */
    fun decrypt(ciphertextB64: String): String {
        val ciphertext = Base64.getUrlDecoder().decode(ciphertextB64)
        val hybrid = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        return String(hybrid.decrypt(ciphertext, CONTEXT_INFO), Charsets.UTF_8)
    }

    companion object {

        private val CONTEXT_INFO = "mtc-identity".toByteArray(Charsets.UTF_8)

        @Volatile
        private var registered = false

        private fun ensureRegistered() {
            if (!registered) {
                HybridConfig.register()
                registered = true
            }
        }

        /** From BackupData.identityKeyset, the full keyset JSON with the private key. */
        fun fromBackupKeyset(keysetJson: String): IdentityKeys {
            ensureRegistered()
            return IdentityKeys(
                TinkJsonProtoKeysetFormat.parseKeyset(keysetJson, InsecureSecretKeyAccess.get())
            )
        }

        /**
         * KeyManager.fingerprintOf: the first 16 bytes of SHA-256 over the public keyset's
         * JSON text, URL safe base64 without padding.
         */
        fun fingerprintOf(publicKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(publicKey.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOfRange(0, 16))
        }

        /** KeyManager.encryptFor: URL safe base64, padding kept. */
        fun encryptFor(recipientPublicKey: String, plaintext: String): String {
            ensureRegistered()
            val publicHandle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(recipientPublicKey)
            val hybrid = publicHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
            val ciphertext = hybrid.encrypt(plaintext.toByteArray(Charsets.UTF_8), CONTEXT_INFO)
            return Base64.getUrlEncoder().encodeToString(ciphertext)
        }
    }
}
