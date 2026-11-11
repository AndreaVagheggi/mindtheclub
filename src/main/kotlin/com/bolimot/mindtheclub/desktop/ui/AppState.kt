package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.backup.BackupExporter
import com.bolimot.mindtheclub.desktop.backup.BackupImporter
import com.bolimot.mindtheclub.desktop.engine.DesktopEvents
import com.bolimot.mindtheclub.desktop.engine.Engine
import com.bolimot.mindtheclub.desktop.engine.Outgoing
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** What a message shows as its one line: the text, or the kind of content. */
    fun previewOf(message: Message): String =
        if (message.type == "text") message.text.replace('\n', ' ') else "[${message.type}]"

    /** The status under one of our own messages, as stored: the phone's words, or Seen. */
    fun statusOf(message: Message): String =
        if (message.status == Notify.SEEN) Strings.SEEN else message.status

    private companion object {
        const val MAX_MESSAGES_SHOWN = 300
    }
}
