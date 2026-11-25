package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.Type

/**
 * The picture a reply shows of the message it answers, made the way the App makes it, on the
 * side that sends the reply (sendManager.sendText) and on the side that receives it
 * (receiveManager.receiveText) alike:
 *
 *   - a picture, a GIF, a sticker or a shared contact: its own picture, as it is;
 *   - a video: its first frame, a JPEG at quality 50 (saveFirstVideoFrame);
 *   - an album: its first four pictures in a two by two grid, JPEG at quality 50
 *     (buildMultiImagePreview).
 */
object QuotedPictures {

    private const val FRAME_SIDE = 640
    private const val FRAME_QUALITY = 0.50f

    /**
     * The file: URI of that picture for the answered message of [replyType] whose uri is
     * [storedUri], or null when there is none or its files are not on this PC: a name that
     * points at no file must never travel.
     */
    suspend fun of(replyType: String?, storedUri: String?): String? {
        return try {
            when (replyType) {
                // sendText's shouldAttachPreview: a GIF and a sticker are quoted with themselves.
                Type.IMAGE, Type.CONTACT, Type.GIF, Type.STICKER -> MediaFiles.fileOfUri(storedUri)?.toURI()?.toString()
                Type.VIDEO -> {
                    val video = MediaFiles.fileOfUri(storedUri) ?: return null
                    val frame = VideoThumbnail.of(video, FRAME_SIDE) ?: return null
                    MediaFiles.keepJpeg(frame, FRAME_QUALITY).toURI().toString()
                }
                Type.MULTIPLE_IMAGES -> {
                    val grid = Albums.preview(Albums.filesOf(storedUri)) ?: return null
                    MediaFiles.keepJpeg(grid, Albums.PREVIEW_QUALITY).toURI().toString()
                }
                else -> null
            }
        } catch (e: Exception) {
            debugLine("QuotedPictures", "No picture for a reply to a $replyType: ${e.message}")
            null
        }
    }
}
