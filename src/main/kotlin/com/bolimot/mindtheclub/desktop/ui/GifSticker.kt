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
import androidx.compose.runtime.LaunchedEffect
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
import com.bolimot.mindtheclub.desktop.media.AnimatedPictures
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/*
 * GIFs and stickers, as the phone has them.
 *
 *   - a GIF is drawn like a picture (the phone's PictureViewHolder, which Glide makes move), with
 *     its caption under it; a click opens it in the PC's viewer, as a picture;
 *   - a sticker is a small square of 82 (chat_sticker_in.xml, StickerViewHolder), its
 *     transparent parts kept;
 *   - SendGifDialog is the SendGif screen: the GIF moving, a caption, send or cancel.
 *
 * On the phone they come from the keyboard (ChatScreen.handlePasteContent: a GIF opens SendGif,
 * a PNG leaves at once as a sticker) or from another app. The PC's keyboard is Ctrl+V in the
 * message box: pastedPicture reads what was copied.
 */

/** The size the bubble draws a GIF at, as a picture. */
private const val GIF_LONG_SIDE = 320

/** chat_sticker_in.xml: the side of a sticker. */
private val STICKER_SIDE = 82.dp

/** A GIF playing, its frames read once (AnimatedPictures) and shown one after the other. */
@Composable
private fun PlayingGif(file: File?, modifier: Modifier) {
    val animation by produceState<AnimatedPictures.Animation?>(null, file) {
        value = file?.let { withContext(Dispatchers.IO) { AnimatedPictures.animationOf(it, GIF_LONG_SIDE) } }
    }
    val frames by produceState<List<ImageBitmap>?>(null, animation) {
        value = animation?.let { a -> withContext(Dispatchers.Default) { a.frames.map { it.toComposeImageBitmap() } } }
    }
    var index by remember(animation) { mutableStateOf(0) }

    LaunchedEffect(frames) {
        val shown = frames ?: return@LaunchedEffect
        val delays = animation?.delaysMs ?: return@LaunchedEffect
        if (shown.size < 2) return@LaunchedEffect
        while (true) {
            delay(delays.getOrElse(index) { 100L })
            index = (index + 1) % shown.size
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val current = frames?.getOrNull(index)
        when {
            current != null -> Image(
                bitmap = current,
                contentDescription = Strings.GIF,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            file == null -> Text(Strings.MEDIA_NOT_AVAILABLE, style = MaterialTheme.typography.bodySmall)
            else -> {}
        }
    }
}

/** The bubble of a GIF. */
@Composable
internal fun MessageGif(message: Message) {
    val file = remember(message.uri) { MediaFiles.fileFor(message) }
    PlayingGif(
        file,
        modifier = Modifier
            .size(width = 320.dp, height = 240.dp)
            .clickable(enabled = file != null) { file?.let { openInViewer(it) } }
    )
}

/** The bubble of a sticker. */
@Composable
internal fun MessageSticker(message: Message) {
    val picture by produceState<ImageBitmap?>(null, message.uri) {
        value = withContext(Dispatchers.IO) {
            MediaFiles.fileFor(message)?.let { AnimatedPictures.stickerOf(it, 246) }?.toComposeImageBitmap()
        }
    }
    Box(modifier = Modifier.size(STICKER_SIDE), contentAlignment = Alignment.Center) {
        val current = picture
        if (current != null) {
            Image(bitmap = current, contentDescription = Strings.STICKER, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(Strings.STICKER, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The SendGif screen: the GIF, a caption, send or cancel. When the GIF is being forwarded,
 * [quoteText] and [quoteName] are the words and the name it came with, shown on a card that
 * [onDropQuote] takes away.
 */
@Composable
internal fun SendGifDialog(
    file: File,
    initialCaption: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    quoteName: String? = null,
    quoteText: String? = null,
    onDropQuote: () -> Unit = {},
) {
    var caption by remember(file) { mutableStateOf(initialCaption) }
    val readable by produceState<Boolean?>(null, file) {
        value = withContext(Dispatchers.IO) { AnimatedPictures.animationOf(file, 1) != null }
    }

    AlertDialog(
        onDismissRequest = onClose,
        properties = StaysOpen,
        title = { Text(Strings.SEND_GIF_TITLE) },
        text = {
            Column {
                Box(modifier = Modifier.size(width = 400.dp, height = 300.dp), contentAlignment = Alignment.Center) {
                    when (readable) {
                        null -> CircularProgressIndicator()
                        false -> Text(Strings.PHOTO_UNREADABLE, color = MaterialTheme.colorScheme.error)
                        true -> PlayingGif(file, Modifier.fillMaxSize())
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
            Button(enabled = readable == true, onClick = { onSend(caption) }) { Text(Strings.SEND) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(Strings.CANCEL) }
        }
    )
}

/** What a Ctrl+V in the message box brought, when it is a picture. */
internal sealed interface PastedPicture {
    val file: File

    /** A GIF: the SendGif screen. */
    class Gif(override val file: File) : PastedPicture

    /** A picture with see-through parts: a sticker, sent at once. */
    class Sticker(override val file: File) : PastedPicture

    /** Any other picture: the screen of a photo. */
    class Photo(override val file: File) : PastedPicture
}

/**
 * Reads the Windows clipboard for a Ctrl+V in the message box. Null when what was copied is
 * text, or nothing this can use: the box then pastes as usual. A copied text wins over a
 * picture that comes with it (a word processor copies a picture of the text too).
 */
internal fun pastedPicture(): PastedPicture? {
    return try {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null

        // Files copied in Explorer: one picture file is taken, anything else is not a picture.
        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()
            val file = files?.singleOrNull() ?: return null
            return when (file.extension.lowercase()) {
                "gif" -> PastedPicture.Gif(file)
                "png", "jpg", "jpeg", "bmp", "webp" -> {
                    val image = ImageIO.read(file) ?: return null
                    if (AnimatedPictures.hasTransparency(image)) PastedPicture.Sticker(file) else PastedPicture.Photo(file)
                }
                else -> null
            }
        }

        if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null

        // The picture's own bytes, when the program that copied it offers them.
        val gifFlavor = DataFlavor("image/gif; class=java.io.InputStream")
        if (contents.isDataFlavorSupported(gifFlavor)) {
            val bytes = (contents.getTransferData(gifFlavor) as InputStream).use { it.readBytes() }
            return PastedPicture.Gif(scratchFile("gif").apply { writeBytes(bytes) })
        }
        val pngFlavor = DataFlavor("image/png; class=java.io.InputStream")
        if (contents.isDataFlavorSupported(pngFlavor)) {
            val bytes = (contents.getTransferData(pngFlavor) as InputStream).use { it.readBytes() }
            val file = scratchFile("png").apply { writeBytes(bytes) }
            val image = ImageIO.read(file) ?: return null
            return if (AnimatedPictures.hasTransparency(image)) PastedPicture.Sticker(file) else PastedPicture.Photo(file)
        }

        // Only a bitmap: a screenshot, a picture copied from a page.
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            if (width <= 0 || height <= 0) return null
            val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            copy.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
            val file = scratchFile("png")
            ImageIO.write(copy, "png", file)
            return if (AnimatedPictures.hasTransparency(copy)) PastedPicture.Sticker(file) else PastedPicture.Photo(file)
        }
        null
    } catch (e: Exception) {
        null
    }
}

private fun scratchFile(extension: String): File = File(App.context().filesDir, "pasted_${guid()}.$extension")

private fun openInViewer(file: File) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    } catch (e: Exception) {
        // No viewer for this file: the bubble is all there is.
    }
}
