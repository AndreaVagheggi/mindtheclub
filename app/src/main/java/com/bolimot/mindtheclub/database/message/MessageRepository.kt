package com.bolimot.mindtheclub.database.message

import android.icu.text.SimpleDateFormat
import android.net.Uri
import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.room.Transaction
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.DeliveryHealth
import com.bolimot.mindtheclub.functions.GroupSeenTracker
import com.bolimot.mindtheclub.functions.IncomingPendingTracker
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteFile
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.database.reaction.toPillText
import com.bolimot.mindtheclub.functions.getReactionRepository
import com.bolimot.mindtheclub.functions.splitToList
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.stopSendPipeline
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.viewModel.ViewModelProviderHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

class MessageRepository(private val messageDao: MessageDao) {
    private var currentPagingSource: PagingSource<Int, Message>? = null
    private val mutex = Mutex()

    fun getMessages(myUserId: String, remoteUserId: String): Flow<PagingData<Message>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                prefetchDistance = 5,
                jumpThreshold = 100,
                initialLoadSize = 40),
                pagingSourceFactory = {
                    currentPagingSource?.invalidate()
                    val newPagingSource = messageDao.getMessagesPagingSource(myUserId, remoteUserId)
                    currentPagingSource = newPagingSource
                    newPagingSource
                }
        ).flow
    }

    fun getFilteredMessages(
        myUserId: String,
        remoteUserId: String,
        query: String
    ): Flow<PagingData<Message>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                prefetchDistance = 5,
                initialLoadSize = 40
            ),
            pagingSourceFactory = {
                messageDao.getFilteredMessagesPagingSource(myUserId, remoteUserId, query)
            }
        ).flow
    }

    suspend fun getPeerPicture(messageId: String): String? {
        val message = messageDao.getMessage(messageId) ?: return null
        val userId = message.originalSenderId ?: message.fromUserId

        return getPeerViewModel().getPeer(userId)?.picture
    }

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
                        withContext(Dispatchers.Main) {
                            ViewModelProviderHolder.messageViewModel?.incomingMessage(placeholder)
                        }
                    }
                    insertResult
                } catch (e: Exception) {
                    debugLine("saveReceivingPlaceholder", "Failed: ${e.message}")
                    false
                }
            }
        }
    }

    suspend fun getPeerName(messageId: String): String? {
        val message = messageDao.getMessage(messageId) ?: return null
        val userId = message.originalSenderId ?: message.fromUserId

        return getPeerViewModel().getPeer(userId)?.name
    }

    suspend fun getProfilePic(messageId: String): String? {
        val message = messageDao.getMessage(messageId) ?: return null

        val peerId = if(!message.nameAttached.isNullOrEmpty()){
            message.nameAttached ?: return null
        } else {
            message.fromUserId
        }

        val peerViewModel = getPeerViewModel()
        val peer = peerViewModel.getPeer(peerId) ?: return MySelf.pictureUri()

        return peer.picture
    }

    /**
     * Rebuilds the denormalised reaction caption of a message just written.
     *
     * Message.reaction is a cached rendering of the Reaction rows, and a freshly
     * inserted row always carries an empty one. It has to be recomputed here for
     * two situations that both leave reactions on disk with no caption to show
     * them:
     *  - a reaction that arrived BEFORE its target, which happens to anyone who
     *    joined a group mid-conversation and is now kept instead of dropped;
     *  - the placeholder replacement above, a delete followed by an insert, which
     *    silently discarded the caption of any reaction that landed while the
     *    media was still being received.
     *
     * A message with no reactions is left untouched, so the normal path pays one
     * indexed lookup and nothing else.
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
                    // Incoming messages get their local reception time stamped here,
                    // the single choke point every receive path goes through.
                    val toStore = if (messageIn && message.receivedAt == null) {
                        val now = System.currentTimeMillis()
                        // How long the wake-up actually took: the signal that
                        // reveals a device throttling our background work.
                        DeliveryHealth.recordIncoming(message.date, now, App.context())
                        message.copy(receivedAt = now)
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
                                    withContext(Dispatchers.Main) {
                                        ViewModelProviderHolder.messageViewModel?.incomingMessage(toStore)
                                    }
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
                            withContext(Dispatchers.Main) {
                                ViewModelProviderHolder.messageViewModel?.incomingMessage(toStore)
                            }
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

    @Transaction
    suspend fun deleteMessages(messages: List<Message>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                for (message in messages) {
                    deleteLinkedFiles(message)
                    messageDao.deleteMessage(message.messageId)
                    getReactionRepository(App.context()).deleteReactions(message.messageId)
                    currentPagingSource?.invalidate()
                }
                true
            } catch (e: Exception) {
                debugLine("deleteMessages", "Exception: ${e.message}")
                false
            }
        }
    }

    suspend fun deleteRemotePeerMessages(remoteUserId: String): Boolean {
        val messages = messageDao.getMessagesByRemotePeer(remoteUserId)
        var result = true

        for(message in messages){
            if(!deleteLinkedFiles(message)){
                debugLine("deleteRemotePeerMessages", "There were issues in deleting files for message: ${message.messageId}")
            }
            result = messageDao.deleteMessage(message.messageId) > 0
            getReactionRepository(App.context()).deleteReactions(message.messageId)
        }

        purgeChatLeftovers(remoteUserId, messages)

        return result
    }

    /**
     * Deleting a chat deletes EVERYTHING about that chat. The Message rows and
     * the files are gone by the time this runs; this is the rest of it, and
     * without it "delete" only ever meant "hide".
     *
     * On 19 Aug a group was deleted at 09:34:32 and two minutes later the phone
     * was pulling a 923 chunk video of that same group, from two peers at once,
     * into a chat that no longer existed. It ran for hours. The deletion did not
     * merely fail to stop it, it STARTED it: InboxRecoveryWorker's orphan pass
     * skips any chunk set that still has a Message row, so removing that row
     * turned the leftovers into orphans and the worker solicited them. The first
     * chunk back then recreated the placeholder, and the placeholder workers took
     * over and kept asking.
     *
     * So the cure is not a guard on each of those workers, it is to leave them
     * nothing to work on and to shut the door:
     *
     *  - the content is marked REFUSED by contentKey, which receiveData checks on
     *    every incoming chunk. That is the door: a relay mints a new messageId at
     *    every hop, and only the contentKey recognises the same file coming back
     *    under a different name. Nothing gets in, so no placeholder is ever
     *    recreated and the loop cannot restart;
     *  - each messageId is marked cancelled and put through stopSendPipeline,
     *    which cancels the build and dispatch workers and drops the batch tables,
     *    so this device also stops SENDING that content onward. On 19 Aug it was
     *    still relaying a group it had been removed from;
     *  - both pending trackers are cleared, and only then the chunks.
     *
     * Order matters. The trackers must go BEFORE the chunks: an entry left with
     * no chunks to compare against computes "nothing received" and asks for the
     * WHOLE message with no missing range (see PendingRetryWorker), which is
     * worse than doing nothing.
     *
     * The one thing this cannot do is empty another phone's queue. That is why
     * the same code has to reach every member: each device vaporises its own side.
     */
    private suspend fun purgeChatLeftovers(chatId: String, deleted: List<Message>) {
        if (chatId.isEmpty()) return
        try {
            val context = App.context()
            val inboxDao = getInboxDao(context)

            // Every name this chat's content travels under, in both directions.
            val ids = LinkedHashSet<String>()
            val contentKeys = LinkedHashSet<String>()

            for (message in deleted) {
                ids.add(message.messageId)
                contentKeys.add(
                    contentKeyOf(
                        message.messageId, message.chatGroupId,
                        message.originalSenderId, message.date
                    )
                )
            }
            ids.addAll(inboxDao.getMessageIdsForChat(chatId))
            contentKeys.addAll(inboxDao.getContentKeysForChat(chatId))

            val outgoing = PendingMessageTracker.getAll(context)
                .filter { it.chatGroupId == chatId || it.toUserId == chatId || it.messageId in ids }
            ids.addAll(outgoing.map { it.messageId })

            for (key in contentKeys) {
                if (key.isNotEmpty()) CancelledTransferRegistry.markContentCancelled(context, key)
            }

            for (id in ids) {
                if (id.isEmpty()) continue
                CancelledTransferRegistry.markCancelled(context, id)
                stopSendPipeline(id, "", context)
            }

            for (entry in outgoing) {
                PendingMessageTracker.remove(context, entry.messageId, entry.toUserId)
            }
            PendingMessageTracker.removeAllForPeer(context, chatId)

            for (entry in IncomingPendingTracker.getAll(context)) {
                if (entry.messageId in ids) {
                    IncomingPendingTracker.remove(
                        context, entry.messageId, entry.fromUserId, "chat deleted"
                    )
                }
            }

            refuseGroupContentToSenders(contentKeys, inboxDao)

            val dropped = inboxDao.deleteByChat(chatId)
            debugLine(
                "purgeChatLeftovers",
                "Deleted chat $chatId: ${ids.size} message(s), ${contentKeys.size} content(s) refused, " +
                        "$dropped chunk(s) dropped, ${outgoing.size} outgoing transfer(s) stopped"
            )
        } catch (e: Exception) {
            debugLine("purgeChatLeftovers", "Failed to purge leftovers for $chatId: ${e.message}")
        }
    }

    /**
     * Tells whoever could still be pushing this group content that we do not
     * want it, so they stop NOW instead of burning their whole retry ladder
     * against a phone that refuses every chunk at the door.
     *
     * Closes the gap left by "leave group", which only removes the leaver from
     * the Firestore member map and tells nobody: the other members keep a live
     * queue aimed at a chat that no longer exists. Deleting a group does notify
     * everyone (GROUP_REMOVED), and there this is simply a faster stop.
     *
     * The signal is the same one [refuseIncomingGroupTransfer] already uses, an
     * allReceived carrying a "refused:" marker, chosen because every build in the
     * fleet already understands it: senders drop their batch tables for us and
     * the delivery document drops us from its member map, so nobody picks us as
     * a relay target for this content again. Nothing new to deploy on their side.
     *
     * Group content only. A one to one chat has its own CANCEL_TRANSFER path, and
     * an allReceived there would tell a sender we received something we did not;
     * a blocked peer must not be messaged at all. The per row check on chatGroupId
     * is what enforces it: the chat predicate behind [contentKeys] only ever
     * returns rows with an empty chatGroupId when the deleted chat is a person.
     */
    private suspend fun refuseGroupContentToSenders(
        contentKeys: Set<String>,
        inboxDao: com.bolimot.mindtheclub.database.inbox.InboxDao
    ) {
        val myUserId = MySelf.userId()
        for (key in contentKeys) {
            if (key.isEmpty()) continue
            try {
                val row = inboxDao.getFirstByContent(key) ?: continue
                val chatGroupId = row.chatGroupId
                val originalSenderId = row.originalSenderId
                if (chatGroupId.isNullOrEmpty() || originalSenderId.isNullOrEmpty() || row.date <= 0L) continue

                val deliveryId = "refused:" + computeDeliveryDocId(chatGroupId, originalSenderId, row.date)

                val targets = LinkedHashSet<String>()
                targets.add(originalSenderId)
                targets.addAll(ConnectionManager.instance.admittedSendersFor(key))
                targets.add(row.fromUserId)
                targets.remove(myUserId)
                targets.remove("")

                for (target in targets) {
                    debugLine("purgeChatLeftovers", "Refusing $key to $target")
                    notifyRemotePeer(target, row.messageId, Notify.ALL_RECEIVED, deliveryId)
                }
            } catch (e: Exception) {
                debugLine("purgeChatLeftovers", "Could not refuse $key: ${e.message}")
            }
        }
    }

    private suspend fun deleteLinkedFiles(message: Message): Boolean {
        var result = true

        // A profile message points at "<peerId>.jpg", the file the contact's
        // avatar is still using: it belongs to the peer, not to the message.
        // Deleting the message must not take the avatar with it.
        if (message.type == Type.PROFILE) return true

        // Same aliasing, different carrier: a shared contact card (type=contact,
        // sender side) puts the live "<peerId>.jpg" avatar in its uri, not a
        // copy. The per-type guard above missed it, and on 8 Aug deleting a chat
        // deleted a card message and took ANOTHER contact's avatar with it. This
        // check is generic on purpose: no file that is the avatar of a peer that
        // still exists is ever deleted together with a message, whatever type
        // carries it. Receiver-side copies (guid or "full<id>.dat" names) never
        // match a peer id, so normal cleanup is untouched.
        if (isLivePeerAvatar(message.uri)) {
            debugLine("deleteLinkedFiles", "Uri is the live avatar of an existing peer, not deleting: ${message.uri}")
            return true
        }

        try {
            if (message.type != Type.MULTIPLE_IMAGES) {
                val uriString = message.uri
                if (uriString.isNotEmpty()) {
                    val uri = uriString.toUri()
                    if(deleteFile(uri)) {
                        debugLine("deleteLinkedFiles", "File deleted successfully")
                    } else {
                        debugLine("deleteLinkedFiles", "Failed to delete file: $uri")
                        result = false
                    }
                }
                if(message.type == Type.IMAGE) {
                    val previewFile = getPreviewFileName(uriString.toUri())
                    if(previewFile != Uri.EMPTY && deleteFile(previewFile)){
                        debugLine("deleteLinkedFiles", "Preview file deleted successfully")
                    } else {
                        debugLine("deleteLinkedFiles", "Failed to delete preview file: $previewFile")
                        result = false
                    }
                    val previewByKey = File(App.context().filesDir, "${message.messageId}preview.jpg")
                    if (previewByKey.exists()) previewByKey.delete()
                }
            } else {
                val fileList = splitToList(message.uri)
                for (uriString in fileList) {
                    val uri = uriString.toUri()
                    if(deleteFile(uri)){
                        debugLine("deleteLinkedFiles", "File deleted successfully")
                    } else {
                        debugLine("deleteLinkedFiles", "Failed to delete file: $uri")
                        result = false
                    }
                }
            }
        } catch (e: Exception) {
            debugLine("deleteLinkedFiles", "Exception: ${e.message}")
            result = false
        }

        return result
    }

    /** True when [uriString] points at "<peerId>.jpg" for a peer that still exists. */
    private suspend fun isLivePeerAvatar(uriString: String): Boolean {
        if (uriString.isEmpty() || !uriString.endsWith(".jpg")) return false
        val candidateId = uriString.substringAfterLast('/').removeSuffix(".jpg")
        if (candidateId.isEmpty()) return false
        return try {
            com.bolimot.mindtheclub.functions.getPeerDao(App.context()).getPeer(candidateId) != null
        } catch (e: Exception) {
            debugLine("deleteLinkedFiles", "Avatar check failed for $uriString: ${e.message}")
            false
        }
    }

    private fun getPreviewFileName(uri: Uri): Uri {
        val uriString = uri.toString()

        if (uri.scheme == "content") {
            return Uri.EMPTY
        }

        val dotIndex = uriString.lastIndexOf('.')

        if (dotIndex == -1) {
            return uri
        }

        val newUriString = StringBuilder(uriString).insert(dotIndex, "preview").toString()

        return newUriString.toUri()
    }

    suspend fun getMessage(messageId: String): Message? {
        return messageDao.getMessage(messageId)
    }

    suspend fun getReplyImage(replyId: String, type: String): String? {
        return if(type != Type.WEB) {
            messageDao.getReplyImage(replyId)
        } else {
            messageDao.getReplyImageWeb(replyId)
        }
    }

    suspend fun getReplyType(replyId: String): String? {
        return messageDao.getReplyType(replyId)
    }

    suspend fun sendSeenNotificationForUnseenMessages(remoteUserId: String) {
        if (remoteUserId.startsWith("group")) {
            sendGroupSeenNotifications(remoteUserId)
        } else {
            val messages = messageDao.getUnseenMessages(remoteUserId)
            if (messages.isNotEmpty()) {
                for (message in messages) {
                    debugLine("sendSeenNotificationForUnseenMessages", "SEEN, Message: $message")
                    notifyRemotePeer(remoteUserId, message, Notify.SEEN)
                }
            }
        }
    }

    suspend fun getAllMessageDates(myUserId: String, remoteUserId: String): List<Long> {
        return withContext(Dispatchers.IO) {
            messageDao.getAllMessageDates(myUserId, remoteUserId)
        }
    }

    suspend fun countMessagesNewerThan(myUserId: String, remoteUserId: String, timestamp: Long): Int {
        return withContext(Dispatchers.IO) {
            messageDao.countMessagesNewerThan(myUserId, remoteUserId, timestamp)
        }
    }

    private suspend fun sendGroupSeenNotifications(groupId: String) {
        val myUserId = MySelf.userId() ?: return
        val messages = messageDao.getUnseenGroupMessages(groupId)

        for (msg in messages) {
            if (msg.originalSenderId != myUserId) {
                debugLine("sendGroupSeenNotifications", "GROUP_SEEN for ${msg.messageId} to ${msg.originalSenderId}")
                // The local SEEN below removes this message from the unseen set
                // forever, so this single FCM used to be the one and only chance:
                // lost in transit meant the sender stayed at Delivered for good
                // (16 Aug, Raoul's seens drowned in the 13 Aug flood). Record it
                // as unacked; PendingRetryWorker re-sends until the sender's
                // GROUP_SEEN_ACK clears the entry.
                GroupSeenTracker.record(App.context(), msg.messageId, msg.originalSenderId)
                notifyRemotePeer(msg.originalSenderId, msg.messageId, Notify.GROUP_SEEN)
            }
            messageDao.updateStatus(msg.messageId, Notify.SEEN)
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
                }
                result
            } catch (e: Exception) {
                debugLine("updateStatus", "Exception: ${e.message}")
                false
            }
        }
    }

    suspend fun updateReaction(messageId: String, emoji: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val result = messageDao.updateReaction(messageId, emoji) > 0
                if (result) {
                    debugLine("updateReaction", "Reaction updated successfully")
                } else {
                    debugLine("updateReaction", "Failed to update reaction: $messageId")
                }
                result
            } catch (e: Exception) {
                debugLine("updateStatus", "Exception: ${e.message}")
                false
            }
        }
    }

    suspend fun getLastMessageData(fromUserId: String): Pair<String?, String?>? {
        val message = messageDao.getLastMessage(fromUserId) ?: return null
        val context = App.context()

        val lastText = when(message.type){
            Type.TEXT -> {
                message.text.ifEmpty {
                    message.textAttached
                }
            }
            Type.IMAGE -> context.getString(R.string.image)
            Type.MULTIPLE_IMAGES -> context.getString(R.string.images)
            Type.FILE -> context.getString(R.string.file)
            Type.AUDIO -> context.getString(R.string.audio)
            Type.VIDEO -> context.getString(R.string.video)
            Type.GIF -> context.getString(R.string.gif)
            Type.STICKER -> context.getString(R.string.sticker)
            Type.WEB -> context.getString(R.string.link)

            else -> null
        }

        val dateString = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(message.date))


        return Pair(lastText, dateString)
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
