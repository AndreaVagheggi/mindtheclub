package com.bolimot.mindtheclub.desktop.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The receiving half of Web Push message encryption (RFC 8291, content coding aes128gcm).
 *
 * On a phone this is done by the UnifiedPush connector; a PC has no connector, so it is done
 * here. The sending half is the web-push library inside the sendUnifiedPush Cloud Function
 * (C:\mtcShared\functions\src\sendUnifiedPush.js), which encrypts to the two values this
 * class publishes:
 *
 *   p256dh   our P-256 public key, uncompressed point, 65 bytes
 *   auth     16 random bytes
 *
 * This layer only hides the message from the push server in the middle. What is inside is
 * still sealed to the identity key (sealed sender), and is opened by IdentityKeys.
 *
 * JDK classes only. Checked against the server's own library: see selfTest.
 */
class WebPushKeys private constructor(
    private val privateKey: PrivateKey,
    private val publicPoint: ByteArray,
    private val authSecret: ByteArray
) {

    /** base64url without padding, the form updateMyPushEndpoint validates. */
    val p256dh: String = B64.encodeToString(publicPoint)
    val auth: String = B64.encodeToString(authSecret)

    /**
     * Opens one push message body. Throws when the body is malformed or was not encrypted to
     * these keys.
     *
     * Body layout (RFC 8188 section 2.1):
     *   salt (16) || record size (4) || key id length (1) || key id || ciphertext
     * where for Web Push the key id is the sender's ephemeral public key, 65 bytes.
     */
    fun open(body: ByteArray): ByteArray {
        require(body.size > HEADER_FIXED) { "body too short" }

        val salt = body.copyOfRange(0, 16)
        val recordSize = ((body[16].toInt() and 0xff) shl 24) or
                ((body[17].toInt() and 0xff) shl 16) or
                ((body[18].toInt() and 0xff) shl 8) or
                (body[19].toInt() and 0xff)
        val keyIdLength = body[20].toInt() and 0xff
        require(keyIdLength == POINT_LENGTH) { "unexpected key id length $keyIdLength" }
        require(body.size > HEADER_FIXED + keyIdLength) { "no ciphertext" }

        val senderPoint = body.copyOfRange(HEADER_FIXED, HEADER_FIXED + keyIdLength)
        val ciphertext = body.copyOfRange(HEADER_FIXED + keyIdLength, body.size)
        // A push message is at most 4096 bytes and the sender uses a 4096 byte record, so it
        // is always ONE record. Anything else is not something sendUnifiedPush produces.
        require(ciphertext.size <= recordSize) { "multi record body not supported" }

        // RFC 8291 section 3.3 and 3.4.
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodePoint(senderPoint), true)
        val ecdhSecret = agreement.generateSecret()

        val prkKey = hmac(authSecret, ecdhSecret)
        val keyInfo = "WebPush: info\u0000".toByteArray(Charsets.US_ASCII) + publicPoint + senderPoint
        val ikm = hmac(prkKey, keyInfo + ONE)

        // RFC 8188 section 2.2 and 2.3.
        val prk = hmac(salt, ikm)
        val contentKey = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.US_ASCII) + ONE)
            .copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII) + ONE)
            .copyOf(12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, nonce))
        val padded = cipher.doFinal(ciphertext)

        // The record ends with a delimiter byte, 0x02 for the last record, then optional zeros.
        var end = padded.size - 1
        while (end >= 0 && padded[end] == 0.toByte()) end--
        require(end >= 0 && padded[end] == LAST_RECORD) { "missing record delimiter" }
        return padded.copyOfRange(0, end)
    }

    companion object {

        private const val HEADER_FIXED = 21
        private const val POINT_LENGTH = 65
        private const val LAST_RECORD: Byte = 2
        private val ONE = byteArrayOf(1)
        private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        private val curve: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        }

        /** A fresh key pair and auth secret, for a new push registration. */
        fun generate(): WebPushKeys {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = generator.generateKeyPair()
            val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return WebPushKeys(pair.private, encodePoint(pair.public as ECPublicKey), auth)
        }

        /**
         * Decrypts a message produced on 11 Nov 2026 by the web-push library the Cloud Function
         * uses (webpush.encrypt, aes128gcm) for the fixed keys below. It proves that this class
         * and the server agree, on this PC and with this Java, before anything is registered.
         */
        fun selfTest(): Boolean {
            return try {
                val decoder = Base64.getUrlDecoder()
                val scalar = decoder.decode("9PXhbNwpF69VOZrKGy8mdXH9TeLzTCD9uxnmBL6-AXk")
                val point = decoder.decode(
                    "BFtvtMxfuDK80VacWOcXsvZ0DIvuFd9pfVxm2DC_pZGFfUIkvMQS0xx27wzKQQ-w5W1Lpr23yKAD6Csx3E7n6wE"
                )
                val auth = decoder.decode("5miQlMpvK1UG_P_-W95KDQ")
                val body = decoder.decode(
                    "COpOPvvjfy0iMn3luyvfFQAAEABBBMMh6KaQmlxz00jOUDKY1-3ASwNna3MRZRZVC5LADoi-IyDbHFrDrwuCSQzSPFPH" +
                            "GFytkuMhAaNHAKt0fQ5uCzr2XQiB52T0p1nePd-Yk6fZxvXeK4_VN0c7wzD9_dHkWl9eufR6B7DRffT5gHL7EH80" +
                            "4jMLefotZFSHMg2cCGa1X740M_SmGg"
                )
                val privateKey = KeyFactory.getInstance("EC")
                    .generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), curve))
                val clear = WebPushKeys(privateKey, point, auth).open(body)
                String(clear, Charsets.UTF_8) == "toUserId=abc;enc=1;payload=xyz-123;upSentAt=1;upTtl=15"
            } catch (e: Exception) {
                false
            }
        }

        /** 0x04 || X (32) || Y (32). */
        private fun encodePoint(key: ECPublicKey): ByteArray =
            byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

        private fun decodePoint(point: ByteArray): java.security.PublicKey {
            require(point.size == POINT_LENGTH && point[0] == 4.toByte()) { "not an uncompressed P-256 point" }
            val x = BigInteger(1, point.copyOfRange(1, 33))
            val y = BigInteger(1, point.copyOfRange(33, 65))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve))
        }

        /** BigInteger.toByteArray is 31, 32 or 33 bytes long depending on the value. */
        private fun fixed32(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            val out = ByteArray(32)
            val n = minOf(raw.size, 32)
            System.arraycopy(raw, raw.size - n, out, 32 - n, n)
            return out
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}
