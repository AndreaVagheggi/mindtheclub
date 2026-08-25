package com.bolimot.mindtheclub.voip

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.telecom.DisconnectCause
import androidx.core.content.ContextCompat.getString
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerRepository
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Voip
import com.bolimot.mindtheclub.views.OutgoingCall
import com.bolimot.mindtheclub.views.VideoCall
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object ManagedTelecom {
    private const val TAG = "ManagedTelecom"

    data class PendingCall(
        val remoteUserId: String,
        val callId: String,
        val channelId: String,
        val isVideo: Boolean,
        val terminateOnEnd: Boolean,
        val timeoutJob: Job
    )
    val pendingCallFlow = MutableStateFlow<PendingCall?>(null)

    private var ringbackPlayer: ToneGenerator? = null
    private val ringbackLock = Any()

    @Volatile private lateinit var appContext: Context
    @Volatile private var registered = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class WebRTCConnectionState {
        PENDING, CONNECTED, RECONNECTING, FAILED, TIMEOUT
    }

    data class CallSession(
        val callId: String,
        val remoteUserId: String,
        val isVideo: Boolean,
        val isIncoming: Boolean,
        val remoteDisplayName: String?,
        internal val callControl: CallControlScope,
        val terminateOnEnd: Boolean = false,
        val isAnswered: Boolean = false,
        val isConnected: Boolean = false,
        val isHeld: Boolean = false,
        val webRTCConnectionState: WebRTCConnectionState = WebRTCConnectionState.PENDING,
        val disconnectCause: DisconnectCause? = null,
        internal val timeoutJob: Job? = null
        )

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    private val _currentEndpoint = MutableStateFlow<CallEndpointCompat?>(null)

    private val _availableEndpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())

    internal val isRemotelyHeldMutable = MutableStateFlow(false)
    val isRemotelyHeld = isRemotelyHeldMutable.asStateFlow()

    private var endpointObserverJob: Job? = null

    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            debugLine(TAG, "Registering callsManager with Telecom Framework.")

            val caps = CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING

            CallsManager(appContext).registerAppWithTelecom(caps)
            registered = true
        } catch(e: Exception) {
            debugLine(TAG, "init: Permission DENIED. Not registering with Telecom.")
            registered = false
        }
    }

    private inline fun <T> withCallsManager(block: (CallsManager) -> T): T {
        check(registered) { "ManagedTelecom.init(context) must be called first, or permission was denied at startup." }
        return block(CallsManager(appContext))
    }

    fun stopRingbackTone() {
        synchronized(ringbackLock) {
            if (ringbackPlayer != null) {
                debugLine(TAG, "Stopping ringback tone.")
                ringbackPlayer?.stopTone()
                ringbackPlayer?.release()
                ringbackPlayer = null
            }
        }
    }

    private inline fun withCurrentCall(
        callId: String,
        crossinline action: suspend (session: CallSession) -> Unit,
        crossinline fallback: suspend () -> Unit
    ): Job {
        return scope.launch {
            val session = _currentCall.value
            if (session != null && (session.callId == callId || session.callId == "")) {
                action(session)
            } else {
                fallback()
            }
        }
    }

    private fun getCallAttributesObject(
        remoteUserId: String,
        callId: String,
        isVideo: Boolean,
        incoming: Boolean,
        displayName: String?
    ): CallAttributesCompat {
        val uri = "${Voip.VOIP_SCHEME}:$remoteUserId/$callId".toUri()
        val callerLabel: CharSequence = displayName?.takeIf { it.isNotBlank() } ?: remoteUserId

        return CallAttributesCompat(
            displayName = callerLabel,
            address = uri,
            direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = if (isVideo) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE
        )
    }

    fun updateWebRTCConnectionState(callId: String, newState: WebRTCConnectionState) {
        _currentCall.update { session ->
            if (session?.callId == callId && session.webRTCConnectionState != newState) {
                when(newState) {
                    WebRTCConnectionState.CONNECTED -> debugLine(TAG, "WebRTC connection established")
                    WebRTCConnectionState.RECONNECTING -> debugLine(TAG, "WebRTC connection reconnecting")
                    WebRTCConnectionState.FAILED -> {
                        debugLine(TAG, "WebRTC connection failed")
                        disconnectCall(callId, DisconnectCause(DisconnectCause.LOCAL, "WebRTC connection failed"))
                    }
                    WebRTCConnectionState.PENDING -> debugLine(TAG, "WebRTC connection initiated (pending)")
                    WebRTCConnectionState.TIMEOUT -> {
                        debugLine(TAG, "WebRTC connection timed out")
                        disconnectCall(callId, DisconnectCause(DisconnectCause.REMOTE, "WebRTC connection timed out"))
                    }
                }
                val newSession = session.copy(webRTCConnectionState = newState)
                debugLogTelecomSession(newSession)
                newSession
            } else {
                session
            }
        }
    }

    suspend fun setCallActive(callId: String) {
        debugLine("ManagedTelecom", "Setting call active for callId: $callId")
        withCurrentCall(callId,
            action = { session ->
                debugLine("ManagedTelecom", "Call found, setting active in both OS and app state.")
                session.callControl.setActive()

                _currentCall.update { currentSession ->
                    currentSession?.copy(isConnected = true)
                }
            },
            fallback = {
                debugLine("ManagedTelecom", "setCallActive failed: Could not find call with id $callId")
            }
        )
    }

    fun disconnectCall(callId: String, cause: DisconnectCause): Job {
        return withCurrentCall(
            callId = callId,
            action = { session ->
                debugLine(TAG, "Initiating disconnection for call $callId with cause: ${cause.code}")

                stopRingbackTone()
                session.timeoutJob?.cancel()

                if(cause.code == DisconnectCause.MISSED && session.isIncoming){
                    saveMissedCallMessage(session.remoteUserId)
                }

                endpointObserverJob?.cancel()
                _currentEndpoint.value = null
                _availableEndpoints.value = emptyList()

                isRemotelyHeldMutable.value = false

                val newSession = session.copy(disconnectCause = cause)
                _currentCall.value = newSession
                debugLogTelecomSession(newSession)

                // Informing the remote peer that I'm closing the call
                val result = sendCallEventToPeer(session.remoteUserId, CallEvent.CLOSE, session.callId)

                debugLine(TAG, "Call event sent to remote peer: $result")

                // Closing and cleaning up the WebRTC connection
                ConnectionManager.instance.webRTCCleanUp(session.remoteUserId)

                // Closing the call
                session.callControl.disconnect(cause)

                _currentCall.value = null

                debugLine(TAG, "Call disconnected successfully")
            },
            fallback = {
                debugLine(TAG, "disconnectCall ignored: No active call session found for callId $callId")
            }
        )
    }

    suspend fun startOutgoingCall(
        remoteUserId: String,
        callId: String,
        isVideo: Boolean,
        context: Context
    ) {
        val tag = "startOutgoingCall"

        if(currentCall.value != null) {
            debugLine(tag, "There is already an active call, callId=${currentCall.value!!.callId}")
            showToast(context.getString(R.string.system_busy), context)
            return
        }

        val myUserId = MySelf.userId()

        if(myUserId == null)  {
            debugLine(tag, "I don't have a userId, I cannot place a call")
            return
        }

        val displayName = getPeerRepository(context).getPeer(remoteUserId)?.name

        try {
            synchronized(ringbackLock) {
                ringbackPlayer?.release()
                ringbackPlayer = null

                try {
                    ringbackPlayer = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                    ringbackPlayer?.startTone(ToneGenerator.TONE_SUP_RINGTONE, -1)
                    debugLine(tag, "Ringback tone started.")
                } catch (e: Exception) {
                    debugLine(tag, "Failed to create or start ToneGenerator: ${e.message}")
                    ringbackPlayer = null
                }
            }

            withCallsManager { callsManager ->
                callsManager.addCall(getCallAttributesObject(remoteUserId, callId, isVideo, incoming = false, displayName),
                    onAnswer = { _: Int -> /* the caller does not answer */},

                    onDisconnect = { cause: DisconnectCause ->
                        debugLine(tag, "[onDisconnect] Outgoing call disconnected by Telecom framework. Cause: $cause")

                        stopRingbackTone()

                        when (cause.code) {
                            DisconnectCause.LOCAL -> {
                                scope.launch(Dispatchers.IO) {
                                    sendCallEventToPeer(remoteUserId, CallEvent.CLOSE, callId) }
                            }
                            else -> {
                                scope.launch(Dispatchers.IO) {
                                    sendCallEventToPeer(remoteUserId, CallEvent.FAILED, callId) }
                            }
                        }
                        _currentCall.value?.let { session ->
                            val newSession = session.copy(disconnectCause = cause)
                            _currentCall.value = newSession

                            debugLogTelecomSession(newSession)

                            _currentCall.value = null

                            ConnectionManager.instance.webRTCCleanUp(session.remoteUserId)

                        } ?: debugLine(tag, "[onDisconnect] Session was already null. No cleanup needed.")
                    },

                    onSetActive = {
                        debugLine(tag, "onSetActive triggered")
                        _currentCall.update { session ->
                            val newSession = session?.copy(isConnected = true, isHeld = false)
                            debugLogTelecomSession(newSession)
                            newSession
                        }
                    },

                    onSetInactive = {
                        debugLine(tag, "onSetInactive triggered")
                        _currentCall.update { session ->
                            val newSession = session?.copy(isConnected = false, isHeld = true)
                            debugLogTelecomSession(newSession)
                            newSession
                        }
                    }
                ) {
                    // This is the call control scope
                    val newSession = CallSession(callId, remoteUserId, isVideo, false, displayName, this)
                    _currentCall.value = newSession
                    debugLogTelecomSession(newSession)

                    val callControlScope = this // Capture the correct scope

                    endpointObserverJob = scope.launch {
                        launch {
                            callControlScope.currentCallEndpoint.collect { endpoint ->
                                debugLine(tag, "Current audio endpoint updated to: ${endpoint.name}")
                                _currentEndpoint.value = endpoint
                            }
                        }
                        launch {
                            callControlScope.availableEndpoints.collect { endpoints ->
                                debugLine(tag, "Available endpoints list updated: ${endpoints.joinToString { it.name.toString() }}")
                                _availableEndpoints.value = endpoints
                            }
                        }
                    }

                    debugLine(tag, "Telecom call registered. Starting WebRTC connection.")

                    val channelId = guid()

                    debugLine(tag, "Outgoing call, created channelId: $channelId, for the callId: $callId")

                    // CHG! Devo informare qui il mio amico che sto chiamando??

                    val notificationType = if(isVideo) Notify.VIDEO_CALL else Notify.AUDIO_CALL

                    scope.launch {
                        fcmSendInstant(remoteUserId, channelId, callId, notificationType, notificationType)

                        ConnectionManager.instance.webRTCConnect(
                            channelId = channelId,
                            callId = callId,
                            remoteUserId = remoteUserId,
                            initiator = true,
                            context = context,
                            video = isVideo,
                            dataOnly = false
                        )
                    }

                    // A video call goes straight to its own screen, with the
                    // camera live while the other phone rings, instead of a
                    // dialling screen that hands over once they answer. It is
                    // how the group call behaves and how every other app does it.
                    //
                    // An audio call keeps the dialling screen: there is no
                    // picture to show early, so the change would be cosmetic, and
                    // this path is not worth disturbing for that.
                    val intent = if (isVideo) {
                        Intent(context, VideoCall::class.java).apply {
                            putExtra("remoteUserId", remoteUserId)
                            putExtra("callId", callId)
                            putExtra("isCaller", true)
                        }
                    } else {
                        OutgoingCall.getIntent(context, remoteUserId, isVideo, callId)
                    }.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (t: Throwable) {
            debugLine(tag, "addCall (outgoing) failed: ${t.message}")
        }
    }

    private suspend fun startIncomingCall(
        remoteUserId: String,
        callId: String,
        channelId: String,
        isVideo: Boolean,
        context: Context,
        terminateOnEnd: Boolean = false,
        autoAnswer: Boolean = false
    ) {
        val tag = "IncomingCall"
        debugLine(tag, "Attempting to start an incoming call, video=$isVideo. 'registered' is $registered")
        debugLine(tag, "Attempting to start an incoming call, terminateOnEnd=$terminateOnEnd")

        if (currentCall.value != null) {
            debugLine(tag, "There is already an active call, cannot have another one (incoming)")
            sendCallEventToPeer(remoteUserId, CallEvent.BUSY, callId)
            return
        }

        val remotePeer = getPeerRepository(context).getPeer(remoteUserId)
        val displayName = remotePeer?.name ?: getString(context, R.string.unknown)

        try {
            withCallsManager { callsManager ->
                callsManager.addCall(getCallAttributesObject(remoteUserId, callId, isVideo, true, displayName),

                    onAnswer = { _: Int ->
                        debugLine(tag, "Call answered via system UI. terminateOnEnd=$terminateOnEnd")
                        _currentCall.value?.let { session ->
                            handleAnswer(session, appContext, terminateOnEnd)
                        }
                    },

                    onDisconnect = { cause: DisconnectCause ->
                        debugLine(tag, "[onDisconnect] Incoming call disconnected by Telecom framework. Cause: $cause")

                        // Informing the remote user that I'm disconnecting
                        when (cause.code) {
                            DisconnectCause.REJECTED -> {
                                scope.launch(Dispatchers.IO) {
                                    sendCallEventToPeer(remoteUserId, CallEvent.REJECT, callId)
                                }
                            }
                            DisconnectCause.MISSED -> {
                                scope.launch(Dispatchers.IO) {
                                    sendCallEventToPeer(remoteUserId, CallEvent.NO_ANSWER, callId)

                                    // Signaling the missed call message in the chat
                                    saveMissedCallMessage(remoteUserId)
                                }
                            }
                            DisconnectCause.LOCAL -> {
                                scope.launch(Dispatchers.IO) {
                                    sendCallEventToPeer(remoteUserId, CallEvent.CLOSE, callId)
                                }
                            }
                            else -> {
                                scope.launch(Dispatchers.IO) {
                                    sendCallEventToPeer(remoteUserId, CallEvent.FAILED, callId)
                                }
                            }
                        }

                        _currentCall.value?.let { session ->
                            val newSession = session.copy(disconnectCause = cause)
                            _currentCall.value = newSession
                            debugLogTelecomSession(newSession)

                            // Closing the session
                            _currentCall.value = null

                            // Closing and cleaning up the WebRTC connection
                            ConnectionManager.instance.webRTCCleanUp(session.remoteUserId)
                        } ?: debugLine(tag, "[onDisconnect] Session was already null. No cleanup needed.")
                    },

                    onSetActive = {
                        debugLine(tag, "onSetActive triggered")
                        _currentCall.update { session ->
                            val newSession = session?.copy(isConnected = true, isHeld = false)
                            debugLogTelecomSession(newSession)
                            newSession
                        }
                    },

                    onSetInactive = {
                        debugLine(tag, "onSetInactive triggered")
                        _currentCall.update { session ->
                            val newSession = session?.copy(isConnected = false, isHeld = true)
                            debugLogTelecomSession(newSession)
                            newSession
                        }
                    }
                )
                {
                    debugLine(tag, "Telecom call registered with terminateOnEnd=$terminateOnEnd")

                    val timeoutJob = scope.launch {
                        delay(30000L)
                        val session = _currentCall.value
                        if (session != null && session.callId == callId && !session.isAnswered && session.disconnectCause == null) {
                            debugLine(tag, "Incoming call timed out ($callId). Disconnecting.")
                            disconnectCall(callId, DisconnectCause(DisconnectCause.MISSED))
                        }
                    }

                    val newSession = CallSession(callId,
                        remoteUserId,
                        isVideo,
                        true,
                        displayName,
                        this,
                        terminateOnEnd,
                        timeoutJob = timeoutJob)

                    _currentCall.value = newSession
                    debugLogTelecomSession(newSession)

                    if (autoAnswer) {
                        handleAnswer(newSession, context, terminateOnEnd)
                    }

                    val callControlScope = this

                    endpointObserverJob = scope.launch {
                        launch {
                            callControlScope.currentCallEndpoint.collect { endpoint ->
                                debugLine(tag, "Current audio endpoint updated to: ${endpoint.name}")
                                _currentEndpoint.value = endpoint
                            }
                        }
                        launch {
                            callControlScope.availableEndpoints.collect { endpoints ->
                                debugLine(tag, "Available endpoints list updated: ${endpoints.joinToString { it.name.toString() }}")
                                _availableEndpoints.value = endpoints
                            }
                        }
                    }

                    debugLine(tag, "Telecom call registered. Starting WebRTC connection.")
                    scope.launch {
                        ConnectionManager.instance.webRTCConnect(
                            channelId = channelId,
                            callId = callId,
                            remoteUserId = remoteUserId,
                            initiator = false,
                            context = context,
                            video = isVideo,
                            dataOnly = false
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            debugLine(tag, "addCall (incoming) failed with exception: ${t.message}")
        }
    }

    private fun handleAnswer(session: CallSession, context: Context, terminateOnEnd: Boolean = false) {
        session.timeoutJob?.cancel()

        scope.launch {
            val peer = getPeerRepository(context).getPeer(session.remoteUserId)
            if (peer != null) {
                debugLine(TAG, "I have accepted the call, launching Call Screen with terminateOnEnd=$terminateOnEnd")

                launchCallScreen(peer, session.isVideo, session.callId, context, terminateOnEnd)
                _currentCall.update { it?.copy(isAnswered = true) }

                debugLine(TAG, "Answering the call, setting the call active and informing the remote peer I have accepted the call")
                setCallActive(callId = session.callId)
                sendCallEventToPeer(session.remoteUserId, CallEvent.ACCEPT, session.callId)

            } else {
                debugLine(TAG, "Peer not found for callId: ${session.callId}, disconnecting.")
                disconnectCall(session.callId, DisconnectCause(DisconnectCause.LOCAL))
            }
        }
    }

    fun setSpeakerphone(isOn: Boolean) {
        val session = _currentCall.value
        if (session == null) {
            debugLine(TAG, "setSpeakerphone failed: No active call session.")
            return
        }

        val desiredEndpointType = if (isOn) {
            CallEndpointCompat.TYPE_SPEAKER
        } else {
            CallEndpointCompat.TYPE_EARPIECE
        }

        // Find the endpoint from our continuously updated list
        val desiredEndpoint = _availableEndpoints.value.find { it.type == desiredEndpointType }

        if (desiredEndpoint != null) {
            debugLine(TAG, "Endpoint found in cached list, requesting change to: ${desiredEndpoint.name}")
            // The request itself is a suspend function, so it needs a coroutine
            scope.launch {
                session.callControl.requestEndpointChange(desiredEndpoint)
            }
        } else {
            debugLine(TAG, "Failed to switch audio: Desired endpoint not found in the currently available list.")
        }
    }

    // Add this new function inside the ManagedTelecom object

    fun toggleHold() {
        scope.launch {
            val session = _currentCall.value ?: return@launch
            val rtcClient = ConnectionManager.instance.activeMediaRtcClient ?: return@launch

            val shouldHold = !session.isHeld

            if (shouldHold) {
                // --- Going ON HOLD ---
                debugLine(TAG, "Placing call on hold")
                session.callControl.setInactive() // 1. Tell the Telecom system
                _currentCall.update { it?.copy(isHeld = true) }

                rtcClient.setLocalAudioEnabled(false) // 2. Stop sending audio
                rtcClient.setLocalVideoEnabled(false) //    Stop sending video

                sendCallEventToPeer(session.remoteUserId, CallEvent.HELD, session.callId) // 3. Notify peer

            } else {
                // --- Going OFF HOLD ---
                debugLine(TAG, "Taking call off hold")
                session.callControl.setActive() // 1. Tell the Telecom system

                _currentCall.update { it?.copy(isHeld = false) }

                rtcClient.setLocalAudioEnabled(true) // 2. Resume sending audio
                rtcClient.setLocalVideoEnabled(true) //    Resume sending video

                sendCallEventToPeer(session.remoteUserId, CallEvent.UNHELD, session.callId) // 3. Notify peer
            }
        }
    }

    fun handlePendingCallTimeout(callId: String) {
        val pCall = pendingCallFlow.value
        if (pCall != null && pCall.callId == callId) {
            debugLine(TAG, "Pending call timed out: $callId")
            scope.launch(Dispatchers.IO) {
                sendCallEventToPeer(pCall.remoteUserId, CallEvent.NO_ANSWER, callId)
                saveMissedCallMessage(pCall.remoteUserId)
            }
            pendingCallFlow.value = null
        }
    }

    suspend fun acceptPendingCall(callId: String, context: Context) {
        val pCall = pendingCallFlow.value
        if (pCall != null && pCall.callId == callId) {
            debugLine(TAG, "Accepting pending call: $callId")
            pCall.timeoutJob.cancel()
            pendingCallFlow.value = null
            startIncomingCall(
                remoteUserId = pCall.remoteUserId, callId = pCall.callId, channelId = pCall.channelId,
                isVideo = pCall.isVideo, context = context, terminateOnEnd = pCall.terminateOnEnd,
                autoAnswer = true
            )
        } else {
            val session = _currentCall.value
            if (session?.callId == callId) handleAnswer(session, context, session.terminateOnEnd)
        }
    }

    fun declinePendingCall(callId: String) {
        val pCall = pendingCallFlow.value
        if (pCall != null && pCall.callId == callId) {
            debugLine(TAG, "Declining pending call: $callId")
            pCall.timeoutJob.cancel()
            pendingCallFlow.value = null
            scope.launch(Dispatchers.IO) { sendCallEventToPeer(pCall.remoteUserId, CallEvent.REJECT, callId) }
        } else disconnectCall(callId, DisconnectCause(DisconnectCause.REJECTED))
    }

    fun cancelPendingCall(callId: String, context: Context) {
        val pCall = pendingCallFlow.value
        if (pCall != null && pCall.callId == callId) {
            debugLine(TAG, "Remote cancelled pending call: $callId")
            pCall.timeoutJob.cancel()
            pendingCallFlow.value = null
            scope.launch(Dispatchers.IO) { saveMissedCallMessage(pCall.remoteUserId) }
            CallNotificationManager(context).dismissCallNotification()
        } else disconnectCall(callId, DisconnectCause(DisconnectCause.REMOTE))
    }
}