package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.contactAcquisition.autoAcceptRequestDocument
import com.bolimot.mindtheclub.contactAcquisition.clearAcquisitionStatus
import com.bolimot.mindtheclub.contactAcquisition.getAcquisitionStatus
import com.bolimot.mindtheclub.contactAcquisition.isAutoInviteEnabled
import com.bolimot.mindtheclub.contactAcquisition.setAcquisitionStatus
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.desktop.media.Albums
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.IncomingPendingTracker
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.RecoveryProgress
import com.bolimot.mindtheclub.functions.batchTablesExists
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.stringToListInt
import com.bolimot.mindtheclub.sending.reSendMessage
import com.bolimot.mindtheclub.sending.restrictBatchToMissing
import com.bolimot.mindtheclub.sending.sendMessageId
import com.bolimot.mindtheclub.works.ContactRequestRetryWorker
import com.bolimot.mindtheclub.works.DispatchWorker
import com.bolimot.mindtheclub.works.submitDispatchWorker
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.functions.saveNewGroupAsPeer
import com.bolimot.mindtheclub.functions.ContentServeQueue
import com.bolimot.mindtheclub.functions.GroupSeenTracker
import com.bolimot.mindtheclub.functions.PeerProbe
import com.bolimot.mindtheclub.functions.batchContentCoordinates
import com.bolimot.mindtheclub.functions.groupHopId
import com.bolimot.mindtheclub.functions.pickRecoverySource
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.handleGroupDeliveryConfirmation
import com.bolimot.mindtheclub.sending.promoteGroupAggregate
import com.bolimot.mindtheclub.sending.sendSingleMessage
import com.bolimot.mindtheclub.works.contentTag
import com.bolimot.mindtheclub.works.submitSendMessageWorker
import android.content.Context
import androidx.work.WorkManager
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.receiving.checkIfMessageIsCompleted
import com.bolimot.mindtheclub.receiving.missingChunksByContent
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.AcquisitionStatus
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.ProfileType
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What the PC does with an incoming signal: the counterpart of onMessageReceived and
 * handleMessage in the App's firebase/myFirebaseMessagingService.kt.
 *
 * That file is 1400 lines in which the protocol is interleaved with Android (services, wake
 * locks, broadcasts, notifications, calls), so it cannot be copied. Each case below is the
 * App's case for the same signal, with its checks in the same order, and names the lines it
 * comes from. The cases present are the ones RECEIVING a 1:1 message needs:
 *
 * RECEIVING
 *   dataCall        a peer wants to send: open the connection, unless the content is already here
 *   pending         a peer announces content: answer allReceived if it is here, else one sendMe
 *   completed       a peer says it has sent everything: process, or say what is missing
 *
 * SENDING
 *   allReceived     the peer has our message: mark it delivered, drop its batch tables; for
 *                   group content also the delivery document (handleGroupDeliveryConfirmation)
 *   sendMe          the peer asks for a message we announced, whole or a range of chunks
 *   allMissing      nothing of our message arrived: send it all again
 *   someMissing     a range of chunks did not arrive: send those again
 *   Seen            the peer read our message
 *   connectionBusy  the peer refused our dataCall for now
 *
 * CONTACTS
 *   contactRequest  someone asked to become a contact: accept the request in our inbox
 *   requestProfile  a contact asks for our profile: send it
 *
 * GROUPS
 *   group           we were added to a group: it joins the contact list
 *   groupRemoved    we were taken out of a group: it leaves, with its messages
 *   groupPending    a member needs a group message we hold: send it
 *   groupSeen       a member read a group message we wrote: record it, acknowledge
 *   groupSeenAck    the writer recorded our read receipt: stop repeating it
 *   ping / pong     a sender choosing whom to give a heavy content asks if we are awake
 *
 * Everything else (calls, typing) is recorded in the log and otherwise ignored.
 */
object SignalHandler {

    private const val TAG = "SignalHandler"

    /** mtcx MyFirebaseMessagingService.CLOCK_GRACE_MS. */
    private const val CLOCK_GRACE_MS = 30_000L

    /** Called for every signal once its sender is known, before any check. For display only. */
    @Volatile var onSignal: ((type: String, fromUserId: String) -> Unit)? = null

