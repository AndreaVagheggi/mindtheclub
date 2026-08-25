package com.bolimot.mindtheclub.views

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.GroupCallAdapter
import com.bolimot.mindtheclub.chat.SelectPeersForGroup
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.functions.wakeUpPhone
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.voip.GroupCallService
import com.bolimot.mindtheclub.webrtc.group.GroupCallManager
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The group call screen.
 *
 * It renders state and nothing else: every flow it reads is owned by
 * [GroupCallManager], so rotating the phone, backgrounding the app or reopening
 * the screen from the notification all land on the same call rather than on a
 * second one. Hanging up goes through the service, which is what actually holds
 * the call alive when this activity is gone.
 */
class GroupCall : BaseActivity() {

    /**
     * A call already in progress is never interrupted by the paywall. Whoever is
     * on this screen either started a call that was allowed, or answered one:
     * both were checked before a single byte was spent.
     */
    override fun isSubscriptionGateExempt(): Boolean = true

    private lateinit var grid: RecyclerView
    private lateinit var adapter: GroupCallAdapter
    private lateinit var title: TextView
    private lateinit var duration: TextView
    private lateinit var banner: TextView
    private lateinit var reactionsBar: LinearLayout

    private lateinit var micButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
    private lateinit var reactionButton: ImageButton
    private lateinit var hangUpButton: ImageButton
    private lateinit var addParticipantButton: ImageButton

    private var startedAt = 0L

    private val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")

    override fun onCreate(savedInstanceState: Bundle?) {
        wakeUpPhone()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.group_call)
        setFullScreen()

        // Leaving a call is an explicit act, not a stray back press.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        grid = findViewById(R.id.participants_grid)
        title = findViewById(R.id.call_title)
        duration = findViewById(R.id.call_duration)
        banner = findViewById(R.id.call_banner)
        reactionsBar = findViewById(R.id.reactions_bar)

        micButton = findViewById(R.id.btn_mic)
        cameraButton = findViewById(R.id.btn_camera)
        switchCameraButton = findViewById(R.id.btn_switch_camera)
        reactionButton = findViewById(R.id.btn_reaction)
        hangUpButton = findViewById(R.id.btn_hang_up)
        addParticipantButton = findViewById(R.id.btn_add_participant)

        adapter = GroupCallAdapter { pid -> GroupCallManager.pin(pid) }
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter
        grid.itemAnimator = null

        buildReactionsBar()
        setupControls()
        observe()

