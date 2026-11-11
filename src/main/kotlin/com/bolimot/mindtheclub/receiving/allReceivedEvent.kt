package com.bolimot.mindtheclub.receiving

// DESKTOP: this file is NOT a copy of the App's allReceivedEvent.kt. fullMessageReceivedEvent
// keeps the App's structure and order (the processing claim, the read that tolerates a missing
// row, the blocked sender, the content check, then the type), with these differences:
//
//   - ONLY TEXT AND SINGLE IMAGES ARE PROCESSED. Every other type (video, files, albums, profile,
//     reaction, sticker, gif, link, audio, contact) is left exactly where it is: its chunks stay
//     in the Inbox and nothing is confirmed, so the sender keeps its copy and goes on announcing it.
//     Confirming a content this program cannot save would make the sender delete it
//     (docs/arch/transport.md section 2). When the identity goes back to the phone the phone
//     asks for it again and gets it.
//   - the group branches are gone (the echo of my own group message, stopRedundantSenders):
//     group content never gets this far, receiveData refuses it;
//   - MessageNotifier (a LiveData the chat screen observes) is replaced by DesktopEvents, which
//     the save itself already calls;
//   - getFullMessage keeps its behaviour, with java.util.Base64 and a file URI in place of the
//     Android classes; addIncomingContact is not here (contact acquisition is not on the PC).

import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.resolveContentKey
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.Collections

private val activeProcessing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

fun isProcessingActive(messageId: String): Boolean = messageId in activeProcessing

suspend fun fullMessageReceivedEvent(messageId: String): Boolean {
    if (!activeProcessing.add(messageId)) {
        debugLine("receivedEvent", "Already processing $messageId, skipping")
        return false
    }
    try {
        val inboxDao: InboxDao = getInboxDao(App.context())

        // Read once, and survive the row not being there: the chunks can be deleted between the
        // completeness check that got us here and this read (see the App's file for the crash
        // of 23 Aug that taught it).
        val message = inboxDao.getMessage(messageId)
        if (message == null) {
            debugLine("receivedEvent", "Inbox rows for $messageId are gone, nothing to assemble")
            return false
        }
        val fromUserId = message.fromUserId

        if (getBlockedUserRepository(App.context()).isBlocked(fromUserId)) {
            debugLine("receivedEvent", "Blocked user $fromUserId, discarding completed message")
            val blockedContentKey = resolveContentKey(inboxDao, messageId)
            inboxDao.deleteByContent(blockedContentKey)
            return false
        }

        val type = message.type

        // DESKTOP: see the note at the top. Nothing is deleted and nothing is confirmed.
        if (type != Type.TEXT && type != Type.IMAGE) {
            debugLine("receivedEvent", "Type $type is not on the PC yet: $messageId stays in the Inbox, unconfirmed")
            return false
        }

        // As in the App: a media content is assembled by its own receive function, straight
        // from the chunks, and never passes through getFullMessage below.
        if (type == Type.IMAGE) {
            debugLine("receivedEvent", "Message received, type = $type")
            receiveImage(messageId, "", message.text, fromUserId)
            return true
        }

        val content = getFullMessage(message, inboxDao, type)

        if (content.isNullOrEmpty()) {
            debugLine("receivedEvent", "CRITICAL Received event, content is NULL")

            val nullContentKey = resolveContentKey(inboxDao, messageId)
            inboxDao.deleteByContent(nullContentKey)
            notifyRemotePeer(message.fromUserId, message.messageId, Notify.ALL_MISSING)

            return false
        }

        debugLine("receivedEvent", "Message received, type = $type")

        // DESKTOP: for a text the assembled file is only the proof that the chunk is readable;
        // the text itself is in the Inbox row. Removed here so the scratch folder does not fill
        // with empty files.
        if (content.startsWith("file:")) {
            try {
                File(java.net.URI(content)).delete()
            } catch (e: Exception) {
                // Not worth a log line.
            }
        }

        receiveText(messageId)
        return true
    } finally {
        activeProcessing.remove(messageId)
    }
}

suspend fun getFullMessage(message: Inbox, inboxDao: InboxDao, type: String): String? {
    val messageId = message.messageId
    val totalChunks = message.totalNo
    val context = App.context()
    val contentKey = contentKeyOf(message)

    if (totalChunks == 1) {
        val firstChunk = inboxDao.getChunkContentByContent(contentKey, 1)
        if (firstChunk != null && firstChunk.startsWith("http", ignoreCase = true)) {
            return firstChunk
        }
    }

    val fileName: String = if (type == "image") "${messageId}_temp.jpg" else "full${messageId}.dat"

    val file = File(context.filesDir, fileName)

    val success = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(file).use { fileOutputStream ->
                for (i in 1..totalChunks) {
                    val chunkContent = inboxDao.getChunkContentByContent(contentKey, i)

                    if (chunkContent != null) {
                        // android.util.Base64.DEFAULT tolerates line breaks: so does the MIME decoder.
                        val decodedChunk = Base64.getMimeDecoder().decode(chunkContent)
                        fileOutputStream.write(decodedChunk)
                    } else {
                        debugLine("getFullMessage", "CRITICAL ERROR: Chunk $i is missing from DB!")
                        return@use false
                    }
                }
                return@use true
            }
        } catch (e: Exception) {
            debugLine("getFullMessage", "Error writing file: ${e.message}")
            return@withContext false
        }
    }

    if (!success) return null

    return file.toURI().toString()
}
