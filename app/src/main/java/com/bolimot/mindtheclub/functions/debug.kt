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
    val msg = "$function;$message"
    Log.d("##", msg)
    if (BuildConfig.ENABLE_DEBUG_TOOLS) logToFileIce(msg)
}

fun debugLine2(function: String, message: String) {
    val msg = "$function;$message"
    Log.d("##", msg)
    if (BuildConfig.ENABLE_DEBUG_TOOLS) logToFileIce(msg)

}

fun debugLine3(function: String, message: String) {
    val msg = "$function;$message"
    Log.d("##", msg)
    if (BuildConfig.ENABLE_DEBUG_TOOLS) logToFileIce(msg)

}

fun debugLine4(function: String, message: String) {
    val msg = "$function;$message"
    Log.d("##", msg)
    if (BuildConfig.ENABLE_DEBUG_TOOLS) logToFileIce(msg)
}


private val logChannel = kotlinx.coroutines.channels.Channel<String>(capacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

private val logWriter: Unit by lazy {
    App.instance!!.applicationScope.launch(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        val context = App.context()
        val deviceContext = context.createDeviceProtectedStorageContext()
        val safeName = MySelf.name()?.trim() ?: "unknown_user"
        val logFile = File(deviceContext.filesDir, "$safeName.txt")

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

fun exportLogToVisibleStorage() {
    App.instance!!.applicationScope.launch(Dispatchers.IO) {
        try {
            val context = App.context()
            val filename = "${MySelf.name()?.trim()}.txt"

            val deviceContext = context.createDeviceProtectedStorageContext()
            val sourceFile = File(deviceContext.filesDir, filename)

            if (!sourceFile.exists()) {
                debugLine("EXPORT", "Source file not found in DE storage.")
                return@launch
            }

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