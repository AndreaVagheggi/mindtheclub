package com.bolimot.mindtheclub.works

// DESKTOP: this is the App's PendingRetryWorker.kt with the group parts taken out. The two
// ladders it drives, the outgoing one (re-announce what a peer has not confirmed) and the
// incoming one (ask again for what a peer announced), are the App's code unchanged. Removed:
//
//   - retryGroupSeen and GroupSeenTracker: read receipts of group messages;
//   - pickRecoverySource: choosing a group member that holds a complete copy. A 1:1 message
//     has one holder, the peer that announced it, which is also the App's fallback;
//   - DeliveryHealth.recordHeartbeat: statistics about Android suppressing background work.

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.IncomingPendingTracker
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.receiving.missingChunksByContent
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes. For each tracked pending message whose backoff has elapsed, re-sends
 * the `pending` signal to the receiver.
 *
 * Entries are pruned after 14 days, and as soon as the contact they were addressed to is
 * deleted from the address book.
 */
class PendingRetryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "PendingRetryWorker"
        private const val INTERVAL_MINUTES = 15L

        /**
         * A one to one entry whose peer is no longer in the address book: the user deleted the
         * contact, so its queue must go with it. Group entries are exempt (see the App's file).
         * Fails safe: a database error leaves the entry alone.
         */
        private suspend fun isOrphanedDirectEntry(
            context: Context,
            entry: PendingMessageTracker.PendingEntry
        ): Boolean {
            if (!entry.chatGroupId.isNullOrEmpty()) return false
            return try {
                !getPeerDao(context).exist(entry.toUserId)
            } catch (e: Exception) {
                debugLine("PendingRetryWorker", "Peer check failed for ${entry.toUserId}: ${e.message}")
                false
            }
        }

        suspend fun retryAllNow(context: Context) {
            val entries = PendingMessageTracker.getAll(context)
            if (entries.isEmpty()) return

            val myUserId = MySelf.userId()
            val now = System.currentTimeMillis()
            var retried = 0

            for (entry in entries) {
                if (entry.toUserId == myUserId) {
                    PendingMessageTracker.remove(context, entry.messageId, entry.toUserId)
                    debugLine("PendingRetryWorker", "Purged self-addressed pending: ${entry.messageId}")
                    continue
                }

                if (isOrphanedDirectEntry(context, entry)) {
                    PendingMessageTracker.remove(context, entry.messageId, entry.toUserId)
                    debugLine("PendingRetryWorker", "Dropped pending for deleted contact: ${entry.messageId} → ${entry.toUserId}")
                    continue
                }

                val backoffDelay = PendingMessageTracker.getBackoffDelay(entry.retryCount)
                val elapsed = now - entry.lastRetryAt
                if (elapsed < backoffDelay) {
                    debugLine(
                        "PendingRetryWorker",
                        "App start skip: ${entry.messageId} → ${entry.toUserId}: " +
                                "retry #${entry.retryCount}, need ${backoffDelay / 60000}min, " +
                                "elapsed ${elapsed / 60000}min"
                    )
                    continue
                }

                debugLine("PendingRetryWorker", "App start retry: ${entry.messageId} → ${entry.toUserId}")
                notifyRemotePeer(entry.toUserId, entry.messageKey, "pending")
                PendingMessageTracker.updateRetry(context, entry)
                retried++
                delay(500L)
            }

            if (retried > 0) {
                debugLine("PendingRetryWorker", "App start: retried $retried pending message(s)")
            }
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PendingRetryWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            debugLine("PendingRetryWorker", "Scheduled periodic worker (${INTERVAL_MINUTES}min)")
        }
    }

    override suspend fun doWork(): Result {
        retryOutgoing()
        retryIncoming()
        return Result.success()
    }

    /** Messages WE owe a peer: re-send the `pending` signal. */
    private suspend fun retryOutgoing() {
        val entries = PendingMessageTracker.getAll(applicationContext)

        if (entries.isEmpty()) {
            debugLine("PendingRetryWorker", "No pending messages to retry")
            return
        }

        debugLine("PendingRetryWorker", "Found ${entries.size} pending message(s) to check")

        val now = System.currentTimeMillis()
        val maxAge = 14 * 24 * 60 * 60 * 1000L  // 14 days

        for (entry in entries) {
            if (now - entry.createdAt > maxAge) {
                PendingMessageTracker.remove(applicationContext, entry.messageId, entry.toUserId)
                debugLine("PendingRetryWorker", "Pruned stale entry: ${entry.messageId} → ${entry.toUserId} (age: ${(now - entry.createdAt) / 3600000}h)")
                continue
            }

            if (isOrphanedDirectEntry(applicationContext, entry)) {
                PendingMessageTracker.remove(applicationContext, entry.messageId, entry.toUserId)
                debugLine("PendingRetryWorker", "Dropped pending for deleted contact: ${entry.messageId} → ${entry.toUserId}")
                continue
            }

            val backoffDelay = PendingMessageTracker.getBackoffDelay(entry.retryCount)
            val elapsed = now - entry.lastRetryAt

            if (elapsed < backoffDelay) {
                debugLine(
                    "PendingRetryWorker",
                    "Skipping ${entry.messageId} → ${entry.toUserId}: " +
                            "retry #${entry.retryCount}, need ${backoffDelay / 60000}min, " +
                            "elapsed ${elapsed / 60000}min"
                )
                continue
            }

            debugLine(
                "PendingRetryWorker",
                "Re-sending pending FCM: ${entry.messageId} → ${entry.toUserId} " +
                        "(retry #${entry.retryCount + 1})"
            )
            notifyRemotePeer(entry.toUserId, entry.messageKey, "pending")
            PendingMessageTracker.updateRetry(applicationContext, entry)
        }
    }

    /**
     * Messages a peer owes US: re-issue the sendMe.
     *
     * The PENDING handler answers a `pending` with one sendMe. If that single round fails,
     * nothing else on this side would remember the message was owed, and it would wait for the
     * sender's own ladder. Arrival is detected here by looking the message up, not by hooking
     * the receive path, so an entry can never outlive the message it waits for.
     */
    private suspend fun retryIncoming() {
        val entries = IncomingPendingTracker.getAll(applicationContext)
        if (entries.isEmpty()) return

        debugLine("IncomingPending", "Checking ${entries.size} owed message(s)")

        val messageDao = getMessageDao(applicationContext)
        val inboxDao = getInboxDao(applicationContext)
        val now = System.currentTimeMillis()

        for (entry in entries) {
            // After refusing a transfer, never go on asking the sender for the very chunks
            // the user had just thrown away.
            if (CancelledTransferRegistry.isCancelled(applicationContext, entry.messageId)) {
                IncomingPendingTracker.remove(
                    applicationContext, entry.messageId, entry.fromUserId, "cancelled"
                )
                continue
            }

            val message = try {
                messageDao.getMessage(entry.messageId)
            } catch (e: Exception) {
                debugLine("IncomingPending", "Lookup failed for ${entry.messageId}: ${e.message}")
                null
            }

            if (message != null && message.status != Status.RECEIVING) {
                IncomingPendingTracker.remove(
                    applicationContext, entry.messageId, entry.fromUserId, "arrived"
                )
                continue
            }

            if (now - entry.createdAt > IncomingPendingTracker.MAX_AGE_MS) {
                IncomingPendingTracker.remove(
                    applicationContext, entry.messageId, entry.fromUserId, "expired"
                )
                continue
            }

            if (entry.retryCount >= IncomingPendingTracker.MAX_RETRIES) {
                IncomingPendingTracker.remove(
                    applicationContext, entry.messageId, entry.fromUserId, "retry cap reached"
                )
                continue
            }

            if (now - entry.lastRetryAt < IncomingPendingTracker.getBackoffDelay(entry.retryCount)) {
                continue
            }

            // Same resume aware logic as the PENDING handler: ask only for what is actually
            // missing, and ask for nothing when every chunk is already here.
            val missing = try {
                val contentKey = if (message != null) {
                    contentKeyOf(
                        entry.messageId,
                        message.chatGroupId,
                        message.originalSenderId,
                        message.date
                    )
                } else {
                    resolveContentKey(inboxDao, entry.messageId)
                }
                missingChunksByContent(inboxDao, contentKey)
            } catch (e: Exception) {
                debugLine("IncomingPending", "Chunk check failed for ${entry.messageId}: ${e.message}")
                emptyList()
            }

            if (missing == null) {
                IncomingPendingTracker.remove(
                    applicationContext, entry.messageId, entry.fromUserId, "all chunks present"
                )
                continue
            }

            val missingRange =
                if (missing.isNotEmpty()) "${missing.min()},${missing.max()}" else null

            // DESKTOP: the announcer. The App may pick another group member here, see the top.
            val askTarget = entry.fromUserId
            debugLine(
                "IncomingPending",
                "Re-requesting ${entry.messageId} from $askTarget " +
                        "(retry #${entry.retryCount + 1}/${IncomingPendingTracker.MAX_RETRIES})"
            )
            notifyRemotePeer(askTarget, entry.messageId, Notify.SEND_ME, missingRange)
            IncomingPendingTracker.updateRetry(applicationContext, entry)
            delay(500L)
        }
    }
}
