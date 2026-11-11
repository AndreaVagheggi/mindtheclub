package com.bolimot.mindtheclub.sending

// DESKTOP: this file is NOT a copy of the App's send.kt, but its functions keep the App's
// names, signatures and logic. The differences:
//
//   - sendMessage: the App's two local pseudo peers (AI assistant, note to self) and the group
//     branch are not on the PC. The trial clock, which the first message to a real person
//     starts, is recorded for the identity (it goes back to the phone with it);
//   - sendSingleMessage: the App waits for a network callback when the phone is offline. The
//     PC submits at once; with no network the dispatch fails and its own retries take over;
//   - the batch tables are reached through BatchDb, and the status text "sent" comes from
//     DesktopStrings, in the phone's language;
//   - sendMessageWork chunks ONE kind of local file, a single image, read with java.io where
//     the App goes through Android's ContentResolver. Chunk size, table size and the Base64
//     text of each chunk are the App's. Every other content that is a local file (video,
//     albums, files) is not on the PC yet and is refused here, loudly, before any table is
//     built; a text, or anything else whose content is not a local file, is one chunk as in
//     the App.

import android.content.Context
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.outbox.Outbox
import com.bolimot.mindtheclub.desktop.engine.DesktopStrings
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.desktop.storage.BatchDb
import com.bolimot.mindtheclub.desktop.storage.Prefs
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.batchContentCoordinates
import com.bolimot.mindtheclub.functions.batchTablesExists
import com.bolimot.mindtheclub.functions.countBatchChunks
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.reconcileBatchTotalNo
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.NO_PICTURE
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.works.submitDispatchWorker
import com.bolimot.mindtheclub.works.submitSendMessageWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil

private val batchBuildLock = ReentrantLock()

// DESKTOP: android.util.Base64.encodeToString(bytes, Base64.DEFAULT), byte for byte: lines of 76
// characters, each ended by a line feed, the last one included. The receiver does not care
// (it skips line breaks), but this way a chunk built on the PC is the chunk the phone builds.
private val chunkEncoder: Base64.Encoder = Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte()))

private fun encodeChunk(bytes: ByteArray): String = chunkEncoder.encodeToString(bytes) + "\n"

fun sendMessage(message: MessageData) {
    if (message.toUserId.startsWith("group")) {
        debugLine("sendMessage", "Group ${message.toUserId}: sending to a group is not on the PC yet, nothing sent")
        return
    }
    // The first message the user authors to a real person starts the 30 day trial clock
    // (TrialManager.startsTrial in the App). The clock belongs to the identity: it is kept
    // with what the phone handed over, and the server only ever moves it earlier.
    if (message.type != Type.PROFILE) {
        val carried = Prefs.of("desktop_carried")
        if (carried.getLong("trialStartedAt") == null) carried.putLong("trialStartedAt", System.currentTimeMillis())
    }
    sendSingleMessage(message)
}

fun sendSingleMessage(message: MessageData) {
    CoroutineScope(Dispatchers.IO).launch {
        submitSendMessageWorker(message, App.context())
    }
}

fun reSendMessage(userId: String, messageId: String, missingItems: List<Int>, context: Context) {
    if (CancelledTransferRegistry.isCancelled(context, messageId)) {
        debugLine("reSend", "Transfer $messageId was cancelled, ignoring resend request")
        deleteBatchTables(messageId)
        return
    }
    if (setMessageToNotSent(messageId, missingItems, context)) {
        // Carry the group coordinates so the worker is tagged by CONTENT and not just by this
        // copy's messageId (see the App's file).
        val coords = batchContentCoordinates(messageId)
        submitDispatchWorker(
            messageId, userId, context,
            groupId = coords?.groupId ?: "",
            chatGroupId = coords?.chatGroupId ?: "",
            originalSenderId = coords?.originalSenderId ?: "",
            messageDate = coords?.messageDate ?: 0L
        )
    } else {
        debugLine("reSend", "Failed in resetting sent field")
    }
}

suspend fun sendMessageId(messageId: String) {
    try {
        val message = getMessageRepository(App.context()).getMessage(messageId)
        message?.let {
            sendMessage(MessageData.fromMessage(message))
        }
    } catch (ex: Exception) {
        debugLine("sendMessageId", "Exception on sendMessageId: ${ex.message}")
    }
}

