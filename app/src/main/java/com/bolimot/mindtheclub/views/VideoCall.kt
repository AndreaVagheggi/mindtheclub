package com.bolimot.mindtheclub.views

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.DisconnectCause
import android.util.Rational
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.cardview.widget.CardView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.isLowEndDevice
import com.bolimot.mindtheclub.functions.wakeUpPhone
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Broadcast
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bolimot.mindtheclub.voip.ManagedTelecom.setSpeakerphone
import com.bolimot.mindtheclub.voip.sendCallEventToPeer
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.webrtc.RTCClient
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import android.media.AudioManager
import android.media.ToneGenerator
import com.bumptech.glide.load.engine.DiskCacheStrategy

class VideoCall : BaseActivity() {
    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var localVideoCard: CardView
    private lateinit var hangUp: ImageButton
    private lateinit var cameraOnOff: ImageButton
    private lateinit var onHoldOnOff: ImageButton
    private lateinit var cameraSwitch: ImageButton
    private lateinit var bottomControlsContainer: LinearLayout
    private lateinit var btnPip: ImageButton
    private lateinit var speaker: ImageButton
    private lateinit var connectingText: TextView
    private lateinit var profilePic: ShapeableImageView
    private lateinit var background: ShapeableImageView
    private lateinit var rtcClient: RTCClient

    private var isCaller: Boolean = false
    private var speakerOn: Boolean = true
    private var cameraOn: Boolean = true
    private var videoCallScreenActive: Boolean = false
    private var terminateOnEnd: Boolean = false
    private var connectionManager = ConnectionManager.instance
    private var hideToolbarRunnable: Runnable? = null
    private var isToolbarVisible = true
    private var isInPipMode = false
    private var exitingPipMode = false
    private var remoteUserId: String? = null
    private var callId: String? = null


    private val peerViewModel = getPeerViewModel()
    private val hideToolbarHandler = Handler(Looper.getMainLooper())
    private val toolbarHideDelay = 5000L
    private val tag = "VideoCall"
    private var reconnectToneGenerator: ToneGenerator? = null
    private var reconnectBeepHandler = Handler(Looper.getMainLooper())
    private var reconnectBeepRunnable: Runnable? = null


    private fun startReconnectBeep() {
        if (reconnectToneGenerator != null) return
        try {
            reconnectToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
            reconnectBeepRunnable = object : Runnable {
                override fun run() {
                    reconnectToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    reconnectBeepHandler.postDelayed(this, 3000)
                }
            }
            reconnectBeepRunnable?.run()
        } catch (e: Exception) {
            debugLine(tag, "Failed to start reconnect beep: ${e.message}")
        }
    }

    private fun stopReconnectBeep() {
        reconnectBeepRunnable?.let { reconnectBeepHandler.removeCallbacks(it) }
        reconnectBeepRunnable = null
        try {
            reconnectToneGenerator?.stopTone()
            reconnectToneGenerator?.release()
        } catch (_: Exception) { }
        reconnectToneGenerator = null
    }

    override fun onDestroy() {
        hideToolbarHandler.removeCallbacksAndMessages(null)
        stopReconnectBeep()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        wakeUpPhone()

        super.onCreate(savedInstanceState)

        remoteUserId = intent.getStringExtra("remoteUserId")
        callId = intent.getStringExtra("callId") ?: ""

        isCaller = intent.getBooleanExtra("isCaller", false)
        terminateOnEnd = intent.getBooleanExtra("terminateOnEnd", false)

        if(remoteUserId == null) {
            debugLine(tag, "Remote user ID is null, cannot start call screen. Finishing.")
            ManagedTelecom.disconnectCall(callId!!, DisconnectCause(DisconnectCause.LOCAL))
            return
        }

        debugLine(tag, "Starting Video Call with terminateOnEnd=$terminateOnEnd")

        setFullScreen()

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { }
        }
        onBackPressedDispatcher.addCallback(this, callback)

        setContentView(R.layout.video_call)

