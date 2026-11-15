package com.bolimot.mindtheclub.works

// DESKTOP: the App's file, with these differences:
//
//   - no network constraint: the PC's WorkManager has none (androidx/work/Work.kt), and a PC
//     running this program is taken to be online (hasNetworkAvailable); the exponential
//     backoff alone spaces the attempts;
//   - the inbox is listed over REST (Backend.contactRequests) instead of with the Firestore SDK;
//   - it is also enqueued when the engine starts (Engine.start): on the phone the open app
//     listens to its inbox (AppTab.setupRequestsListener) and accepts what is already there,
//     and the PC has no listener, so a start of the program does that job once.

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.contactAcquisition.autoAcceptRequestDocument
import com.bolimot.mindtheclub.contactAcquisition.isAutoInviteEnabled
import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Retries the processing of incoming contact request documents when the inline signal handler
 * could not. It processes every pending request, not just the one that triggered the signal,
 * and stops for good after [MAX_ATTEMPTS] or when auto-invite is off.
 */
class ContactRequestRetryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "ContactRequestRetry"
        private const val TAG = "ContactRequestRetry"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ContactRequestRetryWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            // KEEP: several signals in a burst must collapse into one retry chain.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            debugLine(TAG, "Retry enqueued (30s exponential backoff)")
        }
    }

    override suspend fun doWork(): Result {
        if (!isAutoInviteEnabled(applicationContext)) {
            debugLine(TAG, "Auto-invite disabled, requests will surface on next app open")
            return Result.success()
        }
        val myUserId = MySelf.userId()
        if (myUserId.isNullOrEmpty()) return Result.success()

        return try {
            val docs = withContext(Dispatchers.IO) { Backend.contactRequests(myUserId) }

            if (docs.isEmpty()) {
                debugLine(TAG, "No pending request documents")
                return Result.success()
            }

            var failures = 0
            for (doc in docs) {
                if (!autoAcceptRequestDocument(doc, applicationContext)) failures++
            }
            debugLine(TAG, "Processed ${docs.size} request(s), $failures failure(s), attempt $runAttemptCount")

            // A failure can also be a permanent condition (peer already exists), which
            // autoAcceptRequestDocument reports the same way as a transient one: the attempt cap
            // keeps that ambiguity from spinning for ever.
            if (failures > 0 && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: Exception) {
            debugLine(TAG, "Attempt $runAttemptCount failed: ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }
}
