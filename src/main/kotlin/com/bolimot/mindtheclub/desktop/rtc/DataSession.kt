package com.bolimot.mindtheclub.desktop.rtc

import com.bolimot.mindtheclub.desktop.backend.Relay
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtcpMuxPolicy
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One data connection with one peer: the PC's counterpart of the phone's webrtc/RTCClient.kt
 * in data-only mode. It works in both roles, as RTCClient does.
 *
 *   ANSWERING (initiator = false). The peer sent a dataCall carrying a channelId. This joins
 *   that signalling room, waits for the peer's offer, answers it, and receives the data channel
 *   the peer created.
 *
 *   CALLING (initiator = true). We sent the dataCall. This creates the data channel
 *   ("dataChannel", unordered, as the phone does), joins the room, and sends the offer when the
 *   relay reports the peer present.
 *
 * Either way the channel then carries chunks in both directions: one message each, a JSON
 * text. Whoever opened the connection, both sides can send on it, which is how a phone that
 * finds a connection already open sends without a new dataCall.
 *
 * NO ACKNOWLEDGEMENT. sendData returns true when the write into the channel's buffer
 * succeeded, and that is all it means. Holes are expected and the recovery layer closes them;
 * delivery is proven only by the receiver's allReceived (C:\mtc\docs\arch\transport.md
 * sections 3 and 5). Do not add an ACK here.
 *
 * Threading: the native library calls back on its own threads and must not be called back
 * from inside a callback. Everything that changes the connection runs on [worker], one thread.
 */
