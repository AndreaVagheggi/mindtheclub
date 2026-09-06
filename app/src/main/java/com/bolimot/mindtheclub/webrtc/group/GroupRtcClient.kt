package com.bolimot.mindtheclub.webrtc.group

import android.content.Context
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.isLowEndDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One phone's leg of a group call: a single PeerConnection to Cloudflare's SFU that
 * sends this device's camera and microphone once and receives every other participant's
 * tracks over the same connection.
 *
 * Deliberately NOT built on [com.bolimot.mindtheclub.webrtc.RTCClient]. That one
 * negotiates peer to peer through the signalling worker, carries the data channel the
 * whole app depends on, and is tuned around the failure modes of two phones trying to
 * reach each other. An SFU leg is a plain client to server connection with an HTTP
 * negotiation and no candidate exchange. Piegare il client 1:1 into both shapes would
 * put the app's most load bearing component at risk for a feature that can live beside
 * it.
 *
 * Media is sealed frame by frame before it reaches Cloudflare (see [setCallKey]). An
 * SFU, unlike a TURN relay, terminates transport encryption on each leg, so without
 * that step the relay would see the picture.
 */
class GroupRtcClient(private val context: Context) {

    private val tag = "GroupRtcClient"

    /** Serialises everything that touches the PeerConnection's negotiation state. */
    private val negotiation = Mutex()

    private var eglBase: EglBase? = null
    var eglContext: EglBase.Context? = null
        private set

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var connection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localAudioTrack: AudioTrack? = null
    var localVideoTrack: VideoTrack? = null
        private set

    /** The SFU session this phone owns. Others need it to subscribe. */
    var sessionId: String? = null
        private set

    var audioTrackName: String = ""
        private set
    var videoTrackName: String = ""
        private set

    private val released = AtomicBoolean(false)

    // mid of a subscribed transceiver -> the participant it belongs to. Concurrent
    // because onTrack arrives on WebRTC's signalling thread while subscribe() is still
    // finishing on a coroutine.
    private val midOwner = java.util.concurrent.ConcurrentHashMap<String, String>()
    // participant -> the mids this phone subscribed for them, for closing on leave
    private val ownerMids = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    // remote track id -> participant, the fallback when a stats entry carries a track
    // identifier but no mid
    private val trackOwner = java.util.concurrent.ConcurrentHashMap<String, String>()

    private var iceGathered: CompletableDeferred<Unit>? = null

    /**
     * Completed the first time the connection to the SFU settles. Subscribing before
     * that is refused: the Realtime API wants the PeerConnection up before it will
     * attach a track to it.
     */
    private val connected = CompletableDeferred<Boolean>()

    // ── frame encryption ──
    private var keyProvider: FrameCryptorKeyProvider? = null
    private val cryptors = mutableListOf<FrameCryptor>()
    private var keyIndex = 0

    // ── byte metering ──
    private var lastBytes = -1L

    /** Remote media as it arrives, keyed by participant id. */
    interface Listener {
        fun onRemoteVideo(pid: String, track: VideoTrack)
        fun onRemoteAudio(pid: String, track: AudioTrack)
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
    }

    @Volatile var listener: Listener? = null

    // ─────────────────────────────────────────────────────────────── lifecycle

