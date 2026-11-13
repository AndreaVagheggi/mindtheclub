package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.desktop.storage.BatchDb
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getImageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.sendMessage
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
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

    /**
     * sendManager.sendText for a 1:1 chat: a plain text, not a reply. Returns the saved
     * message, or null when there is nothing to send or it could not be saved.
     */
    suspend fun sendText(toUserId: String, text: String): Message? {
        val myUserId = MySelf.userId() ?: return null
        if (text.isEmpty()) return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = text,
            textAttached = null,
            nameAttached = null,
            uri = "",
            type = Type.TEXT,
            subType = null,
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
     * Throws MediaFiles.Refused when the picture cannot be read. Returns the saved message, or
     * null when it could not be saved.
     */
    suspend fun sendImage(toUserId: String, source: File, caption: String): Message? {
        val myUserId = MySelf.userId() ?: return null

        val prepared = MediaFiles.prepareForSending(source)

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = caption,
            textAttached = null,
            nameAttached = null,
            uri = prepared.toURI().toString(),
            type = Type.IMAGE,
            subType = "",
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
     * sendManager.sendObject with a file type, for a 1:1 chat, after the phone's SendFile
     * screen: the message carries a copy of the file, and the file's name, extension and size
     * travel in the TYPE of the message, "file<name>,<extension>,<size>".
     *
     * Throws MediaFiles.Refused when the file cannot be read. Returns the saved message, or null
     * when it could not be saved.
     */
    suspend fun sendFile(toUserId: String, source: File, caption: String): Message? {
        val myUserId = MySelf.userId() ?: return null

        // getFileDetails in the App: the name as it is, the extension after the last dot.
        val fullName = source.name
        val extension = if (fullName.contains(".")) fullName.substringAfterLast(".", "Unknown") else "Unknown"
        val size = source.length()
        val detailCSV = "$fullName,$extension,$size"

        val copy = MediaFiles.keepCopyForSending(source)

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = caption,
            textAttached = null,
            nameAttached = null,
            uri = copy.toURI().toString(),
            type = "${Type.FILE}$detailCSV",
            subType = "",
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
