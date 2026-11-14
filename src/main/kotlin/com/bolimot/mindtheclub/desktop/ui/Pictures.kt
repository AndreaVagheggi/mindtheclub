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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.desktop.media.VideoThumbnail
import com.bolimot.mindtheclub.functions.formatFileSize
import com.bolimot.mindtheclub.tools.SubType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/*
 * Pictures in the window: the one inside a chat bubble, and the one shown before sending.
 *
 * A picture is never decoded on the interface thread and never at full size: what the window
 * draws is a small copy, made once and remembered.
 */

/**
 * How every dialog of the window behaves: closed by its own buttons or by Esc, never by a click
 * outside it.
 *
 * Not a matter of taste. A dialog that closes on a click outside closes on the mere RELEASE of
 * the mouse button outside it, and the dialogs shown before sending open right after the user
 * double-clicks a file in the Windows file chooser: the release of that double-click lands on
 * the window, outside the new dialog, and closes it before it is seen. The file was chosen and
 * nothing happened (13 Nov 2026, the videos AI.mp4 and AI2.mp4).
 */
internal val StaysOpen = DialogProperties(dismissOnClickOutside = false)

/** The small copies the window draws, the most recently used ones kept in memory. */
internal object Thumbnails {
    private const val LONG_SIDE = 640
    private const val MAX_KEPT = 60

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_KEPT
    }

    /** Null when [file] is not a picture this PC can read. Blocking: call it off the interface thread. */
    fun of(file: File): ImageBitmap? {
        // Length and date are part of the key: a file changed on disk is a different picture.
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val bitmap = MediaFiles.thumbnail(file, LONG_SIDE)?.toComposeImageBitmap() ?: return null
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }

    /**
     * The still picture of a video, asked of Windows (VideoThumbnail). Null when Windows has
     * none for this file. Blocking, as above.
     */
    fun ofVideo(file: File): ImageBitmap? {
        val key = "video|${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val bitmap = VideoThumbnail.of(file, LONG_SIDE)?.toComposeImageBitmap() ?: return null
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }

    /** The small copy of an image, or the still picture of a video. Blocking, as above. */
    fun of(media: QuotedMedia): ImageBitmap? = if (media.isVideo) ofVideo(media.file) else of(media.file)
}

/** What a video shows in the window: its still picture if Windows has one, and whether its file is here. */
private class VideoFrame(val file: File?, val picture: ImageBitmap?)

/**
 * A video as the phone draws it (chat_video_in.xml): a still picture with the play triangle in
 * the middle and a small camera in the corner. Without a picture the triangle is on black.
 */
@Composable
private fun VideoBox(frame: VideoFrame?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        frame?.picture?.let { picture ->
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (frame != null && frame.file == null) {
            // A video received or sent on the phone: the backup carries its name, not its bytes.
            Text(Strings.MEDIA_NOT_AVAILABLE, color = Color.White, style = MaterialTheme.typography.bodySmall)
        } else {
            Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        }
        Icon(
            AppIcons.VideoCamera,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp)
        )
    }
}

/** The video of a video message. A click plays it in the PC's own player. */
@Composable
internal fun MessageVideo(state: AppState, message: Message) {
    val frame by produceState<VideoFrame?>(null, message.messageId, message.uri) {
        value = withContext(Dispatchers.IO) {
            val file = MediaFiles.fileFor(message)
            VideoFrame(file, file?.let { Thumbnails.ofVideo(it) })
        }
    }
    VideoBox(
        frame,
        modifier = Modifier.size(width = 320.dp, height = 240.dp).clickable { state.openFile(message) }
    )
}

/**
 * What the phone's SendVideo screen is: the video, a caption, send or cancel. When the video is
 * being forwarded, [quoteText] and [quoteName] are the words and the name it came with, shown
 * on a card that [onDropQuote] takes away.
 */