    /**
     * Builds the media pipeline, opens the SFU session and publishes this phone.
     *
     * @return false when the call cannot start at all, so the caller shows the failure
     * instead of sitting on a black screen.
     */
    suspend fun start(pid: String, withVideo: Boolean): Boolean = negotiation.withLock {
        try {
            if (!initFactory(withVideo)) return@withLock false

            audioTrackName = "$pid-a"
            videoTrackName = if (withVideo) "$pid-v" else ""

            if (!createConnection()) return@withLock false

            if (!addLocalTracks(withVideo)) {
                debugLine(tag, "No local track to publish")
                return@withLock false
            }

            val pc = connection ?: return@withLock false

            val offer = pc.createOfferSuspend() ?: return@withLock false
            if (!pc.setLocalSuspend(offer)) return@withLock false

            // No trickle channel, so whatever the offer carries when it leaves is all
            // the SFU will ever see. Gathering is host only (the SFU is ice-lite and
            // directly reachable, see createConnection) so it finishes in milliseconds;
            // the timeout is for the phone where it never finishes at all, e da li' the
            // answer's own candidate is enough to connect anyway.
            withTimeoutOrNull(2_000L) { iceGathered?.await() }

            val localSdp = pc.localDescription?.description ?: offer.description

            // The session is opened WITH the offer: the API refuses one without a
            // session description and answers this directly. Publishing is therefore a
            // naming step later, not a second negotiation.
            val session = SfuApi.newSession(localSdp)
            if (session == null) {
                debugLine(tag, "Could not open an SFU session")
                return@withLock false
            }
            sessionId = session.sessionId

            if (!pc.setRemoteSuspend(
                    SessionDescription(SessionDescription.Type.ANSWER, session.answerSdp)
                )
            ) return@withLock false

            // Frame encryption attached before the media path is up, so no frame can
            // ever leave this phone in chiaro.
            attachSenderCryptors(pid)

            // mids exist once the local description is set; the names are what the
            // other participants will pull.
            val published = mutableMapOf<String, String>()
            for (t in pc.transceivers) {
                val mid = t.mid ?: continue
                val kind = t.sender?.track()?.kind() ?: continue
                when (kind) {
                    MediaStreamTrack.AUDIO_TRACK_KIND -> published[mid] = audioTrackName
                    MediaStreamTrack.VIDEO_TRACK_KIND -> published[mid] = videoTrackName
                }
            }

            // The API refuses to attach a track to a session whose PeerConnection is
            // not connected yet ("Session is not ready yet"), so wait, do not race.
            val live = withTimeoutOrNull(20_000L) { connected.await() } ?: false
            if (!live) {
                debugLine(tag, "Never connected to the SFU")
                return@withLock false
            }

            val result = SfuApi.publishTracks(session.sessionId, published)
            if (result == null) {
                debugLine(tag, "SFU refused the local tracks")
                return@withLock false
            }

            // Not expected here, the transceivers were negotiated by the offer that
            // opened the session. Handled anyway: if the SFU ever does ask, ignoring it
            // would leave this phone publishing into nothing.
            if (result.requiresImmediateRenegotiation && result.offerSdp != null) {
                if (!pc.setRemoteSuspend(
                        SessionDescription(SessionDescription.Type.OFFER, result.offerSdp)
                    )
                ) return@withLock false
                val answer = pc.createAnswerSuspend() ?: return@withLock false
                if (!pc.setLocalSuspend(answer)) return@withLock false
                if (!SfuApi.renegotiate(session.sessionId, answer.description)) return@withLock false
            }

            debugLine(tag, "Published local media to the SFU")
            true
        } catch (e: Exception) {
            debugLine(tag, "start failed: ${e.message}")
            false
        }
    }

