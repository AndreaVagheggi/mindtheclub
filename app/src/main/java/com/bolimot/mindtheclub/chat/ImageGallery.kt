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

private const val KEY_GALLERY_POSITION = "galleryPosition"

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

    /**
     * Which photo is on screen, and whether we have already placed the user.
     *
     * The opening position used to be derived from the intent's messageId on
     * every pass, and that can only ever resolve to the FIRST photo of an album,
     * because all the photos of one album share a single messageId. So a
     * rotation, which rebuilds the activity from scratch, threw the reader from
     * the third photo back to the first. The list observer made it worse: the
     * positioning lived inside it, so it re-ran on every change to the image
     * table and yanked the reader back mid-browse, without them touching
     * anything.
     */
    private var currentPosition = RecyclerView.NO_POSITION
    private var restoredPosition = RecyclerView.NO_POSITION
    private var hasPositioned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val messageId = intent?.getStringExtra("messageId") ?: return
        userId = intent?.getStringExtra("userId") ?: return

        // A rebuild (rotation above all) brings the reader back where they were.
        // Absent on a genuine open, and then the intent's messageId decides.
        restoredPosition =
            savedInstanceState?.getInt(KEY_GALLERY_POSITION, RecyclerView.NO_POSITION)
                ?: RecyclerView.NO_POSITION

        setContentView(R.layout.image_gallery)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // Behaviour before hide, which is the order the controller expects.
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
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

        // The block that used to sit here restored null into the layout manager,
        // deliberately throwing away the position Android had kept across the
        // rebuild. That is now handled explicitly, through KEY_GALLERY_POSITION.

        imageAdapter = ImageAdapter(emptyList(), this)
        recyclerView.adapter = imageAdapter

        snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)

        val factory = ImageGalleryViewModelFactory(getImageRepository(this))
        val imageViewModel = ViewModelProvider(this, factory)[ImageGalleryViewModel::class.java]

        imageViewModel.getAllImages(userId).observe(this) { images ->
            imageAdapter.updateImages(images)

            // Place the reader ONCE. This observer fires again on every change to
            // the image table, and it used to re-scroll each time: a photo
            // arriving in the chat while the album was open threw the reader back
            // to its first frame with no action on their part.
            if (images.isNotEmpty() && !hasPositioned) {
                hasPositioned = true

                val startPosition = if (restoredPosition in images.indices) {
                    restoredPosition
                } else {
                    // All the photos of one album carry the same messageId, so
                    // this can only land on the album's first frame. It is the
                    // right answer when opening, and the wrong one after a
                    // rotation, which is why the saved position wins above.
                    images.indexOfFirst { it.messageId == messageId }.takeIf { it != -1 } ?: 0
                }
                currentPosition = startPosition

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
                        // Remembered here, where the photo on screen is already
                        // being computed for the date header, so a rebuild can
                        // put the reader back on it.
                        currentPosition = position

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

    /**
     * Carries the photo on screen across a rebuild. NO_POSITION is saved happily:
     * on the way back it simply fails the `in images.indices` test and the opening
     * rule takes over, which is the correct answer when nothing was ever shown.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_GALLERY_POSITION, currentPosition)
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