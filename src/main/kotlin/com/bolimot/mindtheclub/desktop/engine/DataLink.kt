package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.desktop.rtc.DataSession
import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.transport.PeerIdentityResolver
import com.bolimot.mindtheclub.webrtc.RTCClientWrapper
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The data connections of this PC, at most one per peer, used for both directions.
 *
 * Counterpart of ConnectionManager on the phone (webrtc/ConnectionManager.kt), for data only.
 * The rules carried over from it:
 *
 *   - ONE CONNECTION PER PEER, whoever opened it. A connection that is open is reused for
 *     sending, with no new dataCall (webRTCConnect: "I have already a usable RTCClient").
 *   - CONNECTING IS SERIALISED PER PEER. Several dispatches to one peer queue on one mutex; the
 *     first builds the connection, the others find it open.
 *   - THE LATEST dataCall FROM A PEER WINS. A phone that sends a new dataCall has given up on
 *     its previous attempt, so the session built for that attempt is closed.
 *   - A FAILED ATTEMPT OF OURS IS NOT REPEATED AT ONCE (CONNECT_COOLDOWN_MS, 15 s). It never
 *     gates an answer: a peer that calls us is reachable by definition.
 *
 * Every chunk that arrives goes to MessageProcessor, the App's own entry point for received
 * data (RTCClient.handleIncomingMessage does exactly this).
 */
object DataLink {

    private const val TAG = "DataLink"

    /** ConnectionManager.CONNECT_COOLDOWN_MS. */
    private const val CONNECT_COOLDOWN_MS = 15_000L

    private val sessions = ConcurrentHashMap<String, DataSession>()
    private val connectMutexes = ConcurrentHashMap<String, Mutex>()
    private val lastConnectFailure = ConcurrentHashMap<String, Long>()

    /** Peers that answered our dataCall with connectionBusy, by the channel they refused. */
    private val busyChannels = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var factory: PeerConnectionFactory? = null

    /** For display only: a line about a connection's progress. */
    @Volatile var onProgress: ((fromUserId: String, line: String) -> Unit)? = null

    /**
     * Loads the WebRTC library. Throws when it cannot start on this PC. The headless audio
     * module means no microphone or speaker is touched: only data channels are used.
     */
    @Synchronized
    fun start() {
        if (factory == null) factory = PeerConnectionFactory(HeadlessAudioDeviceModule())
    }

    /** A peer sent a dataCall for [channelId]: answer it. */
    suspend fun accept(channelId: String, fromUserId: String) {
        val current = sessions[fromUserId]
        if (current != null && current.channelId == channelId) return

        val session = newSession(channelId, fromUserId, initiator = false) ?: return
        val previous = sessions.put(fromUserId, session)
        previous?.close("sostituita da una chiamata piu' recente")
        session.start()
    }

    /**
     * A connection to send on: the one already open with this peer, or a new one.
     * Mirror of ConnectionManager.webRTCConnect plus RTCClientWrapper.initialize, initiator side.
     *
     * [senderContentKey] travels inside the dataCall, sealed, so the receiver can answer
     * "I already have this content" without opening a connection (the DATA_CALL gate).
     */
    suspend fun connect(remoteUserId: String, senderContentKey: String): RTCClientResult {
        val mutex = connectMutexes.computeIfAbsent(remoteUserId) { Mutex() }
        return mutex.withLock {
            val existing = sessions[remoteUserId]
            if (existing != null && existing.isOpen) {
                debugLine(TAG, "I have already a usable connection with $remoteUserId")
                return@withLock RTCClientResult.Success(RTCClientWrapper(existing))
            }

            val lastFail = lastConnectFailure[remoteUserId] ?: 0L
            if (System.currentTimeMillis() - lastFail < CONNECT_COOLDOWN_MS) {
                debugLine(TAG, "Skipping connection attempt, recent failure for $remoteUserId")
                return@withLock RTCClientResult.RTCClientGeneralFailure
            }

            val channelId = guid()
            val session = newSession(channelId, remoteUserId, initiator = true)
                ?: return@withLock RTCClientResult.RTCClientNotCreated

            // The wake up signal: a real time invitation, meant to expire if the peer is asleep.
            val extra = if (senderContentKey.isNotEmpty()) mapOf("contentKey" to senderContentKey) else emptyMap()
            if (!fcmSendInstant(remoteUserId, channelId, "NotACall", Notify.DATA_CALL, Notify.DATA_CALL, extra)) {
                debugLine(TAG, "Failed to send the dataCall, unable to wake up peer")
                lastConnectFailure[remoteUserId] = System.currentTimeMillis()
                return@withLock RTCClientResult.RTCClientNotCreated
            }
            debugLine(TAG, "Successfully sent wake up signal, channelId=$channelId")

            val previous = sessions.put(remoteUserId, session)
            previous?.close("sostituita da un nuovo tentativo di invio")
            session.start()

            val failure = session.awaitOpen()
            if (failure == null) {
                return@withLock RTCClientResult.Success(RTCClientWrapper(session))
            }

            lastConnectFailure[remoteUserId] = System.currentTimeMillis()
            if (busyChannels.remove(channelId)) {
                RTCClientResult.RTCClientBusy
            } else {
                debugLine(TAG, "Connection to $remoteUserId not opened: $failure")
                RTCClientResult.RTCClientGeneralFailure
            }
        }
    }

