package com.bolimot.mindtheclub.functions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.start.App
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * JPEG quality for photos leaving this device.
 *
 * Was 100, i.e. visually lossless and several times heavier than it needs to be:
 * at 2048px a quality 100 frame is 2 to 4 MB, the same frame at 80 is under one,
 * with a difference nobody sees on a phone. On a mobile uplink that ratio is the
 * difference between a photo arriving in a minute and in ten, and unlike the
 * transport level ideas it changes nothing on the wire: same chunks, same
 * protocol, same database. A receiver on an older build simply gets a lighter
 * file.
 */
const val SENT_IMAGE_QUALITY = 80

/**
 * What [uriList] will actually weigh on the wire, i.e. AFTER the same
 * recompression [mergeImages] applies.
 *
 * The group size cap used to be checked against the files as the gallery holds
 * them. Those are full resolution camera originals, 4 to 5 MB each on a current
 * phone, while what leaves the device is 2048px at quality 80, well under 1 MB.
 * So eleven perfectly ordinary photos, about 6 MB once packed, were refused for
 * exceeding a 50 MB limit they were nowhere near. The video path had already been
 * moved to measuring after transcoding for exactly this reason; the image path
 * never was.
 *
 * Deliberately not an estimate: it runs the real encoder over the real files, so
 * the number cannot drift from what mergeImages produces later. It also mirrors
 * that function's fallback, counting the original bytes whenever compression is
 * unavailable, because those are the bytes that would then be sent.
 *
 * Costs one decode plus one encode per image, on Dispatchers.IO. Call it off the
 * main thread and only where the answer matters, i.e. on the group path.
 */
suspend fun compressedSizeOfImages(uriList: List<Uri>): Long = withContext(Dispatchers.IO) {
    val context = App.context()
    val contentResolver = context.contentResolver
    var total = 0L

    fun originalSizeOf(uri: Uri): Long =
        try {
            getFileDetails(contentResolver, uri).size
        } catch (e: Exception) {
            debugLine("compressedSizeOfImages", "Cannot size $uri: ${e.message}")
            0L
        }

    for ((index, uri) in uriList.withIndex()) {
        val tempName = "sizeprobe_${System.currentTimeMillis()}_$index.jpg"
        val tempFile = File(context.filesDir, tempName)
        try {
            val compressed = saveBitmapFromUri(uri, tempName, SENT_IMAGE_QUALITY)
            total += if (compressed != null && tempFile.exists() && tempFile.length() > 0) {
                tempFile.length()
            } else {
                originalSizeOf(uri)
            }
        } catch (e: Exception) {
            debugLine("compressedSizeOfImages", "Probe failed for $uri: ${e.message}")
            total += originalSizeOf(uri)
        } finally {
            try { tempFile.delete() } catch (_: Exception) {}
        }
    }

    total
}

fun mergeImages(uriStringList: String, messageId: String): String? {
    try {
        val separator = "--SEPARATOR--"
        val separatorBytes = separator.toByteArray()
        val context = App.context()
        val uriList = uriStringList.split(",").map { it.trim().toUri() }

        val mergedFileName = "merge${messageId}.dat"
        val mergedFile = File(context.cacheDir, mergedFileName)

        if (mergedFile.exists()) {
            val existingUri = Uri.fromFile(mergedFile).toString()
            debugLine("mergeImages", "Merged file already exists. Returning existing URI: $existingUri")
            return existingUri
        }

        FileOutputStream(mergedFile).use { fileOutputStream ->
            for ((index, uri) in uriList.withIndex()) {
                // Recompress before concatenating. This path used to copy the
                // gallery originals byte for byte, so a handful of full
                // resolution photos became tens of megabytes: on 13 Aug an album
                // reached 870 chunks, roughly 35 MB, and took half an hour to
                // reach two peers over a mobile uplink. The single image path
                // already went through saveBitmapFromUri, this one never did.
                //
                // The separator framing is untouched, so extractImages and every
                // already deployed receiver keep working exactly as before.
                val tempName = "mergesrc_${messageId}_$index.jpg"
                val tempFile = File(context.filesDir, tempName)
                val compressed = saveBitmapFromUri(uri, tempName, SENT_IMAGE_QUALITY)

                if (compressed != null && tempFile.exists() && tempFile.length() > 0) {
                    tempFile.inputStream().use { it.copyTo(fileOutputStream) }
                    tempFile.delete()
                } else {
                    // Anything unexpected (undecodable file, out of memory, an
                    // exotic format) falls back to the original bytes, i.e. to
                    // exactly the behaviour this function had before.
                    debugLine("mergeImages", "Compression unavailable for $uri, sending original bytes")
                    tempFile.delete()
                    context.contentResolver.openInputStream(uri).use { inputStream ->
                        if (inputStream == null) {
                            debugLine("mergeImages", "Failed to open input stream for URI: $uri")
                            return null
                        }
                        inputStream.copyTo(fileOutputStream)
                    }
                }

                fileOutputStream.write(separatorBytes)
            }
        }

        val uriMergedFile = Uri.fromFile(mergedFile).toString()

        if (uriMergedFile.isEmpty()) {
            debugLine("mergeImages", "Uri is empty after merging.")
            return null
        }

        debugLine("mergeImages", "Successfully merged images. Merged URI: $uriMergedFile")
        return uriMergedFile

    } catch (e: Exception) {
        debugLine("mergeImages", "Error merging images: ${e.message}")
        return null
    }
}

