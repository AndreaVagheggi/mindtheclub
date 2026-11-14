package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.engine.Engine
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.tools.Status
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * The window of MindTheClub for Windows: contacts on the left, the conversation on the right.
 *
 * This first version does what the engine can do today, 1:1 text, and nothing it cannot. It
 * holds no logic of its own: every screen reads AppState and calls its functions.
 */

fun main() = application {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    LaunchedEffect(Unit) { state.boot() }

    Window(
        onCloseRequest = {
            Engine.stop()
            exitApplication()
        },
        title = Strings.APP_NAME,
        state = rememberWindowState(width = 1000.dp, height = 700.dp),
        // Esc lets go of a picked message: what the Back key does on the phone.
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.selectedMessageId != null) {
                state.clearSelection()
                true
            } else {
                false
            }
        }
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                when (val screen = state.screen) {
                    is Screen.Busy -> BusyScreen(screen.what)
                    is Screen.NeedBackup -> RestoreScreen(state, screen.note)
                    is Screen.Failed -> FailedScreen(state, screen.message)
                    is Screen.Chats -> ChatsScreen(state)
                }
            }
        }
    }
}

@Composable
private fun BusyScreen(what: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(what)
    }
}

@Composable
private fun FailedScreen(state: AppState, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(Strings.START_FAILED, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text(message, modifier = Modifier.widthIn(max = 600.dp))
        Spacer(Modifier.height(24.dp))
        Button(onClick = { state.retry() }) { Text(Strings.RETRY) }
    }
}

/** No identity on this PC, or it was handed back: load a backup made on the phone. */
@Composable
private fun RestoreScreen(state: AppState, note: String) {
    var file by remember { mutableStateOf<File?>(null) }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(Strings.APP_NAME, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(note, modifier = Modifier.widthIn(max = 600.dp))
        Spacer(Modifier.height(32.dp))

        Text(Strings.RESTORE_TITLE, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { chooseFile(save = false, suggested = null)?.let { file = it } }) {
            Text(Strings.CHOOSE_FILE)
        }
        Spacer(Modifier.height(8.dp))
        Text(file?.name ?: Strings.NO_FILE, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(Strings.PASSWORD) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(360.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            Strings.RESTORE_WARNING,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(max = 360.dp)
        )
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = file != null && password.isNotEmpty(),
            onClick = { file?.let { state.loadBackup(it, password) } }
        ) { Text(Strings.RESTORE) }

        state.formError?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.widthIn(max = 600.dp))
        }
    }
}

@Composable
private fun ChatsScreen(state: AppState) {
    var returning by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        state.warning?.let { warning ->
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(warning, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.dismissWarning() }) { Text(Strings.CLOSE) }
            }
        }

        if (state.videosBeingPrepared > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(Strings.PREPARING_VIDEO, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            ContactList(state, onReturnToPhone = { returning = true })
            VerticalDivider()
            Conversation(state)
        }
    }

    if (returning) {
        ReturnToPhoneDialog(state, onClose = { returning = false })
    }
}

