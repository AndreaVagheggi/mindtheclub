package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.desktop.backup.BackupExporter
import com.bolimot.mindtheclub.desktop.backup.BackupImporter
import com.bolimot.mindtheclub.desktop.engine.DesktopEvents
import com.bolimot.mindtheclub.desktop.engine.Engine
import com.bolimot.mindtheclub.desktop.engine.Outgoing
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.desktop.media.VideoCompressor
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File

/** Which screen the window shows. */
sealed interface Screen {
    /** Something is in progress; [what] says what. */
    data class Busy(val what: String) : Screen

    /** No identity here, or it was handed back: a backup must be loaded. [note] explains why. */
    data class NeedBackup(val note: String) : Screen

    /** The engine did not start. Retrying is possible. */
    data class Failed(val message: String) : Screen

    data object Chats : Screen
}

/** One row of the contact list. */
class ChatItem(
    val userId: String,
    val name: String,
    /** The contact's picture as stored (a file: URI on this PC), or null. */
    val picture: String?,
    val preview: String,
    val lastDate: Long,
    val unread: Int
)

/**
 * Everything the window shows, and what the user can do. The screens only read this and call
 * its functions; this talks to the engine. Nothing here decides how a message travels: that is
 * the engine's business, and this is the one place the interface touches it.
 *
 * All work runs on [scope], off the interface thread. The state it changes is Compose state,
 * which can be written from any thread.
 */
class AppState(private val scope: CoroutineScope) {

    var screen: Screen by mutableStateOf<Screen>(Screen.Busy(Strings.STARTING))
        private set

    var myName: String by mutableStateOf("")
        private set

    val chats = mutableStateListOf<ChatItem>()
    val messages = mutableStateListOf<Message>()

    var selectedUserId: String? by mutableStateOf<String?>(null)
        private set
    var selectedName: String by mutableStateOf("")
        private set

    /** Shown as a banner when the push channel drops: signals no longer arrive. */
    var warning: String? by mutableStateOf<String?>(null)
        private set

    /** How many videos are being re-encoded right now, before they are sent. Shown as a notice. */
    var videosBeingPrepared: Int by mutableStateOf(0)
        private set

    /** Shown under the form of the screen that asked for an action which failed. */
    var formError: String? by mutableStateOf<String?>(null)
        private set

    private var myUserId: String = ""

    // Reloads are queued one behind the other: two events arriving together must not
    // interleave their writes to the lists.
    private val reloadLock = Mutex()

    fun boot() = scope.launch(Dispatchers.IO) {
        if (!KeyVault.exists()) {
            screen = Screen.NeedBackup(Strings.NO_IDENTITY)
        } else if (Engine.isDeactivated()) {
            screen = Screen.NeedBackup(Strings.IDENTITY_MOVED)
        } else {
            startEngine()
        }
    }

    fun retry() = scope.launch(Dispatchers.IO) { startEngine() }

    private suspend fun startEngine() {
        screen = Screen.Busy(Strings.CONNECTING)
        formError = null
        warning = null

        // The engine reports here. Set before it starts, so nothing is missed.
        DesktopEvents.onMessageSaved = { message -> scope.launch(Dispatchers.IO) { onMessageSaved(message) } }
        DesktopEvents.onStatusChanged = { _, _ -> scope.launch(Dispatchers.IO) { reload() } }
        DesktopEvents.chatOnScreen = { userId -> userId == selectedUserId }
        Engine.onPushLost = { warning = Strings.PUSH_LOST }

        try {
            Engine.start()
        } catch (e: Engine.StartFailed) {
            screen = if (Engine.isDeactivated()) Screen.NeedBackup(Strings.IDENTITY_MOVED)
            else Screen.Failed(e.message ?: Strings.START_FAILED)
            return
        } catch (e: Throwable) {
            screen = Screen.Failed("${Strings.START_FAILED} ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        myUserId = MySelf.userId() ?: ""
        myName = MySelf.name() ?: ""
        reload()
        screen = Screen.Chats
    }

    /** Loads a phone backup, replacing whatever is on this PC, then starts. */
    fun loadBackup(file: File, password: String) = scope.launch(Dispatchers.IO) {
        formError = null
        val previous = screen
        screen = Screen.Busy(Strings.LOADING_BACKUP)
        try {
            Engine.stop()
            BackupImporter.loadReplacing(file, password.toCharArray())
        } catch (e: BackupImporter.Refused) {
            screen = previous
            formError = e.message
            return@launch
        } catch (e: Throwable) {
            screen = previous
            formError = "${e.javaClass.simpleName}: ${e.message}"
            return@launch
        }
        selectedUserId = null
        selectedName = ""
        messages.clear()
        chats.clear()
        startEngine()
    }

    /**
     * The way back: writes a backup for the phone and gives the identity up. On success this
     * PC is deactivated and the window asks for a backup again.
     */
    fun returnToPhone(file: File, password: String, onDone: (String?) -> Unit) = scope.launch(Dispatchers.IO) {
        try {
            BackupExporter.write(file, password.toCharArray())
        } catch (e: BackupExporter.Refused) {
            onDone(e.message)
            return@launch
        } catch (e: Throwable) {
            onDone("${e.javaClass.simpleName}: ${e.message}")
            return@launch
        }
        Engine.handBack()
        selectedUserId = null
        messages.clear()
        chats.clear()
        onDone(null)
        screen = Screen.NeedBackup(Strings.handedBack(file.absolutePath))
    }

    fun select(userId: String) = scope.launch(Dispatchers.IO) {
        selectedUserId = userId
        selectedName = chats.firstOrNull { it.userId == userId }?.name ?: ""
        reload()
        markSeen(userId)
    }

    fun send(text: String) {
        val to = selectedUserId ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                Outgoing.sendText(to, body)
            } catch (e: Throwable) {
                warning = "${Strings.SEND_FAILED} ${e.message}"
            }
            reload()
        }
    }

