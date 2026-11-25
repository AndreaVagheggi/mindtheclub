package com.bolimot.mindtheclub.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/*
 * Albums, several pictures in one message, as the phone has them.
 *
 *   - MessageAlbum is the bubble (chat_multiple_images_in.xml and chat_double_images_in.xml,
 *     MultiPictureViewHolder): squares of 112, two across. Two pictures make one row; three or
 *     more make two rows, the first four shown and "+N" over the fourth for the others. A
 *     picture that is not on this PC is a grey square, and "Media not available" is said once.
 *   - SendAlbumDialog is the SendImages screen: the picture in view, large, and under it the
 *     whole album small. A click on a small one shows it; a click on the one already shown takes
 *     it out of the album (onItemReselect). With one picture left it is a plain picture again.
 *
 * On the phone a click on the album opens the chat's gallery. The PC opens the clicked picture
 * in its own viewer, as it does for a single picture.
 */

/** chat_multiple_images_in.xml: the side of each square. */
private val ALBUM_CELL = 112.dp

/** The bubble of an album. */
@Composable
internal fun MessageAlbum(message: Message) {
    val uris = remember(message.uri) { message.uri.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
    val shown = uris.take(4)
    val more = uris.size - 4

    val cells by produceState<List<Pair<File?, ImageBitmap?>>?>(null, message.uri) {
        value = withContext(Dispatchers.IO) {
            shown.map { uri ->
                val file = MediaFiles.fileOfUri(uri)
                file to file?.let { Thumbnails.of(it) }
            }
        }
    }
    val anyMissing = cells?.any { it.second == null } == true

    Column {
        val rows = shown.indices.chunked(2)
        for (row in rows) {
            Row {
                for (index in row) {
                    val cell = cells?.getOrNull(index)
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .size(ALBUM_CELL)
                            .background(Color.LightGray)
                            .clickable(enabled = cell?.first != null) { cell?.first?.let { openInViewer(it) } },
                        contentAlignment = Alignment.Center
                    ) {
                        cell?.second?.let { bitmap ->
                            Image(
                                bitmap = bitmap,
                                contentDescription = Strings.IMAGES,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (index == 3 && more > 0) {
                            Text("+$more", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 32.sp)
                        }
                    }
                }
            }
        }
        if (anyMissing) {
            Text(
                Strings.MEDIA_NOT_AVAILABLE,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
        if (message.text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(message.text, fontSize = 16.sp, modifier = Modifier.width(ALBUM_CELL * 2))
        }
    }
}

/**
 * The SendImages screen: [initialFiles] as one album, a caption, send or cancel. When the album
 * is being forwarded, [quoteText] and [quoteName] are the words and the name it came with, shown
 * on a card that [onDropQuote] takes away. [onSingle] is called when only one picture is left.
 */
@Composable
internal fun SendAlbumDialog(
    initialFiles: List<File>,
    initialCaption: String,
    onSend: (List<File>, String) -> Unit,
    onSingle: (File, String) -> Unit,
    onClose: () -> Unit,
    quoteName: String? = null,
    quoteText: String? = null,
    onDropQuote: () -> Unit = {},
) {
    var files by remember(initialFiles) { mutableStateOf(initialFiles) }
    var current by remember(initialFiles) { mutableStateOf(0) }
    var caption by remember(initialFiles) { mutableStateOf(initialCaption) }

    val bitmaps by produceState<Map<File, ImageBitmap?>>(emptyMap(), initialFiles) {
        value = withContext(Dispatchers.IO) { initialFiles.associateWith { Thumbnails.of(it) } }
    }
    val loaded = bitmaps.size == initialFiles.size
    val unreadable = loaded && files.any { bitmaps[it] == null }

    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(Strings.SEND_PHOTO_TITLE) },
        text = {
            Column {
                Box(modifier = Modifier.size(width = 400.dp, height = 300.dp), contentAlignment = Alignment.Center) {
                    val file = files.getOrNull(current)
                    val bitmap = file?.let { bitmaps[it] }
                    when {
                        !loaded -> CircularProgressIndicator()
                        bitmap != null -> Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        else -> Text(Strings.PHOTO_UNREADABLE, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // The whole album, small (images_preview): the one in view is framed.
                Row(modifier = Modifier.width(400.dp).horizontalScroll(rememberScrollState())) {
                    files.forEachIndexed { index, file ->
                        val selected = index == current
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(56.dp)
                                .background(Color.LightGray)
                                .border(
                                    if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else BorderStroke(0.dp, Color.Transparent)
                                )
                                .clickable {
                                    if (!selected) {
                                        current = index
                                    } else {
                                        // onItemReselect: the picture leaves the album.
                                        val left = files.filterIndexed { i, _ -> i != index }
                                        if (left.size == 1) {
                                            onSingle(left[0], caption)
                                        } else {
                                            files = left
                                            current = current.coerceAtMost(left.size - 1)
                                        }
                                    }
                                }
                        ) {
                            bitmaps[file]?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().padding(if (selected) 2.dp else 0.dp)
                                )
                            }
                        }
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
            Button(enabled = loaded && !unreadable, onClick = { onSend(files, caption) }) { Text(Strings.SEND) }
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
