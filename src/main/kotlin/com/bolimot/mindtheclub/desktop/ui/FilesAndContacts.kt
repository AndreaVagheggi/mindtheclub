package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.functions.formatFileSize
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * A file and a shared contact in the window: their bubbles, and what is shown before sending.
 * Drawn after the phone's layouts chat_file_in.xml, chat_profile_in.xml and send_file.xml.
 */

// ------------------------------------------------------------------------------ a file

/**
 * The black card of a file: a sheet of paper with the extension written on it, the name of
 * the file and its size (chat_file_in.xml, "fileFrame"). The three come from the TYPE of the
 * message, where the sender wrote them.
 */
@Composable
private fun FileCard(name: String, extension: String, size: Long, modifier: Modifier = Modifier) {
    Surface(color = Color.Black, shape = RoundedCornerShape(8.dp), modifier = modifier.width(180.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Icon(AppIcons.FileSheet, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize())
                Text(extension.uppercase(), color = Color.White, fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Text(name, color = Color.White, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(formatFileSize(size), color = Color.Gray)
        }
    }
}

/** The file of a file message. A click opens it (AppState.openFile says how, and how carefully). */
@Composable
internal fun MessageFile(state: AppState, message: Message) {
    val detail = getFileDetailFromType(message.type)
    FileCard(
        name = detail.getOrNull(0) ?: "",
        extension = detail.getOrNull(1) ?: "",
        size = detail.getOrNull(2)?.toLongOrNull() ?: 0L,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { state.openFile(message) }
    )
}

/**
 * What the phone's SendFile screen is: the file's card, a caption, send or cancel. When the file
 * is being forwarded, [displayName] is the name the original message gave it (this PC's copy is
 * named by a code), and [quoteText] and [quoteName] are the words and the name it came with,
 * shown on a card that [onDropQuote] takes away.
 */
@Composable
internal fun SendFileDialog(
    file: File,
    initialCaption: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    displayName: String? = null,
    quoteName: String? = null,
    quoteText: String? = null,
    onDropQuote: () -> Unit = {},
) {
    var caption by remember(file) { mutableStateOf(initialCaption) }
    val name = displayName?.takeIf { it.isNotBlank() && it != "Unknown" } ?: file.name
    val extension = if (name.contains(".")) name.substringAfterLast(".", "Unknown") else "Unknown"

    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(Strings.SEND_FILE_TITLE) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FileCard(name = name, extension = extension, size = file.length())
                if (!quoteText.isNullOrEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    QuoteInsert(name = quoteName ?: "", text = quoteText, onClose = onDropQuote)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text(Strings.CAPTION_HINT) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSend(caption) }) { Text(Strings.SEND) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(Strings.CANCEL) }
        }
    )
}

// ------------------------------------------------------------------------------ a contact

/** A round picture, or a grey disc when there is none or its file is not on this PC. */
@Composable
internal fun Avatar(picture: File?, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(null, picture) {
        value = withContext(Dispatchers.IO) { picture?.let { Thumbnails.of(it) } }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(modifier = Modifier.size(size).background(Color.LightGray, CircleShape))
    }
}

/**
 * The card of a shared contact: picture, name and bio, and under a line the two actions the
 * phone offers, View Profile and Add Contact (chat_profile_in.xml). The actions are drawn and
 * do nothing yet: viewing a profile and acquiring a contact are not on the PC.
 */
@Composable
internal fun MessageContact(state: AppState, message: Message) {
    val contact = remember(message.text) { state.contactOf(message) }
    val picture = remember(message.uri) { MediaFiles.fileOfUri(message.uri) }

    Surface(color = Color.White, shape = RoundedCornerShape(8.dp), modifier = Modifier.width(250.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(picture, 52.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        contact?.name ?: "",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val bio = contact?.bio ?: ""
                    if (bio.isNotEmpty()) {
                        Text(bio, color = Color.DarkGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                TextButton(onClick = {}, modifier = Modifier.weight(1f)) {
                    Text(Strings.VIEW_PROFILE, fontSize = 12.sp)
                }
                VerticalDivider()
                TextButton(onClick = {}, modifier = Modifier.weight(1f)) {
                    Text(Strings.ADD_CONTACT, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Which contacts to share: the user's contacts, each with a tick, and Send. On the phone this
 * is the contact list opened "in contact share mode", which leaves out the chat being written to.
 *
 * The same list chooses where a forwarded message goes (SelectPeersForForward), under its own
 * [title] and with no more than [maxSelectable] ticks: once that many are ticked, the others
 * cannot be.
 */
@Composable
internal fun ContactPickerDialog(
    contacts: List<ChatItem>,
    onSend: (List<String>) -> Unit,
    onClose: () -> Unit,
    title: String = Strings.CONTACTS,
    maxSelectable: Int = Int.MAX_VALUE,
) {
    val chosen = remember { mutableStateListOf<String>() }

    fun tick(userId: String, on: Boolean) {
        if (!on) chosen.remove(userId)
        else if (userId !in chosen && chosen.size < maxSelectable) chosen.add(userId)
    }

    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(title) },
        text = {
            if (contacts.isEmpty()) {
                Text(Strings.NO_CONTACTS_TO_SHARE)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(contacts, key = { it.userId }) { contact ->
                        val ticked = contact.userId in chosen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tick(contact.userId, !ticked) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(MediaFiles.fileOfUri(contact.picture), 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(contact.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(
                                checked = ticked,
                                enabled = ticked || chosen.size < maxSelectable,
                                onCheckedChange = { now -> tick(contact.userId, now) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = chosen.isNotEmpty(), onClick = { onSend(chosen.toList()) }) { Text(Strings.SEND) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(Strings.CANCEL) }
        }
    )
}
