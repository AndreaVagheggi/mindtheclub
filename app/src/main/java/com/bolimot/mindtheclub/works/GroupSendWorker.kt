package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.sending.sendGroupMessageSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class GroupSendWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    override suspend fun doWork(): Result {
        val messageDataJson = inputData.getString("messageDataJson")
            ?: return Result.failure()

        val message: MessageData = try {
            json.decodeFromString<MessageData>(messageDataJson)
        } catch (e: Exception) {
            debugLine("GroupSendWorker", "Failed to parse MessageData: ${e.message}")
            return Result.failure()
        }

        return withContext(Dispatchers.IO) {
            try {
                debugLine("GroupSendWorker", "Retrying group send: ${message.messageId}")
                sendGroupMessageSuspend(message)
                debugLine("GroupSendWorker", "Group send succeeded: ${message.messageId}")
                Result.success()
            } catch (e: Exception) {
                debugLine("GroupSendWorker", "Group send still failing: ${e.message}")
                Result.retry()
            }
        }
    }
}
