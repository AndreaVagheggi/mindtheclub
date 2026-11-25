package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.video.Video
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.desktop.media.VideoCompressor
import com.bolimot.mindtheclub.desktop.media.QuotedPictures
import com.bolimot.mindtheclub.desktop.web.WebsiteInfo
import com.bolimot.mindtheclub.desktop.storage.BatchDb
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getImageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getVideoDao
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.sendMessage
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.NO_PICTURE
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the user interface calls to send: the PC's counterpart of the send functions in the
 * App's sending/sendManager.kt, which are written against Android views (EditText, ViewModel,
 * lifecycle scope) and cannot be copied.
 *
 * Each function does what its App twin does and no more: build the Message row exactly as the
 * App builds it, save it, hand it to sendMessage. Everything after that is the engine.
 */
object Outgoing {

    /** sendManager.sendObject: a content is marked "forwarded" when it quotes some words. */
    private fun forwardSubType(textAttached: String?): String =
        if (!textAttached.isNullOrEmpty()) SubType.FORWARD else ""

    /**
     * sendManager.sendText for a 1:1 chat: a plain text, or a reply. Returns the saved message,
     * or null when there is nothing to send or it could not be saved.
     *
     * A reply is a text with [subType] SubType.REPLY, the id of the message it answers in
     * [replyId], and what the bubble quotes in [textAttached] and [nameAttached], which the
     * caller decides as ChatScreen.messageReply does. When the answered message is a picture, a
     * video or a contact, the reply also CARRIES a picture as its content, so that the receiver
     * can draw the quote even if it no longer has the original: that part is here, as it is in
     * the App's sendText.
     */
    suspend fun sendText(
        toUserId: String,
        text: String,
        textAttached: String? = null,
        nameAttached: String? = null,
        subType: String? = null,
        replyId: String = "",
    ): Message? {
        val myUserId = MySelf.userId() ?: return null
        if (text.isEmpty()) return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = replyId,
            groupId = "",
            groupSize = 0,
            text = text,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = "",
            type = Type.TEXT,
            subType = subType,
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (message.subType == SubType.REPLY && !message.replyId.isNullOrBlank()) {
            message.uri = quotedPictureUri(replyId)
        }

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * sendManager.sendText with Type.WEB: a text with a link in it, sent with the link's
     * preview as ChatScreen fills it in. The preview travels in three fields the App gives this
     * meaning to: replyId is the address of the page's picture, nameAttached the page's title,
     * textAttached its description. The picture itself does not travel.
     *
     * ChatScreen does the same when a reply carries a link: the preview takes the fields of the
     * quote, and only the subType still says "reply". [subType] is that subType.
     */
    suspend fun sendWeb(toUserId: String, text: String, info: WebsiteInfo, subType: String?): Message? {
        val myUserId = MySelf.userId() ?: return null
        if (text.isEmpty()) return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = info.imageUrl,
            groupId = "",
            groupSize = 0,
            text = text,
            textAttached = info.description,
            nameAttached = info.title,
            uri = "",
            type = Type.WEB,
            subType = subType,
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /** sendManager.forwardWeb: a link and its preview to [toUserId], as they came. */
    suspend fun forwardWeb(toUserId: String, original: Message): Message? {
        val myUserId = MySelf.userId() ?: return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = original.replyId.toString(),
            groupId = "",
            groupSize = 0,
            text = original.text,
            textAttached = original.textAttached.toString(),
            nameAttached = original.nameAttached.toString(),
            uri = "",
            type = Type.WEB,
            subType = SubType.FORWARD,
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * The picture a reply to [replyId] carries, as a file: URI, or "" when it carries none.
     * sendText in the App: the picture itself for a picture, the first frame for a video, a
     * grid of its first four pictures for an album, the contact's picture for a shared contact;
     * nothing for a text or a file (QuotedPictures). Nothing either when the original's files are
     * not on this PC: a name that points at no file must never travel.
     */
    private suspend fun quotedPictureUri(replyId: String): String {
        return try {
            val repository = getMessageRepository(App.context())
            val replyType = repository.getReplyType(replyId) ?: return ""
            QuotedPictures.of(replyType, repository.getReplyImage(replyId, replyType)) ?: ""
        } catch (e: Exception) {
            debugLine("Outgoing", "No picture for the reply to $replyId: ${e.message}")
            ""
        }
    }

    /**
     * sendManager.sendReaction for a 1:1 chat: this user's reaction to [message], which is a
     * message of its own (its own id, never saved as a bubble), with the reacted message's id in
     * replyId and the emoji as its text. An empty [emoji] withdraws the reaction.
     *
     * The caller records the reaction locally first (ReactionManager.apply), as ChatScreen does.
     */
    fun sendReaction(message: Message, emoji: String) {
        val myUserId = MySelf.userId() ?: return

        val reaction = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = message.fromUserId,
            messageId = guid(),
            replyId = message.messageId,
            groupId = "",
            groupSize = 0,
            text = emoji,
            textAttached = "",
            nameAttached = "",
            uri = "",
            type = Type.REACTION,
            subType = "",
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null
        )

        sendMessage(MessageData.fromMessage(reaction))
    }

    /**
     * sendManager.forwardText for a 1:1 chat: the forwarded words travel in [textAttached] with
     * the name of who wrote them in [nameAttached], and the text of the message itself is empty.
     */
    suspend fun forwardText(toUserId: String, textAttached: String?, nameAttached: String?): Message? {
        val myUserId = MySelf.userId() ?: return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = "",
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = "",
            type = Type.TEXT,
            subType = SubType.FORWARD,
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * sendManager.sendObject with Type.IMAGE, for a 1:1 chat. [source] is the picture the user
     * chose, anywhere on the disk; what is sent is the copy MediaFiles prepares from it, as the
     * phone sends the copy saveBitmapFromUri makes and never the gallery original.
     *
     * A FORWARD passes [textAttached] and [nameAttached], the words and the name the bubble
     * quotes, and [source] is then the file this PC already keeps, which is sent as it is.
     *
     * Throws MediaFiles.Refused when the picture cannot be read. Returns the saved message, or
     * null when it could not be saved.
     */
    suspend fun sendImage(
        toUserId: String,
        source: File,
        caption: String,
        textAttached: String? = null,
        nameAttached: String? = null,
    ): Message? {
        val myUserId = MySelf.userId() ?: return null

        // A file already in the media folder went through prepareForSending when it was sent or
        // was prepared by the phone that sent it: doing it again would only lose quality.
        val prepared = if (MediaFiles.isKept(source)) source else MediaFiles.prepareForSending(source)

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = caption,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = prepared.toURI().toString(),
            type = Type.IMAGE,
            subType = forwardSubType(textAttached),
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))

        // sendManager.addObjectForBrowsing: the row the phone's gallery of a chat is built from.
        try {
            val imageDao = getImageDao(App.context())
            if (imageDao.countByFilename(prepared.name, message.toUserId, message.messageId) == 0) {
                imageDao.insert(Image(0, message.messageId, message.uri, message.date, "", message.toUserId))
            }
        } catch (e: Exception) {
            debugLine("Outgoing", "Image row not written for ${message.messageId}: ${e.message}")
        }
        return message
    }

    /**
     * sendManager.sendMultipleImage for a 1:1 chat, after the phone's SendImages screen: an album
     * of [sources], in that order, with [caption] under it. Each picture is prepared as a single
     * one is (MediaFiles.prepareForSending: upright, at most 2048, JPEG 80), unless this PC
     * already keeps it (a forward). The row keeps them as a comma separated list; the one file
     * they travel as is made when the message is dispatched (MessageData.fromMessage).
     *
     * Throws MediaFiles.Refused when a picture cannot be read. Returns the saved message, or
     * null when it could not be saved.
     */
    suspend fun sendImages(
        toUserId: String,
        sources: List<File>,
        caption: String,
        textAttached: String? = null,
        nameAttached: String? = null,
    ): Message? {
        val myUserId = MySelf.userId() ?: return null
        if (sources.isEmpty()) return null

        val prepared = sources.map { if (MediaFiles.isKept(it)) it else MediaFiles.prepareForSending(it) }

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = caption,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = prepared.joinToString(",") { it.toURI().toString() },
            type = Type.MULTIPLE_IMAGES,
            subType = forwardSubType(textAttached),
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))

