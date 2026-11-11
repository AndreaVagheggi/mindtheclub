package com.bolimot.mindtheclub.receiving

// DESKTOP: this file is NOT a copy of the App's receiveManager.kt, which is a thousand lines
// and mostly media: images, albums, video, files, each saved through Android's MediaStore.
// receiveText and receiveImage are here. They keep the App's statements in the App's order, with
// these differences:
//
//   - the notification, the sound and the "is this chat on screen" check go through
//     DesktopEvents instead of the Android classes;
//   - receiveImage keeps the picture in the PC's media folder (desktop/media/MediaFiles.kt)
//     where the App saves it to the phone's public Pictures folder through MediaStore. Same
//     name, the SHA-256 of the bytes, and the same reuse of a file already there. The small
//     "preview" copy the App also writes (for the quote shown when a text replies to a
//     picture) is not made: replies are not on the PC yet;
//   - a reply to an album or to a video gets no preview image (the App builds one from the
//     media, which are not on the PC); a reply to a single image keeps the uri it finds;
//   - propagateGroupMessage is not called: a group text never reaches this function on the PC
//     (receiveData refuses group content), and for a 1:1 message the App's call does nothing.

import ProcessedMessageCache
import android.content.Context
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageRepository
import com.bolimot.mindtheclub.desktop.engine.DesktopEvents
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.functions.assembleChunksToTempFile
import com.bolimot.mindtheclub.functions.computeSha256
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.functions.getImageDao
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.markContentComplete
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type

// A RECEIVING placeholder left behind by a copy that was then discarded as a duplicate. Remove
// it, and only it: a row that is not RECEIVING is a real message and is never touched.
private suspend fun dropOrphanPlaceholder(messageKey: String) {
    try {
        val repository = getMessageRepository(App.context())
        val orphan = repository.getMessage(messageKey) ?: return
        if (orphan.status != Status.RECEIVING) return
        repository.deleteMessages(listOf(orphan))
        debugLine("receivedEvent", "Dropped orphan placeholder $messageKey left by a discarded copy")
    } catch (e: Exception) {
        debugLine("receivedEvent", "Orphan placeholder cleanup failed for $messageKey: ${e.message}")
    }
}

suspend fun receiveText(messageId: String) {
    debugLine("fullMessageReceivedEvent", "TEXT MESSAGE RECEIVED")

    try {
        val inboxDao = getInboxDao(App.context())
        val messageDao = getMessageDao(App.context())

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if (inboxMessage == null) {
            debugLine("fullMessageReceivedEvent", "No such a text message")
            return
        }

        val contentKey = contentKeyOf(inboxMessage)

        val messageRepository = MessageRepository(messageDao)

        val originalSender = inboxMessage.originalSenderId ?: inboxMessage.fromUserId

        val messageKey = if (inboxMessage.chatGroupId != null && inboxMessage.groupId.isNotEmpty())
            inboxMessage.groupId else inboxMessage.messageId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveText", "Duplicate group message, skipping")
                inboxDao.deleteByContent(contentKey)
                dropOrphanPlaceholder(messageKey)
                ProcessedMessageCache.markProcessed(messageId)
                val dupDeliveryId = computeDeliveryDocId(inboxMessage.chatGroupId,
                    inboxMessage.originalSenderId, inboxMessage.date)
                notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                if (inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                    notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                }
                return
            }
        }

        val message = Message(
            uid = 0,
            fromUserId = inboxMessage.chatGroupId ?: originalSender,
            toUserId = MySelf.userId()!!,
            messageId = messageKey,
            replyId = inboxMessage.replyId,
            groupId = inboxMessage.groupId,
            groupSize = inboxMessage.groupSize,
            text = inboxMessage.text,
            textAttached = inboxMessage.textAttached,
            nameAttached = inboxMessage.nameAttached,
            uri = "",
            type = Type.TEXT,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            status = "",
            reaction = null,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = inboxMessage.originalSenderId
        )

        if (message.subType == SubType.REPLY) {
            val originalId = message.replyId

            if (!originalId.isNullOrEmpty()) {
                val replyType = getReplyType(originalId, App.context())
                var replyUri = getReplyImage(originalId, replyType ?: "", App.context())

                if (replyUri != null) {
                    // DESKTOP: no preview is built for an album or a video, see the top of the file.
                    if (replyType == Type.MULTIPLE_IMAGES || replyType == Type.VIDEO) {
                        replyUri = null
                    }
                }
                message.uri = replyUri ?: ""
            }
        }

        if (messageRepository.saveMessage(message, messageIn = true)) {
            inboxDao.deleteByContent(contentKey)
            // The chunks are gone: without this a completed signal naming the id finds nothing,
            // answers allMissing and triggers a full resend of a message that was just saved.
            ProcessedMessageCache.markProcessed(messageId)

            val deliveryId = message.chatGroupId?.let {
                computeDeliveryDocId(it, message.originalSenderId ?: "", message.date)
            }
            markContentComplete(deliveryId)
            notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

            if (deliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
            }

            // DESKTOP: propagateGroupMessage not called, see the top of the file.

            if (!chatScreenIsInForeground(message.fromUserId)) {
                DesktopEvents.notifyMessage(message)
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save text message")
        }
    } catch (e: Exception) {
        debugLine("fullMessageReceivedEvent", "Failed to receive text: ${e.message}")
    }
}

