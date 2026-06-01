package com.bolimot.mindtheclub.works

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

fun submitSendMessageWorker(messageData: MessageData, context: Context) {
    val messageDataJson = Json.encodeToString(messageData)
    val inputData = workDataOf(
        "messageDataJson" to messageDataJson
    )

    debugLine("submitSendMessageWorker", "Work Data: ${messageData.uri}")
    val dispatchRequest = OneTimeWorkRequestBuilder<SendMessageWorker>()
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
        .build()

    val uniqueWorkName = "build${messageData.messageId}"

    WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.REPLACE,
        dispatchRequest
    )

    debugLine("submitSendMessageWorker", "Work Submitted")
}
