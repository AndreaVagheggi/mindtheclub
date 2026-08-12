package com.bolimot.mindtheclub.works

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import kotlinx.coroutines.delay
import com.bolimot.mindtheclub.functions.getInboxDao

class DataSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DataSyncWorker"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_REMOTE_USER_ID = "remote_user_id"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val IDLE_LIMIT_MS = 15_000L
        private const val MAX_SYNC_MS = 10 * 60 * 1000L
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "DataSyncWorkerChannel"

        fun enqueue(context: Context, channelId: String, remoteUserId: String) {
            val data = workDataOf(
                KEY_CHANNEL_ID to channelId,
                KEY_REMOTE_USER_ID to remoteUserId
            )
            val request = OneTimeWorkRequestBuilder<DataSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Data Sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background data synchronization"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentTitle("MindTheClub")
            .setContentText(applicationContext.getString(R.string.incoming_data))
            .setOngoing(true)
            .build()
        debugLine(TAG, "NOTIF_FIRED id=$NOTIFICATION_ID source=DataSyncWorker")
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val channelId = inputData.getString(KEY_CHANNEL_ID) ?: return Result.failure()
        val remoteUserId = inputData.getString(KEY_REMOTE_USER_ID) ?: return Result.failure()

        return try {
            // Claim before destroying anything, exactly as DataSyncService does.
            ConnectionManager.instance.claimLatestDataChannel(remoteUserId, channelId)

            if (ConnectionManager.instance.hasLiveConnection(remoteUserId)) {
                debugLine(TAG, "Already connected to $remoteUserId with open data channel. Skipping.")
                return Result.success()
            }

            if (ConnectionManager.instance.isSupersededDataChannel(remoteUserId, channelId)) {
                debugLine(TAG, "dataCall $channelId superseded before cleanup, nothing to do")
                return Result.success()
            }

            try { ConnectionManager.instance.webRTCCleanUp(remoteUserId) } catch (e: Exception) { debugLine(TAG, "Ignore: ${e.message}") }

            if (ConnectionManager.instance.isSupersededDataChannel(remoteUserId, channelId)) {
                debugLine(TAG, "dataCall $channelId superseded during cleanup, letting the newer one connect")
                return Result.success()
            }

            debugLine(TAG, "Starting WebRTC connection for Data Sync (via WorkManager fallback)...")
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
                debugLine(TAG, "WebRTC Connected. Monitoring data channel activity.")
                awaitTransferComplete(remoteUserId)
            } else {
                debugLine(TAG, "WebRTC Failed to connect ($result).")
            }

            debugLine(TAG, "NOTIF_CLEARED id=$NOTIFICATION_ID source=DataSyncWorker")
            Result.success()
        } catch (e: Exception) {
            debugLine(TAG, "NOTIF_CLEARED id=$NOTIFICATION_ID source=DataSyncWorker (exception)")
            debugLine(TAG, "WebRTC Exception: ${e.message}")
            Result.failure()
        }
    }

    private suspend fun awaitTransferComplete(remoteUserId: String) {
        val startTime = System.currentTimeMillis()
        var lastActivityTime = System.currentTimeMillis()
        var lastChunkCount = -1

        val inboxDao = getInboxDao(applicationContext)

        while (System.currentTimeMillis() - startTime < MAX_SYNC_MS) {
            delay(POLL_INTERVAL_MS)

            val client = ConnectionManager.instance.getExistingClient(remoteUserId)
            val channelOpen = client != null
                    && client.rtcClient.isConnected()
                    && client.rtcClient.isDataChannelOpen()

            if (!channelOpen) {
                debugLine(TAG, "Data channel closed. Transfer done or connection lost.")
                break
            }

            val currentCount = inboxDao.countRecords()
            if (currentCount != lastChunkCount) {
                lastChunkCount = currentCount
                lastActivityTime = System.currentTimeMillis()
            }

            val idleMs = System.currentTimeMillis() - lastActivityTime
            if (idleMs >= IDLE_LIMIT_MS) {
                debugLine(TAG, "Data channel open but idle for ${idleMs / 1000}s. Stopping.")
                break
            }
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        debugLine(TAG, "Sync monitoring ended after ${elapsed}s")
    }
}

