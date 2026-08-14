package com.bolimot.mindtheclub.receiving

import ProcessedMessageCache
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.image.ImageRepository
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageRepository
import com.bolimot.mindtheclub.database.reaction.ReactionManager
import com.bolimot.mindtheclub.database.video.Video
import com.bolimot.mindtheclub.database.video.VideoRepository
import com.bolimot.mindtheclub.functions.assembleChunksToTempFile
import com.bolimot.mindtheclub.functions.buildMultiImagePreview
import com.bolimot.mindtheclub.functions.computeSha256
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.functions.deleteFile
import com.bolimot.mindtheclub.functions.extractImages
import com.bolimot.mindtheclub.functions.getExistingDownloadUriByHash
import com.bolimot.mindtheclub.functions.getExistingUriByHash
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import com.bolimot.mindtheclub.functions.getImageDao
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getVideoDao
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.functions.saveBitmap
import com.bolimot.mindtheclub.functions.saveFileToPublicDownloads
import com.bolimot.mindtheclub.functions.saveMediaToPublicStorage
import com.bolimot.mindtheclub.notifications.MessageReceivedNotification
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.propagateGroupMessage
import com.bolimot.mindtheclub.sending.saveFirstVideoFrame
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.ActivityStatus
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.SoundManager
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type
import java.io.File

suspend fun receiveReaction(messageId: String, emoji: String) {
    val inboxDao = getInboxDao(App.context())
    val inboxMessage = inboxDao.getMessage(messageId)

    // Reactions carry their target in replyId. Peers on the previous build instead reused the
    // target's own id, which in a group arrives as groupId because the relay re-mints the id per
    // member; both are still accepted so a reaction from an older peer is not lost.
    val targetMessageId = inboxMessage.replyId?.takeIf { it.isNotEmpty() }
        ?: if (inboxMessage.chatGroupId != null && inboxMessage.groupId.isNotEmpty()) {
            inboxMessage.groupId
        } else {
            messageId
        }

    // In a group the relay rewrites fromUserId at every hop, so only originalSenderId identifies
    // who actually reacted.
    val reactorUserId = inboxMessage.originalSenderId?.takeIf { it.isNotEmpty() }
        ?: inboxMessage.fromUserId

    // Stored whether or not the target is here yet. This used to bail out when the
    // reacted-to message was missing from the local database, which threw away far
    // more than the pill: the acknowledgement never left, so the sender kept
    // re-offering the reaction for hours, and the gossip relay never ran, so every
    // member downstream of this one lost it too. It happens routinely to anyone who
    // joined a group after the conversation started (14 Aug, White: reaction
    // received and silently dropped, chunks left orphaned for the recovery worker).
    // The Reaction table has no foreign key to Message, so an orphan row is legal;
    // saveMessage recomputes the pill from these rows when the target finally lands.
    ReactionManager.apply(targetMessageId, reactorUserId, emoji, inboxMessage.date)

    val reactionContentKey = resolveContentKey(inboxDao, messageId)
    inboxDao.deleteByContent(reactionContentKey)

    ProcessedMessageCache.markProcessed(messageId)

    // A reaction is a group-gossip message like any other type: acknowledge with the
    // delivery doc id (so the original sender's re-dispatch path engages) and relay it
    // onward to the remaining group members. Without this, a reaction only reached the
    // members the sender directly fanned out to (GROUP_DISPATCH_FANOUT).
    val deliveryId = inboxMessage.chatGroupId?.let {
        computeDeliveryDocId(it, inboxMessage.originalSenderId ?: "", inboxMessage.date)
    }
    notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

    if (deliveryId != null && inboxMessage.originalSenderId != null
        && inboxMessage.originalSenderId != inboxMessage.fromUserId) {
        notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
    }

    propagateGroupMessage(
        MessageData(
            fromUserId = inboxMessage.chatGroupId ?: (inboxMessage.originalSenderId ?: inboxMessage.fromUserId),
            toUserId = MySelf.userId()!!,
            messageId = messageId,
            replyId = inboxMessage.replyId,
            groupId = inboxMessage.groupId,
            groupSize = inboxMessage.groupSize,
            text = emoji,
            textAttached = inboxMessage.textAttached,
            nameAttached = inboxMessage.nameAttached,
            uri = "",
            type = inboxMessage.type,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = inboxMessage.originalSenderId
        )
    )
}

