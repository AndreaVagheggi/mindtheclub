package com.bolimot.mindtheclub.sending

import com.bolimot.mindtheclub.functions.OutgoingActivity
import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.database.database.AppDatabase
import com.bolimot.mindtheclub.database.outbox.Outbox
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.transport.Transport
import kotlinx.serialization.json.Json
import com.bolimot.mindtheclub.transport.TransportRouter
import com.bolimot.mindtheclub.transport.TransportResult

data class DispatchResult(
    val clientResult: RTCClientResult,
    val chunksSent: Int
)

/**
 * At most this many MEDIA dispatches on the wire at once; the rest wait here.
 *
 * Without the gate the sender pushes every transfer in parallel: on 13 Aug Raoul's phone ran 3
 * contents by 4 receivers together, the shared uplink saturated, every data channel buffer filled
 * (20.804 backpressure waits, chunks abandoned after 15s of delay), dispatches died in bulk (1.520
 * GENERAL_FAILURE against 16 successes) and the receivers' recovery requests multiplied the load
 * further. Serialised, each transfer takes the full uplink and finishes quickly, so buffers never
 * fill and the recovery machinery stays quiet. Two permits and not one, so a stalled transfer
 * cannot freeze the queue while its timeouts run.
 *
 * Small messages (texts, reactions, profiles) skip the gate entirely: they must never wait behind
 * a thousand chunk album.
 */
private val mediaDispatchGate = kotlinx.coroutines.sync.Semaphore(2)
private const val MEDIA_GATE_MIN_CHUNKS = 5

/** Total chunk count of the message, read from the first batch row; 0 when unknown. */
private fun totalChunksOf(db: SupportSQLiteDatabase, messageId: String): Int {
    return try {
        val cursor = db.query("SELECT totalNo FROM batch${messageId}1 LIMIT 1", emptyArray())
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) {
        0
    }
}

private data class BatchOutcome(
    val success: Boolean,
    val chunksSent: Int
)

@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
suspend fun dispatchMessage(
    messageId: String,
    remoteUserId: String,
    context: Context,
    chatGroupId: String? = null,
    originalSenderId: String? = null,
    messageDate: Long = 0L
): DispatchResult{
    var totalChunksSent = 0
    try {
        val db = AppDatabase.getInstance(context).openHelper.readableDatabase
        val batchesNumber = getBatchesNumber(db, messageId)
        val myUserId = MySelf.userId()

        if(myUserId.isNullOrEmpty()){
            debugLine("DispatchMessage","MyUserId is empty")
            return DispatchResult(RTCClientResult.RTCClientGeneralFailure, totalChunksSent)
        }

        if(batchesNumber == 0){
            debugLine("DispatchMessage","No batches found")
            return DispatchResult(RTCClientResult.RTCClientNotNeeded, totalChunksSent)
        }

        val senderContentKey = contentKeyOf(messageId, chatGroupId, originalSenderId, messageDate)

        // The permit is taken BEFORE connecting: a queued transfer must not hold a signalling
        // room open while it waits, the peer would time out in it. If the acquire itself is
        // cancelled we have taken nothing, so only a successful acquire is paired with the
        // release in finally.
        val gated = totalChunksOf(db, messageId) >= MEDIA_GATE_MIN_CHUNKS
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
                        debugLine("DispatchMessage","Dispatch batch failed at batch $i of $batchesNumber batches")
                        return DispatchResult(RTCClientResult.RTCClientGeneralFailure, totalChunksSent)
                    }
                }
            } finally {
                if (transport is com.bolimot.mindtheclub.transport.BluetoothTransport) {
                    transport.sendCompletedAndAwaitReply(messageId, remoteUserId)
                }
                transport.close()
            }

            debugLine("dispatchMessage", "All $batchesNumber batches sent")
            if (transport !is com.bolimot.mindtheclub.transport.BluetoothTransport) {
                sentAllSentEvent(messageId, remoteUserId)
            }
            return DispatchResult(RTCClientResult.RTCClientSuccess, totalChunksSent)
        } finally {
            if (gated) mediaDispatchGate.release()
        }

    } catch(ex: Exception){
        debugLine("dispatchMessage", "Dispatch not completed because: ${ex.message}")
        return DispatchResult(RTCClientResult.RTCClientGeneralFailure, totalChunksSent)
    }
}

private suspend fun dispatchBatch(tableName: String, transport: Transport, context: Context): BatchOutcome {
    val db = AppDatabase.getInstance(context).openHelper.writableDatabase
    val successfulUids = mutableListOf<Int>()

    try {
        var success = true

        if (!tableExists(db, tableName)) {
            debugLine("dispatchBatch", "Table: $tableName does not exist")
            return BatchOutcome(success = true, chunksSent = 0)
        }

        debugLine("dispatchBatch", "Table: $tableName exists")
        val selectSQL = "SELECT * FROM $tableName WHERE sent = 0"
        val cursor = db.query(selectSQL, emptyArray())

        cursor.use { cur ->
            if (!cur.moveToFirst()) {
                debugLine("dispatchBatch", "No messages to dispatch")
                return BatchOutcome(success = true, chunksSent = 0)
            }
            else {
                debugLine("dispatchBatch", "Records to dispatch from this batch: ${cur.count}")
            }

            do {
                val outbox = getOutboxRecord(cur)

                val jsonString = Json.encodeToString(outbox)

                if (!transport.sendData(jsonString.toByteArray(Charsets.UTF_8))) {
                    debugLine("dispatchBatch", "Failed to send message: ${outbox.sequenceNo}")
                    success = false
                    break
                } else {
                    // Direct proof that this phone is transmitting, read by DataSyncService so it
                    // does not release the wake lock in the middle of an upload. See
                    // OutgoingActivity.
                    OutgoingActivity.touch()
                    debugLine("dispatchBatch", "Sent message: ${outbox.sequenceNo}")
                    successfulUids.add(outbox.uid)
                }
            } while (cur.moveToNext())
        }

        if (successfulUids.isNotEmpty()) {
            val idsToUpdate = successfulUids.joinToString(",")
            val updateSQL = "UPDATE `$tableName` SET sent = 1 WHERE uid IN ($idsToUpdate)"
            db.execSQL(updateSQL)
            debugLine("dispatchBatch", "Updated sent status to TRUE for UIDs: $idsToUpdate")
        }

        return BatchOutcome(success = success, chunksSent = successfulUids.size)

    } catch (ex: Exception) {
        debugLine("dispatchBatch", "Dispatch batch failed with Exception: ${ex.message}")
        return BatchOutcome(success = false, chunksSent = successfulUids.size)
    }
}

