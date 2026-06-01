package com.bolimot.mindtheclub.views

import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.start.BaseActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import io.getstream.photoview.PhotoView

class ProfilePictureViewActivity : BaseActivity() {

    private lateinit var photoView: PhotoView
    private lateinit var appBarLayout: AppBarLayout
    private var barIsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pictureUri = intent?.getStringExtra("pictureUri")
        val title = intent?.getStringExtra("title") ?: ""

        setContentView(R.layout.activity_profile_picture_view)

        // Fullscreen immersive — same pattern as ImageGalleryActivity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }

        appBarLayout = findViewById(R.id.appBarLayout)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        photoView = findViewById(R.id.photoView)

        setSupportActionBar(toolbar)
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Toggle toolbar on tap (same UX as ImageGalleryActivity)
        photoView.setOnPhotoTapListener { _, _, _ ->
            if (barIsVisible) hideToolbar() else showToolbar()
        }

        // Load the image — Glide handles both local URIs and remote URLs
        if (!pictureUri.isNullOrEmpty()) {
            Glide.with(this)
                .load(pictureUri)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(photoView)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun hideToolbar() {
        appBarLayout.animate()
            .translationY(-appBarLayout.height.toFloat())
            .setDuration(300)
            .withEndAction { appBarLayout.visibility = View.GONE }

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
        appBarLayout.visibility = View.VISIBLE
        appBarLayout.animate()
            .translationY(0f)
            .setDuration(300)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }

        barIsVisible = true
    }
}
