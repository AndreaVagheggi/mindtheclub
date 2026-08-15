package com.bolimot.mindtheclub.receiving

import ProcessedMessageCache
import android.net.Uri
import android.util.Base64
import com.bolimot.mindtheclub.contactAcquisition.receiveProfile
import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MessageNotifier
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.resolveContentKey
import java.util.Collections

private val activeProcessing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

// Claimed by CONTENT identity, not by copy. activeProcessing above only stops the
// same copy from entering twice; two different copies of the same group content
// have different messageIds, slipped past it, and assembled the same temp file in
// parallel (Romy, 15 Aug: one copy saved a truncated image, "Media non
// disponibile" in the chat with corrupted twins in the gallery). The loser of
// this claim returns before reading or writing anything, with its chunks and
// placeholder intact: the recovery worker retries it later, when the winner has
// saved, and the duplicate guards then discard it cleanly.
private val activeContent: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

fun isProcessingActive(messageId: String): Boolean = messageId in activeProcessing


suspend fun fullMessageReceivedEvent(messageId: String): Boolean {
if (!activeProcessing.add(messageId)) {
    debugLine("receivedEvent", "Already processing $messageId, skipping")
    return false
}
var claimedContent: String? = null
try {
    val inboxDao: InboxDao = getInboxDao(App.context())

    // For one to one traffic the content key IS the messageId and activeProcessing
    // already covers it; claiming is only needed where distinct copies exist.
    val raceContentKey = resolveContentKey(inboxDao, messageId)
    if (raceContentKey != messageId) {
        if (!activeContent.add(raceContentKey)) {
            debugLine("receivedEvent", "Another copy of $raceContentKey is already being assembled, skipping $messageId")
            return false
        }
        claimedContent = raceContentKey
    }

    val fromUserId = inboxDao.getMessage(messageId).fromUserId

    if (getBlockedUserRepository(App.context()).isBlocked(fromUserId)) {
        debugLine("receivedEvent", "Blocked user $fromUserId, discarding completed message")
        val blockedContentKey = resolveContentKey(inboxDao, messageId)
        inboxDao.deleteByContent(blockedContentKey)
        return false
    }

    val message = inboxDao.getMessage(messageId)
    val type = message.type
    val text = message.text

    // A group relay echo of a message I originally sent. Observed on 12 Aug:
    // Giovanni's own text came back through the Family group, saveMessage said
    // "already exists" but returned true, and receiveText notified him about his
    // own message. Acknowledge the relayer so its retry loop stops, drop the
    // chunks, and never reach the save or notify paths. Scoped to group messages
    // (chatGroupId set), so 1:1 traffic and the note-to-self pseudo peer, which
    // uses its own id, are untouched.
    val echoGroupId = message.chatGroupId
    val echoSenderId = message.originalSenderId
    if (echoGroupId != null && !echoSenderId.isNullOrEmpty() && echoSenderId == MySelf.userId()) {
        debugLine("receivedEvent", "Group echo of my own message $messageId, acking and discarding")
        val echoDeliveryId = computeDeliveryDocId(echoGroupId, echoSenderId, message.date)
        notifyRemotePeer(message.fromUserId, messageId, Notify.ALL_RECEIVED, echoDeliveryId)
        val echoContentKey = resolveContentKey(inboxDao, messageId)
        inboxDao.deleteByContent(echoContentKey)
        // A completed FCM for this echo may still arrive: answer it from the cache,
        // not with an allMissing that would make the relayer resend the echo.
        ProcessedMessageCache.markProcessed(messageId)
        return false
    }

    if (type == Type.VIDEO || type == Type.IMAGE || isFileType(type)) {
        debugLine("receivedEvent", "Message received, type = $type")
        MessageNotifier.notifyFromUserId(fromUserId, messageId)
        when (type) {
            Type.VIDEO -> receiveVideo(messageId, "", text, fromUserId)
            Type.IMAGE -> receiveImage(messageId, "", text, fromUserId)
            else -> receiveObject(messageId, "", text, fromUserId, type)
        }
        stopRedundantSenders(message)
        return true
    }

    val content = getFullMessage(message, inboxDao, type)

    if(content.isNullOrEmpty()) {
        debugLine("receivedEvent", "CRITICAL Received event, content is NULL")

        val nullContentKey = resolveContentKey(inboxDao, messageId)
        inboxDao.deleteByContent(nullContentKey)
        notifyRemotePeer(message.fromUserId, message.messageId, Notify.ALL_MISSING)

        return false
    }

    debugLine("receivedEvent", "Message received, type = $type")

    MessageNotifier.notifyFromUserId(fromUserId, messageId)

    when(type) {
        Type.PROFILE -> receiveProfile(text, content, message)
        Type.TEXT -> receiveText(messageId)
        Type.MULTIPLE_IMAGES -> receiveImages(messageId, content, text, fromUserId)
        Type.REACTION -> receiveReaction(messageId, text)
        Type.STICKER, Type.GIF, Type.WEB, Type.AUDIO, Type.CONTACT -> receiveObject(messageId, content, text, fromUserId, type)

        else -> {
            if(isFileType(type)){
                receiveObject(messageId, content, text, fromUserId, type)
            } else {
                debugLine("receivedEvent", "Unknown message type: $type")
            }
        }
    }
    stopRedundantSenders(message)
    return true
    } finally {
        activeProcessing.remove(messageId)
        claimedContent?.let { activeContent.remove(it) }
    }
}

