package com.bolimot.mindtheclub.works

// DESKTOP: copy of the App's submitSendMessageWorker.kt with two differences: the serializer is named explicitly, and the block that
// asks Android for expedited execution (Build.VERSION, setExpedited) is removed. Expedited work
// is how a phone keeps a job alive through doze; a PC program is not put to sleep.

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
    val messageDataJson = Json.encodeToString(MessageData.serializer(), messageData)
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

    val dispatchRequest = dispatchRequestBuilder.build()

    val uniqueWorkName = "build${messageData.messageId}"

    WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.REPLACE,
        dispatchRequest
    )

    debugLine("submitSendMessageWorker", "Work Submitted")
}
