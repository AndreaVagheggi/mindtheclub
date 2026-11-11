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

    // DESKTOP: group content is refused here, whole. A group member that confirms a message must
    // also relay it to the others (fanout plus relay, docs/arch/group.md), and relay is not on
    // the PC yet: accepting would make the sender count this device as served and nobody would
    // carry the content on. Dropped unconfirmed, the sender simply serves another member.
    if (!message.chatGroupId.isNullOrEmpty()) {
        debugLine("receiveData", "Group content ${message.messageId} dropped: groups are not on the PC yet")
        return
    }

    if (CancelledTransferRegistry.isCancelled(App.context(), message.messageId)) {
        debugLine("receiveData", "Transfer ${message.messageId} was cancelled, dropping chunk ${message.sequenceNo}")
        return
    }

    // Content level check on top of the messageId one: a refused group transfer comes back under
    // a different messageId at every relay hop, and only the contentKey recognises it as the
    // same file.
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
        // DESKTOP: logSoakArrival removed, the soak test does not exist on the PC.
    } else {
        debugLine("receiveData", "Received messageId: ${newMessage.messageId} Sequence No.: ${newMessage.sequenceNo}")
    }

    // Chunks are matched by (contentKey, sequenceNo) alone, so a transfer re-split with a
    // different chunk size would keep the already stored chunks of the old split and mix the
    // two, producing a silently corrupt file. A totalNo that no longer matches is the signal
    // that this happened: the partial set is worthless, drop it and rebuild from this chunk on.
    val storedTotal = inboxDao.getTotalChunksByContent(newMessage.contentKey)

    // A chunk for something already assembled starts a NEW partial set that nothing will ever
    // clear, and that set then lies to every check that counts chunks instead of looking at the
    // message.
    //
    // deleteByContent runs once, when the message is assembled. Senders keep streaming until
    // they get the ack, so the tail lands just after and settles in. On 20 Aug an album of 68
    // chunks was received in full at 10:36:46, assembled at 10:36:48, cleaned at 10:36:50, and
    // by 10:36:52 it was back up to 41 chunks out of 68. Those 41 never moved again: at 17:06,
    // six and a half hours later, the phone was still asking for the missing 27, round 45, while
    // the DATA_CALL gate four seconds away answered "already complete" on the same contentKey.
    //
    // Only checked when the set is EMPTY, which is the only way a residue can start, so a
    // transfer in flight pays nothing: storedTotal is non zero from its second chunk onwards.
    if (storedTotal == 0) {
        val anchorId = newMessage.groupId.ifEmpty { newMessage.messageId }
        val assembled = getMessageDao(App.context()).getMessage(anchorId)
        if (assembled != null && assembled.status != Status.RECEIVING) {
            debugLine(
                "receiveData",
                "Dropping chunk ${newMessage.sequenceNo}: $anchorId is already assembled"
            )
            return
        }
    }

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
        // DESKTOP: androidx.sqlite.SQLiteException. With the bundled SQLite driver a broken UNIQUE
        // constraint surfaces as this type; android.database.sqlite.* exists on Android only.
        } catch (e: androidx.sqlite.SQLiteException) {
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
 * Used to enrich sendMe requests so the sender re-sends only what is actually missing instead
 * of the whole message.
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
        // The count above and this read are two separate queries: a set deleted in between
        // leaves the count stale and this row missing. See allReceivedEvent for the crash that
        // taught us the DAO must not promise a row here.
        val firstChunk = inboxDao.getMessage(messageId)
        if (firstChunk == null) {
            debugLine("inboxCheckMessageComplete", "Inbox rows for $messageId are gone, nothing to assemble")
            return
        }
        val type = firstChunk.type
        debugLine("inboxCheckMessageComplete","Message is complete, received $currentChunkNo chunks out of $expectedChunksNo, type=$type")
        if (expectedChunksNo > 1) {
            // Multi chunk (media) assembly takes seconds and must survive doze: run it under
            // WorkManager, like every other part of the send path.
            // DESKTOP: AssembleMessageWorker not called. Media assembly is not on the PC yet: the
            // complete set stays in the Inbox, unconfirmed, so the sender keeps its copy.
            debugLine("inboxCheckMessageComplete", "Media assembly not on the PC yet, $messageId left in the Inbox")
        } else {
            // Single chunk messages (text, reactions) assemble instantly: keep the original
            // inline path untouched.
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
            // DESKTOP: StalePlaceholderCheckWorker not scheduled, it concerns group content only.
        }
    } catch (e: Exception) {
        debugLine("receivingPlaceholder", "Failed to create placeholder: ${e.message}")
    }
}