fun setMessageToNotSent(messageId: String, missingItems: List<Int>, context: Context): Boolean {
    val sequenceNoList = missingItems.joinToString(separator = ",")
    val numberOfBatches = getBatchesNumber(messageId)

    return try {
        BatchDb.transaction {
            for (i in 1..numberOfBatches) {
                val tableName = "batch${messageId}$i"

                val updateSQL: String = if (sequenceNoList.isNotEmpty()) {
                    "UPDATE $tableName SET sent = 0 WHERE sequenceNo IN ($sequenceNoList)"
                } else {
                    "UPDATE $tableName SET sent = 0"
                }
                BatchDb.exec(updateSQL)
            }
        }
        true
    } catch (e: Exception) {
        debugLine("reSend", "Error updating sent status for messageId $messageId: ${e.message}")
        false
    }
}

/**
 * Inverse of setMessageToNotSent: marks everything as already sent, then re-enables only
 * [missingItems]. Used when serving a partial sendMe on a freshly rebuilt batch, so dispatch
 * transmits just the missing chunks.
 */
fun restrictBatchToMissing(messageId: String, missingItems: List<Int>, context: Context): Boolean {
    val numberOfBatches = getBatchesNumber(messageId)
    if (numberOfBatches == 0 || missingItems.isEmpty()) return false
    val sequenceNoList = missingItems.joinToString(separator = ",")

    return try {
        BatchDb.transaction {
            for (i in 1..numberOfBatches) {
                val tableName = "batch${messageId}$i"
                BatchDb.exec("UPDATE $tableName SET sent = 1")
                BatchDb.exec("UPDATE $tableName SET sent = 0 WHERE sequenceNo IN ($sequenceNoList)")
            }
        }
        true
    } catch (e: Exception) {
        debugLine("restrictBatch", "Error restricting batch for $messageId: ${e.message}")
        false
    }
}

