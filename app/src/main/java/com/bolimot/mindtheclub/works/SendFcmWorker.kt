package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.firebase.fcmSendWork
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.FCM
import kotlin.coroutines.cancellation.CancellationException
import com.bolimot.mindtheclub.functions.PendingMessageTracker


class SendFcmWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val userId = inputData.getString("userId") ?: return Result.failure()
        val content = inputData.getString("content") ?: return Result.failure()
        val information = inputData.getString("information") ?: return Result.failure()
        val collapseKey = inputData.getString("collapseKey") ?: return Result.failure()

        try {
            val result = fcmSendWork(userId, content, information, collapseKey)
            debugLine("SendFcmWorker", "FCM Send Result: $result")

            return when (result) {
                FCM.SUCCESS -> {
                    debugLine("SendFcmWorker", "FCM Send Success")
                    Result.success()
                }

                FCM.FAILURE -> {
                    debugLine("SendFcmWorker", "FCM Send Failed, retry")
                    Result.retry()
                }

                FCM.TOKEN_NOT_FOUND -> {
                    debugLine("SendFcmWorker", "FCM Token invalid, clearing pending entries for $userId")
                    PendingMessageTracker.removeAllForPeer(applicationContext, userId)
                    Result.failure()
                }
                else -> {
                    Result.retry()
                }
            }
        } catch (e: CancellationException) {
            debugLine("SendFcmWorker", "Job cancelled by System")
            throw e
        } catch (e: Exception) {
            debugLine("SendFcmWorker", "FCM Send Failed, still retry: ${e.message}")
            return Result.retry()
        }
    }
}

