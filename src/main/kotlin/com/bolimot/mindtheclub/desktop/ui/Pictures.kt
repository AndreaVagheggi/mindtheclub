package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.media.MediaFiles
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

/** What the phone's SendImage screen is: the picture, a caption, send or cancel. */
@Composable
internal fun SendPhotoDialog(file: File, initialCaption: String, onSend: (String) -> Unit, onClose: () -> Unit) {
    var caption by remember(file) { mutableStateOf(initialCaption) }
    val preview by produceState<PictureState>(PictureState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            val bitmap = Thumbnails.of(file)
            if (bitmap != null) PictureState.Ready(bitmap, file) else PictureState.Missing
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
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
