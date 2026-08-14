package com.bolimot.mindtheclub.receiving

import ProcessedMessageCache
import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.outbox.Outbox
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.works.AssembleMessageWorker
import com.bolimot.mindtheclub.works.StalePlaceholderCheckWorker
import com.bolimot.mindtheclub.works.logSoakArrival
import kotlinx.serialization.json.Json

private val jsonParser = Json { ignoreUnknownKeys = true }

suspend fun receiveData(remoteUserId: String,
                        receivedMessage: String,
                        inboxDao: InboxDao
) {

    if (getBlockedUserRepository(App.context()).isBlocked(remoteUserId)) {
        debugLine("receiveData", "Blocked user $remoteUserId, dropping message")
        return
    }

    val message = jsonParser.decodeFromString<Outbox>(receivedMessage)

    if (CancelledTransferRegistry.isCancelled(App.context(), message.messageId)) {
        debugLine("receiveData", "Transfer ${message.messageId} was cancelled, dropping chunk ${message.sequenceNo}")
        return
    }

    // Content-level check on top of the messageId one: a refused group transfer
    // comes back under a different messageId at every relay hop, and only the
    // contentKey recognises it as the same file.
    val incomingContentKey = contentKeyOf(message)
    if (CancelledTransferRegistry.isContentCancelled(App.context(), incomingContentKey)) {
        debugLine("receiveData", "Content $incomingContentKey was refused, dropping chunk ${message.sequenceNo}")
        return
    }

    val newMessage = Inbox(
        uid = 0,
        fromUserId = remoteUserId,
        messageId = message.messageId,
        replyId = message.replyId,
        groupId = message.groupId,
        groupSize = message.groupSize,
        chunkId = message.chunkId,
        sequenceNo = message.sequenceNo,
        totalNo = message.totalNo,
        content = message.content,
        text = message.text,
        textAttached = message.textAttached,
        nameAttached = message.nameAttached,
        type = message.type,
        subType = message.subType,
        date = message.date,
        sent = false,
        chatGroupId = message.chatGroupId,
        originalSenderId = message.originalSenderId,
        contentKey = contentKeyOf(
            message.messageId,
            message.chatGroupId,
            message.originalSenderId,
            message.date
        )
    )

    if(newMessage.content.isEmpty()){
        debugLine("receiveData", "Received text message: ${newMessage.text}")
        logSoakArrival(newMessage.text, newMessage.date)
    } else {
        debugLine("receiveData", "Received messageId: ${newMessage.messageId} Sequence No.: ${newMessage.sequenceNo}")
    }

    // Chunks are matched by (contentKey, sequenceNo) alone, so a transfer that
    // gets re-split with a different chunk size would keep the already stored
    // chunks of the old split and mix the two, producing a silently corrupt
    // file. A totalNo that no longer matches is the signal that this happened:
    // the partial set is worthless, drop it and rebuild from this chunk on.
    val storedTotal = inboxDao.getTotalChunksByContent(newMessage.contentKey)
    if (storedTotal > 0 && storedTotal != newMessage.totalNo) {
        val dropped = inboxDao.deleteByContent(newMessage.contentKey)
        debugLine(
            "receiveData",
            "Chunking changed for ${newMessage.contentKey} (stored totalNo=$storedTotal, " +
                    "incoming=${newMessage.totalNo}): dropped $dropped stale chunk(s)"
        )
    }

    if(insertIfNotExists(newMessage, inboxDao)) {
        if (newMessage.sequenceNo == 1 && newMessage.totalNo > 1) {
            insertReceivingPlaceholder(newMessage)
        }
        MessageProcessor.requestInboxCheck(newMessage.messageId)
        debugLine("receiveData", "${newMessage.sequenceNo};${newMessage.totalNo}")
    }
}

suspend fun insertIfNotExists(newMessage: Inbox, inboxDao: InboxDao): Boolean {
    val exists = inboxDao.countBySequenceNoByContent(newMessage.sequenceNo, newMessage.contentKey) > 0
    return if (!exists) {
        val result = try {
            inboxDao.insert(newMessage)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            debugLine("InboxInsert", "Chunk seq=${newMessage.sequenceNo} already present for contentKey=${newMessage.contentKey}, skipping")
            -1L
        }
        if (result != -1L) {
            debugLine("InboxInsert", "Successfully inserted chunk seq=${newMessage.sequenceNo} for contentKey=${newMessage.contentKey}")
            true
        } else {
            false
        }
    } else {
        debugLine("InboxInsert", "Chunk seq=${newMessage.sequenceNo} already exists for contentKey=${newMessage.contentKey}, not inserting.")
        true
    }
}