@Composable
internal fun SendVideoDialog(
    file: File,
    initialCaption: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    quoteName: String? = null,
    quoteText: String? = null,
    onDropQuote: () -> Unit = {},
) {
    var caption by remember(file) { mutableStateOf(initialCaption) }
    val frame by produceState<VideoFrame?>(null, file) {
        value = withContext(Dispatchers.IO) { VideoFrame(file, Thumbnails.ofVideo(file)) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(Strings.SEND_VIDEO_TITLE) },
        text = {
            Column {
                VideoBox(frame, modifier = Modifier.size(width = 400.dp, height = 300.dp))
                Spacer(Modifier.height(4.dp))
                // A forwarded video is this PC's copy, whose name on disk is a code: only its size is said.
                Text(
                    if (MediaFiles.isKept(file)) formatFileSize(file.length())
                    else "${file.name}  ${formatFileSize(file.length())}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

private sealed interface PictureState {
    data object Loading : PictureState
    data object Missing : PictureState
    class Ready(val bitmap: ImageBitmap, val file: File) : PictureState
}

/**
 * The picture of an image message. The space it takes is fixed, whatever the picture and
 * whether or not it has loaded yet, so the conversation does not jump while pictures appear.
 * A click opens the file in the PC's own viewer.
 */
@Composable
internal fun MessagePicture(message: Message) {
    val picture by produceState<PictureState>(PictureState.Loading, message.messageId, message.uri) {
        value = withContext(Dispatchers.IO) {
            val file = MediaFiles.fileFor(message)
            val bitmap = file?.let { Thumbnails.of(it) }
            if (file != null && bitmap != null) PictureState.Ready(bitmap, file) else PictureState.Missing
        }
    }

    Box(modifier = Modifier.size(width = 320.dp, height = 240.dp), contentAlignment = Alignment.Center) {
        when (val current = picture) {
            is PictureState.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable { openInViewer(current.file) }
            )
            // A picture received or sent on the phone: the backup carries its name, not its bytes.
            PictureState.Missing -> Text(Strings.MEDIA_NOT_AVAILABLE, style = MaterialTheme.typography.bodySmall)
            PictureState.Loading -> {}
        }
    }
}

/**
 * The quote at the top of a bubble: the message this one replies to, or the one it forwards.
 * What the phone draws in its "reply container": who wrote it, its text and, when the quoted
 * message is a picture, a small copy of that picture on the right.
 */
@Composable
internal fun MessageQuote(state: AppState, message: Message) {
    val picture by produceState<ImageBitmap?>(null, message.messageId, message.replyId) {
        value = withContext(Dispatchers.IO) {
            state.quotedMediaOf(message)?.let { Thumbnails.of(it) }
        }
    }
    val name = state.quotedNameOf(message)
    val text = message.textAttached ?: ""

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                if (message.subType == SubType.FORWARD) {
                    Text(Strings.FORWARDED, style = MaterialTheme.typography.labelSmall)
                }
                if (name.isNotEmpty()) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (text.isNotEmpty()) {
                    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            picture?.let { bitmap ->
                Spacer(Modifier.width(8.dp))
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
                )
            }
        }
    }
}

/**
 * What the phone's SendImage screen is: the picture, a caption, send or cancel. When the picture
 * is being forwarded, [quoteText] and [quoteName] are the words and the name it came with, shown
 * on a card that [onDropQuote] takes away.
 */
@Composable
internal fun SendPhotoDialog(
    file: File,
    initialCaption: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    quoteName: String? = null,
    quoteText: String? = null,
    onDropQuote: () -> Unit = {},
) {
    var caption by remember(file) { mutableStateOf(initialCaption) }
    val preview by produceState<PictureState>(PictureState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            val bitmap = Thumbnails.of(file)
            if (bitmap != null) PictureState.Ready(bitmap, file) else PictureState.Missing
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(Strings.SEND_PHOTO_TITLE) },
        text = {
            Column {
                Box(modifier = Modifier.size(width = 400.dp, height = 300.dp), contentAlignment = Alignment.Center) {
                    when (val current = preview) {
                        is PictureState.Ready -> Image(
                            bitmap = current.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        PictureState.Missing -> Text(Strings.PHOTO_UNREADABLE, color = MaterialTheme.colorScheme.error)
                        PictureState.Loading -> CircularProgressIndicator()
                    }
                }
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
            Button(enabled = preview is PictureState.Ready, onClick = { onSend(caption) }) { Text(Strings.SEND) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(Strings.CANCEL) }
        }
    )
}

private fun openInViewer(file: File) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    } catch (e: Exception) {
        // No viewer for this file: the small copy in the chat is all there is.
    }
}
