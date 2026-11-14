package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.storage.DataDir
import com.bolimot.mindtheclub.functions.computeSha256
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The image files of this PC: where they are kept, and how a picture chosen by the user becomes
 * the file that is sent.
 *
 * On the phone a received image goes to the public folder Pictures/MindTheClub through
 * MediaStore, named after the SHA-256 of its bytes, so that the same picture received twice is
 * one file (receiving/receiveManager.kt, receiveImage). The PC keeps the same rule and the same
 * names in its own folder, %LOCALAPPDATA%\MindTheClub\media. A message points at its file with
 * a file: URI in Message.uri, as on the phone it points at a content: URI.
 *
 * WHAT DOES NOT TRAVEL. The phone's backup carries the NAMES of the media files, never their
 * bytes (BackupData.mediaFileNames). So a picture received on the phone is not on the PC after
 * a load, and a picture received on the PC is not on the phone after the way back: in both
 * cases the bubble is there and the picture is not. Carrying the bytes is the job of the direct
 * handover.
 */
object MediaFiles {

    /** The picture cannot be used. [message] is for the log; the window has its own words. */
    class Refused(message: String) : Exception(message)

    // The App's functions/bitmap.kt: MAX_SAVED_IMAGE_DIMENSION and SENT_IMAGE_QUALITY. A picture
    // leaves the phone as a JPEG no larger than this on its longest side, at this quality.
    private const val MAX_SENT_DIMENSION = 2048
    private const val SENT_IMAGE_QUALITY = 0.80f

    fun dir(): File = DataDir.media()

    /** The file a file: URI names, if it is on this PC. Null for any other URI, the phone's included. */
    fun fileOfUri(uri: String?): File? {
        if (uri.isNullOrEmpty() || !uri.startsWith("file:")) return null
        return try {
            File(URI(uri)).takeIf { it.isFile }
        } catch (e: Exception) {
            null
        }
    }

    /** The picture of an image message, if this PC has it. */
    fun fileFor(message: Message): File? = fileOfUri(message.uri)

    /**
     * An extension that is safe to put in a file name on this disk. The extension of a received
     * file is text written by the sender: left as it is, "../x" or "a\b" would name a file
     * outside the media folder. Letters and digits only, at most ten; "bin" when nothing is left.
     */
    fun safeExtension(extension: String): String {
        val cleaned = extension.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(10).lowercase()
        return cleaned.ifEmpty { "bin" }
    }

