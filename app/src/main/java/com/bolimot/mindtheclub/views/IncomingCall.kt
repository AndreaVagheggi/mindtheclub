package com.bolimot.mindtheclub.views

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.telecom.DisconnectCause
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.wakeUpPhone
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.voip.CallActionReceiver
import com.bolimot.mindtheclub.voip.CallNotificationManager
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class IncomingCall : BaseActivity() {

    /**
     * A ringing call must never be replaced by the paywall: a lapsed user can
     * still answer (they cannot place calls, and the caller's own relay cap
     * bounds the cost), which is far better than a call that silently dies.
     */
    override fun isSubscriptionGateExempt(): Boolean = true

    private var callId: String? = null
    private var displayName: String? = null
    private var picture: String? = null
    private var tag = "IncomingCallActivity"
    private var acceptButtonAnimator: ObjectAnimator? = null
    private var initialY = 0f
    private var isDragging = false
    private val swipeThreshold = 150f
    private val maxSwipeDistance = 300f
    private var terminateOnEnd = false
    private var isAccepting = false

    override fun onPause() {
        super.onPause()
        if (isFinishing && callId != null) {
            val session = ManagedTelecom.currentCall.value
            if (session != null && !session.isAnswered) {
                debugLine(tag, "Activity is finishing, active call was not answered. Rejecting.")
                ManagedTelecom.disconnectCall(session.callId, DisconnectCause(DisconnectCause.REJECTED))
            } else if (session == null && ManagedTelecom.pendingCallFlow.value?.callId == callId) {
                debugLine(tag, "Activity is finishing, pending call was not answered. Rejecting.")
                ManagedTelecom.declinePendingCall(callId!!)
            }
        }
    }

    private fun setFullScreen(){
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        wakeUpPhone()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        callId = intent.getStringExtra(CallActionReceiver.EXTRA_CALL_ID)
        displayName = intent.getStringExtra("EXTRA_DISPLAY_NAME")
        picture = intent.getStringExtra("EXTRA_REMOTE_PICTURE")
        terminateOnEnd = intent.getBooleanExtra("EXTRA_TERMINATE_ON_END", false)

        if (callId == null) {
            debugLine(tag, "Call ID is null. This activity was started incorrectly. Finishing.")
            finishAndRemoveTask()
            return
        }

        setContentView(R.layout.call_screen)

        setFullScreen()

        observeCallState()

        val userTextView = findViewById<TextView>(R.id.user)
        val peerPicture = findViewById<ShapeableImageView>(R.id.profilePic)
        val callText = findViewById<TextView>(R.id.calling)
        val container = findViewById<ConstraintLayout>(R.id.container)

        userTextView.text = displayName
        Glide.with(this@IncomingCall)
            .load(picture)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .placeholder(R.drawable.peer)
            .error(R.drawable.peer)
            .into(peerPicture)


        val isVideo = ManagedTelecom.pendingCallFlow.value?.isVideo
            ?: ManagedTelecom.currentCall.value?.isVideo
            ?: false

        if (isVideo) {
            callText.text = getString(R.string.video_call)
            container.setBackgroundColor(getColor(R.color.primary_opaque))
        } else {
            callText.text = getString(R.string.phone_call)
            container.setBackgroundColor(getColor(R.color.black))
        }

        val acceptCall = findViewById<FloatingActionButton>(R.id.button_accept_call)
        val declineCall = findViewById<FloatingActionButton>(R.id.button_decline_call)
        val acceptText = findViewById<TextView>(R.id.accept_text)
        val declineText = findViewById<TextView>(R.id.decline_text)
        val ovalCard = findViewById<CardView>(R.id.oval_card)

        acceptCall.visibility = View.VISIBLE
        declineCall.visibility = View.VISIBLE
        acceptText.visibility = View.VISIBLE
        declineText.visibility = View.VISIBLE
        ovalCard.visibility = View.VISIBLE

        setupSwipeToAccept()
        startAcceptButtonAnimation()

        declineCall.setOnClickListener {
            stopAnimationsAndJobs()
            ManagedTelecom.declinePendingCall(callId!!)
            val notificationManager = CallNotificationManager(applicationContext)
            notificationManager.dismissCallNotification()
            finishAndRemoveTask()
        }
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            var hadActiveSession = false
            ManagedTelecom.currentCall.collect { callSession ->
                if (callSession != null && callSession.disconnectCause == null) {
                    hadActiveSession = true
                }
                if (hadActiveSession && (callSession == null || callSession.disconnectCause != null)) {
                    debugLine(tag, "Call session ended. Finishing Activity.")
                    finishAndRemoveTask()
                }
            }
        }

        // Observer 2: Pre-answer phase — close when pending call is cleared
        // by timeout or remote cancel (but NOT when the user pressed Accept)
        lifecycleScope.launch {
            ManagedTelecom.pendingCallFlow.collect { pending ->
                if (pending == null && !isAccepting && ManagedTelecom.currentCall.value == null) {
                    debugLine(tag, "Pending call cleared (timeout or remote cancel). Finishing Activity.")
                    finishAndRemoveTask()
                }
            }
        }
    }

    private fun acceptCallAction() {
        stopAnimationsAndJobs()
        isAccepting = true

        val notificationManager = CallNotificationManager(applicationContext)
        notificationManager.dismissCallNotification()

        lifecycleScope.launch(Dispatchers.IO) {
            if (callId != null) {
                ManagedTelecom.acceptPendingCall(callId!!, applicationContext)
            } else {
                debugLine(tag, "Tried to accept a call, but callId was null.")
            }
        }
    }

    private fun startAcceptButtonAnimation() {
        val acceptCallButton = findViewById<FloatingActionButton>(R.id.button_accept_call)
        acceptButtonAnimator = ObjectAnimator.ofFloat(acceptCallButton, "translationY", 0f, -30f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 500
        }
        acceptButtonAnimator?.start()
    }

    private fun stopAnimationsAndJobs() {
        acceptButtonAnimator?.cancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeToAccept() {
        val acceptCallButton = findViewById<FloatingActionButton>(R.id.button_accept_call)
        val acceptText = findViewById<TextView>(R.id.accept_text)
        val ovalCard = findViewById<CardView>(R.id.oval_card)

        acceptCallButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    acceptButtonAnimator?.cancel()
                    initialY = event.rawY
                    isDragging = true
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val deltaY = event.rawY - initialY
                        if (deltaY < 0) {
                            val translationY = deltaY.coerceAtLeast(-maxSwipeDistance)
                            view.translationY = translationY
                            val progress = abs(translationY) / swipeThreshold
                            updateSwipeProgress(progress, acceptText, ovalCard)
                            if (abs(translationY) >= swipeThreshold) {
                                view.backgroundTintList = ColorStateList.valueOf(getColor(R.color.mtc_main))
                            } else {
                                view.backgroundTintList = null
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        val finalTranslationY = view.translationY
                        if (abs(finalTranslationY) >= swipeThreshold) {
                            acceptCallAction()
                        } else {
                            view.animate()
                                .translationY(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(200)
                                .withEndAction { startAcceptButtonAnimation() }
                                .start()
                            view.backgroundTintList = null
                            resetSwipeProgress(acceptText, ovalCard)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateSwipeProgress(progress: Float, acceptText: TextView, ovalCard: CardView) {
        val alpha = 1f - progress.coerceAtMost(1f)
        acceptText.alpha = alpha
        ovalCard.alpha = alpha
    }

    private fun resetSwipeProgress(acceptText: TextView, ovalCard: CardView) {
        acceptText.animate().alpha(1f).setDuration(200).start()
        ovalCard.animate().alpha(1f).setDuration(200).start()
    }
}