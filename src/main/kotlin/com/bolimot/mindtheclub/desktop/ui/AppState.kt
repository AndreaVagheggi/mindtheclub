package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.reaction.ReactionManager
import com.bolimot.mindtheclub.desktop.backend.Backend
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
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.works.AcquireContactWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit

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
    val unread: Int,
    /** The contact's status: Contact.ACTIVE, or Contact.NEW while it is being acquired. */
    val status: String = Contact.ACTIVE
) {
    /** A contact being acquired: its row says "Pending…" and opens its profile, not a chat (PeersFragment). */
    val isNew: Boolean get() = status == Contact.NEW
}

/**
 * The profile on screen (PeerView): opened from a shared contact's View Profile, or from the
 * row of a contact being acquired, which can be removed from there.
 */
class ProfileView(val userId: String, val name: String, val bio: String, val picture: File?, val pending: Boolean)

/** A contact the user is asked to add (NewPeerDialog), with the fingerprint its card vouches for. */
class ContactToAdd(val userId: String, val name: String, val bio: String?, val fingerprint: String?)

/** One line of the list of who reacted to a message (the phone's ReactionItem). */
class ReactionRow(val userId: String, val name: String, val picture: File?, val emoji: String, val isMe: Boolean)

/** A picture to draw small: the file of an image, or of a video, whose still frame is then drawn. */
class QuotedMedia(val file: File, val isVideo: Boolean)

/**
 * A message on its way to other chats: which one, to whom, and what its bubble will quote there
 * (the name of who wrote it and its words: nameToForward and textToForward in ChatScreen).
 */
