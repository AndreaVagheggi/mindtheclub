package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.storage.BatchDb
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.sendMessage
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type

/**
 * What the user interface calls to send: the PC's counterpart of the send functions in the
 * App's sending/sendManager.kt, which are written against Android views (EditText, ViewModel,
 * lifecycle scope) and cannot be copied.
 *
 * Each function does what its App twin does and no more: build the Message row exactly as the
 * App builds it, save it, hand it to sendMessage. Everything after that is the engine.
 */
object Outgoing {

    /**
     * sendManager.sendText for a 1:1 chat: a plain text, not a reply. Returns the saved
     * message, or null when there is nothing to send or it could not be saved.
     */
    suspend fun sendText(toUserId: String, text: String): Message? {
        val myUserId = MySelf.userId() ?: return null
        if (text.isEmpty()) return null

        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = text,
            textAttached = null,
            nameAttached = null,
            uri = "",
            type = Type.TEXT,
            subType = null,
            date = System.currentTimeMillis(),
            status = DesktopStrings.sending(),
            chatGroupId = null,
            originalSenderId = null,
        )

        if (!getMessageRepository(App.context()).saveMessage(message, messageIn = false)) return null
        sendMessage(MessageData.fromMessage(message))
        return message
    }

    /**
     * Picks up what a previous run left unfinished. A message that still has batch tables has
     * not been confirmed by its receiver: the batch table is the sender's state, and a
     * restarted program resumes from it (C:\mtc\docs\arch\transport.md section 2).
     *
     * This is the PC's answer to the one thing its WorkManager stand-in does not do, keep its
     * queue across a restart. Each unconfirmed message is ANNOUNCED again with a pending, not
     * dispatched: the receiver answers allReceived if it already has it, or sendMe if it does
     * not, and only then is a connection opened. A blind dispatch would open a connection for
     * every message, including the ones that arrived and only lack their confirmation.
     */
    fun resumeUnconfirmed(): Int {
        var resumed = 0
        try {
            val tables = BatchDb.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'batch%'"
            ) { it.getText(0) }

            // Already on the outgoing ladder: PendingRetryWorker re-announces those on its own
            // schedule, and announcing them here too would reset their backoff.
            val tracked = PendingMessageTracker.getAll(App.context()).map { it.messageId }.toHashSet()

            val seen = HashSet<String>()
            for (table in tables) {
                val target = BatchDb.query("SELECT messageId, toUserId FROM `$table` LIMIT 1") {
                    Pair(if (it.isNull(0)) "" else it.getText(0), if (it.isNull(1)) "" else it.getText(1))
                }.firstOrNull() ?: continue
                val (messageId, toUserId) = target
                if (messageId.isEmpty() || toUserId.isEmpty() || !seen.add(messageId)) continue
                if (messageId in tracked) continue

                debugLine("Outgoing", "Announcing unconfirmed $messageId to $toUserId again")
                notifyRemotePeer(toUserId, messageId, Notify.PENDING)
                PendingMessageTracker.record(App.context(), messageId, toUserId, messageId)
                resumed++
            }
        } catch (e: Exception) {
            debugLine("Outgoing", "Resume failed: ${e.message}")
        }
        return resumed
    }
}