fun extractImages(mergedUri: Uri): List<Uri>? {
    val uriList = mutableListOf<Uri>()
    val separator = "--SEPARATOR--"
    val context = App.context()

    try {
        if (mergedUri.path == null) {
            debugLine("extractImages", "Uri path is null")
            return null
        }

        val inputStream = when (mergedUri.scheme) {
            "content" -> context.contentResolver.openInputStream(mergedUri)
            "file" -> File(mergedUri.path!!).inputStream()
            else -> null
        }

        if (inputStream == null) {
            debugLine("extractImages", "InputStream could not be opened for URI: $mergedUri")
            return null
        }

        val separatorBytes = separator.toByteArray()
        val buffer = ByteArray(8192)
        val currentSegment = ByteArrayOutputStream()

        var bytesRead: Int
        var matchIndex = 0

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            for (i in 0 until bytesRead) {
                if (buffer[i] == separatorBytes[matchIndex]) {
                    matchIndex++
                    if (matchIndex == separatorBytes.size) {
                        saveSegmentAsImage(currentSegment.toByteArray(), uriList)
                        debugLine("extractImages", "uriList size: ${uriList.size}")
                        currentSegment.reset()
                        matchIndex = 0
                    }
                } else {
                    if (matchIndex > 0) {
                        currentSegment.write(separatorBytes, 0, matchIndex)
                        matchIndex = 0
                    }
                    currentSegment.write(buffer[i].toInt())
                }
            }
        }

        if (currentSegment.size() > 0) {
            saveSegmentAsImage(currentSegment.toByteArray(), uriList)
            debugLine("extractImages", "uriList size: ${uriList.size}")
        }

        inputStream.close()

        if (uriList.isEmpty()) {
            debugLine("extractImages", "Uri list is empty")
            return null
        }

        debugLine("extractImages", "RETURNING: ${uriList.size} IMAGES")

        return uriList

    } catch (e: Exception) {
        debugLine("extractImages", "Error extracting images: ${e.message}")
        return null
    }
}

private fun saveSegmentAsImage(bytes: ByteArray, uriList: MutableList<Uri>) {
    try {
        val context = App.context()
        val orientation = try {
            val exif = androidx.exifinterface.media.ExifInterface(bytes.inputStream())
            exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        }

        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        bitmap = applyExifOrientation(bitmap, orientation)

        val fileName = "${guid()}.jpg"

        val tempFile = File(context.filesDir, fileName)
        FileOutputStream(tempFile).use { fileOutputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream)
        }
        bitmap.recycle()

        val publicUri = kotlinx.coroutines.runBlocking {
            saveMediaToPublicStorage(context, tempFile, fileName, "image/jpeg", isVideo = false)
        }

        if (publicUri != null) {
            tempFile.delete() // Remove internal copy
            uriList.add(publicUri)
            debugLine("saveSegmentAsImage", "Image saved to public storage: $publicUri")
        } else {
            val uri = Uri.fromFile(tempFile)
            uriList.add(uri)
            debugLine("saveSegmentAsImage", "Fallback - image saved to filesDir: $uri")
        }
    } catch (e: Exception) {
        debugLine("saveSegmentAsImage", "Error saving image: ${e.message}")
    }
}

fun saveBitmap(bitmap: Bitmap?, fileName: String, compression: Int, external: Boolean = true): Uri? {
    bitmap?.let{
        val directory = App.context().filesDir
        val file = File(directory, fileName)

        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, compression, out)
                out.flush()
            }

            if(external) {
                val authority = "${App.context().packageName}.provider"
                FileProvider.getUriForFile(App.context(), authority, file)
            }
            else {
                Uri.fromFile(file)
            }

        } catch (e: IOException) {
            debugLine("saveBitmap", "Error saving image: ${e.message}")
            null
        }
    }
    return null
}

