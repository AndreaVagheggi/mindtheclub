package com.bolimot.mindtheclub.sending

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.groupMessageStatus.GroupMessageStatus
import com.bolimot.mindtheclub.functions.PeerProbe
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.groupHopId
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.works.GroupPropagateWorker
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

// Types whose transfers are chunk heavy enough that a weak uplink cannot feed
// two recipients at once. Decided by type rather than by chunk count because
// the type is known before the batches exist.
fun isHeavyContent(type: String): Boolean =
    type == Type.IMAGE || type == Type.MULTIPLE_IMAGES ||
    type == Type.VIDEO || type == Type.AUDIO || isFileType(type)

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

/**
 * [excludeUserId] is the member that has just exhausted its attempts, when this
 * is called from the failure path. It is kept OUT of this one draw.
 *
 * Until 1.32 the switch to somebody else happened by accident: hopCount climbed
 * with every reservation, and once it hit GROUP_HOP_LIMIT releaseFailedGroupTarget
 * bailed out with "Hop limit reached, NOT releasing", leaving the failed member
 * marked unavailable so the next draw could not pick it. Refunding the hop (which
 * fixed a real deadlock against switched off phones, 16 Aug) removed that side
 * effect with it: the counter stops climbing, the bail out never fires, and the
 * member that just failed three times goes straight back into the hat. On 19 Aug
 * an album was drawn towards the same unreachable phone three times running while
 * the other two members were never contacted at all.
 *
 * Excluding it here is the direct expression of what the old code achieved by
 * accident, and it acts on the FIRST failure rather than after three.
 *
 * Default null, so the success path at handleGroupDeliveryConfirmation keeps
 * drawing from everybody exactly as before. And an exclusion that would empty the
 * pool is dropped: better to retry the same member than to dispatch to nobody.
 */
