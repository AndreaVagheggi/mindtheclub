package com.bolimot.mindtheclub.chat

import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.cardview.widget.CardView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.calculateTargetDimensions
import com.bolimot.mindtheclub.functions.closeKeyboard
import com.bolimot.mindtheclub.functions.safeUrl
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.sending.sendObject
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.viewModel.ViewModelProviderHolder
import com.bumptech.glide.Glide
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar


class SendGif : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toUserIdListString = intent?.getStringExtra("userId") ?: return
        val imagePath= intent?.getStringExtra("gifPath") ?: return
        var fromName= intent?.getStringExtra("fromName")
        var messageToForward= intent?.getStringExtra("messageToForward")
        val peerPicturePath= intent?.getStringExtra("peerPicturePath")

        val selectedPeerUserIds = toUserIdListString.split(",")
        if(selectedPeerUserIds.isEmpty()) return

        setContentView(R.layout.send_gif)

        val selectedPeerPictures = peerPicturePath?.split(",") ?: emptyList()
        if(selectedPeerPictures.size == 1){
            Glide.with(this)
                .load(safeUrl(selectedPeerPictures[0]))
                .into(findViewById(R.id.pic))
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val topBar: AppBarLayout = findViewById(R.id.appBarLayout)
        val bottomBar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar_bottom)

        val image = findViewById<ImageView>(R.id.image)
        val rootView = findViewById<CoordinatorLayout>(R.id.container)

        val send = findViewById<ImageButton>(R.id.send)
        val caption = findViewById<EditText>(R.id.editTextMessage)

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

        val dimension = calculateTargetDimensions(imagePath.toUri(), 230) ?: return

        Glide.with(this)
            .load(safeUrl(imagePath))
            .override(dimension.first, dimension.second)
            .into(image)

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
                val size = com.bolimot.mindtheclub.functions.getFileDetails(contentResolver,
                    imagePath.toUri()).size
                if (size > com.bolimot.mindtheclub.tools.MAX_GROUP_MESSAGE_BYTES) {
                    showToast(getString(R.string.message_size_limit), this)
                    caption.text.clear()
                    send.isEnabled = true
                    return@setOnClickListener
                }
            }

            sendObject(
                selectedPeerUserIds,
                imagePath,
                caption.text.toString(),
                messageToForward,
                fromName,
                lifecycleScope,
                viewModel,
                Type.GIF
            )
            finish()
        }
    }

    /**
     * Handles action bar item clicks.
     *
     * @param item The menu item that was selected.
     * @return boolean Return false to allow normal menu processing to proceed,
     *         true to consume it here.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Checks if the keyboard is open.
     *
     * @param rootView The root view of the layout.
     * @return True if the keyboard is open, false otherwise.
     */
    private fun keypadOpen(rootView: View): Boolean{
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.rootView.height
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }
}