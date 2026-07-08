package com.bolimot.mindtheclub.sending

import android.content.Context
import androidx.work.WorkManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.works.dispatchMessageTag

/**
 * Cancellation of an in-flight 1:1 transfer, from either end.
 *
 * Group messages are excluded on purpose: they travel by gossip through several
 * members, so a single FCM to one peer cannot revoke them (and notifyRemotePeer
 * refuses group ids anyway). The UI never offers the cancel action for them.
 *
 * Delivery wins every race: a cancel arriving after the message completed on the
 * receiver, or after the sender saw Delivered/Seen, is ignored.
 */

// Sender side: the user withdrew an outgoing message that is not yet Delivered.
suspend fun cancelOutgoingSend(message: Message, context: Context) {
    val messageId = message.messageId
    debugLine("cancelTransfer", "Sender cancelling outgoing message $messageId")

    CancelledTransferRegistry.markCancelled(context, messageId)
    stopSendPipeline(messageId, message.toUserId, context)

    getMessageRepository(context).deleteMessages(listOf(message))

    notifyRemotePeer(message.toUserId, messageId, Notify.CANCEL_TRANSFER)
}

// Receiver side: the user refused an incoming transfer (swiped the placeholder away).
suspend fun cancelIncomingTransfer(placeholder: Message, context: Context) {
    val messageId = placeholder.messageId
    debugLine("cancelTransfer", "Receiver cancelling incoming transfer $messageId")

    CancelledTransferRegistry.markCancelled(context, messageId)

    val inboxDao = getInboxDao(context)
    inboxDao.deleteByContent(resolveContentKey(inboxDao, messageId))

    getMessageRepository(context).deleteMessages(listOf(placeholder))

    notifyRemotePeer(placeholder.fromUserId, messageId, Notify.CANCEL_TRANSFER)
}

// FCM side: the remote peer cancelled the transfer of [messageId].
suspend fun receiveTransferCancelled(fromUserId: String, messageId: String, context: Context) {
    val repository = getMessageRepository(context)
    val message = repository.getMessage(messageId)
    val myUserId = MySelf.userId() ?: return

    when {
        message == null -> {
            // Chunks may exist without a placeholder yet (single-chunk message or
            // a cancel that outran the data). Only act if the chunks really came
            // from the peer that claims to cancel.
            val inboxDao = getInboxDao(context)
            val contentKey = resolveContentKey(inboxDao, messageId)
            val firstChunk = inboxDao.getFirstByContent(contentKey)
            if (firstChunk != null && firstChunk.fromUserId != fromUserId) {
                debugLine("cancelTransfer", "Ignoring cancel for $messageId: chunk sender mismatch")
                return
            }
            CancelledTransferRegistry.markCancelled(context, messageId)
            val deleted = inboxDao.deleteByContent(contentKey)
            debugLine("cancelTransfer", "Remote cancel for $messageId (no message row), dropped $deleted chunk(s)")
        }

        // I am the sender: the receiver refused the transfer.
        message.fromUserId == myUserId && message.toUserId == fromUserId -> {
            val delivered = context.getString(R.string.delivered)
            if (message.status == Notify.SEEN || message.status == delivered) {
                debugLine("cancelTransfer", "Ignoring remote cancel for $messageId: already ${message.status}")
                return
            }
            CancelledTransferRegistry.markCancelled(context, messageId)
            stopSendPipeline(messageId, fromUserId, context)
            repository.updateStatus(messageId, context.getString(R.string.cancelled))
            debugLine("cancelTransfer", "Receiver $fromUserId cancelled $messageId, status set to cancelled")
        }

        // I am the receiver: the sender withdrew the message while still incoming.
        message.status == Status.RECEIVING && message.fromUserId == fromUserId -> {
            CancelledTransferRegistry.markCancelled(context, messageId)
            val inboxDao = getInboxDao(context)
            inboxDao.deleteByContent(resolveContentKey(inboxDao, messageId))
            repository.deleteMessages(listOf(message))
            debugLine("cancelTransfer", "Sender $fromUserId cancelled $messageId, placeholder removed")
        }

        else -> {
            debugLine("cancelTransfer", "Ignoring cancel for $messageId from $fromUserId: status=${message.status}")
        }
    }
}

// Stops every sender-side vehicle that could still move this message.
private fun stopSendPipeline(messageId: String, toUserId: String, context: Context) {
    val workManager = WorkManager.getInstance(context)
    workManager.cancelUniqueWork("build$messageId")   // SendMessageWorker (batch build)
    workManager.cancelUniqueWork(messageId)           // DispatchWorker
    workManager.cancelAllWorkByTag(dispatchMessageTag(messageId))

    deleteBatchTables(messageId)
    PendingMessageTracker.remove(context, messageId, toUserId)
}
