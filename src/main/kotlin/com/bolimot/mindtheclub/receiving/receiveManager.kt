package com.bolimot.mindtheclub.receiving

// DESKTOP: this file is NOT a copy of the App's receiveManager.kt, which is a thousand lines
// and mostly media: images, albums, video, files, each saved through Android's MediaStore.
// receiveReaction, receiveText, receiveImage, receiveImages (albums), receiveVideo and
// receiveObject (for a file, a contact and a voice message) are here. They keep the App's
// statements in the App's order, with these differences:
//
//   - the notification, the sound and the "is this chat on screen" check go through
//     DesktopEvents instead of the Android classes;
//   - receiveImage and receiveImages keep the pictures in the PC's media folder
//     (desktop/media/MediaFiles.kt) where the App saves them to the phone's public Pictures
//     folder through MediaStore. Same name, the SHA-256 of the bytes, and the same reuse of a
//     file already there;
//   - the preview of a reply to an album or to a video is made by QuotedPictures, as the App's
//     buildMultiImagePreview and saveFirstVideoFrame make it;
//   - receiveImages relays an album rebuilt from its saved pictures (MessageData.fromMessage,
//     Albums.merge) where the App relays the file it arrived as: the same bytes.

import ProcessedMessageCache
import android.content.Context
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageRepository
import com.bolimot.mindtheclub.database.reaction.ReactionManager
import com.bolimot.mindtheclub.database.video.Video
import com.bolimot.mindtheclub.desktop.engine.DesktopEvents
import com.bolimot.mindtheclub.desktop.media.Albums
import com.bolimot.mindtheclub.desktop.media.AnimatedPictures
import com.bolimot.mindtheclub.desktop.media.AudioFiles
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.desktop.media.QuotedPictures
import com.bolimot.mindtheclub.functions.assembleChunksToTempFile
import com.bolimot.mindtheclub.functions.computeSha256
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import com.bolimot.mindtheclub.functions.getImageDao
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getVideoDao
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.markContentComplete
import com.bolimot.mindtheclub.sending.propagateGroupMessage
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type
import java.io.File

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

