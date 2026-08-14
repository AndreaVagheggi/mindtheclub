package com.bolimot.mindtheclub.sending

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.groupMessageStatus.GroupMessageStatus
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.works.GroupSendWorker
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.concurrent.TimeUnit

const val GROUP_DISPATCH_FANOUT = 2
const val GROUP_HOP_LIMIT = 3
const val MAX_FAIL_COUNT_PER_TARGET = 5

private const val PREFS_GROUP_DISPATCH = "group_dispatch_counters"
private const val COUNTER_EXPIRE_MS = 14L * 24 * 60 * 60 * 1000

private fun counterKey(originalMessageId: String) = "directAllReceivedCount_$originalMessageId"

fun incrementDirectAllReceived(context: Context, originalMessageId: String): Int {
    val prefs = context.getSharedPreferences(PREFS_GROUP_DISPATCH, Context.MODE_PRIVATE)
    val current = getDirectAllReceivedCount(context, originalMessageId)
    val updated = current + 1
    prefs.edit { putString(counterKey(originalMessageId), "$updated|${System.currentTimeMillis()}") }
    return updated
}

fun getDirectAllReceivedCount(context: Context, originalMessageId: String): Int {
    val prefs = context.getSharedPreferences(PREFS_GROUP_DISPATCH, Context.MODE_PRIVATE)
    val raw = prefs.getString(counterKey(originalMessageId), null) ?: return 0
    val parts = raw.split("|")
    if (parts.size != 2) {
        prefs.edit { remove(counterKey(originalMessageId)) }
        return 0
    }
    val count = parts[0].toIntOrNull() ?: 0
    val timestamp = parts[1].toLongOrNull() ?: 0L
    if (System.currentTimeMillis() - timestamp > COUNTER_EXPIRE_MS) {
        prefs.edit { remove(counterKey(originalMessageId)) }
        return 0
    }
    return count
}

fun clearDirectAllReceivedCount(context: Context, originalMessageId: String) {
    context.getSharedPreferences(PREFS_GROUP_DISPATCH, Context.MODE_PRIVATE)
        .edit { remove(counterKey(originalMessageId)) }
}

