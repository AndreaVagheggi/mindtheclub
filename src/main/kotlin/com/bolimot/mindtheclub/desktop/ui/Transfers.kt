package com.bolimot.mindtheclub.desktop.ui

import com.bolimot.mindtheclub.desktop.media.AnimatedPictures
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/*
 * What comes into a chat from outside the window: copied and pasted with Ctrl+V in the message
 * box, or dragged from a browser or from Explorer and dropped on the window. The PC's
 * counterpart of what reaches the phone from the keyboard (ChatScreen.handlePasteContent) and
 * from other apps (sharing/EntryPoint.kt), read once here for both gestures.
 *
 * Each kind goes where the phone sends it: a GIF to the SendGif screen, a picture with
 * see-through parts straight out as a sticker, a picture to the SendImage screen (several to
 * SendImages), a video to SendVideo, any other file to SendFile. Text is the one difference
 * between the two gestures: a paste of text is left to the message box, which pastes it where
 * the cursor is; a dropped text is written into the box.
 */

/** Something that came in from outside the window. */
internal sealed interface Incoming {
    /** A GIF: the SendGif screen. */
    class Gif(val file: File) : Incoming

    /**
     * The address of a GIF on the web: downloaded (downloadGif), then the SendGif screen. What a
     * browser gives for a GIF whatever is chosen: "Copy image address" and "Copy link" copy the
     * address, "Copy image" and a drag carry one still frame with, beside it, the page's <img>
     * tag. The phone never meets this: its GIFs come whole from the keyboard. [address] is what
     * came, for the message box if the download fails.
     */
    class GifAt(val url: String, val address: String) : Incoming

    /**
     * A picture dragged from a page, which the browser hands over only as addresses, best
     * first: downloaded by receive (downloadPicture), then a GIF, a sticker or a photo by what
     * came.
     */
    class PictureAt(val urls: List<String>) : Incoming

    /** A picture with see-through parts: a sticker, sent at once. */
    class Sticker(val file: File) : Incoming

    /** Any other picture: the SendImage screen. */
    class Photo(val file: File) : Incoming

    /** Several pictures: the SendImages screen. */
    class Album(val files: List<File>) : Incoming

    /** A video: the SendVideo screen. */
    class Video(val file: File) : Incoming

    /** Any other file: the SendFile screen. */
    class AnyFile(val file: File) : Incoming

    /** Text dropped on the window: it goes into the message box. */
    class Text(val text: String) : Incoming
}

/** The kinds of file that are pictures and videos (the paperclip's Images and Video). */
private val PICTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "webp")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp")

