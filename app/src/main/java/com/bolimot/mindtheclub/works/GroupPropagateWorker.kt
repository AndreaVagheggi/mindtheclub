package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.propagateGroupMessageSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Second chance for a relay that could not read its delivery document.
 *
 * Twin of [GroupSendWorker], same shape apposta. It exists because the relay used to be a single
 * shot: on 19 Aug a member took an 11 photo album in 21 seconds and then lost Firestore for a few
 * seconds while its link collapsed, and the two remaining members of the group never got the album
 * at all. The origin had stopped with its fanout satisfied, and nobody had told them there was
 * anything to ask for.
 *
 * Scheduled with a CONNECTED constraint, so it waits for a working network rather than burning its
 * attempts against a radio that is still down, and it survives the process being killed, which a
 * coroutine retry does not.
 */
class GroupPropagateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val TAG = "GroupPropagateWorker"
    }

    override suspend fun doWork(): Result {
        val messageDataJson = inputData.getString("messageDataJson")
            ?: return Result.failure()

        val message: MessageData = try {
            json.decodeFromString<MessageData>(messageDataJson)
        } catch (e: Exception) {
            debugLine(TAG, "Failed to parse MessageData: ${e.message}")
            return Result.failure()
        }

        val chatGroupId = message.chatGroupId
        val originalSenderId = message.originalSenderId
        if (chatGroupId.isNullOrEmpty() || originalSenderId.isNullOrEmpty() || message.date <= 0L) {
            debugLine(TAG, "Incomplete group coordinates, nothing to relay")
            return Result.failure()
        }

        val deliveryDocId = computeDeliveryDocId(chatGroupId, originalSenderId, message.date)

        return withContext(Dispatchers.IO) {
            try {
                debugLine(TAG, "Retrying relay for $deliveryDocId")
                propagateGroupMessageSuspend(message, deliveryDocId)
                debugLine(TAG, "Relay settled for $deliveryDocId")
                Result.success()
            } catch (e: Exception) {
                debugLine(TAG, "Relay still failing: ${e.message}")
                Result.retry()
            }
        }
    }
}
