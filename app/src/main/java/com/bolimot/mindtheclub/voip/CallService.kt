package com.bolimot.mindtheclub.voip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CallService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tag = "CallService"

    private lateinit var notificationManager: CallNotificationManager
    private var isNotifying = false

    companion object {
        const val ACTION_INCOMING_CALL = "com.bolimot.mindtheclub.voip.ACTION_INCOMING_CALL"
        const val ACTION_OUTGOING_CALL = "com.bolimot.mindtheclub.voip.ACTION_OUTGOING_CALL"
        const val EXTRA_REMOTE_USER_ID = "EXTRA_REMOTE_USER_ID"
        const val EXTRA_CALL_ID = "EXTRA_CALL_ID"
        const val EXTRA_CHANNEL_ID = "EXTRA_CHANNEL_ID" // For incoming calls
        const val EXTRA_IS_VIDEO = "EXTRA_IS_VIDEO"
        const val EXTRA_TERMINATE_ON_END = "EXTRA_TERMINATE_ON_END"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = CallNotificationManager(this)
        observeCallState()

        debugLine(tag, "Service created and observing call state")
    }

    private fun observeCallState() {
        var previousSession: ManagedTelecom.CallSession? = ManagedTelecom.currentCall.value

        serviceScope.launch {
            ManagedTelecom.currentCall.collect { currentSession ->
                val callEnded = previousSession != null && currentSession == null

                if (callEnded && isNotifying) {
                    debugLine(tag, "Call ended. Dismissing notification.")
                    isNotifying = false
                    notificationManager.dismissCallNotification()
                }

                previousSession = currentSession
            }
        }

        serviceScope.launch {
            ManagedTelecom.pendingCallFlow.collect { pending ->
                if (pending == null && isNotifying) {
                    debugLine(tag, "Pending call resolved. Stopping foreground and dismissing notification.")
                    isNotifying = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    notificationManager.dismissCallNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLine(tag, "onStartCommand received with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_INCOMING_CALL -> handleIncomingCall(intent)
            ACTION_OUTGOING_CALL -> handleOutgoingCall(intent)
            else -> {
                debugLine(tag, "Unknown or null action. Stopping service.")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleIncomingCall(intent: Intent) {
        val remoteUserId = intent.getStringExtra(EXTRA_REMOTE_USER_ID)
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val terminateOnEnd = intent.getBooleanExtra(EXTRA_TERMINATE_ON_END, false)
        val myUserId = MySelf.userId()

        try {
            val placeholderChannelId = "call_service_channel"
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(placeholderChannelId) == null) {
                val channel = NotificationChannel(
                    placeholderChannelId, "Call Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Foreground service for incoming calls" }
                nm.createNotificationChannel(channel)
            }
            val placeholderNotification = android.app.Notification.Builder(this, placeholderChannelId)
                .setSmallIcon(R.drawable.mtc_logo_small_icon)
                .setContentTitle("Incoming call…")
                .build()
            startForeground(111, placeholderNotification)
        } catch (e: Exception) {
            debugLine(tag, "Failed to start foreground: ${e.message}")
        }

        if(myUserId.isNullOrEmpty()) {
            debugLine(tag, "Missing myUserId. Aborting.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        if (remoteUserId == null || callId == null || channelId == null) {
            debugLine(tag, "Missing required extras for incoming call. Aborting.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        debugLine(tag, "Handling incoming call request, callId: $callId, channelId: $channelId")

        val timeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(30000L)
            ManagedTelecom.handlePendingCallTimeout(callId)
            isNotifying = false
            notificationManager.dismissCallNotification()
        }

        ManagedTelecom.pendingCallFlow.value = ManagedTelecom.PendingCall(
            remoteUserId = remoteUserId, callId = callId, channelId = channelId,
            isVideo = isVideo, terminateOnEnd = terminateOnEnd, timeoutJob = timeoutJob
        )

        isNotifying = true
        serviceScope.launch {
            notificationManager.showIncomingCallNotification(
                callId = callId, remoteUserId = remoteUserId,
                terminateOnEnd = terminateOnEnd, isVideo = isVideo
            )
        }
    }

    private fun handleOutgoingCall(intent: Intent) {
        val remoteUserId = intent.getStringExtra(EXTRA_REMOTE_USER_ID)
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        if (remoteUserId == null || callId == null) {
            debugLine(tag, "Missing required extras for outgoing call. Aborting.")
            return
        }

        debugLine(tag, "Handling outgoing call request, callId: $callId")

        serviceScope.launch {
            ManagedTelecom.startOutgoingCall(
                remoteUserId = remoteUserId,
                callId = callId,
                isVideo = isVideo,
                context = this@CallService
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationManager.dismissCallNotification()
        debugLine(tag, "Service destroyed and scope cancelled.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