    /** Sends the picture in [file] to the open chat, with [caption] under it (may be empty). */
    fun sendImage(file: File, caption: String) {
        val to = selectedUserId ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (Outgoing.sendImage(to, file, caption.trim()) == null) warning = Strings.SEND_FAILED
            } catch (e: MediaFiles.Refused) {
                warning = Strings.PHOTO_UNREADABLE
            } catch (e: Throwable) {
                warning = "${Strings.SEND_FAILED} ${e.message}"
            }
            reload()
        }
    }

    /** Sends the video in [file] to the open chat, with [caption] under it (may be empty). */
    fun sendVideo(file: File, caption: String) {
        val to = selectedUserId ?: return
        scope.launch(Dispatchers.IO) {
            // A large video is re-encoded before it leaves, which takes a while: the window
            // says so, as the phone's Send screen does, until the message is in the chat.
            val preparing = VideoCompressor.mayCompress(file)
            if (preparing) videosBeingPrepared++
            try {
                if (Outgoing.sendVideo(to, file, caption.trim()) == null) warning = Strings.SEND_FAILED
            } catch (e: MediaFiles.Refused) {
                warning = Strings.FILE_UNREADABLE
            } catch (e: Throwable) {
                warning = "${Strings.SEND_FAILED} ${e.message}"
            } finally {
                if (preparing) videosBeingPrepared--
            }
            reload()
        }
    }

    /** Sends [file], whatever it is, to the open chat, with [caption] under it (may be empty). */
    fun sendFile(file: File, caption: String) {
        val to = selectedUserId ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (Outgoing.sendFile(to, file, caption.trim()) == null) warning = Strings.SEND_FAILED
            } catch (e: MediaFiles.Refused) {
                warning = Strings.FILE_UNREADABLE
            } catch (e: Throwable) {
                warning = "${Strings.SEND_FAILED} ${e.message}"
            }
            reload()
        }
    }

    /** Shares the contacts in [userIds] with the open chat, one message each, as the phone does. */
    fun sendContacts(userIds: List<String>) {
        val to = selectedUserId ?: return
        scope.launch(Dispatchers.IO) {
            for (userId in userIds) {
                try {
                    if (Outgoing.sendContact(to, userId) == null) warning = Strings.SEND_FAILED
                } catch (e: Throwable) {
                    warning = "${Strings.SEND_FAILED} ${e.message}"
                }
            }
            reload()
        }
    }

    /** The contacts that can be shared in the open chat: all of them but the one being written to. */
    fun shareableContacts(): List<ChatItem> = chats.filter { it.userId != selectedUserId }

    /** The contact a contact message carries: its text is the contact's record, as JSON. */
    fun contactOf(message: Message): Peer? {
        return try {
            contactJson.decodeFromString(Peer.serializer(), message.text)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens the file of a file message or of a video message, if this PC has it.
     *
     * A received file is whatever its sender chose to send, and opening a file on a PC can mean
     * RUNNING it. So only the kinds that are documents, pictures, sound and video are handed to
     * the program Windows associates with them; for anything else the folder is opened with the
     * file selected, and what to do with it is left to the user.
     */
    fun openFile(message: Message) {
        scope.launch(Dispatchers.IO) {
            val file = MediaFiles.fileFor(message) ?: return@launch
            try {
                if (file.extension.lowercase() in OPENED_DIRECTLY) {
                    Desktop.getDesktop().open(file)
                } else {
                    ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                }
            } catch (e: Throwable) {
                // No program for this file: nothing to open it with.
            }
        }
    }

    fun dismissWarning() {
        warning = null
    }

    private suspend fun onMessageSaved(message: Message) {
        reload()
        // A message arriving in the chat the user is looking at is read as it arrives.
        if (message.fromUserId == selectedUserId && message.status != Status.RECEIVING) {
            markSeen(message.fromUserId)
        }
    }

    /** ChatScreen does this when a chat opens (sendSeenNotificationForUnseenMessages). */
    private suspend fun markSeen(userId: String) {
        try {
            getMessageRepository(App.context()).sendSeenNotificationForUnseenMessages(userId)
        } catch (e: Throwable) {
            // The peer simply learns later; the next opening of the chat tries again.
        }
    }

    /** Reads the contact list, and the open conversation, again from the database. */
    private suspend fun reload() = reloadLock.withLock {
        try {
            val db = DatabaseProvider.provideDatabase()
            val items = ArrayList<ChatItem>()
            for (peer in db.peerDao().getAllPeers()) {
                // Groups are not on the PC yet; neither are the App's two local pseudo
                // contacts, the AI assistant and the note to self ("mtc-..." ids).
                if (peer.userId.startsWith("group") || peer.userId.startsWith("mtc-")) continue
                if (peer.userId == myUserId) continue

                val last = db.messageDao().getLastMessage(peer.userId)
                val unread = db.messageDao().getUnseenMessages(peer.userId).size
                items.add(
                    ChatItem(
                        userId = peer.userId,
                        name = peer.name,
                        picture = peer.picture,
                        preview = last?.let { previewOf(it) } ?: "",
                        lastDate = last?.date ?: 0L,
                        unread = unread
                    )
                )
            }
            items.sortWith(compareByDescending<ChatItem> { it.lastDate }.thenBy { it.name.lowercase() })
            chats.clear()
            chats.addAll(items)

            val open = selectedUserId
            if (open != null) {
                val list = db.messageDao().getRecentMessages(myUserId, open, MAX_MESSAGES_SHOWN).reversed()
                messages.clear()
                messages.addAll(list)
            }
        } catch (e: Throwable) {
            warning = "${Strings.DATABASE_ERROR} ${e.message}"
        }
    }

    fun isMine(message: Message): Boolean = message.fromUserId == myUserId

    /** Whether a message carries a quote: it replies to another message, or it was forwarded. */
    fun hasQuote(message: Message): Boolean =
        !message.textAttached.isNullOrEmpty() || (message.type == Type.TEXT && !message.subType.isNullOrEmpty())

    /** Who wrote the quoted message. The phone shows "You" for the user's own name (textViewHolder). */
    fun quotedNameOf(message: Message): String {
        val name = message.nameAttached ?: ""
        return if (name.isNotEmpty() && name == myName) Strings.YOU else name
    }

    /**
     * The picture of the message a reply quotes, if it is on this PC. Found as the phone finds
     * it (TextImageViewHolder): through replyId, in the message that was replied to. Only a
     * single image for now; the phone also builds a preview for an album, a video and a file.
     */
    suspend fun quotedPictureOf(message: Message): File? {
        val replyId = message.replyId
        if (replyId.isNullOrEmpty()) return null
        return try {
            val repository = getMessageRepository(App.context())
            val originalType = repository.getReplyType(replyId) ?: return null
            if (originalType != Type.IMAGE) return null
            MediaFiles.fileOfUri(repository.getReplyImage(replyId, originalType))
        } catch (e: Throwable) {
            null
        }
    }

    /** What a message shows as its one line: the text, or the kind of content. */
    fun previewOf(message: Message): String = when {
        message.status == Status.RECEIVING -> Strings.RECEIVING
        message.type == Type.TEXT -> message.text.replace('\n', ' ')
        message.type == Type.IMAGE ->
            if (message.text.isBlank()) Strings.PHOTO else "${Strings.PHOTO}: ${message.text.replace('\n', ' ')}"
        message.type == Type.VIDEO ->
            if (message.text.isBlank()) Strings.VIDEO else "${Strings.VIDEO}: ${message.text.replace('\n', ' ')}"
        isFileType(message.type) -> "${Strings.FILE}: ${getFileDetailFromType(message.type).getOrNull(0) ?: ""}"
        message.type == Type.CONTACT -> "${Strings.CONTACT}: ${contactOf(message)?.name ?: ""}"
        else -> "[${message.type}]"
    }

    /** The status under one of our own messages, as stored: the phone's words, or Seen. */
    fun statusOf(message: Message): String =
        if (message.status == Notify.SEEN) Strings.SEEN else message.status

    private companion object {
        const val MAX_MESSAGES_SHOWN = 300

        // A contact's record may gain fields in a later version of the App: read what is known.
        val contactJson = Json { ignoreUnknownKeys = true }

        /** The kinds of file openFile hands to their program. None of them is a program itself. */
        val OPENED_DIRECTLY = setOf(
            "pdf", "txt", "csv", "rtf", "md",
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "tif", "tiff",
            "mp3", "m4a", "wav", "ogg", "opus", "aac", "flac",
            "mp4", "mov", "m4v", "mkv", "avi", "webm", "3gp",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
            "zip", "7z", "rar"
        )
    }
}
