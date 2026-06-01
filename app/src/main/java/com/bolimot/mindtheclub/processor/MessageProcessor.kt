package com.bolimot.mindtheclub.processor

import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.receiving.inboxCheckMessageComplete
import com.bolimot.mindtheclub.receiving.receiveData
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedList

// SOAK CHANGE, 01/03 replace with new class
object MessageProcessor {
    private val messageChannel = Channel<Pair<String, String>>(capacity = 64)
    private var inboxDao: InboxDao = getInboxDao(App.context().applicationContext)

    @Volatile private var queuedMessagesCount = 0

    private val checkMutex = Mutex()
    private val checkQueue = LinkedList<String>()
    private val checkPending = HashSet<String>()
    private val checkSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            debugLine("MessageProcessor", "Data Received")
            for (messagePair in messageChannel) {
                queuedMessagesCount--
                val (userId, message) = messagePair
                debugLine("MessageProcessor", "Writing message to database. Current queue size: $queuedMessagesCount")
                receiveData(userId, message, inboxDao)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            for (signal in checkSignal) {
                while (true) {
                    val messageId = checkMutex.withLock {
                        checkQueue.poll()?.also { checkPending.remove(it) }
                    } ?: break
                    debugLine("MessageProcessor", "Processing inbox check for: $messageId")
                    inboxCheckMessageComplete(messageId, inboxDao)
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun enqueueMessage(userId: String, message: String) {
        if (!messageChannel.isClosedForSend) {
            runBlocking {
                messageChannel.send(Pair(userId, message))
            }
            queuedMessagesCount++
            debugLine("MessageProcessor", "Message enqueued. Current queue size: $queuedMessagesCount")
        }
    }

    fun requestInboxCheck(messageId: String) {
        runBlocking {
            checkMutex.withLock {
                if (checkPending.add(messageId)) {
                    checkQueue.add(messageId)
                    debugLine("MessageProcessor", "Inbox check queued for: $messageId (pending: ${checkPending.size})")
                } else {
                    debugLine("MessageProcessor", "Inbox check already pending for: $messageId, skipping")
                }
            }
        }
        checkSignal.trySend(Unit)
    }
}