    private fun initFactory(withVideo: Boolean): Boolean {
        return try {
            eglBase = EglBase.create()
            eglContext = eglBase?.eglBaseContext
            if (eglContext == null) {
                debugLine(tag, "EGL context is null")
                return false
            }

            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()

            val options = PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = false
                disableEncryption = false
            }

            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, false))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()

            if (factory == null) {
                debugLine(tag, "PeerConnectionFactory creation failed")
                audioDeviceModule?.release()
                audioDeviceModule = null
                return false
            }

            debugLine(tag, "Factory ready (video=$withVideo)")
            true
        } catch (e: Exception) {
            debugLine(tag, "initFactory failed: ${e.message}")
            false
        }
    }

    private fun createConnection(): Boolean {
        // Nessun ICE server, apposta. The SFU answers with `a=ice-lite` and a public
        // host candidate, so this phone is the controlling side and simply sends to
        // that address: the outgoing packet opens the NAT binding by itself. A STUN
        // round trip would only slow the start, and TURN would add a hop, a cost and a
        // second thing to fail.
        //
        // It is also why a group call connects where the old relay only mode failed
        // nine times out of ten: that was two phones meeting in the middle, this is an
        // ordinary client to server connection.
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Gather once and finish, so COMPLETE actually fires and the offer can
            // leave. Continual gathering never reports COMPLETE and would cost every
            // call the full wait for nothing.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        iceGathered = CompletableDeferred()

        connection = factory?.createPeerConnection(config, object : PeerConnection.Observer {

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                debugLine(tag, "ICE state: $state")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                debugLine(tag, "PeerConnection state: $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        if (!connected.isCompleted) connected.complete(true)
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED ->
                        if (!connected.isCompleted) connected.complete(false)
                    else -> {}
                }
                state?.let { listener?.onConnectionState(it) }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGathered?.complete(Unit)
                }
            }

            // No trickle: candidates travel inside the offer, so there is nothing to
            // send when one shows up.
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val mid = transceiver?.mid ?: return
                val pid = midOwner[mid]
                if (pid == null) {
                    debugLine(tag, "Track on mid $mid with no known owner yet")
                    return
                }

                val receiver = transceiver.receiver
                attachReceiverCryptor(receiver, pid)

                when (val track = receiver?.track()) {
                    is VideoTrack -> {
                        track.setEnabled(true)
                        trackOwner[track.id()] = pid
                        debugLine(tag, "Remote video track for $pid on mid $mid")
                        listener?.onRemoteVideo(pid, track)
                    }
                    is AudioTrack -> {
                        track.setEnabled(true)
                        trackOwner[track.id()] = pid
                        debugLine(tag, "Remote audio track for $pid on mid $mid")
                        listener?.onRemoteAudio(pid, track)
                    }
                    else -> debugLine(tag, "Unknown track kind on mid $mid")
                }
            }
        })

        if (connection == null) debugLine(tag, "createPeerConnection returned null")
        return connection != null
    }

    /** @return true when at least the microphone made it onto the connection. */
    private fun addLocalTracks(withVideo: Boolean): Boolean {
        val pc = connection ?: return false
        val f = factory ?: return false
        var any = false

        val audioSource = f.createAudioSource(MediaConstraints())
        localAudioTrack = f.createAudioTrack("mtc-a0", audioSource)?.apply { setEnabled(true) }
        localAudioTrack?.let {
            pc.addTransceiver(
                it,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            )
            any = true
        }

        // A phone with no working camera still joins, muted picture and all: la call
        // vale piu' della tile.
        if (withVideo && startCapture()) {
            localVideoTrack?.let {
                pc.addTransceiver(
                    it,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
                any = true
            }
        } else if (withVideo) {
            videoTrackName = ""
        }

        return any
    }

    private fun startCapture(): Boolean {
        val f = factory ?: return false

        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            org.webrtc.Camera1Enumerator(true)
        }

        val capturer = (enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull())
            ?.let { enumerator.createCapturer(it, null) } as? CameraVideoCapturer

        if (capturer == null) {
            debugLine(tag, "No camera available")
            return false
        }
        videoCapturer = capturer

        val source = f.createVideoSource(capturer.isScreencast) ?: return false
        surfaceTextureHelper = SurfaceTextureHelper.create("GroupCapture", eglContext)
            ?: return false

        return try {
            capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
            if (isLowEndDevice()) {
                capturer.startCapture(
                    GroupCallConfig.CAPTURE_WIDTH_LOW,
                    GroupCallConfig.CAPTURE_HEIGHT_LOW,
                    GroupCallConfig.CAPTURE_FPS_LOW
                )
            } else {
                capturer.startCapture(
                    GroupCallConfig.CAPTURE_WIDTH,
                    GroupCallConfig.CAPTURE_HEIGHT,
                    GroupCallConfig.CAPTURE_FPS
                )
            }
            localVideoTrack = f.createVideoTrack("mtc-v0", source)?.apply { setEnabled(true) }
            localVideoTrack != null
        } catch (e: Exception) {
            debugLine(tag, "startCapture failed: ${e.message}")
            false
        }
    }

    // ────────────────────────────────────────────────────────────── subscribing

    /**
     * Subscribes to one participant's published tracks.
     *
     * The mid mapping is recorded before the SFU's offer is applied, because onTrack
     * fires inside setRemoteDescription: register late and the first track arrives with
     * nobody to attach it to.
     */
    suspend fun subscribe(participant: RoomParticipant): Boolean = negotiation.withLock {
        val session = sessionId ?: return@withLock false
        val pc = connection ?: return@withLock false

        // The roster can arrive within milliseconds of publishing, well before the
        // media path is up. Pulling then is simply refused by the SFU, and the
        // participant would sit as a permanently blank tile.
        val live = withTimeoutOrNull(20_000L) { connected.await() } ?: false
        if (!live) {
            debugLine(tag, "Not connected to the SFU, cannot subscribe to ${participant.pid}")
            return@withLock false
        }

        val refs = mutableListOf<SfuApi.RemoteTrackRef>()
        if (participant.audioTrack.isNotEmpty()) {
            refs.add(SfuApi.RemoteTrackRef(participant.sessionId, participant.audioTrack))
        }
        if (participant.videoTrack.isNotEmpty()) {
            refs.add(SfuApi.RemoteTrackRef(participant.sessionId, participant.videoTrack))
        }
        if (refs.isEmpty()) return@withLock false

        var attached = 0
        var pending = refs

        // Due passate. A publisher whose camera has not produced its first frame yet
        // is answered with empty_track_error, and that is a moment in time, not a
        // verdict: without the second ask, a tile a fraction of a second late stays
        // blank for the whole call.
        repeat(2) { attempt ->
            if (pending.isEmpty()) return@repeat
            if (attempt > 0) delay(3_000L)

            val result = SfuApi.pullTracks(session, pending)
            if (result == null) {
                debugLine(tag, "Pull failed for ${participant.pid}")
                return@repeat
            }

            val mids = ownerMids.getOrPut(participant.pid) { mutableListOf() }
            for ((_, mid) in result.mids) {
                midOwner[mid] = participant.pid
                if (!mids.contains(mid)) mids.add(mid)
            }
            attached += result.mids.size

            if (result.requiresImmediateRenegotiation && result.offerSdp != null) {
                if (!pc.setRemoteSuspend(
                        SessionDescription(SessionDescription.Type.OFFER, result.offerSdp)
                    )
                ) return@withLock false
                val answer = pc.createAnswerSuspend() ?: return@withLock false
                if (!pc.setLocalSuspend(answer)) return@withLock false
                if (!SfuApi.renegotiate(session, answer.description)) return@withLock false
            }

            pending = pending.filter { it.trackName in result.failed }.toMutableList()
            if (pending.isNotEmpty()) {
                debugLine(tag, "${pending.size} track(s) of ${participant.pid} not ready, retrying")
            }
        }

        debugLine(tag, "Subscribed to ${participant.pid} ($attached track(s))")
        attached > 0
    }

    /** Stops paying for the tracks of somebody who left. */
    suspend fun unsubscribe(pid: String) = negotiation.withLock {
        val session = sessionId ?: return@withLock
        val mids = ownerMids.remove(pid) ?: return@withLock
        mids.forEach { midOwner.remove(it) }
        SfuApi.closeTracks(session, mids)
        debugLine(tag, "Unsubscribed from $pid")
    }

    // ──────────────────────────────────────────────────────────── frame crypto

    /**
     * Installs the call key. Every sender and receiver on this connection uses the same
     * key, which reached this phone sealed inside the call invitation over the app's
     * own encrypted channel, so the SFU never sees it.
     *
     * @param epoch increments on every re-key and doubles as the key ring slot, so a
     * participant a moment behind still decrypts with the previous key instead of
     * seeing a frozen picture.
     */
    fun setCallKey(key: ByteArray, epoch: Int) {
        if (!GroupCallConfig.E2EE_ENABLED) return

        val provider = keyProvider ?: FrameCryptorFactory.createFrameCryptorKeyProvider(
            true,                                   // shared key: one key for the whole call
            "MTCGroupCall".toByteArray(),           // ratchet salt
            16,                                     // ratchet window
            ByteArray(0),                           // no unencrypted magic bytes
            -1,                                     // tolerate decryption failures, never mute the call
            16,                                     // key ring size
            false,                                  // keep frames while the cryptor warms up
            FrameCryptorKeyDerivationAlgorithm.HKDF
        ).also { keyProvider = it }

        keyIndex = epoch % 16
        provider.setSharedKey(keyIndex, key)
        cryptors.forEach { it.setKeyIndex(keyIndex) }
        debugLine(tag, "Call key installed at index $keyIndex")
    }

    private fun attachSenderCryptors(pid: String) {
        if (!GroupCallConfig.E2EE_ENABLED) return
        val f = factory ?: return
        val provider = keyProvider ?: run {
            // The manager installs the call key before starting. Arrivare qui means
            // outgoing media would leave in the clear, the one outcome this feature
            // must never have.
            debugLine(tag, "No call key at publish time, refusing to attach senders")
            return
        }

        connection?.senders?.forEach { sender ->
            if (sender.track() == null) return@forEach
            try {
                val cryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
                    f, sender, pid, FrameCryptorAlgorithm.AES_GCM, provider
                )
                cryptor.setKeyIndex(keyIndex)
                cryptor.setEnabled(true)
                cryptors.add(cryptor)
            } catch (e: Exception) {
                debugLine(tag, "Sender cryptor failed: ${e.message}")
            }
        }
    }

    private fun attachReceiverCryptor(receiver: RtpReceiver?, pid: String) {
        if (!GroupCallConfig.E2EE_ENABLED) return
        val f = factory ?: return
        val provider = keyProvider ?: return
        if (receiver == null) return

        try {
            val cryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
                f, receiver, pid, FrameCryptorAlgorithm.AES_GCM, provider
            )
            cryptor.setKeyIndex(keyIndex)
            cryptor.setEnabled(true)
            cryptors.add(cryptor)
        } catch (e: Exception) {
            debugLine(tag, "Receiver cryptor failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────── controls

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        try {
            if (enabled) {
                if (isLowEndDevice()) {
                    videoCapturer?.startCapture(
                        GroupCallConfig.CAPTURE_WIDTH_LOW,
                        GroupCallConfig.CAPTURE_HEIGHT_LOW,
                        GroupCallConfig.CAPTURE_FPS_LOW
                    )
                } else {
                    videoCapturer?.startCapture(
                        GroupCallConfig.CAPTURE_WIDTH,
                        GroupCallConfig.CAPTURE_HEIGHT,
                        GroupCallConfig.CAPTURE_FPS
                    )
                }
            } else {
                // Stopping the capturer, not just the track: a disabled track still
                // sends black frames, and black frames still cost money.
                videoCapturer?.stopCapture()
            }
        } catch (e: Exception) {
            debugLine(tag, "setCameraEnabled($enabled) failed: ${e.message}")
        }
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    /** One reading of the connection: what it cost, and who is talking. */
    data class StatsSnapshot(
        val bytesDelta: Long,
        /** participant id to audio level, 0..1. Only participants currently heard. */
        val audioLevels: Map<String, Double>
    )

    /**
     * Reads the connection's counters once and answers both questions the call screen
     * has: how many bytes this phone moved through the SFU since the previous reading,
     * which is what the monthly allowance is spent against, and who is speaking, which
     * promotes a tile to the spotlight.
     *
     * One report for both apposta: speaker detection must be quick and metering must
     * not, so asking twice would double the cost of the fast loop for nothing.
     */
    suspend fun pollStats(): StatsSnapshot = withContext(Dispatchers.Default) {
        val pc = connection ?: return@withContext StatsSnapshot(0L, emptyMap())
        val report = CompletableDeferred<RTCStatsReport?>()

        try {
            pc.getStats { report.complete(it) }
        } catch (e: Exception) {
            debugLine(tag, "getStats threw: ${e.message}")
            return@withContext StatsSnapshot(0L, emptyMap())
        }

        val stats = withTimeoutOrNull(5_000L) { report.await() }
            ?: return@withContext StatsSnapshot(0L, emptyMap())

        var total = 0L
        val levels = mutableMapOf<String, Double>()

        for (entry in stats.statsMap.values) {
            when (entry.type) {
                "transport" -> {
                    val sent = (entry.members["bytesSent"] as? Number)?.toLong() ?: 0L
                    val received = (entry.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                    total += sent + received
                }

                "inbound-rtp" -> {
                    if (entry.members["kind"] != "audio") continue
                    val level = (entry.members["audioLevel"] as? Number)?.toDouble() ?: continue
                    val mid = entry.members["mid"] as? String
                    val trackId = entry.members["trackIdentifier"] as? String
                    val pid = mid?.let { midOwner[it] } ?: trackId?.let { trackOwner[it] } ?: continue
                    // Keep the loudest reading when a participant somehow reports twice.
                    levels[pid] = maxOf(levels[pid] ?: 0.0, level)
                }
            }
        }

        // First reading sets the baseline; a counter that goes backwards means the
        // transport was replaced, so start again from there rather than charging the
        // user for a negative or a bogus jump.
        val delta = if (total <= 0L) 0L
        else if (lastBytes < 0L || total < lastBytes) 0L
        else total - lastBytes

        if (total > 0L) lastBytes = total

        StatsSnapshot(delta, levels)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        debugLine(tag, "Releasing group RTC resources")

        cryptors.forEach { runCatching { it.dispose() } }
        cryptors.clear()
        runCatching { keyProvider?.dispose() }
        keyProvider = null

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null

        runCatching { connection?.close() }
        runCatching { connection?.dispose() }
        connection = null

        runCatching { factory?.dispose() }
        factory = null

        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null

        runCatching { eglBase?.release() }
        eglBase = null
        eglContext = null

        midOwner.clear()
        ownerMids.clear()
        trackOwner.clear()
    }

    // ───────────────────────────────────────────────── suspend SDP conveniences

    private suspend fun PeerConnection.createOfferSuspend(): SessionDescription? {
        val result = CompletableDeferred<SessionDescription?>()
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) { result.complete(description) }
            override fun onCreateFailure(error: String?) {
                debugLine(tag, "createOffer failed: $error")
                result.complete(null)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
        return withTimeoutOrNull(10_000L) { result.await() }
    }

    private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription? {
        val result = CompletableDeferred<SessionDescription?>()
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) { result.complete(description) }
            override fun onCreateFailure(error: String?) {
                debugLine(tag, "createAnswer failed: $error")
                result.complete(null)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
        return withTimeoutOrNull(10_000L) { result.await() }
    }

    private suspend fun PeerConnection.setLocalSuspend(description: SessionDescription): Boolean {
        val result = CompletableDeferred<Boolean>()
        setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { result.complete(true) }
            override fun onSetFailure(error: String?) {
                debugLine(tag, "setLocalDescription failed: $error")
                result.complete(false)
            }
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, description)
        return withTimeoutOrNull(10_000L) { result.await() } ?: false
    }

    private suspend fun PeerConnection.setRemoteSuspend(description: SessionDescription): Boolean {
        val result = CompletableDeferred<Boolean>()
        setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { result.complete(true) }
            override fun onSetFailure(error: String?) {
                debugLine(tag, "setRemoteDescription failed: $error")
                result.complete(false)
            }
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, description)
        return withTimeoutOrNull(10_000L) { result.await() } ?: false
    }
}
