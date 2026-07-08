package com.bolimot.mindtheclub.sending

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import androidx.core.content.ContextCompat.getString
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.database.AppDatabase
import com.bolimot.mindtheclub.database.outbox.Outbox
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.NoteToSelf
import com.bolimot.mindtheclub.functions.batchTablesExists
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.functions.getFileDetails
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.hasNetworkAvailable
import com.bolimot.mindtheclub.functions.timer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.works.submitDispatchWorker
import com.bolimot.mindtheclub.works.submitSendMessageWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.ceil
import com.bolimot.mindtheclub.functions.countBatchChunks
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.reconcileBatchTotalNo
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking
import com.bolimot.mindtheclub.tools.NO_PICTURE

private val batchBuildLock = ReentrantLock()

fun sendMessage(message: MessageData) {
    if(AiAssistant.isAssistant(message.toUserId)){
        AiAssistant.handleOutgoing(message)
        return
    }
    if(NoteToSelf.isNoteToSelf(message.toUserId)){
        NoteToSelf.handleOutgoing(message)
        return
    }
    if(message.toUserId.startsWith("group")){
        sendGroupMessage(message)
    } else {
        sendSingleMessage(message)
    }
}

fun sendSingleMessage(message: MessageData) {
    CoroutineScope(Dispatchers.IO).launch {
        if (hasNetworkAvailable(App.context()) || hasBluetoothPathTo(message.toUserId, App.context())) {
            submitSendMessageWorker(message, App.context())
        } else {
            deferredSendMessage(App.context(), message)
        }
    }
}

private suspend fun hasBluetoothPathTo(userId: String, context: Context): Boolean {
    if (!com.bolimot.mindtheclub.transport.BluetoothPresence.isEnabledByUser(context)) return false
    val mac = getPeerDao(context).getPeer(userId)?.bluetoothMac
    return !mac.isNullOrEmpty()
}

private fun deferredSendMessage(context: Context, message: MessageData) {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            submitSendMessageWorker(message, context)

            connectivityManager.unregisterNetworkCallback(this)
        }
    }

    connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
}

fun reSendMessage(userId: String, messageId: String, missingItems: List<Int>, context: Context){
    if (CancelledTransferRegistry.isCancelled(context, messageId)) {
        debugLine("reSend", "Transfer $messageId was cancelled, ignoring resend request")
        deleteBatchTables(messageId)
        return
    }
    if(setMessageToNotSent(messageId, missingItems, context)) {
        if (hasNetworkAvailable(App.context())
            || runBlocking { hasBluetoothPathTo(userId, App.context()) }
        ) {
            submitDispatchWorker(messageId, userId, context)
        } else {
            deferredReSendMessage(messageId, userId, context)
        }
    } else {
        debugLine("reSend", "Failed in resetting sent field")
    }
}

private fun deferredReSendMessage(messageId: String, userId: String, context: Context) {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            submitDispatchWorker(messageId, userId, context)

            connectivityManager.unregisterNetworkCallback(this)
        }
    }

    connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
}

suspend fun sendMessageId(messageId: String){
    try {
        val message = getMessageRepository(App.context()).getMessage(messageId)
        message?.let {
            sendMessage(MessageData.fromMessage(message))
        }
    } catch(ex: Exception) {
        debugLine("sendMessageId", "Exception on sendMessageId: ${ex.message}")
    }
}

fun setMessageToNotSent(messageId: String, missingItems: List<Int>, context: Context): Boolean {
    val dbInstance = AppDatabase.getInstance(context)
    val db = dbInstance.openHelper.writableDatabase
    val sequenceNoList = missingItems.joinToString(separator = ",")
    val numberOfBatches = getBatchesNumber(db, messageId)

    try {
        db.beginTransaction()
        for (i in 1..numberOfBatches) {
            val tableName = "batch${messageId}$i"

            val updateSQL: String = if(sequenceNoList.isNotEmpty()) {
                """
                UPDATE $tableName
                SET sent = 0
                WHERE sequenceNo IN ($sequenceNoList)
            """.trimIndent()
            } else {
                """
                UPDATE $tableName
                SET sent = 0
            """.trimIndent()
            }
            db.execSQL(updateSQL)
        }
        db.setTransactionSuccessful()
        return true
    } catch (e: Exception) {
        debugLine("reSend", "Error updating sent status for messageId $messageId: ${e.message}")
        return false
    } finally {
        db.endTransaction()
    }
}