suspend fun tryDispatchNextGroupMember(
    context: Context,
    originalMessageId: String,
    chatGroupId: String,
    originalSenderId: String,
    messageDate: Long
) {
    val myUserId: String = MySelf.userId() ?: return

    val count = getDirectAllReceivedCount(context, originalMessageId)
    if (count >= GROUP_DISPATCH_FANOUT) {
        debugLine("groupDispatch", "Fanout satisfied ($count/$GROUP_DISPATCH_FANOUT) for $originalMessageId")
        return
    }

    try {
        val db = Firebase.firestore
        val deliveryDocId = computeDeliveryDocId(chatGroupId, originalSenderId, messageDate)
        val deliveryRef = db.collection("groupDelivery").document(deliveryDocId)
        val doc = deliveryRef.get().await()

        if (!doc.exists()) {
            debugLine("groupDispatch", "No delivery doc")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val members = doc.get("members") as? Map<String, Boolean> ?: return
        val available = members.filter { it.value && it.key != myUserId }.keys.toList()

        if (available.isEmpty()) {
            debugLine("groupDispatch", "No available members right now")
            return
        }

        val hopCount = (doc.getLong("hopCount") ?: 0L).toInt()
        if (hopCount >= GROUP_HOP_LIMIT) {
            debugLine("groupDispatch", "Hop limit reached ($hopCount)")
            return
        }

        val nextTarget = available.shuffled().first()
        deliveryRef.update(
            mapOf(
                "members.$nextTarget" to false,
                "hopCount" to hopCount + 1
            )
        ).await()
        debugLine("groupDispatch", "Reserved $nextTarget (hop ${hopCount + 1})")

        val repo = getMessageRepository(context)
        val message = repo.getMessage(originalMessageId)
        if (message == null) {
            debugLine("groupDispatch", "Original message $originalMessageId not in DB, releasing")
            deliveryRef.update("members.$nextTarget", true).await()
            return
        }

        val totalMembers = (doc.getLong("totalMembers") ?: members.size.toLong()).toInt()
        val newMemberMessageId = guid()
        val memberMessage = MessageData.fromMessage(message).copy(
            toUserId = nextTarget,
            messageId = newMemberMessageId,
            groupId = message.messageId,
            groupSize = totalMembers + 1,
            chatGroupId = chatGroupId,
            originalSenderId = originalSenderId
        )
        sendSingleMessage(memberMessage)
        debugLine("groupDispatch", "Dispatched to $nextTarget (messageId=$newMemberMessageId)")
    } catch (e: Exception) {
        debugLine("groupDispatch", "Error in tryDispatchNextGroupMember: ${e.message}")
    }
}

/**
 * Release a failed target. If the target has been failed by too many peers globally
 * (tracked in the delivery doc), do NOT release it — consider it dead for this message.
 * Uses atomic FieldValue.increment to avoid race conditions between peers.
 */
suspend fun releaseFailedGroupTarget(
    chatGroupId: String,
    originalSenderId: String,
    messageDate: Long,
    toUserId: String
) {
    try {
        val db = Firebase.firestore
        val deliveryDocId = computeDeliveryDocId(chatGroupId, originalSenderId, messageDate)
        val deliveryRef = db.collection("groupDelivery").document(deliveryDocId)

        // Atomically increment the global fail count for this target.
        deliveryRef.update("failCount.$toUserId", FieldValue.increment(1)).await()

        val doc = deliveryRef.get().await()
        if (!doc.exists()) return

        @Suppress("UNCHECKED_CAST")
        val members = doc.get("members") as? Map<String, Any> ?: return
        if (!members.containsKey(toUserId)) {
            debugLine("groupDispatch", "$toUserId already removed, no release needed")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val failCounts = doc.get("failCount") as? Map<String, Long> ?: emptyMap()
        val currentFailCount = (failCounts[toUserId] ?: 0L).toInt()
        if (currentFailCount >= MAX_FAIL_COUNT_PER_TARGET) {
            debugLine("groupDispatch", "$toUserId has $currentFailCount global fails, declared unreachable, NOT releasing")
            return
        }

        val hopCount = (doc.getLong("hopCount") ?: 0L).toInt()
        if (hopCount >= GROUP_HOP_LIMIT) {
            debugLine("groupDispatch", "Hop limit reached, NOT releasing $toUserId")
            return
        }

        deliveryRef.update("members.$toUserId", true).await()
        debugLine("groupDispatch", "Released failed target $toUserId back to available (failCount=$currentFailCount)")
    } catch (e: Exception) {
        debugLine("groupDispatch", "Error releasing: ${e.message}")
    }
}

fun sendGroupMessage(message: MessageData) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            sendGroupMessageSuspend(message)
        } catch (e: Exception) {
            debugLine("sendGroupMessage", "Error: ${e.message}")
            debugLine("sendGroupMessage", "Scheduling WorkManager retry for ${message.messageId}")
            submitGroupSendWorker(message, App.context())
        }
    }
}

internal suspend fun sendGroupMessageSuspend(message: MessageData) {
    debugLine("sendGroupMessage", "Sending group message: ${message.messageId}")

    val groupId = message.toUserId
    val senderUserId = message.fromUserId
    val deliveryDocId = computeDeliveryDocId(groupId, senderUserId, message.date)

    val db = Firebase.firestore

    val groupDoc = db.collection("groups")
        .document(groupId)
        .get()
        .await()

    if (!groupDoc.exists()) {
        debugLine("sendGroupMessage", "Error: Group $groupId does not exist")
        return
    }

    @Suppress("UNCHECKED_CAST")
    val membersMap = groupDoc.get("members") as? Map<String, Any>
    if (membersMap == null) {
        debugLine("sendGroupMessage", "Error: No members found in group $groupId")
        return
    }

    val otherMembers = membersMap.keys.filter { it != MySelf.userId() }

    if (otherMembers.isEmpty()) {
        debugLine("sendGroupMessage", "No other members to send to")
        return
    }

    val groupSize = membersMap.size

    val deliveryRef = db.collection("groupDelivery").document(deliveryDocId)
    val deliveryDoc = deliveryRef.get().await()

    val availableMembers: List<String>

    if (!deliveryDoc.exists()) {
        val membersData = otherMembers.associateWith { true }

        deliveryRef.set(mapOf(
            "members" to membersData,
            "groupId" to groupId,
            "senderId" to senderUserId,
            "originalMessageId" to message.messageId,
            "totalMembers" to otherMembers.size,
            "messageDate" to message.date,
            "expireAt" to Timestamp(Date(System.currentTimeMillis() + 14 * 24 * 60 * 60 * 1000L))
        )).await()

        val statusDao = DatabaseProvider.provideDatabase(App.context()).groupMessageStatusDao()
        val statusEntries = otherMembers.map { userId ->
            GroupMessageStatus(
                messageId = message.messageId,
                memberUserId = userId,
                status = ContextCompat.getString(App.context(), R.string.sent)
            )
        }
        statusDao.insertAll(statusEntries)

        availableMembers = otherMembers
        debugLine("sendGroupMessage", "Created delivery doc with ${otherMembers.size} members")
    } else {
        @Suppress("UNCHECKED_CAST")
        val existing = deliveryDoc.get("members") as? Map<String, Boolean> ?: emptyMap()
        availableMembers = existing.filter { it.value }.keys.toList()
    }

    if (availableMembers.isEmpty()) {
        debugLine("sendGroupMessage", "No available members to send to")
        return
    }

    val targets = availableMembers.shuffled().take(GROUP_DISPATCH_FANOUT)

    for ((index, userId) in targets.withIndex()) {
        if (index > 0) kotlinx.coroutines.delay(2000)

        val memberMessageId = guid()
        val memberMessage = message.copy(
            toUserId = userId,
            messageId = memberMessageId,
            groupId = message.messageId,
            groupSize = groupSize,
            chatGroupId = groupId,
            originalSenderId = senderUserId
        )

        debugLine("SEND GROUP MESSAGE", "Sending message to $userId")

        sendSingleMessage(memberMessage)

        deliveryRef.update("members.$userId", false).await()
        debugLine("sendGroupMessage", "Set $userId as unavailable")
    }

    debugLine("sendGroupMessage", "Sent to ${targets.size} of ${availableMembers.size} available members")

    if (message.type != Type.REACTION) {
        getMessageRepository(App.context()).updateStatus(
            message.messageId,
            ContextCompat.getString(App.context(), R.string.sent)
        )
    }
}

