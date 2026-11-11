package com.bolimot.mindtheclub.functions

// DESKTOP: this file is NOT a copy of the App's debug.kt. Same four functions with the same
// signatures, so every copied file logs exactly what it logs on the phone; the Android parts
// (Logcat, the export through a FileProvider) are gone.
//
// The lines go to mtc_debug_log.txt in the data folder, the same name and the same
// "dd/MM HH:mm:ss;function;message" shape as the phone's file, so the two can be read side by
// side when a transfer is diagnosed from both ends.
//
// On the phone this is compiled out of production builds (BuildConfig.ENABLE_DEBUG_TOOLS).
// Here it is ON while the PC version is being built, and is switched off by starting the
// program with -Dmtc.debugLog=false. It must be off by default before any release: the log
// records message texts, as the phone's debug log does.

import com.bolimot.mindtheclub.desktop.storage.DataDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOG_FILE_NAME = "mtc_debug_log.txt"

private val enabled: Boolean = System.getProperty("mtc.debugLog", "true").toBoolean()

private val logLock = Any()
private val dateFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
private val logFile: File by lazy { File(DataDir.root(), LOG_FILE_NAME) }

private fun write(function: String, message: String) {
    if (!enabled) return
    try {
        synchronized(logLock) {
            logFile.appendText("${dateFormat.format(Date())};$function;$message\n", Charsets.UTF_8)
        }
    } catch (e: Exception) {
        // A log line that cannot be written must never stop the engine.
    }
}

fun debugLine(function: String, message: String) = write(function, message)

fun debugLine2(function: String, message: String) = write(function, message)

fun debugLine3(function: String, message: String) = write(function, message)

fun debugLine4(function: String, message: String) = write(function, message)

/** Path of the log file, for the checks that tell the user where to look. */
fun debugLogPath(): String = logFile.absolutePath

fun deleteDebugLog(): Boolean = synchronized(logLock) { !logFile.exists() || logFile.delete() }
