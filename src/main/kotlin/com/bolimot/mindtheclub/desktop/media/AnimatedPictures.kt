package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import org.w3c.dom.Node
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GIFs and stickers for the window.
 *
 * On the phone Glide plays a GIF and draws a sticker with its transparency. The PC's picture
 * path (MediaFiles.thumbnail) reads one frame and lays it on white, which is right for a photo
 * and wrong for both of these, so they are read here: every frame of a GIF, put together as a
 * browser does (each frame drawn at its place over what the previous one left), and a sticker
 * with its transparent parts kept.
 */
object AnimatedPictures {

    /** A GIF ready to play: its frames, already put together, and how long each one stays. */
    class Animation(val frames: List<BufferedImage>, val delaysMs: List<Long>)

    /** A GIF with more frames than this is shown up to here: enough for any GIF a chat carries. */
    private const val MAX_FRAMES = 300

    /** Browsers give a frame that asks for less than 20 ms a tenth of a second; so does this. */
    private const val MIN_DELAY_MS = 20L
    private const val DEFAULT_DELAY_MS = 100L

    /** The extension a received picture is kept under, read from its first bytes. */
    fun extensionOf(file: File): String {
        val head = ByteArray(12)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        if (read < 12) return "jpg"
        fun ascii(from: Int, length: Int) = String(head, from, length, Charsets.ISO_8859_1)
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        return when {
            ascii(0, 4) == "GIF8" -> "gif"
            b0 == 0x89 && ascii(1, 3) == "PNG" -> "png"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "webp"
            b0 == 0xFF && b1 == 0xD8 -> "jpg"
            else -> "jpg"
        }
    }

    /**
     * Every frame of the GIF in [file], no larger than [longSide], or null when it is not a GIF
     * this PC can read. Blocking: call it off the interface thread.
     */
    fun animationOf(file: File, longSide: Int): Animation? {
        val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull() ?: return null
        return try {
            ImageIO.createImageInputStream(file).use { input ->
                reader.input = input

                val screen = (reader.streamMetadata?.getAsTree("javax_imageio_gif_stream_1.0") as? IIOMetadataNode)
                    ?.let { child(it, "LogicalScreenDescriptor") }
                var width = screen?.let { attribute(it, "logicalScreenWidth") } ?: 0
                var height = screen?.let { attribute(it, "logicalScreenHeight") } ?: 0
                if (width <= 0 || height <= 0) {
                    width = reader.getWidth(0)
                    height = reader.getHeight(0)
                }

                val scale = minOf(1.0, longSide.toDouble() / max(width, height))
                val shownWidth = max(1, (width * scale).roundToInt())
                val shownHeight = max(1, (height * scale).roundToInt())

                var canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val frames = ArrayList<BufferedImage>()
                val delays = ArrayList<Long>()

                var index = 0
                while (index < MAX_FRAMES) {
                    val frame = try {
                        reader.read(index)
                    } catch (e: IndexOutOfBoundsException) {
                        break
                    }
                    val meta = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                    val descriptor = child(meta, "ImageDescriptor")
                    val left = descriptor?.let { attribute(it, "imageLeftPosition") } ?: 0
                    val top = descriptor?.let { attribute(it, "imageTopPosition") } ?: 0
                    val control = child(meta, "GraphicControlExtension")
                    val disposal = control?.getAttribute("disposalMethod") ?: "none"
                    val delay = (control?.let { attribute(it, "delayTime") } ?: 0) * 10L

                    val before = if (disposal == "restoreToPrevious") copyOf(canvas) else null

                    val g = canvas.createGraphics()
                    g.drawImage(frame, left, top, null)
                    g.dispose()

                    frames.add(scaled(canvas, shownWidth, shownHeight))
                    delays.add(if (delay < MIN_DELAY_MS) DEFAULT_DELAY_MS else delay)

                    when (disposal) {
                        "restoreToBackgroundColor" -> {
                            val clear = canvas.createGraphics()
                            clear.composite = AlphaComposite.Clear
                            clear.fillRect(left, top, frame.width, frame.height)
                            clear.dispose()
                        }
                        "restoreToPrevious" -> if (before != null) canvas = before
                    }
                    index++
                }
                if (frames.isEmpty()) null else Animation(frames, delays)
            }
        } catch (e: Exception) {
            debugLine("AnimatedPictures", "Cannot read the GIF ${file.name}: ${e.message}")
            null
        } finally {
            reader.dispose()
        }
    }

    /**
     * A sticker no larger than [longSide], its transparent parts kept, or null when this PC
     * cannot read it (a WebP sticker, for one). Blocking, as above.
     */
    fun stickerOf(file: File, longSide: Int): BufferedImage? {
        return try {
            val image = ImageIO.read(file) ?: return null
            val scale = minOf(1.0, longSide.toDouble() / max(image.width, image.height))
            scaled(image, max(1, (image.width * scale).roundToInt()), max(1, (image.height * scale).roundToInt()))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether [image] has see-through parts: what makes a pasted picture a sticker rather than a
     * photo (EntryPoint.hasTransparency on the phone).
     */
    fun hasTransparency(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) return false
        // A sample of the pixels is enough: a sticker's background is a large part of it.
        val stepX = max(1, image.width / 64)
        val stepY = max(1, image.height / 64)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if ((image.getRGB(x, y) ushr 24) < 0xFF) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun scaled(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return target
    }

    private fun copyOf(source: BufferedImage): BufferedImage {
        val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return copy
    }

    private fun child(parent: IIOMetadataNode, name: String): IIOMetadataNode? {
        var node: Node? = parent.firstChild
        while (node != null) {
            if (node.nodeName == name) return node as? IIOMetadataNode
            node = node.nextSibling
        }
        return null
    }

    private fun attribute(node: IIOMetadataNode, name: String): Int? = node.getAttribute(name).toIntOrNull()
}