class DataSession(
    val channelId: String,
    private val myUserId: String,
    private val remoteUserId: String,
    private val remotePublicKey: String,
    private val identity: IdentityKeys,
    private val factory: PeerConnectionFactory,
    private val initiator: Boolean,
    private val log: (String) -> Unit,
    private val onChunk: (String) -> Unit,
    private val onEnd: (String) -> Unit
) {

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mtc-rtc-$channelId").apply { isDaemon = true }
    }

    // Touched only on the worker thread.
    private var connection: RTCPeerConnection? = null
    private var room: SignalRoom? = null
    private var remoteDescriptionSet = false
    private val queuedCandidates = ArrayList<RTCIceCandidate>()
    private var offerSent = false

    // Read from other threads too.
    @Volatile private var channel: RTCDataChannel? = null
    @Volatile private var channelOpened = false
    @Volatile private var ended = false

    /** Completes with null when the channel opens, or with the reason it never did. */
    private val opening = CompletableDeferred<String?>()

    /** Serialises sends: several dispatches can share this connection (RTCClient.sendMutex). */
    private val sendMutex = Mutex()

    val isOpen: Boolean
        get() = channelOpened && !ended

    /** Suspends until the data channel is open. Returns null on success, else why it failed. */
    suspend fun awaitOpen(): String? = opening.await()

    fun start() = post {
        val relayServers = Relay.iceServers()
        log("server ICE ricevuti: ${relayServers.size}")

        val config = RTCConfiguration()
        for (relayServer in relayServers) {
            val server = RTCIceServer()
            server.urls.addAll(relayServer.urls)
            if (relayServer.username.isNotEmpty() && relayServer.credential.isNotEmpty()) {
                server.username = relayServer.username
                server.password = relayServer.credential
            }
            config.iceServers.add(server)
        }
        // As on the phone (RTCClient.initializePeerConnection).
        config.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
        config.rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE

        val pc = factory.createPeerConnection(config, connectionObserver)
        if (pc == null) {
            end("impossibile creare la connessione WebRTC")
            return@post
        }
        connection = pc

        if (initiator) {
            // RTCClient.setupDataChannel: the initiator creates the channel before the offer.
            val init = RTCDataChannelInit()
            init.ordered = false
            val created = pc.createDataChannel("dataChannel", init)
            if (created == null) {
                end("impossibile creare il canale dati")
                return@post
            }
            attach(created)
        }

        val signalRoom = SignalRoom(
            channelId = channelId,
            myUserId = myUserId,
            remoteUserId = remoteUserId,
            remotePublicKey = remotePublicKey,
            identity = identity,
            onPeer = { post { peerJoined() } },
            onSignal = { signal -> post { handleSignal(signal) } },
            onProblem = { problem -> log(problem) }
        )
        room = signalRoom
        if (!signalRoom.connect()) {
            end("stanza di segnalazione non raggiungibile")
            return@post
        }
        log(if (initiator) "entrato nella stanza di segnalazione, in attesa del destinatario"
            else "entrato nella stanza di segnalazione, in attesa dell'offerta")

        // The phone waits 45 s for a peer in the room (CloudflareSignal PEER_TIMEOUT_MS) and
        // 75 s overall (Voip.WEBRTC_CONNECTION_TIMEOUT). A session with no open channel after
        // that is dead.
        val openTimeout = Runnable {
            if (!channelOpened) end("nessun canale dati entro $OPEN_TIMEOUT_SECONDS s")
        }
        worker.schedule(openTimeout, OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Ends the session from outside, e.g. when a newer dataCall from the same peer replaces it. */
    fun close(reason: String) = post { end(reason) }

    /**
     * Writes one chunk into the data channel. Mirror of RTCClient.sendData: wait briefly for a
     * channel still connecting, drain a full buffer BEFORE queueing more (a standing queue on a
     * slow path starves the connectivity checks and gets the connection declared dead), give up
     * after 15 s of a buffer that does not drain.
     */
    suspend fun sendData(data: ByteArray): Boolean {
        return try {
            sendMutex.withLock {
                val current = channel ?: return@withLock false
                if (ended) return@withLock false

                var stateAttempts = 0
                while (current.state == RTCDataChannelState.CONNECTING && stateAttempts < 10) {
                    delay(100)
                    stateAttempts++
                }
                if (current.state != RTCDataChannelState.OPEN) return@withLock false

                var adaptiveDelay = 15L
                val startTime = System.currentTimeMillis()
                while (current.bufferedAmount > MAX_BUFFERED_BYTES) {
                    if (System.currentTimeMillis() - startTime > BUFFER_TIMEOUT_MS) {
                        log("buffer del canale pieno da ${BUFFER_TIMEOUT_MS / 1000} s, invio interrotto")
                        return@withLock false
                    }
                    adaptiveDelay = (adaptiveDelay + 15).coerceAtMost(150)
                    delay(adaptiveDelay)
                }

                // Binary, as the phone sends it; the receiver reads the bytes as UTF-8 text.
                current.send(RTCDataChannelBuffer(ByteBuffer.wrap(data), true))
                true
            }
        } catch (e: Exception) {
            log("invio sul canale fallito: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------ signalling

    /** The relay says the other side is in the room. The initiator sends its offer, once. */
    private fun peerJoined() {
        if (ended) return
        if (!initiator) {
            log("il mittente e' nella stanza di segnalazione")
            return
        }
        if (offerSent) return
        offerSent = true
        log("il destinatario e' nella stanza di segnalazione, invio l'offerta")
        val pc = connection ?: return
        pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription?) = post {
                if (description == null) {
                    end("offerta vuota")
                    return@post
                }
                setLocal(description, "offer")
            }

            override fun onFailure(error: String?) = post { end("offerta non creata: $error") }
        })
    }

    private fun handleSignal(signal: Signal) {
        if (ended) return
        val pc = connection ?: return
        when (signal.type) {
            "offer" -> {
                val sdp = signal.sdp ?: return
                log("offerta ricevuta")
                pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, sdp),
                    object : SetSessionDescriptionObserver {
                        override fun onSuccess() = post {
                            afterRemoteDescription()
                            createAnswer()
                        }

                        override fun onFailure(error: String?) = post { end("offerta rifiutata: $error") }
                    })
            }
            "answer" -> {
                val sdp = signal.sdp ?: return
                log("risposta ricevuta")
                pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, sdp),
                    object : SetSessionDescriptionObserver {
                        override fun onSuccess() = post { afterRemoteDescription() }

                        override fun onFailure(error: String?) = post { end("risposta rifiutata: $error") }
                    })
            }
            "candidate" -> {
                val sdp = signal.candidateSdp ?: return
                val candidate = RTCIceCandidate(signal.candidateMid ?: "", signal.candidateLineIndex, sdp)
                if (remoteDescriptionSet) pc.addIceCandidate(candidate) else queuedCandidates.add(candidate)
            }
        }
    }

    /** Candidates that arrived before the remote description can be applied now. */
    private fun afterRemoteDescription() {
        if (ended) return
        val pc = connection ?: return
        remoteDescriptionSet = true
        for (candidate in queuedCandidates) pc.addIceCandidate(candidate)
        queuedCandidates.clear()
    }

    private fun createAnswer() {
        if (ended) return
        val pc = connection ?: return
        pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription?) = post {
                if (description == null) {
                    end("risposta vuota")
                    return@post
                }
                setLocal(description, "answer")
            }

            override fun onFailure(error: String?) = post { end("risposta non creata: $error") }
        })
    }

    /** Applies our own offer or answer, then sends it to the peer. */
    private fun setLocal(description: RTCSessionDescription, type: String) {
        if (ended) return
        val pc = connection ?: return
        pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
            override fun onSuccess() = post {
                room?.send(Signal(type = type, sdp = description.sdp))
                log(if (type == "offer") "offerta inviata" else "risposta inviata")
            }

            override fun onFailure(error: String?) = post { end("descrizione locale non applicata: $error") }
        })
    }

    // ------------------------------------------------------------------ connection and channel

    private val connectionObserver = object : PeerConnectionObserver {

        override fun onIceCandidate(candidate: RTCIceCandidate?) {
            if (candidate == null) return
            post {
                room?.send(
                    Signal(
                        type = "candidate",
                        candidateMid = candidate.sdpMid,
                        candidateLineIndex = candidate.sdpMLineIndex,
                        candidateSdp = candidate.sdp
                    )
                )
            }
        }

        override fun onConnectionChange(state: RTCPeerConnectionState?) {
            post {
                log("connessione: $state")
                if (state == RTCPeerConnectionState.FAILED) end("connessione fallita")
                if (state == RTCPeerConnectionState.CLOSED) end("connessione chiusa")
            }
        }

        override fun onDataChannel(dataChannel: RTCDataChannel?) {
            if (dataChannel == null) return
            // The initiator uses the channel it created and ignores any other, as RTCClient does.
            if (initiator) return
            attach(dataChannel)
        }
    }

    /**
     * Registers the observer on a data channel, ours or the peer's. Done at once, on whatever
     * thread we are on, as the phone does (RTCClient.onDataChannel): the sender starts writing
     * chunks the moment its side reports the channel open.
     */
    private fun attach(dataChannel: RTCDataChannel) {
        dataChannel.registerObserver(object : RTCDataChannelObserver {

            override fun onBufferedAmountChange(sentDataSize: Long) {}

            override fun onStateChange() {
                post { channelStateChanged(dataChannel) }
            }

            override fun onMessage(buffer: RTCDataChannelBuffer?) {
                if (buffer == null) return
                // The buffer belongs to the native side: copy it before returning.
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                post { onChunk(text) }
            }
        })
        channel = dataChannel
        // The channel can already be open by the time the observer is registered.
        post { channelStateChanged(dataChannel) }
    }

    /** Worker thread only. */
    private fun channelStateChanged(dataChannel: RTCDataChannel) {
        if (ended) return
        val state = dataChannel.state
        if (state == RTCDataChannelState.OPEN && !channelOpened) {
            channelOpened = true
            // The room is only needed for negotiation (C:\mtc\docs\arch\webrtc.md section 3).
            room?.close()
            log("canale dati APERTO")
            opening.complete(null)
        }
        if (state == RTCDataChannelState.CLOSED) end("canale dati chiuso dall'altro capo")
    }

    /** Worker thread only. */
    private fun end(reason: String) {
        if (ended) return
        ended = true
        opening.complete(reason)
        try {
            room?.close()
        } catch (e: Exception) {
            // Nothing to do.
        }
        try {
            channel?.unregisterObserver()
        } catch (e: Exception) {
            // Nothing to do.
        }
        try {
            connection?.close()
        } catch (e: Exception) {
            // Nothing to do.
        }
        onEnd(reason)
        worker.shutdown()
    }

    private fun post(block: () -> Unit) {
        try {
            worker.execute {
                try {
                    block()
                } catch (e: Throwable) {
                    log("errore nella sessione: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // The session already ended and its worker is shut down.
            opening.complete("sessione gia' chiusa")
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_SECONDS = 75L

        /** RTCClient.sendData: maxBufferSize and bufferTimeout. */
        const val MAX_BUFFERED_BYTES = 200_000L
        const val BUFFER_TIMEOUT_MS = 15_000L
    }
}