fun sendMessageWork(
    message: MessageData,
    context: Context,
    scope: CoroutineScope,
    resendMissing: List<Int>? = null
): Boolean {
    if (CancelledTransferRegistry.isCancelled(context, message.messageId)) {
        debugLine("sendMediaMessage", "Transfer ${message.messageId} was cancelled, aborting build")
        deleteBatchTables(message.messageId)
        return true
    }

    // Raw bytes per chunk. On the wire each becomes about 4/3 of this after Base64, plus the
    // Outbox metadata, so 40 KB here is roughly 55 KB queued. Small on purpose: a relayed path
    // has a fraction of the bandwidth of a direct one, and large chunks built a standing send
    // queue there that starved the connectivity checks (see the App's file).
    val chunkSize = 40000
    val maxChunksPerTable = 100
    var tableCounter = 1

    try {
        val isRemoteUri = message.uri?.lowercase()?.startsWith("http") == true
        val hasMedia = !message.uri.isNullOrEmpty() && message.uri != "null"
                && message.uri != NO_PICTURE
                && message.type != Type.REACTION && !isRemoteUri

        // DESKTOP: a content that is a local file and is not a single image. Returning true
        // ends the work without a retry, as the App does for an empty media: retrying cannot help.
        if (hasMedia && message.type != Type.TEXT && message.type != Type.IMAGE) {
            debugLine2("sendMediaMessage", "Media of type ${message.type} cannot be sent from the PC yet: ${message.messageId} not sent")
            return true
        }

        // DESKTOP: the image file, when this message is an image and its file is on this PC.
        // An image message that came with the phone's backup has no file here (the backup
        // carries names, not bytes), so its file is null.
        val sendsImage = hasMedia && message.type == Type.IMAGE
        val mediaFile = if (sendsImage) MediaFiles.fileOfUri(message.uri) else null

        if (batchTablesExists(message.messageId) && mediaFile != null) {
            val expectedChunks = if (mediaFile.length() > 0) ceil(mediaFile.length().toDouble() / chunkSize).toInt() else 0
            if (expectedChunks > 0) {
                val actualChunks = countBatchChunks(message.messageId)
                if (actualChunks < expectedChunks) {
                    debugLine("sendMediaMessage", "Incomplete batch tables: $actualChunks/$expectedChunks, rebuilding")
                    deleteBatchTables(message.messageId)
                }
            }
        }

        if (!batchTablesExists(message.messageId) && sendsImage) {
            if (mediaFile == null) {
                debugLine2("sendMediaMessage", "The image of ${message.messageId} is not on this PC: not sent")
                return true
            }

            val totalNumberOfChunks = if (mediaFile.length() > 0) {
                ceil(mediaFile.length().toDouble() / chunkSize).toInt()
            } else {
                0
            }

            if (totalNumberOfChunks == 0) {
                debugLine("sendMediaMessage", "Media is empty")
                return true
            }

            debugLine("sendMediaMessage", "Chunks to send: $totalNumberOfChunks")

            batchBuildLock.withLock {
                mediaFile.inputStream().use { stream ->
                    var chunksInCurrentTable = 0
                    var currentChunkIndex = 1
                    val outboxChunks = mutableListOf<Outbox>()

                    val buffer = ByteArray(chunkSize)

                    while (true) {
                        // DESKTOP: readNBytes fills the buffer unless the file ends, so every
                        // chunk but the last is exactly chunkSize and the count matches the total.
                        val bytesRead = stream.readNBytes(buffer, 0, chunkSize)
                        if (bytesRead <= 0) break

                        if (chunksInCurrentTable >= maxChunksPerTable) {
                            val tableName = "batch${message.messageId}${tableCounter}"
                            if (createBatch(tableName, context)) {
                                insertBatch(context, tableName, outboxChunks)
                            } else {
                                debugLine("sendMediaMessage", "Failed to create batch")
                                return false
                            }
                            outboxChunks.clear()
                            tableCounter++
                            chunksInCurrentTable = 0
                        }

                        val chunkData = if (bytesRead < chunkSize) buffer.copyOf(bytesRead) else buffer
                        val encodedString = encodeChunk(chunkData)
                        val text = message.text?.ifEmpty { "" } ?: ""

                        outboxChunks.add(
                            Outbox(
                                0,
                                message.toUserId,
                                message.messageId,
                                message.replyId,
                                message.groupId,
                                message.groupSize,
                                "batch${message.messageId}${tableCounter}",
                                currentChunkIndex,
                                totalNumberOfChunks,
                                text,
                                message.textAttached,
                                message.nameAttached,
                                encodedString,
                                message.type,
                                message.subType,
                                message.date,
                                false,
                                message.chatGroupId,
                                message.originalSenderId
                            )
                        )

                        currentChunkIndex++
                        chunksInCurrentTable++
                    }

                    if (outboxChunks.isNotEmpty()) {
                        val tableName = "batch${message.messageId}${tableCounter}"
                        if (createBatch(tableName, context)) {
                            insertBatch(context, tableName, outboxChunks)
                        } else {
                            debugLine("sendMediaMessage", "Failed to create batch")
                            return false
                        }
                    }

                    scope.launch {
                        val messageId = message.groupId.ifEmpty { message.messageId }
                        getMessageRepository(context).updateStatus(
                            messageId,
                            DesktopStrings.sent()
                        )
                    }
                }
            }
        }

        // DESKTOP: "&& !sendsImage". An image never goes out as a single chunk carrying its
        // file name: if its tables are not there at this point, nothing is built.
        if (!batchTablesExists(message.messageId) && !sendsImage) {
            val tableName = "batch${message.messageId}1"
            val outboxChunks = mutableListOf<Outbox>()

            // DESKTOP: in the App a text that replies to a picture carries the preview image as
            // its content, chunked like any media. Previews are not built on the PC, so a text
            // always goes out as one chunk with no content.
            val uri = if (message.uri.isNullOrEmpty() || message.type == Type.TEXT) "" else message.uri!!

            outboxChunks.add(
                Outbox(
                    0,
                    message.toUserId,
                    message.messageId,
                    message.replyId,
                    message.groupId,
                    message.groupSize,
                    tableName,
                    1,
                    1,
                    message.text!!,
                    message.textAttached,
                    message.nameAttached,
                    uri,
                    message.type,
                    message.subType,
                    message.date,
                    false,
                    message.chatGroupId,
                    message.originalSenderId
                )
            )

            if (createBatch(tableName, context)) {
                insertBatch(context, tableName, outboxChunks)
            } else {
                debugLine("sendMediaMessage", "Failed to create text batch")
                return false
            }

            if (message.type != Type.REACTION) {
                scope.launch {
                    getMessageRepository(context).updateStatus(
                        message.messageId,
                        DesktopStrings.sent(),
                    )
                }
            }
        }

        if (message.uri == null) message.uri = ""

        val reconciled = reconcileBatchTotalNo(message.messageId)
        if (reconciled > 0) {
            debugLine("sendMediaMessage", "Batch totalNo reconciled to $reconciled for ${message.messageId}")
        }

        // Partial sendMe: the requester told us which chunks it is missing, so do not
        // re-transmit the ones it already has.
        if (!resendMissing.isNullOrEmpty()) {
            if (restrictBatchToMissing(message.messageId, resendMissing, context)) {
                debugLine("sendMediaMessage", "Restricted batch to ${resendMissing.size} missing chunks for ${message.messageId}")
            }
        }

        submitDispatchWorker(message.messageId, message.toUserId, context, message.groupId, message.uri!!, message.chatGroupId ?: "", message.originalSenderId ?: "", message.date)
        return true

    } catch (ex: Exception) {
        debugLine2("sendMediaMessage", "Failed with Exception: ${ex.message}, URI: ${message.uri}")
        return false
    }
}
