package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.receiving.isProcessingActive
import com.bolimot.mindtheclub.receiving.missingChunksByContent
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type
import java.util.concurrent.TimeUnit

class InboxRecoveryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "InboxRecoveryWorker"
        private const val INTERVAL_MINUTES = 15L
        private const val TAG = "InboxRecovery"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InboxRecoveryWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            debugLine(TAG, "Scheduled periodic worker (${INTERVAL_MINUTES}min)")
        }
    }

    override suspend fun doWork(): Result {
        val inboxDao = getInboxDao(applicationContext)
        val messageDao = getMessageDao(applicationContext)

        // Pass 1: complete messages whose assembly needs re-triggering (via AssembleMessageWorker)
        val completeIds = inboxDao.getCompleteMessageIds()

        if (completeIds.isNotEmpty()) {
            debugLine(TAG, "Found ${completeIds.size} complete inbox message(s) to recover")

            for (messageId in completeIds) {
                if (isProcessingActive(messageId)) {
                    debugLine(TAG, "Skipping $messageId — already being processed")
                    continue
                }

                try {
                    val firstChunk = inboxDao.getFirstMessage(messageId)
                    if (firstChunk == null) {
                        debugLine(TAG, "No inbox data for $messageId, skipping")
                        continue
                    }

                    val type = firstChunk.type
                    if (type == Type.PROFILE || type == Type.REACTION) {
                        debugLine(TAG, "Cleaning up stale $type inbox chunks for $messageId")
                        val recoveryContentKey = contentKeyOf(firstChunk)
                        inboxDao.deleteByContent(recoveryContentKey)
                        continue
                    }

                    // Assembly must run under WorkManager protection, never as a
                    // fire-and-forget coroutine: doWork() returning while assembly
                    // is still running drops the doze protection mid-write.
                    debugLine(TAG, "Re-triggering assembly for $messageId ($type)")
                    AssembleMessageWorker.enqueue(applicationContext, messageId)
                } catch (e: Exception) {
                    debugLine(TAG, "Failed to recover $messageId: ${e.message}")
                }
            }
        }

        // Pass 2: incomplete RECEIVING messages (placeholders stuck with partial chunks)
        try {
            val receivingMessages = messageDao.getReceivingMessages()
            if (receivingMessages.isNotEmpty()) {
                debugLine(TAG, "Found ${receivingMessages.size} RECEIVING message(s) to check")

                for (msg in receivingMessages) {
                    try {
                        val ck = contentKeyOf(msg.messageId, msg.chatGroupId, msg.originalSenderId, msg.date)
                        val count = inboxDao.countChunksByContent(ck)
                        val total = inboxDao.getTotalChunksByContent(ck)

                        if (count > 0 && count >= total) {
                            debugLine(TAG, "RECEIVING $msg.messageId is actually complete ($count/$total), skipping")
                            continue
                        }

                        val originalSender = msg.originalSenderId
                        if (!originalSender.isNullOrEmpty()) {
                            // Resume-aware: report the missing range so the sender can
                            // re-send only those chunks (see the PENDING/SEND_ME handlers).
                            val missing = missingChunksByContent(inboxDao, ck)
                            val missingRange = if (missing != null && missing.isNotEmpty()) {
                                "${missing.min()},${missing.max()}"
                            } else {
                                null
                            }
                            debugLine(TAG, "RECEIVING ${msg.messageId} stalled at $count/$total, sending sendMe to $originalSender (missing: ${missingRange ?: "all"})")
                            notifyRemotePeer(originalSender, msg.messageId, Notify.SEND_ME, missingRange)
                        } else {
                            debugLine(TAG, "RECEIVING ${msg.messageId} has no originalSenderId, skipping")
                        }
                    } catch (e: Exception) {
                        debugLine(TAG, "Failed to process RECEIVING ${msg.messageId}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            debugLine(TAG, "Failed to query RECEIVING messages: ${e.message}")
        }

        // Status import required. Suppress unused warning on the imported symbol.
        return Result.success()
    }
}