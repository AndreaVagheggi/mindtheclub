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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.engine.Engine
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
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
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
    val listState = rememberLazyListState()

    // Stay at the newest message, as a chat does, when the conversation opens or grows.
    LaunchedEffect(state.selectedUserId, state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1)
    }

    fun send() {
        state.send(draft)
        draft = ""
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            state.selectedName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.messages, key = { it.messageId }) { message ->
                Bubble(state, message)
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(Strings.MESSAGE_HINT) },
                maxLines = 4,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
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
}

@Composable
private fun Bubble(state: AppState, message: Message) {
    val mine = state.isMine(message)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(state.previewOf(message).takeIf { message.type != "text" } ?: message.text)
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