private suspend fun getReplyType(replyId: String, context: Context): String? {
    return getMessageRepository(context).getReplyType(replyId)
}

private suspend fun getReplyImage(replyId: String, type: String, context: Context): String? {
    return getMessageRepository(context).getReplyImage(replyId, type)
}

// DESKTOP: [content] is kept for the App's signature. There it can carry a file already
// assembled by getFullMessage; on the PC, as in the App's current receive path, it is always
// empty and the picture is assembled here from its chunks.
suspend fun receiveImage(messageId: String, content: String, text: String, fromUserId: String) {
    debugLine2("fullMessageReceivedEvent", "IMAGE MESSAGE RECEIVED: $messageId")

    try {
        val context = App.context()
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)
        val imagesDao = getImageDao(context)

        val messageRepository = MessageRepository(messageDao)

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if (inboxMessage == null) {
            debugLine("fullMessageReceivedEvent", "No such image message in Inbox")
            return
        }

        val contentKey = contentKeyOf(inboxMessage)

        val messageKey = if (inboxMessage.chatGroupId != null && inboxMessage.groupId.isNotEmpty())
            inboxMessage.groupId else messageId

        val existingMessage = messageRepository.getMessage(messageKey)

        if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
            debugLine("fullMessageReceivedEvent", "Image message already exists: $messageKey")
            val earlyDeliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, inboxMessage.originalSenderId ?: fromUserId, inboxMessage.date)
            }
            notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            if (earlyDeliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            }
            // DESKTOP: propagateGroupMessage not called, see the top of the file.
            inboxDao.deleteByContent(contentKey)
            ProcessedMessageCache.markProcessed(messageId)
            return
        }

        val originalSender = inboxMessage.originalSenderId ?: fromUserId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveImage", "Duplicate group message, skipping")
                inboxDao.deleteByContent(contentKey)
                dropOrphanPlaceholder(messageKey)
                ProcessedMessageCache.markProcessed(messageId)
                val dupDeliveryId = computeDeliveryDocId(inboxMessage.chatGroupId,
                    inboxMessage.originalSenderId, inboxMessage.date)
                notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                if (inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                    notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                }
                return
            }
        }

        // ---- dedup by hash, start ----
        // Per copy name, as in the App.
        val tempFile = assembleChunksToTempFile(context, inboxDao, messageId, "temp_${messageId}.tmp")
        if (tempFile == null) {
            debugLine("receiveImage", "Failed to assemble image from chunks")
            return
        }

        val hash = computeSha256(tempFile.inputStream()) ?: run {
            debugLine("receiveImage", "Failed to compute hash for image")
            tempFile.delete()
            return
        }
        val extension = "jpg"
        val hashFileName = "$hash.$extension"

        // DESKTOP: the PC's media folder in place of MediaStore. keep() reuses a file already
        // there under this name, which is the App's "duplicate image detected" branch.
        val finalFile = try {
            MediaFiles.keep(tempFile, hashFileName)
        } catch (e: Exception) {
            debugLine("receiveImage", "Failed to save image to the media folder: ${e.message}")
            tempFile.delete()
            return
        }

        val picturePath = finalFile.toURI().toString()

        val message = Message(
            uid = 0,
            fromUserId = inboxMessage.chatGroupId ?: originalSender,
            toUserId = MySelf.userId()!!,
            messageId = messageKey,
            replyId = inboxMessage.replyId,
            groupId = inboxMessage.groupId,
            groupSize = inboxMessage.groupSize,
            text = text,
            textAttached = inboxMessage.textAttached,
            nameAttached = inboxMessage.nameAttached,
            uri = picturePath,
            type = Type.IMAGE,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            status = "",
            reaction = null,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = originalSender
        )

        if (messageRepository.saveMessage(message, messageIn = true)) {

            // DESKTOP: propagateGroupMessage not called, see the top of the file.

            inboxDao.deleteByContent(contentKey)
            ProcessedMessageCache.markProcessed(messageId)

            // ImageRepository.insertImage in the App: one row per message and file.
            val imageOwner = inboxMessage.chatGroupId ?: inboxMessage.fromUserId
            if (imagesDao.countByFilename(hashFileName, imageOwner, messageKey) == 0) {
                imagesDao.insert(Image(0, messageKey, picturePath, inboxMessage.date, "", imageOwner))
            }

            val deliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, originalSender, inboxMessage.date)
            }
            markContentComplete(deliveryId)
            notifyRemotePeer(fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

            if (deliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
            }

            debugLine("fullMessageReceivedEvent", "Saved message: $messageKey")

            if (!chatScreenIsInForeground(message.fromUserId)) {
                DesktopEvents.notifyMessage(message)
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save image message")
        }

    } catch (e: Exception) {
        debugLine("fullMessageReceivedEvent", "Failed to receive image: ${e.message}")
    }
}

fun chatScreenIsInForeground(fromUserId: String): Boolean {
    return DesktopEvents.chatOnScreen(fromUserId)
}
