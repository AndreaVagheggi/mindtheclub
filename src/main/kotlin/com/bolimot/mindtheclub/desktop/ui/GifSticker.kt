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
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
 * message box, or a drag into the window: Transfers.kt reads what was copied or dropped.
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

/** A GIF downloaded from the web larger than this is not taken: it is not a chat's GIF. */
private const val MAX_GIF_BYTES = 20L * 1024 * 1024

private val gifClient: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build()

/**
 * The address to download a GIF from, when [text] is nothing but the address of one: a path
 * ending in .gif, or one of Giphy's media addresses, whose .webp and .mp4 have a .gif beside
 * them under the same name (checked on 25 Nov 2026: ".../200.webp" and ".../200.gif"). Null
 * for any other text, which is then pasted as text.
 */
internal fun gifAddressOf(text: String): String? {
    val address = text.trim()
    if (address.isEmpty() || address.any { it.isWhitespace() }) return null
    if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) return null
    val uri = try {
        URI(address)
    } catch (e: Exception) {
        return null
    }
    val host = uri.host?.lowercase() ?: return null
    val path = uri.path ?: return null
    val lowerPath = path.lowercase()
    return when {
        lowerPath.endsWith(".gif") -> address
        host.endsWith("giphy.com") && host.startsWith("media") &&
                (lowerPath.endsWith(".webp") || lowerPath.endsWith(".mp4")) ->
            "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.substringBeforeLast('.')}.gif"
        else -> null
    }
}

/** Downloads the GIF at [url] into the scratch folder. Null when what comes is not a GIF. */
internal suspend fun downloadGif(url: String): File? {
    val file = download(url) ?: return null
    if (AnimatedPictures.extensionOf(file) != "gif") {
        file.delete()
        return null
    }
    return file
}

/**
 * Downloads a picture dragged from a page, trying [urls] in order, into the scratch folder,
 * named by what its bytes are (gif, png, jpg). An address may also be a "data:" one, the picture
 * written inside the page. Null when none of them gives a picture this PC can read.
 */
internal suspend fun downloadPicture(urls: List<String>): File? = withContext(Dispatchers.IO) {
    for (url in urls) {
        val file = if (url.startsWith("data:", ignoreCase = true)) fromDataAddress(url) else download(url)
        file ?: continue
        val kind = AnimatedPictures.extensionOf(file)
        val readable = kind == "gif" || try {
            javax.imageio.ImageIO.read(file) != null
        } catch (e: Exception) {
            false
        }
        if (!readable) {
            file.delete()
            continue
        }
        val named = scratchFile(kind)
        if (file.renameTo(named)) return@withContext named
        return@withContext file
    }
    null
}

/** "data:image/png;base64,...": the picture written inside the page. */
private fun fromDataAddress(address: String): File? {
    val comma = address.indexOf(',')
    if (comma < 0 || !address.substring(0, comma).contains(";base64", ignoreCase = true)) return null
    return try {
        val bytes = java.util.Base64.getMimeDecoder().decode(address.substring(comma + 1))
        if (bytes.isEmpty() || bytes.size > MAX_GIF_BYTES) null else scratchFile("bin").apply { writeBytes(bytes) }
    } catch (e: Exception) {
        null
    }
}

/** Downloads [url] into the scratch folder, as a browser would ask for it. Null on any failure. */
private suspend fun download(url: String): File? = withContext(Dispatchers.IO) {
    try {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
            .GET()
            .build()
        val response = gifClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            return@withContext null
        }
        val file = scratchFile("bin")
        response.body().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_GIF_BYTES) {
                        output.close()
                        file.delete()
                        return@withContext null
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        file
    } catch (e: Exception) {
        null
    }
}

internal fun scratchFile(extension: String): File = File(App.context().filesDir, "pasted_${guid()}.$extension")

private fun openInViewer(file: File) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
    } catch (e: Exception) {
        // No viewer for this file: the bubble is all there is.
    }
}
