package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.sending.sendMessageWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SendMessageWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    override suspend fun doWork(): Result {

        val messageDataJson = inputData.getString("messageDataJson") ?: return Result.failure()

        val messageData: MessageData? = try {
            json.decodeFromString<MessageData>(messageDataJson)
        } catch (e: Exception) {
            debugLine2("doWork", "Failed to parse MessageData from JSON: ${e.message}")
            null
        }

        if (messageData == null) {
            return Result.failure()
        }

        return withContext(Dispatchers.IO) {
            debugLine2("doWork", "Calling sendMediaMessage for: $messageData, part of ${messageData.groupId}")
            if (sendMessageWork(messageData, applicationContext, this)) {
                debugLine2("doWork", "sendMediaMessage successful: work=success for $messageData.uri, part of ${messageData.groupId}")
                Result.success()
            } else {
                debugLine2("doWork", "sendMediaMessage failed: work=retry for $messageData.uri, part of ${messageData.groupId}")
                Result.retry()
            }
        }
    }
}