/** The src of the first <img> of a piece of HTML. */
private val imgSource = Regex("""<img[^>]*\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/**
 * Reads the Windows clipboard for a Ctrl+V in the message box. Null when what was copied is
 * text, or nothing this can use: the box then pastes as usual.
 */
internal fun pastedContent(): Incoming? {
    return try {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
        readTransfer(contents, dropped = false)
    } catch (e: Exception) {
        null
    }
}

/**
 * Makes the whole window a place to drop things on, as Compose's own drag support does
 * (AwtWindowDropTarget sets itself as the window's drop target). [onDrop] is called on the
 * interface thread with what was dropped; the data is read before the drop ends, because the
 * source holds it only until then.
 */
internal fun installDropTarget(window: Window, onDrop: (Incoming) -> Unit): DropTarget {
    val target = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun dragEnter(event: DropTargetDragEvent) {
            event.acceptDrag(DnDConstants.ACTION_COPY)
        }

        override fun dragOver(event: DropTargetDragEvent) {
            event.acceptDrag(DnDConstants.ACTION_COPY)
        }

        override fun drop(event: DropTargetDropEvent) {
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val incoming = try {
                readTransfer(event.transferable, dropped = true)
            } catch (e: Exception) {
                null
            }
            event.dropComplete(incoming != null)
            incoming?.let(onDrop)
        }
    }, true)
    window.dropTarget = target
    return target
}

/**
 * What [contents] holds, in order of what says the most:
 *
 *   1. the <img> of a page, when it is a GIF: a browser's still frame never moves, its address does;
 *   2. files, from Explorer: one picture, video or file; several pictures as an album;
 *   3. dropped from a page, a picture's address: the full size one, or the one on the page;
 *   4. text: the address of a GIF, or, dropped, any text; pasted, other text is the box's;
 *   5. the bytes of a GIF or a PNG, or a bare picture (a screenshot): GIF, sticker or photo.
 *
 * A format a browser announces and then does not give (it happens) is skipped, not fatal.
 */
private fun readTransfer(contents: Transferable, dropped: Boolean): Incoming? {
    readHtml(contents)?.let { html ->
        val source = imgSource.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
        source?.let { gifAddressOf(it) }?.let { return Incoming.GifAt(it, "") }
    }

    // A browser announces a file for a dragged picture and, asked for it, answers "no native data
    // was transferred" (25 Nov 2026, a picture from Google Images): a file list that cannot be
    // read is no file list, and what else was dragged is read below.
    val files = if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        try {
            (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                ?.filterIsInstance<File>()?.filter { it.isFile }
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }
    if (!files.isNullOrEmpty()) {
        if (files.size == 1) return ofFile(files[0])
        // Several files are an album when they are all pictures; anything else, the phone
        // too sends one file at a time.
        if (files.all { it.extension.lowercase() in PICTURE_EXTENSIONS }) return Incoming.Album(files)
        return null
    }

    // A picture dragged from a page: the browser gives no picture, only where it is. The full
    // size one when the address says (Google Images puts it in "imgurl"), the one on the page
    // otherwise. Downloaded by receive, as a GIF's.
    if (dropped) {
        val text = readString(contents)
        val html = readHtml(contents)
        val onPage = html?.let { imgSource.find(it)?.groupValues?.get(1)?.replace("&amp;", "&") }
            ?.takeIf { it.startsWith("http", ignoreCase = true) || it.startsWith("data:image/", ignoreCase = true) }
        val fullSize = text?.let { fullSizePictureIn(it) }
        val candidates = listOfNotNull(fullSize, onPage).distinct()
        if (candidates.isNotEmpty()) {
            candidates.firstNotNullOfOrNull { gifAddressOf(it) }?.let { return Incoming.GifAt(it, "") }
            return Incoming.PictureAt(candidates)
        }
    }

    // A word processor copies a picture of the text too: a copied text wins over it. A picture
    // dragged from a page is the exception: it brings its address as text beside the picture,
    // and what was dragged is the picture.
    if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        val text = readString(contents) ?: return null
        gifAddressOf(text)?.let { return Incoming.GifAt(it, text) }
        val pictureWithItsAddress = dropped && contents.isDataFlavorSupported(DataFlavor.imageFlavor) &&
                text.trim().let { it.startsWith("http", ignoreCase = true) && it.none { c -> c.isWhitespace() } }
        if (!pictureWithItsAddress) {
            return if (dropped && text.isNotBlank()) Incoming.Text(text) else null
        }
    }

    val gifFlavor = DataFlavor("image/gif; class=java.io.InputStream")
    if (contents.isDataFlavorSupported(gifFlavor)) {
        val bytes = (contents.getTransferData(gifFlavor) as InputStream).use { it.readBytes() }
        return Incoming.Gif(scratchFile("gif").apply { writeBytes(bytes) })
    }
    val pngFlavor = DataFlavor("image/png; class=java.io.InputStream")
    if (contents.isDataFlavorSupported(pngFlavor)) {
        val bytes = (contents.getTransferData(pngFlavor) as InputStream).use { it.readBytes() }
        val file = scratchFile("png").apply { writeBytes(bytes) }
        val image = ImageIO.read(file) ?: return null
        return if (AnimatedPictures.hasTransparency(image)) Incoming.Sticker(file) else Incoming.Photo(file)
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
        return if (AnimatedPictures.hasTransparency(copy)) Incoming.Sticker(file) else Incoming.Photo(file)
    }
    return null
}

private fun readString(contents: Transferable): String? = try {
    if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) contents.getTransferData(DataFlavor.stringFlavor) as? String else null
} catch (e: Exception) {
    null
}

private fun readHtml(contents: Transferable): String? = try {
    if (contents.isDataFlavorSupported(DataFlavor.fragmentHtmlFlavor)) contents.getTransferData(DataFlavor.fragmentHtmlFlavor) as? String else null
} catch (e: Exception) {
    null
}

/**
 * The address of the full size picture inside the address of a page about it: Google Images
 * drags ".../imgres?...&imgurl=<the picture>&imgrefurl=<its page>...". Null otherwise.
 */
private fun fullSizePictureIn(text: String): String? {
    val address = text.trim()
    if (address.any { it.isWhitespace() }) return null
    val query = address.substringAfter('?', "")
    if (query.isEmpty()) return null
    for (part in query.split('&')) {
        if (part.startsWith("imgurl=")) {
            val value = try {
                java.net.URLDecoder.decode(part.removePrefix("imgurl="), Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            }
            return value.takeIf { it.startsWith("http", ignoreCase = true) }
        }
    }
    return null
}

/** One file from Explorer, by its kind. */
private fun ofFile(file: File): Incoming {
    val extension = file.extension.lowercase()
    return when {
        extension == "gif" -> Incoming.Gif(file)
        extension in PICTURE_EXTENSIONS -> {
            val image = try {
                ImageIO.read(file)
            } catch (e: Exception) {
                null
            }
            // A picture this PC cannot read (a WebP) still goes to the SendImage screen, which says so.
            if (image != null && AnimatedPictures.hasTransparency(image)) Incoming.Sticker(file) else Incoming.Photo(file)
        }
        extension in VIDEO_EXTENSIONS -> Incoming.Video(file)
        else -> Incoming.AnyFile(file)
    }
}
