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
            for (uri in uriList) {
                context.contentResolver.openInputStream(uri).use { inputStream ->
                    if (inputStream == null) {
                        debugLine("mergeImages", "Failed to open input stream for URI: $uri")
                        return null
                    }

                    inputStream.copyTo(fileOutputStream)
                    fileOutputStream.write(separatorBytes)
                }
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


fun saveBitmapFromUri(uri: Uri?, fileName: String, compression: Int, resize: Int = 0): Uri? {
    uri ?: return null
    val context = App.context()
    val directory = context.filesDir
    val file = File(directory, fileName)

    return try {
        var bitmap: Bitmap

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // API 26-27: manual EXIF handling
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

            bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null

            bitmap = applyExifOrientation(bitmap, orientation)
        }

        if (resize != 0) {
            bitmap = resizeBitmap(bitmap, resize)
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, compression, out)
            out.flush()
        }

        val authority = "${context.packageName}.provider"
        FileProvider.getUriForFile(context, authority, file)
    } catch (e: IOException) {
        debugLine("saveBitmapFromUri", "Error saving image: ${e.message}")
        null
    }
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
