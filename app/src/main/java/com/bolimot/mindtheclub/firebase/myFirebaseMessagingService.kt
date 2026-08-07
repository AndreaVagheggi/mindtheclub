package com.bolimot.mindtheclub.firebase

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.WorkManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.crypto.KeyManager
import org.json.JSONObject
import com.bolimot.mindtheclub.contactAcquisition.autoAcceptRequestDocument
import com.bolimot.mindtheclub.contactAcquisition.getAcquisitionStatus
import com.bolimot.mindtheclub.contactAcquisition.isAutoInviteEnabled
import com.bolimot.mindtheclub.contactAcquisition.setAcquisitionStatus
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.IncomingPendingTracker
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.appIsForeground
import com.bolimot.mindtheclub.functions.batchTablesExists
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getMessageViewModel
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.functions.saveNewGroupAsPeer
import com.bolimot.mindtheclub.functions.stringToListInt
import com.bolimot.mindtheclub.functions.timer
import com.bolimot.mindtheclub.notifications.MessageReceivedNotification
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.receiving.DataSyncService
import com.bolimot.mindtheclub.receiving.checkIfMessageIsCompleted
import com.bolimot.mindtheclub.receiving.missingChunksByContent
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.handleGroupDeliveryConfirmation
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.reSendMessage
import com.bolimot.mindtheclub.sending.restrictBatchToMissing
import com.bolimot.mindtheclub.sending.receiveTransferCancelled
import com.bolimot.mindtheclub.sending.sendMessageId
import com.bolimot.mindtheclub.sending.sendSingleMessage
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.AcquisitionStatus
import com.bolimot.mindtheclub.tools.Broadcast
import com.bolimot.mindtheclub.tools.CallControlEvent
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.tools.CallEventBus
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.ProfileType
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.voip.CallService
import com.bolimot.mindtheclub.voip.receiveCallEventFromPeer
import com.bolimot.mindtheclub.voip.shutdownRTC
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.works.ContactRequestRetryWorker
import com.bolimot.mindtheclub.works.DispatchWorker
import com.bolimot.mindtheclub.works.contentTag
import com.bolimot.mindtheclub.works.submitDispatchWorker
import com.bolimot.mindtheclub.works.submitSendMessageWorker
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val appScope by lazy { (applicationContext as App).applicationScope }
    private val tag = "MyFirebaseMessagingService"

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        debugLine(tag, "FCM Messages deleted!!")
    }

    private fun openPayload(raw: Map<String, String>): Map<String, String> {
        if (raw["enc"] != "1") return raw

        val sealed = raw["payload"] ?: return raw
        val plain = KeyManager.decrypt(sealed)
        if (plain == null) {
            debugLine(tag, "openPayload: decrypt failed, dropping content fields")
            return raw
        }

        return try {
            val json = JSONObject(plain)
            val merged = HashMap<String, String>(raw)
            for (key in json.keys()) {
                merged[key] = json.getString(key)
            }
            merged
        } catch (e: Exception) {
            debugLine(tag, "openPayload: bad JSON: ${e.message}")
            raw
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = openPayload(remoteMessage.data)

        val remoteUserId = data["fromUserId"]
        val content = data["content"]
        val type = data["type"]
        val callId = data["callId"]

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MindTheClub:MyWakeLockTag")

        wakeLock.acquire(10 * 1000L)

        if(remoteUserId == null) return

        if (remoteUserId == MySelf.userId()) {
            debugLine(tag, "Ignoring FCM from self, breaking potential loop")
            return
        }

        if (type == Notify.PENDING && !appIsForeground()) {
            val blockedRepo = getBlockedUserRepository(applicationContext)
            val isBlocked = kotlinx.coroutines.runBlocking(Dispatchers.IO) { blockedRepo.isBlocked(remoteUserId) }
            if (!isBlocked) {
                showIncomingDataNotification(remoteUserId)
            }
        }

        debugLine(tag, "FCM received, Type: $type, CallId: $callId")

        appScope.launch {
            try {
                if (data["toUserId"] != MySelf.userId()) return@launch
                if (remoteUserId.isEmpty()) return@launch

                if (getBlockedUserRepository(applicationContext).isBlocked(remoteUserId)) {
                    debugLine(tag, "Ignoring FCM from blocked user: $remoteUserId")
                    return@launch
                }

                // CONTACT_REQUEST is by definition from a not-yet-recognised peer.
                // Safe to let through: the handler acts only on a request document
                // present in OUR OWN Firestore, with full fingerprint verification —
                // a spoofed nudge with no matching request is a no-op.
                if (type != Notify.CONTACT_REQUEST && !isRecognisedPeer(remoteUserId)) {
                    debugLine(tag, "Ignoring FCM from unrecognised peer: $remoteUserId")
                    return@launch
                }

                val incomingContentKey = data["contentKey"]
                type?.let { handleMessage(remoteUserId, content, callId, it, incomingContentKey) } ?: run {
                    debugLine(tag, "Uh-oh: Type is NULL, ignoring message")
                }
            } catch (ex: Exception) {
                debugLine(tag, "Exception on onMessageReceived: ${ex.message}")
            } finally {
                if (wakeLock.isHeld) {
                    try {
                        wakeLock.release()
                    } catch (e: RuntimeException) {
                        debugLine(tag, "Exception on wakeLock release: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleMessage(fromUserId: String, channelId: String?, callId: String?, type: String, incomingContentKey: String? = null) {
        val myUserId = MySelf.userId()

        if(myUserId == null) {
            debugLine(tag, "MyUserId is NULL, ignoring message")
            return
        }

        when(type){
            Notify.VIDEO_CALL -> {
                appScope.launch {
                    debugLine(tag, "Received VIDEO_CALL signal, callId=$callId, channelId=$channelId")

                    if(callId == null) {
                        debugLine(tag, "CallId is NULL, cannot accept the video call")
                        return@launch
                    }

                    if(channelId == null) {
                        debugLine(tag, "CallId is NULL, cannot accept the video call")
                        return@launch
                    }

                    val terminateOnEnd = !appIsForeground()
                    debugLine(tag, "VIDEO_CALL notify received, terminateOnEnd=$terminateOnEnd")

                    val intent = Intent(applicationContext, CallService::class.java).apply {
                        action = CallService.ACTION_INCOMING_CALL
                        putExtra(CallService.EXTRA_REMOTE_USER_ID, fromUserId)
                        putExtra(CallService.EXTRA_CALL_ID, callId)
                        putExtra(CallService.EXTRA_CHANNEL_ID, channelId)
                        putExtra(CallService.EXTRA_IS_VIDEO, true)
                        putExtra(CallService.EXTRA_TERMINATE_ON_END, terminateOnEnd)
                    }
                    ContextCompat.startForegroundService(applicationContext, intent)
                }
            }

            Notify.AUDIO_CALL -> {
                appScope.launch {
                    debugLine(tag, "Received AUDIO_CALL signal, callId=$callId, channelId=$channelId")

                    if(callId == null) {
                        debugLine(tag, "CallId is NULL, cannot accept the video call")
                        return@launch
                    }

                    if(channelId == null) {
                        debugLine(tag, "CallId is NULL, cannot accept the video call")
                        return@launch
                    }

                    val terminateOnEnd = !appIsForeground()

                    val intent = Intent(applicationContext, CallService::class.java).apply {
                        action = CallService.ACTION_INCOMING_CALL
                        putExtra(CallService.EXTRA_REMOTE_USER_ID, fromUserId)
                        putExtra(CallService.EXTRA_CALL_ID, callId)
                        putExtra(CallService.EXTRA_CHANNEL_ID, channelId)
                        putExtra(CallService.EXTRA_IS_VIDEO, false)
                        putExtra(CallService.EXTRA_TERMINATE_ON_END, terminateOnEnd)
                    }
                    ContextCompat.startForegroundService(applicationContext, intent)
                }
            }

            in listOf(CallEvent.ACCEPT, CallEvent.REJECT, CallEvent.BUSY, CallEvent.CLOSE, CallEvent.VIDEO_ON,
                CallEvent.VIDEO_OFF,CallEvent.CANCEL, CallEvent.FAILED, CallEvent.NO_ANSWER, CallEvent.HELD,
                CallEvent.UNHELD, CallEvent.CONNECTION_FAILED, CallEvent.VIDEO_UPGRADE_REQUEST,
                CallEvent.VIDEO_UPGRADE_ACCEPT, CallEvent.VIDEO_UPGRADE_REJECT) -> {
                debugLine(tag, "Received video call control message: $channelId")

                appScope.launch {
                    if(callId == null) {
                        debugLine(tag, "CallId is NULL, cannot accept the call event")
                        return@launch
                    }
                    receiveCallEventFromPeer(type, callId)
                }
            }

            Notify.PENDING-> {
                debugLine(tag, "Received pending message: $channelId")

                channelId?.let {
                    appScope.launch {
                        try {
                            shutdownRTC(fromUserId)
                            debugLine(tag, "Previous connection instance cleared")
                        } catch(ex: Exception) {
                            debugLine(tag, "There was no connection manager instance to cancel: ${ex.message}")
                        }

                        val parts = channelId.split("#", limit = 2)
                        val msgId = parts[0]
                        val chatGroupId = parts.getOrNull(1)

                        if (CancelledTransferRegistry.isCancelled(applicationContext, msgId)) {
                            debugLine(tag, "Transfer $msgId was cancelled, ignoring pending")
                            return@launch
                        }

                        val existingMessage = getMessageRepository(App.context()).getMessage(msgId)
                        if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
                            debugLine(tag, "Message $msgId already received, sending allReceived to $fromUserId")
                            val deliveryId = existingMessage.chatGroupId?.let {
                                computeDeliveryDocId(it, existingMessage.originalSenderId ?: "", existingMessage.date)
                            }
                            notifyRemotePeer(fromUserId, msgId, Notify.ALL_RECEIVED, deliveryId)
                            return@launch
                        }

                        // Resume-aware sendMe: report which chunks are still missing so
                        // the sender can re-send only those (big-transfer bandwidth saver).
                        // No local chunks -> plain sendMe (full send, original behavior).
                        val pendingContentKey = if (existingMessage != null) {
                            contentKeyOf(msgId, existingMessage.chatGroupId, existingMessage.originalSenderId, existingMessage.date)
                        } else {
                            resolveContentKey(getInboxDao(applicationContext), msgId)
                        }
                        val missing = missingChunksByContent(getInboxDao(applicationContext), pendingContentKey)
                        if (missing == null) {
                            // Every chunk is already here: requesting a re-send would be
                            // pure waste. Assembly (worker/recovery) finishes the job.
                            debugLine(tag, "All chunks already present for $msgId — skipping sendMe, awaiting assembly")
                            return@launch
                        }
                        val missingRange = if (missing.isNotEmpty()) "${missing.min()},${missing.max()}" else null
                        if (missingRange != null) {
                            debugLine(tag, "Partial state for $msgId: requesting chunks $missingRange (${missing.size} missing)")
                        }

                        // Remember that this message is owed to us, so PendingRetryWorker
                        // can ask again. The sendMe below can fail silently (a dropped
                        // signalling socket is enough) and until now nothing on this side
                        // survived that failure: recovery depended entirely on the sender.
                        IncomingPendingTracker.record(applicationContext, msgId, fromUserId)

                        if (!chatGroupId.isNullOrEmpty()) {
                            debugLine(tag, "Group pending for $chatGroupId, fanning out sendMe")
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
                                    val targets = membersMap?.keys?.filter { key -> key != myId } ?: emptyList()

                                    for (memberId in targets) {
                                        notifyRemotePeer(memberId, msgId, "sendMe", missingRange)
                                    }
                                    debugLine(tag, "Sent sendMe to ${targets.size} group members for $msgId")
                                } else {
                                    debugLine(tag, "Group $chatGroupId not found, falling back to sender")
                                    notifyRemotePeer(fromUserId, msgId, "sendMe", missingRange)
                                }
                            } catch (e: Exception) {
                                debugLine(tag, "Failed to fan out sendMe, falling back to sender: ${e.message}")
                                notifyRemotePeer(fromUserId, msgId, "sendMe", missingRange)
                            }
                        } else {
                            debugLine(tag, "1:1 pending, sending sendMe to $fromUserId")
                            notifyRemotePeer(fromUserId, msgId, "sendMe", missingRange)
                        }
                    }
                }
            }

            Notify.SEND_ME -> {
                debugLine(tag, "I am sending a message: $channelId to requester $fromUserId")
                // The requester is demonstrably awake, it just asked us for this
                // message. A cooldown armed moments ago by a failed attempt would
                // otherwise make the dispatch below defer against a peer that is
                // sitting there waiting.
                DispatchWorker.markPeerAlive(applicationContext, fromUserId)
                channelId?.let { payload ->
                    appScope.launch {
                        // Optional "#low,high" suffix (same format as someMissing): the
                        // requester already holds everything outside that range.
                        val sendMeParts = payload.split("#", limit = 2)
                        val msgId = sendMeParts[0]
                        val missingItems: List<Int>? = sendMeParts.getOrNull(1)?.let { range ->
                            try {
                                val (low, high) = range.split(",").map { it.trim().toInt() }
                                if (low in 1..high) (low..high).toList() else null
                            } catch (e: Exception) {
                                debugLine(tag, "Ignoring malformed sendMe range '$range'")
                                null
                            }
                        }

                        if (CancelledTransferRegistry.isCancelled(applicationContext, msgId)) {
                            debugLine(tag, "Transfer $msgId was cancelled, ignoring sendMe")
                            return@launch
                        }
                        if (batchTablesExists(msgId)) {
                            if (missingItems != null) {
                                debugLine(tag, "Partial sendMe: re-sending chunks ${missingItems.first()}..${missingItems.last()} of $msgId to $fromUserId")
                                // The requester holds everything outside this range, but —
                                // unlike the someMissing flow — the batch rows here may
                                // still be sent=0 (post-freeze rebuild or interrupted
                                // dispatch). Normalize first: mark all as delivered, then
                                // re-enable only the missing range, or dispatch would
                                // re-stream the whole remainder as duplicates.
                                restrictBatchToMissing(msgId, missingItems, applicationContext)
                                reSendMessage(fromUserId, msgId, missingItems, applicationContext)
                            } else {
                                debugLine(tag, "Batch tables exist for $msgId, dispatching to $fromUserId")
                                submitDispatchWorker(msgId, fromUserId, applicationContext)
                            }
                        } else {
                            val message = getMessageRepository(App.context()).getMessage(msgId)
                            if (message != null) {
                                val isReceivedMessage = message.toUserId == MySelf.userId()
                                val isGroupMessage = !message.chatGroupId.isNullOrEmpty()

                                if (message.status == Status.RECEIVING) {
                                    debugLine(tag, "Message $msgId still RECEIVING, cannot relay incomplete message")
                                    return@launch
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
                                        WorkManager.getInstance(applicationContext)
                                            .getWorkInfosByTag(logicalTag).await()
                                    } catch (e: Exception) {
                                        debugLine(tag, "WorkManager query failed, proceeding: ${e.message}")
                                        emptyList()
                                    }
                                    val hasActive = existing.any { !it.state.isFinished }
                                    if (hasActive) {
                                        debugLine(tag, "Skip sendMe for $msgId: dispatch already in flight to $fromUserId (same content)")
                                        return@launch
                                    }
                                    debugLine(tag, "Re-sending received/group message $msgId to requester $fromUserId")
                                    submitSendMessageWorker(
                                        MessageData.fromMessage(message).copy(toUserId = fromUserId),
                                        applicationContext,
                                        resendMissing = missingItems
                                    )
                                } else {
                                    sendMessageId(msgId)
                                }
                            } else {
                                debugLine(tag, "Cannot resend $msgId: no message in DB and no batch tables")
                            }
                        }
                    }
                }
            }

            Notify.GROUP_PENDING -> {
                debugLine(tag, "Group member needs a message: $channelId")
                channelId?.let { payload ->
                    appScope.launch {
                        try {
                            val parts = payload.split("#")
                            if (parts.size < 4) {
                                debugLine(tag, "Invalid groupPending payload: $payload")
                                return@launch
                            }
                            val chatGroupId = parts[0]
                            val originalSenderId = parts[1]
                            val date = parts[2].toLong()
                            val targetUserId = parts[3]

                            val repo = getMessageRepository(applicationContext)
                            if (!repo.groupMessageExists(chatGroupId, originalSenderId, date)) {
                                debugLine(tag, "I don't have this group message, ignoring")
                                return@launch
                            }

                            val message = repo.getGroupMessage(chatGroupId, originalSenderId, date)
                            if (message == null) {
                                debugLine(tag, "Failed to retrieve group message")
                                return@launch
                            }

                            debugLine(tag, "I have the message, re-sending to $targetUserId")

                            val memberMessageId = guid()
                            val memberMessage = MessageData.fromMessage(message).copy(
                                toUserId = targetUserId,
                                messageId = memberMessageId,
                                chatGroupId = chatGroupId,
                                originalSenderId = originalSenderId
                            )

                            sendSingleMessage(memberMessage)
                        } catch (ex: Exception) {
                            debugLine(tag, "Exception on groupPending: ${ex.message}")
                        }
                    }
                }
            }

            Notify.GROUP_REMOVED -> {
                debugLine(tag, "I have been removed from a group: $channelId")
                val gId = channelId?.substringBefore("#") ?: return

                appScope.launch {
                    try {
                        val messageRepository = getMessageRepository(applicationContext)
                        messageRepository.deleteRemotePeerMessages(gId)

                        val peerViewModel = getPeerViewModel()
                        peerViewModel.deletePeer(peerViewModel.getPeer(gId) ?: return@launch)

                        debugLine(tag, "Group $gId removed from local DB")
                    } catch (e: Exception) {
                        debugLine(tag, "Error removing group $gId: ${e.message}")
                    }
                }
            }

            Notify.DATA_CALL -> {
                debugLine(tag, "Someone is trying to wake me up for DATA")

                val ck = incomingContentKey ?: ""

                channelId?.let { cid ->
                    if (ck.isNotEmpty()) {
                        appScope.launch {
                            try {
                                val inboxDao = getInboxDao(applicationContext)
                                val messageDao = getMessageDao(applicationContext)
                                val firstRow = inboxDao.getFirstByContent(ck)
                                val anchorMessageId = firstRow?.groupId?.takeIf { it.isNotEmpty() } ?: firstRow?.messageId
                                val existingMsg = anchorMessageId?.let { messageDao.getMessage(it) }
                                if (existingMsg != null && existingMsg.status != Status.RECEIVING) {
                                    debugLine(tag, "DATA_CALL gate: content $ck already complete, replying allReceived to $fromUserId")
                                    notifyRemotePeer(fromUserId, anchorMessageId, Notify.ALL_RECEIVED)
                                    return@launch
                                }
                                if (!ConnectionManager.instance.tryAdmitSender(ck, fromUserId)) {
                                    debugLine(tag, "DATA_CALL gate: too many active senders for $ck, replying connectionBusy to $fromUserId")
                                    notifyRemotePeer(fromUserId, cid, Notify.CONNECTION_BUSY)
                                    return@launch
                                }
                                // Admitted. Proceed to open the connection (foreground path or DataSyncService).
                                dispatchDataCallToConnectionPath(cid, fromUserId)
                            } catch (e: Exception) {
                                debugLine(tag, "DATA_CALL gate error: ${e.message}")
                                dispatchDataCallToConnectionPath(cid, fromUserId)
                            }
                        }
                    } else {
                        dispatchDataCallToConnectionPath(cid, fromUserId)
                    }
                }
            }

            Notify.CONNECTION_BUSY -> {
                debugLine(tag, "Remote peer is busy for DATA_CALL, aborting connect attempt")
                CallEventBus.callControlFlow.tryEmit(
                    CallControlEvent(
                        action = CallEvent.CONNECTION_BUSY,
                        remoteUserId = fromUserId,
                        reason = "peer busy"
                    )
                )
            }

            Notify.TYPING -> {
                debugLine(tag, "Someone is typing")
                val intent = Intent(Broadcast.ACTION_START_TYPING)
                intent.putExtra("userId", fromUserId)
                intent.putExtra("chatGroupId", channelId ?: "")
                LocalBroadcastManager.getInstance(App.context()).sendBroadcast(intent)
            }

            Notify.STOP_TYPING -> {
                debugLine(tag, "Someone has stopped typing")
                val intent = Intent(Broadcast.ACTION_STOP_TYPING)
                intent.putExtra("userId", fromUserId)
                intent.putExtra("chatGroupId", channelId ?: "")
                LocalBroadcastManager.getInstance(App.context()).sendBroadcast(intent)
            }

            Notify.ALL_RECEIVED -> {
                debugLine(tag, "My message has been received: $channelId")
                timer(false)
                appScope.launch {
                    channelId?.let { rawChannelId ->
                        val parts = rawChannelId.split("#", limit = 2)
                        val messageId = parts[0]
                        val deliveryDocId = parts.getOrNull(1)

                        val messageViewModel = getMessageViewModel(MySelf.userId()!!, fromUserId)

                        val delivered = ContextCompat.getString(App.context(), R.string.delivered)
                        messageViewModel.updateStatus(messageId, delivered)

                        deleteBatchTables(messageId)

                        if(messageViewModel.isProfile(messageId)) {

                            setAcquisitionStatus(fromUserId,
                                Location.LOCAL,
                                ProfileType.LOCAL,
                                AcquisitionStatus.SENT,
                                App.context())
                            debugLine("receiveProfile", "Set LOCALLY My profile sent")

                            setAcquisitionStatus(fromUserId,
                                Location.REMOTE,
                                ProfileType.LOCAL,
                                AcquisitionStatus.RECEIVED,
                                App.context())
                            debugLine("receiveProfile", "Set REMOTELLY My profile received")

                            val myProfileStat = getAcquisitionStatus(
                                fromUserId,
                                Location.LOCAL,
                                ProfileType.REMOTE,
                                App.context())
                            debugLine("receiveProfile", "Getting My profileStat LOCALLY/Remote Profile status: $myProfileStat")


                            when(myProfileStat) {
                                AcquisitionStatus.RECEIVED -> {
                                    debugLine("receiveProfile", "Already received remote profile, set ACTIVE")
                                    getPeerViewModel().setStatusToActive(fromUserId)
                                }
                            }
                        }

                        if (deliveryDocId != null && deliveryDocId.startsWith("relay:")) {
                            val confirmedMember = deliveryDocId.removePrefix("relay:")
                            val statusDao = DatabaseProvider.provideDatabase(App.context()).groupMessageStatusDao()
                            val currentMemberStatus = statusDao.getStatusForMember(messageId, confirmedMember)
                            if (currentMemberStatus != Notify.SEEN) {
                                statusDao.updateStatus(messageId, confirmedMember, delivered)
                                debugLine(tag, "Updated GroupMessageStatus (relayed): $confirmedMember → Delivered for $messageId")
                            } else {
                                debugLine(tag, "Skipped GroupMessageStatus (relayed) for $confirmedMember (already Seen) for $messageId")
                            }
                        } else {
                            handleGroupDeliveryConfirmation(deliveryDocId, fromUserId)
                        }

                        PendingMessageTracker.remove(applicationContext, messageId, fromUserId)

                        val stalePending = PendingMessageTracker.getPendingForPeer(applicationContext, fromUserId)
                        if (stalePending.isNotEmpty()) {
                            debugLine(tag, "Piggyback: ${stalePending.size} stale pending message(s) for $fromUserId")
                            for (entry in stalePending) {
                                debugLine(tag, "Piggyback dispatching: ${entry.messageId} → $fromUserId")
                                submitDispatchWorker(
                                    messageId = entry.messageId,
                                    toUserId = fromUserId,
                                    context = applicationContext,
                                    chatGroupId = entry.chatGroupId ?: "",
                                    originalSenderId = entry.originalSenderId ?: "",
                                    messageDate = entry.messageDate
                                )
                            }
                        }
                    }
                }
            }

            Notify.SEEN -> {
                debugLine(tag, "My message has been seen: $channelId")
                appScope.launch {
                    channelId?.let {
                        val messageViewModel = getMessageViewModel(MySelf.userId()!!, fromUserId)
                        messageViewModel.updateStatus(it, Notify.SEEN)
                        debugLine("onMessageReceived", "Notifing remote peer I have seen Message: $channelId")
                        notifyRemotePeer(fromUserId, it, Notify.RECEIVED_SEEN)
                    }
                }
            }

            Notify.RECEIVED_SEEN -> {
                debugLine(tag, "My message has been received and seen: $channelId")
                appScope.launch {
                    channelId?.let {
                        val messageRepository = getMessageRepository(this@MyFirebaseMessagingService)
                        debugLine(tag, "Updating SEEN status Message: $channelId")
                        messageRepository.updateStatus(channelId, Notify.SEEN)
                    }
                }
            }

            Notify.GROUP_SEEN -> {
                debugLine(tag, "Group message seen by $fromUserId: $channelId")
                appScope.launch {
                    channelId?.let { messageId ->
                        val statusDao = DatabaseProvider.provideDatabase(applicationContext).groupMessageStatusDao()
                        statusDao.updateStatus(messageId, fromUserId, Notify.SEEN)
                        debugLine(tag, "Updated GroupMessageStatus: $fromUserId → Seen for $messageId")

                        val allStatuses = statusDao.getStatusesForMessage(messageId)
                        val allSeen = allStatuses.isNotEmpty() && allStatuses.all { it.status == Notify.SEEN }

                        if (allSeen) {
                            val repo = getMessageRepository(applicationContext)
                            val currentMessage = repo.getMessage(messageId)

                            if (currentMessage != null && currentMessage.status != Notify.SEEN) {
                                val messageViewModel = getMessageViewModel(MySelf.userId()!!, currentMessage.toUserId)
                                messageViewModel.updateStatus(messageId, Notify.SEEN)
                                debugLine(tag, "Group message $messageId updated to Seen (all members seen)")
                            }
                        }
                    }
                }
            }

            Notify.REQUEST_PROFILE -> {
                debugLine(tag, "Remote peer $fromUserId is requesting my profile")
                appScope.launch {
                    getPeerViewModel().sendMyProfileToRemotePeer(fromUserId)
                }
            }

            Notify.CONTACT_REQUEST -> {
                debugLine(tag, "Contact request nudge from $fromUserId")
                appScope.launch {
                    try {
                        if (!isAutoInviteEnabled(applicationContext)) {
                            debugLine(tag, "Auto-invite disabled; request will surface on next app open")
                            return@launch
                        }

                        val doc = Firebase.firestore
                            .collection("users").document(myUserId)
                            .collection("requests").document(fromUserId)
                            .get()
                            .await()

                        if (!doc.exists()) {
                            debugLine(tag, "No pending request doc from $fromUserId (already processed?)")
                            return@launch
                        }

                        autoAcceptRequestDocument(doc, applicationContext)
                    } catch (e: Exception) {
                        // A process cold-started by this very nudge often has no
                        // usable network yet: App Check cannot attest and the
                        // Firestore read dies with PERMISSION_DENIED (seen on
                        // Raoul's log, 7 Aug: the give-up here cost six hours).
                        // Hand the job to a network-constrained retry worker
                        // instead of dropping it.
                        debugLine(tag, "Contact request handling failed: ${e.message}. Scheduling retry.")
                        ContactRequestRetryWorker.enqueue(applicationContext)
                    }
                }
            }

            Notify.ALL_MISSING -> {
                debugLine(tag, "My message has NOT been received: $channelId")
                channelId?.let{
                    reSendMessage(fromUserId, it, emptyList(), applicationContext)
                }
            }

            Notify.SOME_MISSING -> {
                debugLine(tag, "My message has NOT been fully received: $channelId")

                if(channelId == null){
                    debugLine("onMessageReceived", "Content is NULL")
                    return
                }

                try {
                    val parts = channelId.split("#")
                    val messageId = parts[0]
                    val rangeItems = stringToListInt(parts[1])
                    val low = rangeItems.first()
                    val high = rangeItems.last()
                    val missingItems = (low..high).toList()
                    debugLine(tag, "Re-sending chunk range $low..$high (${missingItems.size} chunks)")

                    reSendMessage(fromUserId, messageId, missingItems, applicationContext)
                } catch(ex: Exception){
                    debugLine(tag, "Exception on someMissing reception: ${ex.message}")
                }
            }

            Notify.CANCEL_TRANSFER -> {
                debugLine(tag, "Transfer cancelled by $fromUserId: $channelId")
                channelId?.let { msgId ->
                    appScope.launch {
                        try {
                            receiveTransferCancelled(fromUserId, msgId, applicationContext)
                        } catch (ex: Exception) {
                            debugLine(tag, "Exception on cancelTransfer: ${ex.message}")
                        }
                    }
                }
            }

            Notify.COMPLETED -> {
                debugLine(tag, "This message has been sent to me: $channelId")
                channelId?.let {
                    appScope.launch {
                        if (CancelledTransferRegistry.isCancelled(applicationContext, it)) {
                            debugLine(tag, "Transfer $it was cancelled, ignoring completed")
                            return@launch
                        }
                        val messageStatus = checkIfMessageIsCompleted(it)
                        when {
                            // SOAK CHANGE, 01/03, removed sending all received
                            messageStatus == null -> {
                                debugLine(tag, "All chunks received, requesting inbox processing")
                                MessageProcessor.requestInboxCheck(it)
                            }

                            messageStatus.isEmpty() -> {
                                debugLine(tag, "No chunks received")
                                notifyRemotePeer(fromUserId, it, "allMissing")
                            }

                            else -> {
                                val missingRange = "${messageStatus.min()},${messageStatus.max()}"
                                debugLine(tag, "Missing chunks range: $missingRange (${messageStatus.size} items actually missing)")
                                notifyRemotePeer(fromUserId, it, "someMissing", missingRange)
                            }
                        }
                    }
                }
            }

            Notify.GROUP -> {
                val gId = channelId?.substringBefore("#")
                val groupName = channelId?.substringAfter("#", "")

                if (gId == null || groupName == null) return

                debugLine(tag, "GroupId = $gId, groupName = $groupName")
                appScope.launch { saveNewGroupAsPeer(gId, groupName) }

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

                MessageReceivedNotification.show(message)
            }

            Notify.JOINED -> {
                val intent = Intent(CallEvent.PEER_JOINED)
                intent.putExtra(Broadcast.ACTION_CONTENT, fromUserId)

                LocalBroadcastManager.getInstance(App.context()).sendBroadcast(intent)
            }
        }
    }

    private fun dispatchDataCallToConnectionPath(cid: String, fromUserId: String) {
        if (appIsForeground()) {
            appScope.launch {
                try {
                    val existing = ConnectionManager.instance.getExistingClient(fromUserId)
                    if (existing != null && existing.rtcClient.isConnected() && existing.rtcClient.isDataChannelOpen()) {
                        debugLine(tag, "DATA_CALL: already connected to $fromUserId, ignoring.")
                        return@launch
                    }
                    try { ConnectionManager.instance.webRTCCleanUp(fromUserId) } catch (e: Exception) { debugLine(tag, "Ignore: ${e.message}") }
                    ConnectionManager.instance.webRTCConnect(cid, "", fromUserId, false, applicationContext, video = false, dataOnly = true)
                } catch (e: Exception) {
                    debugLine(tag, "DATA_CALL foreground connect failed: ${e.message}")
                }
            }
        } else {
            val intent = Intent(applicationContext, DataSyncService::class.java).apply {
                action = DataSyncService.ACTION_START_SYNC
                putExtra(DataSyncService.EXTRA_CHANNEL_ID, cid)
                putExtra(DataSyncService.EXTRA_REMOTE_USER_ID, fromUserId)
            }
            try {
                startForegroundService(intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                    debugLine(tag, "CRITICAL: Foreground service start denied: ${e.message}")
                } else {
                    debugLine(tag, "Failed to start data sync service: ${e.message}")
                }
            }
        }
    }

    // TODO This function is not used yet, but it should be in a future
//    private suspend fun isMyContact(userId: String): Boolean {
//        return peersRepository.getPeer(userId) != null
//    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        debugLine(tag, "Got new Token: $token")

        appScope.launch {
            val myUserId = MySelf.userId()
            if (myUserId == null) {
                debugLine(tag, "No UserID yet. Skipping background update (initApplication will handle it).")
                return@launch
            }

            val oldToken = MySelf.fcmTokenGet()

            // Only update if it's actually different or we want to be sure
            if (token != oldToken) {
                val result = updateMyFcmToken(myUserId, token, oldToken)
                if(result) {
                    debugLine(tag, "Token updated successfully via Service")
                }
            }
        }
    }
}

