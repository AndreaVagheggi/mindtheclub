package com.bolimot.mindtheclub.functions

// DESKTOP: this file is NOT a copy of the App's utilities.kt, which is 600 lines of mostly
// Android helpers (network state, files, bitmaps, permissions). Only what the engine's copied
// files call is here, with the same names and signatures. Add a function when a copied file
// needs it, never a new behaviour.

import android.content.Context
import androidx.core.content.edit
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

fun setPreference(key: String, value: String, context: Context) {
    val deviceContext = context.createDeviceProtectedStorageContext()
    val sharedPreferences = deviceContext.getSharedPreferences("default", Context.MODE_PRIVATE)

    sharedPreferences.edit {
        putString(key, value)
    }
}

fun getPreference(key: String, context: Context): String? {
    val deviceContext = context.createDeviceProtectedStorageContext()
    val sharedPreferences = deviceContext.getSharedPreferences("default", Context.MODE_PRIVATE)

    return sharedPreferences.getString(key, null)
}

fun guid(): String {
    return UUID.randomUUID().toString().replace("-", "")
}

/** From the App's conversions.kt, the one function of that file the engine uses. */
fun stringToListInt(str: String): List<Int> {
    return str.split(",").map { it.trim().toInt() }
}

/**
 * The App asks Android whether a network is up, and defers a send until one is. A PC that is
 * running this program is taken to be online; a send that finds no network fails in the
 * dispatch and is retried by the same ladder that covers an unreachable peer.
 */
fun hasNetworkAvailable(context: Context): Boolean = true

// The App's four functions about files, unchanged. A file message carries its details in its
// TYPE: "file<name>,<extension>,<size>".

fun splitToList(csvString: String): List<String> {
    return csvString.split(",").map { it.trim() }
}

fun formatFileSize(sizeInBytes: Long): String {
    val oneKB = 1024
    val oneMB = oneKB * 1024
    val oneGB = oneMB * 1024

    return when {
        sizeInBytes >= oneGB -> "${sizeInBytes / oneGB} GB"
        sizeInBytes >= oneMB -> "${sizeInBytes / oneMB} MB"
        sizeInBytes >= oneKB -> "${sizeInBytes / oneKB} KB"
        else -> "$sizeInBytes Bytes"
    }
}

fun isFileType(type: String?): Boolean {
    return type?.take(4) == Type.FILE
}

fun getFileDetailFromType(type: String?): List<String>{
    // [0] = file name, [1] = extension, [2] = size
    // Read from the right: the name is free text and may itself contain commas
    // ("Biglietto, Roma.pdf"), extension and size never do. Same wire format as before.
    if(type == null) return emptyList()
    val csv = type.substring(4)
    val sizeCut = csv.lastIndexOf(',')
    val extensionCut = if (sizeCut > 0) csv.lastIndexOf(',', sizeCut - 1) else -1
    if (extensionCut < 0) return splitToList(csv)
    return listOf(
        csv.substring(0, extensionCut).trim(),
        csv.substring(extensionCut + 1, sizeCut).trim(),
        csv.substring(sizeCut + 1).trim()
    )
}

/**
 * DESKTOP: the kinds of content this PC processes. Every other kind stays in the Inbox,
 * unconfirmed, so its sender keeps its copy (see receiving/allReceivedEvent.kt).
 */
fun isOnThePc(type: String?): Boolean {
    return type == Type.TEXT || type == Type.IMAGE || type == Type.VIDEO || type == Type.CONTACT || isFileType(type)
}

/** The App's function, unchanged: the name a media file is kept under is the hash of its bytes. */
suspend fun computeSha256(inputStream: java.io.InputStream): String? = withContext(Dispatchers.IO) {
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        debugLine("computeSha256", "Failed: ${e.message}")
        null
    } finally {
        inputStream.close()
    }
}
