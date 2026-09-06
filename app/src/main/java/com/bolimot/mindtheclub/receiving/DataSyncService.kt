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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.functions.OutgoingActivity
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.works.DataSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bolimot.mindtheclub.functions.getInboxDao
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DataSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "DataSyncService"

    // Several dataCall wake-ups can land while one sync is still running, each as its own
    // onStartCommand. stopSelf() from whichever finished first tore the service down under
    // the others: on 3 Aug it was destroyed one second after a second attempt had started,
    // leaving it running senza foreground protection.
    private val activeJobs = AtomicInteger(0)

    /**
     * Peers whose transfer already has a watcher. Without this every dataCall that finds a
     * live connection would start its own awaitTransferComplete on the same peer: ten
     * dataCalls during one photo transfer meant ten coroutines polling the same channel for
     * up to MAX_SYNC_MS each, all keeping the service and the wake lock alive.
     */
    private val watchedPeers = ConcurrentHashMap.newKeySet<String>()

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START_SYNC = "com.bolimot.mindtheclub.ACTION_START_SYNC"
        const val EXTRA_CHANNEL_ID = "EXTRA_CHANNEL_ID"
        const val EXTRA_REMOTE_USER_ID = "EXTRA_REMOTE_USER_ID"
        const val NOTIFICATION_ID = 9999
        private const val POLL_INTERVAL_MS = 5_000L
        // How long an open data channel may stay quiet before the sync gives up. Was 15s,
        // which a sender pausing on a throttled uplink hits routinely: on 14 Aug White
        // abandoned a transfer at 128 chunks out of 131, channel still open, and the last
        // three landed 38 minutes later. Worse than the lost transfer, the teardown also
        // removes the notification that shields the process, and that phone was killed 23
        // seconds later. MAX_SYNC_MS and the immediate exit on a genuinely closed channel
        // both still apply, so the cost is half a minute of pazienza on a dead link.
        private const val IDLE_LIMIT_MS = 45_000L
        // Extra time the service stays alive after the chunk flow stops, while a completed
        // message is still being assembled (chunks -> file -> Message). Bounded so a stale
        // unprocessable inbox row can never pin the service.
        private const val ASSEMBLY_GRACE_MS = 120_000L
        private const val MAX_SYNC_MS = 10 * 60 * 1000L
        // A foreground service does NOT keep the CPU awake. Without this lock the device
        // suspends between FCM wake-ups and every coroutine timer in the connection path
        // stops advancing, because delay() runs on a monotonic clock that ignores suspend
        // time. 3 Aug: a 20s ICE budget took 216s and a 45s peer timeout took 331s, so this
        // device joined the signalling room minutes after the sender had given up. The
        // timeout is only a leak guard, the lock is released as soon as the last job ends;
        // it must outlive MAX_SYNC_MS or a long transfer loses the CPU halfway.
        private const val WAKE_LOCK_TIMEOUT_MS = MAX_SYNC_MS + 60_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SYNC) {
            val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
            val remoteUserId = intent.getStringExtra(EXTRA_REMOTE_USER_ID)

            if (channelId != null && remoteUserId != null) {
                // The lock comes FIRST, before any notification or Binder work. The CPU is
                // only guaranteed for the ~10s FCM grace: on 13 Aug the device suspended in
                // the gap between startForeground and the acquire that used to sit after
                // it, freezing onStartCommand for 36s. The system flagged an ANR and the
                // signalling room expired before the connection was ever attempted.
                acquireWakeLock()
                // When the foreground promotion is refused the work has already gone to
                // WorkManager, so starting it here too would run the same sync twice.
                if (startForegroundSync(channelId, remoteUserId)) {
                    startWebRTC(channelId, remoteUserId)
                } else if (activeJobs.get() == 0) {
                    // Fallback: WorkManager owns the sync now and takes its own foreground
                    // protection, questo lock non deve restare.
                    releaseWakeLock()
                }
            } else {
                debugLine(tag, "Missing extras, stopping.")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** false when the service could not be promoted and the work went to WorkManager. */
    private fun startForegroundSync(rtcChannelId: String, remoteUserId: String): Boolean {
        val channelId = "sync_channel"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "Connectivity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background synchronization status"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        // The PENDING handler may already have posted the "checking" notice for this peer.
        // It carries its own per peer id now, so drop it explicitly, altrimenti it sits
        // next to this one showing the same text twice.
        manager.cancel("sync_$remoteUserId".hashCode())

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
                if (activeJobs.get() == 0) stopSelf()
                return false
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, 0)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        debugLine(tag, "NOTIF_FIRED id=$NOTIFICATION_ID source=DataSyncService peer=$remoteUserId")
        return true
    }

    private fun startWebRTC(channelId: String, remoteUserId: String) {
        // Counted before the launch, so a second onStartCommand arriving right now can
        // never observe an empty queue and stop the service from under us.
        activeJobs.incrementAndGet()
        serviceScope.launch {
            try {
                // Claim first, destroy later. Both guards below MUST run before
                // webRTCCleanUp: that call tears down the live connection, and doing it
                // before discovering the request is stale (or that a perfectly good
                // connection already exists) is what made two concurrent transfers from the
                // same peer kill each other, one reconnect at a time.
                ConnectionManager.instance.claimLatestDataChannel(remoteUserId, channelId)

                if (ConnectionManager.instance.hasLiveConnection(remoteUserId)) {
                    debugLine(tag, "Live connection to $remoteUserId already in place, reusing it and leaving the transfer alone")
                    awaitTransferCompleteOnce(remoteUserId)
                    return@launch
                }

                if (ConnectionManager.instance.isSupersededDataChannel(remoteUserId, channelId)) {
                    debugLine(tag, "dataCall $channelId superseded before cleanup, nothing to do")
                    return@launch
                }

                try { ConnectionManager.instance.webRTCCleanUp(remoteUserId) } catch (e: Exception) { debugLine(tag,"Ignore: ${e.message}") }

                // A newer dataCall may have landed while the cleanup ran.
                if (ConnectionManager.instance.isSupersededDataChannel(remoteUserId, channelId)) {
                    debugLine(tag, "dataCall $channelId superseded during cleanup, letting the newer one connect")
                    return@launch
                }

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
                    awaitTransferCompleteOnce(remoteUserId)
                } else {
                    debugLine(tag, "WebRTC Failed to connect ($result).")
                }

            } catch (e: Exception) {
                debugLine(tag, "WebRTC Exception: ${e.message}")
            } finally {
                val remaining = activeJobs.decrementAndGet()
                if (remaining <= 0) {
                    releaseWakeLock()
                    stopSelf()
                } else {
                    debugLine(tag, "Job finished, $remaining still running, keeping service alive")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MindTheClub:DataSync"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            debugLine(tag, "Wake lock acquired")
        } catch (e: Exception) {
            debugLine(tag, "Wake lock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    debugLine(tag, "Wake lock released")
                }
            }
        } catch (e: Exception) {
            debugLine(tag, "Wake lock release failed: ${e.message}")
        }
        wakeLock = null
    }

    /**
     * Runs [awaitTransferComplete] only when nobody else is already watching this peer. A
     * job that finds a watcher returns at once: the transfer is supervised, a second
     * observer adds niente.
     */
    private suspend fun awaitTransferCompleteOnce(remoteUserId: String) {
        if (!watchedPeers.add(remoteUserId)) {
            debugLine(tag, "Transfer with $remoteUserId is already being watched, not starting a second watcher")
            return
        }
        try {
            awaitTransferComplete(remoteUserId)
        } finally {
            watchedPeers.remove(remoteUserId)
        }
    }

    private suspend fun awaitTransferComplete(remoteUserId: String) {
        val startTime = System.currentTimeMillis()
        var lastActivityTime = System.currentTimeMillis()
        var lastChunkCount = -1

        val inboxDao = getInboxDao(applicationContext)

        while (System.currentTimeMillis() - startTime < MAX_SYNC_MS) {
            delay(POLL_INTERVAL_MS)

            // Il canale e' ancora vivo?
            val client = ConnectionManager.instance.getExistingClient(remoteUserId)
            val channelOpen = client != null
                    && client.rtcClient.isConnected()
                    && client.rtcClient.isDataChannelOpen()

            // New chunks arriving, across all messages. Assembly deletes the chunks when
            // it finishes, so that counts as activity too and correctly extends the window
            // for a following message.
            val currentCount = inboxDao.countRecords()

            if (currentCount != lastChunkCount) {
                lastChunkCount = currentCount
                lastActivityTime = System.currentTimeMillis()
            }

            val idleMs = System.currentTimeMillis() - lastActivityTime

            // A fully received message may still be assembling (the chunks go afterwards).
            // Losing the foreground protection now would let doze freeze the assembly mid
            // write, the exact failure that made a video sit invisible for 91 minutes. Stay
            // alive, within the grace.
            val assemblyPending = if (idleMs < ASSEMBLY_GRACE_MS) {
                try {
                    inboxDao.getCompleteMessageIds().isNotEmpty()
                } catch (e: Exception) {
                    debugLine(tag, "Assembly-pending check failed: ${e.message}")
                    false
                }
            } else {
                false
            }

            // Read from the dispatch pipeline itself, not inferred from WorkManager. The
            // first version asked whether a worker was RUNNING and never once got a yes: a
            // relay submitted at 16:14:31 on 21 Aug was still ENQUEUED when the service quit
            // five seconds later, and only started at 16:14:38. See OutgoingActivity.
            val stillSending = OutgoingActivity.isSending()

            if (!channelOpen) {
                if (assemblyPending) {
                    debugLine(tag, "Channel closed but assembly pending — keeping sync alive (idle ${idleMs / 1000}s).")
                    continue
                }
                if (stillSending) {
                    debugLine(tag, "Channel closed but still sending (${OutgoingActivity.secondsSinceLast()}s ago) — keeping sync alive.")
                    continue
                }
                debugLine(tag, "Data channel closed. Transfer done or connection lost.")
                break
            }

            if (idleMs >= IDLE_LIMIT_MS) {
                if (assemblyPending) {
                    debugLine(tag, "Idle but assembly pending — keeping sync alive (idle ${idleMs / 1000}s).")
                    continue
                }
                if (stillSending) {
                    debugLine(tag, "Nothing incoming but still sending (${OutgoingActivity.secondsSinceLast()}s ago) — keeping sync alive.")
                    continue
                }
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
        // Belt and braces: the normal release happens when the last job ends, questo copre
        // a destroy coming from the system instead.
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}