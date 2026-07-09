package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.receiving.fullMessageReceivedEvent

/**
 * Runs the assembly of a fully received multi-chunk message (chunks -> file ->
 * Message row) under WorkManager protection, the same doze guarantee the send
 * path already has (submitSendMessageWorker / SendFcmWorker).
 *
 * Without this, assembly ran on a bare coroutine: when the last chunk arrived
 * and the DataSyncService stopped, doze froze the process mid-assembly and the
 * completed message sat invisible in the Inbox until the next manual wake-up.
 *
 * The work is awaited inside doWork(), so WorkManager keeps the process alive
 * until assembly finishes — and re-runs it after a process death.
 */
class AssembleMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AssembleMessageWorker"
        private const val KEY_MESSAGE_ID = "messageId"

        fun enqueue(context: Context, messageId: String) {
            // Deliberately NOT expedited: setExpedited() requires a
            // getForegroundInfo() implementation on API < 31 (crash otherwise),
            // and a plain request already starts immediately while the process
            // is alive and is guaranteed to re-run after doze/process death.
            val request = OneTimeWorkRequestBuilder<AssembleMessageWorker>()
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .build()

            // Unique per message + KEEP: repeated completion signals (inbox checks,
            // "completed" FCM, recovery passes) collapse into a single assembly run.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "assemble_$messageId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()

        return try {
            if (getInboxDao(applicationContext).getFirstMessage(messageId) == null) {
                // Chunks are deleted right after a successful assembly: nothing to do.
                debugLine(TAG, "No inbox chunks for $messageId — already assembled")
                Result.success()
            } else {
                debugLine(TAG, "Assembling $messageId under WorkManager protection")
                fullMessageReceivedEvent(messageId)
                Result.success()
            }
        } catch (e: Exception) {
            debugLine(TAG, "Assembly failed for $messageId: ${e.message}")
            Result.retry()
        }
    }
}
