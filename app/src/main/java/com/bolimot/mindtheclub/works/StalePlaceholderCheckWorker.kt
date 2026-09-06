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
import com.bolimot.mindtheclub.functions.pickRecoverySource
import com.bolimot.mindtheclub.receiving.missingChunksByContent
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
                // Prefer a member with a registered complete copy over the origin; single
                // lookup, hard timeout, falls back untouched.
                val target = pickRecoverySource(message.chatGroupId, originalSender, message.date, originalSender)

                // Say WHICH chunks are missing. This was the only one of the four recovery
                // paths that asked blind, and blind here is not merely wasteful, it loses the
                // race against the paths that do say.
                //
                // 22 Aug, a 444 chunk video. Dooge lost the channel at 442/444 and two
                // mechanisms asked Gio for the rest inside three seconds: the PENDING handler
                // with "#443,444", and this worker with nothing. The blind one arrived first,
                // so the sender started a full re-dispatch, found every batch row already
                // flagged sent, and logged "No messages to dispatch" five times followed by ALL
                // SENT with chunksSent: 0. The informed request was then refused by the
                // "dispatch already in flight" guard, which is right to exist and stays.
                // Eighteen minutes later the same pair raced again, the informed one won, and
                // the two chunks crossed in three seconds. Trentuno minuti decisi dall'ordine
                // di arrivo.
                //
                // The range is min..max of the gaps, so it is a superset of what is actually
                // missing: it can ask for more than needed, never less. An empty list means
                // nothing has arrived at all, and a null range is then correct.
                val missing = missingChunksByContent(inboxDao, contentKey)
                val missingRange = if (!missing.isNullOrEmpty()) {
                    "${missing.min()},${missing.max()}"
                } else {
                    null
                }
                debugLine(TAG, "Message $messageKey stalled at $count/$total chunks, sending sendMe to $target (missing: ${missingRange ?: "all"})")
                notifyRemotePeer(target, messageKey, Notify.SEND_ME, missingRange)
            } else {
                debugLine(TAG, "Message $messageKey has no originalSenderId, nothing to do")
            }
        } catch (e: Exception) {
            debugLine(TAG, "Failed for $messageKey: ${e.message}")
        }
        return Result.success()
    }
}
