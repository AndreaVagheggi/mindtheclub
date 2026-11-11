package com.bolimot.mindtheclub.functions

// DESKTOP: this file is NOT a copy of the App's mediaStorage.kt, which is almost entirely
// Android's MediaStore (the phone's public Pictures, Movies and Download folders). The one
// function the receive path calls that has nothing to do with MediaStore is here, with the
// App's name, signature and logic. The differences:
//
//   - java.util.Base64 in place of android.util.Base64. The MIME decoder skips line breaks, as
//     Base64.DEFAULT does;
//   - the temp file goes to the engine's scratch folder (Context.filesDir); a PC has no
//     separate cache directory.
//
// Where a finished file is KEPT on the PC is desktop/media/MediaFiles.kt.

import android.content.Context
import com.bolimot.mindtheclub.database.inbox.InboxDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

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
        val tempFile = File(context.filesDir, fileName)
        tempFile.outputStream().use { output ->
            for (i in 1..totalChunks) {
                val chunk = inboxDao.getChunkContentByContent(contentKey, i)
                if (chunk != null) {
                    output.write(Base64.getMimeDecoder().decode(chunk))
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
