package com.bolimot.mindtheclub.database.message

// DESKTOP: this file is NOT a copy of the App's MessageRepository.kt. The functions the receive
// path needs are here with the App's names, signatures and logic, statement for statement,
// with these differences, all of them at the edge towards the user interface:
//
//   - where the App tells the chat screen a message arrived (ViewModelProviderHolder), this
//     tells DesktopEvents;
//   - DeliveryHealth.recordIncoming is not called (the latency statistics are not on the PC);
//   - deleteMessages removes the rows and their reactions ONLY. In the App it also deletes the
//     linked media files and, for group content, tells the senders the content was refused.
//     Here its one caller is the removal of an orphan RECEIVING placeholder, which has neither.
//     A message the user deletes goes through deleteMessagesAndFiles, which takes its files too;
//   - the paged queries, the chat deletion, the "seen" notifications and the last message
//     preview are not here yet: they arrive with the screens that use them.

import com.bolimot.mindtheclub.database.reaction.toPillText
import com.bolimot.mindtheclub.desktop.engine.DesktopEvents
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.tools.MySelf
import java.io.File
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getReactionRepository
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MessageRepository(private val messageDao: MessageDao) {
    private val mutex = Mutex()

    suspend fun groupMessageExists(chatGroupId: String, senderId: String, date: Long): Boolean {
        return messageDao.countGroupMessage(chatGroupId, senderId, date) > 0
    }

    suspend fun getGroupMessage(chatGroupId: String, senderId: String, date: Long): Message? {
        return messageDao.getGroupMessage(chatGroupId, senderId, date)
    }

    suspend fun saveReceivingPlaceholder(placeholder: Message): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (messageDao.getMessage(placeholder.messageId) != null) {
                        return@withLock false
                    }

                    val insertResult = messageDao.insert(placeholder) > 0L
                    if (insertResult) {
                        debugLine("saveReceivingPlaceholder", "Placeholder inserted: ${placeholder.messageId}")
                        DesktopEvents.incomingMessage(placeholder)
                    }
                    insertResult
                } catch (e: Exception) {
                    debugLine("saveReceivingPlaceholder", "Failed: ${e.message}")
                    false
                }
            }
        }
    }

    /**
     * Rebuilds the denormalised reaction caption of a message just written. See the App's
     * MessageRepository for why: a reaction can arrive before its target, and the replacement
     * of a placeholder is a delete then an insert.
     */
    private suspend fun restoreReactionCaption(messageId: String) {
        try {
            val reactions = getReactionRepository(App.context()).getReactions(messageId)
            if (reactions.isEmpty()) return
            val pill = reactions.toPillText()
            messageDao.updateReaction(messageId, pill)
            debugLine("saveMessage", "Restored reaction caption for $messageId -> '$pill'")
        } catch (e: Exception) {
            debugLine("saveMessage", "Reaction caption restore failed for $messageId: ${e.message}")
        }
    }

    suspend fun saveMessage(message: Message, messageIn: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    // Incoming messages get their local reception time here, the single
                    // choke point every receive path goes through.
                    val toStore = if (messageIn && message.receivedAt == null) {
                        message.copy(receivedAt = System.currentTimeMillis())
                    } else {
                        message
                    }

                    val existing = messageDao.getMessage(toStore.messageId)
                    if (existing != null) {
                        if (existing.status == Status.RECEIVING && toStore.status != Status.RECEIVING) {
                            messageDao.deleteMessage(toStore.messageId)
                            val insertResult = messageDao.insert(toStore) > 0L
                            if (insertResult) {
                                debugLine("saveMessage", "Placeholder replaced for: ${toStore.messageId}")
                                restoreReactionCaption(toStore.messageId)
                                touchPeerLastMessage(toStore, messageIn)
                                if (messageIn) {
                                    DesktopEvents.incomingMessage(toStore)
                                }
                            }
                            return@withLock insertResult
                        }
                        debugLine("saveMessage", "Message ${toStore.messageId}, already exists")
                        return@withLock true
                    }

                    val insertResult = messageDao.insert(toStore) > 0L

                    if (insertResult) {
                        debugLine("saveMessage", "Message successfully added")
                        restoreReactionCaption(toStore.messageId)
                        touchPeerLastMessage(toStore, messageIn)

                        if (messageIn) {
                            debugLine("saveMessage", "Message in detected")
                            DesktopEvents.incomingMessage(toStore)
                        }
                    }
                    insertResult
                } catch (e: Exception) {
                    debugLine("saveMessage", "Failed to add message: ${e.message}")
                    false
                }
            }
        }
    }

    /** Rows and reactions only, see the note at the top of this file. */
    suspend fun deleteMessages(messages: List<Message>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                for (message in messages) {
                    messageDao.deleteMessage(message.messageId)
                    getReactionRepository(App.context()).deleteReactions(message.messageId)
                }
                true
            } catch (e: Exception) {
                debugLine("deleteMessages", "Exception: ${e.message}")
                false
            }
        }
    }

    /**
     * The App's deleteMessages for a message the user deletes (ChatScreen, Delete): its row, its
     * reactions and its files, on this device only.
     *
     * DESKTOP: one difference about the files. The PC keeps a file once, under the hash of its
     * bytes, and every message with the same content points at it (a forward, a reply's
     * picture, a contact's picture that is also the contact's avatar). So a file goes only when
     * no other message and no contact uses it any more, and only from the PC's own media
     * folder. The App's own guard against deleting a live avatar (isLivePeerAvatar) is the same
     * idea for the one case the phone shares a file.
     */
    suspend fun deleteMessagesAndFiles(messages: List<Message>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                for (message in messages) {
                    messageDao.deleteMessage(message.messageId)
                    getReactionRepository(App.context()).deleteReactions(message.messageId)
                    deleteLinkedFiles(message)
                }
                true
            } catch (e: Exception) {
                debugLine("deleteMessages", "Exception: ${e.message}")
                false
            }
        }
    }

    private suspend fun deleteLinkedFiles(message: Message) {
        // A profile message points at the file a contact's avatar is using (see the App's file).
        if (message.type == Type.PROFILE) return

        val uris = if (message.type == Type.MULTIPLE_IMAGES) message.uri.split(",") else listOf(message.uri)
        val avatars = HashSet<String>()
        for (peer in getPeerDao(App.context()).getAllPeers()) {
            MediaFiles.fileOfUri(peer.picture)?.let { avatars.add(it.name) }
        }
        MediaFiles.fileOfUri(MySelf.pictureUri())?.let { avatars.add(it.name) }

        for (uri in uris) {
            val file = MediaFiles.fileOfUri(uri.trim()) ?: continue
            if (!MediaFiles.isKept(file)) continue
            if (file.name in avatars) continue
            if (messageDao.countOthersUsing(file.name, message.messageId) > 0) continue
            if (file.delete()) debugLine("deleteLinkedFiles", "File deleted: ${file.name}")
            // The copy a voice message is played from (AudioFiles.playable).
            File(File(App.context().filesDir, "audio_play"), file.nameWithoutExtension + ".wav").delete()
        }
    }

    suspend fun getMessage(messageId: String): Message? {
        return messageDao.getMessage(messageId)
    }

    suspend fun getReplyImage(replyId: String, type: String): String? {
        return if (type != Type.WEB) {
            messageDao.getReplyImage(replyId)
        } else {
            messageDao.getReplyImageWeb(replyId)
        }
    }

    suspend fun getReplyType(replyId: String): String? {
        return messageDao.getReplyType(replyId)
    }

    /**
     * The chat with [remoteUserId] is on screen: tell the peer its messages have been seen.
     * As in the App, the local status does not change here; it changes when the peer answers
     * ReceivedSeen. Groups are not on the PC yet.
     */
    suspend fun sendSeenNotificationForUnseenMessages(remoteUserId: String) {
        if (remoteUserId.startsWith("group")) return
        val messages = messageDao.getUnseenMessages(remoteUserId)
        if (messages.isNotEmpty()) {
            for (message in messages) {
                debugLine("sendSeenNotificationForUnseenMessages", "SEEN, Message: $message")
                notifyRemotePeer(remoteUserId, message, Notify.SEEN)
            }
        }
    }

    suspend fun updateStatus(messageId: String, status: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val currentStatus = messageDao.getMessage(messageId)?.status
                if (currentStatus == status) return@withContext false

                if (currentStatus == Notify.SEEN) return@withContext false

                val result = messageDao.updateStatus(messageId, status) > 0
                if (result) {
                    debugLine("updateStatus", "Status updated successfully to $status")
                    // DESKTOP: the App's screen redraws from its ViewModel; the PC is told here.
                    DesktopEvents.statusChanged(messageId, status)
                }
                result
            } catch (e: Exception) {
                debugLine("updateStatus", "Exception: ${e.message}")
                false
            }
        }
    }

    private suspend fun touchPeerLastMessage(message: Message, messageIn: Boolean) {
        try {
            val peerId = if (messageIn) message.fromUserId else message.toUserId
            getPeerDao(App.context()).updateLastMessageAt(peerId, System.currentTimeMillis())
        } catch (e: Exception) {
            debugLine("saveMessage", "Failed to update lastMessageAt: ${e.message}")
        }
    }
}
