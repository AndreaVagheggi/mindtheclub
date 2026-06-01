package com.bolimot.mindtheclub.transport

import android.bluetooth.BluetoothSocket
import android.content.Context
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object BluetoothFraming {
    private const val MAX_FRAME = 2 * 1024 * 1024 // bound against garbage/oversized lengths

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        val len = payload.size
        out.write(
            byteArrayOf(
                (len ushr 24).toByte(),
                (len ushr 16).toByte(),
                (len ushr 8).toByte(),
                len.toByte()
            )
        )
        out.write(payload)
        out.flush()
    }

    fun readFrame(input: InputStream): ByteArray? {
        val header = readExactly(input, 4) ?: return null
        val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
        if (len <= 0 || len > MAX_FRAME) return null
        return readExactly(input, len)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null // EOF
            off += r
        }
        return buf
    }
}

class BluetoothTransport private constructor(
    private val socket: BluetoothSocket,
    private val output: OutputStream,
    private val recipientPublicKey: String
) : Transport {

    override suspend fun sendData(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val plaintext = String(data, Charsets.UTF_8)
            val ciphertextB64 = KeyManager.encryptFor(recipientPublicKey, plaintext) ?: run {
                debugLine("BluetoothTransport", "encryptFor returned null")
                return@withContext false
            }
            BluetoothFraming.writeFrame(output, ciphertextB64.toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            debugLine("BluetoothTransport", "sendData failed: ${e.message}")
            false
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    suspend fun sendCompletedAndAwaitReply(messageId: String, remoteUserId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val plaintext = BluetoothControl.completedFrame(messageId)
                val ciphertextB64 = KeyManager.encryptFor(recipientPublicKey, plaintext)
                    ?: return@withContext false
                BluetoothFraming.writeFrame(output, ciphertextB64.toByteArray(Charsets.UTF_8))

                val input = socket.inputStream
                val replyFrame = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                    BluetoothFraming.readFrame(input)
                } ?: run {
                    debugLine("BluetoothTransport", "No control reply within timeout for $messageId")
                    return@withContext false
                }

                val replyPlain = KeyManager.decrypt(String(replyFrame, Charsets.UTF_8))
                    ?: return@withContext false
                BluetoothControl.handleReply(replyPlain, remoteUserId)
                replyPlain.startsWith(com.bolimot.mindtheclub.tools.Notify.ALL_RECEIVED)
            } catch (e: Exception) {
                debugLine("BluetoothTransport", "sendCompletedAndAwaitReply failed: ${e.message}")
                false
            }
        }

    companion object {
        const val MTC_RFCOMM_NAME = "MindTheClub"
        val MTC_RFCOMM_UUID: UUID = UUID.fromString("9a3f2c7e-5b14-4f8a-bd92-1e6c0a7d4f33")
        private const val REPLY_TIMEOUT_MS = 20_000L
        private const val PROBE_HANDSHAKE_TIMEOUT_MS = 4_000L

        @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        suspend fun probeAndGetRemoteUserId(device: android.bluetooth.BluetoothDevice): String? =
            withContext(Dispatchers.IO) {
                val myFingerprint = PeerIdentityResolver.myFingerprint() ?: return@withContext null
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createInsecureRfcommSocketToServiceRecord(MTC_RFCOMM_UUID)
                    socket.connect()
                    val output = socket.outputStream
                    BluetoothFraming.writeFrame(output, myFingerprint.toByteArray(Charsets.UTF_8))

                    val replyFrame = withTimeoutOrNull(PROBE_HANDSHAKE_TIMEOUT_MS) {
                        BluetoothFraming.readFrame(socket.inputStream)
                    } ?: return@withContext null

                    val remoteFingerprint = String(replyFrame, Charsets.UTF_8)
                    PeerIdentityResolver.userIdForFingerprint(remoteFingerprint)
                        ?: PeerIdentityResolver.userIdForFingerprint(remoteFingerprint, forceRefresh = true)
                } catch (e: SecurityException) {
                    debugLine("BluetoothTransport", "probe SecurityException for ${runCatching { device.address }.getOrNull()}: ${e.message}")
                    null
                } catch (e: Exception) {
                    debugLine("BluetoothTransport", "probe failed for ${runCatching { device.address }.getOrNull()}: ${e.message}")
                    null
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {
                    }
                }
            }

        suspend fun open(userId: String, context: Context = App.context()): BluetoothTransport? =
            withContext(Dispatchers.IO) {
                val peer = com.bolimot.mindtheclub.functions.getPeerDao(context).getPeer(userId)
                val mac = peer?.bluetoothMac ?: return@withContext null
                val device = BluetoothPresence.deviceForMac(mac, context) ?: return@withContext null
                val recipientPublicKey = peer.publicKey ?: run {
                    debugLine("BluetoothTransport", "No public key for $userId")
                    return@withContext null
                }
                val myFingerprint = PeerIdentityResolver.myFingerprint() ?: return@withContext null

                try {
                    val socket = device.createInsecureRfcommSocketToServiceRecord(MTC_RFCOMM_UUID)
                    socket.connect()
                    val out = socket.outputStream
                    BluetoothFraming.writeFrame(out, myFingerprint.toByteArray(Charsets.UTF_8))
                    BluetoothFraming.readFrame(socket.inputStream)
                    BluetoothTransport(socket, out, recipientPublicKey)
                } catch (e: SecurityException) {
                    debugLine("BluetoothTransport", "open SecurityException: ${e.message}")
                    null
                } catch (e: Exception) {
                    debugLine("BluetoothTransport", "open failed: ${e.message}")
                    null
                }
            }
    }
}
