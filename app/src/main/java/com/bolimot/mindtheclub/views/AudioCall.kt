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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bolimot.mindtheclub.functions.applyImmersiveFullScreen
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.describePipAvailability
import com.bolimot.mindtheclub.functions.deviceSupportsPip
import com.bolimot.mindtheclub.functions.ensureCallPermissions
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.wakeUpPhone
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Broadcast
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bolimot.mindtheclub.voip.sendCallEventToPeer
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.webrtc.RTCClient
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private lateinit var switchToVideo: ImageButton
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

    private var rtcClient: RTCClient? = null
    private var peerName: String = ""
    private var upgradeDialog: AlertDialog? = null
    private var upgradeTimeoutJob: Job? = null
    // Set between asking the peer and hearing back, so a stray answer cannot move a
    // screen that never asked for anything, and a second tap cannot ask twice.
    private var awaitingUpgradeAnswer = false
    private var upgradeInProgress = false


    private val peerViewModel = getPeerViewModel()
    private val hideToolbarHandler = Handler(Looper.getMainLooper())
    private val toolbarHideDelay = 5000L
    private val tag = "AudioCall"

    // The peer has to see the request, read it and tap. Long enough not to cut off
    // someone who is deciding, short enough to release the button if they never saw it.
    private val upgradeAnswerTimeout = 45000L
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
        upgradeTimeoutJob?.cancel()
        upgradeDialog?.dismiss()
        upgradeDialog = null
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
        switchToVideo = findViewById(R.id.switch_to_video)

        if (!deviceSupportsPip(this)) {
            debugLine(tag, "Device does not support Picture in Picture, hiding the button")
            btnPip.visibility = View.GONE
        }

        lifecycleScope.launch {
            val remotePeer = peerViewModel.getPeer(remoteUserId!!)
            peerName = remotePeer?.name ?: ""
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
        observeVideoUpgradeEvents()
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

        switchToVideo.setOnClickListener {
            startHideToolbarTimer()
            askToSwitchToVideo()
        }
    }

    private fun audioCallScreenSetup(rtcClient: RTCClient) {

        debugLine(tag, "Setting up Audio Call Screen")

        this.rtcClient = rtcClient

        bottomControlsContainer.visibility = View.VISIBLE

        connectingText.visibility = View.GONE

        audioCallScreenActive = true
        rtcClient.setLocalAudioEnabled(true)
        speaker.setImageResource(R.drawable.speaker_off)

        setupToolbarAutoHide()
    }

    //**********************************************************************************//
    //                        AUDIO TO VIDEO CALL UPGRADE                                //
    //**********************************************************************************//

    /**
     * Both ends have to agree before either camera comes on, so the switch is a short
     * handshake rather than a local toggle: we ask, the peer accepts or declines, and
     * only on acceptance does each side add its camera track and renegotiate.
     */
    private fun askToSwitchToVideo() {
        if (!audioCallScreenActive || upgradeInProgress) return

        if (awaitingUpgradeAnswer) {
            Toast.makeText(this, R.string.switch_to_video_waiting, Toast.LENGTH_SHORT).show()
            return
        }

        showUpgradeDialog(
            message = getString(R.string.switch_to_video_question),
            positiveText = R.string.yes,
            negativeText = R.string.no,
            onPositive = { requestVideoUpgrade() },
            onNegative = { }
        )
    }

    private fun requestVideoUpgrade() {
        // Asking before the camera permission is settled would put the peer in front of
        // a request we might not be able to honour.
        if (!ensureCallPermissions(this, isVideo = true)) {
            debugLine(tag, "Camera permission missing, upgrade request not sent")
            return
        }

        if (rtcClient?.eglContext == null) {
            debugLine(tag, "No EGL context on this call, cannot upgrade to video")
            Toast.makeText(this, R.string.switch_to_video_failed, Toast.LENGTH_LONG).show()
            return
        }

        awaitingUpgradeAnswer = true
        switchToVideo.isEnabled = false

        lifecycleScope.launch {
            sendCallEventToPeer(remoteUserId!!, CallEvent.VIDEO_UPGRADE_REQUEST, callId)
        }

        Toast.makeText(this, R.string.switch_to_video_waiting, Toast.LENGTH_SHORT).show()

        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = lifecycleScope.launch {
            delay(upgradeAnswerTimeout)
            if (awaitingUpgradeAnswer) {
                debugLine(tag, "No answer to the video upgrade request, giving up")
                resetUpgradeRequest()
                Toast.makeText(this@AudioCall, R.string.switch_to_video_no_answer, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeVideoUpgradeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionManager.instance.videoUpgradeEvents.collect { event ->
                    when (event) {
                        CallEvent.VIDEO_UPGRADE_REQUEST -> onUpgradeRequested()
                        CallEvent.VIDEO_UPGRADE_ACCEPT -> onUpgradeAccepted()
                        CallEvent.VIDEO_UPGRADE_REJECT -> onUpgradeRejected()
                    }
                }
            }
        }
    }

    /** The peer wants video. Their camera stays off until this side answers. */
    private fun onUpgradeRequested() {
        if (!audioCallScreenActive || upgradeInProgress) return

        // Both sides tapped at the same time: the request we already sent stands, and
        // accepting our own peer's request as well would produce two offers.
        if (awaitingUpgradeAnswer) {
            debugLine(tag, "Upgrade request crossed with ours, ignoring the incoming one")
            return
        }

        val who = peerName.ifEmpty { getString(R.string.contact) }

        showUpgradeDialog(
            message = getString(R.string.switch_to_video_request, who),
            positiveText = R.string.switch_to_video_accept,
            negativeText = R.string.reject,
            onPositive = { acceptVideoUpgrade() },
            onNegative = { declineVideoUpgrade() }
        )
    }

    private fun declineVideoUpgrade() {
        lifecycleScope.launch {
            sendCallEventToPeer(remoteUserId!!, CallEvent.VIDEO_UPGRADE_REJECT, callId)
        }
    }

    /**
     * Accepting side. The camera track goes on before the answer is sent, so that by the
     * time the peer re-offers this end can answer with video in the same exchange.
     */
    private fun acceptVideoUpgrade() {
        if (!ensureCallPermissions(this, isVideo = true)) {
            debugLine(tag, "Camera permission missing, declining the upgrade")
            declineVideoUpgrade()
            return
        }

        upgradeInProgress = true

        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) { rtcClient?.enableLocalVideo() ?: false }

            if (!enabled) {
                debugLine(tag, "Could not enable the local camera, declining the upgrade")
                upgradeInProgress = false
                declineVideoUpgrade()
                Toast.makeText(this@AudioCall, R.string.switch_to_video_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            sendCallEventToPeer(remoteUserId!!, CallEvent.VIDEO_UPGRADE_ACCEPT, callId)
            goToVideoCall()
        }
    }

    /** Requesting side, the peer said yes: add our camera and re-offer the session. */
    private fun onUpgradeAccepted() {
        if (!awaitingUpgradeAnswer) {
            debugLine(tag, "Upgrade acceptance received but nothing was asked, ignoring")
            return
        }

        upgradeTimeoutJob?.cancel()
        awaitingUpgradeAnswer = false
        upgradeInProgress = true

        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) { rtcClient?.enableLocalVideo() ?: false }

            if (!enabled) {
                debugLine(tag, "Peer accepted but the local camera could not be enabled")
                upgradeInProgress = false
                switchToVideo.isEnabled = true
                sendCallEventToPeer(remoteUserId!!, CallEvent.VIDEO_UPGRADE_REJECT, callId)
                Toast.makeText(this@AudioCall, R.string.switch_to_video_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            rtcClient?.renegotiateForVideo()
            goToVideoCall()
        }
    }

    private fun onUpgradeRejected() {
        if (!awaitingUpgradeAnswer) return

        resetUpgradeRequest()

        val who = peerName.ifEmpty { getString(R.string.contact) }
        Toast.makeText(this, getString(R.string.switch_to_video_declined, who), Toast.LENGTH_LONG).show()
    }

    private fun resetUpgradeRequest() {
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        awaitingUpgradeAnswer = false
        switchToVideo.isEnabled = true
    }

    /**
     * Hands the live call over to the video screen. The call itself is untouched: the
     * Telecom session and the PeerConnection both outlive this activity.
     */
    private fun goToVideoCall() {
        debugLine(tag, "Switching the call screen from audio to video")

        upgradeDialog?.dismiss()
        upgradeDialog = null

        val intent = Intent(this, VideoCall::class.java).apply {
            // Without this the framework treats the handover as the user walking away and
            // fires onUserLeaveHint, which would send this screen into PiP on its way out.
            addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra("remoteUserId", remoteUserId)
            putExtra("callId", callId)
            putExtra("isCaller", isCaller)
            putExtra("terminateOnEnd", terminateOnEnd)
        }
        startActivity(intent)
        finish()
    }

    private fun showUpgradeDialog(
        message: String,
        positiveText: Int,
        negativeText: Int,
        onPositive: () -> Unit,
        onNegative: () -> Unit
    ) {
        upgradeDialog?.dismiss()

        cancelHideToolbarTimer()

        upgradeDialog = MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(positiveText) { _, _ -> onPositive() }
            .setNegativeButton(negativeText) { _, _ -> onNegative() }
            .setOnDismissListener {
                upgradeDialog = null
                if (audioCallScreenActive) startHideToolbarTimer()
            }
            .show()
    }

    private fun setFullScreen(){
        applyImmersiveFullScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullScreen()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    private fun enterPipMode() {
        // onUserLeaveHint calls this on every Home press, so without the guard a device
        // with no PiP support would log a refusal each time the user leaves the call.
        if (!deviceSupportsPip(this)) return

        try {
            val aspectRatio = Rational(9, 16)
            val pipParams = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()

            debugLine(tag, "Attempting to enter Picture-in-Picture mode.")
            connectionManager.isClosing = false

            // The system reports a refusal by returning false, not by throwing. Ignoring
            // it left a button that looked dead and said nothing in the log.
            val entered = enterPictureInPictureMode(pipParams)
            if (!entered) {
                debugLine(tag, "System refused PiP: ${describePipAvailability(this)}")
            }
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