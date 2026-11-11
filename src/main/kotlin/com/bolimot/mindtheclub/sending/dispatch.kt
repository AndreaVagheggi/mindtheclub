package com.bolimot.mindtheclub.sending

// DESKTOP: this file is NOT a copy of the App's dispatch.kt, but it is the same code line for
// line wherever it can be. The differences:
//
//   - the batch tables are reached through BatchDb instead of SupportSQLiteDatabase and Cursor
//     (desktop/storage/BatchDb.kt). The SQL is the App's. getBatchesNumber therefore loses its
//     database parameter;
//   - dispatchBatch reads the unsent rows of a table FIRST and sends them afterwards, where the
//     App walks a cursor while sending. The result is the same; the PC's batch connection must
//     not be held across a send, which can wait on a full buffer;
//   - no Bluetooth: no sendCompletedAndAwaitReply, and sentAllSentEvent always fires;
//   - OutgoingActivity.touch() is gone: it tells an Android service not to drop its wake lock
//     in the middle of an upload.
//
// What is unchanged and must stay so: a chunk is marked sent when the write into the transport
// succeeds, NOT when the peer has it. There is no acknowledgement and there must not be one
// (C:\mtc\docs\arch\transport.md section 3).

import android.content.Context
import androidx.sqlite.SQLiteStatement
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.database.outbox.Outbox
import com.bolimot.mindtheclub.desktop.storage.BatchDb
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.transport.Transport
import com.bolimot.mindtheclub.transport.TransportResult
import com.bolimot.mindtheclub.transport.TransportRouter
import kotlinx.serialization.json.Json

data class DispatchResult(
    val clientResult: RTCClientResult,
    val chunksSent: Int
)

/**
 * At most this many MEDIA dispatches on the wire at once; the rest wait here. Small messages
 * (texts, reactions, profiles) skip the gate entirely: they must never wait behind a thousand
 * chunk album. See the App's file for the incident of 13 Aug that this exists for.
 */
private val mediaDispatchGate = kotlinx.coroutines.sync.Semaphore(2)
private const val MEDIA_GATE_MIN_CHUNKS = 5

/** Total chunk count of the message, read from the first batch row; 0 when unknown. */
private fun totalChunksOf(messageId: String): Int {
    return try {
        BatchDb.query("SELECT totalNo FROM batch${messageId}1 LIMIT 1") { it.getInt(0) }.firstOrNull() ?: 0
    } catch (e: Exception) {
        0
    }
}

private data class BatchOutcome(
    val success: Boolean,
    val chunksSent: Int
)

suspend fun dispatchMessage(
    messageId: String,
    remoteUserId: String,
    context: Context,
    chatGroupId: String? = null,
    originalSenderId: String? = null,
    messageDate: Long = 0L
): DispatchResult {
    var totalChunksSent = 0
    try {
        val batchesNumber = getBatchesNumber(messageId)
        val myUserId = MySelf.userId()

        if (myUserId.isNullOrEmpty()) {
            debugLine("DispatchMessage", "MyUserId is empty")
            return DispatchResult(RTCClientResult.RTCClientGeneralFailure, totalChunksSent)
        }

        if (batchesNumber == 0) {
            debugLine("DispatchMessage", "No batches found")
            return DispatchResult(RTCClientResult.RTCClientNotNeeded, totalChunksSent)
        }

        val senderContentKey = contentKeyOf(messageId, chatGroupId, originalSenderId, messageDate)

        // The permit is taken BEFORE connecting: a queued transfer must not hold a signalling
        // room open while it waits, the peer would time out in it.
        val gated = totalChunksOf(messageId) >= MEDIA_GATE_MIN_CHUNKS
        if (gated) {
            val queuedAt = System.currentTimeMillis()
            mediaDispatchGate.acquire()
            val waitedMs = System.currentTimeMillis() - queuedAt
            if (waitedMs > 1_000) {
                debugLine("DispatchMessage", "Media slot for $messageId acquired after ${waitedMs / 1000}s in queue")
            }
        }
        try {
            val transportResult = TransportRouter.connect(remoteUserId, senderContentKey, App.context())

            if (transportResult !is TransportResult.Connected) {
                return DispatchResult((transportResult as TransportResult.Failed).result, totalChunksSent)
            }

            val transport = transportResult.transport

            try {
                for (i in 1..batchesNumber) {
                    val outcome = dispatchBatch("batch$messageId$i", transport, context)
                    totalChunksSent += outcome.chunksSent
                    if (!outcome.success) {
                        debugLine("DispatchMessage", "Dispatch batch failed at batch $i of $batchesNumber batches")
                        return DispatchResult(RTCClientResult.RTCClientGeneralFailure, totalChunksSent)
                    }
                }
            } finally {
                transport.close()
            }

            debugLine("dispatchMessage", "All $batchesNumber batches sent")
            sentAllSentEvent(messageId, remoteUserId)
            return DispatchResult(RTCClientResult.RTCClientSuccess, totalChunksSent)
        } finally {
            if (gated) mediaDispatchGate.release()
        }

    } catch (ex: Exception) {
        debugLine("dispatchMessage", "Dispatch not completed because: ${ex.message}")
        return DispatchResult(RTCClientResult.RTCClientGeneralFailure, totalChunksSent)
    }
}

