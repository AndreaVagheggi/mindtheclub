package com.bolimot.mindtheclub.views

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.billing.BillingManager
import com.bolimot.mindtheclub.billing.SubscriptionCopy
import com.bolimot.mindtheclub.billing.TrialManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.bolimot.mindtheclub.chat.SelectPeersForForward
import com.bolimot.mindtheclub.contactAcquisition.acquiringNewContact
import com.bolimot.mindtheclub.contactAcquisition.autoAcceptRequestDocument
import com.bolimot.mindtheclub.contactAcquisition.isAutoInviteEnabled
import com.bolimot.mindtheclub.fragments.PeersFragment
import com.bolimot.mindtheclub.fragments.SearchResultsFragment
import com.bolimot.mindtheclub.functions.NOTIF_BANNER_SNOOZE_MS
import com.bolimot.mindtheclub.functions.NoteToSelf
import com.bolimot.mindtheclub.functions.PREF_NOTIF_BANNER_SNOOZE_UNTIL
import com.bolimot.mindtheclub.functions.PREF_PENDING_INVITE_SEED
import com.bolimot.mindtheclub.functions.areNotificationsEnabled
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.openNotificationSettings
import com.bolimot.mindtheclub.functions.parseQRCode
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.functions.wakeUpPhone
import com.bolimot.mindtheclub.receiving.DataSyncService
import com.bolimot.mindtheclub.sharing.incomingSharedContent
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Share
import com.bolimot.mindtheclub.viewModel.PeerViewModel
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.badge.ExperimentalBadgeUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.bolimot.mindtheclub.sharing.WebPreviewCache
import com.bolimot.mindtheclub.functions.extractUrl
import com.bolimot.mindtheclub.tools.Type

class AppTab : BaseActivity() {

    private lateinit var peerViewModel: PeerViewModel
    private lateinit var toolbar: MaterialToolbar

    private var newContactBadge: BadgeDrawable? = null
    private var firestoreListener: ListenerRegistration? = null
    private var requestCount: Int = 0
    private var remoteUserId: String? = null
    private var sharing: String? = null
    private var uri: String? = null
    private var shareType: String? = null
    private var text: String? = null
    private var startedForCallOnly: Boolean = false
    private var isFirstLoad: Boolean = true
    private var isSearchOpen = false
    private var searchResultsFragment: SearchResultsFragment? = null

    companion object {
        var fcmSending: Boolean = false
    }

    private fun getParameters(i: Intent){
        uri = i.getStringExtra("uri")
        shareType = i.getStringExtra("shareType")
        sharing = i.getStringExtra("sharing")
        text = i.getStringExtra("text")
        startedForCallOnly = i.getBooleanExtra("callOnly", false)
    }

    private val getPeersResult =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedPeers: List<String> = result.data?.getStringArrayListExtra("selectedPeers") ?: return@registerForActivityResult

            incomingSharedContent(shareType, uri, text, selectedPeers, this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        wakeUpPhone()

        super.onCreate(savedInstanceState)

        getParameters(intent)

        debugLine("AppTab", "onCreate")

        WindowCompat.setDecorFitsSystemWindows(window, false)

        CoroutineScope(Dispatchers.IO).launch {
            AiAssistant.ensureSeeded(this@AppTab)
            NoteToSelf.ensureSeeded(this@AppTab)
            remoteUserId = getPeerDao(this@AppTab).getFirstPeer()?.userId
        }

        peerViewModel = getPeerViewModel()

        setContentView(R.layout.app_tab)

        val rootView = findViewById<View>(R.id.rootLayout)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)

        val searchEditText = findViewById<EditText>(R.id.searchEditText)

        searchEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
            ContextCompat.getDrawable(this, R.drawable.ic_search)?.mutate(),
            null, null, null
        )

        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val clearIcon = if (s.isNullOrEmpty()) null
                else ContextCompat.getDrawable(this@AppTab, R.drawable.ic_clear)
                searchEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    ContextCompat.getDrawable(this@AppTab, R.drawable.ic_search)?.mutate(),
                    null, clearIcon, null
                )

                val query = s?.toString().orEmpty()
                if (query.isNotEmpty()) {
                    showSearchResults(query)
                } else {
                    hideSearchResults()
                }
            }
        })

        searchEditText.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val clearDrawable = searchEditText.compoundDrawablesRelative[2]
                if (clearDrawable != null) {
                    val clearButtonStart = searchEditText.width - searchEditText.paddingEnd - clearDrawable.intrinsicWidth
                    if (event.x >= clearButtonStart) {
                        searchEditText.text.clear()
                        // Don't close search, just clear the text
                        v.performClick()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                true
            } else false
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            appBarLayout.setPadding(appBarLayout.paddingLeft, systemBars.top, appBarLayout.paddingRight, appBarLayout.paddingBottom)
            view.setPadding(view.paddingLeft, 0, view.paddingRight, 0)

            insets
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.title = getString(R.string.app_name)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, PeersFragment())
                .commit()
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
        setupRequestsListener()

        intent?.getStringExtra("sharing")?.let { _ ->
            lifecycleScope.launch {
                handleSendIntent(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        firestoreListener?.remove()
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(DataSyncService.NOTIFICATION_ID)
        debugLine("AppTab", "isClosing = ${ConnectionManager.instance.isClosing}, startedForCallOnly = $startedForCallOnly")
        if(ConnectionManager.instance.isClosing && startedForCallOnly){
            lifecycleScope.launch {
                if (!fcmSending) {
                    debugLine("AppTab", "fcmSending is false, closing immediately.")
                    finishAndRemoveTask()
                } else {
                    debugLine("AppTab", "fcmSending is true, waiting up to 5 seconds for it to become false...")

                    withTimeoutOrNull(5000L) {
                        while (fcmSending) {
                            delay(100)
                        }
                    }

                    debugLine("AppTab", "Wait finished or timed out. Closing now.")
                    finishAndRemoveTask()
                }
            }
            return
        }

        if (!startedForCallOnly) {
            maybeConsumePendingInvite()

            // Keeps the cached entitlement fresh (picking up a purchase made on
            // another device). The access gate itself lives in BaseActivity, so
            // that notification -> ChatScreen cannot bypass it.
            BillingManager.refreshPurchases()
            maybeShowTrialStartedDialog()
        }

        updateNotificationsBanner()
        updateTrialBanner()
    }

    /**
     * One-time disclosure, shown the first time the user returns here after the
     * trial clock started (it starts on a background thread when the first
     * message is sent, so it cannot show a dialog itself).
     */
    private fun maybeShowTrialStartedDialog() {
        if (!TrialManager.consumeStartNotice(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trial_started_title)
            .setMessage(SubscriptionCopy.trialStartedBody(this))
            .setPositiveButton(R.string.close, null)
            .setCancelable(true)
            .show()
    }

    /** Countdown shown only in the last days of an unsubscribed trial. */
    private fun updateTrialBanner() {
        val banner = findViewById<View>(R.id.trialBanner) ?: return

        val daysLeft = TrialManager.daysLeft(this)
        val show = !BillingManager.hasSubscription(this) &&
                TrialManager.state(this) == TrialManager.State.ACTIVE &&
                daysLeft <= TrialManager.REMINDER_DAYS

        banner.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        findViewById<TextView>(R.id.trialBannerText).text = SubscriptionCopy.daysLeftText(this)

        val openPlans = View.OnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
        banner.setOnClickListener(openPlans)
        findViewById<View>(R.id.trialBannerAction).setOnClickListener(openPlans)
    }

    /**
     * Notifications-off nudge: visible only while system notifications are
     * disabled for the app (accidental deny during onboarding leaves the user
     * silently missing every message — nothing else in the app reveals it).
     * Dismiss snoozes it for 30 days; it disappears on its own the moment
     * notifications are enabled.
     */
    private fun updateNotificationsBanner() {
        val banner = findViewById<View>(R.id.notificationsBanner) ?: return

        val snoozeUntil = getPreference(PREF_NOTIF_BANNER_SNOOZE_UNTIL, this)?.toLongOrNull() ?: 0L
        val show = !areNotificationsEnabled(this) && System.currentTimeMillis() > snoozeUntil

        banner.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        findViewById<View>(R.id.notifBannerFix).setOnClickListener {
            openNotificationSettings(this)
        }
        findViewById<View>(R.id.notifBannerDismiss).setOnClickListener {
            setPreference(
                PREF_NOTIF_BANNER_SNOOZE_UNTIL,
                (System.currentTimeMillis() + NOTIF_BANNER_SNOOZE_MS).toString(),
                this
            )
            banner.visibility = View.GONE
        }
    }

    /**
     * Deferred-invite handoff: if the Play Install Referrer carried an inviter's
     * profile (see captureInstallReferrerOnce), present the normal
     * "add this contact?" dialog once onboarding is complete and the main screen is
     * showing. One-shot — the stashed seed is cleared as soon as it is consumed.
     */
    private fun maybeConsumePendingInvite() {
        // Don't collide with an invite arriving via the live deep link this same launch.
        if (intent?.getStringExtra("sharing") != null) return

        val seed = getPreference(PREF_PENDING_INVITE_SEED, this)
        if (seed.isNullOrEmpty()) return

        // Only after onboarding is complete (our own name exists).
        if (MySelf.name().isNullOrEmpty()) return

        // Consume once: clear before showing so it can never fire twice.
        setPreference(PREF_PENDING_INVITE_SEED, "", this)

        val qr = parseQRCode(seed) ?: return
        if (qr.userId.isEmpty() || qr.userId == MySelf.userId()) return

        lifecycleScope.launch {
            acquiringNewContact(
                qr.userId, qr.name, qr.bio, qr.fingerprint,
                this@AppTab, supportFragmentManager,
                // Host is the main screen: closing it here would kill the app right
                // when the profile exchange with the inviter needs to run.
                finishOnAccept = false
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        getParameters(intent)

        debugLine("AppTab", "onCreate new Intent")

        sharing?.let { _ ->
            lifecycleScope.launch {
                handleSendIntent(intent)
            }
        }
    }

    private suspend fun handleSendIntent(intent: Intent) {
        val userId = intent.getStringExtra("userId")
        val name = intent.getStringExtra("name")
        val bio = intent.getStringExtra("bio")
        val fingerprint = intent.getStringExtra("fingerprint")
        val share = intent.getStringExtra("sharing")

        when(share){
            Share.PROFILE -> {
                if (!userId.isNullOrEmpty() && !name.isNullOrEmpty()) {
                    acquiringNewContact(userId, name, bio, fingerprint, this, supportFragmentManager)
                }
                return
            }
            Share.CONTENT -> {
                // Pre-fetch web metadata while user picks a contact
                if (shareType == Type.WEB && !text.isNullOrEmpty()) {
                    val url = extractUrl(text!!)
                    if (url != null) {
                        WebPreviewCache.prefetch(url, lifecycleScope)
                    }
                }

                val i = Intent(this, SelectPeersForForward::class.java)
                i.putExtra("excludedUserId", MySelf.userId())
                getPeersResult.launch(i)
                return
            }
            else -> {
                debugLine("handleSendIntent", "NULL Exception; uri = $uri, type = $shareType, text = $text")
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> {
                val searchEditText = findViewById<EditText>(R.id.searchEditText)
                if (isSearchOpen) {
                    closeSearch(searchEditText)
                } else {
                    openSearch(searchEditText)
                }
                return true
            }
            R.id.action_options -> {
                val intent = Intent(this, OptionsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                return true
            }
            R.id.action_new_contacts -> {
                val intent = Intent(this, NewPeersActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                setPreference("NewRequestNotificationShown", "false", this)

                val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(137)

                startActivity(intent)
                return true
            }
            android.R.id.home -> {
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    @androidx.annotation.OptIn(ExperimentalBadgeUtils::class)
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)

        menu?.findItem(R.id.action_search)?.icon?.setTint(android.graphics.Color.WHITE)
        menu?.findItem(R.id.action_options)?.icon?.setTint(android.graphics.Color.WHITE)

        if (newContactBadge == null) {
            newContactBadge = BadgeDrawable.create(this)
        }

        BadgeUtils.attachBadgeDrawable(newContactBadge!!, toolbar, R.id.action_new_contacts)

        updateBadgeCount()

        return true
    }

    private fun setupRequestsListener() {
        val userId = MySelf.userId()
        if (userId.isNullOrEmpty()) return

        val db = FirebaseFirestore.getInstance()

        val requestsCollectionRef = db.collection("users").document(userId)
            .collection("requests")

        firestoreListener = requestsCollectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                return@addSnapshotListener
            }

            lifecycleScope.launch {
                val blockedRepo = getBlockedUserRepository(this@AppTab)
                val requests = snapshot?.documents.orEmpty().filter {
                    it.id != "received" && !blockedRepo.isBlocked(it.id)
                }

                // Auto-invite mode: silently accept every incoming request instead
                // of surfacing it on the New contact requests screen. Accepted
                // requests are removed from Firestore, so the badge resolves to 0.
                if (isAutoInviteEnabled(this@AppTab)) {
                    isFirstLoad = false
                    requests.forEach { autoAcceptRequestDocument(it, this@AppTab) }
                    return@launch
                }

                val newCount = requests.size

                if (!isFirstLoad && requestCount == 0 && newCount == 1) {
                    showNewRequestNotification()
                }

                requestCount = newCount
                isFirstLoad = false

                updateBadgeCount()
            }
        }
    }

    @androidx.annotation.OptIn(ExperimentalBadgeUtils::class)
    private fun updateBadgeCount() {
        val badge = newContactBadge ?: return

        if (requestCount > 0) {
            badge.isVisible = true
            badge.number = requestCount
        } else {
            badge.isVisible = false
            badge.clearNumber()
        }
    }

    private fun openSearch(searchEditText: EditText) {
        searchEditText.visibility = View.VISIBLE
        searchEditText.alpha = 0f
        searchEditText.translationY = -searchEditText.height.toFloat().coerceAtLeast(36f * resources.displayMetrics.density)

        searchEditText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    searchEditText.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(searchEditText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            })
            .start()

        isSearchOpen = true
    }

    private fun closeSearch(searchEditText: EditText) {
        hideSearchResults()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)

        searchEditText.animate()
            .alpha(0f)
            .translationY(-searchEditText.height.toFloat().coerceAtLeast(36f * resources.displayMetrics.density))
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    searchEditText.visibility = View.GONE
                    searchEditText.text.clear()
                    searchEditText.clearFocus()
                    searchEditText.translationY = 0f
                    searchEditText.alpha = 1f
                }
            })
            .start()

        isSearchOpen = false
    }

    private fun showNewRequestNotification() {
        val context = App.context()
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "NewRequestNotification_v2"

        if (notificationManager.getNotificationChannel(channelId) == null) {
            // Remove the old channel: it may hold a stale numeric-ID sound URI
            notificationManager.deleteNotificationChannel("NewRequestNotification")

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            // Name-based URI: numeric R.raw IDs shift between builds, and the channel
            // stores the URI permanently at creation time.
            val soundUri = ("android.resource://" + context.packageName + "/raw/notification_sound").toUri()

            val channel = NotificationChannel(channelId, "New Request Channel", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Channel for new requests"
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val backStackIntent = Intent(context, AppTab::class.java)
        val newRequestsIntent = Intent(context, NewPeersActivity::class.java)

        val pendingIntent = TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(backStackIntent)
            .addNextIntent(newRequestsIntent)
            .getPendingIntent(FLAG_ONE_SHOT, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentTitle(context.getString(R.string.new_contact_request))
            .setContentText(context.getString(R.string.contact_request))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        builder.setContentIntent(pendingIntent)

        notificationManager.notify(137, builder.build())
    }

    private fun showSearchResults(query: String) {
        val fragment = searchResultsFragment
            ?: SearchResultsFragment().also {
                searchResultsFragment = it
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragmentContainer, it, "search_results")
                    .commit()
                // Ensure transaction is executed before calling updateQuery
                supportFragmentManager.executePendingTransactions()
            }

        // Make sure PeersFragment is hidden and SearchResultsFragment is visible
        supportFragmentManager.fragments.forEach { f ->
            if (f is PeersFragment && !f.isHidden) {
                supportFragmentManager.beginTransaction().hide(f).commit()
            }
            if (f is SearchResultsFragment && f.isHidden) {
                supportFragmentManager.beginTransaction().show(f).commit()
            }
        }

        fragment.updateQuery(query)
    }

    private fun hideSearchResults() {
        val searchFragment = searchResultsFragment ?: return

        supportFragmentManager.beginTransaction()
            .remove(searchFragment)
            .commit()
        searchResultsFragment = null

        // Show PeersFragment again
        supportFragmentManager.fragments.forEach { f ->
            if (f is PeersFragment && f.isHidden) {
                supportFragmentManager.beginTransaction().show(f).commit()
            }
        }

        // If no PeersFragment exists (shouldn't happen), re-add it
        if (supportFragmentManager.fragments.none { it is PeersFragment }) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, PeersFragment())
                .commit()
        }
    }
}