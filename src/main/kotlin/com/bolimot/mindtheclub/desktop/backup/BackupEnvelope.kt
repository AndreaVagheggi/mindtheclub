package com.bolimot.mindtheclub.desktop.backup

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens the password encrypted envelope of a .mtcbackup file.
 *
 * Mirror of BackupManager.decrypt and deriveKey in the Android app
 * (C:\mtc, backup/BackupManager.kt:412-437). The layout is
 *
 *   salt (16) || iv (12) || AES-256-GCM(ciphertext || tag)
 *
 * with the key derived by PBKDF2WithHmacSHA256, 200 000 iterations. Every constant here
 * must stay equal to the app's: a different value does not fail loudly, it just never opens
 * a backup.
 */
object BackupEnvelope {

    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_LENGTH = 256

    /** The clear bytes, or null when the password is wrong or the file is not a backup. */
    fun open(data: ByteArray, password: CharArray): ByteArray? {
        return try {
            if (data.size < SALT_LENGTH + IV_LENGTH + 1) return null

            val salt = data.copyOfRange(0, SALT_LENGTH)
            val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Seals clear bytes into the envelope the phone opens: mirror of BackupManager.encrypt
     * (C:\mtc, backup/BackupManager.kt:398-410). A fresh random salt and iv every time.
     */
    fun seal(data: ByteArray, password: CharArray): ByteArray {
        val random = java.security.SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return salt + iv + cipher.doFinal(data)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}