suspend fun saveClubBitmap(qrBitmap: Bitmap?, picturePath: String?, name: String?, description: String?): Uri? {
    return withContext(Dispatchers.IO) {
        if (qrBitmap == null) return@withContext null

        val metrics = App.context().resources.displayMetrics

        val canvasWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 330f, metrics).toInt()
        val fixedHeaderHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 180f, metrics).toInt()

        val scaleFactor = canvasWidth.toFloat() / 1500f

        val padding = (10 * scaleFactor).toInt().coerceAtLeast(2)
        val qrSize = (550 * scaleFactor).toInt()

        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 60f * scaleFactor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val descPaint = TextPaint().apply {
            isAntiAlias = true
            color = Color.DKGRAY
            textSize = 50f * scaleFactor
            typeface = Typeface.DEFAULT
        }

        val bgPaint = Paint().apply {
            color = Color.WHITE
        }

        try {
            val rawProfileBitmap = if (!picturePath.isNullOrEmpty()) {
                try {
                    if (picturePath.startsWith("http", ignoreCase = true)) {
                        java.net.URL(picturePath).openStream().use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } else {
                        val uri = picturePath.toUri()
                        App.context().contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            } else null

            val profileHeight = if (rawProfileBitmap != null) fixedHeaderHeight else 0

            val textStartX = qrSize + (padding * 2)
            val textWidth = canvasWidth - textStartX - padding

            val nameLayout = StaticLayout.Builder.obtain(name ?: "", 0, (name ?: "").length, titlePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()

            val descLayout = StaticLayout.Builder.obtain(description ?: "", 0, (description ?: "").length, descPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()

            val textSectionHeight = nameLayout.height + padding + descLayout.height
            val bottomSectionHeight = max(qrSize, textSectionHeight) + (padding * 2)
            val totalHeight = profileHeight + bottomSectionHeight

            val resultBitmap = createBitmap(canvasWidth, totalHeight)
            val canvas = Canvas(resultBitmap)

            canvas.drawRect(0f, 0f, canvasWidth.toFloat(), totalHeight.toFloat(), bgPaint)

            if (rawProfileBitmap != null) {
                val destRect = Rect(0, 0, canvasWidth, profileHeight)
                canvas.drawBitmap(rawProfileBitmap, null, destRect, null)
            }

            val scaledQr = qrBitmap.scale(qrSize, qrSize)
            canvas.drawBitmap(scaledQr, padding.toFloat(), (profileHeight + padding).toFloat(), null)

            canvas.withTranslation(textStartX.toFloat(), (profileHeight + padding).toFloat()) {
                nameLayout.draw(this)
                translate(0f, (nameLayout.height + padding / 2).toFloat())
                descLayout.draw(this)
            }

            val fileName = "${System.currentTimeMillis()}_club_share.jpg"
            val directory = App.context().filesDir
            val file = File(directory, fileName)

            FileOutputStream(file).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }

            val authority = "${App.context().packageName}.provider"
            return@withContext FileProvider.getUriForFile(App.context(), authority, file)

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}


// Longest side of any bitmap saveBitmapFromUri holds in memory when no explicit
// resize is requested. Keeps a full-resolution camera photo (50+ MP on modern
// phones) from ever being decoded whole, which freezes/kills low-RAM devices.
private const val MAX_SAVED_IMAGE_DIMENSION = 2048

fun saveBitmapFromUri(uri: Uri?, fileName: String, compression: Int, resize: Int = 0): Uri? {
    uri ?: return null
    val context = App.context()
    val directory = context.filesDir
    val file = File(directory, fileName)
    val targetSize = if (resize != 0) resize else MAX_SAVED_IMAGE_DIMENSION

    return try {
        var bitmap: Bitmap

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDim = max(info.size.width, info.size.height)
                if (maxDim > targetSize) {
                    val scale = targetSize.toFloat() / maxDim
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            // API 26-27: manual EXIF handling and subsampled decode
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

            // NOTE: decodeStream() intentionally returns null when inJustDecodeBounds
            // is set — it only fills [bounds]. The stream-null check must therefore
            // be separate; an elvis on the whole expression would always bail out.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(uri)
            if (boundsStream == null) {
                debugLine("saveBitmapFromUri", "openInputStream returned null (bounds pass) for $uri")
                return null
            }
            boundsStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }

            debugLine(
                "saveBitmapFromUri",
                "bounds: ${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType} uri=$uri"
            )
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                debugLine("saveBitmapFromUri", "Image not decodable on this API level (bounds failed)")
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(max(bounds.outWidth, bounds.outHeight), targetSize)
            }
            bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: run {
                debugLine("saveBitmapFromUri", "decodeStream returned null (full pass), inSampleSize=${options.inSampleSize}")
                return null
            }

            bitmap = applyExifOrientation(bitmap, orientation)
        }

        // Exact-size pass: the decode above only guarantees an upper bound
        // (inSampleSize halves in powers of two). Never upscales.
        if (max(bitmap.width, bitmap.height) > targetSize) {
            bitmap = resizeBitmap(bitmap, targetSize)
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, compression, out)
            out.flush()
        }

        val authority = "${context.packageName}.provider"
        FileProvider.getUriForFile(context, authority, file)
    } catch (e: Exception) {
        // Broad on purpose: SecurityException / IllegalArgumentException etc. must
        // surface as a logged failure, never as a silent one (or a crash).
        debugLine("saveBitmapFromUri", "Error saving image: ${e.javaClass.simpleName}: ${e.message}")
        null
    } catch (e: OutOfMemoryError) {
        debugLine("saveBitmapFromUri", "Out of memory saving image: ${e.message}")
        null
    }
}