class ForwardPlan(val message: Message, val toUserIds: List<String>, val name: String?, val text: String?) {
    /** The same forward with nothing quoted: the user closed the quote on the send screen. */
    fun withoutQuote() = ForwardPlan(message, toUserIds, null, null)
}

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

    /**
     * The message picked with the right mouse button, which is the PC's long press. While one
     * is picked the top of the conversation shows what can be done with it, as the phone's
     * toolbar does. One at a time: the phone picks several only to delete them or to forward
     * pictures together, and neither is on the PC yet.
     */
    var selectedMessageId: String? by mutableStateOf<String?>(null)
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
        DesktopEvents.onReactionChanged = { _, _ -> scope.launch(Dispatchers.IO) { reload() } }
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
        watchContacts()
        screen = Screen.Chats
    }

    private var contactsWatch: Job? = null

    /**
     * The contact list follows the contacts table: a contact asked for, accepted, made active or
     * given a new profile is written by the engine at moments the window cannot know, as the
     * phone's list follows its paging source. Started again with every start of the engine,
     * because a loaded backup opens a new database.
     */
    private fun watchContacts() {
        contactsWatch?.cancel()
        contactsWatch = scope.launch(Dispatchers.IO) {
            try {
                DatabaseProvider.provideDatabase().invalidationTracker.createFlow("Peer").collect { reload() }
            } catch (e: Throwable) {
                // The database was closed under the watch (a backup being loaded): the next
                // start watches the new one.
            }
        }
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
        // A contact being acquired has no chat yet: its row opens its profile (PeersFragment).
        val chat = chats.firstOrNull { it.userId == userId }
        if (chat != null && chat.isNew) {
            // The phone shows "Pending…" where the bio goes.
            profileShown = ProfileView(chat.userId, chat.name, Strings.PENDING, MediaFiles.fileOfUri(chat.picture), pending = true)
            return@launch
        }
        selectedMessageId = null
        selectedUserId = userId
        selectedName = chats.firstOrNull { it.userId == userId }?.name ?: ""
        reload()
        markSeen(userId)
    }

    // ------------------------------------------------------------ picking a message

    /**
     * Whether a message can be replied to and forwarded: ChatScreen.determineMenuRes. A text, a
     * picture, a video and a file can; a shared contact cannot (the phone gives it the plain
     * menu), and neither can a content still arriving.
     */
    fun canReplyOrForward(message: Message): Boolean =
        message.status != Status.RECEIVING &&
                (message.type == Type.TEXT || message.type == Type.IMAGE ||
                        message.type == Type.VIDEO || isFileType(message.type))

    /**
     * Whether the user can react to a message: ChatScreen.showReactionOverlay. In a chat with
     * one person, only to what that person sent (the phone shows no reaction bar on the user's
     * own messages), and not to a content still arriving or a missed call.
     */
    fun canReact(message: Message): Boolean =
        !isMine(message) && message.chatGroupId == null &&
                message.status != Status.RECEIVING && message.type != Type.MISSED_CALL

    /** Picks [message], or lets go of it when it is the one already picked. */
    fun toggleSelection(message: Message) {
        if (!canReplyOrForward(message) && !canReact(message)) return
        selectedMessageId = if (selectedMessageId == message.messageId) null else message.messageId
    }

    // ---------------------------------------------------------------------- reactions

    /**
     * Puts [emoji] on [message] for this user, or takes it away when it is the one already there:
     * ChatScreen.sendReactionToServer. Recorded here first, so the pill changes at once, then
     * sent to the person who wrote the message.
     */
    fun react(message: Message, emoji: String) {
        selectedMessageId = null
        scope.launch(Dispatchers.IO) {
            try {
                val myUserId = MySelf.userId() ?: return@launch
                val applied = if (ReactionManager.myEmoji(message.messageId) == emoji) "" else emoji
                ReactionManager.apply(message.messageId, myUserId, applied, System.currentTimeMillis())
                Outgoing.sendReaction(message, applied)
            } catch (e: Throwable) {
                warning = "${Strings.SEND_FAILED} ${e.message}"
            }
            reload()
        }
    }

    /** The emoji this user put on a message, or null. */
    suspend fun myEmojiOn(messageId: String): String? = ReactionManager.myEmoji(messageId)

    /** Who reacted to a message, oldest first: ReactionsBottomSheet.toItems. */
    suspend fun reactionRowsOf(messageId: String): List<ReactionRow> {
        return try {
            val peers = getPeerDao(App.context())
            ReactionManager.reactionsOf(messageId).map { reaction ->
                val isMe = reaction.reactorUserId == myUserId
                val peer = if (isMe) null else peers.getPeer(reaction.reactorUserId)
                ReactionRow(
                    userId = reaction.reactorUserId,
                    name = if (isMe) Strings.YOU else peer?.name?.takeIf { it.isNotEmpty() } ?: Strings.MEMBER,
                    // Our own picture is not in the contacts: MySelf has it, as on the phone.
                    picture = MediaFiles.fileOfUri(if (isMe) MySelf.pictureUri() else peer?.picture),
                    emoji = reaction.emoji,
                    isMe = isMe
                )
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** Takes this user's reaction off a message: the own line of the list of who reacted. */
    fun removeMyReaction(messageId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val myUserId = MySelf.userId() ?: return@launch
                val message = getMessageRepository(App.context()).getMessage(messageId) ?: return@launch
                ReactionManager.apply(messageId, myUserId, "", System.currentTimeMillis())
                Outgoing.sendReaction(message, "")
            } catch (e: Throwable) {
                warning = "${Strings.SEND_FAILED} ${e.message}"
            }
            reload()
        }
    }

    fun clearSelection() {
        selectedMessageId = null
    }

    /** The picked message, as long as it is still in the open conversation. */
    val selectedMessage: Message?
        get() {
            val id = selectedMessageId ?: return null
            return messages.firstOrNull { it.messageId == id }
        }

    // ---------------------------------------------------------------------- sending

    /**
     * Sends [text] to the open chat. With [replyTo] it is a reply to that message, built as
     * ChatScreen.messageReply builds it: the bubble quotes the words of the answered message
     * under the name of the chat's contact. (The App writes the contact's name even when the
     * answered message is the user's own: the PC does the same, so that a reply written here
     * and one written on the phone are the same message.)
     */
    fun send(text: String, replyTo: Message? = null) {
        val to = selectedUserId ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val quotedName = selectedName
        scope.launch(Dispatchers.IO) {
            try {
                if (replyTo == null) {
                    Outgoing.sendText(to, body)
                } else {
                    Outgoing.sendText(to, body, replyTo.text, quotedName, SubType.REPLY, replyTo.messageId)
                }
            } catch (e: Throwable) {
                warning = "${Strings.SEND_FAILED} ${e.message}"
            }
            reload()
        }
    }

    /** Where a content goes: to the chats of a forward, or to the open chat. */
    private fun targetsOf(forward: ForwardPlan?): List<String> =
        forward?.toUserIds ?: listOfNotNull(selectedUserId)

    /**
     * Sends the picture in [file] with [caption] under it (may be empty): to the open chat, or,
     * with [forward], to the chats of that forward, quoting what it says.
     */
    fun sendImage(file: File, caption: String, forward: ForwardPlan? = null) {
        val targets = targetsOf(forward)
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (to in targets) {
                try {
                    val sent = Outgoing.sendImage(to, file, caption.trim(), forward?.text, forward?.name)
                    if (sent == null) warning = Strings.SEND_FAILED
                } catch (e: MediaFiles.Refused) {
                    warning = Strings.PHOTO_UNREADABLE
                } catch (e: Throwable) {
                    warning = "${Strings.SEND_FAILED} ${e.message}"
                }
            }
            reload()
        }
    }

    /** Sends the video in [file] with [caption] under it: to the open chat, or as [forward] says. */
    fun sendVideo(file: File, caption: String, forward: ForwardPlan? = null) {
        val targets = targetsOf(forward)
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            // A large video is re-encoded before it leaves, which takes a while: the window
            // says so, as the phone's Send screen does, until the message is in the chat. A
            // video this PC already keeps (a forward) is not re-encoded.
            val preparing = !MediaFiles.isKept(file) && VideoCompressor.mayCompress(file)
            if (preparing) videosBeingPrepared++
            try {
                for (to in targets) {
                    try {
                        val sent = Outgoing.sendVideo(to, file, caption.trim(), forward?.text, forward?.name)
                        if (sent == null) warning = Strings.SEND_FAILED
                    } catch (e: MediaFiles.Refused) {
                        warning = Strings.FILE_UNREADABLE
                    } catch (e: Throwable) {
                        warning = "${Strings.SEND_FAILED} ${e.message}"
                    }
                }
            } finally {
                if (preparing) videosBeingPrepared--
            }
            reload()
        }
    }

    /** Sends [file], whatever it is, with [caption] under it: to the open chat, or as [forward] says. */
    fun sendFile(file: File, caption: String, forward: ForwardPlan? = null) {
        val targets = targetsOf(forward)
        if (targets.isEmpty()) return
        // A forwarded file is this PC's copy, whose name on disk is a code: its real name is
        // the one the original message carries.
        val realName = forward?.let { getFileDetailFromType(it.message.type).getOrNull(0) }
        scope.launch(Dispatchers.IO) {
            for (to in targets) {
                try {
                    val sent = Outgoing.sendFile(to, file, caption.trim(), forward?.text, forward?.name, realName)
                    if (sent == null) warning = Strings.SEND_FAILED
                } catch (e: MediaFiles.Refused) {
                    warning = Strings.FILE_UNREADABLE
                } catch (e: Throwable) {
                    warning = "${Strings.SEND_FAILED} ${e.message}"
                }
            }
            reload()
        }
    }

    // -------------------------------------------------------------------- forwarding

    /**
     * What the bubble of a forwarded [message] will quote: ChatScreen, R.id.forward. A message
     * that was itself forwarded keeps the name and the words it came with; any other is quoted
     * under the name of the chat's contact, with its own text.
     */
    fun planForward(message: Message, toUserIds: List<String>): ForwardPlan =
        if (message.subType == SubType.FORWARD) {
            ForwardPlan(message, toUserIds, message.nameAttached, message.textAttached)
        } else {
            ForwardPlan(message, toUserIds, selectedName, message.text)
        }

    /** Forwards a text to the chats of [plan], one message each (ChatScreen, getPeersResult). */
    fun forwardText(plan: ForwardPlan) {
        scope.launch(Dispatchers.IO) {
            for (to in plan.toUserIds) {
                try {
                    if (Outgoing.forwardText(to, plan.text, plan.name) == null) warning = Strings.SEND_FAILED
                } catch (e: Throwable) {
                    warning = "${Strings.SEND_FAILED} ${e.message}"
                }
            }
            reload()
        }
    }

    /** Says [text] in the banner at the top of the window. */
    fun showWarning(text: String) {
        warning = text
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

    /**
     * The contacts that can be shared in the open chat, or a message forwarded to: all of them
     * but the one being written to, and but the ones still being acquired, which have no chat.
     */
    fun shareableContacts(): List<ChatItem> = chats.filter { it.userId != selectedUserId && it.status == Contact.ACTIVE }

    // ------------------------------------------------------------ contacts and profiles

    /** The profile on screen, or null. */
    var profileShown: ProfileView? by mutableStateOf<ProfileView?>(null)
        private set

    /** The contact the user is being asked to add, or null. */
    var contactToAdd: ContactToAdd? by mutableStateOf<ContactToAdd?>(null)
        private set

    /** View Profile on a shared contact: ChatScreen.onViewProfileClick, opening PeerView. */
    fun viewProfileOf(message: Message) {
        val peer = contactOf(message) ?: return
        profileShown = ProfileView(peer.userId, peer.name, peer.bio ?: "", MediaFiles.fileOfUri(message.uri), pending = false)
    }

    fun closeProfile() {
        profileShown = null
    }

    /**
     * Add Contact on a shared contact: ChatScreen.onAddContactClick, then acquiringNewContact.
     * Nothing happens for this user's own card, nor for a contact already here, exactly as on
     * the phone. The phone also records a contact already here whose card carries a different
     * key, for the key change dialog; that dialog is not on the PC, so nothing is recorded and
     * the key is never taken.
     */
    fun addContactFrom(message: Message) {
        val peer = contactOf(message) ?: return
        // The shared contact carries the peer's public key as attested by the mutual friend.
        val fingerprint = peer.publicKey?.takeIf { it.isNotEmpty() }?.let { KeyManager.fingerprintOf(it) }
        scope.launch(Dispatchers.IO) {
            try {
                if (peer.userId == myUserId) return@launch
                if (getPeerDao(App.context()).getPeer(peer.userId) != null) return@launch
                contactToAdd = ContactToAdd(peer.userId, peer.name, peer.bio, fingerprint)
            } catch (e: Throwable) {
                warning = "${Strings.DATABASE_ERROR} ${e.message}"
            }
        }
    }

    /**
     * Yes on the question: NewPeerDialog hands the contact to AcquireContactWorker, which puts
     * it in the list as pending, checks its key, writes the request and tells the contact.
     */
    fun confirmAddContact() {
        val contact = contactToAdd ?: return
        contactToAdd = null
        val inputData = workDataOf(
            "userId" to contact.userId,
            "name" to contact.name,
            "bio" to contact.bio,
            "fingerprint" to contact.fingerprint,
        )
        val workRequest = OneTimeWorkRequestBuilder<AcquireContactWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(App.context()).enqueue(workRequest)
    }

    fun cancelAddContact() {
        contactToAdd = null
    }

    /**
     * "Remove pending contact" (PeerView): the request is taken back from the contact's inbox
     * and the contact leaves the list. (The phone also deletes a picture of older builds from
     * Storage; the PC never put one there.)
     */
    fun removePending(userId: String) {
        profileShown = null
        scope.launch(Dispatchers.IO) {
            try {
                val me = MySelf.userId() ?: return@launch
                Backend.deleteContactRequest(userId, me)
                getPeerDao(App.context()).deletePeerByUserId(userId)
            } catch (e: Throwable) {
                warning = "${Strings.REMOVE_FAILED} ${e.message}"
            }
            reload()
        }
    }

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

                // A profile message is the engine's business and is never drawn (the phone gives
                // it an empty row): the last message shown is the last one of another kind.
                val last = db.messageDao().getLastMessage(peer.userId)?.takeIf { it.type != Type.PROFILE }
                    ?: db.messageDao().getRecentMessages(myUserId, peer.userId, PREVIEW_LOOKBACK)
                        .firstOrNull { it.type != Type.PROFILE }
                val unread = db.messageDao().getUnseenMessages(peer.userId).size
                items.add(
                    ChatItem(
                        userId = peer.userId,
                        name = peer.name,
                        picture = peer.picture,
                        // A contact not yet active says so, as on the phone (viewHolders/peers/active.kt).
                        preview = if (peer.status != Contact.ACTIVE) Strings.PENDING else last?.let { previewOf(it) } ?: "",
                        lastDate = last?.date ?: 0L,
                        unread = unread,
                        status = peer.status
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
                messages.addAll(list.filter { it.type != Type.PROFILE })
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
     * it (TextImageViewHolder): through replyId, in the message that was replied to. A single
     * image or a video for now; the phone also builds a preview for an album and a file.
     */
    suspend fun quotedMediaOf(message: Message): QuotedMedia? {
        val replyId = message.replyId
        if (replyId.isNullOrEmpty()) return null
        return try {
            val repository = getMessageRepository(App.context())
            val originalType = repository.getReplyType(replyId) ?: return null
            if (originalType != Type.IMAGE && originalType != Type.VIDEO) return null
            val file = MediaFiles.fileOfUri(repository.getReplyImage(replyId, originalType)) ?: return null
            QuotedMedia(file, originalType == Type.VIDEO)
        } catch (e: Throwable) {
            null
        }
    }

    /** The picture [message] itself can be drawn small with: its image, or its video. */
    fun mediaOf(message: Message): QuotedMedia? {
        if (message.type != Type.IMAGE && message.type != Type.VIDEO) return null
        val file = MediaFiles.fileFor(message) ?: return null
        return QuotedMedia(file, message.type == Type.VIDEO)
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

        /** How far back the contact list looks for a message to preview past profile messages. */
        const val PREVIEW_LOOKBACK = 20

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
