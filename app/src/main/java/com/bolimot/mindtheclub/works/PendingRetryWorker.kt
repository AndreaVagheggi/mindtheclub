package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.MySelf
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
        val entries = PendingMessageTracker.getAll(applicationContext)

        if (entries.isEmpty()) {
            debugLine("PendingRetryWorker", "No pending messages to retry")
            return Result.success()
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

        return Result.success()
    }
}

