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
import com.bolimot.mindtheclub.functions.ContentServeQueue
import com.bolimot.mindtheclub.functions.GroupSeenTracker
import com.bolimot.mindtheclub.functions.IncomingPendingTracker
import com.bolimot.mindtheclub.functions.PeerProbe
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.RecoveryProgress
import com.bolimot.mindtheclub.functions.appIsForeground
import com.bolimot.mindtheclub.functions.batchContentCoordinates
import com.bolimot.mindtheclub.functions.batchTablesExists
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.DeliveryHealth
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getMessageViewModel
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.pickRecoverySource
import com.bolimot.mindtheclub.functions.groupHopId
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
import com.bolimot.mindtheclub.sending.promoteGroupAggregate
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
import com.bolimot.mindtheclub.voip.GroupCallService
import com.bolimot.mindtheclub.voip.receiveCallEventFromPeer
import com.bolimot.mindtheclub.voip.ringGroupCall
import com.bolimot.mindtheclub.webrtc.group.GroupCallManager
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

        // Anything reaching us proves the app is alive and being delivered to,
        // which is exactly what a phone suppressing it prevents.
        DeliveryHealth.recordHeartbeat(applicationContext)

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
                type?.let { handleMessage(remoteUserId, content, callId, it, incomingContentKey, data) } ?: run {
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

    // Identity of the original group message for a relayed copy. Receivers archive a
    // copy under groupId.ifEmpty { messageId }, so a relay that leaves groupId empty
    // makes every copy land under a fresh key: the same photo saved as two bubbles
    // (Black, 15 Aug) and an orphan RECEIVING placeholder begging the sender forever
    // (Gio, 15 Aug). A forwarded copy already carries the original id in groupId; the
    // original sender's own row has groupId empty by construction, so there the
    // identity is its messageId. One to one messages keep groupId as is: empty is
    // correct outside groups. Same rule fromMessage() already applies to albums.
    private fun originalIdOf(message: Message): String =
        if (message.chatGroupId.isNullOrEmpty()) message.groupId
        else message.groupId.ifEmpty { message.messageId }

    /**
     * @param data the whole decrypted payload. Most types need only the four
     * fields above, but a group call invitation also carries the call key, and
     * that key must never be widened into its own parameter and forgotten in a
     * log line: it stays inside the payload map it arrived in.
     */
    private fun handleMessage(
        fromUserId: String,
        channelId: String?,
        callId: String?,
        type: String,
        incomingContentKey: String? = null,
        data: Map<String, String> = emptyMap()
    ) {
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

            Notify.GROUP_CALL -> {
                debugLine(tag, "Received GROUP_CALL invitation, room=$callId")

                val roomId = callId
                val key = data["gcKey"].orEmpty()
                val epoch = data["gcEpoch"]?.toIntOrNull() ?: 0

                if (roomId == null || key.isEmpty()) {
                    debugLine(tag, "Group call invitation without a room or a key, ignoring")
                } else {
                    ringGroupCall(roomId, key, epoch, fromUserId)
                }
            }

            Notify.GROUP_CALL_REKEY -> {
                // Somebody left, so the call moved to a new key. Only participants
                // still in the room are sent one, which is what actually removes
                // the person who left rather than trusting them to stop watching.
                val key = data["gcKey"].orEmpty()
                val epoch = data["gcEpoch"]?.toIntOrNull() ?: 0
                if (key.isNotEmpty() && callId != null && callId == GroupCallManager.roomId) {
                    GroupCallManager.adoptKey(key, epoch)
                }
            }

            Notify.GROUP_CALL_END -> {
                debugLine(tag, "Group call invitation withdrawn, room=$callId")
                callId?.let { GroupCallService.remoteEnd(applicationContext, it) }
            }

            Notify.GROUP_CALL_DECLINE -> {
                debugLine(tag, "Group call declined by $fromUserId")
                if (callId != null && fromUserId.isNotEmpty()) {
                    GroupCallManager.onDeclined(callId, fromUserId)
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
                        // Nothing is torn down here. A pending is an ANNOUNCEMENT:
                        // it never builds the connection it used to destroy, so
                        // every teardown it performed was a pure loss.
                        //
                        // This called shutdownRTC, a broadcast that makes every
                        // RTCClient for the peer destroy itself. A guard was added
                        // on 21 Aug to spare a live connection, but it asks
                        // rtcClientsRepository, and a connection still being built
                        // is not in that map yet: webRTCConnect only files it after
                        // it is up ("Repository empty, I need to create a new
                        // RTCClient" precedes the insertion by seconds). So the
                        // guard protected the finished case and left the fragile
                        // one, which is the only one that ever gets hit, because a
                        // burst of announcements lands precisely while a transfer
                        // is negotiating.
                        //
                        // Measured on Romy, 21 Aug 16:04:07: a dataCall from Raoul
                        // started building a channel, a pending for an UNRELATED
                        // content arrived one second later, the broadcast fired and
                        // the log reads "Incoming connection failed, Result = :
                        // Requested RTC shutdown". The photo moved zero chunks. The
                        // same phone lost 8 connections that way on 21 Aug and 229
                        // on 12-13 Aug; no other device in any log lost a single
                        // one, because no other device received announcements while
                        // negotiating.
                        //
                        // A stale client cannot survive this removal: the DATA_CALL
                        // path runs webRTCCleanUp before it connects (visible as
                        // "Cleaning up all WebRTC resources for user" one second
                        // after every wake-up), and webRTCConnect itself deletes a
                        // client it finds dead. The cleanup already happens where a
                        // replacement is actually about to be built.

                        val parts = channelId.split("#", limit = 2)
                        val msgId = parts[0]
                        val chatGroupId = parts.getOrNull(1)

                        // Second door onto the same defect the Inbox purge closes
                        // (see MessageRepository.purgeChatLeftovers): an
                        // announcement for a group this device no longer has.
                        // On 19 Aug the handler logged "Group ... not found" and
                        // then solicited the content anyway through its fallback,
                        // pulling a 923 chunk video into a deleted chat. That
                        // fallback exists for a TRANSIENT Firestore miss and is
                        // right to stay; the peer row is the local, authoritative
                        // fact and a network hiccup cannot fake it.
                        //
                        // The trade: an announcement that overtakes the group
                        // invitation for a genuinely new member is dropped too.
                        // That costs one sender retry cycle and heals itself,
                        // whereas the other direction re-downloads whole videos
                        // into a chat that cannot even display them.
                        if (!chatGroupId.isNullOrEmpty() &&
                            !getPeerDao(applicationContext).exist(chatGroupId)
                        ) {
                            debugLine(tag, "Pending for group $chatGroupId which is not on this device, ignoring")
                            return@launch
                        }

                        if (CancelledTransferRegistry.isCancelled(applicationContext, msgId)) {
                            debugLine(tag, "Transfer $msgId was cancelled, ignoring pending")
                            return@launch
                        }

                        // Resolved the way the DATA_CALL gate resolves it, and for the
                        // same reason: the id in an announcement is the ANNOUNCER's
                        // relay id, while our copy of the message is filed under the
                        // original sender's id. Looking it up directly finds nothing,
                        // and the code below then concludes we have never seen this
                        // content, falls back to counting inbox chunks, and asks for
                        // whatever residue says is missing.
                        //
                        // On 20 Aug the two checks contradicted each other four seconds
                        // apart on the same contentKey: the gate answered "already
                        // complete", this one announced "41 chunks out of 68". Six and
                        // a half hours and forty five rounds later it was still asking
                        // for the same 27 chunks of an album sitting complete in the
                        // chat. Answering allReceived here also lets the announcer drop
                        // its pending entry, so both halves of the loop stop.
                        val directMessage = getMessageRepository(App.context()).getMessage(msgId)
                        val existingMessage = directMessage ?: run {
                            val inboxDao = getInboxDao(applicationContext)
                            val firstRow = inboxDao.getFirstMessage(msgId)
                            val anchorId = firstRow?.groupId?.takeIf { it.isNotEmpty() }
                            anchorId?.let { getMessageRepository(App.context()).getMessage(it) }
                        }
                        if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
                            debugLine(tag, "Message $msgId already received, sending allReceived to $fromUserId")
                            RecoveryProgress.clear(applicationContext, msgId)
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
                            RecoveryProgress.clear(applicationContext, msgId)
                            return@launch
                        }
                        val missingRange = if (missing.isNotEmpty()) "${missing.min()},${missing.max()}" else null
                        if (missingRange != null) {
                            debugLine(tag, "Partial state for $msgId: requesting chunks $missingRange (${missing.size} missing)")
                        }

                        // Answer only if the previous answers achieved something.
                        // Measured on chunks actually held, so a slow transfer that
                        // keeps gaining ground is never interrupted, a transfer that
                        // has produced nothing at all is never braked, and only one
                        // standing still with part of the content here waits, for
                        // half an hour.
                        // See RecoveryProgress for why this is not a cap on attempts.
                        val heldChunks = getInboxDao(applicationContext).countChunksByContent(pendingContentKey)
                        if (!RecoveryProgress.shouldAsk(applicationContext, msgId, heldChunks)) {
                            return@launch
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
                                    val targets = membersMap?.keys?.filter { key -> key != myId }?.sorted() ?: emptyList()

                                    if (targets.isEmpty()) {
                                        notifyRemotePeer(fromUserId, msgId, "sendMe", missingRange)
                                    } else {
                                        // ONE member per round instead of all of them at once.
                                        // Every member answers a sendMe with a FULL redispatch,
                                        // so the old fan out turned each stall into triple
                                        // traffic on the same pipe (13 Aug: a 78 chunk photo
                                        // transmitted 4.5 times over, 11 fan outs in 15
                                        // minutes). Round 0 asks whoever announced the pending,
                                        // it certainly holds the content. Later rounds used to
                                        // walk the member list blindly, soliciting members that
                                        // provably held nothing (15 Aug: allMissing floods);
                                        // now they ask a member with a REGISTERED complete
                                        // copy, and only fall back to the announcer when the
                                        // delivery doc knows of none.
                                        val rotationPrefs = applicationContext.getSharedPreferences("SendMeRotation", MODE_PRIVATE)
                                        val round = rotationPrefs.getInt(msgId, 0)
                                        val target = if (round == 0 && fromUserId in targets) fromUserId
                                                     else pickRecoverySource(
                                                         existingMessage?.chatGroupId ?: chatGroupId,
                                                         existingMessage?.originalSenderId,
                                                         existingMessage?.date ?: 0L,
                                                         fallback = fromUserId
                                                     )
                                        rotationPrefs.edit().putInt(msgId, round + 1).apply()
                                        notifyRemotePeer(target, msgId, "sendMe", missingRange)
                                        debugLine(tag, "Sent sendMe round $round to $target for $msgId (informed recovery)")
                                    }
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
                            // Same-content guard, the one the relay branch below has
                            // had all along. It was missing exactly here, on the
                            // ORIGINAL sender's path, and that is what let one photo
                            // be transmitted four times over: submitDispatchWorker
                            // keys its unique work on the messageId, group gossip
                            // mints a new messageId at every hop, so four requests
                            // for one file became four unique names and four
                            // concurrent workers, all streaming from chunk 1 (14 Aug:
                            // 648 useful chunks against 1234 discarded as already
                            // present, four streams live in the same minute for an
                            // hour).
                            val coords = batchContentCoordinates(msgId)
                            if (coords != null) {
                                val contentTag = contentTag(
                                    messageId = msgId,
                                    toUserId = fromUserId,
                                    chatGroupId = coords.chatGroupId,
                                    originalSenderId = coords.originalSenderId,
                                    messageDate = coords.messageDate
                                )
                                // RUNNING only, deliberately not "unfinished": an ENQUEUED
                                // retry sitting in WorkManager backoff is doing nothing for
                                // this peer, and a fresh sendMe proves the peer awake right
                                // now (15 Aug: White abandoned by a stalled retry and then
                                // refused by this very guard). A truly running transfer is
                                // still protected; a dormant one gets REPLACEd below.
                                val active = try {
                                    WorkManager.getInstance(applicationContext)
                                        .getWorkInfosByTag(contentTag).await()
                                        .any { it.state == androidx.work.WorkInfo.State.RUNNING }
                                } catch (e: Exception) {
                                    debugLine(tag, "WorkManager query failed, proceeding: ${e.message}")
                                    false
                                }
                                if (active) {
                                    debugLine(tag, "Skip sendMe for $msgId: same content already being dispatched to $fromUserId")
                                    return@launch
                                }

                                // One transfer per content at a time. A second requester is
                                // queued, not served in parallel: parallel uploads sliced
                                // the sender's bandwidth so that nobody ever completed
                                // (15 Aug). It will be invited back with a pending FCM the
                                // moment the current transfer ends.
                                val serveContentId = ContentServeQueue.contentIdOf(
                                    coords.chatGroupId, coords.originalSenderId, coords.messageDate
                                )
                                if (serveContentId != null &&
                                    !ContentServeQueue.admit(serveContentId, fromUserId, "$msgId#${coords.chatGroupId}")
                                ) {
                                    debugLine(tag, "ServeQueue;Busy serving ${ContentServeQueue.servingTargetOf(serveContentId)} for $serveContentId, queued $fromUserId")
                                    return@launch
                                }
                            }

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
                                // Coordinates passed for the same reason as in
                                // reSendMessage: they make the worker tagged by
                                // content, which is what the guard above matches on.
                                submitDispatchWorker(
                                    msgId, fromUserId, applicationContext,
                                    // See send.kt: omitting this leaves the worker
                                    // naming the message by the hop id.
                                    groupId = coords?.groupId ?: "",
                                    chatGroupId = coords?.chatGroupId ?: "",
                                    originalSenderId = coords?.originalSenderId ?: "",
                                    messageDate = coords?.messageDate ?: 0L
                                )
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
                                    // RUNNING only, same reasoning as the batch path above.
                                    val hasActive = existing.any { it.state == androidx.work.WorkInfo.State.RUNNING }
                                    if (hasActive) {
                                        debugLine(tag, "Skip sendMe for $msgId: dispatch already in flight to $fromUserId (same content)")
                                        return@launch
                                    }

                                    // Same serving discipline as the batch path: one
                                    // transfer per content, later requesters get queued.
                                    val serveContentId = ContentServeQueue.contentIdOf(
                                        message.chatGroupId, message.originalSenderId, message.date
                                    )
                                    if (serveContentId != null &&
                                        !ContentServeQueue.admit(serveContentId, fromUserId, "$msgId#${message.chatGroupId}")
                                    ) {
                                        debugLine(tag, "ServeQueue;Busy serving ${ContentServeQueue.servingTargetOf(serveContentId)} for $serveContentId, queued $fromUserId")
                                        return@launch
                                    }

                                    // Serve group content under the SAME id the push
                                    // path uses towards this same peer, not under the
                                    // original message id.
                                    //
                                    // Both routes name their batch tables and their
                                    // unique work after the messageId, so two different
                                    // ids meant two independent pipelines to one peer,
                                    // running at the same time over one uplink. On
                                    // 20 Aug an album reached a member as
                                    //   bf63d0ba seq 1 / c89aabef seq 1 / bf63d0ba seq 2 ...
                                    // interleaved: 137 chunks on the wire for 71 useful
                                    // ones, 48% thrown away, and each stream at half the
                                    // bandwidth. The RUNNING-only guard above cannot
                                    // catch it, by design: between two attempts the work
                                    // is ENQUEUED, and widening that check is what
                                    // stranded White on 15 Aug.
                                    //
                                    // With one id the two routes share one set of batch
                                    // tables and one unique name, so WorkManager
                                    // serialises them instead of racing them, and the
                                    // sent flags are common: whatever the push already
                                    // delivered is not sent again.
                                    // The guard mirrors groupHopId's own preconditions on
                                    // purpose: given incomplete coordinates it falls back
                                    // to guid(), and a fresh random id here would rebuild
                                    // the tables from chunk 1 on every single request.
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

                                    debugLine(tag, "Re-sending received/group message $msgId to requester $fromUserId as $serveMessageId")
                                    submitSendMessageWorker(
                                        MessageData.fromMessage(message).copy(
                                            toUserId = fromUserId,
                                            messageId = serveMessageId,
                                            groupId = originalIdOf(message)
                                        ),
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
                debugLine(tag, "Someone is typing: $fromUserId (chat ${channelId ?: "1:1"})")
                val intent = Intent(Broadcast.ACTION_START_TYPING)
                intent.putExtra("userId", fromUserId)
                intent.putExtra("chatGroupId", channelId ?: "")
                LocalBroadcastManager.getInstance(App.context()).sendBroadcast(intent)
            }

            Notify.STOP_TYPING -> {
                debugLine(tag, "Someone has stopped typing: $fromUserId (chat ${channelId ?: "1:1"})")
                val intent = Intent(Broadcast.ACTION_STOP_TYPING)
                intent.putExtra("userId", fromUserId)
                intent.putExtra("chatGroupId", channelId ?: "")
                LocalBroadcastManager.getInstance(App.context()).sendBroadcast(intent)
            }

            // Somebody is about to send us something heavy and is asking, before
            // committing, whether we are actually here. Answering costs one small
            // FCM and saves the asker the eleven minutes of WebRTC timeouts it
            // otherwise spends discovering a switched off phone. See PeerProbe.
            Notify.PING -> {
                debugLine(tag, "Ping from $fromUserId, answering pong")
                appScope.launch {
                    fcmSendInstant(fromUserId, "pong", "NotACall", Notify.PONG, Notify.PONG)
                }
            }

            // The answer. Recorded for the probe that is waiting on it, and also
            // fed to the ordinary liveness bookkeeping: an answer that arrives
            // after the asker has already moved on still proves this peer awake,
            // and the next hop can spend one probe fewer.
            Notify.PONG -> {
                debugLine(tag, "Pong from $fromUserId")
                PeerProbe.onPong(fromUserId)
                DispatchWorker.markPeerAlive(applicationContext, fromUserId)
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
                        // For a GROUP message the bubble is an aggregate: it may say
                        // Delivered only when EVERY member has the message. This update
                        // used to be unconditional and flipped the bubble on the FIRST
                        // member's ack (16 Aug: Gio showed Delivered while Black,
                        // correctly "sent" in the detail view, had received nothing).
                        // The per member truth lives in GroupMessageStatus, and
                        // promoteGroupAggregate raises the bubble once no member is
                        // left at "sent". One to one messages keep the immediate
                        // update: one recipient, one ack, delivered.
                        val ackedMessage = getMessageRepository(App.context()).getMessage(messageId)
                        if (ackedMessage?.chatGroupId.isNullOrEmpty()) {
                            messageViewModel.updateStatus(messageId, delivered)
                        }

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
                            promoteGroupAggregate(messageId)
                        } else if (deliveryDocId != null && deliveryDocId.startsWith("refused:")) {
                            // The member swiped our transfer away. Same handling as a
                            // real delivery (it drops out of the member map, so nobody
                            // relays to it again) except that it must not count towards
                            // the fanout, or the message would stop spreading to the
                            // members still waiting for it.
                            debugLine(tag, "$fromUserId refused $messageId, dropping it from the recipients")
                            handleGroupDeliveryConfirmation(
                                deliveryDocId.removePrefix("refused:"),
                                fromUserId,
                                countsTowardFanout = false
                            )
                        } else {
                            handleGroupDeliveryConfirmation(deliveryDocId, fromUserId)
                        }

                        PendingMessageTracker.remove(applicationContext, messageId, fromUserId)

                        // Same acknowledgement, matched by CONTENT as well as by id.
                        //
                        // The line above only finds an entry filed under the exact
                        // messageId the peer quoted, and there are two different ids
                        // in play. A peer that actually received our chunks echoes
                        // back the id it saw on them (allReceivedEvent), which is the
                        // one we filed the entry under, so that case matches. But a
                        // peer answering the DATA_CALL gate — "I already have this
                        // content, do not connect" — replies with firstRow.groupId,
                        // the ORIGINAL message id, while our entry is filed under our
                        // own relay id. Nothing matched, and the piggyback block right
                        // below then re-dispatched that very entry back at them.
                        //
                        // So the acknowledgement was DRIVING the loop it was meant to
                        // stop: on 20 Aug a member answered "already complete" fifteen
                        // times to an album it had held for three hours, and was woken
                        // again every five minutes, with zero PendingTracker;Cleared
                        // in the whole log. Only reachable in a group, where content
                        // arrives by one route while another peer is still offering
                        // it — which is exactly what the cascade is for.
                        //
                        // Group content only: the coordinates are what makes the match
                        // exact, and a one to one entry has none and needs none.
                        val acked = ackedMessage
                        val ackedGroupId = acked?.chatGroupId
                        if (acked != null && !ackedGroupId.isNullOrEmpty()) {
                            for (entry in PendingMessageTracker.getAll(applicationContext)) {
                                if (entry.messageId == messageId) continue
                                if (entry.toUserId != fromUserId) continue
                                if (entry.chatGroupId != ackedGroupId) continue
                                if (entry.originalSenderId != acked.originalSenderId) continue
                                if (entry.messageDate != acked.date) continue

                                debugLine(tag, "Clearing pending ${entry.messageId} → $fromUserId: same content acked as $messageId")
                                PendingMessageTracker.remove(applicationContext, entry.messageId, entry.toUserId)
                                deleteBatchTables(entry.messageId)
                            }
                        }

                        // One at a time, oldest first, not the whole backlog at once.
                        //
                        // The idea is sound: an allReceived proves this peer is awake,
                        // so it is the right moment to clear what is owed to it. What
                        // was wrong was the quantity. Every entry became its own
                        // dispatch, launched in the same instant, all competing for
                        // the one link to the same phone; on 20 Aug four went out
                        // together and all four came back "Job was cancelled", each
                        // then firing its own pending FCM. Six stale messages meant a
                        // burst of six announcements, and on 21 Aug the receiver of
                        // such bursts took two hours to collect a one line text.
                        //
                        // Serialising costs nothing, because this very handler is
                        // what runs next: the one dispatch completes, its allReceived
                        // comes back here, and the following entry goes out. The
                        // backlog still drains, in a queue instead of a heap, and a
                        // link carrying one transfer finishes it sooner than a link
                        // carrying six. If the chain breaks because a dispatch fails,
                        // nothing is lost either: PendingRetryWorker keeps its own
                        // ladder over the same entries.
                        val stalePending = PendingMessageTracker.getPendingForPeer(applicationContext, fromUserId)
                        val nextPending = stalePending.minByOrNull { it.createdAt }
                        if (nextPending != null) {
                            debugLine(tag, "Piggyback: ${stalePending.size} stale for $fromUserId, dispatching the oldest")
                            debugLine(tag, "Piggyback dispatching: ${nextPending.messageId} → $fromUserId")
                            submitDispatchWorker(
                                messageId = nextPending.messageId,
                                toUserId = fromUserId,
                                context = applicationContext,
                                // messageKey is stored as "id#chatGroupId" for
                                // group content, so the suffix comes off here.
                                groupId = nextPending.messageKey.substringBefore("#"),
                                chatGroupId = nextPending.chatGroupId ?: "",
                                originalSenderId = nextPending.originalSenderId ?: "",
                                messageDate = nextPending.messageDate
                            )
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

                        // Ack AFTER the status write, so it means "processed", and
                        // the member can stop its retry ladder: without this ack a
                        // lost GROUP_SEEN was lost forever. Duplicate GROUP_SEENs
                        // from retries are harmless, the write is idempotent.
                        notifyRemotePeer(fromUserId, messageId, Notify.GROUP_SEEN_ACK)

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

            Notify.GROUP_SEEN_ACK -> {
                // The original sender confirmed it processed our GROUP_SEEN:
                // stop the retry ladder for it. Senders on older versions never
                // send this; their entries die at the retry cap instead.
                debugLine(tag, "Group seen acked by $fromUserId: $channelId")
                channelId?.let { messageId ->
                    GroupSeenTracker.remove(applicationContext, messageId, fromUserId, "acked")
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
                    // Claim before destroying anything, exactly as DataSyncService does.
                    // Without the superseded check the second of two dataCalls arriving
                    // together tears down the connection the first one just built, and
                    // a chunked transfer restarts from scratch every few seconds.
                    ConnectionManager.instance.claimLatestDataChannel(fromUserId, cid)

                    if (ConnectionManager.instance.hasLiveConnection(fromUserId)) {
                        debugLine(tag, "DATA_CALL: already connected to $fromUserId, ignoring.")
                        return@launch
                    }

                    if (ConnectionManager.instance.isSupersededDataChannel(fromUserId, cid)) {
                        debugLine(tag, "DATA_CALL: $cid superseded before cleanup, nothing to do")
                        return@launch
                    }

                    try { ConnectionManager.instance.webRTCCleanUp(fromUserId) } catch (e: Exception) { debugLine(tag, "Ignore: ${e.message}") }

                    if (ConnectionManager.instance.isSupersededDataChannel(fromUserId, cid)) {
                        debugLine(tag, "DATA_CALL: $cid superseded during cleanup, letting the newer one connect")
                        return@launch
                    }

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