suspend fun receiveText(messageId: String) {
    debugLine("fullMessageReceivedEvent", "TEXT MESSAGE RECEIVED")

    try{
        val inboxDao = getInboxDao(App.context())
        val messageDao = getMessageDao(App.context())

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if(inboxMessage == null) {
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

        if(message.subType == SubType.REPLY) {
            val originalId = message.replyId

            if(!originalId.isNullOrEmpty()){
                val replyType = getReplyType(originalId, App.context())
                var replyUri = getReplyImage(originalId, replyType ?: "", App.context())

                if (replyUri != null) {
                    if (replyType == Type.MULTIPLE_IMAGES) {
                        replyUri = buildMultiImagePreview(replyUri).toString()
                    } else if (replyType == Type.VIDEO) {
                        replyUri = saveFirstVideoFrame(replyUri, App.context())
                    }
                }
                message.uri = replyUri ?: ""
            }
        }

        if(messageRepository.saveMessage(message, messageIn = true)){
            inboxDao.deleteByContent(contentKey)

            val deliveryId = message.chatGroupId?.let {
                computeDeliveryDocId(it, message.originalSenderId ?: "", message.date)
            }
            notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

            if (deliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
            }

            propagateGroupMessage(MessageData.fromMessage(message).copy(messageId = messageId))

            if(!chatScreenIsInForeground(message.fromUserId)){
                MessageReceivedNotification.show(message)
            } else {
                // Read on screen right now: mark it so a later relay echo or
                // sendMe resend cannot notify a message the user already saw.
                MessageReceivedNotification.markShown(message.messageId)
                SoundManager.playIncoming()
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save text message")
        }
    } catch (e: Exception){
        debugLine("fullMessageReceivedEvent", "Failed to receive text: ${e.message}")
    }
}

private suspend fun getReplyType(replyId: String, context: Context): String? {
    return getMessageRepository(context).getReplyType(replyId)
}

private suspend fun getReplyImage(replyId: String, type: String, context: Context): String? {
    return getMessageRepository(context).getReplyImage(replyId, type)
}

suspend fun receiveImages(messageId: String, content: String, text: String, fromUserId: String) {
    debugLine2("fullMessageReceivedEvent", "MULTIPLE IMAGES MESSAGE RECEIVED: $messageId, uri=$content")

    val context = App.context()

    try{
        val uri = content.toUri()
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)
        val imagesDao  = getImageDao(context)

        val messageRepository = MessageRepository(messageDao)
        val imageRepository = ImageRepository(imagesDao)

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if(inboxMessage == null) {
            debugLine("fullMessageReceivedEvent", "No such multiple images message in Inbox")
            return
        }

        val contentKey = contentKeyOf(inboxMessage)

        val messageKey = inboxMessage.groupId

        val existingMessage = messageRepository.getMessage(messageKey)
        if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
            debugLine("fullMessageReceivedEvent", "Multiple Image message already exists: $messageKey")
            val earlyDeliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, inboxMessage.originalSenderId ?: fromUserId, inboxMessage.date)
            }
            notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            if (earlyDeliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            }
            propagateGroupMessage(MessageData.fromMessage(existingMessage).copy(messageId = messageId, uri = uri.toString()))
            inboxDao.deleteByContent(contentKey)
            if (existingMessage.uri != uri.toString()) {
                deleteFile(uri)
            }
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                MessageReceivedNotification.show(existingMessage)
            }
            return
        }

        val uriList = extractImages(uri)

        if(uriList == null){
            debugLine("fullMessageReceivedEvent", "Failed to extract images")
            return
        }

        debugLine("fullMessageReceivedEvent", "Extracted ${uriList.size} images")

        val uriStringList =uriList.joinToString(",") { it.toString() }
        val inboxOriginalSender = inboxMessage.originalSenderId
        val originalSender = inboxOriginalSender ?: fromUserId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveImages", "Duplicate group message, skipping")
                inboxDao.deleteByContent(contentKey)
                deleteFile(uri)
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
            groupId = messageKey,
            groupSize = inboxMessage.groupSize,
            text = text,
            textAttached = inboxMessage.textAttached,
            nameAttached = inboxMessage.nameAttached,
            uri = uriStringList,
            Type.MULTIPLE_IMAGES,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            status = "",
            reaction = null,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = originalSender
        )

        if(messageRepository.saveMessage(message, messageIn = true)){

            inboxDao.deleteByContent(contentKey)

            for (imageUri in uriList) {
                val tempFile = File(context.cacheDir, "temp_dedup_${imageUri.lastPathSegment}.jpg")
                val copied = context.contentResolver.openInputStream(imageUri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
                if (!copied) continue
                val hash = computeSha256(tempFile.inputStream())
                if (hash == null) { tempFile.delete(); continue }
                val hashFileName = "$hash.jpg"
                val relativePath = "${Environment.DIRECTORY_PICTURES}/MindTheClub"

                var finalImageUri = getExistingUriByHash(context, hashFileName, relativePath, isVideo = false)
                if (finalImageUri == null) {
                    finalImageUri = saveMediaToPublicStorage(context, tempFile, hashFileName, "image/jpeg", isVideo = false)
                    if (finalImageUri == null) continue
                } else {
                    debugLine("receiveImages", "Duplicate image – reusing: $finalImageUri")
                    tempFile.delete()
                }

                val imageOwner = inboxMessage.chatGroupId ?: inboxMessage.fromUserId
                imageRepository.insertImage(Image(0, messageKey, finalImageUri.toString(), inboxMessage.date, "", imageOwner))
            }

            debugLine("fullMessageReceivedEvent", "%% messageId: $messageKey, groupId: ${message.groupId}")

            val deliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, originalSender, inboxMessage.date)
            }

            notifyRemotePeer(fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

            if (deliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
            }

            propagateGroupMessage(MessageData.fromMessage(message).copy(messageId = messageId, uri = uri.toString()))

            if(!chatScreenIsInForeground(message.fromUserId)){
                MessageReceivedNotification.show(message)
            } else {
                // Read on screen right now: mark it so a later relay echo or
                // sendMe resend cannot notify a message the user already saw.
                MessageReceivedNotification.markShown(message.messageId)
                SoundManager.playIncoming()
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save image message")
        }
    } catch (e: Exception){
        debugLine("fullMessageReceivedEvent", "Failed to receive multiple images: Exception: ${e.message}")
    }
}