// Largest power-of-two sample size that still decodes at or above [target] on the
// longest side, so the exact-size pass afterwards only ever scales down.
private fun calculateInSampleSize(largestDimension: Int, target: Int): Int {
    var inSampleSize = 1
    while (largestDimension / (inSampleSize * 2) >= target) {
        inSampleSize *= 2
    }
    return inSampleSize
}

fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f); matrix.preScale(-1f, 1f)
        }
        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f); matrix.preScale(-1f, 1f)
        }
        else -> return bitmap // ORIENTATION_NORMAL or ORIENTATION_UNDEFINED
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

fun loadBitmap(uri: Uri, context: Context): Bitmap? {
    val uriString = uri.toString()
    if (uriString.isBlank() || uriString == "null") {
        debugLine("readBitmap", "Skipping invalid URI: '$uriString'")
        return null
    }
    // For file:// URIs, check the file exists before attempting decode
    if (uri.scheme == "file") {
        val file = uri.path?.let { File(it) }
        if (file == null || !file.exists()) {
            debugLine("readBitmap", "File does not exist: ${uri.path}")
            return null
        }
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } catch (e: IOException) {
            debugLine("readBitmap", "Exception ${e.message}")
            null
        }
    } else {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                return BitmapFactory.decodeStream(inputStream)
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            debugLine("readBitmap", "Exception (old version) ${e.message}")
            null
        }
    }
}

fun makeItRound(bitmap: Bitmap?): Bitmap? {
    if (bitmap == null) {
        debugLine("makeItRound", "Bitmap is null")
        return null
    }

    try {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val size = min(mutableBitmap.width, mutableBitmap.height)
        val output = createBitmap(size, size)

        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
        }

        val shader = BitmapShader(mutableBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix()

        val scale = size.toFloat() / min(mutableBitmap.width, mutableBitmap.height)
        val dx = (size - mutableBitmap.width * scale) * 0.5f
        val dy = (size - mutableBitmap.height * scale) * 0.5f

        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        shader.setLocalMatrix(matrix)

        paint.shader = shader

        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        return output
    } catch (e: Exception) {
        debugLine("makeItRound", "Exception: ${e.message}")
        return null
    }
}