suspend fun receiveReaction(messageId: String, emoji: String) {
    val inboxDao = getInboxDao(App.context())
    val inboxMessage = inboxDao.getMessage(messageId)
    if (inboxMessage == null) {
        debugLine("receiveReaction", "Inbox rows for $messageId are gone, dropping the reaction")
        return
    }

    // Reactions carry their target in replyId. Peers on the previous build reused the
    // target's own id, which in a group arrives as groupId because the relay re-mints per
    // member; both are still accepted so a reaction from an older peer is not lost.
    val targetMessageId = inboxMessage.replyId?.takeIf { it.isNotEmpty() }
        ?: if (inboxMessage.chatGroupId != null && inboxMessage.groupId.isNotEmpty()) {
            inboxMessage.groupId
        } else {
            messageId
        }

    // In a group the relay rewrites fromUserId at every hop, so only originalSenderId says
    // who actually reacted.
    val reactorUserId = inboxMessage.originalSenderId?.takeIf { it.isNotEmpty() }
        ?: inboxMessage.fromUserId

    // Stored whether or not the target is here yet (see the App's file): the Reaction table has
    // no foreign key to Message, so an orphan row is legal; saveMessage recomputes the pill when
    // the target lands.
    ReactionManager.apply(targetMessageId, reactorUserId, emoji, inboxMessage.date)

    val reactionContentKey = resolveContentKey(inboxDao, messageId)
    inboxDao.deleteByContent(reactionContentKey)

    ProcessedMessageCache.markProcessed(messageId)

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
                    // DESKTOP: buildMultiImagePreview and saveFirstVideoFrame are QuotedPictures
                    // on the PC; null when the answered album or video is not on this PC.
                    if (replyType == Type.MULTIPLE_IMAGES || replyType == Type.VIDEO) {
                        replyUri = QuotedPictures.of(replyType, replyUri)
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

            propagateGroupMessage(MessageData.fromMessage(message).copy(messageId = messageId))

            if (!chatScreenIsInForeground(message.fromUserId)) {
                DesktopEvents.notifyMessage(message)
            } else {
                // Gia' letto a schermo: so a later relay echo or sendMe resend cannot
                // notify a message the user already saw.
                DesktopEvents.messageOnScreen(message)
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

// DESKTOP: the App's receiveObject, for the two kinds the PC handles through it:
//
//   - a FILE (type "file<name>,<extension>,<size>"): [content] is empty and the file is
//     assembled here from its chunks, as in the App. It is kept in the PC's media folder under
//     the hash of its bytes plus its extension, where the App saves it to the phone's public
//     Download folder. The extension comes from the sender and becomes part of a file name on
//     this disk, so it is reduced to letters and digits first (MediaFiles.safeExtension): the
//     phone hands the name to MediaStore, which does that job there;
//   - a CONTACT: the text is the contact's record (JSON) and [content] is its picture, already
//     assembled by getFullMessage into the scratch folder. The App leaves it there; the PC
//     moves it to the media folder, so it follows the rule of every other picture. A contact
//     shared without a picture arrives as an empty file, which is dropped;
//   - an AUDIO: [content] is the voice message, assembled by getFullMessage into the scratch
//     folder as "full<id>.dat". The App plays it from there; the PC moves it to the media folder
//     under the hash of its bytes plus the extension its bytes say it has (AudioFiles
//     .extensionOf: m4a for what a phone records), because Windows, which plays it, goes by
//     the extension.
//
//   - a LINK (Type.WEB): it carries no content ([content] is an empty file, dropped) and its
//     preview is in replyId (the picture's address), nameAttached and textAttached, which are
//     copied from the Inbox row as for every kind.
//
//   - a GIF or a STICKER: [content] is the picture, assembled by getFullMessage. The App plays it
//     from there; the PC keeps it in the media folder under the hash of its bytes plus the
//     extension its bytes say it has (AnimatedPictures.extensionOf: gif, png, webp, jpg), so
//     that a GIF stays a GIF and a sticker keeps its transparency.
suspend fun receiveObject(messageId: String, content: String, text: String, fromUserId: String, type: String) {
    debugLine2("fullMessageReceivedEvent", "$type MESSAGE RECEIVED: $messageId")

    val context = App.context()

    // The picture getFullMessage assembled for a contact, or the sound of a voice message.
    val assembledPicture: File? = if (content.isNotEmpty()) MediaFiles.fileOfUri(content) else null

    try {
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)

        val messageRepository = MessageRepository(messageDao)

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if (inboxMessage == null) {
            debugLine("fullMessageReceivedEvent", "No such $messageId message in Inbox")
            assembledPicture?.delete()
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
            ProcessedMessageCache.markProcessed(messageId)
            assembledPicture?.delete()
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                DesktopEvents.notifyMessage(existingMessage)
            }
            return
        }
        val originalSender = inboxMessage.originalSenderId ?: fromUserId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveObject", "Duplicate group message, skipping")
                inboxDao.deleteByContent(contentKey)
                assembledPicture?.delete()
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

        val finalUri: String = if (isFileType(type)) {
            val fileDetail = getFileDetailFromType(type)
            val extension = MediaFiles.safeExtension(fileDetail.getOrNull(1) ?: "")

            // Named after the COPY, not the content, as in the App.
            val tempFile = assembleChunksToTempFile(context, inboxDao, messageId, "temp_${messageId}.bin")
            if (tempFile == null) {
                debugLine("receiveObject", "Failed to assemble file from chunks")
                return
            }

            val hash = computeSha256(tempFile.inputStream()) ?: run {
                debugLine("receiveObject", "Failed to compute hash for file")
                tempFile.delete()
                return
            }
            val hashFileName = "$hash.$extension"

            // DESKTOP: the media folder in place of the phone's Download folder. keep() reuses
            // a file already there under this name, the App's "duplicate file detected" branch.
            try {
                MediaFiles.keep(tempFile, hashFileName).toURI().toString()
            } catch (e: Exception) {
                debugLine("receiveObject", "Failed to save file to the media folder: ${e.message}")
                tempFile.delete()
                return
            }
        } else {
            // DESKTOP: a contact's picture, a voice message, a GIF or a sticker, see the note
            // above the function.
            val keptExtension = when {
                assembledPicture == null -> "jpg"
                type == Type.AUDIO -> AudioFiles.extensionOf(assembledPicture)
                type == Type.GIF || type == Type.STICKER -> AnimatedPictures.extensionOf(assembledPicture)
                else -> "jpg"
            }
            if (assembledPicture == null) {
                ""
            } else if (assembledPicture.length() == 0L) {
                assembledPicture.delete()
                ""
            } else {
                val hash = computeSha256(assembledPicture.inputStream())
                if (hash == null) {
                    assembledPicture.delete()
                    ""
                } else {
                    try {
                        MediaFiles.keep(assembledPicture, "$hash.$keptExtension").toURI().toString()
                    } catch (e: Exception) {
                        debugLine("receiveObject", "Failed to save the contact's picture: ${e.message}")
                        assembledPicture.delete()
                        ""
                    }
                }
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
            text = text,
            textAttached = inboxMessage.textAttached,
            nameAttached = inboxMessage.nameAttached,
            uri = finalUri,
            type = type,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            status = "",
            reaction = null,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = originalSender
        )

        if (messageRepository.saveMessage(message, messageIn = true)) {
            inboxDao.deleteByContent(contentKey)
            ProcessedMessageCache.markProcessed(messageId)

            val deliveryId = inboxMessage.chatGroupId?.let {
                computeDeliveryDocId(it, originalSender, inboxMessage.date)
            }
            markContentComplete(deliveryId)
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

            if (!chatScreenIsInForeground(message.fromUserId)) {
                DesktopEvents.notifyMessage(message)
            } else {
                // Gia' letto a schermo: so a later relay echo or sendMe resend cannot
                // notify a message the user already saw.
                DesktopEvents.messageOnScreen(message)
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save $type message")
        }
    } catch (e: Exception) {
        debugLine("fullMessageReceivedEvent", "Failed to receive $type: Exception: ${e.message}")
    }
}

// DESKTOP: the App's receiveVideo, with the PC's media folder in place of the phone's public
// Movies folder. As in the App the file is kept as "<hash>.mp4" whatever the sender's file was.
// [content] is kept for the App's signature and is always empty here, as for receiveImage.
suspend fun receiveVideo(messageId: String, content: String, text: String, fromUserId: String) {
    debugLine2("fullMessageReceivedEvent", "VIDEO MESSAGE RECEIVED: $messageId")

    val context = App.context()

    try {
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)
        val videoDao = getVideoDao(context)

        val messageRepository = MessageRepository(messageDao)

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if (inboxMessage == null) {
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
            ProcessedMessageCache.markProcessed(messageId)
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                DesktopEvents.notifyMessage(existingMessage)
            }
            return
        }

        val originalSender = inboxMessage.originalSenderId ?: fromUserId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveVideo", "Duplicate group message, skipping")
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

        // Per copy name, as in the App.
        val tempFile = assembleChunksToTempFile(context, inboxDao, messageId, "temp_${messageId}.mp4")
        if (tempFile == null) {
            debugLine("receiveVideo", "Failed to assemble video from chunks")
            return
        }

        val hash = computeSha256(tempFile.inputStream()) ?: run {
            debugLine("receiveVideo", "Failed to compute hash for video")
            tempFile.delete()
            return
        }
        val hashFileName = "$hash.mp4"

        // DESKTOP: the PC's media folder in place of MediaStore. keep() reuses a file already
        // there under this name, which is the App's "duplicate video detected" branch.
        val finalFile = try {
            MediaFiles.keep(tempFile, hashFileName)
        } catch (e: Exception) {
            debugLine("receiveVideo", "Failed to save video to the media folder: ${e.message}")
            tempFile.delete()
            return
        }

        val videoPath = finalFile.toURI().toString()

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
            type = Type.VIDEO,
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
            ProcessedMessageCache.markProcessed(messageId)

            // VideoRepository.insertImage in the App: one row per message and file.
            val videoOwner = inboxMessage.chatGroupId ?: inboxMessage.fromUserId
            if (videoDao.countByFilename(hashFileName, videoOwner, messageKey) == 0) {
                videoDao.insert(Video(0, messageKey, videoPath, inboxMessage.date, "", videoOwner))
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

            if (!chatScreenIsInForeground(message.fromUserId)) {
                DesktopEvents.notifyMessage(message)
            } else {
                // Gia' letto a schermo: so a later relay echo or sendMe resend cannot
                // notify a message the user already saw.
                DesktopEvents.messageOnScreen(message)
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save video message")
        }
    } catch (e: Exception) {
        debugLine("fullMessageReceivedEvent", "Failed to receive video: Exception: ${e.message}")
    }
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
            propagateGroupMessage(MessageData.fromMessage(existingMessage).copy(messageId = messageId))
            inboxDao.deleteByContent(contentKey)
            ProcessedMessageCache.markProcessed(messageId)
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                DesktopEvents.notifyMessage(existingMessage)
            }
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

            propagateGroupMessage(MessageData.fromMessage(message).copy(messageId = messageId))

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
            } else {
                // Gia' letto a schermo: so a later relay echo or sendMe resend cannot
                // notify a message the user already saw.
                DesktopEvents.messageOnScreen(message)
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save image message")
        }

    } catch (e: Exception) {
        debugLine("fullMessageReceivedEvent", "Failed to receive image: ${e.message}")
    }
}

