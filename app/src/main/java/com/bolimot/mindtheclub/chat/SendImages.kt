package com.bolimot.mindtheclub.chat

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.cardview.widget.CardView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.SendImagesAdapter
import com.bolimot.mindtheclub.adapters.SendPreviewImagesAdapter
import com.bolimot.mindtheclub.dataModels.ImageItem
import com.bolimot.mindtheclub.functions.closeKeyboard
import com.bolimot.mindtheclub.functions.safeUrl
import com.bolimot.mindtheclub.functions.toCSVString
import com.bolimot.mindtheclub.sending.sendMultipleImage
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.viewModel.ViewModelProviderHolder
import com.bumptech.glide.Glide
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import android.content.Intent
import androidx.core.net.toUri
import com.bolimot.mindtheclub.functions.showToast

class SendImages : BaseActivity() {

    private lateinit var images: RecyclerView
    private lateinit var imagesPreview: RecyclerView
    private lateinit var previewAdapter: SendPreviewImagesAdapter
    private lateinit var imagesAdapter: SendImagesAdapter
    private val uriList: ArrayList<Uri> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toUserIdListString = intent?.getStringExtra("userId") ?: return
        val uriListString = intent?.getStringExtra("uriList") ?: return
        var fromName= intent?.getStringExtra("fromName")
        var messageToForward= intent?.getStringExtra("messageToForward")
        val peerPicturePath= intent?.getStringExtra("peerPicturePath")

        val selectedPeerUserIds = toUserIdListString.split(",")
        if(selectedPeerUserIds.isEmpty()) return

        uriList.addAll(uriListString.split(",").map { it.trim().toUri() })

        setContentView(R.layout.send_images)

        val selectedPeerPictures = peerPicturePath?.split(",") ?: emptyList()
        if(selectedPeerPictures.size == 1){
            Glide.with(this)
                .load(safeUrl(selectedPeerPictures[0]))
                .into(findViewById(R.id.pic))
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val topBar: AppBarLayout = findViewById(R.id.appBarLayout)
        val bottomBar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar_bottom)

        images = findViewById(R.id.images)
        imagesPreview = findViewById(R.id.images_preview)

        val rootView = findViewById<CoordinatorLayout>(R.id.container)

        val send = findViewById<ImageButton>(R.id.send)
        val caption = findViewById<EditText>(R.id.editTextMessage)

        intent?.getStringExtra("caption")?.let { caption.setText(it) }

        if(!messageToForward.isNullOrEmpty()) {
            findViewById<TextView>(R.id.chat_nameAttached).text = fromName
            findViewById<TextView>(R.id.chat_textAttached).text = messageToForward
            findViewById<CardView>(R.id.chat_insert).visibility = View.VISIBLE


            findViewById<ImageButton>(R.id.close).setOnClickListener {
                findViewById<CardView>(R.id.chat_insert).visibility = View.GONE

                messageToForward = null
                fromName = null
            }
        }

        setSupportActionBar(toolbar)

        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            if (keypadOpen(rootView)) {
                val colorStateList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_opaque))
                val color = ContextCompat.getColor(this, R.color.primary_opaque)
                topBar.backgroundTintList = colorStateList
                bottomBar.backgroundTintList = colorStateList
                topBar.setBackgroundColor(color)
                bottomBar.setBackgroundColor(color)
            } else {
                val colorStateList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_transparent))
                val color = ContextCompat.getColor(this, R.color.primary_transparent)
                topBar.backgroundTintList = colorStateList
                bottomBar.backgroundTintList = colorStateList
                topBar.setBackgroundColor(color)
                bottomBar.setBackgroundColor(color)
            }
        }

        val imageItems = uriList.map { ImageItem(it) }

        images.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        imagesAdapter = SendImagesAdapter(imageItems)
        images.adapter = imagesAdapter

        previewAdapter = SendPreviewImagesAdapter(imageItems, { position ->
            images.scrollToPosition(position)
        }, { position ->
            onItemReselect(position)
        }, imagesPreview)

        imagesPreview.layoutManager = GridLayoutManager(this, 1, GridLayoutManager.HORIZONTAL, false)
        imagesPreview.adapter = previewAdapter

        val pagerSnapHelper = PagerSnapHelper()
        pagerSnapHelper.attachToRecyclerView(images)

        images.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visiblePosition = layoutManager.findFirstVisibleItemPosition()
                previewAdapter.setSelectedPosition(visiblePosition)
            }
        })

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)

        send.setOnClickListener {
            send.isEnabled = false
            val viewModel =ViewModelProviderHolder.messageViewModel

            closeKeyboard(this)

            if (selectedPeerUserIds.any { it.startsWith("group") }) {
                var totalSize = 0L
                for (u in uriList) {
                    totalSize += com.bolimot.mindtheclub.functions.getFileDetails(contentResolver, u).size
                }
                if (totalSize > 52428800L) {
                    showToast(getString(R.string.message_size_limit), this)
                    caption.text.clear()
                    send.isEnabled = true
                    return@setOnClickListener
                }
            }

            viewModel?.let {
                sendMultipleImage(
                    selectedPeerUserIds, // List of peers I need to send this message to
                    toCSVString(uriList), // List of URI pointing to the images
                    caption.text.toString(), // Text of the message
                    messageToForward, // Eventual original text message in case of Forward action
                    fromName,  // Eventual original sender name in case of Forward action
                    lifecycleScope,
                    viewModel
                )
            }
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun keypadOpen(rootView: View): Boolean {
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.rootView.height
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun onItemReselect(currentPosition: Int) {
        uriList.removeAt(currentPosition)

        // If only 1 image left, switch to single image screen
        if (uriList.size == 1) {
            val singleImageIntent = Intent(this, SendImage::class.java).apply {
                putExtra("imagePath", uriList[0].toString())
                putExtra("userId", this@SendImages.intent?.getStringExtra("userId"))
                putExtra("peerPicturePath", this@SendImages.intent?.getStringExtra("peerPicturePath"))
                putExtra("fromName", this@SendImages.intent?.getStringExtra("fromName"))
                putExtra("messageToForward", this@SendImages.intent?.getStringExtra("messageToForward"))
            }
            startActivity(singleImageIntent)
            finish()
            return
        }

        val updatedImageItems = uriList.map { ImageItem(it) }

        imagesAdapter.updateImageList(updatedImageItems)
        previewAdapter.updateImageList(updatedImageItems)

        imagesAdapter.notifyDataSetChanged()
        previewAdapter.notifyDataSetChanged()
    }
}
