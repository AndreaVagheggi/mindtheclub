package com.bolimot.mindtheclub.works

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.functions.debugLine
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

fun submitSendMessageWorker(
    messageData: MessageData,
    context: Context,
    resendMissing: List<Int>? = null
) {
    val messageDataJson = Json.encodeToString(messageData)
    val inputData = if (resendMissing.isNullOrEmpty()) {
        workDataOf("messageDataJson" to messageDataJson)
    } else {
        // Partial sendMe: the requester already holds the other chunks.
        workDataOf(
            "messageDataJson" to messageDataJson,
            "resendMissing" to resendMissing.toIntArray()
        )
    }

    debugLine("submitSendMessageWorker", "Work Data: ${messageData.uri}")
    val dispatchRequestBuilder = OneTimeWorkRequestBuilder<SendMessageWorker>()
        .setInputData(inputData)
        .addTag(
            contentTag(
                messageId = messageData.messageId,
                toUserId = messageData.toUserId,
                chatGroupId = messageData.chatGroupId ?: "",
                originalSenderId = messageData.originalSenderId ?: "",
                messageDate = messageData.date
            )
        )
        .setBackoffCriteria(
            BackoffPolicy.LINEAR,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        )

    // Batch building for a large media message takes minutes: without expedited
    // status doze freezes the worker mid-build (observed on a 200MB relay).
    // Mirrors submitDispatchWorker; quota fallback keeps it a plain request.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dispatchRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }

    val dispatchRequest = dispatchRequestBuilder.build()

    val uniqueWorkName = "build${messageData.messageId}"

    WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.REPLACE,
        dispatchRequest
    )

    debugLine("submitSendMessageWorker", "Work Submitted")
}
