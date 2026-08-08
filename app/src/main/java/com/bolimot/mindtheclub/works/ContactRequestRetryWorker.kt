package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.contactAcquisition.autoAcceptRequestDocument
import com.bolimot.mindtheclub.contactAcquisition.isAutoInviteEnabled
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Retries the processing of incoming contact-request documents when the inline
 * FCM handler could not.
 *
 * The CONTACT_REQUEST nudge punches doze at high priority and cold-starts the
 * process, but a process born one second ago often has no usable network yet:
 * on Raoul's 7 Aug log the App Check warm-up failed with an unresolvable host,
 * the handler's Firestore read got PERMISSION_DENIED (no attestation token, so
 * enforcement rejects the call), and the handler logged the error and gave up.
 * The request document then sat in Firestore for six hours until the app was
 * opened by hand, at which point the very same processing took two seconds.
 *
 * This worker is the missing retry: network-constrained, exponential backoff,
 * so by the time it runs the radio is up and the App Check token is warm. It
 * processes every pending request document, not just the one that triggered
 * the nudge, and stops for good after [MAX_ATTEMPTS] or when auto-invite is
 * off (the request then surfaces in the UI on next app open, by design).
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
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            // KEEP: several nudges in a burst must collapse into one retry chain.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            debugLine(TAG, "Retry enqueued (network-constrained, 30s exponential backoff)")
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
            val docs = Firebase.firestore
                .collection("users").document(myUserId)
                .collection("requests")
                .get()
                .await()

            if (docs.isEmpty) {
                debugLine(TAG, "No pending request documents")
                return Result.success()
            }

            var failures = 0
            for (doc in docs.documents) {
                if (!autoAcceptRequestDocument(doc, applicationContext)) failures++
            }
            debugLine(TAG, "Processed ${docs.size()} request(s), $failures failure(s), attempt $runAttemptCount")

            // A failure can also be a permanent condition (peer already exists),
            // which autoAcceptRequestDocument reports the same way as a transient
            // one: the attempt cap keeps that ambiguity from spinning for ever.
            if (failures > 0 && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: Exception) {
            debugLine(TAG, "Attempt $runAttemptCount failed: ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }
}
