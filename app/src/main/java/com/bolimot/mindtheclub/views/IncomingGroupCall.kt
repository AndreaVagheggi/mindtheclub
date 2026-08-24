package com.bolimot.mindtheclub.views

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.wakeUpPhone
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.voip.GroupCallService
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import kotlin.math.abs

/**
 * The full-screen ring for an arriving group call.
 *
 * It reuses the 1:1 incoming screen's layout on purpose: a call arriving should
 * look like a call arriving, whether it comes from one person or from six.
 */
class IncomingGroupCall : BaseActivity() {

    companion object {
        /** Answers the moment the screen opens, for the notification button. */
        const val EXTRA_AUTO_ANSWER = "autoAnswer"

        /**
         * The ringing screen, while one is up. The service closes it when the
         * caller gives up or hangs up before anyone answers: an invitation that
         * is no longer live must not stay on screen offering to be accepted.
         */
        @Volatile
        private var current: IncomingGroupCall? = null

        fun dismiss() {
            current?.let { activity ->
                activity.runOnUiThread { if (!activity.isFinishing) activity.finishAndRemoveTask() }
            }
        }
    }

    /**
     * A ringing call is never replaced by the paywall. The allowance is checked
     * when the call is actually joined, which is the moment bytes start moving.
     */
    override fun isSubscriptionGateExempt(): Boolean = true

    private val tag = "IncomingGroupCall"

    private var roomId: String? = null
    private var key: String = ""
    private var epoch: Int = 0
    private var host: String = ""
    private var answered = false

    private var acceptAnimator: ObjectAnimator? = null
    private var initialY = 0f
    private var isDragging = false
    private val swipeThreshold = 150f
    private val maxSwipeDistance = 300f

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

        roomId = intent.getStringExtra(GroupCallService.EXTRA_ROOM_ID)
        key = intent.getStringExtra(GroupCallService.EXTRA_KEY).orEmpty()
        epoch = intent.getIntExtra(GroupCallService.EXTRA_EPOCH, 0)
        host = intent.getStringExtra(GroupCallService.EXTRA_HOST).orEmpty()

        if (roomId == null || key.isEmpty()) {
            debugLine(tag, "Invitation without a room or a key, finishing")
            finishAndRemoveTask()
            return
        }

        setContentView(R.layout.call_screen)
        setFullScreen()

        val name = intent.getStringExtra("EXTRA_DISPLAY_NAME").orEmpty()
        val picture = intent.getStringExtra("EXTRA_REMOTE_PICTURE")

        findViewById<TextView>(R.id.user).text = name
        findViewById<TextView>(R.id.calling).text = getString(R.string.incoming_group_call)
        findViewById<ConstraintLayout>(R.id.container)
            .setBackgroundColor(getColor(R.color.primary_opaque))

        Glide.with(this)
            .load(picture)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .placeholder(R.drawable.peer)
            .error(R.drawable.peer)
            .into(findViewById<ShapeableImageView>(R.id.profilePic))

        val accept = findViewById<FloatingActionButton>(R.id.button_accept_call)
        val decline = findViewById<FloatingActionButton>(R.id.button_decline_call)

        accept.visibility = View.VISIBLE
        decline.visibility = View.VISIBLE
        findViewById<TextView>(R.id.accept_text).visibility = View.VISIBLE
        findViewById<TextView>(R.id.decline_text).visibility = View.VISIBLE
        findViewById<CardView>(R.id.oval_card).visibility = View.VISIBLE

        // Swipe up to answer, exactly like the 1:1 screen. This screen borrows
        // that layout, so it has to borrow the gesture too: a call that looks
        // identical and answers differently is a call nobody manages to answer.
        setupSwipeToAccept(accept)
        startAcceptButtonAnimation(accept)

        decline.setOnClickListener { decline() }

        current = this

        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) {
            // Came from the notification's answer button: the choice is already
            // made, this screen exists only to give the call a foreground start.
            answer()
        }
    }

    private fun setupSwipeToAccept(accept: FloatingActionButton) {
        val acceptText = findViewById<TextView>(R.id.accept_text)
        val ovalCard = findViewById<CardView>(R.id.oval_card)

        accept.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    acceptAnimator?.cancel()
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
                            val alpha = 1f - progress.coerceAtMost(1f)
                            acceptText.alpha = alpha
                            ovalCard.alpha = alpha
                            view.backgroundTintList =
                                if (abs(translationY) >= swipeThreshold) {
                                    ColorStateList.valueOf(getColor(R.color.mtc_main))
                                } else null
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        if (abs(view.translationY) >= swipeThreshold) {
                            answer()
                        } else {
                            view.animate()
                                .translationY(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(200)
                                .withEndAction { startAcceptButtonAnimation(accept) }
                                .start()
                            view.backgroundTintList = null
                            acceptText.animate().alpha(1f).setDuration(200).start()
                            ovalCard.animate().alpha(1f).setDuration(200).start()
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** The nudge that tells the user the button wants to be dragged upwards. */
    private fun startAcceptButtonAnimation(accept: FloatingActionButton) {
        acceptAnimator?.cancel()
        acceptAnimator = ObjectAnimator.ofFloat(accept, "translationY", 0f, -24f, 0f).apply {
            duration = 1_400
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun answer() {
        if (answered) return
        answered = true

        val intent = Intent(applicationContext, GroupCallService::class.java).apply {
            action = GroupCallService.ACTION_JOIN
            putExtra(GroupCallService.EXTRA_ROOM_ID, roomId)
            putExtra(GroupCallService.EXTRA_KEY, key)
            putExtra(GroupCallService.EXTRA_EPOCH, epoch)
            putExtra(GroupCallService.EXTRA_VIDEO, true)
        }
        ContextCompat.startForegroundService(applicationContext, intent)

        // The call screen is opened from HERE, not from the service. Android
        // blocks an activity started by a background service, which is why the
        // app appeared to vanish after answering: the call was running with no
        // window, and with no window the camera is denied too.
        startActivity(Intent(this, GroupCall::class.java))
        finish()
    }

    private fun decline() {
        val intent = Intent(applicationContext, GroupCallService::class.java).apply {
            action = GroupCallService.ACTION_DECLINE
            putExtra(GroupCallService.EXTRA_ROOM_ID, roomId)
            putExtra(GroupCallService.EXTRA_HOST, host)
        }
        ContextCompat.startForegroundService(applicationContext, intent)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        acceptAnimator?.cancel()
        if (current === this) current = null
        super.onDestroy()
    }

    private fun setFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullScreen()
    }
}