        remoteVideoView = findViewById(R.id.remote_video_view)
        localVideoView = findViewById(R.id.local_video_view)
        localVideoCard = findViewById(R.id.local_video_card)
        hangUp = findViewById(R.id.fab_close)
        cameraSwitch = findViewById(R.id.camera_front_rear)
        cameraOnOff = findViewById(R.id.camera_on_off)
        onHoldOnOff = findViewById(R.id.on_hold_on_off)
        bottomControlsContainer = findViewById(R.id.bottom_controls_container)
        btnPip = findViewById(R.id.pip_mode)
        speaker = findViewById(R.id.speaker_on_off)
        connectingText = findViewById(R.id.connecting_text)
        background = findViewById(R.id.background)
        profilePic = findViewById(R.id.profilePic)

        lifecycleScope.launch {
            val remotePeer = peerViewModel.getPeer(remoteUserId!!)
            remotePeer?.picture?.let { pictureUrl ->
                withContext(Dispatchers.Main) {
                    Glide.with(this@VideoCall)
                        .load(pictureUrl)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(profilePic)
                }
            }
        }

        setupButtonListeners()
        observeWebRTCConnectionState()
        observeRemoteVideoState()
        observeHoldState()
    }

    private fun observeRemoteVideoState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionManager.instance.remoteVideoState.collect { isPaused ->
                    if (isPaused) {
                        remoteVideoView.visibility = View.GONE
                        profilePic.visibility = View.VISIBLE
                        background.visibility = View.VISIBLE
                    } else {
                        remoteVideoView.visibility = View.VISIBLE

                        if (videoCallScreenActive) {
                            profilePic.visibility = View.GONE
                            background.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun observeWebRTCConnectionState() {
        // Listen for Telecom and WebRTC States changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ManagedTelecom.currentCall.collectLatest { callSession ->

                    // Disconnect Event
                    if (callSession == null || callSession.disconnectCause != null) {
                        debugLine(tag, "State update: Call Disconnected or Failed: callSession = $callSession")
                        finishVideoCall()
                        return@collectLatest
                    }

                    // Determine if the media stream itself is ready
                    val isWebRTCConnected = callSession.webRTCConnectionState == ManagedTelecom.WebRTCConnectionState.CONNECTED
                    val isReconnecting = callSession.webRTCConnectionState == ManagedTelecom.WebRTCConnectionState.RECONNECTING
                    val isLive = callSession.isConnected && !callSession.isHeld && isWebRTCConnected

                    debugLine(
                        tag, "State update received: " +
                                "isConnected=${callSession.isConnected}, " +
                                "isHeld=${callSession.isHeld}, " +
                                "mediaState=${callSession.webRTCConnectionState}. " +
                                "UI Decision: isLive=${isLive}")

                    // Reconnecting overlay
                    val reconnectingOverlay: View = findViewById(R.id.reconnecting_overlay)
                    if (isReconnecting && videoCallScreenActive) {
                        reconnectingOverlay.visibility = View.VISIBLE
                        startReconnectBeep()
                    } else {
                        reconnectingOverlay.visibility = View.GONE
                        stopReconnectBeep()
                    }

                    // Connect Event
                    if (isLive && !videoCallScreenActive) {
                        // WebRTC is connected, Telecom framework is connected, I can show the VideoCall rendering screen
                        val rtcClient = ConnectionManager.instance.rtcClient

                        if(rtcClient != null) {
                            this@VideoCall.rtcClient = rtcClient
                            setupButtonListeners()
                            videoCallScreenSetup(rtcClient)
                        } else {
                            debugLine(tag, "WTF! Connection is Live but RTC Client is null, cannot setup video call screen")
                        }
                    }
                }
            }
        }
    }

    private fun setupButtonListeners() {
        hangUp.setOnClickListener {
            lifecycleScope.launch {
                debugLine(tag, "Closing the call, (hungup)")
                callId?.let {
                    withTimeoutOrNull(3000L) {
                        ManagedTelecom.disconnectCall(it, DisconnectCause(DisconnectCause.LOCAL)).join()
                    }
                }
            }
        }

        cameraSwitch.setOnClickListener {
            startHideToolbarTimer()
            rtcClient.switchCamera { isFrontCamera ->
                runOnUiThread {
                    val newIconRes = if (isFrontCamera) R.drawable.camera_front else R.drawable.camera_rear
                    cameraSwitch.setImageResource(newIconRes)
                }
            }
        }

        btnPip.setOnClickListener {
            startHideToolbarTimer()
            enterPipMode()
        }

        cameraOnOff.setOnClickListener {
            startHideToolbarTimer()

            cameraOn = !cameraOn

            rtcClient.setLocalVideoEnabled(cameraOn)

            signalRemotePeer(cameraOn)

            val iconRes = if (cameraOn) R.drawable.video_on else R.drawable.video_off
            cameraOnOff.setImageResource(iconRes)
        }

        speaker.setOnClickListener {
            startHideToolbarTimer()

            speakerOn = !speakerOn

            setSpeakerphone(speakerOn)

            val iconRes = if (speakerOn) R.drawable.speaker_on else R.drawable.speaker_off
            speaker.setImageResource(iconRes)
        }

        onHoldOnOff.setOnClickListener {
            ManagedTelecom.toggleHold()
        }
    }

    private fun signalRemotePeer(videoEnabled: Boolean) {
        lifecycleScope.launch {
            val videoState = if(videoEnabled) CallEvent.VIDEO_ON else CallEvent.VIDEO_OFF
            sendCallEventToPeer(remoteUserId!!, videoState, callId)
        }
    }

    private suspend fun waitForEglContext(
        rtcClient: RTCClient,
        timeoutMs: Long = 3000L,
        checkIntervalMs: Long = 100L
    ): EglBase.Context? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            rtcClient.eglContext?.let { context ->
                debugLine("VideoCall", "EGL context became available after ${System.currentTimeMillis() - startTime}ms")
                return context
            }

            debugLine("VideoCall", "EGL context not ready, waiting... (${System.currentTimeMillis() - startTime}ms elapsed)")
            delay(checkIntervalMs)
        }

        debugLine("VideoCall", "EGL context timeout after ${timeoutMs}ms")
        return null
    }

    private suspend fun videoCallScreenSetup(rtcClient: RTCClient) {

        val eglContext = waitForEglContext(rtcClient) ?: run {
            debugLine("VideoCall", "EGL context is null")

            ManagedTelecom.disconnectCall(callId!!, DisconnectCause(DisconnectCause.LOCAL))
            return
        }

        debugLine("VideoCall", "Setting up Video Call Screen")

        remoteVideoView.visibility = View.VISIBLE
        localVideoView.visibility = View.VISIBLE
        localVideoCard.visibility = View.VISIBLE
        cameraSwitch.visibility = View.VISIBLE
        bottomControlsContainer.visibility = View.VISIBLE

        connectingText.visibility = View.GONE
        profilePic.visibility = View.GONE
        background.visibility = View.GONE

        val scalingMode = if (isLowEndDevice()) {
            RendererCommon.ScalingType.SCALE_ASPECT_FIT
        } else {
            RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
        }

        remoteVideoView.setScalingType(scalingMode)

        remoteVideoView.init(eglContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {}
        })

        remoteVideoView.setMirror(false)

        localVideoView.init(eglContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {}
        })

        localVideoView.setMirror(true)
        localVideoView.setZOrderMediaOverlay(true)

        rtcClient.remoteStreamEvent.observe(this, Observer { stream ->
            debugLine("VideoCall", "Remote stream received: $stream")
            if (stream.videoTracks.isNotEmpty()) {
                stream.videoTracks[0].addSink(remoteVideoView)
            } else {
                debugLine(
                    "VideoCall",
                    "Remote video stream has no video track, stream: $stream, tracks: ${stream.videoTracks}"
                )
            }
        })

        rtcClient.localVideoTrack?.let { localTrack ->
            localTrack.addSink(localVideoView)
            debugLine("VideoCall", "Local video sink attached.")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            rtcClient.refreshLocalVideo()
        }, 500)

        videoCallScreenActive = true
        rtcClient.setLocalAudioEnabled(true)

        setSpeakerphone(true)

        setupToolbarAutoHide()
    }

    private fun setFullScreen(){
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    private fun enterPipMode() {
        try {
            val aspectRatio = Rational(9, 16)
            val pipParams = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()

            debugLine("VideoCall", "Attempting to enter Picture-in-Picture mode.")
            connectionManager.isClosing = false
            enterPictureInPictureMode(pipParams)
        } catch (e: Exception) {
            debugLine("VideoCall", "Failed to enter PiP mode: ${e.message}")
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            debugLine("VideoCall", "Entering PiP mode - Hiding controls.")
            connectionManager.isClosing = false

            isInPipMode = true
            exitingPipMode = false

            cancelHideToolbarTimer()

            bottomControlsContainer.visibility = View.GONE  // Hide entire container
            localVideoCard.visibility = View.GONE
            localVideoView.visibility = View.GONE
        } else {
            debugLine("VideoCall", "Exiting PiP mode - Showing controls.")
            connectionManager.isClosing = true

            isInPipMode = false
            exitingPipMode = true

            bottomControlsContainer.visibility = View.VISIBLE  // Show entire container
            localVideoCard.visibility = View.VISIBLE
            localVideoView.visibility = View.VISIBLE

            isToolbarVisible = true
            startHideToolbarTimer()

            setFullScreen()
        }
    }

    private fun finishVideoCall(){
        if(!terminateOnEnd) {
            debugLine("VideoCall", "Finishing Video Call, it was not started only for video call, do not terminate App")
            finish()
        } else {
            debugLine("VideoCall", "Finishing Video Call and telling other activities to close, terminate App.")
            val intent = Intent(Broadcast.ACTION_FINISH_CALL)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            ChatScreen.shouldFinish = true

            finishAffinity()
        }
    }

    private fun setupToolbarAutoHide() {
        hideToolbarRunnable = Runnable {
            if (isToolbarVisible && videoCallScreenActive) {
                hideToolbarWithAnimation()
            }
        }

        startHideToolbarTimer()

        remoteVideoView.setOnClickListener {
            if (videoCallScreenActive) {
                if (isToolbarVisible) {
                    hideToolbarWithAnimation()
                    cancelHideToolbarTimer()
                } else {
                    showToolbarWithAnimation()
                    startHideToolbarTimer()
                }
            }
        }
    }

    private fun showToolbarWithAnimation() {
        if (!isToolbarVisible) {
            isToolbarVisible = true
            bottomControlsContainer.apply {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = height.toFloat()
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun hideToolbarWithAnimation() {
        if (isToolbarVisible) {
            isToolbarVisible = false
            bottomControlsContainer.animate()
                .alpha(0f)
                .translationY(bottomControlsContainer.height.toFloat())
                .setDuration(300)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    bottomControlsContainer.visibility = View.GONE
                }
                .start()
        }
    }

    private fun startHideToolbarTimer() {
        cancelHideToolbarTimer()
        hideToolbarRunnable?.let {
            hideToolbarHandler.postDelayed(it, toolbarHideDelay)
        }
    }

    private fun cancelHideToolbarTimer() {
        hideToolbarRunnable?.let {
            hideToolbarHandler.removeCallbacks(it)
        }
    }

    private fun observeHoldState() {
        // Assuming you have a View (like a FrameLayout with a TextView) to act as an overlay
        val onHoldOverlay: View = findViewById(R.id.your_on_hold_overlay_id)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // This combines the local hold state and remote hold state.
                // If either is true, the "On Hold" UI will be shown.
                ManagedTelecom.currentCall.combine(ManagedTelecom.isRemotelyHeld) { session, isRemoteHeld ->
                    session?.isHeld == true || isRemoteHeld
                }.collect { isOnHold ->
                    onHoldOverlay.visibility = if (isOnHold) View.VISIBLE else View.GONE
                }
            }
        }
    }
}