private fun getOutboxRecord(cur: Cursor): Outbox {
    val uid = cur.getInt(cur.getColumnIndexOrThrow("uid"))
    val toUserId = cur.getString(cur.getColumnIndexOrThrow("toUserId"))
    val id= cur.getString(cur.getColumnIndexOrThrow("messageId"))
    val replyId= cur.getString(cur.getColumnIndexOrThrow("replyId"))
    val groupId= cur.getString(cur.getColumnIndexOrThrow("groupId"))
    val groupSize= cur.getInt(cur.getColumnIndexOrThrow("groupSize"))
    val chunkId = cur.getString(cur.getColumnIndexOrThrow("chunkId"))
    val sequenceNo = cur.getInt(cur.getColumnIndexOrThrow("sequenceNo"))
    val totalNo = cur.getInt(cur.getColumnIndexOrThrow("totalNo"))
    val text = cur.getString(cur.getColumnIndexOrThrow("text"))
    val textAttached = cur.getString(cur.getColumnIndexOrThrow("textAttached"))
    val nameAttached = cur.getString(cur.getColumnIndexOrThrow("nameAttached"))
    val content = cur.getString(cur.getColumnIndexOrThrow("content"))
    val type = cur.getString(cur.getColumnIndexOrThrow("type"))
    val subType = cur.getString(cur.getColumnIndexOrThrow("subType"))
    val sent = cur.getInt(cur.getColumnIndexOrThrow("sent")) > 0
    val date = cur.getLong(cur.getColumnIndexOrThrow("date"))
    val chatGroupId= cur.getString(cur.getColumnIndexOrThrow("chatGroupId"))
    val originalSenderId= cur.getString(cur.getColumnIndexOrThrow("originalSenderId"))

    return Outbox(
        uid = uid,
        toUserId = toUserId,
        messageId = id,
        replyId = replyId,
        groupId = groupId,
        groupSize = groupSize,
        chunkId = chunkId,
        sequenceNo = sequenceNo,
        totalNo = totalNo,
        text = text,
        textAttached = textAttached,
        nameAttached = nameAttached,
        content = content,
        type = type,
        subType = subType,
        date = date,
        sent = sent,
        chatGroupId = chatGroupId,
        originalSenderId = originalSenderId
    )
}

private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
    val query = "SELECT name FROM sqlite_master WHERE type='table' AND name=?"
    val cursor = db.query(query, arrayOf(tableName))
    val exists = cursor.moveToFirst()
    cursor.close()
    return exists
}

fun getBatchesNumber(db: SupportSQLiteDatabase, messageId: String): Int {
    val safeMessageId = messageId.replace("'", "''")
    val selectSQL = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE 'batch${safeMessageId}%'"
    val cursor = db.query(selectSQL, emptyArray())
    var count = 0

    if (cursor.moveToFirst()) {
        count = cursor.getInt(0)
    }
    cursor.close()
    return count
}

fun createBatch(tableName: String, context: Context): Boolean {
    val db = AppDatabase.getInstance(context).openHelper.writableDatabase

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
        db.execSQL(dropTableSQL)
        db.execSQL(createTableSQL)
        debugLine("DatabaseSuccess", "Table $tableName created")
        true
    } catch (e: Exception) {
        debugLine("DatabaseError", "Error creating/replacing table $tableName: ${e.message}")
        false
    }
}

fun insertBatch(context: Context, tableName: String, outboxItems: List<Outbox>) {
    val db = AppDatabase.getInstance(context).openHelper.writableDatabase

    outboxItems.forEachIndexed { index, item ->
        val sql = """
            INSERT INTO $tableName (toUserId, messageId, replyId,groupId, 
            groupSize, chunkId, sequenceNo, totalNo, text, textAttached, 
            nameAttached, content, type, subType, date, sent, chatGroupId, originalSenderId)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val bindArgs = arrayOf<Any?>(
            item.toUserId,
            item.messageId,
            item.replyId,
            item.groupId,
            item.groupSize.toString(),
            tableName,
            item.sequenceNo.toString(),
            item.totalNo.toString(),
            item.text,
            item.textAttached,
            item.nameAttached,
            item.content,
            item.type,
            item.subType,
            item.date,
            if (item.sent) "1" else "0",
            item.chatGroupId,
            item.originalSenderId
        )

        try {
            db.execSQL(sql, bindArgs)
        } catch (ex: Exception) {
            debugLine(
                "DatabaseError",
                "Failed to add to $tableName: ${index + 1}. Error: ${ex.message}"
            )
            return
        }
    }
}