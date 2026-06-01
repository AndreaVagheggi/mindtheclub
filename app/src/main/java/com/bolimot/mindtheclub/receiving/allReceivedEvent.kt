package com.bolimot.mindtheclub.receiving

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
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MessageNotifier
import com.bolimot.mindtheclub.tools.Notify
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

fun isProcessingActive(messageId: String): Boolean = messageId in activeProcessing


suspend fun fullMessageReceivedEvent(messageId: String): Boolean {
if (!activeProcessing.add(messageId)) {
    debugLine("receivedEvent", "Already processing $messageId, skipping")
    return false
}
try {
    val inboxDao: InboxDao = getInboxDao(App.context())
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

    if (type == Type.VIDEO || type == Type.IMAGE || isFileType(type)) {
        debugLine("receivedEvent", "Message received, type = $type")
        MessageNotifier.notifyFromUserId(fromUserId, messageId)
        when (type) {
            Type.VIDEO -> receiveVideo(messageId, "", text, fromUserId)
            Type.IMAGE -> receiveImage(messageId, "", text, fromUserId)
            else -> receiveObject(messageId, "", text, fromUserId, type)
        }
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
    return true
    } finally {
        activeProcessing.remove(messageId)
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