suspend fun receiveObject(messageId: String, content: String, text: String, fromUserId: String, type: String) {
    debugLine2("fullMessageReceivedEvent", "$type MESSAGE RECEIVED: $messageId, uri=$content")

    val context = App.context()

    try{
        val uri = content.toUri()
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)

        val messageRepository = MessageRepository(messageDao)

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if(inboxMessage == null) {
            debugLine("fullMessageReceivedEvent", "No such $messageId message in Inbox")
            return
        }

        val contentKey = contentKeyOf(inboxMessage)

        val messageKey = if (inboxMessage.chatGroupId != null && inboxMessage.groupId.isNotEmpty())
            inboxMessage.groupId else messageId

        val existingMessage = messageRepository.getMessage(messageKey)
        if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
            debugLine("fullMessageReceivedEvent", "$type message already exists: $messageKey")
            val earlyDeliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, inboxMessage.originalSenderId ?: fromUserId, inboxMessage.date)
            }
            notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            if (earlyDeliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            }
            val propagateData = MessageData.fromMessage(existingMessage).copy(messageId = messageId)
            if (type == Type.WEB) {
                propagateGroupMessage(propagateData.copy(uri = ""))
            } else {
                propagateGroupMessage(propagateData)
            }
            inboxDao.deleteByContent(contentKey)
            if (content.isNotEmpty() && content != existingMessage.uri) deleteFile(content.toUri())
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                MessageReceivedNotification.show(existingMessage)
            }
            return
        }
        val originalSender = inboxMessage.originalSenderId ?: fromUserId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveObject", "Duplicate group message, skipping")
                inboxDao.deleteByContent(contentKey)
                if (content.isNotEmpty()) deleteFile(uri)
                val dupDeliveryId = computeDeliveryDocId(inboxMessage.chatGroupId,
                    inboxMessage.originalSenderId, inboxMessage.date)
                notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                if (inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                    notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                }
                return
            }
        }

        val finalUri: String = if (isFileType(type)) {
            val fileDetail = getFileDetailFromType(type)
            val extension = fileDetail.getOrNull(1) ?: ""
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

            val tempFile = if (content.isNotEmpty()) {
                File(content.toUri().path ?: "")
            } else {
                val assembled = assembleChunksToTempFile(context, inboxDao, messageId, "temp_${messageKey}.bin")
                if (assembled == null) {
                    debugLine("receiveObject", "Failed to assemble file from chunks")
                    return
                }
                assembled
            }

            val hash = computeSha256(tempFile.inputStream()) ?: run {
                debugLine("receiveObject", "Failed to compute hash for file")
                if (content.isEmpty()) tempFile.delete()
                return
            }
            val hashFileName = "$hash.$extension"
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/MindTheClub"

            var finalUri = getExistingDownloadUriByHash(context, hashFileName, relativePath)

            if (finalUri == null) {
                finalUri = saveFileToPublicDownloads(context, tempFile, hashFileName, mimeType)
                if (finalUri == null) {
                    debugLine("receiveObject", "Failed to save file to Downloads")
                    if (content.isEmpty()) tempFile.delete()
                    return
                }
            } else {
                debugLine("receiveObject", "Duplicate file detected – reusing: $finalUri")
            }

            if (content.isEmpty()) {
                tempFile.delete()
            } else if (finalUri.toString() != content) {
                File(content.toUri().path!!).delete()
            }

            finalUri.toString()
        } else {
            content
        }

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
            uri = finalUri,
            type,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            status = "",
            reaction = null,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = originalSender
        )

        if(messageRepository.saveMessage(message, messageIn = true)){
            inboxDao.deleteByContent(contentKey)

            debugLine("fullMessageReceivedEvent", "%% messageId: $messageKey, groupId: ${message.groupId}")
            val deliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, originalSender, inboxMessage.date)
            }
            notifyRemotePeer(fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

            if (deliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
            }

            val propagateData = MessageData.fromMessage(message).copy(messageId = messageId)
            if (type == Type.WEB) {
                propagateGroupMessage(propagateData.copy(uri = ""))
            } else {
                propagateGroupMessage(propagateData)
            }

            if(!chatScreenIsInForeground(message.fromUserId)){
                MessageReceivedNotification.show(message)
            } else {
                // Read on screen right now: mark it so a later relay echo or
                // sendMe resend cannot notify a message the user already saw.
                MessageReceivedNotification.markShown(message.messageId)
                SoundManager.playIncoming()
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save $type message")
        }
    } catch (e: Exception){
        debugLine("fullMessageReceivedEvent", "Failed to receive $type: Exception: ${e.message}")
    }
}