fun calculateVideoTargetDimensions(context: Context, videoUri: Uri, targetSizeDp: Int): Pair<Int, Int>? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)

        val originalWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
        val originalHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
        retriever.release()

        if (originalWidth == null || originalHeight == null) {
            return null
        }

        val targetSizePx = convertDpToPx(context, targetSizeDp)

        val aspectRatio: Float = originalWidth.toFloat() / originalHeight.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (originalHeight > originalWidth) {
            targetHeight = targetSizePx
            targetWidth = (targetHeight * aspectRatio).toInt()
            if (targetWidth < targetSizePx) {
                return Pair(targetSizePx, (targetSizePx / aspectRatio).toInt())
            }
        } else {
            targetWidth = targetSizePx
            targetHeight = (targetWidth / aspectRatio).toInt()
        }

        Pair(targetWidth, targetHeight)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun calculateTargetDimensions(previewUri: Uri, targetSizeDp: Int): Pair<Int, Int>? {
    return try {
        val context = App.context()
        val targetSizePx = convertDpToPx(context, targetSizeDp)

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(previewUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        val aspectRatio: Float = originalWidth.toFloat() / originalHeight.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (originalHeight > originalWidth) {
            targetHeight = targetSizePx
            targetWidth = (targetHeight * aspectRatio).toInt()
            if (targetWidth < targetSizePx) {
                return Pair(targetSizePx, (targetSizePx / aspectRatio).toInt())
            }
        } else {
            targetWidth = targetSizePx
            targetHeight = (targetWidth / aspectRatio).toInt()
        }

        Pair(targetWidth, targetHeight)
    } catch (e: Exception) {
        debugLine("calculateTargetDimensions", "Error calculating target dimensions: ${e.message}")
        null
    }
}

suspend fun buildMultiImagePreview(uriString: String): Uri? {
    return withContext(Dispatchers.IO) {
        val thumbnailSize = 512
        val gridColumns = 2
        val gridRows = 2
        val finalWidth = thumbnailSize * gridColumns
        val finalHeight = thumbnailSize * gridRows
        val emptyCellColor = Color.LTGRAY
        val context = App.context()

        try {
            val sourceUris = uriString.split(',').map { it.trim().toUri() }
            val sourceBitmaps = sourceUris.mapNotNull { uri ->
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)?.scale(thumbnailSize, thumbnailSize)
                }
            }

            if (sourceBitmaps.isEmpty()) {
                return@withContext null
            }

            val finalBitmap = createBitmap(finalWidth, finalHeight)
            val canvas = Canvas(finalBitmap)
            canvas.drawColor(emptyCellColor)

            sourceBitmaps.forEachIndexed { index, bitmap ->
                if (index >= gridColumns * gridRows) return@forEachIndexed

                val row = index / gridColumns
                val col = index % gridColumns
                val left = (col * thumbnailSize).toFloat()
                val top = (row * thumbnailSize).toFloat()

                canvas.drawBitmap(bitmap, left, top, null)
                bitmap.recycle()
            }

            val file = File(context.filesDir, "multi_${guid()}.jpg")
            FileOutputStream(file).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
            }
            finalBitmap.recycle()

            return@withContext file.toUri()

        } catch (e: Exception) {
            debugLine("buildMultiImagePreview", "Error building multi image preview: ${e.message}")
            return@withContext null
        }
    }
}

fun resizeBitmap(bitmap: Bitmap, targetSize: Int = 200): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val aspectRatio = width.toFloat() / height.toFloat()

    val finalWidth: Int
    val finalHeight: Int

    if (width > height) {
        finalWidth = targetSize
        finalHeight = (targetSize / aspectRatio).toInt()
    } else {
        finalHeight = targetSize
        finalWidth = (targetSize * aspectRatio).toInt()
    }

    return bitmap.scale(finalWidth, finalHeight)
}

fun vectorToBitmap(context: Context, drawableId: Int): Bitmap {
    val drawable = ContextCompat.getDrawable(context, drawableId)!!
    val bmp = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)

    return bmp
}

suspend fun saveFirebaseImageToDisk(imageUrl: Uri?, fileName: String, compression: Int): Uri? {
    val context = App.context()
    val directory = context.filesDir
    val file = File(directory, fileName)

    return withContext(Dispatchers.IO) {
        try {
            val bitmap: Bitmap = if (imageUrl != null) {
                debugLine("saveFirebaseImageToDisk", "Downloading image from: $imageUrl")
                Glide.with(context)
                    .asBitmap()
                    .load(imageUrl)
                    .submit(1080, 1080)
                    .get()
            } else {
                debugLine("saveFirebaseImageToDisk", "imageUrl is null")

                val drawable = ContextCompat.getDrawable(context, R.drawable.peer)
                    ?: throw IllegalArgumentException("R.drawable.peer not found")

                // Create a blank bitmap. Using 1080x1080 to match your Glide load.
                val bmp = createBitmap(1080, 1080)

                // Create a canvas to draw on
                val canvas = Canvas(bmp)

                // Set the bounds for the drawable to fill the entire bitmap
                drawable.setBounds(0, 0, canvas.width, canvas.height)

                // Draw the vector onto the bitmap's canvas
                drawable.draw(canvas)

                bmp // Return the newly created bitmap
            }

            // 2. Compress and save the bitmap to the local file
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, compression, out)
                out.flush()
            }

            debugLine("saveFirebaseImageToDisk", "Image saved to: ${file.absolutePath}")

            // 3. Return the content Uri for the new file
            val authority = "${context.packageName}.provider"
            FileProvider.getUriForFile(context, authority, file)

        } catch (e: Exception) {
            debugLine("saveFirebaseImageToDisk", "Error saving image: ${e.message}")
            null
        }
    }
}