        // sendManager.addObjectForBrowsing: one row of the chat's gallery per picture.
        try {
            val imageDao = getImageDao(App.context())
            for (file in prepared) {
                if (imageDao.countByFilename(file.name, message.toUserId, message.messageId) == 0) {
                    imageDao.insert(Image(0, message.messageId, file.toURI().toString(), message.date, "", message.toUserId))
                }
            }
        } catch (e: Exception) {
            debugLine("Outgoing", "Image rows not written for ${message.messageId}: ${e.message}")
        }
        return message
    }

    /**
     * sendManager.sendObject with Type.GIF, after the phone's SendGif screen: the GIF as it is
     * (never re-encoded, which would stop it moving), with [caption] under it. A FORWARD passes
     * [textAttached] and [nameAttached]. Returns the saved message, or null.
     */
    suspend fun sendGif(
        toUserId: String,
        source: File,
        caption: String,
        textAttached: String? = null,
        nameAttached: String? = null,
    ): Message? = sendPictureObject(toUserId, source, caption, textAttached, nameAttached, Type.GIF)

    /**
     * ChatScreen.handlePasteContent with "image/png": a sticker, sent at once with no screen and
     * no caption, the picture as it is so that its transparency stays. Returns the saved
     * message, or null.
     */
    suspend fun sendSticker(toUserId: String, source: File): Message? =
        sendPictureObject(toUserId, source, "", "", "", Type.STICKER)

    /** sendObject for a picture that travels untouched: a copy is kept, under its hash. */
    private suspend fun sendPictureObject(
        toUserId: String,
        source: File,
        caption: String,
        textAttached: String?,
        nameAttached: String?,
        type: String,
    ): Message? {
        val myUserId = MySelf.userId() ?: return null
        val copy = if (MediaFiles.isKept(source)) source else MediaFiles.keepCopyForSending(source)

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = caption,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = copy.toURI().toString(),
            type = type,
            subType = forwardSubType(textAttached),
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * sendManager.sendObject with "video", for a 1:1 chat, after the phone's SendVideo screen.
     *
     * As on the phone, a large video is first re-encoded to 720p at 2 Mbit/s and the original
     * is sent only when that fails or is not worth it (desktop/media/VideoCompressor.kt). This
     * can take from seconds to minutes, and the message appears in the chat when it is done.
     *
     * A FORWARD passes [textAttached] and [nameAttached], and [source] is then the file this PC
     * already keeps. It is left alone, as SendVideo leaves a forward alone: that video already
     * went through the compressor once, here or on the phone that sent it.
     *
     * Throws MediaFiles.Refused when the file cannot be read. Returns the saved message, or null
     * when it could not be saved.
     */
    suspend fun sendVideo(
        toUserId: String,
        source: File,
        caption: String,
        textAttached: String? = null,
        nameAttached: String? = null,
    ): Message? {
        val myUserId = MySelf.userId() ?: return null

        val copy = if (MediaFiles.isKept(source)) {
            source
        } else {
            // What is sent is a copy in the media folder, either way; the re-encoded file is
            // only a step towards it.
            val compressed = VideoCompressor.compress(source)
            try {
                MediaFiles.keepCopyForSending(compressed ?: source)
            } finally {
                compressed?.delete()
            }
        }

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = caption,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = copy.toURI().toString(),
            type = Type.VIDEO,
            subType = forwardSubType(textAttached),
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))

        // sendManager.addObjectForBrowsing: the row the phone's gallery of a chat is built from.
        try {
            val videoDao = getVideoDao(App.context())
            if (videoDao.countByFilename(copy.name, message.toUserId, message.messageId) == 0) {
                videoDao.insert(Video(0, message.messageId, message.uri, message.date, "", message.toUserId))
            }
        } catch (e: Exception) {
            debugLine("Outgoing", "Video row not written for ${message.messageId}: ${e.message}")
        }
        return message
    }

    /**
     * sendManager.sendObject with a file type, for a 1:1 chat, after the phone's SendFile
     * screen: the message carries a copy of the file, and the file's name, extension and size
     * travel in the TYPE of the message, "file<name>,<extension>,<size>".
     *
     * A FORWARD passes [textAttached] and [nameAttached], and [source] is then the file this PC
     * already keeps, whose name on disk is a code: [realName] is the name the original message
     * gave it, as SendFile takes it from the request.
     *
     * Throws MediaFiles.Refused when the file cannot be read. Returns the saved message, or null
     * when it could not be saved.
     */
    suspend fun sendFile(
        toUserId: String,
        source: File,
        caption: String,
        textAttached: String? = null,
        nameAttached: String? = null,
        realName: String? = null,
    ): Message? {
        val myUserId = MySelf.userId() ?: return null

        // getFileDetails in the App: the name as it is, the extension after the last dot.
        val fullName = realName?.takeIf { it.isNotBlank() && it != "Unknown" } ?: source.name
        val extension = if (fullName.contains(".")) fullName.substringAfterLast(".", "Unknown") else "Unknown"
        val size = source.length()
        val detailCSV = "$fullName,$extension,$size"

        val copy = if (MediaFiles.isKept(source)) source else MediaFiles.keepCopyForSending(source)

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = caption,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = copy.toURI().toString(),
            type = "${Type.FILE}$detailCSV",
            subType = forwardSubType(textAttached),
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * ChatScreen.sendAudioFile, which is sendManager.sendObject with Type.AUDIO: a voice message
     * just recorded, [recording] being the M4A file this PC keeps (AudioFiles.keepRecording).
     * No caption and nothing quoted. Returns the saved message, or null when it could not be saved.
     */
    suspend fun sendAudio(toUserId: String, recording: File): Message? {
        val myUserId = MySelf.userId() ?: return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = "",
            textAttached = "",
            nameAttached = "",
            uri = recording.toURI().toString(),
            type = Type.AUDIO,
            subType = forwardSubType(""),
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * sendManager.forwardAudio: a voice message to [toUserId], with no screen in between. The
     * message carries in nameAttached the userId of whoever SPOKE it, [originId], so that every
     * bubble it reaches shows that person's picture (MessageRepository.getProfilePic).
     */
    suspend fun forwardAudio(toUserId: String, originId: String?, recording: File): Message? {
        val myUserId = MySelf.userId() ?: return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = "",
            textAttached = "",
            nameAttached = originId,
            uri = recording.toURI().toString(),
            type = Type.AUDIO,
            subType = SubType.FORWARD,
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * PeerViewModel.sendContact: shares one of the user's contacts with [toUserId]. The text of
     * the message is the contact's record as the App writes it (the Peer row as JSON) and its
     * content is the contact's picture, when this PC has it.
     *
     * Returns the saved message, or null when the contact is unknown or nothing could be saved.
     */
    suspend fun sendContact(toUserId: String, contactUserId: String): Message? {
        val myUserId = MySelf.userId() ?: return null
        val peer = getPeerDao(App.context()).getPeer(contactUserId) ?: return null

        val profileString = Json.encodeToString(Peer.serializer(), peer)
        if (profileString.isEmpty()) return null

        // The picture goes out only if its file is here: a path that names nothing on this PC
        // must never travel in its place.
        val profilePicture = MediaFiles.fileOfUri(peer.picture)?.toURI()?.toString() ?: ""

        val message = Message(
            0,
            myUserId,
            toUserId,
            guid(),
            "",
            "",
            0,
            profileString,
            null,
            null,
            profilePicture,
            Type.CONTACT,
            null,
            System.currentTimeMillis(),
            "",
            null
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /** peerViewModel.sendMyProfileToRemotePeer: the picture leaves at most 1024 on its long side, quality 85. */
    private const val PROFILE_PICTURE_SIDE = 1024
    private const val PROFILE_PICTURE_QUALITY = 0.85f

    /**
     * peerViewModel.sendMyProfileToRemotePeer: this user's name, bio and picture to [remoteUserId],
     * as a message of type profile. It is how a contact being acquired learns who it is talking
     * to, and how a contact asking for it (REQUEST_PROFILE) gets a fresh one. The row is saved,
     * as on the phone, so that the delivery ladder covers it; the window never draws it.
     *
     * The phone also puts its Bluetooth address in the record; the PC has no Bluetooth
     * transport, so it puts none, which is what a phone without one sends.
     */
    suspend fun sendMyProfile(remoteUserId: String) {
        debugLine("sendMyProfileToRemotePeer", "Start: sending my profile to $remoteUserId")
        try {
            val myUserId = MySelf.userId()
            if (myUserId.isNullOrEmpty()) {
                debugLine("sendMyProfileToRemotePeer", "I do not have a userId")
                return
            }
            val myName = MySelf.name()
            if (myName.isNullOrEmpty()) {
                debugLine("sendMyProfileToRemotePeer", "I do not have a name")
                return
            }

            val peerObject = Peer(
                uid = 0,
                userId = myUserId,
                token = "",
                name = myName,
                bio = MySelf.bio(),
                picture = null,
                status = Contact.NEW,
                privateId = MySelf.privateId() ?: "",
                bluetoothMac = null
            )

            val myProfileString = Json.encodeToString(Peer.serializer(), peerObject)
            val messageId = guid()
            val myProfilePicString = MediaFiles.fileOfUri(MySelf.pictureUri())?.let { source ->
                try {
                    MediaFiles.keepResized(source, PROFILE_PICTURE_SIDE, PROFILE_PICTURE_QUALITY).toURI().toString()
                } catch (e: MediaFiles.Refused) {
                    null
                }
            } ?: NO_PICTURE

            val message = Message(
                0,
                myUserId,
                remoteUserId,
                messageId,
                "",
                "",
                0,
                myProfileString,
                null,
                null,
                myProfilePicString,
                Type.PROFILE,
                null,
                System.currentTimeMillis(),
                "",
                null
            )

            // sendManager.sendProfile.
            if (getMessageRepository(App.context()).saveMessage(message, messageIn = false)) {
                sendMessage(MessageData.fromMessage(message))
            }
            debugLine("sendMyProfileToRemotePeer", "Profile sent to $remoteUserId (messageId=$messageId)")
        } catch (ex: Exception) {
            debugLine("sendMyProfileToRemotePeer", "Cannot send my profile to $remoteUserId: ${ex.message}")
        }
    }

    /**
     * Picks up what a previous run left unfinished. A message that still has batch tables has
     * not been confirmed by its receiver: the batch table is the sender's state, and a
     * restarted program resumes from it (C:\mtc\docs\arch\transport.md section 2).
     *
     * This is the PC's answer to the one thing its WorkManager stand-in does not do, keep its
     * queue across a restart. Each unconfirmed message is ANNOUNCED again with a pending, not
     * dispatched: the receiver answers allReceived if it already has it, or sendMe if it does
     * not, and only then is a connection opened. A blind dispatch would open a connection for
     * every message, including the ones that arrived and only lack their confirmation.
     */
    fun resumeUnconfirmed(): Int {
        var resumed = 0
        try {
            val tables = BatchDb.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'batch%'"
            ) { it.getText(0) }

            // Already on the outgoing ladder: PendingRetryWorker re-announces those on its own
            // schedule, and announcing them here too would reset their backoff.
            val tracked = PendingMessageTracker.getAll(App.context()).map { it.messageId }.toHashSet()

            val seen = HashSet<String>()
            for (table in tables) {
                val target = BatchDb.query("SELECT messageId, toUserId FROM `$table` LIMIT 1") {
                    Pair(if (it.isNull(0)) "" else it.getText(0), if (it.isNull(1)) "" else it.getText(1))
                }.firstOrNull() ?: continue
                val (messageId, toUserId) = target
                if (messageId.isEmpty() || toUserId.isEmpty() || !seen.add(messageId)) continue
                if (messageId in tracked) continue

                debugLine("Outgoing", "Announcing unconfirmed $messageId to $toUserId again")
                notifyRemotePeer(toUserId, messageId, Notify.PENDING)
                PendingMessageTracker.record(App.context(), messageId, toUserId, messageId)
                resumed++
            }
        } catch (e: Exception) {
            debugLine("Outgoing", "Resume failed: ${e.message}")
        }
        return resumed
    }
}
