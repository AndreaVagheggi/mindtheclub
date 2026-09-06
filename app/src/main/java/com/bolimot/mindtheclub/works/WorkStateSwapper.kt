package com.bolimot.mindtheclub.works

import android.content.Context
import android.os.Build
import androidx.concurrent.futures.await
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val TAG_PREFIX_MSG = "_msg:"

object WorkStateSwapper {

    // Tag dei nostri worker
    const val TAG_DISPATCH_NORMAL = "dispatch_normal" // For API < 12, running in foreground
    const val TAG_DISPATCH_EXPEDITED = "dispatch_expedited" // For API < 12, running in background
    const val TAG_DISPATCH_V12_PLUS = "dispatch_v12_plus" // For API 12+

    fun swapDispatchWorkers(isForeground: Boolean, context: Context, scope: CoroutineScope) {
        // This logic is ONLY for Android 11 and older.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return
        }

        // Chi cercare, e con cosa sostituirlo
        val (tagToFind, tagToAdd) = if (isForeground) {
            // App in foreground: background workers become normal
            TAG_DISPATCH_EXPEDITED to TAG_DISPATCH_NORMAL
        } else {
            // App in background: normal workers become expedited
            TAG_DISPATCH_NORMAL to TAG_DISPATCH_EXPEDITED
        }

        val workManager = WorkManager.getInstance(context)

        scope.launch {
            debugLine("WorkStateSwapper", "Swapping workers. Finding: $tagToFind, Adding: $tagToAdd")
            val workInfos: List<WorkInfo> = try {
                workManager.getWorkInfosByTag(tagToFind).await()
            } catch (e: Exception) {
                debugLine("WorkStateSwapper", "Error getting work infos: ${e.message}")
                listOf()
            }

            if (workInfos.isEmpty()) {
                debugLine("WorkStateSwapper", "No workers found with tag $tagToFind. Nothing to do.")
                return@launch
            }

            debugLine("WorkStateSwapper", "Found ${workInfos.size} workers to swap.")

            for (workInfo in workInfos) {
                // Only swap running or enqueued workers
                if (workInfo.state.isFinished) {
                    continue
                }

                val messageId = workInfo.tags.find {
                    it.startsWith(TAG_PREFIX_MSG)
                }?.substring(TAG_PREFIX_MSG.length)

                if (messageId.isNullOrEmpty()) {
                    debugLine("WorkStateSwapper", "Could not find messageId tag. Cannot swap.")
                    continue
                }

                val message = getMessageRepository(context).getMessage(messageId)

                if (message == null) {
                    debugLine("WorkStateSwapper", "Could not find message data for $messageId. Cannot swap.")
                    continue
                }

                // NETWORK CHANGE
                // Re-create the common constraints
//                val constraints = Constraints.Builder()
//                    .setRequiredNetworkType(NetworkType.CONNECTED)
//                    .build()

                // Re-create the input data
                val inputData = workDataOf(
                    "messageId" to messageId,
                    "toUserId" to message.toUserId,
                    "groupId" to message.groupId,
                    "uriString" to message.uri,
                )

                // Build the new request
                val newRequestBuilder = OneTimeWorkRequestBuilder<DispatchWorker>()
                    .setInputData(inputData)
//                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                    .addTag(tagToAdd) // Add the new tag

                // Only set expedited if we are moving to the background
                if (!isForeground) {
                    newRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }

                // Enqueue the new work, replacing the old one
                workManager.enqueueUniqueWork(
                    messageId,
                    ExistingWorkPolicy.REPLACE,
                    newRequestBuilder.build()
                )
                debugLine("WorkStateSwapper", "Successfully swapped worker for $messageId to state: $tagToAdd")
            }
        }
    }
}