package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.crypto.KeyManager
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
import com.bolimot.mindtheclub.works.DispatchWorker
import com.bolimot.mindtheclub.works.submitDispatchWorker
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.receiving.checkIfMessageIsCompleted
import com.bolimot.mindtheclub.receiving.missingChunksByContent
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import kotlinx.coroutines.launch
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
 *   allReceived     the peer has our message: mark it delivered, drop its batch tables
 *   sendMe          the peer asks for a message we announced, whole or a range of chunks
 *   allMissing      nothing of our message arrived: send it all again
 *   someMissing     a range of chunks did not arrive: send those again
 *   Seen            the peer read our message
 *   connectionBusy  the peer refused our dataCall for now
 *
 * Everything else (calls, groups, contact requests, typing) is recorded in the log and
 * otherwise ignored.
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

                // The App also recognises a peer whose contact acquisition is in progress and a
                // member of one of its groups (isRecognisedPeer). Neither exists on the PC yet,
                // so a contact in the database is the whole test.
                if (!getPeerDao(App.context()).exist(remoteUserId)) {
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
                        // The App then caps concurrent senders per content at three
                        // (ConnectionManager.tryAdmitSender, answering connectionBusy). Several
                        // senders for one content only happen in a group, and group content is
                        // refused on the PC, so there is nothing to cap yet.
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

                if (!chatGroupId.isNullOrEmpty()) {
                    debugLine(TAG, "Pending for group $chatGroupId: groups are not on the PC yet, ignoring")
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
                    notifyRemotePeer(fromUserId, msgId, Notify.ALL_RECEIVED, null)
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

                // Remember this message is owed to us. On the phone PendingRetryWorker asks
                // again from this record; that worker is not on the PC yet, so for now the
                // record is kept and the next request waits for the sender's next announcement.
                IncomingPendingTracker.record(context, msgId, fromUserId)

                debugLine(TAG, "1:1 pending, sending sendMe to $fromUserId")
                notifyRemotePeer(fromUserId, msgId, Notify.SEND_ME, missingRange)
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
                // After "#" the App carries a group delivery document id; groups are not on the PC.
                val messageId = rawChannelId.split("#", limit = 2)[0]

                val ackedMessage = getMessageRepository(context).getMessage(messageId)
                if (ackedMessage?.chatGroupId.isNullOrEmpty()) {
                    getMessageRepository(context).updateStatus(messageId, DesktopStrings.delivered())
                }

                deleteBatchTables(messageId)
                PendingMessageTracker.remove(context, messageId, fromUserId)

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
                    // The App guards here against serving one GROUP content several times at
                    // once (same content guard, ContentServeQueue). A 1:1 message has no group
                    // coordinates and skips those guards there too.
                    if (missingItems != null) {
                        debugLine(TAG, "Partial sendMe: re-sending chunks ${missingItems.first()}..${missingItems.last()} of $msgId to $fromUserId")
                        // Normalise first: mark all delivered, then re-enable only the missing
                        // range, or dispatch re-streams the whole remainder as duplicates.
                        restrictBatchToMissing(msgId, missingItems, context)
                        reSendMessage(fromUserId, msgId, missingItems, context)
                    } else {
                        debugLine(TAG, "Batch tables exist for $msgId, dispatching to $fromUserId")
                        submitDispatchWorker(msgId, fromUserId, context)
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
                            // In the App this re-serves a received or group message to a group
                            // member that asks for it (relay). Not on the PC yet.
                            debugLine(TAG, "sendMe for $msgId asks for a relay, which is not on the PC yet")
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

            // The peer refused our dataCall: too many senders for that content right now.
            Notify.CONNECTION_BUSY -> {
                debugLine(TAG, "Remote peer is busy for DATA_CALL, aborting connect attempt")
                val cid = channelId ?: return
                DataLink.peerBusy(fromUserId, cid)
            }

            else -> debugLine(TAG, "Signal $type is not handled on the PC yet")
        }
    }
}
