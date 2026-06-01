package com.bolimot.mindtheclub.sending

import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.works.SendFcmWorker
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

fun notifyRemotePeer(userId: String, messageId: String, information: String, missingItems: String? = null) {
    val content: String = missingItems?.let { "$messageId#$it" } ?: messageId

    if(userId.startsWith("group")) return

    val inputData = workDataOf(
        "userId" to userId,
        "content" to content,
        "information" to information,
        "collapseKey" to "$messageId$information"
    )

    debugLine("notifyRemotePeer", "Queued notification to be sent to $userId")

    val workRequest = OneTimeWorkRequestBuilder<SendFcmWorker>()
        .setInputData(inputData)
        .setBackoffCriteria(
            BackoffPolicy.LINEAR,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        )
        .build()

    WorkManager.getInstance(App.context()).enqueue(workRequest)
}

fun notifyGroupTyping(chatGroupId: String, type: String) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val db = Firebase.firestore
            val groupDoc = db.collection("groups")
                .document(chatGroupId)
                .get()
                .await()

            if (!groupDoc.exists()) return@launch

            @Suppress("UNCHECKED_CAST")
            val membersMap = groupDoc.get("members") as? Map<String, Any> ?: return@launch
            val myUserId = MySelf.userId() ?: return@launch
            val recipients = membersMap.keys.filter { it != myUserId }

            for (userId in recipients) {
                notifyRemotePeer(userId, chatGroupId, type)
            }

            debugLine("notifyGroupTyping", "Sent $type to ${recipients.size} members")
        } catch (e: Exception) {
            debugLine("notifyGroupTyping", "Error: ${e.message}")
        }
    }
}