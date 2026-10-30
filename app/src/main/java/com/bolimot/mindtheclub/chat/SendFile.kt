package com.bolimot.mindtheclub.chat

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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.closeKeyboard
import com.bolimot.mindtheclub.functions.copyUri
import com.bolimot.mindtheclub.functions.formatFileSize
import com.bolimot.mindtheclub.functions.getFileDetails
import com.bolimot.mindtheclub.functions.safeUrl
import com.bolimot.mindtheclub.sending.sendObject
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.viewModel.ViewModelProviderHolder
import com.bumptech.glide.Glide
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.net.toUri
import com.bolimot.mindtheclub.functions.showToast

data class FileDetails(
    val fullName: String,
    val extension: String,
    val size: Long
)

class SendFile : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toUserIdListString = intent?.getStringExtra("userId") ?: return
        var filePath= intent?.getStringExtra("filePath") ?: return
        var fromName= intent?.getStringExtra("fromName")
        var messageToForward= intent?.getStringExtra("messageToForward")
        val peerPicturePath= intent?.getStringExtra("peerPicturePath")

        val selectedPeerUserIds = toUserIdListString.split(",")
        if(selectedPeerUserIds.isEmpty()) return

        val uri: Uri = filePath.toUri()
        // A forward sends our stored copy, whose name is a code: the real name comes with the
        // request, from the original message.
        val detail = getFileDetails(contentResolver, uri).let { found ->
            val realName = intent?.getStringExtra("fileName")?.takeIf { it.isNotBlank() && it != "Unknown" }
            if (realName == null) found
            else found.copy(fullName = realName, extension = realName.substringAfterLast('.', found.extension))
        }
        val detailCSV = "${detail.fullName},${detail.extension},${detail.size}"

        val uriToSend = copyUri(uri, this)
        filePath = uriToSend.toString()

        setContentView(R.layout.send_file)

        findViewById<TextView>(R.id.fileExtension).text = detail.extension
        findViewById<TextView>(R.id.fileName).text = detail.fullName
        findViewById<TextView>(R.id.fileSize).text = formatFileSize(detail.size)

        val selectedPeerPictures = peerPicturePath?.split(",") ?: emptyList()
        if(selectedPeerPictures.size == 1){
            Glide.with(this)
                .load(safeUrl(selectedPeerPictures[0]))
                .into(findViewById(R.id.pic))
        }

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val topBar: AppBarLayout = findViewById(R.id.appBarLayout)
        val bottomBar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar_bottom)

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

            if (selectedPeerUserIds.any { it.startsWith("group") } && detail.size > com.bolimot.mindtheclub.tools.MAX_GROUP_MESSAGE_BYTES) {
                showToast(getString(R.string.message_size_limit), this)
                caption.text.clear()
                send.isEnabled = true
                return@setOnClickListener
            }

            sendObject(selectedPeerUserIds, filePath, caption.text.toString(), messageToForward, fromName, lifecycleScope, viewModel,"${Type.FILE}$detailCSV")

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

    private fun keypadOpen(rootView: View): Boolean{
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.rootView.height
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }
}