private suspend fun dispatchBatch(tableName: String, transport: Transport, context: Context): BatchOutcome {
    val successfulUids = mutableListOf<Int>()

    try {
        var success = true

        if (!tableExists(tableName)) {
            debugLine("dispatchBatch", "Table: $tableName does not exist")
            return BatchOutcome(success = true, chunksSent = 0)
        }

        debugLine("dispatchBatch", "Table: $tableName exists")
        val unsent = BatchDb.query("SELECT * FROM `$tableName` WHERE sent = 0") { getOutboxRecord(it) }

        if (unsent.isEmpty()) {
            debugLine("dispatchBatch", "No messages to dispatch")
            return BatchOutcome(success = true, chunksSent = 0)
        } else {
            debugLine("dispatchBatch", "Records to dispatch from this batch: ${unsent.size}")
        }

        for (outbox in unsent) {
            val jsonString = Json.encodeToString(Outbox.serializer(), outbox)

            if (!transport.sendData(jsonString.toByteArray(Charsets.UTF_8))) {
                debugLine("dispatchBatch", "Failed to send message: ${outbox.sequenceNo}")
                success = false
                break
            } else {
                debugLine("dispatchBatch", "Sent message: ${outbox.sequenceNo}")
                successfulUids.add(outbox.uid)
            }
        }

        if (successfulUids.isNotEmpty()) {
            val idsToUpdate = successfulUids.joinToString(",")
            BatchDb.exec("UPDATE `$tableName` SET sent = 1 WHERE uid IN ($idsToUpdate)")
            debugLine("dispatchBatch", "Updated sent status to TRUE for UIDs: $idsToUpdate")
        }

        return BatchOutcome(success = success, chunksSent = successfulUids.size)

    } catch (ex: Exception) {
        debugLine("dispatchBatch", "Dispatch batch failed with Exception: ${ex.message}")
        return BatchOutcome(success = false, chunksSent = successfulUids.size)
    }
}

/** One batch row as an Outbox, read by column name as the App reads it from its Cursor. */
private fun getOutboxRecord(row: SQLiteStatement): Outbox {
    val columns = row.getColumnNames()
    fun index(name: String): Int = columns.indexOf(name).also { require(it >= 0) { "missing column $name" } }
    fun text(name: String): String = index(name).let { if (row.isNull(it)) "" else row.getText(it) }
    fun textOrNull(name: String): String? = index(name).let { if (row.isNull(it)) null else row.getText(it) }

    return Outbox(
        uid = row.getInt(index("uid")),
        toUserId = text("toUserId"),
        messageId = text("messageId"),
        replyId = textOrNull("replyId"),
        groupId = text("groupId"),
        groupSize = row.getInt(index("groupSize")),
        chunkId = text("chunkId"),
        sequenceNo = row.getInt(index("sequenceNo")),
        totalNo = row.getInt(index("totalNo")),
        text = text("text"),
        textAttached = textOrNull("textAttached"),
        nameAttached = textOrNull("nameAttached"),
        content = text("content"),
        type = text("type"),
        subType = textOrNull("subType"),
        date = row.getLong(index("date")),
        sent = row.getLong(index("sent")) > 0,
        chatGroupId = textOrNull("chatGroupId"),
        originalSenderId = textOrNull("originalSenderId")
    )
}

private fun tableExists(tableName: String): Boolean {
    return BatchDb.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
        listOf(tableName)
    ) { it.getText(0) }.isNotEmpty()
}

fun getBatchesNumber(messageId: String): Int {
    return BatchDb.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE ?",
        listOf("batch$messageId%")
    ) { it.getInt(0) }.firstOrNull() ?: 0
}

fun createBatch(tableName: String, context: Context): Boolean {
    if (!tableName.matches(Regex("^[a-zA-Z0-9_]+$"))) {
        debugLine("DatabaseError", "Invalid table name: $tableName. Table not created.")
        return false
    }

    val dropTableSQL = "DROP TABLE IF EXISTS $tableName"

    val createTableSQL = """
        CREATE TABLE $tableName (
            uid INTEGER PRIMARY KEY AUTOINCREMENT,
            toUserId TEXT,
            messageId TEXT,
            replyId TEXT,
            groupId TEXT,
            groupSize INTEGER,
            chunkId TEXT,
            sequenceNo INTEGER,
            totalNo INTEGER,
            text TEXT,
            textAttached TEXT,
            nameAttached TEXT,
            content TEXT,
            type TEXT,
            subType TEXT,
            date LONG,
            sent BOOLEAN,
            chatGroupId TEXT,
            originalSenderId TEXT
        )
    """.trimIndent()

    return try {
        BatchDb.exec(dropTableSQL)
        BatchDb.exec(createTableSQL)
        debugLine("DatabaseSuccess", "Table $tableName created")
        true
    } catch (e: Exception) {
        debugLine("DatabaseError", "Error creating/replacing table $tableName: ${e.message}")
        false
    }
}

fun insertBatch(context: Context, tableName: String, outboxItems: List<Outbox>) {
    outboxItems.forEachIndexed { index, item ->
        val sql = """
            INSERT INTO $tableName (toUserId, messageId, replyId,groupId,
            groupSize, chunkId, sequenceNo, totalNo, text, textAttached,
            nameAttached, content, type, subType, date, sent, chatGroupId, originalSenderId)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val bindArgs = listOf<Any?>(
            item.toUserId,
            item.messageId,
            item.replyId,
            item.groupId,
            item.groupSize,
            // The table name, as in the App: chunkId is dead metadata, carried and never read.
            tableName,
            item.sequenceNo,
            item.totalNo,
            item.text,
            item.textAttached,
            item.nameAttached,
            item.content,
            item.type,
            item.subType,
            item.date,
            item.sent,
            item.chatGroupId,
            item.originalSenderId
        )

        try {
            BatchDb.update(sql, bindArgs)
        } catch (ex: Exception) {
            debugLine(
                "DatabaseError",
                "Failed to add to $tableName: ${index + 1}. Error: ${ex.message}"
            )
            return
        }
    }
}