suspend fun tryDispatchNextGroupMember(
    context: Context,
    originalMessageId: String,
    chatGroupId: String,
    originalSenderId: String,
    messageDate: Long,
    excludeUserId: String? = null
) {
    val myUserId: String = MySelf.userId() ?: return

    // Loaded before the fanout check because the goal depends on the type.
    // Heavy content stops at ONE confirmed direct delivery: in the 15 Aug
    // throttled test the origin, after seeding Dooge, grabbed White for a
    // second full upload over its mobile uplink (5 minutes) while two Wi-Fi
    // seeders could have served it in 15 seconds. Once a seed exists the
    // remaining members belong to the cascade and the informed recovery; the
    // origin still serves anyone who explicitly asks (sendMe, queue), it only
    // stops volunteering. A FAILED first delivery leaves the count at 0, so
    // the deep dispatch still walks to the next member on real failures.
    val repo = getMessageRepository(context)
    val message = repo.getMessage(originalMessageId)
    if (message == null) {
        debugLine("groupDispatch", "Original message $originalMessageId not in DB, nothing to dispatch")
        return
    }

    // Same width for every type, see the note at the first dispatch. It also
    // removes a disagreement that had been there since 15 Aug: the caller in
    // handleGroupDeliveryConfirmation asks for another member whenever the count
    // is below GROUP_DISPATCH_FANOUT, so with a target of 1 it kept calling in
    // and this line kept answering "Fanout satisfied (1/1)".
    val targetFanout = GROUP_DISPATCH_FANOUT
    val count = getDirectAllReceivedCount(context, originalMessageId)
    if (count >= targetFanout) {
        debugLine("groupDispatch", "Fanout satisfied ($count/$targetFanout) for $originalMessageId")
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

        // ifEmpty is the safety net: excluding must never turn a dispatch that
        // would have happened into one that does not.
        val candidates = available.filterNot { it == excludeUserId }.ifEmpty { available }
        if (candidates.size < available.size) {
            debugLine("groupDispatch", "Excluding just failed $excludeUserId from this draw (${candidates.size} left)")
        }

        // Same probe as the first dispatch, and for the stronger reason: this is
        // the walk that runs AFTER a target has already failed, so picking
        // another dead one costs a second full round of timeouts.
        val drawn = candidates.shuffled()
        val nextTarget = (if (isHeavyContent(message.type)) PeerProbe.preferLive(drawn) else drawn).first()
        deliveryRef.update(
            mapOf(
                "members.$nextTarget" to false,
                "hopCount" to hopCount + 1
            )
        ).await()
        debugLine("groupDispatch", "Reserved $nextTarget (hop ${hopCount + 1})")

        val totalMembers = (doc.getLong("totalMembers") ?: members.size.toLong()).toInt()
        // Stable per (content, target): a target picked up again resumes from
        // the chunks it was left at instead of restarting. See groupHopId.
        val newMemberMessageId = groupHopId(chatGroupId, originalSenderId, messageDate, nextTarget)
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

        // Release AND refund the hop this reservation consumed. Hops must measure
        // successful hand offs down the cascade, not failed attempts: overnight on
        // 16 Aug three reservations against switched off phones burned the whole
        // GROUP_HOP_LIMIT, the old "hop limit reached, NOT releasing" gate then
        // sealed the deadlock, and the proactive dispatch never recovered (the
        // receivers had to pull everything themselves on wake up). The retry walk
        // stays bounded regardless: MAX_FAIL_COUNT_PER_TARGET caps it per member.
        // The refund is skipped at zero so an initial dispatch failure (whose
        // reservation never charged a hop) cannot push the counter negative.
        val hopCount = (doc.getLong("hopCount") ?: 0L).toInt()
        val releaseUpdates = mutableMapOf<String, Any>("members.$toUserId" to true)
        if (hopCount > 0) {
            releaseUpdates["hopCount"] = FieldValue.increment(-1)
        }
        deliveryRef.update(releaseUpdates).await()
        debugLine("groupDispatch", "Released failed target $toUserId back to available (failCount=$currentFailCount, hop refunded)")
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

    // One width for everything, 22 Aug.
    //
    // Between 15 and 22 Aug the heavy types went out to a single member, on the
    // reasoning that a big transfer must produce ONE complete copy as fast as the
    // uplink allows, because only a complete copy can relay onward: splitting a
    // weak uplink across two media recipients had produced two half copies and no
    // relayer at all (15 Aug: a 1137 chunk album, six partial copies, zero
    // complete after four hours).
    //
    // The cost of that was paid on 22 Aug: a 444 chunk video went to the one
    // member that happened to be switched off, and with a single target that
    // choice IS the delivery, so the whole group waited 10m52s for three WebRTC
    // timeouts before anyone else was even offered it. With two, one dead pick
    // costs nothing: the other target has the file and relays it.
    //
    // The original hazard is not forgotten, it is addressed elsewhere now: the
    // probe below no longer commits blindly, and the two dispatches are staggered
    // by two seconds in the loop that follows. If two half copies over a weak
    // uplink ever come back, this constant is the one place to look.
    val fanout = GROUP_DISPATCH_FANOUT
    debugLine("sendGroupMessage", "Dispatch width for ${message.type}: fanout=$fanout")

    // Ask before committing, but only for the heavy types. A wrong pick costs a
    // text message almost nothing, because the other target of the wide fanout
    // has it already and relays it; it costs an album or a video everything,
    // because with fanout=1 that single target IS the delivery. Probing every
    // chat line would put up to 20 seconds in front of a message that today
    // leaves in three. See PeerProbe.
    val drawn = availableMembers.shuffled()
    val ordered = if (isHeavyContent(message.type)) PeerProbe.preferLive(drawn) else drawn

    val targets = ordered.take(fanout)

    for ((index, userId) in targets.withIndex()) {
        if (index > 0) kotlinx.coroutines.delay(2000)

        val memberMessageId = groupHopId(groupId, senderUserId, message.date, userId)
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
 * Raises the sender's bubble to Delivered once no member is left at "sent" in
 * GroupMessageStatus. The bubble used to flip on the FIRST member's ack (the
 * unconditional update in the ALL_RECEIVED handler, now gated to one to one
 * messages), while the detail view showed the truth; and the only aggregate
 * promotion lived in the "delivery doc emptied" branch, which never fires when
 * the last confirmation reaches the origin via a relay (the relayer empties
 * and deletes the doc first). This helper works off the status table alone, so
 * it is immune to the doc's lifecycle. On relayers the table is empty and the
 * call is a no op: only the original sender ever promotes.
 */
suspend fun promoteGroupAggregate(originalMessageId: String) {
    try {
        val context = App.context()
        val statusDao = DatabaseProvider.provideDatabase(context).groupMessageStatusDao()
        val statuses = statusDao.getStatusesForMessage(originalMessageId)
        if (statuses.isEmpty()) return
        val sentLabel = ContextCompat.getString(context, R.string.sent)
        if (statuses.any { it.status == sentLabel }) return
        val currentMsg = getMessageRepository(context).getMessage(originalMessageId) ?: return
        if (currentMsg.status == Notify.SEEN) return
        val delivered = ContextCompat.getString(context, R.string.delivered)
        if (currentMsg.status == delivered) return
        val myId = MySelf.userId() ?: return
        val messageViewModel = com.bolimot.mindtheclub.functions.getMessageViewModel(myId, currentMsg.toUserId)
        messageViewModel.updateStatus(originalMessageId, delivered)
        debugLine("groupDelivery", "No member left at sent, bubble promoted to Delivered for $originalMessageId")
    } catch (e: Exception) {
        debugLine("groupDelivery", "Aggregate promotion failed for $originalMessageId: ${e.message}")
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
                promoteGroupAggregate(originalMessageId)

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

/**
 * Retry for a group send that could not read its Firestore documents.
 *
 * The CONNECTED constraint is the whole point. Without it this worker fired on
 * its own timer whatever the radio was doing, and since sendGroupMessageSuspend
 * must read groupDelivery/<group+sender+date>, a document that is new for every
 * message and therefore never in the local cache, an attempt made with no
 * network cannot do anything except fail and widen the exponential ladder.
 *
 * Measured on White, 22 Aug. Three texts written in flight mode at 08:45:30,
 * 08:45:48 and 08:45:59 were on the same ladder, which by then had reached
 * intervals of ten minutes. The radio came back at 09:07:35. Two of them had
 * spent their attempt at 09:07:33, two seconds early, and went to the next rung
 * past 09:28; the third fired at 09:07:47 and was delivered in four seconds.
 * The whole difference between a message that arrives and one the user sees
 * stuck on "Sending" for 43 minutes was two seconds of timer alignment.
 *
 * With the constraint those attempts are not made at all while the radio is
 * off: hasNetworkAvailable logged "No network" nine times out of nine in that
 * window, so the constraint is demonstrably unmet there. The ladder stays where
 * it was, and the work runs the moment a network appears, its delay having long
 * since elapsed.
 *
 * The backoff policy is deliberately left EXPONENTIAL. It is tempting to make
 * it LINEAR as well, and it would be wrong: with the constraint in place the
 * defect above is already gone, while a linear ladder on a message that can
 * never be sent (deleted group, revoked membership) would keep the interval
 * near ten minutes for ever, in the order of a hundred Firestore reads a day
 * instead of five. GroupSendWorker never inspects runAttemptCount, so nothing
 * else would stop it.
 *
 * Same shape as [submitGroupPropagateWorker], which has carried this constraint
 * since 19 Aug.
 */
fun submitGroupSendWorker(message: MessageData, context: Context) {
    val messageDataJson = Json.encodeToString(message)
    val inputData = workDataOf("messageDataJson" to messageDataJson)

    val workRequest = OneTimeWorkRequestBuilder<GroupSendWorker>()
        .setInputData(inputData)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
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
            propagateGroupMessageSuspend(receivedMessage, deliveryDocId)
        } catch (e: Exception) {
            debugLine("propagate", "Error: ${e.message}. Scheduling retry.")
            submitGroupPropagateWorker(receivedMessage, App.context())
        }
    }
}

/**
 * The relay itself. Throws when it could not even find out who to forward to,
 * which is the signal for the caller to schedule a retry.
 *
 * Split out of [propagateGroupMessage] on 19 Aug. Until then the relay was a
 * single shot inside a fire and forget coroutine: one read of the delivery
 * document, and on any error a log line and nothing else. A member received an
 * 11 photo album in 21 seconds and three seconds later its Firestore read failed
 * with "the client is offline" while the link was collapsing. The cascade ended
 * there, and the other two members of the group never learned the album existed
 * (the origin had already stopped, its fanout of 1 satisfied, and nobody had
 * sent them so much as a pending to react to). The single photo five minutes
 * earlier went through on the same code, because Firestore happened to answer.
 *
 * Once ANY member has been forwarded to, a failure stops being retryable: the
 * delivery document still lists that member as available, so a second run could
 * hand it the same file twice. Whoever is left over is reached by the ordinary
 * recovery path instead. That is what [forwarded] guards.
 */
internal suspend fun propagateGroupMessageSuspend(
    receivedMessage: MessageData,
    deliveryDocId: String
) {
    val chatGroupId = receivedMessage.chatGroupId ?: return
    val originalSenderId = receivedMessage.originalSenderId ?: return

    var forwarded = 0
    try {
        val db = Firebase.firestore
        val deliveryRef = db.collection("groupDelivery").document(deliveryDocId)
        val deliveryDoc = deliveryRef.get().await()

        if (!deliveryDoc.exists()) {
            debugLine("propagate", "No delivery doc found, all members already served")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val members = deliveryDoc.get("members") as? Map<String, Boolean> ?: emptyMap()
        val availableMembers = members.filter { it.value }.keys
            .filter { it != MySelf.userId() }
            .toList()

        if (availableMembers.isEmpty()) {
            debugLine("propagate", "No available members to forward to")
            return
        }

        val targets = availableMembers.shuffled().take(GROUP_DISPATCH_FANOUT)

        for ((index, userId) in targets.withIndex()) {
            if (index > 0) kotlinx.coroutines.delay(2000)
            val newMemberMessageId = groupHopId(chatGroupId, originalSenderId, receivedMessage.date, userId)
            val forwardMessage = receivedMessage.copy(
                toUserId = userId,
                messageId = newMemberMessageId
            )

            debugLine("propagate", "Forwarding to $userId")
            sendSingleMessage(forwardMessage)

            deliveryRef.update("members.$userId", false).await()
            forwarded++
        }

        debugLine("propagate", "Forwarded to ${targets.size} members")

    } catch (e: Exception) {
        if (forwarded > 0) {
            debugLine("propagate", "Error after forwarding to $forwarded member(s): ${e.message}. Not retrying.")
            return
        }
        throw e
    }
}

/**
 * Retry for a relay that could not read the delivery document.
 *
 * Twin of [submitGroupSendWorker], with one addition: a CONNECTED constraint, so
 * it sleeps until the phone actually has a network instead of spending its
 * attempts against a radio that is still down. The unique name is per content
 * and the policy is KEEP, so a second failure for the same album cannot stack a
 * second worker on top of the first.
 */
fun submitGroupPropagateWorker(message: MessageData, context: Context) {
    val messageDataJson = Json.encodeToString(message)
    val inputData = workDataOf("messageDataJson" to messageDataJson)

    val workRequest = OneTimeWorkRequestBuilder<GroupPropagateWorker>()
        .setInputData(inputData)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setInitialDelay(15, TimeUnit.SECONDS)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        )
        .build()

    val uniqueWorkName = "groupPropagate_${message.chatGroupId}_${message.originalSenderId}_${message.date}"

    WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.KEEP,
        workRequest
    )

    debugLine("submitGroupPropagateWorker", "Scheduled relay retry for $uniqueWorkName")
}

fun computeDeliveryDocId(chatGroupId: String, originalSenderId: String, date: Long): String {
    return chatGroupId.removePrefix("group") + originalSenderId + date.toString()
}

/**
 * Registers this device in the delivery doc as holding a COMPLETE copy of the
 * content. This is the honest counterpart of the "members" map, which marks a
 * member unavailable at dispatch ATTEMPT time and can therefore lie (14 Aug:
 * White reserved by a transfer that died at 25/429 and never offered help
 * again). The complete map is only ever written by the member itself, only
 * after saveMessage succeeded, so recovery can trust it: anyone in it can serve
 * the content right now. Older versions ignore the field entirely.
 *
 * The doc disappears once every member confirmed, and an update on a missing
 * doc fails: that failure means nobody needs a seeder any more, so it is logged
 * at whisper level and swallowed.
 */
fun markContentComplete(deliveryDocId: String?) {
    if (deliveryDocId.isNullOrEmpty()) return
    val myId = MySelf.userId() ?: return
    CoroutineScope(Dispatchers.IO).launch {
        try {
            Firebase.firestore.collection("groupDelivery").document(deliveryDocId)
                .update("complete.$myId", true).await()
            debugLine("groupDelivery", "Marked myself complete in $deliveryDocId")
        } catch (e: Exception) {
            debugLine("groupDelivery", "Complete mark skipped for $deliveryDocId (doc gone or offline): ${e.message}")
        }
    }
}
