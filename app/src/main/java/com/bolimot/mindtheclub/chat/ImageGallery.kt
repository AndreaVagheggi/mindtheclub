package com.bolimot.mindtheclub.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.ImageAdapter
import com.bolimot.mindtheclub.functions.getImageRepository
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.viewModel.ImageGalleryViewModel
import com.bolimot.mindtheclub.viewModel.ImageGalleryViewModelFactory
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import java.util.Date
import java.util.Locale

class ImageGalleryActivity : BaseActivity(), ImageAdapter.OnScaleChangeListener {
    private lateinit var recyclerView: LockableRecyclerView
    private lateinit var imageAdapter: ImageAdapter
    private lateinit var dateTextView: TextView
    private lateinit var dayTextView: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var snapHelper: PagerSnapHelper
    private lateinit var userId: String

    private var barIsVisible = true
    private var hidingBars = false
    private var uriToForward: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val messageId = intent?.getStringExtra("messageId") ?: return
        userId = intent?.getStringExtra("userId") ?: return

        setContentView(R.layout.image_gallery)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

        toolbar = findViewById(R.id.toolbar)
        appBarLayout = findViewById(R.id.appBarLayout)

        setSupportActionBar(toolbar)

        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        showToolbar()

        dateTextView = findViewById(R.id.date)
        dayTextView = findViewById(R.id.day)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)

        recyclerView = findViewById(R.id.images)
        recyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        recyclerView.layoutManager?.onSaveInstanceState()?.let {
            recyclerView.layoutManager?.onRestoreInstanceState(null)
        }

        imageAdapter = ImageAdapter(emptyList(), this)
        recyclerView.adapter = imageAdapter

        snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)

        val factory = ImageGalleryViewModelFactory(getImageRepository(this))
        val imageViewModel = ViewModelProvider(this, factory)[ImageGalleryViewModel::class.java]

        imageViewModel.getAllImages(userId).observe(this) { images ->
            imageAdapter.updateImages(images)

            if (images.isNotEmpty()) {
                val startPosition =
                    images.indexOfFirst { it.messageId == messageId }.takeIf { it != -1 } ?: 0

                recyclerView.post {
                    recyclerView.scrollToPosition(startPosition)
                    updateDateText(images[startPosition].date)

                    recyclerView.post {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        val view = layoutManager.findViewByPosition(startPosition)
                        if (view != null) {
                            val snapDistance = snapHelper.calculateDistanceToFinalSnap(layoutManager, view)
                            if (snapDistance != null && (snapDistance[0] != 0 || snapDistance[1] != 0)) {
                                recyclerView.scrollBy(snapDistance[0], snapDistance[1])
                            }
                        }
                    }
                }
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val snappedView = snapHelper.findSnapView(layoutManager)
                    if (snappedView != null) {
                        val position = layoutManager.getPosition(snappedView)

                        val imageItem = imageAdapter.getImageAt(position)
                        imageItem?.let {
                            updateDateText(it.date)
                        }
                    }
                }
            }
        })
    }

    private fun updateDateText(dateInMillis: Long) {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val date = Date(dateInMillis)
        val dateString = sdf.format(date)
        dateTextView.text = dateString

        val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val dayOfWeekString = dayOfWeekFormat.format(date)

        dayTextView.text = dayOfWeekString
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
                getCurrentImageUri()?.let {
                    val intent = Intent(this, SelectPeersForForward::class.java)
                    intent.putExtra("excludedUserId", userId)
                    uriToForward = it
                    getPeersResult.launch(intent)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @SuppressLint("DefaultLocale")
    override fun onScaleChanged(isZoomedIn: Boolean) {
        if (hidingBars) return

        if (isZoomedIn) {
            if (barIsVisible) hideToolbar()
            recyclerView.setScrollingEnabled(false)
        } else {
            if (!barIsVisible) showToolbar()
            recyclerView.setScrollingEnabled(true)
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

        barIsVisible = true
    }

    private fun getCurrentImageUri(): String? {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val snappedView = snapHelper.findSnapView(layoutManager) ?: return null
        val position = layoutManager.getPosition(snappedView)
        val imageItem = imageAdapter.getImageAt(position)
        return imageItem?.url
    }

    private val getPeersResult =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedPeers: List<String> = result.data?.getStringArrayListExtra("selectedPeers") ?: return@registerForActivityResult

            val intent = Intent(this, SendImage::class.java).apply {
                putExtra("imagePath",uriToForward)
                putExtra("userId", selectedPeers.joinToString(","))
            }
            startActivity(intent)
            finish()
        }
    }
}



class LockableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    private var scrollingEnabled = true

    fun setScrollingEnabled(enabled: Boolean) {
        scrollingEnabled = enabled
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        return scrollingEnabled && super.onInterceptTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        return scrollingEnabled && super.onTouchEvent(e)
    }

    override fun fling(velocityX: Int, velocityY: Int): Boolean {
        return scrollingEnabled && super.fling(velocityX, velocityY)
    }
}