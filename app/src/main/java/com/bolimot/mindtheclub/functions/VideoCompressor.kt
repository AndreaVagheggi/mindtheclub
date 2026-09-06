package com.bolimot.mindtheclub.functions

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Re-encodes a video down to a size that can realistically cross a phone to phone link, before
 * it is sent.
 *
 * Photos have always been resized to 2048px and recompressed at quality 80 on their way out
 * (see saveBitmapFromUri), which is why a two photo album weighs about 1.5 MB. Videos went out
 * exactly as the camera wrote them, so a short clip is 35 MB, venticinque volte un album. On
 * 18 Aug one such video spent hours crossing a holiday network and never arrived; at 720p it
 * would have been roughly 8 MB.
 *
 * This is transcoding, not packing: the output is an ordinary MP4 that every receiver plays
 * without knowing anything happened, so no receiving code changes. Lossy and one way, like the
 * photo path.
 */
@UnstableApi
object VideoCompressor {

    private const val TAG = "VideoCompressor"

    /** Longest edge of the output. 720p is the sweet spot for phone screens. */
    private const val TARGET_HEIGHT = 720

    /**
     * Bitrate of the output, roughly 15 MB per minute of footage.
     *
     * Set EXPLICITLY, and this is the crux of the whole file: left to its defaults the encoder
     * keeps, or even raises, the source bitrate. That is the most reported surprise with this
     * library, and without it a 35 MB clip comes out at 35 MB and the feature does nothing.
     */
    private const val TARGET_BITRATE = 2_000_000

    /** Below this a transfer is already cheap; recompressing would only cost quality. */
    const val SKIP_BELOW_BYTES = 8L * 1024 * 1024

    /**
     * True when [uri] is worth transcoding: big enough to matter, and taller than the target. A
     * clip already at 720p or below is left alone whatever its size, perche' the saving would
     * come entirely out of its quality.
     */
    fun isWorthCompressing(context: Context, uri: Uri, sizeBytes: Long): Boolean {
        if (sizeBytes <= SKIP_BELOW_BYTES) {
            debugLine(TAG, "Skipping: ${sizeBytes / 1024 / 1024} MB is already small")
            return false
        }
        // NOT MediaMetadataRetriever.use: it only became AutoCloseable in API 29 and this app
        // supports 26, where that would blow up at runtime.
        val retriever = MediaMetadataRetriever()
        val longestEdge = try {
            retriever.setDataSource(context, uri)
            maxOf(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            debugLine(TAG, "Cannot read video dimensions (${e.message}), transcoding anyway")
            -1
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        if (longestEdge in 1..TARGET_HEIGHT) {
            debugLine(TAG, "Skipping: already ${longestEdge}p")
            return false
        }
        return true
    }

    /**
     * Transcodes [sourceUri] and returns the new file, or null on any failure.
     *
     * Null is not an error the caller has to explain to the user: it means "send the original",
     * which is exactly today's behaviour. Compression is an optimisation and must never be the
     * reason a message fails to go out.
     *
     * [onProgress] reports 0..100 on the main thread.
     */
    suspend fun compress(
        context: Context,
        sourceUri: Uri,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.Main) {
        val output = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        val sourceBytes = sizeOf(context, sourceUri)

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(TARGET_BITRATE).build()
            )
            .build()

        val videoEffects: List<Effect> = listOf(Presentation.createForHeight(TARGET_HEIGHT))
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val produced: File? = suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(output)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        debugLine(TAG, "Transcode failed (code ${exportException.errorCode}): ${exportException.message}")
                        output.delete()
                        if (cont.isActive) cont.resume(null)
                    }
                })
                .build()

            val progressHolder = ProgressHolder()
            val ticker = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    val state = try {
                        transformer.getProgress(progressHolder)
                    } catch (e: Exception) {
                        Transformer.PROGRESS_STATE_NOT_STARTED
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        onProgress(progressHolder.progress)
                        handler.postDelayed(this, 400L)
                    }
                }
            }

            cont.invokeOnCancellation {
                handler.removeCallbacks(ticker)
                try { transformer.cancel() } catch (_: Exception) {}
                output.delete()
            }

            try {
                transformer.start(editedItem, output.absolutePath)
                handler.post(ticker)
            } catch (e: Exception) {
                debugLine(TAG, "Transcode could not start: ${e.message}")
                output.delete()
                if (cont.isActive) cont.resume(null)
            }
        }

        if (produced == null || !produced.exists() || produced.length() == 0L) {
            produced?.delete()
            return@withContext null
        }

        val newBytes = produced.length()
        debugLine(
            TAG,
            "Transcoded ${sourceBytes / 1024} KB -> ${newBytes / 1024} KB (${percentOf(newBytes, sourceBytes)}% of original)"
        )

        // A transcode that produced a BIGGER file is a loss twice over, in size and in
        // quality. It happens with clips the encoder cannot improve on, and the honest answer
        // is to keep the original.
        if (sourceBytes in 1..newBytes) {
            debugLine(TAG, "Transcode produced a larger file, keeping the original")
            produced.delete()
            return@withContext null
        }
        produced
    }

    private fun percentOf(part: Long, whole: Long): Int =
        if (whole <= 0L) 0 else (part * 100 / whole).toInt()

    fun sizeOf(context: Context, uri: Uri): Long =
        try {
            getFileDetails(context.contentResolver, uri).size
        } catch (e: Exception) {
            debugLine(TAG, "Cannot size ${uri.scheme} uri: ${e.message}")
            0L
        }

    /** Best effort cleanup of outputs left behind by sends that were abandoned. */
    fun purgeStaleOutputs() {
        try {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            App.context().cacheDir.listFiles()
                ?.filter { it.name.startsWith("compressed_") && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
