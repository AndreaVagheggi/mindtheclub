package com.bolimot.mindtheclub.works

// DESKTOP: this is the App's InboxRecoveryWorker.kt with these differences:
//
//   - PASS 1 (complete sets whose processing never ran). The App re-triggers assembly through
//     AssembleMessageWorker and deletes stale profile and reaction chunks. On the PC only a
//     TEXT is processed, by calling fullMessageReceivedEvent directly (a text needs no
//     assembly). Every other type is LEFT ALONE, chunks included: they are complete sets the
//     PC cannot process yet and has deliberately not confirmed, so the sender still holds
//     them. Deleting them here would make the PC ask for them again at every announcement;
//   - pickRecoverySource (asking a group member that holds a complete copy) is gone: the PC
//     asks the original sender, which is also the App's fallback;
//   - DeliveryHealth.recordHeartbeat is gone.
//
// Passes 2 and 3, the ones that re-solicit a stalled transfer, are the App's code unchanged.

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.receiving.fullMessageReceivedEvent
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

        // Pass 3 bounds. Younger sets are still being fed by the reactive someMissing path;
        // older ones are dead transfers.
        private const val MIN_ORPHAN_AGE_MS = 5 * 60 * 1000L
        private const val MAX_ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L

        /** Ceiling for pass 2, same window as [MAX_ORPHAN_AGE_MS]. See the use site. */
        private const val MAX_RECEIVING_AGE_MS = 24 * 60 * 60 * 1000L

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

        // Pass 1: complete messages whose processing needs re-triggering.
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
                    // DESKTOP: see the top of the file.
                    if (type != Type.TEXT) {
                        debugLine(TAG, "Complete $type set $messageId waits for the PC to support it, left alone")
                        continue
                    }

                    debugLine(TAG, "Re-triggering processing for $messageId ($type)")
                    fullMessageReceivedEvent(messageId)
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
                            debugLine(TAG, "RECEIVING ${msg.messageId} is actually complete ($count/$total), skipping")
                            continue
                        }

                        // Capped at 24 hours, the same as pass 3: past that point pass 3 has
                        // already deleted every orphan chunk set of that age, so a later
                        // request cannot be served by anyone anyway. The placeholder is left
                        // alone, only the soliciting stops. (See the App's file for the loop
                        // without a ceiling that this replaced.)
                        val age = System.currentTimeMillis() - msg.date
                        if (age > MAX_RECEIVING_AGE_MS) {
                            debugLine(
                                TAG,
                                "RECEIVING ${msg.messageId} is ${age / 3600000}h old, no longer soliciting"
                            )
                            continue
                        }

                        // originalSenderId is only populated for group messages. For a 1:1 the
                        // placeholder's fromUserId IS the sender, so fall back to it.
                        val originalSender = msg.originalSenderId?.takeIf { it.isNotEmpty() }
                            ?: msg.fromUserId.takeIf { !it.startsWith("group") }
                        if (!originalSender.isNullOrEmpty()) {
                            // Resume aware: report the missing range so the sender re-sends
                            // only those chunks (see the PENDING/SEND_ME handlers).
                            val missing = missingChunksByContent(inboxDao, ck)
                            val missingRange = if (missing != null && missing.isNotEmpty()) {
                                "${missing.min()},${missing.max()}"
                            } else {
                                null
                            }
                            debugLine(TAG, "RECEIVING ${msg.messageId} stalled at $count/$total, sending sendMe to $originalSender (missing: ${missingRange ?: "all"})")
                            notifyRemotePeer(originalSender, msg.messageId, Notify.SEND_ME, missingRange)
                        } else {
                            debugLine(TAG, "RECEIVING ${msg.messageId} has no resolvable sender, skipping")
                        }
                    } catch (e: Exception) {
                        debugLine(TAG, "Failed to process RECEIVING ${msg.messageId}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            debugLine(TAG, "Failed to query RECEIVING messages: ${e.message}")
        }

        // Pass 3: incomplete chunk sets with NO Message row. This pass re-solicits ANY orphan
        // incomplete set every run, same cadence and same resume aware sendMe as pass 2.
        try {
            val orphanIds = inboxDao.getDistinctMessageIds()
            for (messageId in orphanIds) {
                try {
                    if (isProcessingActive(messageId)) continue
                    if (CancelledTransferRegistry.isCancelled(applicationContext, messageId)) continue

                    val firstChunk = inboxDao.getFirstMessage(messageId) ?: continue
                    val ck = contentKeyOf(firstChunk)
                    val count = inboxDao.countChunksByContent(ck)
                    val total = inboxDao.getTotalChunksByContent(ck)
                    if (count <= 0 || count >= total) continue // complete sets are pass 1 territory

                    // Sets with a Message row are pass 2 territory; asking twice per run would
                    // double the sendMe traffic for the same transfer.
                    val placeholderKey = firstChunk.groupId.ifEmpty { firstChunk.messageId }
                    if (messageDao.getMessage(placeholderKey) != null) continue

                    val age = System.currentTimeMillis() - firstChunk.date
                    if (age > MAX_ORPHAN_AGE_MS) {
                        val dropped = inboxDao.deleteByContent(ck)
                        debugLine(TAG, "Orphan set $messageId expired (${age / 3600000}h old), dropped $dropped chunk(s)")
                        continue
                    }
                    if (age < MIN_ORPHAN_AGE_MS) continue // in flight, the reactive path is still hot

                    val sender = firstChunk.originalSenderId?.takeIf { it.isNotEmpty() }
                        ?: firstChunk.fromUserId
                    if (sender.startsWith("group")) continue

                    val missing = missingChunksByContent(inboxDao, ck) ?: continue
                    val missingRange = if (missing.isNotEmpty()) "${missing.min()},${missing.max()}" else null
                    debugLine(TAG, "Orphan set $messageId (${firstChunk.type}) stalled at $count/$total, sending sendMe to $sender (missing: ${missingRange ?: "all"})")
                    notifyRemotePeer(sender, messageId, Notify.SEND_ME, missingRange)
                } catch (e: Exception) {
                    debugLine(TAG, "Failed to process orphan set $messageId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            debugLine(TAG, "Failed to query orphan sets: ${e.message}")
        }

        return Result.success()
    }
}