    /**
     * [raw] is the map a phone gets from FCM or UnifiedPush: toUserId in clear, the rest sealed
     * in "payload" when "enc" is "1".
     */
    fun onPush(raw: Map<String, String>) {
        if (isExpired(raw)) return

        val data = openPayload(raw)

        val remoteUserId = data["fromUserId"] ?: return
        val content = data["content"]
        val type = data["type"]

        if (remoteUserId == MySelf.userId()) {
            debugLine(TAG, "Ignoring signal from self, breaking potential loop")
            return
        }

        debugLine(TAG, "Signal received, Type: $type")
        try {
            if (type != null) onSignal?.invoke(type, remoteUserId)
        } catch (e: Exception) {
            // Display only.
        }

        App.instance!!.applicationScope.launch {
            try {
                if (data["toUserId"] != MySelf.userId()) return@launch
                if (remoteUserId.isEmpty()) return@launch

                if (getBlockedUserRepository(App.context()).isBlocked(remoteUserId)) {
                    debugLine(TAG, "Ignoring signal from blocked user: $remoteUserId")
                    return@launch
                }

                // CONTACT_REQUEST comes from an unknown peer by definition. Safe: the handler
                // only acts on a request document in OUR own inbox, with fingerprint check. A
                // spoofed signal with no matching request is a no-op.
                if (type != Notify.CONTACT_REQUEST && !isRecognisedPeer(remoteUserId)) {
                    debugLine(TAG, "Ignoring signal from unrecognised peer: $remoteUserId")
                    return@launch
                }

                if (type == null) {
                    debugLine(TAG, "Uh-oh: Type is NULL, ignoring message")
                    return@launch
                }
                handleMessage(remoteUserId, content, type, data["contentKey"])
            } catch (ex: Exception) {
                debugLine(TAG, "Exception on onPush: ${ex.message}")
            }
        }
    }

    /**
     * myFirebaseMessagingService.kt, isRecognisedPeer: a contact, a peer whose contact
     * acquisition is in progress, or a member of one of our groups (read from groups/<id>
     * through the Firestore stand-in). As in the App, a failed group read lets the signal in.
     */
    private suspend fun isRecognisedPeer(userId: String): Boolean {
        val applicationContext = App.context()
        val peerDao = getPeerDao(applicationContext)
        val tag = "isRecognisedPeer"

        if (peerDao.exist(userId)) return true

        if (getAcquisitionStatus(userId, Location.LOCAL, ProfileType.LOCAL, applicationContext) != null ||
            getAcquisitionStatus(userId, Location.LOCAL, ProfileType.REMOTE, applicationContext) != null ||
            getAcquisitionStatus(userId, Location.REMOTE, ProfileType.LOCAL, applicationContext) != null ||
            getAcquisitionStatus(userId, Location.REMOTE, ProfileType.REMOTE, applicationContext) != null
        ) return true

        try {
            val db = Firebase.firestore
            for (group in peerDao.getGroupPeers()) {
                val doc = db.collection("groups").document(group.userId).get().await()
                if (doc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val members = doc.get("members") as? Map<String, Any>
                    if (members != null && members.containsKey(userId)) return true
                }
            }
        } catch (e: Exception) {
            debugLine(tag, "isRecognisedPeer group check failed: ${e.message}")
            return true
        }

        return false
    }

    /**
     * myFirebaseMessagingService.originalIdOf: identity of the original group message for a
     * relayed copy. Receivers file under groupId.ifEmpty { messageId }, so a relay leaving groupId
     * empty would scatter copies under fresh keys. One to one keeps groupId as is.
     */
    private fun originalIdOf(message: Message): String =
        if (message.chatGroupId.isNullOrEmpty()) message.groupId
        else message.groupId.ifEmpty { message.messageId }

    /** MyFirebaseMessagingService.openPayload. */
    private fun openPayload(raw: Map<String, String>): Map<String, String> {
        if (raw["enc"] != "1") return raw

        val sealed = raw["payload"] ?: return raw
        val plain = KeyManager.decrypt(sealed)
        if (plain == null) {
            debugLine(TAG, "openPayload: decrypt failed, dropping content fields")
            return raw
        }

        return try {
            val merged = HashMap<String, String>(raw)
            for ((key, value) in Json.parseToJsonElement(plain).jsonObject) {
                val text = (value as? JsonPrimitive)?.contentOrNull ?: continue
                merged[key] = text
            }
            merged
        } catch (e: Exception) {
            debugLine(TAG, "openPayload: bad JSON: ${e.message}")
            raw
        }
    }

    /**
     * mtcx MyFirebaseMessagingService.isExpired. FCM drops an expired message itself; a
     * UnifiedPush distributor does not have to, so sendUnifiedPush stamps when it sent the
     * message and how long it is worth. Fails open: without the stamps the message is processed.
     */
    private fun isExpired(raw: Map<String, String>): Boolean {
        val sentAt = raw["upSentAt"]?.toLongOrNull() ?: return false
        val ttlSeconds = raw["upTtl"]?.toLongOrNull() ?: return false
        val age = System.currentTimeMillis() - sentAt
        if (age <= ttlSeconds * 1000 + CLOCK_GRACE_MS) return false
        debugLine(TAG, "Dropping expired push: ${age / 1000}s old, ttl ${ttlSeconds}s")
        return true
    }

