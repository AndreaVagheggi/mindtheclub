package com.bolimot.mindtheclub.chat

import android.app.Activity
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.MediaController
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.customViews.MyVideoView
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import java.util.Date
import java.util.Locale

class VideoView : BaseActivity() {
    private lateinit var dateTextView: TextView
    private lateinit var dayTextView: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var userId: String
    private lateinit var uri: String

    private var barIsVisible = true
    private var hidingBars = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uri = intent?.getStringExtra("uri") ?: return
        userId = intent?.getStringExtra("userId") ?: return
        val timestamp = intent.getLongExtra("messageDate", -1L)

        setContentView(R.layout.video_view)

        dateTextView = findViewById(R.id.date)
        dayTextView = findViewById(R.id.day)

        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val date = Date(timestamp)
        val dateString = sdf.format(date)
        dateTextView.text = dateString

        val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val dayOfWeekString = dayOfWeekFormat.format(date)

        dayTextView.text = dayOfWeekString

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)

            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }

        toolbar = findViewById(R.id.toolbar)
        appBarLayout = findViewById(R.id.appBarLayout)

        @Suppress("DEPRECATION")
        window.navigationBarColor = getColor(R.color.primary_transparent)

        setSupportActionBar(toolbar)

        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        dateTextView = findViewById(R.id.date)
        dayTextView = findViewById(R.id.day)

        val video = findViewById<MyVideoView>(R.id.video)

        video.setVideoPath(uri)

        val mediaController = MediaController(this)
        video.setMediaController(mediaController)
        mediaController.setAnchorView(video)

        video.onPlayPauseListener = object : MyVideoView.OnPlayPauseListener {
            override fun onPlay() { hideToolbar() }
            override fun onPause() { showToolbar() }
            override fun onCompletion() { showToolbar() }
        }

        video.setOnPreparedListener {
            video.start()
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.forward, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.forward -> {
                uri.let {
                    val intent = Intent(this, SelectPeersForForward::class.java)
                    intent.putExtra("excludedUserId", userId)
                    getPeersResult.launch(intent)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun hideToolbar() {
        hidingBars = true
        appBarLayout.animate()
            .translationY(-appBarLayout.height.toFloat())
            .setDuration(300)
            .withEndAction {
                appBarLayout.visibility = View.GONE
                hidingBars = false
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

        barIsVisible = false
    }

    private fun showToolbar() {
        hidingBars = true
        appBarLayout.visibility = View.VISIBLE
        appBarLayout.translationY = -appBarLayout.height.toFloat()
        appBarLayout.animate()
            .translationY(0f)
            .setDuration(300)
            .withEndAction {
                hidingBars = false
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        @Suppress("DEPRECATION")
        window.navigationBarColor = getColor(R.color.primary_transparent)

        barIsVisible = true
    }

    private val getPeersResult =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedPeers: List<String> = result.data?.getStringArrayListExtra("selectedPeers") ?: return@registerForActivityResult

            val intent = Intent(this, SendImage::class.java).apply {
                putExtra("imagePath",uri)
                putExtra("userId", selectedPeers.joinToString(","))
            }
            startActivity(intent)
            finish()
        }
    }
}