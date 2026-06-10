package com.bolimot.mindtheclub.webrtc

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.emitWebRtcControlEvent
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.isLowEndDevice
import com.bolimot.mindtheclub.functions.waitForInternetConnection
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bolimot.mindtheclub.voip.sendCallEventToPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

class RTCClient private constructor(
    val remoteUserId: String,
    private val video: Boolean,
    context: Context,
    private val onlyData: Boolean = false,
) {

    private var iceServers: List<PeerConnection.IceServer>? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private val candidateMutex = Mutex()
    private var isClosing: Boolean = false
    private var isRestarting: Boolean = false
    private var reconnectingUiJob: Job? = null

    private var audioDeviceModule: AudioDeviceModule? = null

    private val context: Context = context.applicationContext

    fun isDataChannelOpen(): Boolean {
        return dataChannel?.state() == DataChannel.State.OPEN
    }

    fun isConnected(): Boolean {
        return connection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
    }

    var eglContext: EglBase.Context? = null
    private var eglBase: EglBase? = null

    private var isVideoCall: Boolean = false

    private val clientJob = Job()
    private val clientScope = CoroutineScope(Dispatchers.IO + clientJob)

    private var socket: Socket? = null
    private val socketMutex = Mutex()

    private val waitForSignalingCompleteTimeout = 25000L

    private val localMediaSetupDeferred = CompletableDeferred<Unit>()
    private val iceGatheringCompleteDeferred = CompletableDeferred<Unit>()

    private val isAnswerHandled = AtomicBoolean(false)
    private val candidateQueue = mutableListOf<Candidate>()
    private val processedCandidates = mutableSetOf<String>()

    private val _isConnectedClient = MutableStateFlow(false)

    private val isConnectedClient get() = _isConnectedClient

    private var hasAudio: Boolean = false
    var hasVideo: Boolean = false
    var cleanedUp: Boolean = false

    private var videoCapturer: CameraVideoCapturer? = null
    private var localAudioTrack: AudioTrack? = null
    var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var dataChannel: DataChannel? = null
    private val _dataChannelState = MutableStateFlow(DataChannelState.CLOSED)

    private val shutdownReceiver = ShutdownReceiver()

    private lateinit var channelId: String
    private var callId: String? = null

    @Volatile
    var isData: Boolean = false
        private set

    @Volatile
    var iceConnectionState: IceConnectionState = IceConnectionState.NEW
        private set(value) {
            field = value
            onIceConnectionStateChange(value)
        }
    @Volatile
    var peerConnectionState: PeerConnectionState = PeerConnectionState.NEW
        private set(value) {
            field = value
            onPeerConnectionStateChange(value)
        }
    @Volatile
    var isIceConnected: Boolean = false
        private set
    @Volatile
    var isInitiator: Boolean = false
        private set
    @Volatile
    var isIceFault: Boolean = false
        private set
    @Volatile
    private var myUserId: String? = MySelf.userId()

    val remoteStreamEvent = MutableLiveData<MediaStream>()

    private fun handleRemoteStream(stream: MediaStream) {
        debugLine("RTCClient", "Remote media stream received")
        remoteStreamEvent.postValue(stream)
    }

    private val isRemoteDescriptionSet = AtomicBoolean(false)

    companion object {
        const val ACTION_WEBRTC_SHUTDOWN = "com.bolimot.mindtheclub.ACTION_WEBRTC_SHUTDOWN"
        const val PREF_STEALTH_MODE = "mtc_stealth_mode_enabled"

        fun create(channelId: String,
                   callId: String,
                   remoteUserId: String,
                   initiator: Boolean,
                   video: Boolean,
                   context: Context,
                   onlyData: Boolean = false
                    ): RTCClient {
            val client = RTCClient(remoteUserId, video, context, onlyData)
            client.initialize(channelId, callId, remoteUserId, initiator, video, onlyData)
            return client
        }
    }

    fun setLocalAudioEnabled(enable: Boolean) {
        localAudioTrack?.setEnabled(enable)
    }

    fun setLocalVideoEnabled(enable: Boolean) {
        localVideoTrack?.setEnabled(enable)
    }

    private fun initialize(channelId: String, callId: String, remoteUserId: String, initiator: Boolean, video: Boolean, onlyData: Boolean) {
        val peerHasJoined = CompletableDeferred<Boolean>()
        val offerReceived = CompletableDeferred<Boolean>()
        val onPeerJoinExecuted = AtomicBoolean(false)

        debugLine("Initialize", "Initializing, video = $video, onlyData = $onlyData")
        isVideoCall = video
        this.channelId = channelId
        this.callId = callId

        clientScope.launch {
            try {
                val intentFilter = IntentFilter(ACTION_WEBRTC_SHUTDOWN)
                ContextCompat.registerReceiver(context, shutdownReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

                myUserId = MySelf.userId()

                if(myUserId == null) {
                    emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId, "User ID is null")
                    debugLine("Initialize", "User ID is null")
                    cleanup()
                    return@launch
                }

                isInitiator = initiator
                debugLine("Initialize", "Initiator: $isInitiator")

                if(video && !onlyData) {
                    val eglSuccess = createEglContextWithRetry()
                    if (!eglSuccess) {
                        debugLine("Initialize", "Failed to create EGL context")
                        return@launch
                    }
                }

                if(!initializeConnectionFactory()) {
                    debugLine("Initialize", "Cannot initialize peer connection factory")
                    emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId, "Cannot initialize peer connection factory")
                    cleanup()
                    return@launch
                }

                //**********************************************************************************//
                //                             START OPEN SIGNAL                                    //
                //**********************************************************************************//

                openSignal(channelId, remoteUserId, initiator,

                    onIceServersFetched = { iceServers ->
                        this@RTCClient.iceServers = iceServers

                        for(server in iceServers){
                            debugLine("Initialize", "Ice server: $server\n")
                        }

                        initializePeerConnection(iceServers)

                        if (isInitiator) {
                            setupDataChannel()
                            debugLine("Initialize", "Initiator setup DataChannel")
                        } else {
                            debugLine("Initialize", "Receiver will wait for onDataChannel callback")
                        }

                        if(!onlyData) {
                            debugLine("Initialize", "It's a media call, Creating local media stream")
                            setupLocalMediaStream(video)
                        } else {
                            if (!localMediaSetupDeferred.isCompleted) {
                                localMediaSetupDeferred.complete(Unit)
                                debugLine("Initialize", "Local Media Setup Always completed for data-only")
                            }
                        }
                    },

                    onPeerJoin = { peer ->
                        debugLine("RTCClient", "Remote peer has joined: $peer/n I am : ${MySelf.userId()}")

                        if (!peerHasJoined.isCompleted) {
                            peerHasJoined.complete(true)
                        }

                        if (onPeerJoinExecuted.compareAndSet(false, true)) {

                            if (isInitiator) {
                                clientScope.launch {
                                    debugLine("RTCClient", "I'm initiator, waiting for local media to be ready...")
                                    localMediaSetupDeferred.await()
                                    debugLine("RTCClient", "Local media is ready. Sending offer.")
                                    sendOffer()
                                }
                            }

                            clientScope.launch {
                                debugLine("RTCClient", "Waiting for connection to be established")

                                isConnectedClient.filter { it }.first()
                                debugLine("RTCClient","YAY! Connection established, initiator = $isInitiator")
                                emitWebRtcControlEvent(CallEvent.CONNECTION_OPEN, remoteUserId, callId)

                                if(onlyData) {
                                    iceGatheringCompleteDeferred.await()
                                    socket = closeSignal(socket)
                                }
                            }
                        } else {
                            debugLine("RTCClient", "Redundant report! I already know remote peer has joined")
                        }
                    },

                    onSignal = { data ->
                        val signal = data.toMutableMap()
                        val type = signal["type"] as? String

                        debugLine("RTCClient", "Received signaling message, Type: $type")


                        if(type == "offer"){
                            debugLine("RTCClient", "Received offer")
                            if (!offerReceived.isCompleted) {
                                offerReceived.complete(true)
                            }
                        }

                        receiveSignal(signal)
                    },

                    onError = { error ->
                        debugLine("RTCClient", "Error: $error")
                        clientScope.launch {
                            emitWebRtcControlEvent(
                                CallEvent.CONNECTION_FAILED,
                                remoteUserId,
                                "Error: $error"
                            )
                            socket = closeSignal(socket)
                        }
                    },

                    onJoin = { sk ->
                        socket = sk

                        if (ConnectionManager.instance.cleaningUpWebRTC) {
                            debugLine("RTCClient", "Cleanup was initiated during my creation. Aborting and cleaning myself up.")
                            clientScope.launch { cleanup(true) }
                        } else {

                            if(!onlyData) {
                                ConnectionManager.instance.activeMediaRtcClient = this@RTCClient
                            } else {
                                ConnectionManager.instance.activeMediaRtcClient = null
                            }

                            debugLine("RTCClient","Channel joined, I am initiator = $initiator")

                            if (isInitiator) {
                                clientScope.launch {
                                    debugLine("RTCClient","I am initiator, waiting for remote peer to join")
                                    peerHasJoined.await()
                                }
                            } else {
                                clientScope.launch {
                                    debugLine("RTCClient","I'm not initiator, waiting for offer")
                                    offerReceived.await()
                                }
                            }
                        }
                    },

                    onTimeout = {
                        debugLine("RTCClient", "Timeout")
                        clientScope.launch {
                            emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId, "Timeout")
                            socket = closeSignal(socket)
                        }
                    }
                )

                //**********************************************************************************//
                //                             END OPEN SIGNAL                                      //
                //**********************************************************************************//

            } catch (e: Exception) {
                clientScope.launch {
                    debugLine("Initialize", "GENERAL Error initializing, Exception: ${e.message}")
                    emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId, "Error initializing: ${e.message}")
                    cleanup()
                }
            }
        }
    }

    private suspend fun createEglContextWithRetry(
        maxAttempts: Int = 3,
        delayMs: Long = 200L
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            try {
                debugLine("Initialize", "Creating eglBase (attempt ${attempt + 1}/$maxAttempts)")

                eglBase?.release()
                eglBase = null
                eglContext = null

                eglBase = EglBase.create()

                if (eglBase == null) {
                    debugLine("Initialize", "eglBase is null on attempt ${attempt + 1}")
                    throw Exception("eglBase creation returned null")
                }

                eglContext = eglBase!!.eglBaseContext

                if (eglContext == null) {
                    debugLine("Initialize", "eglContext is null on attempt ${attempt + 1}")
                    throw Exception("eglContext is null after eglBase creation")
                }

                debugLine("Initialize", "EGL context created successfully on attempt ${attempt + 1}")
                return true

            } catch (e: Exception) {
                debugLine("Initialize", "EGL creation failed on attempt ${attempt + 1}: ${e.message}")

                runCatching { eglBase?.release() }
                eglBase = null
                eglContext = null

                if (attempt == maxAttempts - 1) {
                    debugLine("Initialize", "All EGL creation attempts failed")
                    emitWebRtcControlEvent(
                        CallEvent.CONNECTION_FAILED,
                        remoteUserId,
                        "Failed to create EGL context after $maxAttempts attempts: ${e.message}"
                    )
                    return false
                }

                if (attempt < maxAttempts - 1) {
                    debugLine("Initialize", "Waiting ${delayMs}ms before retry...")
                    delay(delayMs)
                }
            }
        }

        return false
    }

    private fun receiveSignal(data: MutableMap<String, Any>) {
        if (isClosing || cleanedUp) {
            debugLine("Signaling", "Cleanup in progress, ignoring signal")
            return
        }

        val type = data["type"] as? String
        if(type == null) {
            debugLine("Signaling", "Received invalid signaling message")
            return
        }

        debugLine("Signaling", "Received signaling message: $type")

        val candidateData = data["candidate"] as? Map<*, *>
        val candidate = candidateData?.let {
            Candidate(
                sdpMid = it["sdpMid"] as? String ?: "",
                sdpMLineIndex = it["sdpMLineIndex"] as? Int ?: -1,
                sdp = it["sdp"] as? String ?: ""
            )
        }
        val message = SignalingMessage(type, data["sdp"] as? String, candidate)
        when (message.type) {
            "offer" -> handleOffer(message)
            "answer" -> if (!isAnswerHandled.getAndSet(true)) {
                handleAnswer(message)
            }
            "candidate" -> candidate?.let { handleCandidate(it) }
            else -> debugLine("Signaling", "Unknown message type: ${message.type}")
        }
    }

    fun sendSignal(message: SignalingMessage) {
        clientScope.launch {
            socketMutex.withLock {
                if (isClosing || cleanedUp || socket == null) {
                    debugLine("RTCClient", "Socket closed or cleaning up, aborting sendSignal")
                    return@withLock
                }

                socket?.sendSignal(message)
            }
        }
    }

    private fun sendOffer() {
        debugLine("RTCClient", "Sending offer")
        iceConnectionState = IceConnectionState.CONNECTING
        isInitiator = true

        var mediaConstraints = MediaConstraints()

        if(!onlyData) {
            mediaConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToSendVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToSendAudio", "true"))

                if (isLowEndDevice()) {
                    optional.add(MediaConstraints.KeyValuePair("internalSctpDataChannels", "true"))
                    optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googUseH264", "false"))
                }
            }
        }

        connection?.createOffer(object : SdpObserver by DefaultSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                debugLine("RTCClient", "Offer created: $sessionDescription")
                connection?.setLocalDescription(DefaultSdpObserver(), sessionDescription)
                applyHardwareEncoderConstraints()
                sendSignal(SignalingMessage(sessionDescription.type.canonicalForm(), sessionDescription.description, null))
            }
            override fun onCreateFailure(error: String?) {
                debugLine("RTCClient", "Offer creation failed: $error")
            }
        }, mediaConstraints)
    }

    private fun createAnswer() {
        var mediaConstraints = MediaConstraints()

        if(!onlyData) {
            mediaConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToSendVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToSendAudio", "true"))

                if (isLowEndDevice()) {
                    optional.add(MediaConstraints.KeyValuePair("internalSctpDataChannels", "true"))
                    optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googUseH264", "false"))
                }
            }
        }

        connection?.createAnswer(object : DefaultSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) {
                    debugLine("RTCClient", "onCreateSuccess returned null description")
                    return
                }
                connection?.setLocalDescription(object : DefaultSdpObserver() {
                    override fun onSetSuccess() {
                        applyHardwareEncoderConstraints()
                        sendAnswer(description)
                        debugLine("RTCClient", "Answer sent immediately: $description")
                    }
                    override fun onSetFailure(error: String?) {
                        debugLine("RTCClient", "Failed to set local description: $error")
                    }
                }, description)
            }

            override fun onCreateFailure(error: String?) {
                debugLine("RTCClient", "Answer creation failed: $error")
            }
        }, mediaConstraints)
    }

    private fun sendAnswer(sessionDescription: SessionDescription) {
        sendSignal(SignalingMessage(sessionDescription.type.canonicalForm(), sessionDescription.description, null))
        debugLine("RTCClient", "Answer sent")
    }

    private fun handleOffer(message: SignalingMessage) {
        debugLine("RTCClient", "Received offer")
        message.sdp?.let { offerSdp ->
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            debugLine("RTCClient", "Offer received: ${sessionDescription.type}")

            clientScope.launch {

                localMediaSetupDeferred.await()

                debugLine("RTCClient", "Setting remote description")

                connection?.setRemoteDescription(object : DefaultSdpObserver() {
                    override fun onSetSuccess() {
                        debugLine("RTCClient", "Remote description set successfully, creating answer")
                        isRemoteDescriptionSet.set(true)
                        processQueuedCandidates()
                        createAnswer()
                    }
                    override fun onSetFailure(error: String?) {
                        debugLine("RTCClient", "Failed to set remote description: $error")
                    }
                    override fun onCreateFailure(error: String?) {
                        debugLine("RTCClient", "Failed to create answer: $error")
                    }
                    override fun onCreateSuccess(description: SessionDescription?) {
                        debugLine("RTCClient", "Answer created: ${description?.type}")
                    }
                }, sessionDescription)
            }
        }
    }

    private fun handleAnswer(message: SignalingMessage) {
        message.sdp?.let { answerSdp ->
            val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
            connection?.setRemoteDescription(object : DefaultSdpObserver() {
                override fun onSetSuccess() {
                    debugLine("RTCClient", "Remote answer set successfully")
                    isRemoteDescriptionSet.set(true)
                    processQueuedCandidates()
                }
                override fun onSetFailure(error: String?) {
                    debugLine("RTCClient", "Failed to set remote answer: $error")
                    iceConnectionState = IceConnectionState.FAILED
                }
                override fun onCreateFailure(error: String?) {
                    debugLine("RTCClient", "Failed to create answer: $error")
                    iceConnectionState = IceConnectionState.FAILED
                }
                override fun onCreateSuccess(description: SessionDescription?) {
                    debugLine("RTCClient", "Answer created: ${description?.type}")
                }
            }, sessionDescription)
        }
    }

    private fun handleCandidate(candidate: Candidate) {
        clientScope.launch {
            candidateMutex.withLock {
                if (isClosing || cleanedUp) {
                    debugLine("HandleCandidate", "Cleanup in progress, ignoring candidate")
                    return@withLock
                }

                val candidateId = "${candidate.sdpMid}-${candidate.sdpMLineIndex}-${candidate.sdp}"

                if (processedCandidates.contains(candidateId)) {
                    debugLine("HandleCandidate", "Duplicate candidate, ignoring")
                    return@withLock
                }

                processedCandidates.add(candidateId)

                val iceCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)

                val added = connection?.addIceCandidate(iceCandidate) ?: false
                if (added) {
                    debugLine("HandleCandidate", "ICE candidate added")
                } else {
                    debugLine("HandleCandidate", "Failed to add ICE candidate, queueing for later")
                    candidateQueue.add(candidate)
                }
            }
        }
    }

    private fun initializeConnectionFactory(): Boolean {
        return try {
            if(!onlyData) {
                debugLine("initializePeerConnectionFactory", "Creating audio device module")
                audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule()
            } else {
                debugLine("initializePeerConnectionFactory", "Audio device module not created in data-only mode")
            }

            val options = PeerConnectionFactory.Options()

            options.disableNetworkMonitor = false
            options.disableEncryption = false

            if(!onlyData) {
                debugLine("initializePeerConnectionFactory", "Creating peer connection factory in video mode")
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(audioDeviceModule)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, false))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                    .createPeerConnectionFactory()
            } else {
                debugLine("initializePeerConnectionFactory", "Creating peer connection factory in data-only mode")
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .createPeerConnectionFactory()
            }

            if(peerConnectionFactory != null) {
                debugLine("initializePeerConnectionFactory", "Peer connection factory initialized")
            } else {
                debugLine("initializePeerConnectionFactory", "Peer connection factory initialization failed")
                audioDeviceModule?.release()
                audioDeviceModule = null
                return false
            }

            peerConnectionFactory != null
        } catch (e: Exception) {
            debugLine("initializePeerConnectionFactory", "Error: ${e.message}")
            audioDeviceModule?.release()
            audioDeviceModule = null
            false
        }
    }

    private fun onPeerConnectionStateChange(newPeerState: PeerConnectionState) {
        when (newPeerState) {
            PeerConnectionState.DISCONNECTED -> {
                debugLine("RTCClient", "Connection DISCONNECTED")
            }
            PeerConnectionState.FAILED -> {
                debugLine("RTCClient", "Connection FAILED")
                if(onlyData) closeConnection()
            }
            PeerConnectionState.CONNECTED -> {
                debugLine("RTCClient", "Connection CONNECTED")
            }
            else -> {
                debugLine("RTCClient", "Peer connection state changed to: $newPeerState")
            }
        }
    }

    private fun onIceConnectionStateChange(newIceState: IceConnectionState) {
        debugLine("RTCClient", "ICE Connection state changed to: $newIceState, was connected = $isIceConnected")
        val wasConnected = isIceConnected

        when (newIceState) {
            IceConnectionState.CONNECTED, IceConnectionState.COMPLETED -> {
                debugLine("RTCClient", "ICE Connection established")

                reconnectingUiJob?.cancel()
                reconnectingUiJob = null

                if(!onlyData) {
                    ConnectionManager.instance.mediaCallEstablished.value = true
                    callId?.let { id ->
                        val currentState = ManagedTelecom.currentCall.value?.webRTCConnectionState
                        if (currentState == ManagedTelecom.WebRTCConnectionState.RECONNECTING) {
                            ManagedTelecom.updateWebRTCConnectionState(id, ManagedTelecom.WebRTCConnectionState.CONNECTED)
                        }
                    }
                }

                isIceConnected = true
                isIceFault = false

                hasAudio = false
                hasVideo = false

                if(!onlyData) {
                    setupBitrateAdaptation()
                    hasAudio = true
                    hasVideo = video
                }
            }

            IceConnectionState.DISCONNECTED -> {
                debugLine("RTCClient", "ICE Connection DICONNECTED")
                if(!onlyData) {
                    ConnectionManager.instance.mediaCallEstablished.value = false
                    if (wasConnected) {
                        reconnectingUiJob?.cancel()
                        reconnectingUiJob = clientScope.launch {
                            delay(2000)
                            debugLine("RTCClient", "Still disconnected after 2s, showing reconnecting UI")
                            callId?.let { ManagedTelecom.updateWebRTCConnectionState(it, ManagedTelecom.WebRTCConnectionState.RECONNECTING) }
                        }
                    }
                }
            }

            IceConnectionState.FAILED -> {
                debugLine("RTCClient", "ICE Connection FAILED")
                reconnectingUiJob?.cancel()
                reconnectingUiJob = null

                if(!onlyData) {
                    ConnectionManager.instance.mediaCallEstablished.value = false
                }

                isIceConnected = false
                isIceFault = wasConnected

                if (isIceFault) {
                    if (isInitiator && !onlyData && !isRestarting) {
                        debugLine("RTCClient", "ICE Connection failed (Initiator). Attempting reconnect.")
                        callId?.let { ManagedTelecom.updateWebRTCConnectionState(it, ManagedTelecom.WebRTCConnectionState.RECONNECTING) }
                        clientScope.launch {
                            if (!tryRTCReconnect()) {
                                debugLine("RTCClient", "All reconnect attempts failed. Closing connection.")
                                callId?.let { ManagedTelecom.updateWebRTCConnectionState(it, ManagedTelecom.WebRTCConnectionState.FAILED) }
                                closeConnection()
                            }
                        }
                    } else if (!isInitiator && !onlyData) {
                        debugLine("RTCClient", "ICE Connection failed (Receiver). Waiting for initiator's ICE restart.")
                        callId?.let { ManagedTelecom.updateWebRTCConnectionState(it, ManagedTelecom.WebRTCConnectionState.RECONNECTING) }
                        clientScope.launch {
                            val reconnected = withTimeoutOrNull(45_000L) {
                                isConnectedClient.filter { it }.first()
                            }
                            if (reconnected == true) {
                                debugLine("RTCClient", "Receiver: ICE restart from initiator succeeded!")
                                callId?.let { ManagedTelecom.updateWebRTCConnectionState(it, ManagedTelecom.WebRTCConnectionState.CONNECTED) }
                            } else {
                                debugLine("RTCClient", "Receiver: Timed out waiting for initiator's ICE restart. Closing connection.")
                                callId?.let { ManagedTelecom.updateWebRTCConnectionState(it, ManagedTelecom.WebRTCConnectionState.FAILED) }
                                closeConnection()
                            }
                        }
                    } else {
                        debugLine("RTCClient", "ICE Connection failed (data-only). Closing connection.")
                        closeConnection()
                    }
                }
            }
            else -> {
                debugLine("RTCClient", "Connection state changed to: $newIceState, doing nothing")
            }
        }

        _isConnectedClient.value = isIceConnected
        debugLine("RTCClient", "Client connected: $isIceConnected")
    }

    private suspend fun tryRTCReconnect(): Boolean {
        var attempts = 0
        val maxAttempts = 3
        val baseDelayMs = 2000L

        while (attempts < maxAttempts) {
            attempts++
            debugLine("RTCClient", "tryRTCReconnect: Attempt $attempts of $maxAttempts")

            if (reconnectRTC()) {
                debugLine("RTCClient", "tryRTCReconnect: Successful on attempt $attempts.")
                return true
            }

            debugLine("RTCClient", "tryRTCReconnect: Failed on attempt $attempts.")
            if (attempts < maxAttempts) {
                val delayTime = baseDelayMs * attempts
                debugLine("RTCClient", "tryRTCReconnect: Will retry after ${delayTime}ms.")
                delay(delayTime)
            }
        }

        debugLine("RTCClient", "tryRTCReconnect: All $maxAttempts attempts failed.")
        return false
    }

    private suspend fun reconnectRTC(): Boolean {
        if (isRestarting) {
            debugLine("RTCClient", "reconnectRTC: Already in progress, skipping new attempt.")
            return false
        }

        if (connection == null || connection?.signalingState() == PeerConnection.SignalingState.CLOSED) {
            debugLine("RTCClient", "reconnectRTC: Cannot perform ICE restart, PeerConnection is null or closed.")
            return false
        }

        isRestarting = true
        debugLine("RTCClient", "reconnectRTC: Attempting an ICE Restart...")

        val internetConnected = waitForInternetConnection(App.context())
        if (!internetConnected) {
            debugLine("RTCClient", "reconnectRTC: No internet connection.")
            isRestarting = false
            return false
        }

        isAnswerHandled.set(false)
        isRemoteDescriptionSet.set(false)
        candidateMutex.withLock {
            candidateQueue.clear()
            processedCandidates.clear()
        }
        debugLine("RTCClient", "reconnectRTC: Signaling states reset for ICE Restart.")

        sendIceRestartOffer()

        debugLine("RTCClient", "reconnectRTC: Waiting for ICE restart to complete...")
        val connected = withTimeoutOrNull(waitForSignalingCompleteTimeout) {
            isConnectedClient.filter { it }.first()
        }

        if (connected == true) {
            debugLine("RTCClient", "reconnectRTC: ICE Restart successful, connection re-established!")
            isRestarting = false
            callId?.let { ManagedTelecom.updateWebRTCConnectionState(it, ManagedTelecom.WebRTCConnectionState.CONNECTED) }
            return true
        } else {
            debugLine("RTCClient", "reconnectRTC: Timed out or failed, ICE Restart did not complete.")
            isRestarting = false
            return false
        }
    }
    private fun sendIceRestartOffer() {
        debugLine("RTCClient", "Sending ICE restart offer")

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToSendVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToSendAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }

        connection?.createOffer(object : SdpObserver by DefaultSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                debugLine("RTCClient", "ICE restart offer created: ${sessionDescription.type}")
                connection?.setLocalDescription(DefaultSdpObserver(), sessionDescription)
                sendSignal(SignalingMessage(sessionDescription.type.canonicalForm(), sessionDescription.description, null))
            }
            override fun onCreateFailure(error: String?) {
                debugLine("RTCClient", "ICE restart offer creation failed: $error")
            }
        }, mediaConstraints)
    }

    private fun closeConnection() {
        clientScope.launch {
            debugLine("RTCClient", "Closing connection and clearing up")
            sendCallEventToPeer(remoteUserId, CallEvent.CONNECTION_FAILED)
            emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId,"Connection failed")
            cleanup()
        }
    }

    private fun initializePeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            enableDscp = true
            iceCandidatePoolSize = 0
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            enableCpuOveruseDetection = true
            audioJitterBufferMaxPackets = 20
            audioJitterBufferFastAccelerate = true
            if (getPreference(PREF_STEALTH_MODE, context) == "true") {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
                debugLine("initializePeerConnection", "Stealth mode ON — forcing RELAY-only ICE")
            }
        }

        connection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                    debugLine("PeerConnection.Observer", "ICE Connection State Changed: $iceConnectionState")
                    val newIceState: IceConnectionState = when (iceConnectionState) {
                        PeerConnection.IceConnectionState.CONNECTED -> IceConnectionState.CONNECTED
                        PeerConnection.IceConnectionState.DISCONNECTED -> IceConnectionState.DISCONNECTED
                        PeerConnection.IceConnectionState.FAILED -> IceConnectionState.FAILED
                        PeerConnection.IceConnectionState.NEW -> IceConnectionState.NEW
                        PeerConnection.IceConnectionState.CHECKING -> IceConnectionState.CHECKING
                        PeerConnection.IceConnectionState.COMPLETED -> IceConnectionState.COMPLETED
                        PeerConnection.IceConnectionState.CLOSED -> IceConnectionState.CLOSED
                        null -> IceConnectionState.NULL
                    }
                    this@RTCClient.iceConnectionState = newIceState
                }

                override fun onConnectionChange(connectionState: PeerConnection.PeerConnectionState?) {
                    debugLine("PeerConnection.Observer", "ICE Connection State Changed: $iceConnectionState")
                    val newPeerState: PeerConnectionState = when (connectionState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> PeerConnectionState.CONNECTED
                        PeerConnection.PeerConnectionState.DISCONNECTED -> PeerConnectionState.DISCONNECTED
                        PeerConnection.PeerConnectionState.FAILED -> PeerConnectionState.FAILED
                        PeerConnection.PeerConnectionState.NEW -> PeerConnectionState.NEW
                        PeerConnection.PeerConnectionState.CONNECTING -> PeerConnectionState.CONNECTING
                        PeerConnection.PeerConnectionState.CLOSED -> PeerConnectionState.CLOSED
                        null -> PeerConnectionState.NULL
                    }
                    this@RTCClient.peerConnectionState = newPeerState
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (isClosing || cleanedUp) {
                        debugLine("PeerConnection.Observer", "Cleanup in progress, not sending candidate")
                        return
                    }

                    candidate?.let {
                        val can = Candidate(sdp = it.sdp, sdpMLineIndex = it.sdpMLineIndex, sdpMid = it.sdpMid)
                        sendSignal(SignalingMessage("candidate", candidate = can))
                    } ?: run {
                        debugLine("PeerConnection.Observer", "End of ICE candidates.")
                    }
                }

                override fun onDataChannel(dataChannel: DataChannel?) {
                    debugLine("PeerConnection.Observer", "[onDataChannel] Callback triggered. IsInitiator: $isInitiator, Received channel label: ${dataChannel?.label()}")

                    if (dataChannel == null) {
                        debugLine("PeerConnection.Observer", "[onDataChannel] Received null data channel in callback. Ignoring.")
                        return
                    }

                    if (!isInitiator) {
                        debugLine("PeerConnection.Observer", "[onDataChannel] Acting as Receiver: Assigning received data channel and registering observer.")
                        this@RTCClient.dataChannel = dataChannel

                        this@RTCClient.dataChannel?.registerObserver(object : DataChannel.Observer {

                            override fun onStateChange() {

                                val currentChannel = this@RTCClient.dataChannel
                                val webRtcState = currentChannel?.state()
                                val newState = webRtcState?.let { DataChannelState.fromWebRtcState(it) } ?: DataChannelState.CLOSED

                                debugLine("DataChannel [Received]", "State changed to: $newState (WebRTC state: $webRtcState)")
                                _dataChannelState.value = newState

                                clientScope.launch {
                                    val wasData = isData
                                    isData = newState == DataChannelState.OPEN
                                    isIceFault = wasData && !isData

                                    if (isData) {
                                        debugLine("DataChannel [Received]", "Channel is now OPEN.")
                                        emitWebRtcControlEvent(CallEvent.DATA_CHANNEL_OPEN, remoteUserId, "Data channel is Open")
                                        try {
                                            val notificationManager = App.context().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                            notificationManager.cancel(remoteUserId.hashCode())
                                        } catch (ex: Exception) {
                                            debugLine("DataChannel [Received]", "Failed to cancel notification: ${ex.message}")
                                        }
                                    }

                                    if (isIceFault) {
                                        debugLine("DataChannel [Received]", "Channel closed or failed, marking as Fault.")
                                        if (onlyData) {
                                            debugLine("DataChannel [Received]", "Fault in data-only mode. Cleaning up.")
                                            emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId, "Data channel (received) failed")
                                            cleanup()
                                        }
                                    }
                                }
                            }

                            override fun onMessage(buffer: DataChannel.Buffer) {
                                debugLine("DataChannel [Received]", "Message received on received channel.")
                                handleIncomingMessage(buffer)
                            }

                            override fun onBufferedAmountChange(previousAmount: Long) { }
                        })
                    }

                    else {
                        debugLine("PeerConnection.Observer", "[onDataChannel] Acting as Initiator: Ignoring received data channel (label: ${dataChannel.label()}). Using own created channel (label: ${this@RTCClient.dataChannel?.label()}).")
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    if (onlyData) {
                        debugLine("PeerConnection.Observer", "[onAddTrack] In data-only mode, ignoring added track.")
                        return
                    }

                    streams?.firstOrNull()?.let { stream ->
                        debugLine("PeerConnection.Observer", "[onAddTrack] Remote media stream received with ${stream.videoTracks.size} video tracks and ${stream.audioTracks.size} audio tracks.")
                        handleRemoteStream(stream)
                    } ?: run {
                        receiver?.track()?.let { track ->
                            debugLine("PeerConnection.Observer", "[onAddTrack] Remote track received (kind: ${track.kind()}), but not associated with a stream object in this callback.")
                        } ?: debugLine("PeerConnection.Observer", "[onAddTrack] Received null receiver and null streams.")
                    }
                }

                @Deprecated("Use onTrack instead")
                override fun onAddStream(stream: MediaStream?) {
                    if (onlyData) {
                        debugLine("PeerConnection.Observer", "[onAddStream] In data-only mode, ignoring added stream.")
                        return
                    }
                    stream?.let {
                        debugLine("PeerConnection.Observer", "[onAddStream] Remote media stream received (Deprecated callback).")
                        handleRemoteStream(it)
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    debugLine("PeerConnection.Observer", "[onRemoveStream] Remote stream removed (Deprecated callback).")
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    debugLine("PeerConnection.Observer", "[onIceCandidatesRemoved] Remote ICE candidates were removed.")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    debugLine("PeerConnection.Observer", "[onIceConnectionReceivingChange] Receiving status changed: $receiving")
                }

                override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {
                    debugLine("PeerConnection.Observer", "[onIceGatheringChange] ICE Gathering State: $iceGatheringState")
                    if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                        if (!iceGatheringCompleteDeferred.isCompleted) {
                            iceGatheringCompleteDeferred.complete(Unit)
                        }
                    }
                }

                override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                    debugLine("PeerConnection.Observer", "[onSignalingChange] Signaling State: $signalingState")
                }

                override fun onRenegotiationNeeded() {
                    debugLine("PeerConnection.Observer", "[onRenegotiationNeeded] Renegotiation needed.")
                }
            }
        )

        if (connection == null) {
            debugLine("initializePeerConnection", "FATAL: peerConnectionFactory?.createPeerConnection returned null!")
              clientScope.launch {
                 emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId, "Failed to create PeerConnection object.")
                 cleanup()
             }
        } else {
            debugLine("initializePeerConnection", "PeerConnection object created successfully.")
            ConnectionManager.instance.peerConnection = connection
        }
    }

    private fun setupLocalMediaStream(video: Boolean) {
        val audioConstraints = MediaConstraints()

        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        val audioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)

        if (audioTrack != null) {
            this.localAudioTrack = audioTrack
            this.localAudioTrack?.setEnabled(false)

            connection?.addTrack(audioTrack, listOf("ARDAMS"))

            debugLine("RTCClient", "Audio track added successfully")
        } else {
            debugLine("RTCClient", "Audio track creation failed")
        }

        if (video) {
            debugLine("RTCClient", "Creating video capturer")
            videoCapturer = createCameraVideoCapturer(context)

            if (videoCapturer == null) {
                debugLine("RTCClient", "Video capturer is null")
                localMediaSetupDeferred.completeExceptionally(Exception("Video capturer is null"))
                return
            } else {
                debugLine("RTCClient", "Video capturer created")
            }

            videoCapturer?.let { capturer ->
                val videoSource = peerConnectionFactory?.createVideoSource(capturer.isScreencast)

                if (videoSource == null) {
                    debugLine("RTCClient", "Video source creation failed")
                    localMediaSetupDeferred.completeExceptionally(Exception("Video source creation failed"))
                    return
                } else {
                    debugLine("RTCClient", "Video source created")
                }

                val originalObserver = videoSource.capturerObserver

                val customObserver = object : CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) {
                        originalObserver.onCapturerStarted(success)
                        debugLine("RTCClient", "Video capturer reported started: $success")
                    }
                    override fun onFrameCaptured(frame: VideoFrame) {
                        originalObserver.onFrameCaptured(frame)
                    }
                    override fun onCapturerStopped() {
                        debugLine("RTCClient", "Video capturer reported stopped")
                        originalObserver.onCapturerStopped()
                    }
                }

                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)
                if (surfaceTextureHelper == null) {
                    debugLine("RTCClient", "Surface texture helper creation failed")
                    localMediaSetupDeferred.completeExceptionally(Exception("Surface texture helper creation failed"))
                    return
                }
                try {
                    capturer.initialize(surfaceTextureHelper, context, customObserver)
                    debugLine("RTCClient", "Video capturer initialized")
                } catch (e: Exception) {
                    debugLine("RTCClient", "Error initializing video capturer: ${e.message}")
                }

                try {
                    if (isLowEndDevice()) {
                        capturer.startCapture(480, 270, 15)
                        debugLine("RTCClient", "Started video capture in low-end mode")
                    } else {
                        capturer.startCapture(1280, 720, 30)
                        debugLine("RTCClient", "Started video capture in high-quality mode")
                    }
                } catch (e: Exception) {
                    debugLine("RTCClient", "Error starting video capture: ${e.message}")
                }

                localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)

                if (localVideoTrack != null) {
                    localVideoTrack?.setEnabled(true)
                    connection?.addTrack(localVideoTrack, listOf("ARDAMS"))
                    debugLine("RTCClient", "Video track created successfully")
                } else {
                    debugLine("RTCClient", "Video track creation failed")
                }

                if (!localMediaSetupDeferred.isCompleted) {
                    localMediaSetupDeferred.complete(Unit)
                    debugLine("RTCClient", "Local media setup deferred completed after video track addition")
                }
            }
        } else {
            if (!localMediaSetupDeferred.isCompleted) {
                localMediaSetupDeferred.complete(Unit)
                debugLine("RTCClient", "Local media setup deferred completed for audio only")
            }
        }
    }

    fun refreshLocalVideo() {
        if (localVideoTrack != null) {
            clientScope.launch {
                try {
                    debugLine("RTCClient", "Refreshing video in cold start mode")
                    videoCapturer?.let { capturer ->
                        runCatching { capturer.stopCapture() }
                            .onFailure { error -> debugLine("RTCClient", "Error stopping capture: $error") }

                        delay(100)

                        runCatching { capturer.startCapture(1280, 720, 30) }
                            .onFailure { error -> debugLine("RTCClient", "Error restarting capture: $error") }
                    }

                    localVideoTrack?.setEnabled(false)
                    delay(50)
                    localVideoTrack?.setEnabled(true)

                    debugLine("RTCClient", "Video refresh completed")
                } catch (e: Exception) {
                    debugLine("RTCClient", "Error refreshing video: ${e.message}")
                }
            }
        }
    }

    private fun createCameraVideoCapturer(context: Context): CameraVideoCapturer? {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }

        if (enumerator.deviceNames.isEmpty()) {
            debugLine("RTCClient", "No cameras found")
            return null
        }

        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer is CameraVideoCapturer) {
                    debugLine("RTCClient", "Front camera found")
                    return capturer
                }
            }
        }

        for (deviceName in enumerator.deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer is CameraVideoCapturer) {
                    debugLine("RTCClient", "Back camera found")
                    return capturer
                }
            }
        }
        debugLine("RTCClient", "No cameras found")
        return null
    }

    fun switchCamera(callback: (isFrontCamera: Boolean) -> Unit) {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                debugLine("RTCClient", "Camera switch done, isFrontCamera: $isFrontCamera")
                callback(isFrontCamera)
            }
            override fun onCameraSwitchError(error: String?) {
                debugLine("RTCClient", "Camera switch error: $error")
            }
        })
    }

    private fun processQueuedCandidates() {
        clientScope.launch {
            candidateMutex.withLock {
                if (candidateQueue.isNotEmpty()) {
                    debugLine("processQueuedCandidates", "Processing ${candidateQueue.size} queued candidates")

                    val successfullyAdded = mutableListOf<Candidate>()

                    candidateQueue.forEach { candidate ->
                        val iceCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                        val added = connection?.addIceCandidate(iceCandidate) ?: false

                        if (added) {
                            debugLine("processQueuedCandidates", "Queued candidate added")
                            successfullyAdded.add(candidate)
                        } else {
                            debugLine("processQueuedCandidates", "Failed to add queued candidate")
                        }
                    }
                    candidateQueue.removeAll(successfullyAdded)
                }
            }
        }
    }

    private fun applyHardwareEncoderConstraints() {
        if(onlyData) {
            debugLine("RTCClient", "Skipping hardware encoder constraints in data-only mode")
            return
        }

        if (isLowEndDevice()) {
            connection?.let { conn ->
                val params = Bundle()
                params.putInt("x-google-max-quantization-parameter", 45)
                params.putInt("x-google-min-bitrate", 300)
                params.putInt("x-google-target-bitrate", 500)
                params.putInt("x-google-cpu-used", 8)

                try {
                    conn.setLocalDescription(
                        object : DefaultSdpObserver() {},
                        SessionDescription(conn.localDescription.type, conn.localDescription.description)
                    )
                    debugLine("RTCClient", "Applied hardware encoder constraints for older device")
                } catch (e: Exception) {
                    debugLine("RTCClient", "Failed to apply encoder constraints: ${e.message}")
                }
            }
        }
    }

    private fun setupBitrateAdaptation() {
        connection?.let { conn ->
            conn.senders.forEach { sender ->
                when (sender.track()?.kind()) {
                    "audio" -> {
                        try {
                            conn.setAudioPlayout(true)
                            conn.setAudioRecording(true)

                            try {
                                val parameters = sender.parameters
                                if (parameters.encodings.isNotEmpty()) {
                                    parameters.encodings[0].maxBitrateBps = 64000
                                    parameters.encodings[0].minBitrateBps = 16000
                                    sender.parameters = parameters
                                    debugLine("RTCClient", "Applied audio bitrate adaptation via parameters")
                                }
                            } catch (e: Exception) {
                                debugLine("RTCClient", "Cannot modify audio parameters directly: ${e.message}")
                            }
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error configuring audio: ${e.message}")
                        }
                    }
                    "video" -> {
                        try {
                            try {
                                val parameters = sender.parameters
                                if (parameters.encodings.isNotEmpty()) {
                                    parameters.encodings[0].maxBitrateBps = 2500000
                                    parameters.encodings[0].minBitrateBps = 100000
                                    sender.parameters = parameters
                                    debugLine("RTCClient", "Applied video bitrate adaptation via parameters")
                                }
                            } catch (e: Exception) {
                                debugLine("RTCClient", "Cannot modify video parameters directly: ${e.message}")
                            }
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error configuring video: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun setupDataChannel() {
        if (connection == null) {
            debugLine("SetupDataChannel", "PeerConnection is null, cannot create DataChannel.")
            return
        }

        if (this.dataChannel != null && this.dataChannel?.label() == "dataChannel") {
            debugLine("SetupDataChannel", "DataChannel 'dataChannel' already exists.")
            return
        }

        val init = DataChannel.Init().apply {
            ordered = false
        }

        this.dataChannel = connection?.createDataChannel("dataChannel", init)

        this.dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                val newState = this@RTCClient.dataChannel?.state()?.let { DataChannelState.fromWebRtcState(it) } ?: DataChannelState.CLOSED
                clientScope.launch {
                    _dataChannelState.value = newState
                    debugLine("DataChannel (Created)", "State changed to: $newState. Current state: ${this@RTCClient.dataChannel?.state()}")

                    val wasData = isData
                    isData = newState == DataChannelState.OPEN
                    isIceFault = wasData && !isData

                    if (isIceFault && onlyData) {
                        debugLine("DataChannel (Created)", "Fault detected in data-only mode, cleaning up.")
                        emitWebRtcControlEvent(CallEvent.CONNECTION_FAILED, remoteUserId, "Data channel (created) failed")
                        cleanup()
                    }

                    if(isData) {
                        emitWebRtcControlEvent(CallEvent.DATA_CHANNEL_OPEN, remoteUserId, "Data channel is Open")
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                debugLine("DataChannel (Created)", "Message received (unexpected here if receiver uses onDataChannel)")
                handleIncomingMessage(buffer)
            }

            override fun onBufferedAmountChange(previousAmount: Long) {
                debugLine("DataChannel (Created)", "Buffered amount changed: ${this@RTCClient.dataChannel?.bufferedAmount()}")
            }
        })

        if (this.dataChannel == null) {
            debugLine("SetupDataChannel", "Failed to create DataChannel.")
        } else {
            debugLine("SetupDataChannel", "DataChannel created successfully by initiator.")
        }
    }

    private fun handleIncomingMessage(buffer: DataChannel.Buffer) {
        val data = buffer.data
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        val receivedMessage = String(bytes, Charsets.UTF_8)

        MessageProcessor.enqueueMessage(remoteUserId, receivedMessage)
    }

    suspend fun sendData(data: ByteArray): Boolean {
        try {
            return dataChannel?.let { channel ->
                var stateAttempts = 0
                while (channel.state() == DataChannel.State.CONNECTING && stateAttempts < 10) {
                    debugLine("sendData", "Channel is CONNECTING, waiting for OPEN... attempt $stateAttempts")
                    delay(100)
                    stateAttempts++
                }

                if (channel.state() == DataChannel.State.OPEN) {
                    val dataToSend = java.nio.ByteBuffer.wrap(data)

                    var adaptiveDelay = 15L
                    var attempts = 0


                    val maxBufferSize = 200000

                    val bufferTimeout = 15000L
                    val startTime = System.currentTimeMillis()
                    // -----------------------------

                    debugLine("sendData", "Initial buffered amount: ${channel.bufferedAmount()}")

                    if (channel.send(DataChannel.Buffer(dataToSend, true))) {
                        debugLine("sendData", "Buffered amount after sending: ${channel.bufferedAmount()}")

                        while (channel.bufferedAmount() > maxBufferSize) {

                            if (System.currentTimeMillis() - startTime > bufferTimeout) {
                                debugLine("sendData", "Buffer TIMEOUT. Amount: ${channel.bufferedAmount()}. Aborting to save connection.")
                                isIceFault = true
                                return@let false
                            }

                            adaptiveDelay = (adaptiveDelay + 15).coerceAtMost(150)
                            debugLine("sendData", "Buffered amount: ${channel.bufferedAmount()}, delaying: $adaptiveDelay ms, attempt: $attempts")
                            delay(adaptiveDelay)
                            attempts++
                        }

                        debugLine("sendData", "Data sent successfully after $attempts attempts with final buffered amount: ${channel.bufferedAmount()}")
                        true
                    } else {
                        debugLine("sendData", "Send failed")
                        isIceFault = true
                        false
                    }
                } else {
                    debugLine("sendData", "Cannot send data, data channel is: ${channel.state()}")
                    isIceFault = true
                    false
                }
            } ?: run {
                debugLine("sendData", "Data channel is null")
                isIceFault = true
                false
            }
        } catch (ex: Exception) {
            debugLine("sendData", "Send Data failed with Exception: ${ex.message}")
            return false
        }
    }

    private val cleanupMutex = Mutex()

    fun cleanup(forced: Boolean = false) {
        if (cleanedUp || isClosing) return

        clientScope.launch {
            cleanupMutex.withLock {
                if (cleanedUp) return@withLock

                withContext(NonCancellable) {
                    isClosing = forced
                    reconnectingUiJob?.cancel()
                    reconnectingUiJob = null

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            deleteSignalingChannel(channelId)
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error deleting channel asynchronously: ${e.message}")
                        }
                    }

                    try {
                        context.unregisterReceiver(shutdownReceiver)
                    } catch (e: Exception) {
                        debugLine("RTCClient", "Error unregistering shutdown receiver: ${e.message}")
                    }

                    try{
                        socket = closeSignal(socket)
                    } catch (e: Exception) {
                        debugLine("RTCClient", "Error closing socket: ${e.message}")
                    }

                    dataChannel?.let {
                        it.unregisterObserver()
                        it.dispose()
                        debugLine("RTCClient", "Data channel unregistered and disposed")
                    }
                    dataChannel = null
                    debugLine("RTCClient", "Data channel cleared (set to NULL)")

                    try {
                        videoCapturer?.let { capturer ->
                            try {
                                capturer.stopCapture()
                                delay(100)
                            } catch (e: Exception) {
                                debugLine("RTCClient", "Error stopping capture: ${e.message}")
                            }
                            try {
                                capturer.dispose()
                            } catch (e: Exception) {
                                debugLine("RTCClient", "Error disposing capturer: ${e.message}")
                            }
                        }
                        videoCapturer = null

                        try {
                            localVideoTrack?.setEnabled(false)
                            localVideoTrack?.dispose()
                            localVideoTrack = null
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error disposing video track: ${e.message}")
                        }

                        try {
                            localAudioTrack?.setEnabled(false)
                            localAudioTrack?.dispose()
                            localAudioTrack = null
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error disposing audio track: ${e.message}")
                        }

                        try {
                            surfaceTextureHelper?.dispose()
                            surfaceTextureHelper = null
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error disposing texture helper: ${e.message}")
                        }

                        try {
                            connection?.dispose()
                            debugLine("RTCClient", "Disposed connection")
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error disposing connection: ${e.message}")
                        }

                        connection = null
                        ConnectionManager.instance.peerConnection = null

                        try {
                            debugLine("RTCClient", "Releasing AudioDeviceModule")
                            audioDeviceModule?.release()
                            audioDeviceModule = null
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error releasing AudioDeviceModule: ${e.message}")
                        }

                        try {
                            peerConnectionFactory?.dispose()
                            debugLine("RTCClient", "Disposed factory")
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error disposing factory: ${e.message}")
                        }
                        peerConnectionFactory = null

                        try {
                            eglBase?.release()
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error releasing EGL base: ${e.message}")
                        }
                        eglBase = null

                        processedCandidates.clear()
                        candidateQueue.clear()

                        try {
                            if (!clientJob.isCancelled) {
                                clientJob.cancel()
                            }
                        } catch (e: Exception) {
                            debugLine("RTCClient", "Error cancelling client job: ${e.message}")
                        }

                        debugLine("RTCClient", "Cleanup completed")
                        ConnectionManager.instance.rtcClient = null
                        cleanedUp = true
                    } catch (e: Exception) {
                        debugLine("RTCClient", "Error during cleanup: ${e.message}")
                    }
                }
            }
        }
    }

    private inner class ShutdownReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val broadcastUserId = intent?.getStringExtra("userId")

            if (intent?.action == ACTION_WEBRTC_SHUTDOWN && !broadcastUserId.isNullOrEmpty() && broadcastUserId == this@RTCClient.remoteUserId) {
                debugLine("RTCClient", "Received force shutdown broadcast. Cleaning up.")
                clientScope.launch {
                    emitWebRtcControlEvent(CallEvent.WEBRTC_SHUTDOWN, remoteUserId,"Requested RTC shutdown")
                    cleanup(forced = true)
                }
            }
        }
    }
}