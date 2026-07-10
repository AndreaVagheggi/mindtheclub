package com.bolimot.mindtheclub.works

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.sending.sendMessageWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SendMessageWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val NOTIFICATION_ID = 1003
        // Shared with DispatchWorker: one "Message Dispatching" channel for both.
        private const val CHANNEL_ID = "DispatchWorkerChannel"
    }

    // Required by setExpedited() on API < 31 (mirrors DispatchWorker). On 31+
    // expedited jobs run without a notification, so this stays invisible there.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val name = "Message Dispatching"
        val descriptionText = "Shows when a message is being sent in the background."
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = descriptionText
        }
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentText(applicationContext.getString(R.string.sending))
            .setOngoing(true)
            .build()

        debugLine("SendMessageWorker", "NOTIF_FIRED id=$NOTIFICATION_ID source=SendMessageWorker")
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {

        val messageDataJson = inputData.getString("messageDataJson") ?: return Result.failure()

        val messageData: MessageData? = try {
            json.decodeFromString<MessageData>(messageDataJson)
        } catch (e: Exception) {
            debugLine2("doWork", "Failed to parse MessageData from JSON: ${e.message}")
            null
        }

        if (messageData == null) {
            return Result.failure()
        }

        val resendMissing = inputData.getIntArray("resendMissing")?.toList()

        return withContext(Dispatchers.IO) {
            debugLine2("doWork", "Calling sendMediaMessage for: $messageData, part of ${messageData.groupId}")
            if (sendMessageWork(messageData, applicationContext, this, resendMissing)) {
                debugLine2("doWork", "sendMediaMessage successful: work=success for $messageData.uri, part of ${messageData.groupId}")
                Result.success()
            } else {
                debugLine2("doWork", "sendMediaMessage failed: work=retry for $messageData.uri, part of ${messageData.groupId}")
                Result.retry()
            }
        }
    }
}