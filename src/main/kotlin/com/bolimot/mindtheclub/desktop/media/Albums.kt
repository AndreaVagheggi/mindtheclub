package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Albums, several pictures in one message (Type.MULTIPLE_IMAGES), in the form the phone gives
 * them on the wire.
 *
 * The message row keeps the pictures as a comma separated list of URIs. What travels is ONE
 * file: the pictures one after the other, each followed by the bytes of "--SEPARATOR--"
 * (functions/bitmap.kt in the App: mergeImages builds it when the message is dispatched,
 * extractImages takes it apart on arrival). The framing is the App's to the byte, because
 * every phone already deployed reads it.
 */
object Albums {

    private const val TAG = "Albums"
    private val SEPARATOR = "--SEPARATOR--".toByteArray()

    /** buildMultiImagePreview: four cells of 512 on a light grey ground, JPEG at quality 50. */
    private const val PREVIEW_CELL = 512
    private const val PREVIEW_COLUMNS = 2
    private const val PREVIEW_ROWS = 2
    const val PREVIEW_QUALITY = 0.50f

    /** The pictures of an album message, in order, those on this PC. */
    fun filesOf(uriCsv: String?): List<File> =
        uriCsv.orEmpty().split(",").mapNotNull { MediaFiles.fileOfUri(it.trim()) }

    /** How many pictures an album message has, here or not (MessagesAdapter.calculateGroupSize). */
    fun countOf(uriCsv: String?): Int = uriCsv.orEmpty().split(",").count { it.isNotBlank() }

    /** Where the merged file of a message being sent is kept until its receiver has it. */
    fun mergedFile(messageId: String): File = File(App.context().filesDir, "merge$messageId.dat")

    /**
     * mergeImages: the one file an album travels as, made once per message and reused for every
     * resend. The pictures are already the ones that leave the PC (MediaFiles.prepareForSending
     * ran when they were chosen), so they are copied as they are; the App recompresses here
     * only because its list holds the gallery's originals. Null when a picture is missing.
     */
    fun merge(uriCsv: String?, messageId: String): String? {
        val merged = mergedFile(messageId)
        if (merged.isFile && merged.length() > 0) return merged.toURI().toString()

        val uris = uriCsv.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val files = uris.map { MediaFiles.fileOfUri(it) }
        if (files.isEmpty() || files.any { it == null }) {
            debugLine(TAG, "Album $messageId: a picture is not on this PC, nothing to merge")
            return null
        }

        val temp = File(App.context().filesDir, "merging_${guid()}.dat")
        return try {
            FileOutputStream(temp).use { out ->
                for (file in files) {
                    file!!.inputStream().use { it.copyTo(out) }
                    out.write(SEPARATOR)
                }
            }
            if (!temp.renameTo(merged)) {
                temp.delete()
                return if (merged.isFile) merged.toURI().toString() else null
            }
            merged.toURI().toString()
        } catch (e: Exception) {
            debugLine(TAG, "Album $messageId could not be merged: ${e.message}")
            temp.delete()
            null
        }
    }

    /**
     * extractImages: the pictures of a received album, each in a file of its own in the scratch
     * folder, in order. A part that is not a picture is the caller's to drop (the App skips what
     * BitmapFactory cannot decode). Empty when nothing could be read.
     */
    fun split(merged: File): List<File> {
        val parts = ArrayList<File>()
        val current = ByteArrayOutputStream()

        fun flush() {
            if (current.size() == 0) return
            val part = File(App.context().filesDir, "album_part_${guid()}.jpg")
            part.writeBytes(current.toByteArray())
            parts.add(part)
            current.reset()
        }

        try {
            merged.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var matched = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    for (i in 0 until read) {
                        val byte = buffer[i]
                        if (byte == SEPARATOR[matched]) {
                            matched++
                            if (matched == SEPARATOR.size) {
                                flush()
                                matched = 0
                            }
                        } else {
                            if (matched > 0) {
                                current.write(SEPARATOR, 0, matched)
                                matched = 0
                            }
                            // The byte that broke a partial match may begin a new one, as in
                            // the App's loop, which compares it with the first byte again only
                            // on the next round: kept identical, the framing never puts a
                            // separator inside a picture's bytes anyway.
                            current.write(byte.toInt())
                        }
                    }
                }
            }
            flush()
        } catch (e: Exception) {
            debugLine(TAG, "Album ${merged.name} could not be read: ${e.message}")
            parts.forEach { it.delete() }
            return emptyList()
        }
        return parts
    }

    /**
     * buildMultiImagePreview: the picture a reply to an album carries, the first four pictures
     * in a two by two grid, each stretched to its cell as the App does. Null when none can be read.
     */
    fun preview(files: List<File>): BufferedImage? {
        val cells = files.take(PREVIEW_COLUMNS * PREVIEW_ROWS).mapNotNull { MediaFiles.thumbnail(it, PREVIEW_CELL) }
        if (cells.isEmpty()) return null

        val grid = BufferedImage(PREVIEW_CELL * PREVIEW_COLUMNS, PREVIEW_CELL * PREVIEW_ROWS, BufferedImage.TYPE_INT_RGB)
        val g = grid.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color.LIGHT_GRAY
            g.fillRect(0, 0, grid.width, grid.height)
            cells.forEachIndexed { index, cell ->
                val x = (index % PREVIEW_COLUMNS) * PREVIEW_CELL
                val y = (index / PREVIEW_COLUMNS) * PREVIEW_CELL
                g.drawImage(cell, x, y, PREVIEW_CELL, PREVIEW_CELL, null)
            }
        } finally {
            g.dispose()
        }
        return grid
    }
}
