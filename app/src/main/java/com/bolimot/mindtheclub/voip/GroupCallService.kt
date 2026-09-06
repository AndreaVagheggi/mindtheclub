package com.bolimot.mindtheclub.voip

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerRepository
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.views.GroupCall
import com.bolimot.mindtheclub.views.IncomingGroupCall
import com.bolimot.mindtheclub.webrtc.group.GroupCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a group call alive while the app is off screen, and rings for one that is arriving.
 *
 * Out of [ManagedTelecom] apposta. Telecom models a call between this device and one other
 * party, with hold, endpoints and a system call log built around that assumption; a room of
 * eight people fits none of it. The price of staying outside is routing audio here by hand,
 * poche righe, and the benefit is that the 1:1 stack, which the app's calling has been tuned
 * around for months, is left completely untouched.
 *
 * The foreground type is `phoneCall`, whose only prerequisite is the MANAGE_OWN_CALLS
 * permission the app already declares. Microphone and camera types would be the literal
 * description, but both carry while-in-use restrictions that would stop an incoming call
 * from ringing on an idle phone, which is exactly when calls arrive.
 */
class GroupCallService : Service() {

    companion object {
        const val ACTION_START = "com.bolimot.mindtheclub.GROUP_CALL_START"
        const val ACTION_INCOMING = "com.bolimot.mindtheclub.GROUP_CALL_INCOMING"
        const val ACTION_JOIN = "com.bolimot.mindtheclub.GROUP_CALL_JOIN"
        const val ACTION_DECLINE = "com.bolimot.mindtheclub.GROUP_CALL_DECLINE"
        const val ACTION_LEAVE = "com.bolimot.mindtheclub.GROUP_CALL_LEAVE"
        const val ACTION_REMOTE_END = "com.bolimot.mindtheclub.GROUP_CALL_REMOTE_END"

        const val EXTRA_ROOM_ID = "roomId"
        const val EXTRA_KEY = "callKey"
        const val EXTRA_EPOCH = "callEpoch"
        const val EXTRA_HOST = "hostUserId"
        const val EXTRA_INVITEES = "invitees"
        const val EXTRA_VIDEO = "withVideo"

        private const val ONGOING_CHANNEL_ID = "group_call_ongoing_v1"
        private const val RINGING_CHANNEL_ID = "group_call_incoming_v1"
        private const val NOTIFICATION_ID = 120

        /**
         * The room this phone is ringing for, if any. Static because the FCM handler has to
         * answer "are we ringing for this room" before touching the service at all: a
         * withdrawal arriving while a call is actually running must not go near it.
         */
        @Volatile
        private var ringingRoom: String? = null

        /** Rings this phone for an arriving group call. */
        fun incoming(context: Context, roomId: String, key: String, epoch: Int, hostUserId: String) {
            val intent = Intent(context, GroupCallService::class.java).apply {
                action = ACTION_INCOMING
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_EPOCH, epoch)
                putExtra(EXTRA_HOST, hostUserId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /**
         * The host gave up before anyone answered: stop ringing.
         *
         * Does nothing unless this phone is ringing for exactly that room. The host sends it
         * to everyone it invited, people already inside the call included, and for them it
         * means nothing: stopping there would tear the notification off a live call and
         * strand the camera and microphone with no way to switch them off.
         */
        fun remoteEnd(context: Context, roomId: String) {
            if (ringingRoom != roomId) return
            val intent = Intent(context, GroupCallService::class.java).apply {
                action = ACTION_REMOTE_END
                putExtra(EXTRA_ROOM_ID, roomId)
            }
            context.startService(intent)
        }

        /** Starts a call and rings the given peers. */
        fun start(context: Context, invitees: List<String>, withVideo: Boolean) {
            val intent = Intent(context, GroupCallService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_INVITEES, ArrayList(invitees))
                putExtra(EXTRA_VIDEO, withVideo)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun leave(context: Context) {
            val intent = Intent(context, GroupCallService::class.java).apply { action = ACTION_LEAVE }
            context.startService(intent)
        }
    }

    private val tag = "GroupCallService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ringingRoomId: String? = null
    private var ringTimeout: Job? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var audioConfigured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING -> handleIncoming(intent)
            ACTION_START -> handleStart(intent)
            ACTION_JOIN -> handleJoin(intent)
            ACTION_DECLINE -> handleDecline(intent)
            ACTION_REMOTE_END -> handleRemoteEnd(intent)
            ACTION_LEAVE -> handleLeave()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ─────────────────────────────────────────────────────────────── incoming

    private fun handleIncoming(intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return stopSelf()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val epoch = intent.getIntExtra(EXTRA_EPOCH, 0)
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()

        if (GroupCallManager.isBusy()) {
            debugLine(tag, "Already in a call, declining $roomId")
            scope.launch { sendDecline(host, roomId) }
            stopSelf()
            return
        }

        ringingRoomId = roomId
        ringingRoom = roomId

        // Notification first, with whatever name is known right now. A foreground service
        // started from an FCM wake-up has a few seconds to show one, and a database read is
        // not what to spend them on.
        startForegroundCompat(
            ringingNotification(roomId, key, epoch, host, getString(R.string.app_name), null)
        )

        scope.launch {
            val peer = runCatching { getPeerRepository(applicationContext).getPeer(host) }.getOrNull()
            if (peer != null) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    ringingNotification(roomId, key, epoch, host, peer.name, peer.picture)
                )
            }
        }

        // An invitation nobody answers must not ring forever, ne' leave a foreground
        // service behind.
        ringTimeout = scope.launch {
            delay(GroupCallManager.RING_TIMEOUT_MS)
            debugLine(tag, "Group call invitation timed out")
            stopEverything()
        }
    }

    private fun handleRemoteEnd(intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (ringingRoomId != null && ringingRoomId == roomId) {
            debugLine(tag, "Host cancelled the invitation")
            stopEverything()
        }
        // Otherwise this phone is either in the call or not involved at all. In both cases
        // doing nothing is the only safe answer: stopping here would take down a running
        // call's own service.
    }

    private fun handleDecline(intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: ringingRoomId
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()

        // Told before stopping, so the caller sees the refusal instead of waiting out the
        // full ring timeout.
        if (roomId != null && host.isNotEmpty()) {
            // On the application scope, not this service's: the service is about to stop,
            // and the refusal still has to leave the phone.
            (applicationContext as App).applicationScope.launch { sendDecline(host, roomId) }
        }
        debugLine(tag, "Group call declined")
        stopEverything()
    }

    private suspend fun sendDecline(hostUserId: String, roomId: String) {
        if (hostUserId.isEmpty()) return
        fcmSendInstant(
            userId = hostUserId,
            content = roomId,
            callId = roomId,
            type = Notify.GROUP_CALL_DECLINE,
            collapseKey = Notify.GROUP_CALL_DECLINE
        )
    }

    // ───────────────────────────────────────────────────────────────── joining

    private fun handleStart(intent: Intent) {
        val invitees = intent.getStringArrayListExtra(EXTRA_INVITEES) ?: arrayListOf()
        val withVideo = intent.getBooleanExtra(EXTRA_VIDEO, true)

        startForegroundCompat(ongoingNotification(), inCall = true)
        configureAudio()

        GroupCallManager.startCall(applicationContext, invitees, withVideo)
        watchCall()
    }

    private fun handleJoin(intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return stopSelf()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val epoch = intent.getIntExtra(EXTRA_EPOCH, 0)
        val withVideo = intent.getBooleanExtra(EXTRA_VIDEO, true)

        ringTimeout?.cancel()
        ringingRoomId = null
        ringingRoom = null
        IncomingGroupCall.dismiss()

        startForegroundCompat(ongoingNotification(), inCall = true)
        configureAudio()

        GroupCallManager.joinCall(applicationContext, roomId, key, epoch, withVideo)
        watchCall()
    }

    private fun handleLeave() {
        GroupCallManager.leave()
        stopEverything()
    }

    /** The call can also end on its own: allowance, duration, or everyone leaving. */
    private fun watchCall() {
        scope.launch {
            GroupCallManager.status.collect { status ->
                when (status) {
                    GroupCallManager.Status.ENDED,
                    GroupCallManager.Status.FAILED,
                    GroupCallManager.Status.FULL,
                    GroupCallManager.Status.NO_ALLOWANCE -> stopEverything()
                    else -> {}
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────── plumbing

    /**
     * @param inCall adds the camera and microphone types. Ringing deliberately does not:
     * those two carry while-in-use restrictions and cannot be started from the background,
     * which is exactly where an arriving call finds the app. Once the user has answered the
     * app is in the foreground and both become legal and necessary, or Android cuts the
     * camera off the moment the call screen stops being visible.
     */
    private fun startForegroundCompat(notification: android.app.Notification, inCall: Boolean = false) {
        val phoneOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else 0

        val types = if (inCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            phoneOnly or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else phoneOnly

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            // A refused type must never take the call down with it: fall back to the one
            // that is always available and carry on senza immagine.
            debugLine(tag, "startForeground with camera and mic refused: ${e.message}")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, phoneOnly)
        }
    }

    /**
     * Group calls are speakerphone by default, like every conferencing app: una stanza di
     * gente non si tiene all'orecchio.
     */
    private fun configureAudio() {
        if (audioConfigured) return
        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            previousAudioMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
            audioConfigured = true
        } catch (e: Exception) {
            debugLine(tag, "Audio routing failed: ${e.message}")
        }
    }

    private fun restoreAudio() {
        if (!audioConfigured) return
        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            audio.mode = previousAudioMode
        } catch (e: Exception) {
            debugLine(tag, "Audio restore failed: ${e.message}")
        }
        audioConfigured = false
    }

    private fun stopEverything() {
        ringTimeout?.cancel()
        if (ringingRoomId != null) IncomingGroupCall.dismiss()
        ringingRoomId = null
        ringingRoom = null
        restoreAudio()
        // Back to IDLE, so the next call does not inherit this one's ending.
        GroupCallManager.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        restoreAudio()
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────── notifications

    @SuppressLint("FullScreenIntentPolicy")
    private fun ringingNotification(
        roomId: String,
        key: String,
        epoch: Int,
        host: String,
        name: String,
        picture: String?
    ): android.app.Notification {
        createChannels()

        val fullScreen = Intent(this, IncomingGroupCall::class.java).apply {
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_EPOCH, epoch)
            putExtra(EXTRA_HOST, host)
            putExtra("EXTRA_DISPLAY_NAME", name)
            putExtra("EXTRA_REMOTE_PICTURE", picture)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 11, fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Answering opens the ringing activity with the decision already taken, instead of
        // poking the service directly. The service cannot start an activity from the
        // background, so answering from the notification would leave the call running behind
        // no window at all.
        val answer = Intent(this, IncomingGroupCall::class.java).apply {
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_EPOCH, epoch)
            putExtra(EXTRA_HOST, host)
            putExtra(IncomingGroupCall.EXTRA_AUTO_ANSWER, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val answerPending = PendingIntent.getActivity(
            this, 12, answer,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val decline = Intent(this, GroupCallService::class.java).apply {
            action = ACTION_DECLINE
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_HOST, host)
        }
        val declinePending = PendingIntent.getService(
            this, 13, decline,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var icon: IconCompat? = null
        if (picture != null) {
            runCatching { loadBitmap(picture.toUri(), this) }.getOrNull()?.let {
                icon = IconCompat.createWithBitmap(it)
            }
        }

        val caller = Person.Builder().setName(name).setImportant(true).setIcon(icon).build()

        return NotificationCompat.Builder(this, RINGING_CHANNEL_ID)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentTitle(getString(R.string.incoming_group_call))
            .setContentText(getString(R.string.incoming_group_call))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePending, answerPending))
            .build()
    }

    private fun ongoingNotification(): android.app.Notification {
        createChannels()

        val open = PendingIntent.getActivity(
            this, 14,
            Intent(this, GroupCall::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangUp = PendingIntent.getService(
            this, 15,
            Intent(this, GroupCallService::class.java).apply { action = ACTION_LEAVE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentTitle(getString(R.string.group_call))
            .setContentText(getString(R.string.group_call_ongoing))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.mtc_logo_small_icon, getString(R.string.hang_up), hangUp)
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        if (manager.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ONGOING_CHANNEL_ID,
                    "Group calls",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Ongoing group call" }
            )
        }

        if (manager.getNotificationChannel(RINGING_CHANNEL_ID) == null) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            manager.createNotificationChannel(
                NotificationChannel(
                    RINGING_CHANNEL_ID,
                    "Incoming group calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Someone is calling the group"
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attributes)
                    enableVibration(true)
                }
            )
        }
    }
}

/** Convenience for the FCM handler, which has only the application context. */
fun ringGroupCall(roomId: String, key: String, epoch: Int, host: String) {
    GroupCallService.incoming(App.context(), roomId, key, epoch, host)
}