suspend fun checkIfMessageIsCompleted(messageId: String): List<Int>? {
    try{
        val messageDao = getMessageDao(App.context())
        val existingMessage = messageDao.getMessage(messageId)
        if (existingMessage != null && existingMessage.status != Status.RECEIVING) {
            debugLine("messageIsCompleted","Message already exists in database: $messageId")
            return null
        }

        val inboxDao = getInboxDao(App.context())
        val contentKey = resolveContentKey(inboxDao, messageId)

        val currentChunkNo = inboxDao.countChunksByContent(contentKey)

        if (currentChunkNo == 0) {
            if (ProcessedMessageCache.wasProcessed(messageId)) {
                debugLine("messageIsCompleted", "Message already processed (cache hit): $messageId")
                return null
            }
            debugLine("messageIsCompleted","Don't have records in inbox for: $messageId (contentKey=$contentKey)")
            return listOf()
        }

        val expectedChunksNo = inboxDao.getTotalChunksByContent(contentKey)
        if (currentChunkNo == expectedChunksNo) {
            debugLine("messageIsCompleted","Message is complete, received $currentChunkNo chunks out of $expectedChunksNo (contentKey=$contentKey)")
            return null
        } else {
            val existingSequences = inboxDao.getAllSequencesByContent(contentKey)
            debugLine("messageIsCompleted","Message is not complete, received $currentChunkNo chunks out of $expectedChunksNo (contentKey=$contentKey)")
            return (1..expectedChunksNo).filter { it !in existingSequences }
        }
    } catch (e: Exception){
        debugLine("messageIsCompleted", "Exception: ${e.message}")
        return listOf()
    }
}

/**
 * Missing chunk sequence numbers for [contentKey]:
 *  - null       -> all chunks present locally (assembly pending, nothing to request)
 *  - empty list -> nothing received locally (a full send is needed)
 *  - list       -> partial: exactly these sequence numbers are missing
 *
 * Used to enrich sendMe requests so the sender can re-send only what is
 * actually missing instead of the whole message.
 */
suspend fun missingChunksByContent(inboxDao: InboxDao, contentKey: String): List<Int>? {
    val current = inboxDao.countChunksByContent(contentKey)
    if (current == 0) return listOf()
    val expected = inboxDao.getTotalChunksByContent(contentKey)
    if (current >= expected) return null
    val have = inboxDao.getAllSequencesByContent(contentKey)
    return (1..expected).filter { it !in have }
}

suspend fun inboxCheckMessageComplete(messageId: String, inboxDao: InboxDao) {
    val contentKey = resolveContentKey(inboxDao, messageId)
    val currentChunkNo = inboxDao.countChunksByContent(contentKey)

    if (currentChunkNo == 0) {
        debugLine("inboxCheckMessageComplete","Don't have records in inbox for: $messageId")
        return
    }

    val expectedChunksNo = inboxDao.getTotalChunksByContent(contentKey)

    if (currentChunkNo == expectedChunksNo) {
        val type = inboxDao.getMessage(messageId).type
        debugLine("inboxCheckMessageComplete","Message is complete, received $currentChunkNo chunks out of $expectedChunksNo, type=$type")
        if (expectedChunksNo > 1) {
            // Multi-chunk (media) assembly takes seconds and must survive doze:
            // run it under WorkManager, like every other part of the send path.
            AssembleMessageWorker.enqueue(App.context(), messageId)
        } else {
            // Single-chunk messages (text, reactions, …) assemble instantly:
            // keep the original inline path untouched.
            fullMessageReceivedEvent(messageId)
        }
    }
    else {
        debugLine("inboxCheckMessageComplete","Message is not complete, received $currentChunkNo chunks out of $expectedChunksNo")
    }
}

private suspend fun insertReceivingPlaceholder(chunk: Inbox) {
    try {
        val myUserId = MySelf.userId() ?: return

        if (chunk.type == Type.PROFILE) return

        val messageKey = chunk.groupId.ifEmpty { chunk.messageId }

        val messageDao = getMessageDao(App.context())
        if (messageDao.getMessage(messageKey) != null) {
            debugLine("receivingPlaceholder", "Message already exists for key: $messageKey")
            return
        }

        val placeholder = Message(
            uid = 0,
            fromUserId = chunk.chatGroupId ?: chunk.originalSenderId ?: chunk.fromUserId,
            toUserId = myUserId,
            messageId = messageKey,
            replyId = null,
            groupId = chunk.groupId,
            groupSize = chunk.groupSize,
            text = "",
            textAttached = null,
            nameAttached = chunk.nameAttached,
            uri = "",
            type = chunk.type,
            subType = null,
            date = chunk.date,
            status = Status.RECEIVING,
            reaction = null,
            chatGroupId = chunk.chatGroupId,
            originalSenderId = chunk.originalSenderId
        )

        val repo = getMessageRepository(App.context())
        repo.saveReceivingPlaceholder(placeholder)

        debugLine("receivingPlaceholder", "Placeholder created for messageKey: $messageKey, type: ${chunk.type}")

        if (!chunk.chatGroupId.isNullOrEmpty() && !chunk.originalSenderId.isNullOrEmpty()) {
            StalePlaceholderCheckWorker.schedule(App.context(), messageKey)
        }
    } catch (e: Exception) {
        debugLine("receivingPlaceholder", "Failed to create placeholder: ${e.message}")
    }
}