/**
 * @param countsTowardFanout false when the member REFUSED the transfer rather
 * than received it (see refuseIncomingGroupTransfer). Everything else is
 * identical, the member is dropped from the delivery map exactly the same way,
 * but a refusal must not push the fanout counter: two refusals would otherwise
 * satisfy GROUP_DISPATCH_FANOUT and the sender would stop relaying to the
 * members who are still waiting for it.
 */
fun handleGroupDeliveryConfirmation(
    deliveryDocId: String?,
    fromUserId: String,
    countsTowardFanout: Boolean = true
) {
    if (deliveryDocId.isNullOrEmpty()) return

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val db = Firebase.firestore
            val deliveryRef = db.collection("groupDelivery").document(deliveryDocId)
            val deliveryDoc = deliveryRef.get().await()

            if (!deliveryDoc.exists()) return@launch

            // CHANGE SOAK
            val originalMessageId = deliveryDoc.getString("originalMessageId")
            val senderId = deliveryDoc.getString("senderId")

            deliveryRef.update("members.$fromUserId", FieldValue.delete()).await()
            debugLine("groupDelivery", "Removed $fromUserId from delivery tracking")

            val chatGroupIdFromDoc = deliveryDoc.getString("groupId")
            val messageDateFromDoc = deliveryDoc.getLong("messageDate") ?: 0L

            if (originalMessageId != null && senderId == MySelf.userId()) {
                val statusDao = DatabaseProvider.provideDatabase(App.context()).groupMessageStatusDao()
                val currentMemberStatus = statusDao.getStatusForMember(originalMessageId, fromUserId)
                if (currentMemberStatus != Notify.SEEN) {
                    statusDao.updateStatus(originalMessageId, fromUserId, ContextCompat.getString(App.context(), R.string.delivered))
                    debugLine("groupDelivery", "Updated GroupMessageStatus: $fromUserId → Delivered for $originalMessageId")
                } else {
                    debugLine("groupDelivery", "Skipped GroupMessageStatus update for $fromUserId (already Seen) for $originalMessageId")
                }

                val count = if (countsTowardFanout) {
                    incrementDirectAllReceived(App.context(), originalMessageId)
                } else {
                    debugLine("groupDelivery", "$fromUserId refused $originalMessageId, not counting towards fanout")
                    getDirectAllReceivedCount(App.context(), originalMessageId)
                }
                debugLine("groupDelivery", "Direct allReceived: $count/$GROUP_DISPATCH_FANOUT for $originalMessageId")
                if (count < GROUP_DISPATCH_FANOUT && chatGroupIdFromDoc != null && messageDateFromDoc > 0L && senderId != null) {
                    tryDispatchNextGroupMember(
                        context = App.context(),
                        originalMessageId = originalMessageId,
                        chatGroupId = chatGroupIdFromDoc,
                        originalSenderId = senderId,
                        messageDate = messageDateFromDoc
                    )
                }
            }

            val updated = deliveryRef.get().await()
            @Suppress("UNCHECKED_CAST")

            val remaining = if (updated.exists()) {
                updated.get("members") as? Map<String, Any>
            } else {
                null
            }

            if (remaining.isNullOrEmpty()) {
                if (originalMessageId != null) {
                    if (senderId == MySelf.userId()) {
                        val repo = getMessageRepository(App.context())
                        val currentMsg = repo.getMessage(originalMessageId)

                        if (currentMsg != null && currentMsg.status != Notify.SEEN) {
                            val messageViewModel = com.bolimot.mindtheclub.functions.getMessageViewModel(MySelf.userId()!!, currentMsg.toUserId)
                            messageViewModel.updateStatus(
                                originalMessageId,
                                ContextCompat.getString(App.context(), R.string.delivered)
                            )

                            val statusDao = DatabaseProvider.provideDatabase(App.context()).groupMessageStatusDao()
                            val allStatuses = statusDao.getStatusesForMessage(originalMessageId)
                            val allSeen = allStatuses.isNotEmpty() && allStatuses.all { it.status == Notify.SEEN }

                            if (allSeen) {
                                messageViewModel.updateStatus(originalMessageId, Notify.SEEN)
                                debugLine("groupDelivery", "Deferred seen applied for $originalMessageId (all members seen)")
                            }
                        }
                    } else if (senderId != null) {
                        notifyRemotePeer(senderId, originalMessageId, Notify.ALL_RECEIVED, "relay:$fromUserId")
                    }
                }

                if (updated.exists()) {
                    deliveryRef.delete().await()
                }

                if (originalMessageId != null) {
                    clearDirectAllReceivedCount(App.context(), originalMessageId)
                }

                debugLine("groupDelivery", "All members confirmed. Delivery doc deleted.")
            }
        } catch (e: Exception) {
            debugLine("groupDelivery", "Error handling confirmation: ${e.message}")
        }
    }
}