suspend fun receiveVideo(messageId: String, content: String, text: String, fromUserId: String) {
    debugLine2("fullMessageReceivedEvent", "VIDEO MESSAGE RECEIVED: $messageId, uri=$content")

    val context = App.context()

    try{
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)
        val videoDao  = getVideoDao(context)

        val messageRepository = MessageRepository(messageDao)
        val videoRepository = VideoRepository(videoDao)

        val inboxMessage = inboxDao.getFirstMessage(messageId)



        if(inboxMessage == null) {
            debugLine("fullMessageReceivedEvent", "No such Video message in Inbox")
            return
        }

        val contentKey = contentKeyOf(inboxMessage)

        val messageKey = if (inboxMessage.chatGroupId != null && inboxMessage.groupId.isNotEmpty())
            inboxMessage.groupId else messageId

        val existingMessage = messageRepository.getMessage(messageKey)
        if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
            debugLine("fullMessageReceivedEvent", "Video message already exists: $messageKey")
            val earlyDeliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, inboxMessage.originalSenderId ?: fromUserId, inboxMessage.date)
            }
            notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            if (earlyDeliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, earlyDeliveryId)
            }
            propagateGroupMessage(MessageData.fromMessage(existingMessage).copy(messageId = messageId))
            inboxDao.deleteByContent(contentKey)
            if (content.isNotEmpty()) deleteFile(content.toUri())
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                MessageReceivedNotification.show(existingMessage)
            }
            return
        }

        val originalSender = inboxMessage.originalSenderId ?: fromUserId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveVideo", "Duplicate group message, skipping")
                inboxDao.deleteByContent(contentKey)
                if (content.isNotEmpty()) deleteFile(content.toUri())
                val dupDeliveryId = computeDeliveryDocId(inboxMessage.chatGroupId,
                    inboxMessage.originalSenderId, inboxMessage.date)
                notifyRemotePeer(inboxMessage.fromUserId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                if (inboxMessage.originalSenderId != inboxMessage.fromUserId) {
                    notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, dupDeliveryId)
                }
                return
            }
        }

        val tempFile = if (content.isNotEmpty()) {
            File(content.toUri().path!!)
        } else {
            val assembled = assembleChunksToTempFile(context, inboxDao, messageId, "temp_${messageKey}.mp4")
            if (assembled == null) {
                debugLine("receiveVideo", "Failed to assemble video from chunks")
                return
            }
            assembled
        }

        val hash = computeSha256(tempFile.inputStream()) ?: run {
            debugLine("receiveVideo", "Failed to compute hash for video")
            if (content.isEmpty()) tempFile.delete()
            return
        }
        val hashFileName = "$hash.mp4"
        val relativePath = "${Environment.DIRECTORY_MOVIES}/MindTheClub"

        var finalUri = getExistingUriByHash(context, hashFileName, relativePath, isVideo = true)

        if (finalUri == null) {
            finalUri = saveMediaToPublicStorage(context, tempFile, hashFileName, "video/mp4", isVideo = true)
            if (finalUri == null) {
                debugLine("receiveVideo", "Failed to save video to public storage")
                if (content.isEmpty()) tempFile.delete()
                return
            }
        } else {
            debugLine("receiveVideo", "Duplicate video detected – reusing: $finalUri")
        }

        if (content.isEmpty()) {
            tempFile.delete()
        } else if (finalUri.toString() != content) {
            File(content.toUri().path!!).delete()
        }

        val videoPath = finalUri.toString()

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
            uri = videoPath,
            Type.VIDEO,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            status = "",
            reaction = null,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = originalSender
        )

        if(messageRepository.saveMessage(message, messageIn = true)){
            propagateGroupMessage(MessageData.fromMessage(message).copy(messageId = messageId))

            inboxDao.deleteByContent(contentKey)

            if (content.isNotEmpty() && videoPath != content) deleteFile(content.toUri())

            val videoOwner = inboxMessage.chatGroupId ?: inboxMessage.fromUserId
            videoRepository.insertImage(Video(0, messageKey, videoPath, inboxMessage.date, "", videoOwner))

            debugLine("fullMessageReceivedEvent", "%% messageId: $messageKey, groupId: ${message.groupId}")
            val deliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, originalSender, inboxMessage.date)
            }
            notifyRemotePeer(fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

            if (deliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
            }

            if(!chatScreenIsInForeground(message.fromUserId)){
                MessageReceivedNotification.show(message)
            } else {
                // Read on screen right now: mark it so a later relay echo or
                // sendMe resend cannot notify a message the user already saw.
                MessageReceivedNotification.markShown(message.messageId)
                SoundManager.playIncoming()
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save video message")
        }
    } catch (e: Exception){
        debugLine("fullMessageReceivedEvent", "Failed to receive video: Exception: ${e.message}")
    }
}


