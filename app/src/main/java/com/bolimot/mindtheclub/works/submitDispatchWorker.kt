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
import com.bolimot.mindtheclub.works.WorkStateSwapper.TAG_DISPATCH_NORMAL
import com.bolimot.mindtheclub.works.WorkStateSwapper.TAG_DISPATCH_V12_PLUS
import java.util.concurrent.TimeUnit

private const val TAG_PREFIX_MSG = "_msg:"

fun contentTag(
    messageId: String,
    toUserId: String,
    chatGroupId: String,
    originalSenderId: String,
    messageDate: Long
): String = if (chatGroupId.isNotEmpty() && originalSenderId.isNotEmpty() && messageDate > 0L) {
    "content_${chatGroupId}_${originalSenderId}_${messageDate}_${toUserId}"
} else {
    "peer_${messageId}_${toUserId}"
}

fun submitDispatchWorker(messageId: String, toUserId: String, context: Context, groupId: String = "", uriString: String = "", chatGroupId: String = "", originalSenderId: String = "", messageDate: Long = 0L) {
    val inputData = workDataOf(
        "messageId" to messageId,
        "toUserId" to toUserId,
        "groupId" to groupId,
        "uriString" to uriString,
        "chatGroupId" to chatGroupId,
        "originalSenderId" to originalSenderId,
        "messageDate" to messageDate
    )

    val dispatchRequestBuilder = OneTimeWorkRequestBuilder<DispatchWorker>()
        .setInputData(inputData)
        .addTag("$TAG_PREFIX_MSG$messageId")
        .addTag(contentTag(messageId, toUserId, chatGroupId, originalSenderId, messageDate))
        .setBackoffCriteria(
            BackoffPolicy.LINEAR,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dispatchRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        dispatchRequestBuilder.addTag(TAG_DISPATCH_V12_PLUS)
    } else {
        dispatchRequestBuilder.addTag(TAG_DISPATCH_NORMAL)
    }

    val dispatchRequest = dispatchRequestBuilder.build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        messageId,
        ExistingWorkPolicy.REPLACE,
        dispatchRequest
    )
}
