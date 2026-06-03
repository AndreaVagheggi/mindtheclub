package com.bolimot.mindtheclub.functions

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.bolimot.mindtheclub.database.inbox.InboxDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun deleteExistingMediaFile(
    context: Context,
    fileName: String,
    relativePath: String,
    isVideo: Boolean
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

    val collection = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(fileName, if (relativePath.endsWith("/")) relativePath else "$relativePath/")

    val resolver = context.contentResolver
    resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val uri = ContentUris.withAppendedId(collection, id)
            return resolver.delete(uri, null, null) > 0
        }
    }
    return false
}

suspend fun saveFileToPublicDownloads(
    context: Context,
    sourceFile: File,
    fileName: String,
    mimeType: String
): Uri? = withContext(Dispatchers.IO) {
    try {
        if (!sourceFile.exists()) {
            debugLine("saveFileToPublicDownloads", "Source file does not exist: ${sourceFile.path}")
            return@withContext null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/MindTheClub"

            // Delete existing file with same name in Downloads/MindTheClub
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, relativePath)

            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val existingUri = ContentUris.withAppendedId(collection, id)
                    resolver.delete(existingUri, null, null)
                }
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, contentValues)
            if (uri == null) {
                debugLine("saveFileToPublicDownloads", "MediaStore insert returned null")
                return@withContext null
            }

            val success = resolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { it.copyTo(outputStream) }
                true
            } ?: false

            if (!success) {
                resolver.delete(uri, null, null)
                debugLine("saveFileToPublicDownloads", "Failed to write to output stream")
                return@withContext null
            }

            val updateValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, updateValues, null, null)

            debugLine("saveFileToPublicDownloads", "Saved to Downloads: $uri")
            uri
        } else {
            // Legacy storage – FileOutputStream overwrites automatically
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MindTheClub"
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val destFile = File(downloadsDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)

            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)

            debugLine("saveFileToPublicDownloads", "Saved to: ${destFile.absolutePath}")
            Uri.fromFile(destFile)
        }
    } catch (e: Exception) {
        debugLine("saveFileToPublicDownloads", "Failed: ${e.message}")
        null
    }
}

suspend fun saveMediaToPublicStorage(
    context: Context,
    sourceFile: File,
    fileName: String,
    mimeType: String,
    isVideo: Boolean
): Uri? = withContext(Dispatchers.IO) {
    try {
        if (!sourceFile.exists()) {
            debugLine("saveMediaToPublicStorage", "Source file does not exist: ${sourceFile.path}")
            return@withContext null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, sourceFile, fileName, mimeType, isVideo)
        } else {
            saveViaPublicDirectory(context, sourceFile, fileName, mimeType, isVideo)
        }
    } catch (e: Exception) {
        debugLine("saveMediaToPublicStorage", "Failed: ${e.message}")
        null
    }
}

private fun saveViaMediaStore(
    context: Context,
    sourceFile: File,
    fileName: String,
    mimeType: String,
    isVideo: Boolean
): Uri? {
    val collection = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val relativePath = if (isVideo) {
        "${Environment.DIRECTORY_MOVIES}/MindTheClub"
    } else {
        "${Environment.DIRECTORY_PICTURES}/MindTheClub"
    }

    // Delete any existing file with the same name in the same directory
    deleteExistingMediaFile(context, fileName, relativePath, isVideo)

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(collection, contentValues)
    if (uri == null) {
        debugLine("saveMediaToPublicStorage", "MediaStore insert returned null")
        return null
    }

    val success = resolver.openOutputStream(uri)?.use { outputStream ->
        sourceFile.inputStream().use { inputStream ->
            inputStream.copyTo(outputStream)
        }
        true
    } ?: false

    if (!success) {
        resolver.delete(uri, null, null)
        debugLine("saveMediaToPublicStorage", "Failed to write to MediaStore output stream")
        return null
    }

    val updateValues = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
    resolver.update(uri, updateValues, null, null)

    debugLine("saveMediaToPublicStorage", "Saved to public storage: $uri")
    return uri
}

private fun saveViaPublicDirectory(
    context: Context,
    sourceFile: File,
    fileName: String,
    mimeType: String,
    isVideo: Boolean
): Uri? {
    val baseDir = if (isVideo) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
    } else {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    }
    val appDir = File(baseDir, "MindTheClub")
    if (!appDir.exists()) appDir.mkdirs()

    val destFile = File(appDir, fileName)
    sourceFile.copyTo(destFile, overwrite = true)

    MediaScannerConnection.scanFile(
        context,
        arrayOf(destFile.absolutePath),
        arrayOf(mimeType),
        null
    )

    debugLine("saveMediaToPublicStorage", "Saved to: ${destFile.absolutePath}")
    return Uri.fromFile(destFile)
}

suspend fun getExistingUriByHash(
    context: Context,
    hashFileName: String,
    relativePath: String,
    isVideo: Boolean
): Uri? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val baseDir = if (isVideo) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        val file = File(baseDir, "$relativePath/$hashFileName")
        return@withContext if (file.exists()) Uri.fromFile(file) else null
    }

    val collection = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"

    val selectionArgs = arrayOf(hashFileName, if (relativePath.endsWith("/")) relativePath else "$relativePath/")

    context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            return@withContext ContentUris.withAppendedId(collection, id)
        }
    }
    null
}

suspend fun getExistingDownloadUriByHash(
    context: Context,
    hashFileName: String,
    relativePath: String
): Uri? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$relativePath/$hashFileName")
        return@withContext if (file.exists()) Uri.fromFile(file) else null
    }

    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(hashFileName, if (relativePath.endsWith("/")) relativePath else "$relativePath/")

    context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            return@withContext ContentUris.withAppendedId(collection, id)
        }
    }
    null
}

suspend fun assembleChunksToTempFile(
    context: Context,
    inboxDao: InboxDao,
    messageId: String,
    fileName: String
): File? = withContext(Dispatchers.IO) {
    try {
        val contentKey = resolveContentKey(inboxDao, messageId)
        val totalChunks = inboxDao.getTotalChunksByContent(contentKey)
        if (totalChunks == 0) {
            debugLine("assembleTemp", "No chunks found for $messageId")
            return@withContext null
        }
        val tempFile = File(context.cacheDir, fileName)
        tempFile.outputStream().use { output ->
            for (i in 1..totalChunks) {
                val chunk = inboxDao.getChunkContentByContent(contentKey, i)
                if (chunk != null) {
                    output.write(Base64.decode(chunk, Base64.DEFAULT))
                } else {
                    debugLine("assembleTemp", "Chunk $i missing for $messageId")
                    tempFile.delete()
                    return@withContext null
                }
            }
        }
        tempFile
    } catch (e: Exception) {
        debugLine("assembleTemp", "Failed: ${e.message}")
        null
    }
}
