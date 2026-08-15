package com.bolimot.mindtheclub.functions

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bolimot.mindtheclub.BuildConfig

fun debugLine(function: String, message: String) {
    if (BuildConfig.ENABLE_DEBUG_TOOLS) {
        val msg = "$function;$message"
        Log.d("##", msg)
        logToFileIce(msg)
    }
}

fun debugLine2(function: String, message: String) {
    if (BuildConfig.ENABLE_DEBUG_TOOLS) {
        val msg = "$function;$message"
        Log.d("##", msg)
        logToFileIce(msg)
    }

}

fun debugLine3(function: String, message: String) {
    if (BuildConfig.ENABLE_DEBUG_TOOLS) {
        val msg = "$function;$message"
        Log.d("##", msg)
        logToFileIce(msg)
    }

}

fun debugLine4(function: String, message: String) {
    if (BuildConfig.ENABLE_DEBUG_TOOLS) {
        val msg = "$function;$message"
        Log.d("##", msg)
        logToFileIce(msg)
    }
}


private val logChannel = kotlinx.coroutines.channels.Channel<String>(capacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

/**
 * Fixed working file name. It used to be derived from MySelf.name(), captured
 * ONCE per process in this lazy block: a fresh install logged to
 * "unknown_user.txt" and a restore changed the name mid-process, so the export
 * (which re-reads the name) looked for a file that did not exist and the most
 * interesting minutes of any test were silently unreachable. The user's name is
 * still used for the exported copy, so attachments stay sorted per tester.
 */
private const val LOG_FILE_NAME = "mtc_debug_log.txt"

private val logWriter: Unit by lazy {
    App.instance!!.applicationScope.launch(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        val context = App.context()
        val deviceContext = context.createDeviceProtectedStorageContext()
        val logFile = File(deviceContext.filesDir, LOG_FILE_NAME)

        for (message in logChannel) {
            try {
                val timestamp = dateFormat.format(Date())
                logFile.appendText("$timestamp;$message\n")
            } catch (e: Exception) {
                Log.e("##", "logToFile Failed: ${e.message}")
            }
        }
    }
    Unit
}

fun logToFileIce(message: String) {
    logWriter
    logChannel.trySend(message)
}

/**
 * Deletes the working debug log, plus the legacy per-user files if any survive.
 * Counterpart of the resolution in exportLogToVisibleStorage: after the rename
 * to the fixed LOG_FILE_NAME, the two deletion sites (options menu, destructive
 * migration) kept looking only for the legacy "<name>.txt" and always reported
 * "No log file found" while mtc_debug_log.txt kept growing (Gio, 15 Aug: 165k
 * lines that could not be cleared from the phone). Returns true if anything was
 * actually deleted. The writer recreates the file on the next debugLine, so
 * logging continues seamlessly after a wipe.
 */
fun deleteDebugLog(): Boolean {
    return try {
        val deviceContext = App.context().createDeviceProtectedStorageContext()
        val candidates = listOfNotNull(
            File(deviceContext.filesDir, LOG_FILE_NAME),
            MySelf.name()?.trim()?.let { File(deviceContext.filesDir, "$it.txt") },
            File(deviceContext.filesDir, "unknown_user.txt")
        )
        var deleted = false
        for (file in candidates) {
            if (file.exists() && file.delete()) deleted = true
        }
        deleted
    } catch (e: Exception) {
        Log.e("##", "deleteDebugLog Failed: ${e.message}")
        false
    }
}

fun exportLogToVisibleStorage() {
    App.instance!!.applicationScope.launch(Dispatchers.IO) {
        try {
            val context = App.context()
            val deviceContext = context.createDeviceProtectedStorageContext()

            var sourceFile = File(deviceContext.filesDir, LOG_FILE_NAME)
            if (!sourceFile.exists()) {
                // Fall back to the legacy per-user name so logs already
                // accumulated by testers on older builds are not lost.
                val legacy = File(deviceContext.filesDir, "${MySelf.name()?.trim()}.txt")
                val legacyUnknown = File(deviceContext.filesDir, "unknown_user.txt")
                sourceFile = when {
                    legacy.exists() -> legacy
                    legacyUnknown.exists() -> legacyUnknown
                    else -> {
                        debugLine("EXPORT", "No log file found in DE storage.")
                        return@launch
                    }
                }
                debugLine("EXPORT", "Using legacy log file ${sourceFile.name}")
            }

            // The attachment keeps the user's name so the mailbox stays sorted.
            val filename = "${MySelf.name()?.trim() ?: "unknown_user"}.txt"
            val destFile = File(context.filesDir, "exported_$filename")
            sourceFile.copyTo(destFile, overwrite = true)

            val uris = ArrayList<Uri>()

            uris.add(FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                destFile
            ))

            // Attach soak test log if it exists
            val soakLogFile = File(context.filesDir, "soak_test_log.csv")
            if (soakLogFile.exists()) {
                uris.add(FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    soakLogFile
                ))
            }

            val emailIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("admin@mindtheclub.com"))
                putExtra(Intent.EXTRA_SUBJECT, "Debug log")
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(emailIntent, "Send debug log via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }

            debugLine("EXPORT", "Email intent launched with ${uris.size} attachment(s)")

        } catch (e: Exception) {
            debugLine("EXPORT", "Error exporting log: ${e.message}")
        }
    }
}