fun sendMessageWork(message: MessageData, context: Context, scope: CoroutineScope): Boolean {
    if (CancelledTransferRegistry.isCancelled(context, message.messageId)) {
        debugLine("sendMediaMessage", "Transfer ${message.messageId} was cancelled, aborting build")
        deleteBatchTables(message.messageId)
        return true
    }

    val contentResolver = context.contentResolver
    val chunkSize = 100000
    val maxChunksPerTable = 100
    var tableCounter = 1

    try {
        if (batchTablesExists(message.messageId)) {
            val isRemoteUri = message.uri?.lowercase()?.startsWith("http") == true
            val hasMedia = !message.uri.isNullOrEmpty() && message.uri != "null"
                    && message.uri != NO_PICTURE
                    && message.type != Type.REACTION && !isRemoteUri
            if (hasMedia) {
                val fileDetails = getFileDetails(contentResolver, message.uri!!.toUri())
                val expectedChunks = if (fileDetails.size > 0) ceil(fileDetails.size.toDouble() / chunkSize).toInt() else 0
                if (expectedChunks > 0) {
                    val actualChunks = countBatchChunks(message.messageId)
                    if (actualChunks < expectedChunks) {
                        debugLine("sendMediaMessage", "Incomplete batch tables: $actualChunks/$expectedChunks, rebuilding")
                        deleteBatchTables(message.messageId)
                    }
                }
            }
        }
        if(!batchTablesExists(message.messageId)) {
            val isRemoteUri = message.uri?.lowercase()?.startsWith("http") == true

            if (!message.uri.isNullOrEmpty() && message.uri != "null" && message.uri != NO_PICTURE && message.type != Type.REACTION && !isRemoteUri)  {
                debugLine2("sendMediaMessage", "Sending media: ${message.uri}")
                val uri = message.uri!!.toUri()
                debugLine("sendMediaMessage", "Sending URI: $uri")

                val fileDetails = getFileDetails(contentResolver, uri)
                val totalNumberOfChunks = if (fileDetails.size > 0) {
                    ceil(fileDetails.size.toDouble() / chunkSize).toInt()
                } else {
                    0
                }

                if (totalNumberOfChunks == 0) {
                    debugLine("sendMediaMessage", "Media is empty")
                    return true
                }

                debugLine("sendMediaMessage", "Chunks to send: $totalNumberOfChunks")
                timer(true)

                batchBuildLock.withLock {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        var chunksInCurrentTable = 0
                        var currentChunkIndex = 1
                        val outboxChunks = mutableListOf<Outbox>()

                        val buffer = ByteArray(chunkSize)
                        var bytesRead: Int

                        while (stream.read(buffer).also { bytesRead = it } != -1) {
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

                            val chunkData =
                                if (bytesRead < chunkSize) buffer.copyOf(bytesRead) else buffer
                            val encodedString = Base64.encodeToString(chunkData, Base64.DEFAULT)
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
                                getString(context, R.string.sent)
                            )
                        }
                    }
                }
            } else {
                val tableName = "batch${message.messageId}1"
                val outboxChunks = mutableListOf<Outbox>()

                val uri = if (message.uri.isNullOrEmpty()) "" else message.uri!!

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
                            getString(context, R.string.sent),
                        )
                    }
                }
            }
        }

        if (message.uri == null) message.uri = ""

        val reconciled = reconcileBatchTotalNo(message.messageId)
        if (reconciled > 0) {
            debugLine("sendMediaMessage", "Batch totalNo reconciled to $reconciled for ${message.messageId}")
        }

        submitDispatchWorker(message.messageId, message.toUserId, context, message.groupId, message.uri!!, message.chatGroupId ?: "", message.originalSenderId ?: "", message.date)
        return true

    } catch (ex: Exception) {
        debugLine2("sendMediaMessage", "Failed with Exception: ${ex.message}, URI: ${message.uri}")
        return false
    }
}