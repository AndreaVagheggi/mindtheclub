package com.bolimot.mindtheclub.transport

import android.content.Context
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.receiving.checkIfMessageIsCompleted
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.processor.MessageProcessor
import com.bolimot.mindtheclub.sending.reSendMessage
import com.bolimot.mindtheclub.tools.Notify

internal object BluetoothControl {

    fun completedFrame(messageId: String) = "${Notify.COMPLETED} $messageId"

    suspend fun handleCompletedAndBuildReply(messageId: String): String {
        return when (val missing = checkIfMessageIsCompleted(messageId)) {
            null -> {
                MessageProcessor.requestInboxCheck(messageId)
                "${Notify.ALL_RECEIVED} $messageId"
            }
            else -> if (missing.isEmpty()) {
                "${Notify.ALL_MISSING} $messageId"
            } else {
                "${Notify.SOME_MISSING} $messageId ${missing.min()},${missing.max()}"
            }
        }
    }

    suspend fun handleReply(reply: String, remoteUserId: String, context: Context = App.context()) {
        val parts = reply.split(" ")
        val type = parts.getOrNull(0) ?: return
        val messageId = parts.getOrNull(1) ?: return

        when (type) {
            Notify.ALL_RECEIVED -> {
                val delivered = ContextCompat.getString(context, R.string.delivered)
                getMessageRepository(context).updateStatus(messageId, delivered)
                deleteBatchTables(messageId)
                PendingMessageTracker.remove(context, messageId, remoteUserId)
                debugLine("BluetoothControl", "Delivery confirmed over Bluetooth: $messageId")
            }
            Notify.ALL_MISSING -> {
                debugLine("BluetoothControl", "Peer reports all missing: $messageId — resending")
                reSendMessage(remoteUserId, messageId, emptyList(), context)
            }
            Notify.SOME_MISSING -> {
                val range = parts.getOrNull(2)?.split(",")
                val low = range?.getOrNull(0)?.toIntOrNull()
                val high = range?.getOrNull(1)?.toIntOrNull()
                if (low != null && high != null) {
                    debugLine("BluetoothControl", "Peer reports missing $low..$high: $messageId — resending range")
                    reSendMessage(remoteUserId, messageId, (low..high).toList(), context)
                }
            }
            else -> debugLine("BluetoothControl", "Unknown reply: $reply")
        }
    }
}