        startedAt = System.currentTimeMillis()
    }

    // ────────────────────────────────────────────────────────────────── controls

    private fun setupControls() {
        micButton.setOnClickListener { GroupCallManager.toggleMic() }
        cameraButton.setOnClickListener { GroupCallManager.toggleCamera() }
        switchCameraButton.setOnClickListener { GroupCallManager.switchCamera() }

        reactionButton.setOnClickListener {
            reactionsBar.visibility =
                if (reactionsBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        hangUpButton.setOnClickListener {
            GroupCallService.leave(applicationContext)
            finishAndRemoveTask()
        }

        addParticipantButton.setOnClickListener {
            val seats = GroupCallManager.freeSeats()
            if (seats <= 0) {
                showToast(getString(R.string.group_call_full), this)
                return@setOnClickListener
            }

            val intent = Intent(this, SelectPeersForGroup::class.java).apply {
                // Anyone in the contacts can be pulled in, exactly as in a 1:1
                // call that grows: the room is not tied to the group it started
                // from. People already here are filtered out.
                putStringArrayListExtra(
                    "excludedUserIds",
                    ArrayList(GroupCallManager.presentUserIds())
                )
                putExtra(SelectPeersForGroup.EXTRA_MAX_SELECTION, seats)
                putExtra(SelectPeersForGroup.EXTRA_TITLE, getString(R.string.group_call_who_to_add))
            }
            addParticipantResult.launch(intent)
        }
    }

    private val addParticipantResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val chosen = result.data
                ?.getStringArrayListExtra(SelectPeersForGroup.RESULT_SELECTED)
                ?: return@registerForActivityResult
            GroupCallManager.invite(chosen)
        }

    private fun buildReactionsBar() {
        for (emoji in emojis) {
            val view = TextView(this).apply {
                text = emoji
                textSize = 26f
                setPadding(18, 0, 18, 0)
                setOnClickListener {
                    GroupCallManager.sendReaction(emoji)
                    reactionsBar.visibility = View.GONE
                }
            }
            reactionsBar.addView(view)
        }
    }

    // ─────────────────────────────────────────────────────────────── observation

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { GroupCallManager.members.collect { renderMembers(it) } }

                launch {
                    GroupCallManager.status.collect { status ->
                        when (status) {
                            GroupCallManager.Status.CONNECTING ->
                                showBanner(getString(R.string.connecting))
                            GroupCallManager.Status.RECONNECTING ->
                                showBanner(getString(R.string.reconnecting))
                            GroupCallManager.Status.CONNECTED -> hideBanner()
                            GroupCallManager.Status.FULL -> {
                                showBanner(getString(R.string.group_call_full))
                                finishSoon()
                            }
                            GroupCallManager.Status.NO_ALLOWANCE -> {
                                showBanner(getString(R.string.group_call_no_allowance))
                                finishSoon()
                            }
                            GroupCallManager.Status.FAILED -> {
                                showBanner(getString(R.string.call_failed))
                                finishSoon()
                            }
                            GroupCallManager.Status.ENDED,
                            GroupCallManager.Status.IDLE -> finishSoon()
                        }
                    }
                }

                launch {
                    GroupCallManager.micEnabled.collect {
                        micButton.setImageResource(
                            if (it) R.drawable.microphone else R.drawable.microphone_off
                        )
                    }
                }

                launch {
                    GroupCallManager.cameraEnabled.collect {
                        cameraButton.setImageResource(
                            if (it) R.drawable.video else R.drawable.video_off
                        )
                    }
                }

                launch {
                    GroupCallManager.audioOnly.collect { audioOnly ->
                        if (audioOnly) {
                            showBanner(getString(R.string.group_call_audio_only))
                            cameraButton.isEnabled = false
                            switchCameraButton.isEnabled = false
                        }
                    }
                }

                launch {
                    GroupCallManager.allowanceWarning.collect { warn ->
                        if (warn) {
                            showBanner(getString(R.string.group_call_allowance_warning))
                            GroupCallManager.consumeAllowanceWarning()
                        }
                    }
                }

                launch {
                    // Pinning changes what the grid shows, so it travels the same
                    // path as any other change to the participants.
                    GroupCallManager.pinned.collect {
                        renderMembers(GroupCallManager.members.value)
                    }
                }

                launch {
                    GroupCallManager.reactions.collect { reaction ->
                        if (reaction != null) {
                            adapter.showReaction(grid, reaction.first, reaction.second)
                            GroupCallManager.consumeReaction()
                        }
                    }
                }

                launch { tickDuration() }
            }
        }
    }

    /**
     * Draws the call.
     *
     * Pinning is a state of this grid, not a second view stacked on top of it.
     * It used to be an overlaid SurfaceViewRenderer, and two SurfaceViews in one
     * window each cut their own hole through it: hiding the overlay left the
     * tiles beneath with dead surfaces, so coming back from full screen froze
     * every picture at once. With a single set of renderers that cannot happen,
     * and the pinned participant is simply the only tile, at full height.
     */
    private fun renderMembers(members: List<GroupCallManager.Member>) {
        title.text = getString(R.string.group_call_participants, members.size)

        // No point offering to add somebody to a room with no seat left.
        addParticipantButton.visibility =
            if (GroupCallManager.freeSeats() > 0) View.VISIBLE else View.GONE

        // A pinned participant who has left releases the pin rather than leaving
        // the screen stuck on a tile with nobody behind it.
        val requested = GroupCallManager.pinned.value
        if (requested != null && members.none { it.pid == requested }) {
            GroupCallManager.pin(null)
            return
        }

        val shown = if (requested != null) members.filter { it.pid == requested } else members
        val modeChanged = adapter.pinnedMode != (requested != null)
        adapter.pinnedMode = requested != null

        // Tiles are sized to fill the screen rather than scroll: a call is
        // something you look at, not something you browse.
        val columns = if (shown.size <= 1) 1 else if (shown.size <= 6) 2 else 3
        (grid.layoutManager as GridLayoutManager).spanCount = columns

        val rows = ceil(shown.size / columns.toDouble()).toInt().coerceAtLeast(1)
        val available = grid.height - grid.paddingTop - grid.paddingBottom
        if (available <= 0) {
            // First pass, before the grid has been measured. One post is enough:
            // by the time it runs the layout has happened.
            grid.post { if (!isFinishing) renderMembers(members) }
            return
        }

        val height = available / rows
        val heightChanged = adapter.tileHeight != height
        adapter.tileHeight = height

        adapter.submitList(shown.toList()) {
            // A tile whose content did not change is not rebound, so a grid that
            // went from four faces to six would keep the old, taller tiles, and
            // a tile that survived a pin would keep the grid's cropping.
            if (heightChanged || modeChanged) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    private suspend fun tickDuration() {
        while (true) {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            duration.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
            kotlinx.coroutines.delay(1_000L)
        }
    }

    private fun showBanner(text: String) {
        banner.text = text
        banner.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        banner.visibility = View.GONE
    }

    private fun finishSoon() {
        if (isFinishing) return
        banner.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, 1_500L)
    }

    // ────────────────────────────────────────────────────────────────── lifecycle

    override fun onDestroy() {
        // Tiles still on screen were never recycled, so their renderers still
        // hold a GPU surface and a sink on a live track.
        adapter.releaseAll(grid)
        grid.adapter = null
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
