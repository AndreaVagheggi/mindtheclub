package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Replying to a message and forwarding it, as the phone does them (C:\mtc, chat/ChatScreen.kt).
 *
 * On the phone a long press picks a message, and while one is picked the toolbar at the top
 * loses the contact's name and shows what can be done: Delete, Reply, Forward, Share, Copy,
 * according to the kind of message (res/menu/chat_drfsc.xml and its siblings). A PC has no long
 * press: its gesture for "what can I do with this" is the right mouse button, and that is the
 * one thing that differs. The rest is the phone's:
 *
 *   - Reply puts a small card above the message box, with the name and the words of the
 *     answered message and its picture ("chat_insert" in screen_chat.xml). The next message
 *     sent is the reply; the cross on the card gives it up.
 *   - Forward opens the list of contacts ("Select contacts", three at most, without the chat
 *     one is in). A text leaves at once. A picture, a video and a file first show the same
 *     screen they are sent from, with a card saying whose they are and the words they came
 *     with, and a cross to send them without it.
 *
 * Delete, Share and Copy are not on the PC yet.
 */

/** How many chats one forward can go to: SelectPeersForForward, maxSelectableCount. */
internal const val FORWARD_MAX_CONTACTS = 3

/**
 * What the top of the conversation shows while a message is picked: the actions on the right,
 * as the phone's toolbar has them, and on the left a cross that lets go of the message, which
 * on the phone is the Back key.
 */
@Composable
internal fun SelectionBar(onClose: () -> Unit, onReply: () -> Unit, onForward: () -> Unit, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(AppIcons.Close, contentDescription = Strings.CLOSE, tint = tint)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onReply) {
            Icon(AppIcons.Reply, contentDescription = Strings.REPLY, tint = tint)
        }
        IconButton(onClick = onForward) {
            Icon(AppIcons.Forward, contentDescription = Strings.FORWARD, tint = tint)
        }
    }
}

/**
 * The card that says what is being quoted: a name in bold, words in italics on three lines at
 * most, a picture on the right when there is one, and a cross to take the card away
 * ("chat_insert": 250 wide, light grey). The same card serves the reply, above the message box,
 * and the forward, on the screen a picture, a video or a file is sent from.
 */
@Composable
internal fun QuoteInsert(
    name: String,
    text: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    picture: ImageBitmap? = null,
    isVideo: Boolean = false,
    isFile: Boolean = false,
) {
    val hasPicture = picture != null || isFile

    Surface(color = INSERT_COLOR, shape = RoundedCornerShape(8.dp), modifier = modifier.width(250.dp)) {
        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // Without a picture the cross sits beside the words: leave it its room.
                        .padding(start = 8.dp, end = if (hasPicture) 8.dp else 24.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    if (name.isNotEmpty()) {
                        Text(
                            name,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (text.isNotEmpty()) {
                        Text(
                            text,
                            color = Color.Black,
                            fontStyle = FontStyle.Italic,
                            fontSize = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (hasPicture) {
                    Box(modifier = Modifier.size(width = 40.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                        if (picture != null) {
                            Image(
                                bitmap = picture,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(AppIcons.FileSheet, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(32.dp))
                        }
                        if (isVideo) {
                            Icon(
                                AppIcons.VideoCamera,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(16.dp)
                            )
                        }
                    }
                }
            }

            // The cross: on a grey disc when it lies over a picture, plain otherwise.
            if (hasPicture) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(CLOSE_DISC_COLOR)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Close, contentDescription = Strings.CLOSE, tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            } else {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Close, contentDescription = Strings.CLOSE, tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * The card above the message box while [message] is being answered: ChatScreen.messageReply.
 * It quotes the words of the answered message under the name of the chat's contact, with the
 * picture of a picture, the still frame of a video, or a sheet of paper for a file.
 */
@Composable
internal fun ReplyInsert(state: AppState, message: Message, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val picture by produceState<ImageBitmap?>(null, message.messageId) {
        value = withContext(Dispatchers.IO) { state.mediaOf(message)?.let { Thumbnails.of(it) } }
    }
    QuoteInsert(
        name = state.selectedName,
        text = message.text,
        onClose = onClose,
        modifier = modifier,
        picture = picture,
        isVideo = message.type == Type.VIDEO && picture != null,
        isFile = isFileType(message.type),
    )
}

/** @color/lightGray, the background of "chat_insert". */
private val INSERT_COLOR = Color(0xFFE0E0E0)

/** circle_gray_background.xml, under the cross that lies over a picture. */
private val CLOSE_DISC_COLOR = Color(0xFFBDBDBD)
