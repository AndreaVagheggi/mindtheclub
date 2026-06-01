package com.bolimot.mindtheclub.transport

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object BluetoothServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null

    fun start(context: Context = App.context()) {
        if (running) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return
        if (!adapter.isEnabled) return

        running = true
        acceptJob = scope.launch {
            try {
                val ss = adapter.listenUsingInsecureRfcommWithServiceRecord(
                    BluetoothTransport.MTC_RFCOMM_NAME,
                    BluetoothTransport.MTC_RFCOMM_UUID
                )
                serverSocket = ss
                debugLine("BluetoothServer", "Listening for RFCOMM connections")
                while (running) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) debugLine("BluetoothServer", "accept ended: ${e.message}")
                        break
                    }
                    handleClient(socket)
                }
            } catch (e: SecurityException) {
                debugLine("BluetoothServer", "listen SecurityException: ${e.message}")
            } catch (e: Exception) {
                debugLine("BluetoothServer", "listen failed: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        debugLine("BluetoothServer", "Stopped")
    }

    private fun handleClient(socket: BluetoothSocket) {
        scope.launch {
            try {
                val input = socket.inputStream

                val handshake = BluetoothFraming.readFrame(input) ?: return@launch
                val fingerprint = String(handshake, Charsets.UTF_8)
                val senderUserId = PeerIdentityResolver.userIdForFingerprint(fingerprint)
                    ?: PeerIdentityResolver.userIdForFingerprint(fingerprint, forceRefresh = true)
                if (senderUserId == null) {
                    debugLine("BluetoothServer", "Unknown sender fingerprint, dropping connection")
                    return@launch
                }
                debugLine("BluetoothServer", "Receiving from $senderUserId")

                val senderPublicKey = PeerIdentityResolver.publicKeyForUserId(senderUserId)
                val output = socket.outputStream

                val myFingerprint = PeerIdentityResolver.myFingerprint()
                if (myFingerprint != null) {
                    BluetoothFraming.writeFrame(output, myFingerprint.toByteArray(Charsets.UTF_8))
                }

                while (true) {
                    val frame = BluetoothFraming.readFrame(input) ?: break
                    val plaintext = KeyManager.decrypt(String(frame, Charsets.UTF_8))
                    if (plaintext == null) {
                        debugLine("BluetoothServer", "Decrypt failed, skipping frame")
                        continue
                    }

                    if (plaintext.startsWith("${com.bolimot.mindtheclub.tools.Notify.COMPLETED} ")) {
                        val messageId = plaintext.substringAfter(" ").trim()
                        val reply = BluetoothControl.handleCompletedAndBuildReply(messageId)
                        if (senderPublicKey != null) {
                            val replyCipher = KeyManager.encryptFor(senderPublicKey, reply)
                            if (replyCipher != null) {
                                BluetoothFraming.writeFrame(output, replyCipher.toByteArray(Charsets.UTF_8))
                            }
                        } else {
                            debugLine("BluetoothServer", "No public key for $senderUserId; cannot reply")
                        }
                    } else {
                        MessageProcessor.enqueueMessage(senderUserId, plaintext)
                    }
                }
            } catch (e: Exception) {
                debugLine("BluetoothServer", "Client handler error: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }
}