suspend fun receiveImage(messageId: String, content: String, text: String, fromUserId: String) {
    debugLine2("fullMessageReceivedEvent", "IMAGE MESSAGE RECEIVED: $messageId")

    try {
        val context = App.context()
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)
        val imagesDao = getImageDao(context)

        val messageRepository = MessageRepository(messageDao)
        val imageRepository = ImageRepository(imagesDao)

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
            propagateGroupMessage(MessageData.fromMessage(existingMessage).copy(messageId = messageId))
            inboxDao.deleteByContent(contentKey)
            if (content.isNotEmpty()) deleteFile(content.toUri())
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                MessageReceivedNotification.show(existingMessage)
            }
            return
        }

        val originalSender = inboxMessage.originalSenderId ?: fromUserId

        // ---- HASH‑BASED DEDUPLICATION START ----
        val tempFile = if (content.isNotEmpty()) {
            File(content.toUri().path!!)
        } else {
            val assembled = assembleChunksToTempFile(context, inboxDao, messageId, "temp_${messageKey}.tmp")
            if (assembled == null) {
                debugLine("receiveImage", "Failed to assemble image from chunks")
                return
            }
            assembled
        }

        val hash = computeSha256(tempFile.inputStream()) ?: run {
            debugLine("receiveImage", "Failed to compute hash for image")
            if (content.isEmpty()) tempFile.delete()
            return
        }
        val extension = "jpg" // or detect from mime type
        val hashFileName = "$hash.$extension"
        val relativePath = "${Environment.DIRECTORY_PICTURES}/MindTheClub"

        var finalUri = getExistingUriByHash(context, hashFileName, relativePath, isVideo = false)

        if (finalUri == null) {
            // No duplicate – save new file under hash name
            finalUri = saveMediaToPublicStorage(context, tempFile, hashFileName, "image/jpeg", isVideo = false)
            if (finalUri == null) {
                debugLine("receiveImage", "Failed to save image to public storage")
                if (content.isEmpty()) tempFile.delete()
                return
            }
        } else {
            debugLine("receiveImage", "Duplicate image detected – reusing existing file: $finalUri")
        }

