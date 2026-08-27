package com.bolimot.mindtheclub.chat

import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.net.Uri
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.closeKeyboard
import com.bolimot.mindtheclub.functions.VideoCompressor
import com.bolimot.mindtheclub.sending.sendObject
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.viewModel.ViewModelProviderHolder
import com.bumptech.glide.Glide
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.net.toUri
import com.bolimot.mindtheclub.functions.showToast
import kotlinx.coroutines.launch

class SendVideo : BaseActivity() {
    // The transcode path below goes through VideoCompressor, which carries media3
    // @UnstableApi. Same opt-in as MainActivity.startApplication().
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toUserIdListString = intent?.getStringExtra("userId") ?: return
        val imagePath= intent?.getStringExtra("imagePath") ?: return
        var fromName= intent?.getStringExtra("fromName")
        var messageToForward= intent?.getStringExtra("messageToForward")
        val peerPicturePath= intent?.getStringExtra("peerPicturePath")


        val selectedPeerUserIds = toUserIdListString.split(",")
        if(selectedPeerUserIds.isEmpty()) return

        setContentView(R.layout.send_video)

        val selectedPeerPictures = peerPicturePath?.split(",") ?: emptyList()
        if(selectedPeerPictures.size == 1){
            Glide.with(this)
                .load(selectedPeerPictures[0])
                .into(findViewById(R.id.pic))
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val topBar: AppBarLayout = findViewById(R.id.appBarLayout)
        val bottomBar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar_bottom)

        val image = findViewById<VideoView>(R.id.image)
        val rootView = findViewById<ConstraintLayout>(R.id.container)

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
        } else {
            findViewById<CardView>(R.id.chat_insert).visibility = View.GONE
        }

        setSupportActionBar(toolbar)

        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            if(keypadOpen(rootView)){
                val colorStateList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_opaque))
                val color = ContextCompat.getColor(this, R.color.primary_opaque)
                topBar.backgroundTintList = colorStateList
                bottomBar.backgroundTintList = colorStateList
                topBar.setBackgroundColor(color)
                bottomBar.setBackgroundColor(color)
            }
            else {
                val colorStateList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_transparent))
                val color = ContextCompat.getColor(this, R.color.primary_transparent)
                topBar.backgroundTintList = colorStateList
                bottomBar.backgroundTintList = colorStateList
                topBar.setBackgroundColor(color)
                bottomBar.setBackgroundColor(color)
            }
        }

        image.setVideoPath(imagePath)

        val mediaController = MediaController(this)
        image.setMediaController(mediaController)
        mediaController.setAnchorView(image)

        image.setOnPreparedListener {
            image.seekTo(1)
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)

        val compressOverlay = findViewById<View>(R.id.compressOverlay)
        val compressBar = findViewById<ProgressBar>(R.id.compressBar)

        send.setOnClickListener {
            send.isEnabled = false
            val viewModel = ViewModelProviderHolder.messageViewModel

            closeKeyboard(this)

            // Transcode BEFORE the size check, not after. A 60 MB clip that comes
            // out at 8 MB used to be refused for a limit it would never have hit;
            // now the limit judges what actually goes on the wire. A forward is
            // left alone: that video already went through this once when it was
            // first sent, and a second pass would only shave quality.
            val isForward = !messageToForward.isNullOrEmpty()

            lifecycleScope.launch {
                var pathToSend = imagePath
                val sourceUri = imagePath.toUri()
                val originalSize = VideoCompressor.sizeOf(this@SendVideo, sourceUri)

                if (!isForward && VideoCompressor.isWorthCompressing(this@SendVideo, sourceUri, originalSize)) {
                    compressBar.progress = 0
                    compressOverlay.visibility = View.VISIBLE
                    val compressed = VideoCompressor.compress(this@SendVideo, sourceUri) { percent ->
                        compressBar.progress = percent
                    }
                    compressOverlay.visibility = View.GONE
                    // null means "keep the original": a failed or pointless
                    // transcode must never stop a message from being sent.
                    if (compressed != null) pathToSend = Uri.fromFile(compressed).toString()
                }

                if (selectedPeerUserIds.any { it.startsWith("group") }) {
                    val size = VideoCompressor.sizeOf(this@SendVideo, pathToSend.toUri())
                    if (size > com.bolimot.mindtheclub.tools.MAX_GROUP_MESSAGE_BYTES) {
                        showToast(getString(R.string.message_size_limit), this@SendVideo)
                        caption.text.clear()
                        send.isEnabled = true
                        return@launch
                    }
                }

                sendObject(selectedPeerUserIds, pathToSend, caption.text.toString(), messageToForward, fromName, lifecycleScope, viewModel, "video")
                setResult(RESULT_OK)
                finish()
            }
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

    private fun keypadOpen(rootView: View): Boolean{
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.rootView.height
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }
}