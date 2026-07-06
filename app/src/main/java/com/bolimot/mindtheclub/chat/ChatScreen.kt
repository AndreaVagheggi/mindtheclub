@file:Suppress("DEPRECATION")
package com.bolimot.mindtheclub.chat

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.DateNavigatorAdapter
import com.bolimot.mindtheclub.adapters.MessagesAdapter
import com.bolimot.mindtheclub.adapters.TypingIndicatorAdapter
import com.bolimot.mindtheclub.contactAcquisition.NewPeerDialog
import com.bolimot.mindtheclub.customViews.AccessibleImageButton
import com.bolimot.mindtheclub.customViews.CustomLinearLayoutManager
import com.bolimot.mindtheclub.customViews.MyCustomRecyclerView
import com.bolimot.mindtheclub.customViews.RichEditText
import com.bolimot.mindtheclub.customViews.SlideUpItemAnimator
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.buildMultiImagePreview
import com.bolimot.mindtheclub.functions.copyUri
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.emptyString
import com.bolimot.mindtheclub.functions.ensureCallPermissions
import com.bolimot.mindtheclub.functions.extractUrl
import com.bolimot.mindtheclub.functions.fetchWebsiteInfo
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getReactionViewModel
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.functions.safeUrl
import com.bolimot.mindtheclub.functions.saveBitmapFromUri
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.functions.startBlinkingAnimation
import com.bolimot.mindtheclub.functions.toImage
import com.bolimot.mindtheclub.functions.typeHasImageAttached
import com.bolimot.mindtheclub.functions.vectorToBitmap
import com.bolimot.mindtheclub.notifications.MessageReceivedNotification
import com.bolimot.mindtheclub.sending.cancelIncomingTransfer
import com.bolimot.mindtheclub.sending.cancelOutgoingSend
import com.bolimot.mindtheclub.sending.forwardAudio
import com.bolimot.mindtheclub.sending.forwardText
import com.bolimot.mindtheclub.sending.forwardWeb
import com.bolimot.mindtheclub.sending.notifyGroupTyping
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.sendObject
import com.bolimot.mindtheclub.sending.sendReaction
import com.bolimot.mindtheclub.sending.sendText
import com.bolimot.mindtheclub.sharing.WebPreviewCache
import com.bolimot.mindtheclub.sharing.shareContent
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.Broadcast
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.viewModel.MessageViewModel
import com.bolimot.mindtheclub.viewModel.MessageViewModelFactory
import com.bolimot.mindtheclub.viewModel.ReactionViewModel
import com.bolimot.mindtheclub.viewModel.ViewModelProviderHolder
import com.bolimot.mindtheclub.views.AppTab
import com.bolimot.mindtheclub.views.ImagesTab
import com.bolimot.mindtheclub.views.PeerView
import com.bolimot.mindtheclub.views.VideoTab
import com.bolimot.mindtheclub.voip.startCall
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors

@Suppress("PrivatePropertyName")
class ChatScreen : BaseActivity(), MessagesAdapter.OnItemClickListener {
    companion object {
        var shouldFinish = false
    }

    private lateinit var messagesAdapter: MessagesAdapter
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var recyclerView: MyCustomRecyclerView
    private lateinit var chatLayoutBackground: LinearLayout
    private lateinit var lockingView: CardView
    private lateinit var editText: RichEditText
    private lateinit var attach: ImageButton
    private lateinit var camera: ImageButton
    private lateinit var recordingView: CardView
    private lateinit var microphone: AccessibleImageButton
    private lateinit var microphoneActive: AccessibleImageButton
    private lateinit var blinkAnimation: Animation
    private lateinit var sendButton: ImageButton
    private lateinit var cancelButton: ImageButton
    private lateinit var stopRecording: ImageButton
    private lateinit var rewind: ImageButton
    private lateinit var recordingImage: ImageView
    private lateinit var upIcon: ImageView
    private lateinit var gotoBottom: FloatingActionButton
    private lateinit var bottomAppBar: BottomAppBar
    private lateinit var remoteUserId: String
    private lateinit var name: String
    private lateinit var peerPicturePath: String
    private lateinit var replyId: String
    private lateinit var messageViewModel: MessageViewModel
    private lateinit var recordingChronometer: Chronometer
    private lateinit var reactionViewModel: ReactionViewModel
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var bottomControlsContainer: LinearLayout
    private lateinit var attachImage: Button
    private lateinit var attachVideo: Button
    private lateinit var attachFile: Button
    private lateinit var attachContact: Button
    private lateinit var typingIndicatorAdapter: TypingIndicatorAdapter
    private lateinit var peerViewLauncher: ActivityResultLauncher<Intent>

    private var mediaRecorder: MediaRecorder? = null
    private var reactionBarView: View? = null
    private var audioFilePath: String? = null
    private var isToggledMenu = false
    private var isRecording = false
    private var isLocked = false
    private var forwardImages = false
    private var originalMenuRes = R.menu.chat
    private var toggledMenuRes: Int? = null
    private var nameAttached: String? = null
    private var textAttached: String? = null
    private var subType: String? = null
    private var type: String? = null
    private var textToForward: String? = null
    private var webUrl: String? = null
    private var nameToForward: String? = null
    private var messageToForward: Message? = null
    private var imageUrl: String = ""
    private var webTitle: String = ""
    private var webDescription: String = ""
    private var text: String = emptyString()
    private val handler = Handler(Looper.getMainLooper())
    private var lockToBottom = true
    private var keyboardIsVisible = false
    private var typingTimer: Timer? = null
    private var isCurrentlyTyping = false
    private val typingTimeoutMs = 2000L
    private var incomingMessage = false
    private var hideToolbarRunnable: Runnable? = null
    private var isToolbarVisible = false
    private val hideToolbarHandler = Handler(Looper.getMainLooper())
    private var isAtBottom = true
    private var initialLoadComplete = false
    private val TOOLBAR_HIDE_DELAY = 5000L
    private var peerPicture: Bitmap? = null
    private var dateNavigatorView: View? = null
    private var isSearchOpen = false
    private var targetMessageId: String? = null
    private val typingWatchdogRunnable = Runnable { typingIndicatorAdapter.setTyping(false) }
    private val typingWatchdogMs = 8000L
    private var webPreviewDismissed = false
    private var pendingCaptionText: String? = null

    private fun listenToRemoteCallEvents(){
        val intentFilter = IntentFilter()
        intentFilter.addAction(Broadcast.ACTION_START_TYPING)
        intentFilter.addAction(Broadcast.ACTION_STOP_TYPING)
        intentFilter.addAction(Broadcast.ACTION_FINISH_CALL)

        debugLine("ChatScreen", "Listening to remote call events")

        LocalBroadcastManager.getInstance(this).registerReceiver(
            remoteCallEventsReceiver,
            intentFilter
        )
    }

    private val remoteCallEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            debugLine("ChatScreen", "Call control received: ${intent.action}")

            val incomingUserId = intent.getStringExtra("userId")
            val incomingGroupId = intent.getStringExtra("chatGroupId")

            val isRelevant = if (remoteUserId.startsWith("group")) {
                !incomingGroupId.isNullOrEmpty() && incomingGroupId == remoteUserId
            } else {
                incomingUserId == remoteUserId
            }
            if (!isRelevant) return