/**
 * Early stop for every admitted sender of this content beyond the one whose
 * copy just completed. Without it, a member answering an earlier sendMe kept
 * transmitting the whole message to the very end and only then learnt it was
 * wasted (13 Aug: a completed 78 chunk photo arrived in full a second time ten
 * minutes later, over the same pipe a 1017 chunk photo was fighting for).
 *
 * Addressed with the message KEY (the group original id), because a sendMe
 * redispatch names its batch tables after that key, and the allReceived
 * handler deletes tables by the id it receives. The sender that completed and
 * the original sender are excluded: the receive paths already acknowledge
 * both.
 */
private fun stopRedundantSenders(inboxMessage: Inbox) {
    try {
        val contentKey = contentKeyOf(inboxMessage)
        val others = ConnectionManager.instance.admittedSendersFor(contentKey)
            .filter { it != inboxMessage.fromUserId && it != inboxMessage.originalSenderId }
        if (others.isEmpty()) return

        val messageKey = if (inboxMessage.chatGroupId != null && inboxMessage.groupId.isNotEmpty())
            inboxMessage.groupId else inboxMessage.messageId
        val deliveryId = inboxMessage.chatGroupId?.let {
            computeDeliveryDocId(it, inboxMessage.originalSenderId ?: inboxMessage.fromUserId, inboxMessage.date)
        }
        for (senderId in others) {
            debugLine("receivedEvent", "Content complete, early allReceived to redundant sender $senderId for $messageKey")
            notifyRemotePeer(senderId, messageKey, Notify.ALL_RECEIVED, deliveryId)
        }
    } catch (e: Exception) {
        debugLine("receivedEvent", "Redundant sender stop failed: ${e.message}")
    }
}

suspend fun getFullMessage(message: Inbox, inboxDao: InboxDao, type: String): String? {
    val messageId = message.messageId
    val totalChunks = message.totalNo
    val context = App.context()
    val contentKey = contentKeyOf(message)

    if (totalChunks == 1) {
        val firstChunk = inboxDao.getChunkContentByContent(contentKey, 1)
        if (firstChunk != null && firstChunk.startsWith("http", ignoreCase = true)) {
            return firstChunk
        }
    }

    val fileName: String = if (type == "image") {
        "${messageId}_temp.jpg"
    } else if (!isFileType(type)) {
        "full${messageId}.dat"
    } else {
        val fileDetail = getFileDetailFromType(type)
        fileDetail[0]
    }

    val file = File(context.filesDir, fileName)

    val success = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(file).use { fileOutputStream ->
                for (i in 1..totalChunks) {
                    val chunkContent = inboxDao.getChunkContentByContent(contentKey, i)

                    if (chunkContent != null) {
                        val decodedChunk = Base64.decode(chunkContent, Base64.DEFAULT)
                        fileOutputStream.write(decodedChunk)
                    } else {
                        debugLine("getFullMessage", "CRITICAL ERROR: Chunk $i is missing from DB!")
                        return@use false
                    }
                }
                return@use true
            }
        } catch (e: Exception) {
            debugLine("getFullMessage", "Error writing file: ${e.message}")
            return@withContext false
        }
    }

    if (!success) return null

    return Uri.fromFile(file).toString()
}

suspend fun addIncomingContact(profileData: Peer): Boolean {
    try {
        return getPeerViewModel().addOrUpdatePeer(profileData)
    } catch (e: Exception) {
        debugLine("addIncomingContact", e.toString())
        return false
    }
}