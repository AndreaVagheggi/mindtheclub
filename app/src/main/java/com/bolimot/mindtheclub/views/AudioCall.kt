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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.wakeUpPhone
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Broadcast
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.webrtc.RTCClient
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.media.AudioManager
import android.media.ToneGenerator
import com.bumptech.glide.load.engine.DiskCacheStrategy

class AudioCall : BaseActivity() {

    private lateinit var fabClose: ImageButton
    private lateinit var bottomControlsContainer: LinearLayout
    private lateinit var btnPip: ImageButton
    private lateinit var speaker: ImageButton
    private lateinit var onHoldOnOff: ImageButton
    private lateinit var connectingText: TextView
    private lateinit var profilePic: ShapeableImageView
    private lateinit var background: ConstraintLayout

    private var isCaller: Boolean = false
    private var speakerOn: Boolean = false
    private var audioCallScreenActive: Boolean = false
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
    private val tag = "AudioCall"
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

        debugLine(tag, "Starting Audio Call with terminateOnEnd=$terminateOnEnd")

        setFullScreen()

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { }
        }
        onBackPressedDispatcher.addCallback(this, callback)

        setContentView(R.layout.audio_call)

        fabClose = findViewById(R.id.fab_close)
        bottomControlsContainer = findViewById(R.id.bottom_controls_container)
        btnPip = findViewById(R.id.pip_mode)
        speaker = findViewById(R.id.speaker_on_off)
        connectingText = findViewById(R.id.connecting_text)
        profilePic = findViewById(R.id.profilePic)
        background = findViewById(R.id.container)
        onHoldOnOff = findViewById(R.id.on_hold_on_off)

        lifecycleScope.launch {
            val remotePeer = peerViewModel.getPeer(remoteUserId!!)
            remotePeer?.picture?.let { pictureUrl ->
                withContext(Dispatchers.Main) {
                    Glide.with(this@AudioCall)
                        .load(pictureUrl)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(profilePic)
                }
            }
        }

        setupButtonListeners()
        observeWebRTCConnectionState()
        observeHoldState()
    }

    private fun observeWebRTCConnectionState() {
        // Listen for Telecom and WebRTC States changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ManagedTelecom.currentCall.collectLatest { callSession ->

                    // Disconnect Event
                    if (callSession == null || callSession.disconnectCause != null) {
                        debugLine(tag, "State update: Call Disconnected or Failed: callSession = $callSession")
                        finishAudioCall()
                        return@collectLatest
                    }

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
                    if (isReconnecting && audioCallScreenActive) {
                        reconnectingOverlay.visibility = View.VISIBLE
                        startReconnectBeep()
                    } else {
                        reconnectingOverlay.visibility = View.GONE
                        stopReconnectBeep()
                    }

                    // Connect Event
                    if (isLive && !audioCallScreenActive) {
                        // WebRTC is connected, Telecom framework is connected, I can show the VideoCall rendering screen
                        val rtcClient = ConnectionManager.instance.rtcClient

                        if(rtcClient != null) {
                            setupButtonListeners()
                            audioCallScreenSetup(rtcClient)
                        } else {
                            debugLine(tag, "WTF! Connection is Live but RTC Client is null, cannot setup audio call screen")
                        }
                    }
                }
            }
        }
    }

    private fun setupButtonListeners() {
        fabClose.setOnClickListener {
            lifecycleScope.launch {
                debugLine(tag, "Closing the call, (hungup)")
                callId?.let {
                    withTimeoutOrNull(3000L) {
                        ManagedTelecom.disconnectCall(it, DisconnectCause(DisconnectCause.LOCAL)).join()
                    }
                }
            }
        }

        btnPip.setOnClickListener {
            startHideToolbarTimer()
            enterPipMode()
        }

        speaker.setOnClickListener {
            startHideToolbarTimer()

            speakerOn = !speakerOn
            ManagedTelecom.setSpeakerphone(speakerOn)
            val iconRes = if (speakerOn) R.drawable.speaker_on else R.drawable.speaker_off
            speaker.setImageResource(iconRes)
        }

        onHoldOnOff.setOnClickListener {
            ManagedTelecom.toggleHold()
        }
    }

    private fun audioCallScreenSetup(rtcClient: RTCClient) {

        debugLine(tag, "Setting up Audio Call Screen")

        bottomControlsContainer.visibility = View.VISIBLE

        connectingText.visibility = View.GONE

        audioCallScreenActive = true
        rtcClient.setLocalAudioEnabled(true)
        speaker.setImageResource(R.drawable.speaker_off)

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

            debugLine(tag, "Attempting to enter Picture-in-Picture mode.")
            connectionManager.isClosing = false
            enterPictureInPictureMode(pipParams)
        } catch (e: Exception) {
            debugLine(tag, "Failed to enter PiP mode: ${e.message}")
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            debugLine(tag, "Entering PiP mode - Hiding controls.")
            connectionManager.isClosing = false

            isInPipMode = true
            exitingPipMode = false

            cancelHideToolbarTimer()

            bottomControlsContainer.visibility = View.GONE  // Hide entire container

        } else {
            debugLine(tag, "Exiting PiP mode - Showing controls.")
            connectionManager.isClosing = true

            isInPipMode = false
            exitingPipMode = true

            bottomControlsContainer.visibility = View.VISIBLE  // Show entire container

            isToolbarVisible = true
            startHideToolbarTimer()

            setFullScreen()
        }
    }

    private fun finishAudioCall(){
        if(!terminateOnEnd) {
            debugLine(tag, "Finishing Audio Call, it was not started only for audio call, do not terminate App")
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
            if (isToolbarVisible && audioCallScreenActive) {
                hideToolbarWithAnimation()
            }
        }

        startHideToolbarTimer()

        background.setOnClickListener {
            if (audioCallScreenActive) {
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
                    .setInterpolator(DecelerateInterpolator())
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
                .setInterpolator(AccelerateInterpolator())
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