    /**
     * Copies a file the user chose into the media folder, named after the hash of its bytes
     * plus its extension, and returns the copy. What the phone's copyUri does before a file is
     * sent: the message then points at a copy of its own, and the original can be moved or
     * deleted without breaking a later resend.
     */
    suspend fun keepCopyForSending(source: File): File = withContext(Dispatchers.IO) {
        if (!source.isFile) throw Refused("no such file")
        val temp = File(App.context().filesDir, "outgoing_${guid()}.bin")
        try {
            Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val hash = computeSha256(temp.inputStream()) ?: throw Refused("the file could not be read back")
            keep(temp, "$hash.${safeExtension(source.extension)}")
        } catch (e: Refused) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw Refused("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Whether [file] is one of the files this PC keeps (and not, say, a picture elsewhere on disk). */
    fun isKept(file: File): Boolean = file.parentFile?.absoluteFile == dir().absoluteFile

    /**
     * Moves [temp] into the media folder under [name]. The name is the hash of the content, so a
     * file already there under that name IS this content, and is reused.
     */
    fun keep(temp: File, name: String): File {
        val target = File(dir(), name)
        if (target.isFile && target.length() == temp.length()) {
            temp.delete()
            return target
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * Turns a picture the user chose into the file that is sent, and keeps it in the media
     * folder. Same result as the phone's saveBitmapFromUri: turned the way its EXIF tag says,
     * no larger than 2048 on the longest side (never enlarged), JPEG at quality 80.
     */
    suspend fun prepareForSending(source: File): File = withContext(Dispatchers.IO) {
        if (!source.isFile) throw Refused("no such file")

        val prepared = try {
            orient(toRgbScaled(decode(source, MAX_SENT_DIMENSION), MAX_SENT_DIMENSION), exifOrientation(source))
        } catch (e: Refused) {
            throw e
        } catch (e: Throwable) {
            throw Refused("${e.javaClass.simpleName}: ${e.message}")
        }

        val temp = File(App.context().filesDir, "outgoing_${guid()}.jpg")
        try {
            writeJpeg(prepared, temp, SENT_IMAGE_QUALITY)
            val hash = computeSha256(temp.inputStream()) ?: throw Refused("the file could not be read back")
            keep(temp, "$hash.jpg")
        } catch (e: Refused) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw Refused("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Keeps [image] in the media folder as a JPEG at [quality], named after the hash of its
     * bytes like every other file here. For a picture that does not come from a file: the still
     * frame of a video that a reply quotes (the phone's saveFirstVideoFrame).
     */
    suspend fun keepJpeg(image: BufferedImage, quality: Float): File = withContext(Dispatchers.IO) {
        val temp = File(App.context().filesDir, "outgoing_${guid()}.jpg")
        try {
            writeJpeg(toRgbScaled(image, max(image.width, image.height)), temp, quality)
            val hash = computeSha256(temp.inputStream()) ?: throw Refused("the file could not be read back")
            keep(temp, "$hash.jpg")
        } catch (e: Refused) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw Refused("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * A small copy of a picture for the window, no larger than [longSide]. Null when the file
     * cannot be read as an image. Blocking: call it off the interface thread.
     */
    fun thumbnail(file: File, longSide: Int): BufferedImage? {
        return try {
            orient(toRgbScaled(decode(file, longSide), longSide), exifOrientation(file))
        } catch (e: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ reading and writing

    /**
     * Reads a picture, skipping pixels while reading when it is far larger than needed: a 50
     * megapixel photo decoded whole takes 200 MB of memory, which is the reason the phone never
     * decodes one whole either.
     */
    private fun decode(file: File, longSide: Int): BufferedImage {
        val input = ImageIO.createImageInputStream(file) ?: throw Refused("the file cannot be opened")
        val image: BufferedImage? = input.use {
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) throw Refused("not a JPEG, PNG, BMP or GIF picture")
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val param = reader.defaultReadParam
                val step = max(1, max(width, height) / (longSide * 2))
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
        return image ?: throw Refused("the picture has no content")
    }

    /**
     * The picture as plain RGB, reduced so that its longest side is at most [longSide]. Never
     * enlarges. A transparent background becomes white: JPEG has no transparency.
     */
    private fun toRgbScaled(source: BufferedImage, longSide: Int): BufferedImage {
        var current = source
        // Halving first: one large step from a big picture to a small one loses detail.
        while (max(current.width, current.height) >= longSide * 2) {
            current = redraw(
                current,
                max(1, current.width / 2),
                max(1, current.height / 2),
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
        }
        val longest = max(current.width, current.height)
        var width = current.width
        var height = current.height
        if (longest > longSide) {
            val scale = longSide.toDouble() / longest
            width = (current.width * scale).roundToInt().coerceAtLeast(1)
            height = (current.height * scale).roundToInt().coerceAtLeast(1)
        }
        if (width == current.width && height == current.height && current.type == BufferedImage.TYPE_INT_RGB) {
            return current
        }
        return redraw(current, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    private fun redraw(source: BufferedImage, width: Int, height: Int, interpolation: Any): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun writeJpeg(image: BufferedImage, target: File, quality: Float) {
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) throw Refused("this Java cannot write JPEG")
        val writer = writers.next()
        try {
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = quality
            // The stream writes into an existing file without shortening it.
            target.delete()
            FileImageOutputStream(target).use { out ->
                writer.output = out
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
    }

    // ------------------------------------------------------------------ orientation

    /**
     * A camera stores a photo as the sensor saw it and adds a tag saying how to turn it. Java's
     * reader ignores the tag, so a portrait photo would be sent lying on its side. The values
     * are the EXIF standard's, 1 to 8; 1 means "as it is".
     */
    private fun orient(source: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return source
        val w = source.width.toDouble()
        val h = source.height.toDouble()
        val t = AffineTransform()
        when (orientation) {
            2 -> { t.scale(-1.0, 1.0); t.translate(-w, 0.0) }
            3 -> { t.translate(w, h); t.rotate(Math.PI) }
            4 -> { t.scale(1.0, -1.0); t.translate(0.0, -h) }
            5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0) }
            6 -> { t.translate(h, 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0.0); t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
            8 -> { t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
        }
        val turned = orientation >= 5
        val out = BufferedImage(
            if (turned) source.height else source.width,
            if (turned) source.width else source.height,
            BufferedImage.TYPE_INT_RGB
        )
        val g = out.createGraphics()
        try {
            g.drawImage(source, t, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** The orientation tag of a JPEG file, 1 when there is none or the file is not a JPEG. */
    private fun exifOrientation(file: File): Int {
        return try {
            // The tag sits in the first segments of the file, before the picture data.
            val head = file.inputStream().use { it.readNBytes(131072) }
            fun u8(i: Int): Int = head[i].toInt() and 0xFF

            if (head.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 1
            var p = 2
            while (p + 4 <= head.size) {
                if (u8(p) != 0xFF) return 1
                val marker = u8(p + 1)
                if (marker == 0xFF) {
                    p++
                    continue
                }
                // Picture data, or the end: no EXIF segment comes after these.
                if (marker == 0xDA || marker == 0xD9) return 1
                val length = (u8(p + 2) shl 8) or u8(p + 3)
                if (length < 2) return 1
                if (marker == 0xE1 && p + 10 <= head.size &&
                    u8(p + 4) == 'E'.code && u8(p + 5) == 'x'.code && u8(p + 6) == 'i'.code &&
                    u8(p + 7) == 'f'.code && u8(p + 8) == 0 && u8(p + 9) == 0
                ) {
                    return orientationFromTiff(head, p + 10, minOf(head.size, p + 2 + length))
                }
                p += 2 + length
            }
            1
        } catch (e: Exception) {
            1
        }
    }

    /** Reads tag 0x0112 from the TIFF block of an EXIF segment, [start] to [end] in [b]. */
    private fun orientationFromTiff(b: ByteArray, start: Int, end: Int): Int {
        if (start + 8 > end) return 1
        val little = when {
            b[start] == 'I'.code.toByte() && b[start + 1] == 'I'.code.toByte() -> true
            b[start] == 'M'.code.toByte() && b[start + 1] == 'M'.code.toByte() -> false
            else -> return 1
        }
        fun u8(i: Int): Int = b[i].toInt() and 0xFF
        fun u16(i: Int): Int {
            if (i < start || i + 2 > end) return -1
            return if (little) u8(i) or (u8(i + 1) shl 8) else (u8(i) shl 8) or u8(i + 1)
        }
        fun u32(i: Int): Long {
            if (i < start || i + 4 > end) return -1
            return if (little) {
                u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
            } else {
                (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
            }
        }

        if (u16(start + 2) != 42) return 1
        val offset = u32(start + 4)
        if (offset < 8 || offset > end - start) return 1
        val directory = start + offset.toInt()
        val entries = u16(directory)
        if (entries < 0) return 1
        for (i in 0 until entries) {
            val entry = directory + 2 + i * 12
            if (entry + 12 > end) return 1
            if (u16(entry) == 0x0112) {
                val value = u16(entry + 8)
                return if (value in 1..8) value else 1
            }
        }
        return 1
    }
}