@Composable
private fun ContactList(state: AppState, onReturnToPhone: () -> Unit) {
    Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(state.myName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onReturnToPhone) { Text(Strings.RETURN_TO_PHONE) }
        }
        HorizontalDivider()

        if (state.chats.isEmpty()) {
            Text(Strings.NO_CHATS, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.chats, key = { it.userId }) { chat ->
                val selected = chat.userId == state.selectedUserId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { state.select(chat.userId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(chat.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            chat.preview,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (chat.unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (chat.unread > 99) "99" else chat.unread.toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Conversation(state: AppState) {
    if (state.selectedUserId == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(Strings.PICK_A_CHAT)
        }
        return
    }

    var draft by remember(state.selectedUserId) { mutableStateOf("") }
    // The picture chosen under the paperclip's Images, shown for confirmation before it is sent.
    var photo by remember(state.selectedUserId) { mutableStateOf<File?>(null) }
    // The video chosen under the paperclip's Video, shown for confirmation before it is sent.
    var videoToSend by remember(state.selectedUserId) { mutableStateOf<File?>(null) }
    // The file chosen under the paperclip's File, shown for confirmation before it is sent.
    var fileToSend by remember(state.selectedUserId) { mutableStateOf<File?>(null) }
    // Whether the list opened by the paperclip's Contact is on screen.
    var pickingContacts by remember(state.selectedUserId) { mutableStateOf(false) }
    // Whether the paperclip's bar is open.
    var attaching by remember(state.selectedUserId) { mutableStateOf(false) }
    // The message being answered: its card sits above the message box until the reply is sent
    // or the card is closed.
    var replyTo by remember(state.selectedUserId) { mutableStateOf<Message?>(null) }
    // The message being forwarded, while the list of contacts to send it to is on screen.
    var forwarding by remember(state.selectedUserId) { mutableStateOf<Message?>(null) }
    // A picture, video or file being forwarded, shown for confirmation before it is sent.
    var forwardPlan by remember(state.selectedUserId) { mutableStateOf<ForwardPlan?>(null) }
    val messageBoxFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Choosing Reply puts the cursor in the message box, as on the phone.
    LaunchedEffect(replyTo) {
        if (replyTo != null) {
            try {
                messageBoxFocus.requestFocus()
            } catch (e: IllegalStateException) {
                // The box is not on screen yet: the user clicks into it.
            }
        }
    }

    // Left open with no choice made, the bar closes by itself, as on the phone.
    LaunchedEffect(attaching) {
        if (attaching) {
            delay(ATTACH_BAR_HIDE_DELAY_MS)
            attaching = false
        }
    }

    // Stay at the newest message, as a chat does, when the conversation opens or grows.
    LaunchedEffect(state.selectedUserId, state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1)
    }

    fun send() {
        // Nothing written: nothing is sent, and a reply being prepared stays where it is.
        if (draft.isBlank()) return
        state.send(draft, replyTo)
        draft = ""
        replyTo = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // The top of the conversation: the contact's name or, while a message is picked, what
        // can be done with it, as the phone's toolbar does. Same height either way.
        val picked = state.selectedMessage
        if (picked == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    state.selectedName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            SelectionBar(
                onClose = { state.clearSelection() },
                onReply = {
                    replyTo = picked
                    state.clearSelection()
                },
                onForward = {
                    state.clearSelection()
                    // A picture, video or file can be forwarded only if its file is on this PC.
                    if (picked.type != Type.TEXT && MediaFiles.fileFor(picked) == null) {
                        state.showWarning(Strings.MEDIA_NOT_AVAILABLE)
                    } else {
                        forwarding = picked
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)
            )
        }
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.messages, key = { it.messageId }) { message ->
                    Bubble(state, message)
                }
            }

            // The paperclip's bar, over the bottom of the conversation and just above the
            // message box. Every choice closes it.
            AttachBar(
                visible = attaching,
                onImages = {
                    attaching = false
                    chooseFile(save = false, suggested = PICTURE_FILES)?.let { photo = it }
                },
                onVideo = {
                    attaching = false
                    chooseFile(save = false, suggested = VIDEO_FILES)?.let { videoToSend = it }
                },
                onFile = {
                    attaching = false
                    chooseFile(save = false, suggested = null)?.let { fileToSend = it }
                },
                onContact = {
                    attaching = false
                    pickingContacts = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
            )
        }

        HorizontalDivider()
        // The message being answered, on its card just above the message box.
        replyTo?.let { answered ->
            ReplyInsert(
                state,
                answered,
                onClose = { replyTo = null },
                modifier = Modifier.padding(start = 12.dp, top = 8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(Strings.MESSAGE_HINT) },
                // The paperclip, inside the box at its end, as on the phone.
                trailingIcon = { AttachButton(onClick = { attaching = !attaching }) },
                maxLines = 4,
                modifier = Modifier.weight(1f).focusRequester(messageBoxFocus).onPreviewKeyEvent { event ->
                    // Enter sends, Shift+Enter makes a new line.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        send()
                        true
                    } else {
                        false
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = draft.isNotBlank(), onClick = { send() }) { Text(Strings.SEND) }
        }
    }

    photo?.let { file ->
        SendPhotoDialog(
            file = file,
            // What is already written in the message box becomes the caption, as on the phone.
            initialCaption = draft,
            onSend = { caption ->
                state.sendImage(file, caption)
                draft = ""
                photo = null
            },
            onClose = { photo = null }
        )
    }

    videoToSend?.let { file ->
        SendVideoDialog(
            file = file,
            initialCaption = draft,
            onSend = { caption ->
                state.sendVideo(file, caption)
                draft = ""
                videoToSend = null
            },
            onClose = { videoToSend = null }
        )
    }

    fileToSend?.let { file ->
        SendFileDialog(
            file = file,
            initialCaption = draft,
            onSend = { caption ->
                state.sendFile(file, caption)
                draft = ""
                fileToSend = null
            },
            onClose = { fileToSend = null }
        )
    }

    if (pickingContacts) {
        ContactPickerDialog(
            contacts = state.shareableContacts(),
            onSend = { userIds ->
                state.sendContacts(userIds)
                pickingContacts = false
            },
            onClose = { pickingContacts = false }
        )
    }

    // Forward, first step: to whom. A text then leaves at once; a picture, a video and a file
    // go through the screen they are sent from (ChatScreen, getPeersResult).
    forwarding?.let { message ->
        ContactPickerDialog(
            contacts = state.shareableContacts(),
            title = Strings.SELECT_CONTACTS,
            maxSelectable = FORWARD_MAX_CONTACTS,
            onSend = { userIds ->
                val plan = state.planForward(message, userIds)
                if (message.type == Type.TEXT) state.forwardText(plan) else forwardPlan = plan
                forwarding = null
            },
            onClose = { forwarding = null }
        )
    }

    // Forward, second step for a picture, a video and a file: the send screen, with the card
    // that says whose it is and the words it came with. The cross on the card sends it bare.
    forwardPlan?.let { plan ->
        val file = remember(plan.message.messageId) { MediaFiles.fileFor(plan.message) }
        if (file == null) {
            LaunchedEffect(plan) { forwardPlan = null }
        } else {
            val type = plan.message.type
            when {
                type == Type.IMAGE -> SendPhotoDialog(
                    file = file,
                    initialCaption = "",
                    onSend = { caption ->
                        state.sendImage(file, caption, plan)
                        forwardPlan = null
                    },
                    onClose = { forwardPlan = null },
                    quoteName = plan.name,
                    quoteText = plan.text,
                    onDropQuote = { forwardPlan = plan.withoutQuote() }
                )
                type == Type.VIDEO -> SendVideoDialog(
                    file = file,
                    initialCaption = "",
                    onSend = { caption ->
                        state.sendVideo(file, caption, plan)
                        forwardPlan = null
                    },
                    onClose = { forwardPlan = null },
                    quoteName = plan.name,
                    quoteText = plan.text,
                    onDropQuote = { forwardPlan = plan.withoutQuote() }
                )
                isFileType(type) -> SendFileDialog(
                    file = file,
                    initialCaption = "",
                    onSend = { caption ->
                        state.sendFile(file, caption, plan)
                        forwardPlan = null
                    },
                    onClose = { forwardPlan = null },
                    displayName = getFileDetailFromType(type).getOrNull(0),
                    quoteName = plan.name,
                    quoteText = plan.text,
                    onDropQuote = { forwardPlan = plan.withoutQuote() }
                )
                // Only these three are forwarded through a send screen (AppState.canReplyOrForward).
                else -> {}
            }
        }
    }
}

@Composable
private fun Bubble(state: AppState, message: Message) {
    val mine = state.isMine(message)
    val picked = state.selectedMessageId == message.messageId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A picked message is marked across the whole row, as on the phone.
            .background(if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            // The right mouse button picks a message: the PC's long press. Keyed on what
            // decides whether the message can be picked, so a content that has finished
            // arriving is not judged by what it was.
            .pointerInput(message.messageId, message.status, message.type) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            state.toggleSelection(message)
                        }
                    }
                }
            }
            .padding(top = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // The message this one replies to, or forwards, drawn above it as on the phone.
                if (message.status != Status.RECEIVING && state.hasQuote(message)) {
                    MessageQuote(state, message)
                    Spacer(Modifier.height(6.dp))
                }
                when {
                    // A content still arriving: the engine replaces this row when it is complete.
                    message.status == Status.RECEIVING -> Text(Strings.RECEIVING)
                    message.type == Type.IMAGE -> {
                        MessagePicture(message)
                        if (message.text.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(message.text)
                        }
                    }
                    message.type == Type.VIDEO -> {
                        MessageVideo(state, message)
                        if (message.text.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(message.text)
                        }
                    }
                    isFileType(message.type) -> {
                        MessageFile(state, message)
                        if (message.text.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            // As wide as the file's card, as on the phone.
                            Text(message.text, modifier = Modifier.width(180.dp))
                        }
                    }
                    // A shared contact shows its card and no text: the text is the contact's record.
                    message.type == Type.CONTACT -> MessageContact(state, message)
                    // A forwarded text has no words of its own: they are all in the quote above.
                    message.type == Type.TEXT -> if (message.text.isNotEmpty()) Text(message.text)
                    else -> Text(state.previewOf(message))
                }
                Spacer(Modifier.height(2.dp))
                val time = formatTime(message.date)
                Text(
                    if (mine) "$time  ${state.statusOf(message)}" else time,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** The way back to the phone: a password, a place for the file, then the PC lets go. */
@Composable
private fun ReturnToPhoneDialog(state: AppState, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        properties = StaysOpen,
        title = { Text(Strings.RETURN_TITLE) },
        text = {
            Column {
                Text(Strings.RETURN_TEXT)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(Strings.RETURN_PASSWORD) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = again,
                    onValueChange = { again = it },
                    label = { Text(Strings.RETURN_PASSWORD_AGAIN) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                onClick = {
                    when {
                        password.length < 8 -> error = Strings.PASSWORD_TOO_SHORT
                        password != again -> error = Strings.PASSWORDS_DIFFER
                        else -> {
                            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
                                .withZone(ZoneId.systemDefault()).format(Instant.now())
                            val file = chooseFile(save = true, suggested = "mindtheclub_pc_$stamp.mtcbackup")
                            if (file != null) {
                                working = true
                                error = null
                                state.returnToPhone(file, password) { failure ->
                                    working = false
                                    if (failure == null) onClose() else error = failure
                                }
                            }
                        }
                    }
                }
            ) { Text(Strings.CONFIRM) }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onClose) { Text(Strings.CANCEL) }
        }
    )
}

/** What the paperclip's Images offers: the kinds of picture Java reads. On Windows a pattern in the name box filters the list. */
private const val PICTURE_FILES = "*.jpg;*.jpeg;*.png;*.bmp;*.gif"

/**
 * What the paperclip's Video offers. Only the kinds a phone plays under the name ".mp4", which
 * is the name the receiving phone gives every video (receiveVideo).
 */
private const val VIDEO_FILES = "*.mp4;*.mov;*.m4v;*.3gp"

/** The system's own file window. Null when the user cancels. */
private fun chooseFile(save: Boolean, suggested: String?): File? {
    val dialog = FileDialog(null as Frame?, Strings.APP_NAME, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (suggested != null) dialog.file = suggested
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

private val timeOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dateAndTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Today's messages show the time, older ones the date too. */
private fun formatTime(millis: Long): String {
    val instant = Instant.ofEpochMilli(millis)
    val today = LocalDate.now(ZoneId.systemDefault())
    return if (instant.atZone(ZoneId.systemDefault()).toLocalDate() == today) timeOnly.format(instant)
    else dateAndTime.format(instant)
}