// Clean up temporary file if it was assembled
        if (content.isEmpty()) {
            tempFile.delete()
        } else if (finalUri.toString() != content) {
            // Original temp file not needed anymore
            File(content.toUri().path!!).delete()
        }

        val picturePath = finalUri.toString()

        val previewFileName = "${messageKey}preview.jpg"
        try {
            context.contentResolver.openInputStream(finalUri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    saveBitmap(bitmap, previewFileName, 30)
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) { }

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

            propagateGroupMessage(MessageData.fromMessage(message).copy(messageId = messageId))

            inboxDao.deleteByContent(contentKey)

            val imageOwner = inboxMessage.chatGroupId ?: inboxMessage.fromUserId
            imageRepository.insertImage(Image(0, messageKey, picturePath, inboxMessage.date, "", imageOwner))

            val deliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, originalSender, inboxMessage.date)
            }
            notifyRemotePeer(fromUserId, messageId, Notify.ALL_RECEIVED, deliveryId)

            if (deliveryId != null && inboxMessage.originalSenderId != null
                && inboxMessage.originalSenderId != fromUserId) {
                notifyRemotePeer(inboxMessage.originalSenderId, messageId, Notify.ALL_RECEIVED, deliveryId)
            }

            debugLine("fullMessageReceivedEvent", "Saved message: $messageKey")

            if(!chatScreenIsInForeground(message.fromUserId)){
                MessageReceivedNotification.show(message)
            } else {
                // Read on screen right now: mark it so a later relay echo or
                // sendMe resend cannot notify a message the user already saw.
                MessageReceivedNotification.markShown(message.messageId)
                SoundManager.playIncoming()
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save image message")
        }

    } catch (e: Exception) {
        debugLine("fullMessageReceivedEvent", "Failed to receive image: ${e.message}")
        e.printStackTrace()
    }
}

fun chatScreenIsInForeground(fromUserId: String): Boolean {
    return ActivityStatus.inForeground(ChatScreen::class.java.name, "userId", fromUserId)
}




