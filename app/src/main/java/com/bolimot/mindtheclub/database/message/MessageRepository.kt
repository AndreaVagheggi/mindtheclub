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
import com.bolimot.mindtheclub.functions.DeliveryHealth
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteFile
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.splitToList
import com.bolimot.mindtheclub.sending.notifyRemotePeer
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
        }

        return result
    }

    private fun deleteLinkedFiles(message: Message): Boolean {
        var result = true

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