    private suspend fun handleMessage(fromUserId: String, channelId: String?, type: String, incomingContentKey: String?) {
        val context = App.context()

        when (type) {

            // myFirebaseMessagingService.kt, Notify.DATA_CALL.
            Notify.DATA_CALL -> {
                debugLine(TAG, "Someone is trying to wake me up for DATA")
                val cid = channelId ?: return
                val ck = incomingContentKey ?: ""

                if (ck.isNotEmpty()) {
                    try {
                        val firstRow = getInboxDao(context).getFirstByContent(ck)
                        val anchorMessageId = firstRow?.groupId?.takeIf { it.isNotEmpty() } ?: firstRow?.messageId
                        val existingMsg = anchorMessageId?.let { getMessageDao(context).getMessage(it) }
                        if (anchorMessageId != null && existingMsg != null && existingMsg.status != Status.RECEIVING) {
                            debugLine(TAG, "DATA_CALL gate: content $ck already complete, replying allReceived to $fromUserId")
                            notifyRemotePeer(fromUserId, anchorMessageId, Notify.ALL_RECEIVED)
                            return
                        }
                        // DESKTOP: the App then caps concurrent senders per content at three
                        // (ConnectionManager.tryAdmitSender, answering connectionBusy), and stops
                        // the extra ones early when the content completes (stopRedundantSenders).
                        // Not on the PC: a second relayer of one content is let in and finishes
                        // its transfer, wasted traffic but nothing lost.
                    } catch (e: Exception) {
                        debugLine(TAG, "DATA_CALL gate error: ${e.message}")
                    }
                }
                DataLink.accept(cid, fromUserId)
            }

            // myFirebaseMessagingService.kt, Notify.PENDING.
            Notify.PENDING -> {
                debugLine(TAG, "Received pending message: $channelId")
                val value = channelId ?: return

                val parts = value.split("#", limit = 2)
                val msgId = parts[0]
                val chatGroupId = parts.getOrNull(1)

                // An announcement for a group this device no longer has: the peer row is the
                // local authoritative fact (see the App's file for the 923 chunk video pulled
                // into a deleted chat).
                if (!chatGroupId.isNullOrEmpty() &&
                    !getPeerDao(context).exist(chatGroupId)
                ) {
                    debugLine(TAG, "Pending for group $chatGroupId which is not on this device, ignoring")
                    return
                }

                if (CancelledTransferRegistry.isCancelled(context, msgId)) {
                    debugLine(TAG, "Transfer $msgId was cancelled, ignoring pending")
                    return
                }

                // Already here: answering allReceived is how the announcer learns it can drop
                // its own pending entry, so both halves of the loop stop together.
                val directMessage = getMessageRepository(context).getMessage(msgId)
                val existingMessage = directMessage ?: run {
                    val firstRow = getInboxDao(context).getFirstMessage(msgId)
                    val anchorId = firstRow?.groupId?.takeIf { it.isNotEmpty() }
                    anchorId?.let { getMessageRepository(context).getMessage(it) }
                }
                if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
                    debugLine(TAG, "Message $msgId already received, sending allReceived to $fromUserId")
                    RecoveryProgress.clear(context, msgId)
                    val deliveryId = existingMessage.chatGroupId?.let {
                        computeDeliveryDocId(it, existingMessage.originalSenderId ?: "", existingMessage.date)
                    }
                    notifyRemotePeer(fromUserId, msgId, Notify.ALL_RECEIVED, deliveryId)
                    return
                }

                // Resume aware sendMe: say which chunks are missing so the sender re-sends only
                // those. No local chunks means a plain sendMe, a full send.
                val pendingContentKey = if (existingMessage != null) {
                    contentKeyOf(msgId, existingMessage.chatGroupId, existingMessage.originalSenderId, existingMessage.date)
                } else {
                    resolveContentKey(getInboxDao(context), msgId)
                }
                val missing = missingChunksByContent(getInboxDao(context), pendingContentKey)
                if (missing == null) {
                    // Everything is here already. On the phone assembly finishes the job; on the
                    // PC this is also where a content it cannot process yet comes to rest.
                    debugLine(TAG, "All chunks already present for $msgId, skipping sendMe")
                    RecoveryProgress.clear(context, msgId)
                    return
                }
                val missingRange = if (missing.isNotEmpty()) "${missing.min()},${missing.max()}" else null
                if (missingRange != null) {
                    debugLine(TAG, "Partial state for $msgId: requesting chunks $missingRange (${missing.size} missing)")
                }

                // Answer only if the previous answers achieved something (RecoveryProgress).
                val heldChunks = getInboxDao(context).countChunksByContent(pendingContentKey)
                if (!RecoveryProgress.shouldAsk(context, msgId, heldChunks)) {
                    return
                }

                // Remember this message is owed to us, so PendingRetryWorker can ask again.
                IncomingPendingTracker.record(context, msgId, fromUserId)

                if (!chatGroupId.isNullOrEmpty()) {
                    debugLine(TAG, "Group pending for $chatGroupId, fanning out sendMe")
                    try {
                        val db = Firebase.firestore
                        val groupDoc = db.collection("groups")
                            .document(chatGroupId)
                            .get()
                            .await()

                        if (groupDoc.exists()) {
                            @Suppress("UNCHECKED_CAST")
                            val membersMap = groupDoc.get("members") as? Map<String, Any>
                            val myId = MySelf.userId()
                            val targets = membersMap?.keys?.filter { key -> key != myId }?.sorted() ?: emptyList()

                            if (targets.isEmpty()) {
                                notifyRemotePeer(fromUserId, msgId, Notify.SEND_ME, missingRange)
                            } else {
                                // ONE member per round. Round 0 asks whoever announced the
                                // pending; later rounds ask a member with a REGISTERED complete
                                // copy, falling back to the announcer (see the App's file).
                                val rotationPrefs = context.getSharedPreferences("SendMeRotation", Context.MODE_PRIVATE)
                                val round = rotationPrefs.getInt(msgId, 0)
                                val target = if (round == 0 && fromUserId in targets) fromUserId
                                             else pickRecoverySource(
                                                 existingMessage?.chatGroupId ?: chatGroupId,
                                                 existingMessage?.originalSenderId,
                                                 existingMessage?.date ?: 0L,
                                                 fallback = fromUserId
                                             )
                                rotationPrefs.edit().putInt(msgId, round + 1).apply()
                                notifyRemotePeer(target, msgId, Notify.SEND_ME, missingRange)
                                debugLine(TAG, "Sent sendMe round $round to $target for $msgId (informed recovery)")
                            }
                        } else {
                            debugLine(TAG, "Group $chatGroupId not found, falling back to sender")
                            notifyRemotePeer(fromUserId, msgId, Notify.SEND_ME, missingRange)
                        }
                    } catch (e: Exception) {
                        debugLine(TAG, "Failed to fan out sendMe, falling back to sender: ${e.message}")
                        notifyRemotePeer(fromUserId, msgId, Notify.SEND_ME, missingRange)
                    }
                } else {
                    debugLine(TAG, "1:1 pending, sending sendMe to $fromUserId")
                    notifyRemotePeer(fromUserId, msgId, Notify.SEND_ME, missingRange)
                }
            }

            // myFirebaseMessagingService.kt, Notify.COMPLETED.
            Notify.COMPLETED -> {
                debugLine(TAG, "This message has been sent to me: $channelId")
                val messageId = channelId ?: return

                if (CancelledTransferRegistry.isCancelled(context, messageId)) {
                    debugLine(TAG, "Transfer $messageId was cancelled, ignoring completed")
                    return
                }
                val messageStatus = checkIfMessageIsCompleted(messageId)
                when {
                    messageStatus == null -> {
                        debugLine(TAG, "All chunks received, requesting inbox processing")
                        MessageProcessor.requestInboxCheck(messageId)
                    }

                    messageStatus.isEmpty() -> {
                        debugLine(TAG, "No chunks received")
                        notifyRemotePeer(fromUserId, messageId, Notify.ALL_MISSING)
                    }

                    else -> {
                        val missingRange = "${messageStatus.min()},${messageStatus.max()}"
                        debugLine(TAG, "Missing chunks range: $missingRange (${messageStatus.size} items actually missing)")
                        notifyRemotePeer(fromUserId, messageId, Notify.SOME_MISSING, missingRange)
                    }
                }
            }

            // ------------------------------------------------ the sending side

            // myFirebaseMessagingService.kt, Notify.ALL_RECEIVED. The receiver has the whole
            // message: this, and nothing else, is what "delivered" means.
            Notify.ALL_RECEIVED -> {
                debugLine(TAG, "My message has been received: $channelId")
                val rawChannelId = channelId ?: return
                val parts = rawChannelId.split("#", limit = 2)
                val messageId = parts[0]
                val deliveryDocId = parts.getOrNull(1)

                // For a GROUP message the bubble is an aggregate: Delivered only when EVERY
                // member has it (promoteGroupAggregate). One to one stays immediate.
                val delivered = DesktopStrings.delivered()
                val ackedMessage = getMessageRepository(context).getMessage(messageId)
                if (ackedMessage?.chatGroupId.isNullOrEmpty()) {
                    getMessageRepository(context).updateStatus(messageId, delivered)
                }

                deleteBatchTables(messageId)

                // DESKTOP: the one file an album travelled as (Albums.merge) is not needed any
                // more. The App leaves it in its cache folder, which Android empties; the PC has
                // no such folder, so it goes now.
                if (ackedMessage?.type == Type.MULTIPLE_IMAGES) Albums.mergedFile(messageId).delete()

                // Our profile has arrived: the acquisition of this contact moves on, and the
                // contact is complete once its own profile is here too.
                if (ackedMessage?.type == Type.PROFILE) {
                    setAcquisitionStatus(fromUserId, Location.LOCAL, ProfileType.LOCAL, AcquisitionStatus.SENT, context)
                    debugLine("receiveProfile", "Set LOCALLY My profile sent")

                    setAcquisitionStatus(fromUserId, Location.REMOTE, ProfileType.LOCAL, AcquisitionStatus.RECEIVED, context)
                    debugLine("receiveProfile", "Set REMOTELLY My profile received")

                    val myProfileStat = getAcquisitionStatus(fromUserId, Location.LOCAL, ProfileType.REMOTE, context)
                    debugLine("receiveProfile", "Getting My profileStat LOCALLY/Remote Profile status: $myProfileStat")

                    if (myProfileStat == AcquisitionStatus.RECEIVED) {
                        debugLine("receiveProfile", "Already received remote profile, set ACTIVE")
                        getPeerDao(context).updatePeerStatus(fromUserId, Contact.ACTIVE)
                    }
                }

                if (deliveryDocId != null && deliveryDocId.startsWith("relay:")) {
                    val confirmedMember = deliveryDocId.removePrefix("relay:")
                    val statusDao = DatabaseProvider.provideDatabase().groupMessageStatusDao()
                    val currentMemberStatus = statusDao.getStatusForMember(messageId, confirmedMember)
                    if (currentMemberStatus != Notify.SEEN) {
                        statusDao.updateStatus(messageId, confirmedMember, delivered)
                        debugLine(TAG, "Updated GroupMessageStatus (relayed): $confirmedMember → Delivered for $messageId")
                    } else {
                        debugLine(TAG, "Skipped GroupMessageStatus (relayed) for $confirmedMember (already Seen) for $messageId")
                    }
                    promoteGroupAggregate(messageId)
                } else if (deliveryDocId != null && deliveryDocId.startsWith("refused:")) {
                    // The member refused our transfer: it drops out of the member map so nobody
                    // relays to it again, without counting towards the fanout.
                    debugLine(TAG, "$fromUserId refused $messageId, dropping it from the recipients")
                    handleGroupDeliveryConfirmation(
                        deliveryDocId.removePrefix("refused:"),
                        fromUserId,
                        countsTowardFanout = false
                    )
                } else {
                    handleGroupDeliveryConfirmation(deliveryDocId, fromUserId)
                }

                PendingMessageTracker.remove(context, messageId, fromUserId)

                // Same acknowledgement, matched by CONTENT as well as by id: a peer answering
                // the DATA_CALL gate quotes the ORIGINAL id while our entry is filed under our
                // own relay id (see the App's file, 20 Aug). Group content only.
                val acked = ackedMessage
                val ackedGroupId = acked?.chatGroupId
                if (acked != null && !ackedGroupId.isNullOrEmpty()) {
                    for (entry in PendingMessageTracker.getAll(context)) {
                        if (entry.messageId == messageId) continue
                        if (entry.toUserId != fromUserId) continue
                        if (entry.chatGroupId != ackedGroupId) continue
                        if (entry.originalSenderId != acked.originalSenderId) continue
                        if (entry.messageDate != acked.date) continue

                        debugLine(TAG, "Clearing pending ${entry.messageId} → $fromUserId: same content acked as $messageId")
                        PendingMessageTracker.remove(context, entry.messageId, entry.toUserId)
                        deleteBatchTables(entry.messageId)
                    }
                }

                // Piggyback: the peer is demonstrably awake, so the oldest message still owed
                // to it goes out now instead of waiting for its rung on the ladder.
                val stalePending = PendingMessageTracker.getPendingForPeer(context, fromUserId)
                val nextPending = stalePending.minByOrNull { it.createdAt }
                if (nextPending != null) {
                    debugLine(TAG, "Piggyback: ${stalePending.size} stale for $fromUserId, dispatching the oldest")
                    submitDispatchWorker(
                        messageId = nextPending.messageId,
                        toUserId = fromUserId,
                        context = context,
                        groupId = nextPending.messageKey.substringBefore("#"),
                        chatGroupId = nextPending.chatGroupId ?: "",
                        originalSenderId = nextPending.originalSenderId ?: "",
                        messageDate = nextPending.messageDate
                    )
                }
            }

            // myFirebaseMessagingService.kt, Notify.SEND_ME. A peer asks for a message we
            // announced, optionally for a range of chunks only ("<messageId>#<low>,<high>").
            Notify.SEND_ME -> {
                debugLine(TAG, "I am sending a message: $channelId to requester $fromUserId")
                // The requester is demonstrably awake: a cooldown armed moments ago by a failed
                // attempt must not defer the answer.
                DispatchWorker.markPeerAlive(context, fromUserId)
                val payload = channelId ?: return

                val sendMeParts = payload.split("#", limit = 2)
                val msgId = sendMeParts[0]
                val missingItems: List<Int>? = sendMeParts.getOrNull(1)?.let { range ->
                    try {
                        val (low, high) = range.split(",").map { it.trim().toInt() }
                        if (low in 1..high) (low..high).toList() else null
                    } catch (e: Exception) {
                        debugLine(TAG, "Ignoring malformed sendMe range '$range'")
                        null
                    }
                }

                if (CancelledTransferRegistry.isCancelled(context, msgId)) {
                    debugLine(TAG, "Transfer $msgId was cancelled, ignoring sendMe")
                    return
                }
                if (batchTablesExists(msgId)) {
                    // Same content guard: work is tagged by CONTENT, because group gossip mints a
                    // new messageId at every hop (14 Aug: one photo went out four times).
                    val coords = batchContentCoordinates(msgId)
                    if (coords != null) {
                        val contentTag = contentTag(
                            messageId = msgId,
                            toUserId = fromUserId,
                            chatGroupId = coords.chatGroupId,
                            originalSenderId = coords.originalSenderId,
                            messageDate = coords.messageDate
                        )
                        // RUNNING only: an ENQUEUED retry in backoff does nothing for this peer.
                        val active = try {
                            WorkManager.getInstance(context)
                                .getWorkInfosByTag(contentTag).await()
                                .any { it.state == androidx.work.WorkInfo.State.RUNNING }
                        } catch (e: Exception) {
                            debugLine(TAG, "WorkManager query failed, proceeding: ${e.message}")
                            false
                        }
                        if (active) {
                            debugLine(TAG, "Skip sendMe for $msgId: same content already being dispatched to $fromUserId")
                            return
                        }

                        // One transfer per content at a time; a second requester is queued and
                        // invited back with a pending when the current transfer ends.
                        val serveContentId = ContentServeQueue.contentIdOf(
                            coords.chatGroupId, coords.originalSenderId, coords.messageDate
                        )
                        if (serveContentId != null &&
                            !ContentServeQueue.admit(serveContentId, fromUserId, "$msgId#${coords.chatGroupId}")
                        ) {
                            debugLine(TAG, "ServeQueue;Busy serving ${ContentServeQueue.servingTargetOf(serveContentId)} for $serveContentId, queued $fromUserId")
                            return
                        }
                    }

                    if (missingItems != null) {
                        debugLine(TAG, "Partial sendMe: re-sending chunks ${missingItems.first()}..${missingItems.last()} of $msgId to $fromUserId")
                        // Normalise first: mark all delivered, then re-enable only the missing
                        // range, or dispatch re-streams the whole remainder as duplicates.
                        restrictBatchToMissing(msgId, missingItems, context)
                        reSendMessage(fromUserId, msgId, missingItems, context)
                    } else {
                        debugLine(TAG, "Batch tables exist for $msgId, dispatching to $fromUserId")
                        // Coordinates passed so the worker is tagged by content, which is what
                        // the guard above matches on.
                        submitDispatchWorker(
                            msgId, fromUserId, context,
                            groupId = coords?.groupId ?: "",
                            chatGroupId = coords?.chatGroupId ?: "",
                            originalSenderId = coords?.originalSenderId ?: "",
                            messageDate = coords?.messageDate ?: 0L
                        )
                    }
                } else {
                    val message = getMessageRepository(context).getMessage(msgId)
                    if (message != null) {
                        val isReceivedMessage = message.toUserId == MySelf.userId()
                        val isGroupMessage = !message.chatGroupId.isNullOrEmpty()

                        if (message.status == Status.RECEIVING) {
                            debugLine(TAG, "Message $msgId still RECEIVING, cannot relay incomplete message")
                            return
                        }
                        if (isReceivedMessage || isGroupMessage) {
                            val logicalTag = contentTag(
                                messageId = message.messageId,
                                toUserId = fromUserId,
                                chatGroupId = message.chatGroupId ?: "",
                                originalSenderId = message.originalSenderId ?: "",
                                messageDate = message.date
                            )
                            val existing = try {
                                WorkManager.getInstance(context)
                                    .getWorkInfosByTag(logicalTag).await()
                            } catch (e: Exception) {
                                debugLine(TAG, "WorkManager query failed, proceeding: ${e.message}")
                                emptyList()
                            }
                            // RUNNING only, as above.
                            val hasActive = existing.any { it.state == androidx.work.WorkInfo.State.RUNNING }
                            if (hasActive) {
                                debugLine(TAG, "Skip sendMe for $msgId: dispatch already in flight to $fromUserId (same content)")
                                return
                            }

                            // Same serving discipline as the batch path.
                            val serveContentId = ContentServeQueue.contentIdOf(
                                message.chatGroupId, message.originalSenderId, message.date
                            )
                            if (serveContentId != null &&
                                !ContentServeQueue.admit(serveContentId, fromUserId, "$msgId#${message.chatGroupId}")
                            ) {
                                debugLine(TAG, "ServeQueue;Busy serving ${ContentServeQueue.servingTargetOf(serveContentId)} for $serveContentId, queued $fromUserId")
                                return
                            }

                            // Group content is served under the SAME id the push path uses
                            // towards this same peer (groupHopId), so the two routes share one
                            // set of tables and one unique work (see the App's file, 20 Aug).
                            val canDeriveHopId = !message.chatGroupId.isNullOrEmpty()
                                    && !message.originalSenderId.isNullOrEmpty()
                                    && message.date > 0L

                            val serveMessageId = if (canDeriveHopId) {
                                groupHopId(
                                    message.chatGroupId,
                                    message.originalSenderId,
                                    message.date,
                                    fromUserId
                                )
                            } else {
                                message.messageId
                            }

                            debugLine(TAG, "Re-sending received/group message $msgId to requester $fromUserId as $serveMessageId")
                            submitSendMessageWorker(
                                MessageData.fromMessage(message).copy(
                                    toUserId = fromUserId,
                                    messageId = serveMessageId,
                                    groupId = originalIdOf(message)
                                ),
                                context,
                                resendMissing = missingItems
                            )
                        } else {
                            // Our own 1:1 message with no batch tables left: rebuilt from the
                            // saved message and sent again.
                            sendMessageId(msgId)
                        }
                    } else {
                        debugLine(TAG, "Cannot resend $msgId: no message in DB and no batch tables")
                    }
                }
            }

            // myFirebaseMessagingService.kt, Notify.ALL_MISSING.
            Notify.ALL_MISSING -> {
                debugLine(TAG, "My message has NOT been received: $channelId")
                val messageId = channelId ?: return
                reSendMessage(fromUserId, messageId, emptyList(), context)
            }

            // myFirebaseMessagingService.kt, Notify.SOME_MISSING: "<messageId>#<low>,<high>".
            Notify.SOME_MISSING -> {
                debugLine(TAG, "My message has NOT been fully received: $channelId")
                val value = channelId ?: return
                try {
                    val parts = value.split("#")
                    val messageId = parts[0]
                    val rangeItems = stringToListInt(parts[1])
                    val low = rangeItems.first()
                    val high = rangeItems.last()
                    val missingItems = (low..high).toList()
                    debugLine(TAG, "Re-sending chunk range $low..$high (${missingItems.size} chunks)")

                    reSendMessage(fromUserId, messageId, missingItems, context)
                } catch (ex: Exception) {
                    debugLine(TAG, "Exception on someMissing reception: ${ex.message}")
                }
            }

            // myFirebaseMessagingService.kt, Notify.SEEN and Notify.RECEIVED_SEEN.
            Notify.SEEN -> {
                debugLine(TAG, "My message has been seen: $channelId")
                val messageId = channelId ?: return
                getMessageRepository(context).updateStatus(messageId, Notify.SEEN)
                notifyRemotePeer(fromUserId, messageId, Notify.RECEIVED_SEEN)
            }

            Notify.RECEIVED_SEEN -> {
                debugLine(TAG, "My message has been received and seen: $channelId")
                val messageId = channelId ?: return
                getMessageRepository(context).updateStatus(messageId, Notify.SEEN)
            }

            // myFirebaseMessagingService.kt, Notify.GROUP_SEEN: a member read a group message
            // this identity wrote. Recorded, acknowledged, and the bubble turns Seen when all
            // members have read it.
            Notify.GROUP_SEEN -> {
                debugLine(TAG, "Group message seen by $fromUserId: $channelId")
                val messageId = channelId ?: return
                val statusDao = DatabaseProvider.provideDatabase().groupMessageStatusDao()
                statusDao.updateStatus(messageId, fromUserId, Notify.SEEN)
                debugLine(TAG, "Updated GroupMessageStatus: $fromUserId → Seen for $messageId")

                // Ack AFTER the status write, so it means "processed" and the member can stop
                // its retry ladder.
                notifyRemotePeer(fromUserId, messageId, Notify.GROUP_SEEN_ACK)

                val allStatuses = statusDao.getStatusesForMessage(messageId)
                val allSeen = allStatuses.isNotEmpty() && allStatuses.all { it.status == Notify.SEEN }

                if (allSeen) {
                    val repo = getMessageRepository(context)
                    val currentMessage = repo.getMessage(messageId)

                    if (currentMessage != null && currentMessage.status != Notify.SEEN) {
                        repo.updateStatus(messageId, Notify.SEEN)
                        debugLine(TAG, "Group message $messageId updated to Seen (all members seen)")
                    }
                }
            }

            // myFirebaseMessagingService.kt, Notify.GROUP_SEEN_ACK: the writer processed our
            // read receipt, stop the ladder.
            Notify.GROUP_SEEN_ACK -> {
                debugLine(TAG, "Group seen acked by $fromUserId: $channelId")
                val messageId = channelId ?: return
                GroupSeenTracker.remove(context, messageId, fromUserId, "acked")
            }

            // myFirebaseMessagingService.kt, Notify.PING and Notify.PONG: a sender choosing whom
            // to give a heavy group content asks who is awake (PeerProbe).
            Notify.PING -> {
                debugLine(TAG, "Ping from $fromUserId, answering pong")
                fcmSendInstant(fromUserId, "pong", "NotACall", Notify.PONG, Notify.PONG)
            }

            Notify.PONG -> {
                debugLine(TAG, "Pong from $fromUserId")
                PeerProbe.onPong(fromUserId)
                DispatchWorker.markPeerAlive(context, fromUserId)
            }

            // The peer refused our dataCall: too many senders for that content right now.
            Notify.CONNECTION_BUSY -> {
                debugLine(TAG, "Remote peer is busy for DATA_CALL, aborting connect attempt")
                val cid = channelId ?: return
                DataLink.peerBusy(fromUserId, cid)
            }

            // myFirebaseMessagingService.kt, Notify.REQUEST_PROFILE.
            Notify.REQUEST_PROFILE -> {
                debugLine(TAG, "Remote peer $fromUserId is requesting my profile")
                Outgoing.sendMyProfile(fromUserId)
            }

            // myFirebaseMessagingService.kt, Notify.CONTACT_REQUEST. Someone wrote a request
            // into our inbox and says so: accept it, as the phone does with auto-invite on
            // (acceptNewContact.kt says why the PC has it always on).
            Notify.CONTACT_REQUEST -> {
                debugLine(TAG, "Contact request nudge from $fromUserId")
                try {
                    if (!isAutoInviteEnabled(context)) {
                        debugLine(TAG, "Auto-invite disabled; request will surface on next app open")
                        return
                    }
                    val myUserId = MySelf.userId() ?: return

                    val doc = withContext(Dispatchers.IO) { Backend.contactRequest(myUserId, fromUserId) }
                    if (doc == null) {
                        debugLine(TAG, "No pending request doc from $fromUserId (already processed?)")
                        return
                    }

                    autoAcceptRequestDocument(doc, context)
                } catch (e: Exception) {
                    debugLine(TAG, "Contact request handling failed: ${e.message}. Scheduling retry.")
                    ContactRequestRetryWorker.enqueue(context)
                }
            }

            // myFirebaseMessagingService.kt, Notify.GROUP: we were added to a group. It becomes
            // a peer of the contact list, and the user is told ("New Group"). DESKTOP: the
            // notification goes through DesktopEvents, as every notification on the PC.
            Notify.GROUP -> {
                val gId = channelId?.substringBefore("#")
                val groupName = channelId?.substringAfter("#", "")

                if (gId == null || groupName == null) return

                debugLine(TAG, "GroupId = $gId, groupName = $groupName")
                App.instance!!.applicationScope.launch { saveNewGroupAsPeer(gId, groupName) }

                val message = Message(
                    uid = 0,
                    fromUserId = fromUserId,
                    toUserId = MySelf.userId()!!,
                    messageId = gId,
                    replyId = "",
                    groupId = "",
                    groupSize = 0,
                    text = groupName,
                    textAttached = "",
                    nameAttached = "",
                    uri = "",
                    type = Type.GROUP,
                    subType = "",
                    date = 0,
                    status = "",
                    reaction = ""
                )

                DesktopEvents.notifyMessage(message)
            }

            // myFirebaseMessagingService.kt, Notify.GROUP_PENDING: a member needs a group
            // message: "<chatGroupId>#<originalSenderId>#<date>#<targetUserId>".
            Notify.GROUP_PENDING -> {
                debugLine(TAG, "Group member needs a message: $channelId")
                val payload = channelId ?: return
                try {
                    val parts = payload.split("#")
                    if (parts.size < 4) {
                        debugLine(TAG, "Invalid groupPending payload: $payload")
                        return
                    }
                    val chatGroupId = parts[0]
                    val originalSenderId = parts[1]
                    val date = parts[2].toLong()
                    val targetUserId = parts[3]

                    val repo = getMessageRepository(context)
                    if (!repo.groupMessageExists(chatGroupId, originalSenderId, date)) {
                        debugLine(TAG, "I don't have this group message, ignoring")
                        return
                    }

                    val message = repo.getGroupMessage(chatGroupId, originalSenderId, date)
                    if (message == null) {
                        debugLine(TAG, "Failed to retrieve group message")
                        return
                    }

                    debugLine(TAG, "I have the message, re-sending to $targetUserId")

                    val memberMessageId = groupHopId(chatGroupId, originalSenderId, date, targetUserId)
                    val memberMessage = MessageData.fromMessage(message).copy(
                        toUserId = targetUserId,
                        messageId = memberMessageId,
                        groupId = originalIdOf(message),
                        chatGroupId = chatGroupId,
                        originalSenderId = originalSenderId
                    )

                    sendSingleMessage(memberMessage)
                } catch (ex: Exception) {
                    debugLine(TAG, "Exception on groupPending: ${ex.message}")
                }
            }

            // myFirebaseMessagingService.kt, Notify.GROUP_REMOVED: we were taken out of a group.
            // Its messages and the group itself leave this PC. DESKTOP: the messages go with
            // their files through deleteMessagesAndFiles, the PC's deleteRemotePeerMessages.
            Notify.GROUP_REMOVED -> {
                debugLine(TAG, "I have been removed from a group: $channelId")
                val gId = channelId?.substringBefore("#") ?: return

                App.instance!!.applicationScope.launch {
                    try {
                        val messageRepository = getMessageRepository(context)
                        messageRepository.deleteMessagesAndFiles(getMessageDao(context).getMessagesByRemotePeer(gId))

                        val peerDao = getPeerDao(context)
                        peerDao.delete(peerDao.getPeer(gId) ?: return@launch)
                        clearAcquisitionStatus(gId, context)

                        debugLine(TAG, "Group $gId removed from local DB")
                    } catch (e: Exception) {
                        debugLine(TAG, "Error removing group $gId: ${e.message}")
                    }
                }
            }

            else -> debugLine(TAG, "Signal $type is not handled on the PC yet")
        }
    }
}