fun submitGroupSendWorker(message: MessageData, context: Context) {
    val messageDataJson = Json.encodeToString(message)
    val inputData = workDataOf("messageDataJson" to messageDataJson)

    val workRequest = OneTimeWorkRequestBuilder<GroupSendWorker>()
        .setInputData(inputData)
        .setInitialDelay(30, TimeUnit.SECONDS)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        )
        .build()

    val uniqueWorkName = "groupSend_${message.messageId}"

    WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.KEEP,
        workRequest
    )

    debugLine("submitGroupSendWorker", "Scheduled retry for group message ${message.messageId}")
}

fun propagateGroupMessage(receivedMessage: MessageData) {
    val chatGroupId = receivedMessage.chatGroupId ?: return
    val originalSenderId = receivedMessage.originalSenderId ?: return
    val deliveryDocId = computeDeliveryDocId(chatGroupId, originalSenderId, receivedMessage.date)

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val db = Firebase.firestore
            val deliveryRef = db.collection("groupDelivery").document(deliveryDocId)
            val deliveryDoc = deliveryRef.get().await()

            if (!deliveryDoc.exists()) {
                debugLine("propagate", "No delivery doc found, all members already served")
                return@launch
            }

            @Suppress("UNCHECKED_CAST")
            val members = deliveryDoc.get("members") as? Map<String, Boolean> ?: emptyMap()
            val availableMembers = members.filter { it.value }.keys
                .filter { it != MySelf.userId() }
                .toList()

            if (availableMembers.isEmpty()) {
                debugLine("propagate", "No available members to forward to")
                return@launch
            }

            val targets = availableMembers.shuffled().take(GROUP_DISPATCH_FANOUT)

            for ((index, userId) in targets.withIndex()) {
                if (index > 0) kotlinx.coroutines.delay(2000)
                val newMemberMessageId = guid()
                val forwardMessage = receivedMessage.copy(
                    toUserId = userId,
                    messageId = newMemberMessageId
                )

                debugLine("propagate", "Forwarding to $userId")
                sendSingleMessage(forwardMessage)

                deliveryRef.update("members.$userId", false).await()
            }

            debugLine("propagate", "Forwarded to ${targets.size} members")

        } catch (e: Exception) {
            debugLine("propagate", "Error: ${e.message}")
        }
    }
}

fun computeDeliveryDocId(chatGroupId: String, originalSenderId: String, date: Long): String {
    return chatGroupId.removePrefix("group") + originalSenderId + date.toString()
}