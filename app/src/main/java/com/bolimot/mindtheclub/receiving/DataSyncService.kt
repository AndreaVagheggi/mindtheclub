package com.bolimot.mindtheclub.receiving

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.works.DataSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bolimot.mindtheclub.functions.getInboxDao

class DataSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "DataSyncService"

    companion object {
        const val ACTION_START_SYNC = "com.bolimot.mindtheclub.ACTION_START_SYNC"
        const val EXTRA_CHANNEL_ID = "EXTRA_CHANNEL_ID"
        const val EXTRA_REMOTE_USER_ID = "EXTRA_REMOTE_USER_ID"
        const val NOTIFICATION_ID = 9999
        private const val POLL_INTERVAL_MS = 5_000L
        private const val IDLE_LIMIT_MS = 15_000L
        private const val MAX_SYNC_MS = 10 * 60 * 1000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SYNC) {
            val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
            val remoteUserId = intent.getStringExtra(EXTRA_REMOTE_USER_ID)

            if (channelId != null && remoteUserId != null) {
                startForegroundSync(channelId, remoteUserId)
                startWebRTC(channelId, remoteUserId)
            } else {
                debugLine(tag, "Missing extras, stopping.")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSync(rtcChannelId: String, remoteUserId: String) {
        val channelId = "sync_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "Connectivity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background synchronization status"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            remoteUserId.hashCode(),
            Intent(this, ChatScreen::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("userId", remoteUserId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentText(getString(R.string.checking))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                debugLine(tag, "FGS blocked (BOOT_COMPLETED context), falling back to WorkManager: ${e.message}")
                DataSyncWorker.enqueue(applicationContext, rtcChannelId, remoteUserId)
                stopSelf()
                return
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, 0)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        debugLine(tag, "NOTIF_FIRED id=$NOTIFICATION_ID source=DataSyncService peer=$remoteUserId")
    }

    private fun startWebRTC(channelId: String, remoteUserId: String) {
        serviceScope.launch {
            try {
                val existing = ConnectionManager.instance.getExistingClient(remoteUserId)
                if (existing != null && existing.rtcClient.isConnected() && existing.rtcClient.isDataChannelOpen()) {
                    debugLine(tag, "Already connected to $remoteUserId with open data channel. Ignoring new dataCall.")
                    stopSelf()
                    return@launch
                }

                try { ConnectionManager.instance.webRTCCleanUp(remoteUserId) } catch (e: Exception) { debugLine(tag,"Ignore: ${e.message}") }

                debugLine(tag, "Starting WebRTC connection for Data Sync...")
                val result = ConnectionManager.instance.webRTCConnect(
                    channelId,
                    "",
                    remoteUserId,
                    false,
                    App.context(),
                    video = false,
                    dataOnly = true
                )

                if (result is RTCClientResult.Success) {
                    debugLine(tag, "WebRTC Connected. Monitoring data channel activity.")
                    awaitTransferComplete(remoteUserId)
                } else {
                    debugLine(tag, "WebRTC Failed to connect ($result). Stopping service immediately.")
                }

            } catch (e: Exception) {
                debugLine(tag, "WebRTC Exception: ${e.message}")
            } finally {
                stopSelf()
            }
        }
    }

    private suspend fun awaitTransferComplete(remoteUserId: String) {
        val startTime = System.currentTimeMillis()
        var lastActivityTime = System.currentTimeMillis()
        var lastChunkCount = -1

        val inboxDao = getInboxDao(applicationContext)

        while (System.currentTimeMillis() - startTime < MAX_SYNC_MS) {
            delay(POLL_INTERVAL_MS)

            // Check if data channel is still alive
            val client = ConnectionManager.instance.getExistingClient(remoteUserId)
            val channelOpen = client != null
                    && client.rtcClient.isConnected()
                    && client.rtcClient.isDataChannelOpen()

            if (!channelOpen) {
                debugLine(tag, "Data channel closed. Transfer done or connection lost.")
                break
            }

            // Check if new chunks are arriving (across all messages)
            val currentCount = inboxDao.countRecords()

            if (currentCount != lastChunkCount) {
                lastChunkCount = currentCount
                lastActivityTime = System.currentTimeMillis()
            }

            val idleMs = System.currentTimeMillis() - lastActivityTime
            if (idleMs >= IDLE_LIMIT_MS) {
                debugLine(tag, "Data channel open but idle for ${idleMs / 1000}s. Stopping.")
                break
            }
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        debugLine(tag, "Sync monitoring ended after ${elapsed}s")
    }

    override fun onDestroy() {
        debugLine(tag, "NOTIF_CLEARED id=$NOTIFICATION_ID source=DataSyncService")
        debugLine(tag, "Service destroyed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}