// DESKTOP: the App's receiveImages. [content] is the album as it travelled, assembled by
// getFullMessage: the pictures one after the other, each followed by a separator (Albums.split
// is the App's extractImages). Each picture is kept once, in the media folder, under the hash
// of its bytes: where the App writes it twice, once by extractImages under a random name (the
// copy the message points at) and once under its hash (the copy of the chat's gallery), the PC
// writes the hash copy only and points both at it. The App also turns each picture upright and
// re-encodes it; the PC keeps its bytes as they came, with their EXIF orientation, which every
// viewer on the PC honours (MediaFiles.thumbnail does too).
suspend fun receiveImages(messageId: String, content: String, text: String, fromUserId: String) {
    debugLine2("fullMessageReceivedEvent", "MULTIPLE IMAGES MESSAGE RECEIVED: $messageId")

    val context = App.context()
    val merged: File? = MediaFiles.fileOfUri(content)

    try {
        val messageDao = getMessageDao(context)
        val inboxDao = getInboxDao(context)
        val imagesDao = getImageDao(context)

        val messageRepository = MessageRepository(messageDao)

        val inboxMessage = inboxDao.getFirstMessage(messageId)

        if (inboxMessage == null) {
            debugLine("fullMessageReceivedEvent", "No such multiple images message in Inbox")
            merged?.delete()
            return
        }

        val contentKey = contentKeyOf(inboxMessage)

        // The sender puts the album's own id in groupId (MessageData.fromMessage).
        val messageKey = inboxMessage.groupId.ifEmpty { messageId }

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
            // DESKTOP: the App passes the album as it arrived (uri); here it is rebuilt from
            // the saved pictures by MessageData.fromMessage (Albums.merge), the same bytes.
            propagateGroupMessage(MessageData.fromMessage(existingMessage).copy(messageId = messageId))
            inboxDao.deleteByContent(contentKey)
            ProcessedMessageCache.markProcessed(messageId)
            merged?.delete()
            if (!chatScreenIsInForeground(existingMessage.fromUserId)) {
                DesktopEvents.notifyMessage(existingMessage)
            }
            return
        }

        if (merged == null) {
            debugLine("fullMessageReceivedEvent", "Failed to extract images")
            return
        }

        // extractImages, then the hash copy of each picture (see the note above the function).
        val parts = Albums.split(merged)
        val kept = ArrayList<File>()
        for (part in parts) {
            MediaFiles.keepReceivedPicture(part)?.let { kept.add(it) }
        }
        merged.delete()

        if (kept.isEmpty()) {
            debugLine("fullMessageReceivedEvent", "Failed to extract images")
            return
        }

        debugLine("fullMessageReceivedEvent", "Extracted ${kept.size} images")

        val uriStringList = kept.joinToString(",") { it.toURI().toString() }
        val originalSender = inboxMessage.originalSenderId ?: fromUserId

        if (inboxMessage.chatGroupId != null && inboxMessage.originalSenderId != null) {
            if (messageRepository.groupMessageExists(inboxMessage.chatGroupId, inboxMessage.originalSenderId, inboxMessage.date)) {
                debugLine("receiveImages", "Duplicate group message, skipping")
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
            groupId = messageKey,
            groupSize = inboxMessage.groupSize,
            text = text,
            textAttached = inboxMessage.textAttached,
            nameAttached = inboxMessage.nameAttached,
            uri = uriStringList,
            type = Type.MULTIPLE_IMAGES,
            subType = inboxMessage.subType,
            date = inboxMessage.date,
            status = "",
            reaction = null,
            chatGroupId = inboxMessage.chatGroupId,
            originalSenderId = originalSender
        )

        if (messageRepository.saveMessage(message, messageIn = true)) {
            inboxDao.deleteByContent(contentKey)
            ProcessedMessageCache.markProcessed(messageId)

            // ImageRepository.insertImage in the App: one row per picture of the album.
            val imageOwner = inboxMessage.chatGroupId ?: inboxMessage.fromUserId
            for (file in kept) {
                if (imagesDao.countByFilename(file.name, imageOwner, messageKey) == 0) {
                    imagesDao.insert(Image(0, messageKey, file.toURI().toString(), inboxMessage.date, "", imageOwner))
                }
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

            // DESKTOP: the App passes the album as it arrived (uri); here it is rebuilt from
            // the saved pictures by MessageData.fromMessage (Albums.merge), the same bytes.
            propagateGroupMessage(MessageData.fromMessage(message).copy(messageId = messageId))

            if (!chatScreenIsInForeground(message.fromUserId)) {
                DesktopEvents.notifyMessage(message)
            } else {
                // Gia' letto a schermo: so a later relay echo or sendMe resend cannot
                // notify a message the user already saw.
                DesktopEvents.messageOnScreen(message)
            }
        } else {
            debugLine("fullMessageReceivedEvent", "Failed to save image message")
        }
    } catch (e: Exception) {
        debugLine("fullMessageReceivedEvent", "Failed to receive multiple images: Exception: ${e.message}")
    }
}

fun chatScreenIsInForeground(fromUserId: String): Boolean {
    return DesktopEvents.chatOnScreen(fromUserId)
}