    /**
     * The peer answered our dataCall with connectionBusy (too many senders for that content):
     * stop waiting, the dispatch retries later with a longer backoff. [channelId] is the
     * channel it refused.
     */
    fun peerBusy(fromUserId: String, channelId: String) {
        val session = sessions[fromUserId] ?: return
        if (session.channelId != channelId) return
        busyChannels.add(channelId)
        session.close("il destinatario e' occupato")
    }

    private suspend fun newSession(channelId: String, remoteUserId: String, initiator: Boolean): DataSession? {
        val activeFactory = factory ?: run {
            debugLine(TAG, "WebRTC not started")
            return null
        }
        val myUserId = MySelf.userId() ?: return null
        val identity = KeyManager.identity() ?: return null

        val remoteKey = PeerIdentityResolver.publicKeyForUserId(remoteUserId)?.takeIf { it.isNotEmpty() }
            ?: recoverPeerPublicKey(remoteUserId)
        if (remoteKey.isNullOrEmpty()) {
            // Signalling never degrades to clear text (docs/arch/webrtc.md section 3).
            debugLine(TAG, "No public key for $remoteUserId, no connection possible")
            return null
        }

        return DataSession(
            channelId = channelId,
            myUserId = myUserId,
            remoteUserId = remoteUserId,
            remotePublicKey = remoteKey,
            identity = identity,
            factory = activeFactory,
            initiator = initiator,
            log = { line ->
                debugLine("DataSession", "[$remoteUserId] $line")
                progress(remoteUserId, line)
            },
            onChunk = { chunk -> MessageProcessor.enqueueMessage(remoteUserId, chunk) },
            onEnd = { reason ->
                sessions.computeIfPresent(remoteUserId) { _, live -> if (live.channelId == channelId) null else live }
                debugLine("DataSession", "[$remoteUserId] closed: $reason")
                progress(remoteUserId, "sessione chiusa: $reason")
            }
        )
    }

    private fun progress(userId: String, line: String) {
        try {
            onProgress?.invoke(userId, line)
        } catch (e: Exception) {
            // Display only.
        }
    }

    /**
     * CloudflareSignal.recoverPeerPublicKey: a contact with no key stored locally. The key is
     * read from the server and kept in the contact's row. As in the App this fills a gap and
     * verifies no fingerprint; a key that CHANGED never goes this way, the row already has one.
     */
    private suspend fun recoverPeerPublicKey(userId: String): String? {
        return try {
            val publicKey = Backend.publishedPublicKey(userId)
            if (!publicKey.isNullOrEmpty()) {
                getPeerDao(App.context()).updatePeerPublicKey(userId, publicKey)
                PeerIdentityResolver.markStale()
                debugLine(TAG, "Recovered publicKey for $userId")
                publicKey
            } else {
                debugLine(TAG, "No publicKey on the server for $userId yet")
                null
            }
        } catch (e: Exception) {
            debugLine(TAG, "recoverPeerPublicKey failed: ${e.message}")
            null
        }
    }

    fun closeAll() {
        for (session in sessions.values) session.close("chiusura del programma")
        sessions.clear()
    }
}