private const val NOTICE_TIMEOUT_MS = 60_000L

fun showIncomingDataNotification(remoteUserId: String) {
    val context = App.context()
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "sync_channel"
    val tag = "showIncomingDataNotification"

    if (notificationManager.getNotificationChannel(channelId) == null) {
        val channel = NotificationChannel(channelId, "Connectivity", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Background synchronization status"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    val intent = Intent(context, ChatScreen::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("userId", remoteUserId)
    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        remoteUserId.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.mtc_logo_small_icon)
        .setContentText(context.getString(R.string.checking))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(false)
        .setAutoCancel(true)
        // System-side expiry. The cancel below runs on an app coroutine, which does
        // not tick while the process is frozen — a doze window would otherwise leave
        // this notice on screen for as long as the freeze lasts.
        .setTimeoutAfter(NOTICE_TIMEOUT_MS)
        .setContentIntent(pendingIntent)

    // Per-peer id. 9999 belongs to DataSyncService/DataSyncWorker: sharing it meant
    // their stopForeground(STOP_FOREGROUND_REMOVE) cancelled this notice, and made
    // the matching cancel in MessageReceivedNotification.show() a no-op.
    val notificationId = "sync_$remoteUserId".hashCode()

    try {
        notificationManager.notify(notificationId, builder.build())
        debugLine(tag, "NOTIF_FIRED id=$notificationId source=showIncomingData peer=$remoteUserId")
    } catch (e: SecurityException) {
        debugLine(tag, "SecurityException on showIncomingDataNotification: ${e.message}")
    }

    val appScope = (context.applicationContext as App).applicationScope
    appScope.launch(Dispatchers.IO) {
        delay(30000L)
        try {
            notificationManager.cancel(notificationId)
            debugLine(tag, "NOTIF_CLEARED id=$notificationId source=showIncomingData peer=$remoteUserId")
        } catch (e: Exception) {
            debugLine(tag, "SecurityException on showIncomingDataNotification: ${e.message}")
        }
    }
}

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