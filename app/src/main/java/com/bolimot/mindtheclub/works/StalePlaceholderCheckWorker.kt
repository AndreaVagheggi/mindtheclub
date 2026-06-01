package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import java.util.concurrent.TimeUnit

class StalePlaceholderCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "stalePlaceholderCheck"
        private const val KEY_MESSAGE_KEY = "messageKey"
        private const val INITIAL_DELAY_MIN = 5L

        fun schedule(context: Context, messageKey: String) {
            val inputData = workDataOf(KEY_MESSAGE_KEY to messageKey)
            val request = OneTimeWorkRequestBuilder<StalePlaceholderCheckWorker>()
                .setInputData(inputData)
                .setInitialDelay(INITIAL_DELAY_MIN, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "stale_$messageKey",
                ExistingWorkPolicy.KEEP,
                request
            )
            debugLine(TAG, "Scheduled check for $messageKey in ${INITIAL_DELAY_MIN}min")
        }
    }

    override suspend fun doWork(): Result {
        val messageKey = inputData.getString(KEY_MESSAGE_KEY) ?: return Result.success()
        try {
            val inboxDao = getInboxDao(applicationContext)
            val messageDao = getMessageDao(applicationContext)

            val message = messageDao.getMessage(messageKey) ?: return Result.success()
            if (message.status != Status.RECEIVING) {
                debugLine(TAG, "Message $messageKey no longer RECEIVING, skipping")
                return Result.success()
            }

            val contentKey = contentKeyOf(message.messageId, message.chatGroupId, message.originalSenderId, message.date)
            val count = inboxDao.countChunksByContent(contentKey)
            val total = inboxDao.getTotalChunksByContent(contentKey)

            if (count > 0 && count >= total) {
                debugLine(TAG, "Message $messageKey actually complete ($count/$total), skipping")
                return Result.success()
            }

            val originalSender = message.originalSenderId
            if (!originalSender.isNullOrEmpty()) {
                debugLine(TAG, "Message $messageKey stalled at $count/$total chunks, sending sendMe to $originalSender")
                notifyRemotePeer(originalSender, messageKey, Notify.SEND_ME)
            } else {
                debugLine(TAG, "Message $messageKey has no originalSenderId, nothing to do")
            }
        } catch (e: Exception) {
            debugLine(TAG, "Failed for $messageKey: ${e.message}")
        }
        return Result.success()
    }
}
