package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.GroupSeenTracker
import com.bolimot.mindtheclub.functions.IncomingPendingTracker
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.pickRecoverySource
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.receiving.missingChunksByContent
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes. For each tracked pending message whose backoff delay
 * has elapsed, re-sends the `pending` FCM to the receiver.
 *
 * Entries older than 24 hours are pruned automatically.
 */
class PendingRetryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "PendingRetryWorker"
        private const val INTERVAL_MINUTES = 15L
        private const val MAX_SEEDER_LOOKUPS_PER_PASS = 5

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
        retryGroupSeen()
        return Result.success()
    }

    /**
     * GROUP_SEEN notifications still waiting for the sender's ack: re-send them.
     * A group seen used to be one fire and forget FCM with no second chance
     * (16 Aug: two messages stuck at Delivered because Raoul's seens died in
     * the 13 Aug notification flood). Entries are cleared by GROUP_SEEN_ACK;
     * against older senders that never ack, the cap drains them quietly.
     */
    private suspend fun retryGroupSeen() {
        val entries = GroupSeenTracker.getAll(applicationContext)
        if (entries.isEmpty()) return

        debugLine("GroupSeen", "Checking ${entries.size} unacked seen notification(s)")
        val now = System.currentTimeMillis()

        for (entry in entries) {
            if (now - entry.createdAt > GroupSeenTracker.MAX_AGE_MS) {
                GroupSeenTracker.remove(applicationContext, entry.messageId, entry.originalSenderId, "expired")
                continue
            }
            if (entry.retryCount >= GroupSeenTracker.MAX_RETRIES) {
                GroupSeenTracker.remove(applicationContext, entry.messageId, entry.originalSenderId, "retry cap reached")
                continue
            }
            if (now - entry.lastRetryAt < GroupSeenTracker.getBackoffDelay(entry.retryCount)) {
                continue
            }

            debugLine(
                "GroupSeen",
                "Re-sending GROUP_SEEN for ${entry.messageId} to ${entry.originalSenderId} " +
                        "(retry #${entry.retryCount + 1}/${GroupSeenTracker.MAX_RETRIES})"
            )
            notifyRemotePeer(entry.originalSenderId, entry.messageId, Notify.GROUP_SEEN)
            GroupSeenTracker.updateRetry(applicationContext, entry)
            delay(500L)
        }
    }

    /** Messages WE owe a peer: re-send the `pending` FCM. Behaviour unchanged. */
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
     * The PENDING handler answers a `pending` FCM with one sendMe. If that single
     * round fails, and it does (a dropped signalling socket is enough), nothing
     * used to survive it on this side and the message waited for the sender's own
     * ladder, up to 13 hours.
     *
     * Arrival is detected here, by looking the message up, rather than by hooking
     * the receive path: one query per pass leaves the hot path untouched and an
     * entry can never outlive the message it waits for. Worst case it lingers one
     * extra pass, costing nothing, because the check runs before any FCM is sent.
     */
    private suspend fun retryIncoming() {
        val entries = IncomingPendingTracker.getAll(applicationContext)
        if (entries.isEmpty()) return

        debugLine("IncomingPending", "Checking ${entries.size} owed message(s)")

        val messageDao = getMessageDao(applicationContext)
        val inboxDao = getInboxDao(applicationContext)
        val now = System.currentTimeMillis()
        // Same bound as InboxRecoveryWorker: informed choices are welcome, a
        // Firestore read per entry in a loop is not (the 15 Aug lesson).
        var seederLookups = 0

        for (entry in entries) {
            // Every other recovery path consults the registry, this one never did:
            // after refusing a transfer it went on asking the sender for the very
            // chunks the user had just thrown away, resurrecting it.
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

            // Same resume-aware logic as the PENDING handler: ask only for what is
            // actually missing, and ask for nothing when every chunk is already here.
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

            // Ask a registered complete member when one is known; the announcer
            // stays the fallback, it declared it holds the content.
            val askTarget = if (message != null && !message.chatGroupId.isNullOrEmpty()
                && seederLookups < MAX_SEEDER_LOOKUPS_PER_PASS
            ) {
                seederLookups++
                pickRecoverySource(message.chatGroupId, message.originalSenderId, message.date, entry.fromUserId)
            } else {
                entry.fromUserId
            }
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