            when (intent.action) {
                Broadcast.ACTION_START_TYPING -> {
                    handler.removeCallbacks(typingWatchdogRunnable)
                    handler.postDelayed(typingWatchdogRunnable, typingWatchdogMs)
                    typingIndicatorAdapter.setTyping(true)
                    if (!recyclerView.canScrollVertically(1)) {
                        recyclerView.smoothScrollToPosition(0)
                    }
                }

                Broadcast.ACTION_STOP_TYPING -> {
                    handler.removeCallbacks(typingWatchdogRunnable)
                    typingIndicatorAdapter.setTyping(false)
                }

                Broadcast.ACTION_FINISH_CALL -> {
                    debugLine("ChatScreen", "Call finished, closing the App")
                    finishAndRemoveTask()
                }
            }
        }
    }

    override fun shouldApplyBottomInsetPadding(): Boolean {
        return true
    }

    override fun onImeVisibilityChanged(visible: Boolean) {
        if (visible) {
            keyboardIsVisible = true
            // attach stays visible — camera hides (WhatsApp behaviour)
            camera.visibility = ImageButton.INVISIBLE
        } else {
            keyboardIsVisible = false
            camera.visibility = ImageButton.VISIBLE
            if (editText.text.isNullOrEmpty()) {
                microphone.visibility = ImageButton.VISIBLE
            }
            editText.setRichContentEnabled(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (::remoteUserId.isInitialized) {
            outState.putString("KEY_REMOTE_USER_ID", remoteUserId)
            outState.putString("KEY_NAME", name)
            outState.putString("KEY_PICTURE_PATH", peerPicturePath)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            remoteUserId = savedInstanceState.getString("KEY_REMOTE_USER_ID")
                ?: throw IllegalStateException("Activity restored without a remoteUserId.")
            name = savedInstanceState.getString("KEY_NAME")
                ?: throw IllegalStateException("Activity restored without a name.")
            peerPicturePath = savedInstanceState.getString("KEY_PICTURE_PATH") ?: ""

        } else {
            remoteUserId = intent?.getStringExtra("userId")
                ?: throw IllegalArgumentException("ChatScreen must be started with a 'userId' extra.")

            name = intent?.getStringExtra("name")
                ?: runBlocking(Dispatchers.IO) {
                    val userId = intent?.getStringExtra("userId") ?: ""
                    getPeerViewModel().getPeer(userId)?.name ?: ""
                }

            peerPicturePath = intent?.getStringExtra("picture")
                ?: runBlocking(Dispatchers.IO) {
                    getPeerViewModel().getPeer(remoteUserId)?.picture ?: ""
                }

            targetMessageId = intent?.getStringExtra("targetMessageId")
        }

        text = intent?.getStringExtra("text") ?: emptyString()
        val bio = intent?.getStringExtra("bio")

        peerPicture = if (remoteUserId.startsWith("group")) {
            vectorToBitmap(this, R.drawable.group_placeholder)
        } else if (peerPicturePath.isNotEmpty()) {
            loadBitmap(peerPicturePath.toUri(), this)
        } else {
            null
        }

        if (remoteUserId.startsWith("group")) {
            loadGroupPicture()
        }

        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let {
                handleSelectedFile(it)
            }
        }

        val messageRepository = getMessageRepository(this)

        val factory = MessageViewModelFactory(
            Application(),
            messageRepository,
            MySelf.userId()!!,
            remoteUserId
        )
        messageViewModel = ViewModelProvider(this, factory)[MessageViewModel::class.java]

        reactionViewModel = getReactionViewModel()

        ViewModelProviderHolder.messageViewModel = messageViewModel

        setContentView(R.layout.screen_chat)

        rootLayout = findViewById(R.id.root_layout)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)

        val searchContainer = findViewById<LinearLayout>(R.id.searchContainer)
        val searchEditText = findViewById<EditText>(R.id.searchEditText)
        val searchClose = findViewById<ImageButton>(R.id.searchClose)

        searchEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_search, 0, 0, 0
        )

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val clearIcon = if (s.isNullOrEmpty()) null
                else ContextCompat.getDrawable(this@ChatScreen, R.drawable.ic_clear)
                searchEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    ContextCompat.getDrawable(this@ChatScreen, R.drawable.ic_search),
                    null, clearIcon, null
                )
                messageViewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })

        searchEditText.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    val clearDrawable = searchEditText.compoundDrawablesRelative[2]
                    if (clearDrawable != null) {
                        val clearButtonStart = searchEditText.width - searchEditText.paddingEnd - clearDrawable.intrinsicWidth
                        if (event.x >= clearButtonStart) {
                            searchEditText.text.clear()
                            v.performClick()
                            return true
                        }
                    }
                }
                return false
            }
        })

        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                true
            } else false
        }

        searchClose.setOnClickListener {
            closeSearch(searchContainer, searchEditText)
        }
        sendButton = findViewById(R.id.send)
        cancelButton = findViewById(R.id.cancel)
        stopRecording = findViewById(R.id.cancelRecording)
        rewind = findViewById(R.id.rewind)
        attach = findViewById(R.id.attach)
        camera = findViewById(R.id.camera)
        upIcon = findViewById(R.id.up)
        recordingImage = findViewById(R.id.recordingImage)
        gotoBottom = findViewById(R.id.gotoBottom)
        microphone = findViewById(R.id.microphone)
        microphoneActive = findViewById(R.id.microphone_active)
        editText = findViewById(R.id.editTextMessage)
        recyclerView = findViewById(R.id.recyclerView)
        bottomAppBar = findViewById(R.id.bottomAppBar)
        recordingView = findViewById(R.id.recordingView)
        chatLayoutBackground = findViewById(R.id.chat_layout_background)
        lockingView = findViewById(R.id.locking_view)
        recordingChronometer = findViewById(R.id.recordingChronometer)
        bottomControlsContainer = findViewById(R.id.bottom_controls_container)
        attachImage = findViewById(R.id.attach_image)
        attachVideo = findViewById(R.id.attach_video)
        attachFile = findViewById(R.id.attach_file)
        attachContact = findViewById(R.id.attach_contact)

        val richContentListener = object : RichEditText.OnRichContentListener {
            override fun onRichContentInserted(content: ContentInfoCompat): ContentInfoCompat? {
                handlePasteContent(content)
                return null
            }
        }

        attachImage.setOnClickListener{
            cancelHideToolbarTimer()
            hideToolbarWithAnimation()

            // Capture any typed text to pass as caption to the Send screen
            pendingCaptionText = editText.text?.toString()?.takeIf { it.isNotBlank() }

            val intent = Intent(this, ImagesTab::class.java).apply {
                putExtra("multipleSelection", true)
            }
            getImageResult.launch(intent)
        }

        attachVideo.setOnClickListener{
            cancelHideToolbarTimer()
            hideToolbarWithAnimation()

            // Capture any typed text to pass as caption to the Send screen
            pendingCaptionText = editText.text?.toString()?.takeIf { it.isNotBlank() }

            val intent = Intent(this, VideoTab::class.java).apply {
                putExtra("multipleSelection", false)
            }
            getVideoResult.launch(intent)
        }

        attachFile.setOnClickListener{
            cancelHideToolbarTimer()
            hideToolbarWithAnimation()

            // Capture any typed text to pass as caption to the Send screen
            pendingCaptionText = editText.text?.toString()?.takeIf { it.isNotBlank() }

            showFilePicker()
        }

        attachContact.setOnClickListener {
            cancelHideToolbarTimer()
            hideToolbarWithAnimation()

            messageToForward = Message(0,"","","","","",0,"","","","",Type.CONTACT,"",0L,"","")

            val intent = Intent(this@ChatScreen, SelectPeersForForward::class.java)
            intent.putExtra("excludedUserId", remoteUserId)
            getPeersResult.launch(intent)
        }

        editText.setOnRichContentListener(richContentListener)

        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                s?.getSpans(0, s.length, URLSpan::class.java)?.forEach { span ->
                    s.removeSpan(span)
                }

                s?.let {
                    Linkify.addLinks(it, Linkify.WEB_URLS)
                }

                editText.movementMethod = LinkMovementMethod.getInstance()

                s?.toString()?.let { text ->
                    if(webUrl == null && !webPreviewDismissed) {
                        webUrl = extractUrl(text)
                        if (webUrl != null) {
                            showPlaceholder()
                            CoroutineScope(Dispatchers.Main).launch {
                                imageUrl = ""
                                webTitle = ""
                                webDescription = ""
                                val websiteInfo = fetchWebsiteInfo(webUrl!!, this@ChatScreen)

                                imageUrl = websiteInfo.imageUrl
                                webTitle = websiteInfo.title
                                webDescription = websiteInfo.description

                                updateUI(webTitle, webDescription, imageUrl)
                            }
                        }
                    }

                    if (webPreviewDismissed && extractUrl(text) == null) {
                        webPreviewDismissed = false
                    }
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) {
                    sendButton.visibility = ImageButton.INVISIBLE
                    microphone.visibility = ImageButton.VISIBLE

                    stopTyping()
                } else {
                    sendButton.visibility = ImageButton.VISIBLE
                    microphone.visibility = ImageButton.INVISIBLE

                    handleTypingState()
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        microphone.setOnTouchListener(micTouchListener)
        microphone.setOnClickListener { }

        stopRecording.setOnClickListener {
            cancelRecording()
        }

        blinkAnimation = AlphaAnimation(1.0f, 0.0f).apply {
            duration = 500
            interpolator = LinearInterpolator()
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        handleIncomingSharedText()

        if (Build.VERSION.SDK_INT < 35) {
            setKeyboardVisibilityListener(rootLayout) { isKeyboardVisible ->
                if (isKeyboardVisible) {
                    keyboardIsVisible = true

                    // attach stays visible — camera hides (WhatsApp behaviour)
                    camera.visibility = ImageButton.INVISIBLE

                } else {
                    keyboardIsVisible = false

                    camera.visibility = ImageButton.VISIBLE

                    if (editText.text.isNullOrEmpty()) {
                        microphone.visibility = ImageButton.VISIBLE
                    }
                    editText.setRichContentEnabled(true)
                }
            }
        }

        val profilePic: ImageView = findViewById(R.id.profilePic)
        peerPicture?.let {
            profilePic.setImageBitmap(peerPicture)
        }

        findViewById<TextView>(R.id.name).text = name

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBarLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val chatBackground = ContextCompat.getDrawable(this, R.drawable.chat_background) as BitmapDrawable
        chatBackground.alpha = 20
        rootLayout.background = chatBackground

        peerViewLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    finish()
                }
            }

        messagesAdapter = MessagesAdapter(this, this, messageRepository )

        typingIndicatorAdapter = TypingIndicatorAdapter()

        recyclerView.apply {
            layoutManager = CustomLinearLayoutManager(this@ChatScreen).apply {
                stackFromEnd = false
                reverseLayout = true
            }
            adapter = ConcatAdapter(typingIndicatorAdapter, messagesAdapter)
            visibility = View.INVISIBLE
        }

        messagesAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (!initialLoadComplete) return

                if (positionStart == 0 && isAtBottom) {
                    recyclerView.post {
                        recyclerView.smoothScrollToPosition(0)
                    }
                }
            }
        })

        recyclerView.itemAnimator = SlideUpItemAnimator()

        messagesAdapter.attachSwipeToReply(recyclerView)

        recyclerView.setOnTouchListener { view, _ ->
            lockToBottom = false
            recyclerView.setOnTouchListener(null)
            view.performClick()
            false
        }

        gotoBottom.setOnClickListener {
            scrollToBottom()
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as CustomLinearLayoutManager

                val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
                if(lockToBottom && firstVisiblePosition != 0) {
                    recyclerView.scrollToPosition(0)
                }

                isAtBottom = !recyclerView.canScrollVertically(1)

                if(isAtBottom) {
                    setGotoBottomFAB(Status.INVISIBLE)
                } else {
                    if(dy < 0) setGotoBottomFAB(Status.VISIBLE)
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val intent = Intent(this@ChatScreen, AppTab::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        })

        profilePic.setOnClickListener {
            val intent = Intent(this, PeerView::class.java).apply {
                putExtra("userId", remoteUserId)
                putExtra("name", name)
                putExtra("bio", bio)
                putExtra("picture", peerPicturePath)
            }

            peerViewLauncher.launch(intent)
        }

        sendButton.setOnClickListener {

            stopTyping()

            if (isRecording) {
                stopRecording()
                sendAudioFile()
                return@setOnClickListener
            }

            if (subType != SubType.REPLY) {
                replyId = ""
            }

            if (extractUrl(editText.text.toString()) != null) {
                type = Type.WEB
                replyId = imageUrl
                nameAttached = webTitle
                textAttached = webDescription
            } else {
                type = Type.TEXT
            }

            sendText(this,
                remoteUserId,
                editText,
                textAttached,
                nameAttached,
                subType,
                lifecycleScope,
                messageViewModel,
                replyId,
                type!!
            )

            textAttached = null
            nameAttached = null
            type = null
            subType = null
            replyId = ""
            webUrl = null

            findViewById<CardView>(R.id.chat_insert).visibility = View.GONE
            editText.setRichContentEnabled(true)
        }

        attach.setOnClickListener {
            if (isToolbarVisible) {
                hideToolbarWithAnimation()
                cancelHideToolbarTimer()
            } else {
                showToolbarWithAnimation()
                startHideToolbarTimer()
            }
        }

        camera.setOnClickListener {
            val intent = Intent(this, ChatCamera::class.java).apply {
                putExtra("userId", remoteUserId)
                putExtra("name", name)
                putExtra("peerPicturePath", peerPicturePath)
            }
            startActivity(intent)
        }

        lifecycleScope.launch {
            messageViewModel.messages.collectLatest { pagingData ->
                messagesAdapter.submitData(pagingData)
            }
        }

        lifecycleScope.launch {
            messagesAdapter.loadStateFlow
                .filter { it.refresh is androidx.paging.LoadState.NotLoading }
                .take(1)
                .collect {
                    recyclerView.post {
                        val lm = recyclerView.layoutManager as LinearLayoutManager
                        lm.scrollToPositionWithOffset(0, 0)
                        recyclerView.postDelayed({
                            recyclerView.visibility = View.VISIBLE
                            initialLoadComplete = true
                        }, 150)
                    }
                }
        }

        messageViewModel.getNewMessageOutEvent().observe(this) { message ->
            message?.let {
                isAtBottom = true
            }
        }

        messageViewModel.getNewMessageInEvent().observe(this) { message ->
            message?.let {
                incomingMessage = true
                lifecycleScope.launch {
                    debugLine("ChatScreen", "New message while chat open, Sending seen notification")
                    messageViewModel.sendSeenNotificationForUnseenMessages(remoteUserId = remoteUserId)
                }

                if (recyclerView.canScrollVertically(1)) {
                    setGotoBottomFAB(Status.HIGHLIGHT)
                }
            }
        }

        messageViewModel.statusUpdate.observe(this) { (messageId, newStatus) ->
            messagesAdapter.updateMessageStatus(messageId, newStatus)
        }

        messageViewModel.reactionUpdate.observe(this) { (messageId, emoji) ->
            messagesAdapter.updateMessageReaction(messageId, emoji)
        }

        startHandleKeyboard()
        setupToolbarAutoHide()

        targetMessageId?.let { msgId ->
            lifecycleScope.launch {
                delay(600)
                scrollToTargetMessage(msgId)
            }
        }
    }

    private fun scrollToTargetMessage(messageId: String) {
        lockToBottom = false
        lifecycleScope.launch {
            val targetPosition = withContext(Dispatchers.IO) {
                messagesAdapter.getPositionByMessageId(messageId, remoteUserId, this@ChatScreen)
            }
            if (targetPosition != RecyclerView.NO_POSITION) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(targetPosition, recyclerView.height / 3)

                recyclerView.postDelayed({
                    val viewHolder = recyclerView.findViewHolderForAdapterPosition(targetPosition)
                    viewHolder?.itemView?.let { view ->
                        view.setBackgroundColor(
                            ContextCompat.getColor(this@ChatScreen, R.color.lightYellow)
                        )
                        view.postDelayed({
                            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }, 1500)
                    }
                }, 300)
            }
            targetMessageId = null
        }
    }

    private fun setGotoBottomFAB(status: String) {
        when (status) {
            Status.VISIBLE -> {
                gotoBottom.alpha = 0.4f
                gotoBottom.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.grey))
                gotoBottom.visibility = View.VISIBLE
            }
            Status.HIGHLIGHT -> {
                gotoBottom.alpha = 1f
                gotoBottom.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
                gotoBottom.visibility = View.VISIBLE
            }
            Status.INVISIBLE -> gotoBottom.visibility = View.GONE
        }
    }

    private fun handleTypingState() {
        typingTimer?.cancel()

        if (!isCurrentlyTyping) {
            sendTypingNotification()
            isCurrentlyTyping = true
        }

        typingTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        stopTyping()
                    }
                }
            }, typingTimeoutMs)
        }
    }

    private fun sendTypingNotification() {
        lifecycleScope.launch {
            if (remoteUserId.startsWith("group")) {
                notifyGroupTyping(remoteUserId, Notify.TYPING)
            } else {
                notifyRemotePeer(remoteUserId, "", Notify.TYPING)
            }
        }
        debugLine("ChatScreen", "Sent typing notification")
    }

    private fun stopTyping() {
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            typingTimer?.cancel()
            typingTimer = null

            lifecycleScope.launch {
                if (remoteUserId.startsWith("group")) {
                    notifyGroupTyping(remoteUserId, Notify.STOP_TYPING)
                } else {
                    notifyRemotePeer(remoteUserId, "", Notify.STOP_TYPING)
                }
            }
            debugLine("ChatScreen", "Sent stop typing notification")
        }
    }

    override fun onDestroy() {
        typingTimer?.cancel()
        handler.removeCallbacksAndMessages(null)
        hideToolbarHandler.removeCallbacksAndMessages(null)
        closeDateNavigator()
        ViewModelProviderHolder.messageViewModel = null

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        if (shouldFinish) {
            shouldFinish = false
            finish()
            return
        }

        listenToRemoteCallEvents()

        MessageReceivedNotification.cancel(remoteUserId)

        executor = Executors.newSingleThreadExecutor()
        val config = BundledEmojiCompatConfig(this, executor).apply {
            setReplaceAll(true)
        }

        EmojiCompat.init(config)

        lifecycleScope.launch {
            debugLine("ChatScreen", "onResume, Sending seen notification")
            messageViewModel.sendSeenNotificationForUnseenMessages(remoteUserId = remoteUserId)
        }

        if (remoteUserId.startsWith("group")) {
            loadGroupPicture()
        }
    }

    override fun onPostResume() {
        super.onPostResume()

        Handler(Looper.getMainLooper()).postDelayed({
            lockToBottom = false
        }, 5000)
    }

    private fun loadGroupPicture() {
        lifecycleScope.launch {
            try {
                val pictureUrl = withContext(Dispatchers.IO) {
                    val snapshot = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("groups").document(remoteUserId)
                        .get().await()
                    snapshot.getString("picture")
                }

                if (!pictureUrl.isNullOrEmpty()) {
                    val profilePic: ImageView = findViewById(R.id.profilePic)
                    Glide.with(this@ChatScreen)
                        .load(pictureUrl)
                        .placeholder(R.drawable.group_placeholder)
                        .error(R.drawable.group_placeholder)
                        .into(profilePic)

                    withContext(Dispatchers.IO) {
                        getPeerViewModel().updatePeerPicture(remoteUserId, pictureUrl)
                    }
                }
            } catch (e: Exception) {
                debugLine("ChatScreen", "Error loading group picture: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(typingWatchdogRunnable)
        typingIndicatorAdapter.setTyping(false)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(remoteCallEventsReceiver)
        if (this::executor.isInitialized) {
            executor.shutdown()
        }
    }

    private fun showPlaceholder() {
        findViewById<CardView>(R.id.chat_insert).visibility = View.VISIBLE

        val imageView = findViewById<ImageView>(R.id.chat_imageAttached)
        imageView.visibility = View.VISIBLE

        Glide.with(this@ChatScreen)
            .load(R.drawable.blur2)
            .into(imageView)

        startBlinkingAnimation(imageView)

        // Show close button so user can dismiss the web preview
        findViewById<ImageButton>(R.id.close_with_background).apply {
            visibility = View.VISIBLE
            setOnClickListener { dismissWebPreview() }
        }
        findViewById<ImageButton>(R.id.close).visibility = View.GONE
    }

    private fun dismissWebPreview() {
        findViewById<CardView>(R.id.chat_insert).visibility = View.GONE
        findViewById<ImageButton>(R.id.close_with_background).visibility = View.GONE
        findViewById<ImageView>(R.id.chat_imageAttached).clearAnimation()

        webUrl = null
        webTitle = ""
        webDescription = ""
        imageUrl = ""
        webPreviewDismissed = true
        editText.text?.clear()
    }

    private fun handlePasteContent(content: ContentInfoCompat) {
        val clip = content.clip
        val type = clip.description.getMimeType(0)

        when (type) {
            "image/png" -> {
                val targetUri = copyUri(clip.getItemAt(0).uri, this)
                sendObject(
                    listOf(remoteUserId),
                    targetUri.toString(),
                    "", "", "",
                    lifecycleScope,
                    messageViewModel,
                    Type.STICKER
                )
            }

            "image/gif" -> {
                val targetUri = copyUri(clip.getItemAt(0).uri, this)
                debugLine("handleRichContent", "targetUri: $targetUri")

                val intent = Intent(this, SendGif::class.java).apply {
                    putExtra("gifPath", targetUri.toString())
                    putExtra("peerPicturePath", listOf(peerPicturePath).joinToString(","))
                    putExtra("userId", listOf(remoteUserId).joinToString(","))
                }
                startActivity(intent)
            }

            else -> {
                val plainText = clip.getItemAt(0).text.toString()
                editText.setText(plainText)
                editText.setSelection(plainText.length)

                editText.isFocusable = true
                editText.isFocusableInTouchMode = true

                editText.postDelayed({
                    editText.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                }, 300)
            }
        }
    }

    private fun scrollToBottom() {
        recyclerView.post {
            val lm = recyclerView.layoutManager as LinearLayoutManager
            lm.scrollToPositionWithOffset(0, 0)
            recyclerView.post {
                if (recyclerView.canScrollVertically(1)) {
                    lm.scrollToPositionWithOffset(0, 0)
                }
            }
        }
    }

    private fun startHandleKeyboard() {
        recyclerView.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                recyclerView.post {
                    scrollToBottom()
                }
            }
        }
    }

    override fun getExtraData(): Map<String, String?> {
        return mapOf("userId" to remoteUserId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingSharedText()
    }

    private fun handleIncomingSharedText() {
        val newText = intent?.getStringExtra("text") ?: emptyString()

        if (newText.isNotEmpty()) {
            intent?.removeExtra("text")

            val detectedUrl = extractUrl(newText)
            val textToPaste = detectedUrl ?: newText

            if (detectedUrl != null) {
                // Set webUrl BEFORE setText so the TextWatcher's afterTextChanged
                // sees webUrl != null and skips its own fetchWebsiteInfo call
                webUrl = detectedUrl

                editText.setText(textToPaste)
                editText.setSelection(textToPaste.length)

                showPlaceholder()

                lifecycleScope.launch {
                    val cached = WebPreviewCache.consume(detectedUrl)
                    if (cached != null) {
                        imageUrl = cached.imageUrl
                        webTitle = cached.title
                        webDescription = cached.description
                        updateUI(webTitle, webDescription, imageUrl)
                    } else {
                        val info = fetchWebsiteInfo(detectedUrl, this@ChatScreen)
                        imageUrl = info.imageUrl
                        webTitle = info.title
                        webDescription = info.description
                        updateUI(webTitle, webDescription, imageUrl)
                    }
                }
            } else {
                val clipData = ClipData.newPlainText("label", textToPaste)
                val contentInfo = ContentInfoCompat.Builder(clipData, ContentInfoCompat.SOURCE_CLIPBOARD).build()

                if (::editText.isInitialized) {
                    handlePasteContent(contentInfo)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val menuRes = if (isToggledMenu && toggledMenuRes != null) {
            toggledMenuRes!!
        } else {
            originalMenuRes
        }
        menuInflater.inflate(menuRes, menu)

        menu?.let {
            try {
                val method = it.javaClass.getDeclaredMethod(
                    "setOptionalIconsVisible",
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(it, true)
            } catch (_: Exception) { }
        }

        if (remoteUserId.startsWith("group")) {
            menu?.findItem(R.id.phone_call)?.isVisible = false
            menu?.findItem(R.id.video_call)?.isVisible = false
            menu?.findItem(R.id.calendar)?.isVisible = !isToggledMenu
        }

        menu?.findItem(R.id.action_search)?.isVisible = !isToggledMenu

        return true
    }

    private fun openSearch(searchContainer: LinearLayout, searchEditText: EditText) {
        searchContainer.visibility = View.VISIBLE
        searchContainer.alpha = 0f
        searchContainer.translationY = -searchContainer.height.toFloat().coerceAtLeast(36f * resources.displayMetrics.density)

        searchContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    searchEditText.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
                }
            })
            .start()

        isSearchOpen = true
    }

    private fun closeSearch(searchContainer: LinearLayout, searchEditText: EditText) {
        messageViewModel.setSearchQuery("")
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)

        searchContainer.animate()
            .alpha(0f)
            .translationY(-searchContainer.height.toFloat().coerceAtLeast(36f * resources.displayMetrics.density))
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    searchContainer.visibility = View.GONE
                    searchEditText.text.clear()
                    searchEditText.clearFocus()
                    searchContainer.translationY = 0f
                    searchContainer.alpha = 1f
                }
            })
            .start()

        isSearchOpen = false
    }

    private fun updateUI(title: String, description: String, imageUrl: String) {
        findViewById<TextView>(R.id.chat_nameAttached).text = title
        findViewById<TextView>(R.id.chat_textAttached).text = description
        findViewById<CardView>(R.id.chat_insert).visibility = View.VISIBLE

        val imageView = findViewById<ImageView>(R.id.chat_imageAttached)

        Glide.with(this@ChatScreen)
            .load(imageUrl)
            .placeholder(R.drawable.image)
            .error(R.drawable.error)
            .into(imageView)

        imageView.clearAnimation()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        removeReactionOverlay()

        return when (item.itemId) {
            R.id.action_search -> {
                val searchContainer = findViewById<LinearLayout>(R.id.searchContainer)
                val searchEditText = findViewById<EditText>(R.id.searchEditText)
                if (isSearchOpen) {
                    closeSearch(findViewById(R.id.searchContainer), findViewById(R.id.searchEditText))
                } else {
                    openSearch(searchContainer, searchEditText)
                }
                true
            }
            R.id.phone_call -> {
                debugLine("ChatScreen", "Initiating Audio call")

                // Guard: with the microphone denied the call would start "deaf".
                // ensureCallPermissions launches the recovery (request or settings
                // dialog); the user taps the call button again after granting.
                if (ensureCallPermissions(this, isVideo = false)) {
                    startCall(this, remoteUserId, false)
                }

                true
            }

            R.id.calendar -> {
                toggleDateNavigator()
                true
            }

            R.id.video_call -> {
                debugLine("ChatScreen", "Initiating Video call")

                if (ensureCallPermissions(this, isVideo = true)) {
                    startCall(this, remoteUserId, true)
                }

                true
            }

            R.id.delete -> {
                val selectedMessageIds = messagesAdapter.getSelectedMessagesIds()

                messagesAdapter.clearSelectionStateOnly()

                toggleMenu(false)

                lifecycleScope.launch {
                    val selectedMessages = messageViewModel.getMessagesByIds(selectedMessageIds)
                    messageViewModel.deleteMessages(selectedMessages)
                }
                true
            }

            R.id.copy -> {
                val selectedMessageId = messagesAdapter.getSelectedMessagesIds().first()
                lifecycleScope.launch {
                    val selectedMessage = messageViewModel.getMessage(selectedMessageId)
                    selectedMessage?.let {
                        val clipboard =
                            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("textMessage", it.text)
                        clipboard.setPrimaryClip(clip)
                    }
                    messagesAdapter.toggleSelection(selectedMessageId)
                    toggleMenu(false)
                }

                true
            }

            R.id.reply -> {

                val selectedMessageId = messagesAdapter.getSelectedMessagesIds().first()
                editText.setRichContentEnabled(false)

                lifecycleScope.launch {
                    messageReply(messageViewModel.getMessage(selectedMessageId), selectedMessageId)
                }

                true
            }

            R.id.forward -> {

                if (!forwardImages) {
                    lifecycleScope.launch {
                        val selectedMessageId = messagesAdapter.getSelectedMessagesIds().first()
                        messageToForward = messageViewModel.getMessage(selectedMessageId) ?: return@launch
                        editText.setRichContentEnabled(false)

                        if (messageToForward == null) return@launch

                        if (messageToForward!!.subType == SubType.FORWARD) {
                            nameToForward = messageToForward!!.nameAttached
                            textToForward = messageToForward!!.textAttached
                        } else {
                            nameToForward = name
                            textToForward = messageToForward!!.text
                        }

                        if (messageToForward!!.type == Type.WEB) {
                            imageUrl = messageToForward!!.replyId.toString()
                            webTitle = messageToForward!!.nameAttached.toString()
                            webDescription = messageToForward!!.textAttached.toString()
                            textToForward = messageToForward!!.text
                        }

                        val intent = Intent(this@ChatScreen, SelectPeersForForward::class.java)
                        intent.putExtra("excludedUserId", remoteUserId)
                        getPeersResult.launch(intent)

                        messagesAdapter.toggleSelection(selectedMessageId)
                        toggleMenu(false)
                    }
                    editText.setRichContentEnabled(true)

                } else {

                    lifecycleScope.launch {

                        val selectedMessageUris = messagesAdapter.getSelectedMessagesUris()
                        val selectedMessageId = messagesAdapter.getSelectedMessagesIds().first()

                        messageToForward =
                            messageViewModel.getMessage(selectedMessageId) ?: return@launch
                        editText.setRichContentEnabled(false)

                        messageToForward?.uri = selectedMessageUris.joinToString(separator = ",")
                        messageToForward?.type = Type.MULTIPLE_IMAGES
                        nameToForward = ""
                        textToForward = ""

                        val intent = Intent(this@ChatScreen, SelectPeersForForward::class.java)
                        intent.putExtra("excludedUserId", remoteUserId)
                        getPeersResult.launch(intent)

                        messagesAdapter.removeSelection()
                        messagesAdapter.refreshAdapter()

                        toggleMenu(false)

                        forwardImages = false
                    }
                    editText.setRichContentEnabled(true)
                }
                true
            }

            R.id.share -> {
                lifecycleScope.launch {
                    val selectedMessageId = messagesAdapter.getSelectedMessagesIds().first()
                    val selectedMessage = messageViewModel.getMessage(selectedMessageId) ?: return@launch
                    val messageType = selectedMessage.type

                    when(messageType) {
                        Type.TEXT -> {
                            shareContent(selectedMessage.text, this@ChatScreen, "text/plain")
                        }
                        Type.IMAGE -> {
                            shareContent(selectedMessage.uri, this@ChatScreen, "image/jpeg")
                        }
                        Type.STICKER -> {
                            shareContent(selectedMessage.uri, this@ChatScreen, "image/png")
                        }
                        Type.GIF -> {
                            shareContent(selectedMessage.uri, this@ChatScreen, "image/gif")
                        }
                        Type.VIDEO -> {
                            shareContent(selectedMessage.uri, this@ChatScreen, "video/*")
                        }
                        Type.AUDIO -> {
                            shareContent(selectedMessage.uri, this@ChatScreen, "audio/m4a")
                        }
                        Type.FILE -> {
                            shareContent(selectedMessage.uri, this@ChatScreen)
                        }
                        Type.WEB -> {
                            shareContent(selectedMessage.text, this@ChatScreen, "text/plain")
                        }
                    }

                    messagesAdapter.toggleSelection(selectedMessageId)
                    toggleMenu(false)
                }
                true
            }

            android.R.id.home -> {
                val intent = Intent(this, AppTab::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun messageReply(selectedMessage: Message?, selectedMessageId: String) {
        selectedMessage?.let {
            textAttached = it.text

            if (it.originalSenderId != null) {
                lifecycleScope.launch {
                    val senderName = getPeerViewModel().getPeer(it.originalSenderId)?.name ?: name
                    nameAttached = senderName
                    findViewById<TextView>(R.id.chat_nameAttached).text = nameAttached
                }
            } else {
                nameAttached = name
            }

            subType = SubType.REPLY
            replyId = it.messageId

            findViewById<CardView>(R.id.chat_insert).visibility = View.VISIBLE

            if (typeHasImageAttached(it.type)) {
                findViewById<ImageView>(R.id.chat_imageAttached).visibility = View.VISIBLE
                findViewById<ImageButton>(R.id.close_with_background).visibility = View.VISIBLE
                findViewById<ImageButton>(R.id.close).visibility = View.GONE

                when {
                    isFileType(it.type) -> {
                        Glide.with(this@ChatScreen)
                            .load(toImage(getFileDetailFromType(it.type)[1], this))
                            .into(findViewById(R.id.chat_imageAttached))
                    }
                    it.type == Type.AUDIO -> {
                        // Assign the VARIABLE, not just the compose-bar view: textAttached
                        // is what gets saved locally and sent to the peer. (The view is
                        // set from the variable further below anyway.)
                        textAttached = "<${this@ChatScreen.getString(R.string.audio)}>"
                        findViewById<ImageView>(R.id.chat_imageAttached).visibility = View.GONE
                    }
                    it.type == Type.WEB -> {
                        Glide.with(this@ChatScreen)
                            .load(safeUrl(selectedMessage.replyId))
                            .into(findViewById(R.id.chat_imageAttached))
                    }
                    it.type == Type.CONTACT -> {
                        val peer = Json.decodeFromString<Peer>(it.text)
                        textAttached = getString(R.string.contact) + ": "+ peer.name

                        Glide.with(this@ChatScreen)
                            .load(safeUrl(selectedMessage.uri))
                            .into(findViewById(R.id.chat_imageAttached))
                    }
                    it.type == Type.MULTIPLE_IMAGES -> {
                        lifecycleScope.launch {
                            Glide.with(this@ChatScreen)
                                .load(buildMultiImagePreview(selectedMessage.uri))
                                .into(findViewById(R.id.chat_imageAttached))
                        }
                    }
                    else -> {
                        Glide.with(this@ChatScreen)
                            .load(safeUrl(selectedMessage.uri))
                            .into(findViewById(R.id.chat_imageAttached))
                    }
                }

                findViewById<ImageView>(R.id.camera_icon).visibility =
                    if (it.type == Type.VIDEO) View.VISIBLE else View.GONE
            } else {
                findViewById<ImageView>(R.id.chat_imageAttached).visibility = View.GONE
                findViewById<ImageButton>(R.id.close_with_background).visibility = View.GONE
                findViewById<ImageButton>(R.id.close).visibility = View.VISIBLE
            }

            findViewById<TextView>(R.id.chat_nameAttached).text = nameAttached
            findViewById<TextView>(R.id.chat_textAttached).text = textAttached

            editText.requestFocus()
            editText.postDelayed({
                val imm =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }, 200)

            messagesAdapter.toggleSelection(selectedMessageId)
            toggleMenu(false)

            findViewById<ImageButton>(R.id.close).setOnClickListener {
                findViewById<CardView>(R.id.chat_insert).visibility = View.GONE

                textAttached = null
                nameAttached = null
                subType = null
            }
            findViewById<ImageButton>(R.id.close_with_background).setOnClickListener {
                findViewById<CardView>(R.id.chat_insert).visibility = View.GONE

                textAttached = null
                nameAttached = null
                subType = null
            }
        }
    }

    override fun onItemClick(message: Message) {
        if (messagesAdapter.isAnyMessageSelected()) {
            messagesAdapter.toggleSelection(message.messageId)
        }
        if (messagesAdapter.isAnyMessageSelected()) {
            toggleMenu(true)
        } else {
            toggleMenu(false)

            if (message.type == Type.IMAGE || message.type == Type.MULTIPLE_IMAGES) {
                val intent = Intent(this, ImageGalleryActivity::class.java).apply {
                    putExtra("messageId", message.messageId)
                    putExtra("userId", remoteUserId)
                }
                startActivity(intent)
            }

            if (message.type == Type.VIDEO) {
                val intent = Intent(this, VideoView::class.java).apply {
                    putExtra("uri", message.uri)
                    putExtra("userId", remoteUserId)
                    putExtra("messageDate", message.date)
                }
                startActivity(intent)
            }

            if(message.subType == SubType.REPLY){
                scrollToOriginalMessage(message.replyId)
            }

        }
    }

    private fun scrollToOriginalMessage(replyId: String?) {
        if (replyId.isNullOrEmpty()) return

        lifecycleScope.launch {
            val targetPosition = withContext(Dispatchers.IO) {
                messagesAdapter.getPositionByMessageId(replyId, remoteUserId,this@ChatScreen)
            }
            if (targetPosition != RecyclerView.NO_POSITION) {
                recyclerView.smoothScrollToPosition(targetPosition)
            } else {
                debugLine("scrollToOriginalMessage", "Unable to find original message.")
            }
        }
    }


    override fun onSwipeToReply(message: Message) {
        lifecycleScope.launch {
            messageReply(message, message.messageId)
            messagesAdapter.toggleSelection(message.messageId)
        }
    }

    override fun onSwipeToDismissPlaceholder(message: Message) {
        if (message.chatGroupId != null) {
            // Group transfers travel by gossip and cannot be revoked with a single
            // FCM: keep the original local-only dismiss for them.
            lifecycleScope.launch {
                messageViewModel.deleteMessages(listOf(message))
            }
            return
        }

        AlertDialog.Builder(this)
            .setMessage(R.string.cancel_transfer_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch {
                    val current = messageViewModel.getMessage(message.messageId)
                    if (current == null || current.status != Status.RECEIVING) {
                        // Completed while the dialog was open: delivery wins.
                        return@launch
                    }
                    cancelIncomingTransfer(current, this@ChatScreen)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onSwipeToCancelSend(message: Message) {
        AlertDialog.Builder(this)
            .setMessage(R.string.cancel_send_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch {
                    val current = messageViewModel.getMessage(message.messageId) ?: return@launch
                    if (current.status != getString(R.string.sending)
                        && current.status != getString(R.string.sent)) {
                        // Status changed while the dialog was open: delivery wins.
                        if (current.status == getString(R.string.delivered) || current.status == Notify.SEEN) {
                            showToast(getString(R.string.delivered), this@ChatScreen)
                        }
                        return@launch
                    }
                    cancelOutgoingSend(current, this@ChatScreen)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onViewProfileClick(message: Message) {
        val peer = Json.decodeFromString<Peer>(message.text)

        val intent = Intent(this, PeerView::class.java).apply {
            putExtra("userId", peer.userId)
            putExtra("name", peer.name)
            putExtra("bio", peer.bio)
            putExtra("picture", message.uri)
            putExtra("fromChat", true)
        }

        peerViewLauncher.launch(intent)
    }

    override fun onAddContactClick(message: Message) {
        val peer = Json.decodeFromString<Peer>(message.text)
        val dialog = NewPeerDialog.newInstance(peer.userId, peer.name, peer.bio)

        dialog.show(supportFragmentManager, "confirmNewPeer")
    }

    override fun onItemLongClick(
        message: Message,
        anchorView: View,
        displayHeader: Boolean
    ): Boolean {
        val isFirstClick = !messagesAdapter.isAnyMessageSelected()

        messagesAdapter.toggleSelection(message.messageId)
        if (messagesAdapter.isAnyMessageSelected()) {
            toggleMenu(true)
            if (isFirstClick && message.type != Type.MISSED_CALL) {
                lifecycleScope.launch {
                    val showRequestContact = if (message.chatGroupId != null) {
                        val senderId = message.originalSenderId ?: message.fromUserId
                        senderId != MySelf.userId() && getPeerViewModel().getPeer(senderId) == null
                    } else false

                    showReactionOverlay(message, anchorView, showRequestContact)
                }
            }
        } else {
            toggleMenu(false)
        }
        return true
    }

    override fun isAnyMessageSelected(): Boolean {
        return messagesAdapter.isAnyMessageSelected()
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        if (menu.javaClass.simpleName.equals("MenuBuilder", ignoreCase = true)) {
            try {
                val method = menu.javaClass.getDeclaredMethod("setOptionalIconsVisible", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(menu, true)
            } catch (_: Exception) { }
        }
        return super.onMenuOpened(featureId, menu)
    }

    @SuppressLint("RestrictedApi")
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        menu?.findItem(R.id.action_search)?.icon?.setTint(android.graphics.Color.BLACK)
        menu?.findItem(R.id.calendar)?.icon?.setTint(android.graphics.Color.BLACK)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun toggleMenu(toggle: Boolean): Boolean {
        if (isSearchOpen) {
            closeSearch(findViewById(R.id.searchContainer), findViewById(R.id.searchEditText))
        }

        isToggledMenu = toggle
        toggledMenuRes = if (toggle) {
            findViewById<TextView>(R.id.name).text = emptyString()
            determineMenuRes(messagesAdapter.getSelectedMessagesTypes())
        } else {
            findViewById<TextView>(R.id.name).text = name
            null
        }
        invalidateOptionsMenu()
        return isToggledMenu
    }

    private fun determineMenuRes(types: List<String>): Int {
        // Delete | Reply | Forward | Share | Copy

        if (!types.all { it == types.first() }) {
            return R.menu.chat_dxxxx
        }

        if (types.count() > 1 && types.first() != Type.IMAGE) {
            return R.menu.chat_dxxxx
        }

        return when (types.first()) {
            Type.TEXT -> R.menu.chat_drfsc
            Type.IMAGE -> getImageMenu(types)
            Type.MULTIPLE_IMAGES -> R.menu.chat_drfsx
            Type.GIF -> R.menu.chat_drfxx
            Type.STICKER -> R.menu.chat_drfxx
            Type.VIDEO -> R.menu.chat_drfsx
            Type.WEB -> R.menu.chat_drfsc
            Type.AUDIO -> R.menu.chat_drfsx
            Type.MISSED_CALL -> R.menu.chat_dxxxx

            else -> {
                if(isFileType(types.first())){
                    R.menu.chat_drfxx
                } else {
                    R.menu.chat
                }
            }
        }
    }

    private fun getImageMenu(types: List<String>): Int {
        if (types.count() == 1) {
            return R.menu.chat_drfsx
        } else {
            forwardImages = true
            return R.menu.chat_drfxx
        }
    }

    private val getPeersResult =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            if (messageToForward == null) return@registerForActivityResult
            val selectedPeers: List<String> =
                result.data?.getStringArrayListExtra("selectedPeers")
                    ?: return@registerForActivityResult
            val type = messageToForward?.type ?: return@registerForActivityResult

            when (type) {
                Type.CONTACT -> {
                    if(messageToForward!!.fromUserId.isEmpty()) {
                        val peerViewModel = getPeerViewModel()
                        peerViewModel.sendContacts(selectedPeers, remoteUserId)
                    } else {
                        val peerViewModel = getPeerViewModel()
                        val peer = Json.decodeFromString<Peer>(messageToForward!!.text)
                        peerViewModel.forwardContact(peer.userId, selectedPeers)
                    }
                }

                Type.TEXT -> {
                    val viewModel = ViewModelProviderHolder.messageViewModel
                    viewModel?.let {
                        selectedPeers.forEach { peerId ->
                            forwardText(
                                peerId,
                                textToForward,
                                nameToForward,
                                lifecycleScope,
                                viewModel
                            )
                        }
                    }
                }

                Type.AUDIO -> {
                    val viewModel = ViewModelProviderHolder.messageViewModel
                    viewModel?.let {
                        val authorId = if(messageToForward!!.nameAttached.isNullOrEmpty()) {
                            messageToForward!!.fromUserId
                        } else {
                            messageToForward!!.nameAttached
                        }

                        selectedPeers.forEach { peerId ->
                            forwardAudio(
                                peerId,
                                authorId,
                                messageToForward!!.uri,
                                lifecycleScope,
                                viewModel
                            )
                        }
                    }
                }

                Type.WEB -> {
                    val viewModel = ViewModelProviderHolder.messageViewModel

                    if (textToForward.isNullOrEmpty()) return@registerForActivityResult

                    val webLink = textToForward.toString()

                    viewModel?.let {
                        selectedPeers.forEach { peerId ->
                            forwardWeb(
                                peerId,
                                imageUrl,
                                webTitle,
                                webDescription,
                                webLink,
                                lifecycleScope,
                                viewModel
                            )
                        }
                    }
                }

                Type.IMAGE -> {
                    val intent = Intent(this, SendImage::class.java).apply {
                        putExtra("imagePath", messageToForward!!.uri)
                        putExtra("messageToForward", textToForward)
                        putExtra("fromName", nameToForward)
                        putExtra("userId", selectedPeers.joinToString(","))
                    }
                    startActivity(intent)
                }

                Type.MULTIPLE_IMAGES -> {
                    val intent = Intent(this, SendImages::class.java).apply {
                        putExtra("userId", selectedPeers.joinToString(","))
                        putExtra("fromName", nameToForward)
                        putExtra("uriList", messageToForward!!.uri)
                        putExtra("messageToForward", textToForward)
                    }
                    startActivity(intent)
                }

                Type.GIF -> {
                    val intent = Intent(this, SendGif::class.java).apply {
                        putExtra("userId", selectedPeers.joinToString(","))
                        putExtra("fromName", nameToForward)
                        putExtra("gifPath", messageToForward!!.uri)
                        putExtra("messageToForward", textToForward)
                    }
                    startActivity(intent)
                }

                Type.VIDEO -> {
                    val intent = Intent(this, SendVideo::class.java).apply {
                        putExtra("imagePath", messageToForward!!.uri)
                        putExtra("messageToForward", textToForward)
                        putExtra("fromName", nameToForward)
                        putExtra("userId", selectedPeers.joinToString(","))
                    }
                    startActivity(intent)
                }

                else -> {
                    if(isFileType(type)){
                        val intent = Intent(this, SendFile::class.java).apply {
                            putExtra("filePath", messageToForward!!.uri)
                            putExtra("messageToForward", textToForward)
                            putExtra("fromName", nameToForward)
                            putExtra("userId", selectedPeers.joinToString(","))
                            putExtra("peerPicturePath", peerPicturePath)
                        }
                        startActivity(intent)
                    } else {
                        debugLine("messageForward", "Type not supported")
                    }
                }
            }
        }
    }

    private val getImageResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val receivedUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.data?.getParcelableArrayListExtra("uriList", Uri::class.java)
                        ?: emptyList()
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableArrayListExtra("uriList") ?: emptyList()
                }

                if (receivedUris.isNotEmpty()) {
                    if (receivedUris.size == 1) {
                        var fileName = guid()
                        val previewFileName = fileName + "preview.jpg"
                        fileName += ".jpg"

                        // Decode + save off the main thread (a full-resolution photo
                        // freezes low-RAM devices), then open SendImage.
                        lifecycleScope.launch(Dispatchers.IO) {
                            val imageUri: Uri? = saveBitmapFromUri(receivedUris[0], fileName, 100)
                            saveBitmapFromUri(receivedUris[0], previewFileName, 50)

                            val userIdList: List<String> = listOf(remoteUserId)
                            val peerPicturePathList: List<String> = listOf(peerPicturePath)

                            withContext(Dispatchers.Main) {
                                val intent = Intent(this@ChatScreen, SendImage::class.java).apply {
                                    putExtra("imagePath", imageUri.toString())
                                    putExtra("peerPicturePath", peerPicturePathList.joinToString(","))
                                    putExtra("userId", userIdList.joinToString(","))
                                    pendingCaptionText?.let { putExtra("caption", it) }
                                }
                                startActivity(intent)
                                pendingCaptionText?.let { editText.text?.clear(); pendingCaptionText = null }
                            }
                        }
                    } else {
                        val intent = Intent(this, SendImages::class.java).apply {
                            putExtra("uriList", receivedUris.joinToString(",") { it.toString() })
                            putExtra("peerPicturePath", peerPicturePath)
                            putExtra("name", name)
                            putExtra("userId", remoteUserId)
                            pendingCaptionText?.let { putExtra("caption", it) }
                        }
                        startActivity(intent)
                        pendingCaptionText?.let { editText.text?.clear(); pendingCaptionText = null }
                    }
                }
            }
        }

    private val getVideoResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val imageUri: Uri? = result.data?.data

                imageUri?.let {
                    val userIdList: List<String> = listOf(remoteUserId)
                    val peerPicturePathList: List<String> = listOf(peerPicturePath)

                    val intent = Intent(this, SendVideo::class.java).apply {
                        putExtra("imagePath", it.toString())
                        putExtra("peerPicturePath", peerPicturePathList.joinToString(","))
                        putExtra("userId", userIdList.joinToString(","))
                        pendingCaptionText?.let { putExtra("caption", it) }
                    }
                    startActivity(intent)
                    pendingCaptionText?.let { editText.text?.clear(); pendingCaptionText = null }
                }
            }
        }

    private fun setKeyboardVisibilityListener(
        rootLayout: View,
        onVisibilityChanged: (Boolean) -> Unit
    ) {
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            private var lastVisibility: Boolean = false

            override fun onGlobalLayout() {
                val rect = Rect()
                rootLayout.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootLayout.height
                val keypadHeight = screenHeight - rect.bottom

                val isKeyboardVisible = keypadHeight > screenHeight * 0.15
                if (isKeyboardVisible != lastVisibility) {
                    lastVisibility = isKeyboardVisible
                    onVisibilityChanged(isKeyboardVisible)
                }
            }
        })
    }

    private val startRecordingRunnable = Runnable {
        startRecording()
    }

    private val micTouchListener = View.OnTouchListener { view, motionEvent ->
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                handler.postDelayed(startRecordingRunnable, 500)
                view.isPressed = true
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRecording) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    handleSlide(x, y)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                handler.removeCallbacks(startRecordingRunnable)
                view.isPressed = false
                if (isRecording && !isLocked) {
                    stopRecording()
                    sendAudioFile()

                    microphoneActive.visibility = View.GONE
                    microphone.visibility = View.VISIBLE

                } else {
                    view.performClick()
                }
                true
            }

            else -> false
        }
    }

    private fun handleSlide(x: Float, y: Float) {
        val slideOffset = 150

        if (y < -slideOffset) {
            lockRecording()
        } else if (x < -slideOffset) {
            cancelRecording()
        }
    }

    private fun lockRecording() {
        isLocked = true
        showRecordingLockedUI()
    }

    private fun cancelRecording() {
        if (isRecording) {
            stopRecording()
            deleteAudioFile()
            hideRecordingLockedUI()
            showToast("Recording cancelled", this)
        }
    }

    private fun startRecording() {
        isRecording = true
        showRecordingUI()

        recordingImage.startAnimation(blinkAnimation)
        rewind.startAnimation(blinkAnimation)
        upIcon.startAnimation(blinkAnimation)

        recordingChronometer.base = SystemClock.elapsedRealtime()
        recordingChronometer.start()

        val filePath =
            "${this.filesDir.absolutePath}/voice_message_${System.currentTimeMillis()}.m4a"
        val uriFilePath =
            FileProvider.getUriForFile(this, "${this.packageName}.provider", File(filePath))
        audioFilePath = uriFilePath.toString()

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(filePath)
            try {
                prepare()
                start()
            } catch (e: IOException) {
                debugLine("Exception, failed to start Recording", e.message.toString())
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        isLocked = false

        microphoneActive.clearAnimation()
        rewind.clearAnimation()
        recordingChronometer.stop()

        hideRecordingUI()
        hideRecordingLockedUI()

        mediaRecorder?.apply {
            try {
                stop()
                release()
            } catch (e: RuntimeException) {
                debugLine("Stop recording failed", e.message.toString())
                deleteAudioFile()
            }
        }
        mediaRecorder = null
    }

    private fun deleteAudioFile() {
        val file = audioFilePath?.let { File(it) }
        if (file != null) {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun showRecordingUI() {
        sendButton.visibility = View.INVISIBLE
        microphone.visibility = View.INVISIBLE
        microphoneActive.visibility = View.VISIBLE
        recordingView.visibility = View.VISIBLE
        chatLayoutBackground.visibility = View.INVISIBLE
        editText.visibility = View.INVISIBLE
        camera.visibility = View.INVISIBLE
        attach.visibility = View.INVISIBLE
        cancelButton.visibility = View.VISIBLE
        rewind.visibility = View.VISIBLE
        lockingView.visibility = View.VISIBLE
    }

    private fun hideRecordingUI() {
        sendButton.visibility = View.INVISIBLE
        microphone.visibility = View.VISIBLE
        microphoneActive.visibility = View.GONE
        recordingView.visibility = View.GONE
        chatLayoutBackground.visibility = View.VISIBLE
        editText.visibility = View.VISIBLE
        camera.visibility = View.VISIBLE
        attach.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        rewind.visibility = View.GONE
        lockingView.visibility = View.GONE
    }

    private fun showRecordingLockedUI() {
        cancelButton.visibility = View.GONE
        stopRecording.visibility = View.VISIBLE
        microphoneActive.visibility = View.GONE
        sendButton.visibility = View.VISIBLE
        recordingView.visibility = View.VISIBLE
        chatLayoutBackground.visibility = View.INVISIBLE
        editText.visibility = View.INVISIBLE
        camera.visibility = View.INVISIBLE
        attach.visibility = View.INVISIBLE
        lockingView.visibility = View.GONE
        rewind.visibility = View.GONE
        rewind.clearAnimation()
    }

    private fun hideRecordingLockedUI() {
        cancelButton.visibility = View.GONE
        stopRecording.visibility = View.GONE
    }

    private fun sendAudioFile() {
        if (!audioFilePath.isNullOrEmpty()) {
            sendObject(
                listOf(remoteUserId),
                audioFilePath!!,
                "",
                "",
                "",
                lifecycleScope,
                messageViewModel,
                Type.AUDIO
            )
        } else
            debugLine("sendAudioFile", "Audio file is empty")
    }

    private fun showReactionOverlay(message: Message, anchorView: View, showRequestContact: Boolean = false) {
        val isMyMessage = message.fromUserId == MySelf.userId()
        val isGroupMessage = message.chatGroupId != null

        // No overlay for outgoing 1:1 messages (no reactions, no info)
        if (isMyMessage && !isGroupMessage) return

        removeReactionOverlay()

        val inflater = LayoutInflater.from(this)
        val reactionView = inflater.inflate(R.layout.popup_reaction_bar, rootLayout, false)

        val emojiLike = reactionView.findViewById<TextView>(R.id.emoji_like)
        val emojiLove = reactionView.findViewById<TextView>(R.id.emoji_love)
        val emojiLaugh = reactionView.findViewById<TextView>(R.id.emoji_laugh)
        val emojiBlink = reactionView.findViewById<TextView>(R.id.emoji_blink)
        val emojiPlus = reactionView.findViewById<ImageButton>(R.id.plus_icon_button)

        emojiLike.setOnClickListener {
            removeReactionOverlay()
            sendReactionToServer(message, "👍")
        }

        emojiLove.setOnClickListener {
            removeReactionOverlay()
            sendReactionToServer(message, "❤️")
        }

        emojiLaugh.setOnClickListener {
            removeReactionOverlay()
            sendReactionToServer(message, "😂")
        }

        emojiBlink.setOnClickListener {
            removeReactionOverlay()
            sendReactionToServer(message, "😉")
        }

        emojiPlus.setOnClickListener {
            removeReactionOverlay()
            showAllEmojiSelector(message)
        }

        val wrapperLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Only show emoji reactions for incoming messages
        if (!isMyMessage) {
            wrapperLayout.addView(reactionView)
        }

        // Show "Info" button for outgoing group messages
        if (isMyMessage) {
            val infoView = inflater.inflate(R.layout.popup_message_info, wrapperLayout, false)
            infoView.setOnClickListener {
                removeReactionOverlay()
                messagesAdapter.toggleSelection(message.messageId)
                messagesAdapter.removeSelection()
                toggleMenu(false)

                val intent = Intent(this, MessageInfoActivity::class.java).apply {
                    putExtra("messageId", message.messageId)
                    putExtra("chatGroupId", message.chatGroupId)
                }
                startActivity(intent)
            }
            wrapperLayout.addView(infoView)
        }

        if (showRequestContact) {
            val requestContactView = inflater.inflate(R.layout.popup_request_contact, wrapperLayout, false)
            requestContactView.setOnClickListener {
                removeReactionOverlay()
                messagesAdapter.toggleSelection(message.messageId)
                messagesAdapter.removeSelection()
                toggleMenu(false)

                val senderId = message.originalSenderId ?: message.fromUserId
                val dialog = NewPeerDialog.newInstance(senderId, getString(R.string.member), "")
                dialog.show(supportFragmentManager, "confirmNewPeer")
            }
            wrapperLayout.addView(requestContactView)
        }

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)

        val anchorRect = Rect(
            location[0],
            location[1],
            location[0] + anchorView.width,
            location[1] + anchorView.height
        )

        wrapperLayout.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val wrapperWidth = wrapperLayout.measuredWidth
        val wrapperHeight = wrapperLayout.measuredHeight

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val desiredY = anchorRect.top - wrapperHeight

        val toolbarHeightPx: Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            58f,
            resources.displayMetrics
        ).toInt()

        val statusBarHeight = getStatusBarHeight()
        val topBarHeightPx = toolbarHeightPx + statusBarHeight
        var finalY = desiredY

        if (finalY < topBarHeightPx) {
            finalY = anchorRect.bottom - wrapperHeight
        }

        if (finalY + wrapperHeight > screenHeight) {
            finalY = screenHeight - wrapperHeight
        }

        var x = screenWidth - wrapperWidth
        if (x < 0) x = 0
        if (x + wrapperWidth > screenWidth) x = screenWidth - wrapperWidth

        wrapperLayout.translationX = x.toFloat()
        wrapperLayout.translationY = finalY.toFloat()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)

            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val wrapperRect = Rect()
                    wrapperLayout.getGlobalVisibleRect(wrapperRect)

                    val tappedOutside =
                        !wrapperRect.contains(event.rawX.toInt(), event.rawY.toInt())
                    if (tappedOutside) {
                        removeReactionOverlay()

                        val recyclerLocation = IntArray(2)
                        recyclerView.getLocationOnScreen(recyclerLocation)
                        val xInRecycler = event.rawX - recyclerLocation[0]
                        val yInRecycler = event.rawY - recyclerLocation[1]
                        val childView = recyclerView.findChildViewUnder(xInRecycler, yInRecycler)

                        if (childView != null) {
                            val newPosition = recyclerView.getChildAdapterPosition(childView)
                            if (newPosition != RecyclerView.NO_POSITION) {
                                val newMessage = messagesAdapter.snapshot()[newPosition]
                                if (newMessage != null) {
                                    messagesAdapter.toggleSelection(newMessage.messageId)
                                    if (!messagesAdapter.isAnyMessageSelected()) {
                                        toggleMenu(false)
                                    } else {
                                        toggleMenu(true)
                                    }
                                }
                            }
                        }

                        v.performClick()

                        return@setOnTouchListener true
                    }
                }
                false
            }

            addView(wrapperLayout)
        }

        rootLayout.addView(container)
        reactionBarView = container
    }

    private fun getStatusBarHeight(): Int {
        val insets = window?.decorView?.rootWindowInsets ?: return fallbackStatusBarHeight()
        val windowInsetsCompat = WindowInsetsCompat.toWindowInsetsCompat(insets)
        val statusBarInset = windowInsetsCompat.getInsets(WindowInsetsCompat.Type.statusBars()).top
        return if (statusBarInset > 0) statusBarInset else fallbackStatusBarHeight()
    }

    private fun fallbackStatusBarHeight(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            24f,
            resources.displayMetrics
        ).toInt()
    }

    private fun removeReactionOverlay() {
        reactionBarView?.let { rootLayout.removeView(it) }
        reactionBarView = null
    }

    private fun sendReactionToServer(message: Message, emoji: String) {
        messagesAdapter.toggleSelection(message.messageId)
        messagesAdapter.removeSelection()
        toggleMenu(false)
        lifecycleScope.launch {
            messageViewModel.updateReaction(message.messageId, emoji)
            sendReaction(message, emoji)
        }
    }

    private fun showAllEmojiSelector(message: Message) {
        messagesAdapter.toggleSelection(message.messageId)
        messagesAdapter.removeSelection()
        val emojiPicker = EmojiPickerBottomSheet { selectedEmoji ->
            sendReactionToServer(message, selectedEmoji)
        }
        emojiPicker.show(supportFragmentManager, "EmojiPickerBottomSheet")
    }

    private fun showFilePicker() {
        val mimeTypes = arrayOf(
            "application/pdf",
            "image/*",
            "video/*",
            "audio/*",
            "text/*",
            "*/*"
        )
        filePickerLauncher.launch(mimeTypes)
    }

    private fun handleSelectedFile(uri: Uri) {
        sendFileMessage(uri)
    }

    private fun sendFileMessage(uri: Uri) {
        uri.let {
            val userIdList: List<String> = listOf(remoteUserId)
            val peerPicturePathList: List<String> = listOf(peerPicturePath)

            val intent = Intent(this, SendFile::class.java).apply {
                putExtra("filePath", it.toString())
                putExtra("peerPicturePath", peerPicturePathList.joinToString(","))
                putExtra("userId", userIdList.joinToString(","))
                pendingCaptionText?.let { putExtra("caption", it) }
            }
            startActivity(intent)
            pendingCaptionText?.let { editText.text?.clear(); pendingCaptionText = null }
        }
    }

    private fun setupToolbarAutoHide() {
        hideToolbarRunnable = Runnable {
            if (isToolbarVisible) {
                hideToolbarWithAnimation()
            }
        }

        startHideToolbarTimer()
    }

    private fun showToolbarWithAnimation() {
        if (!isToolbarVisible) {
            isToolbarVisible = true
            bottomControlsContainer.apply {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = height.toFloat()
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun hideToolbarWithAnimation() {
        if (isToolbarVisible) {
            isToolbarVisible = false
            bottomControlsContainer.animate()
                .alpha(0f)
                .translationY(bottomControlsContainer.height.toFloat())
                .setDuration(300)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    bottomControlsContainer.visibility = View.GONE
                }
                .start()
        }
    }

    private fun startHideToolbarTimer() {
        cancelHideToolbarTimer()
        hideToolbarRunnable?.let {
            hideToolbarHandler.postDelayed(it, TOOLBAR_HIDE_DELAY)
        }
    }

    private fun cancelHideToolbarTimer() {
        hideToolbarRunnable?.let {
            hideToolbarHandler.removeCallbacks(it)
        }
    }

    private var allPagesLoaded = false

    private fun toggleDateNavigator() {
        if (dateNavigatorView != null) {
            closeDateNavigator()
            return
        }

        if (allPagesLoaded) {
            openDateNavigator()
        } else {
            preloadAllPagesAndOpenNavigator()
        }
    }

    private fun preloadAllPagesAndOpenNavigator() {
        recyclerView.visibility = View.INVISIBLE

        val progressBar = android.widget.ProgressBar(this).apply {
            id = View.generateViewId()
        }
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        rootLayout.addView(progressBar, params)

        lifecycleScope.launch {
            messagesAdapter.loadStateFlow
                .filter { it.append.endOfPaginationReached }
                .take(1)
                .collect {
                    allPagesLoaded = true
                    recyclerView.post {
                        rootLayout.removeView(progressBar)
                        val lm = recyclerView.layoutManager as LinearLayoutManager
                        lm.scrollToPositionWithOffset(0, 0)
                        recyclerView.visibility = View.VISIBLE
                        openDateNavigator()
                    }
                }
        }

        recyclerView.scrollToPosition(messagesAdapter.itemCount - 1)

        val forceLoadListener = object : () -> Unit {
            override fun invoke() {
                if (!allPagesLoaded) {
                    recyclerView.scrollToPosition(messagesAdapter.itemCount - 1)
                } else {
                    messagesAdapter.removeOnPagesUpdatedListener(this)
                }
            }
        }
        messagesAdapter.addOnPagesUpdatedListener(forceLoadListener)
        messagesAdapter.addOnPagesUpdatedListener(forceLoadListener)
    }

    private fun openDateNavigator() {
        val myUserId = MySelf.userId() ?: return

        lifecycleScope.launch {
            val dates = messageViewModel.getDistinctMessageDays(myUserId, remoteUserId)
            if (dates.isEmpty()) return@launch

            val inflater = LayoutInflater.from(this@ChatScreen)
            val navigator = inflater.inflate(R.layout.view_date_navigator, rootLayout, false)

            val datesRecycler = navigator.findViewById<RecyclerView>(R.id.dates_recycler)
            val closeButton = navigator.findViewById<ImageButton>(R.id.close_navigator)
            val dragHandle = navigator.findViewById<View>(R.id.drag_handle)

            datesRecycler.layoutManager = LinearLayoutManager(this@ChatScreen)
            datesRecycler.adapter = DateNavigatorAdapter(dates) { timestamp ->
                scrollToDate(timestamp)
            }

            closeButton.setOnClickListener { closeDateNavigator() }

            var dX = 0f
            var dY = 0f

            dragHandle.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = navigator.x - event.rawX
                        dY = navigator.y - event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        navigator.x = (event.rawX + dX)
                            .coerceIn(0f, (rootLayout.width - navigator.width).toFloat())
                        navigator.y = (event.rawY + dY)
                            .coerceIn(0f, (rootLayout.height - navigator.height).toFloat())
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.performClick()
                        true
                    }
                    else -> false
                }
            }

            navigator.post {
                navigator.x = 24 * resources.displayMetrics.density
                navigator.y = (rootLayout.height - navigator.height - 80 * resources.displayMetrics.density)
            }

            rootLayout.addView(navigator)
            dateNavigatorView = navigator
            invalidateOptionsMenu()
        }
    }

    private fun closeDateNavigator() {
        dateNavigatorView?.let { rootLayout.removeView(it) }
        dateNavigatorView = null
        invalidateOptionsMenu()
    }

    private fun scrollToDate(timestamp: Long) {
        val myUserId = MySelf.userId() ?: return

        lifecycleScope.launch {
            val position = withContext(Dispatchers.IO) {
                messageViewModel.getPositionForDate(myUserId, remoteUserId, timestamp)
            }
            val targetPosition = position - 1
            if (targetPosition >= 0) {
                val lm = recyclerView.layoutManager as LinearLayoutManager
                lm.scrollToPositionWithOffset(position, recyclerView.height - (80 * resources.displayMetrics.density).toInt())
                recyclerView.post {
                    lm.scrollToPositionWithOffset(position, recyclerView.height - (80 * resources.displayMetrics.density).toInt())
                }
            }
        }
    }
}


