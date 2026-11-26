package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Share on the PC: the Windows Share panel, as the phone's Share opens Android's share sheet
 * (sharing/shareContent.kt). The panel is reached through a small program,
 * src/main/resources/desktop/share.cs, which this compiles the first time with the C# compiler
 * that is part of Windows (.NET Framework 4, csc.exe, on every Windows 10 and 11) and keeps in
 * the scratch folder; it is compiled again only when the program carries a different share.cs.
 * Nothing is downloaded.
 *
 * Checked on 26 Nov 2026 on the owner's Windows 10: the panel opens for a program that is not
 * installed as a package, which MindTheClub on the PC is not.
 */
object WindowsShare {

    private const val TAG = "WindowsShare"
    private const val SOURCE_RESOURCE = "/desktop/share.cs"

    /** The panel waits for the user; past this the helper is let go. */
    private const val TIMEOUT_MINUTES = 6L

    private val compileLock = Mutex()

    /** AllowSetForegroundWindow's "any process". */
    private const val ASFW_ANY = -1

    /**
     * The one call to Windows this needs that JNA's ready-made User32 lacks. Not private: JNA
     * builds a proxy of it, which must be able to see it.
     */
    internal interface User32Foreground : Library {
        fun AllowSetForegroundWindow(processId: Int): Boolean
    }

    private object ForegroundGrant {
        val user32: User32Foreground by lazy { Native.load("user32", User32Foreground::class.java) }
    }

    private fun dir(): File = File(App.context().filesDir, "share").apply { mkdirs() }

    /** Shares [text]: a text message, or a link. False when the panel could not be opened. */
    suspend fun shareText(text: String): Boolean = withContext(Dispatchers.IO) {
        val textFile = File(dir(), "text_${guid()}.txt")
        try {
            textFile.writeText(text, Charsets.UTF_8)
            run(listOf("text", textFile.absolutePath))
        } finally {
            textFile.delete()
        }
    }

    /** Shares [files]: a picture, a video, a voice message, a GIF, an album. */
    suspend fun shareFiles(files: List<File>): Boolean = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext false
        run(listOf("files") + files.map { it.absolutePath })
    }

    /** Runs the helper; the result file is put in second place for "files", third for "text". */
    private suspend fun run(arguments: List<String>): Boolean {
        val exe = helper() ?: return false
        val result = File(dir(), "result_${guid()}.txt")
        val command = ArrayList<String>()
        command.add(exe.absolutePath)
        if (arguments[0] == "text") {
            command.addAll(arguments)
            command.add(result.absolutePath)
        } else {
            command.add("files")
            command.add(result.absolutePath)
            command.addAll(arguments.drop(1))
        }
        // Windows opens the panel only for the window in front, and a program just started may
        // not put itself in front. MindTheClub is in front (Share was just clicked on it), and
        // a program in front may let another one take its place: AllowSetForegroundWindow, the
        // way Windows provides for this. Without it the helper's window stays behind and the
        // panel never opens (26 Nov 2026, measured: no DataRequested at all).
        try {
            ForegroundGrant.user32.AllowSetForegroundWindow(ASFW_ANY)
        } catch (e: Throwable) {
            debugLine(TAG, "AllowSetForegroundWindow failed: ${e.message}")
        }
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) process.destroyForcibly()
            val outcome = if (result.isFile) result.readText().trim() else ""
            // The helper's steps (share.cs, Trace): whether it came to the front, whether
            // Windows asked it what to share.
            val trace = File(result.absolutePath + ".trace")
            if (trace.isFile) {
                trace.readLines().forEach { debugLine(TAG, "helper: $it") }
                trace.delete()
            }
            debugLine(TAG, "Share: ${outcome.ifEmpty { "no answer" }}")
            !outcome.startsWith("FAILED") && outcome.isNotEmpty()
        } catch (e: Exception) {
            debugLine(TAG, "Share failed: ${e.message}")
            false
        } finally {
            result.delete()
        }
    }

    /** The compiled helper, compiled now if the program carries a share.cs it has not compiled yet. */
    private suspend fun helper(): File? = compileLock.withLock {
        val source = WindowsShare::class.java.getResourceAsStream(SOURCE_RESOURCE)?.use { it.readBytes() }
        if (source == null) {
            debugLine(TAG, "share.cs is not in the program")
            return@withLock null
        }
        val version = MessageDigest.getInstance("SHA-256").digest(source).take(8).joinToString("") { "%02x".format(it) }
        val exe = File(dir(), "share_$version.exe")
        if (exe.isFile) return@withLock exe

        val windows = System.getenv("WINDIR") ?: "C:\\Windows"
        val framework = File(windows, "Microsoft.NET\\Framework64\\v4.0.30319")
        val csc = File(framework, "csc.exe")
        val metadata = File(windows, "System32\\WinMetadata")
        if (!csc.isFile) {
            debugLine(TAG, "No C# compiler in this Windows: ${csc.absolutePath}")
            return@withLock null
        }

        val sourceFile = File(dir(), "share_$version.cs")
        sourceFile.writeBytes(source)
        val log = File(dir(), "compile_$version.txt")
        val command = listOf(
            csc.absolutePath, "/nologo", "/target:winexe", "/platform:x64", "/out:${exe.absolutePath}",
            "/r:${File(framework, "System.Runtime.dll")}",
            "/r:${File(framework, "System.Runtime.InteropServices.WindowsRuntime.dll")}",
            "/r:${File(framework, "System.Windows.Forms.dll")}",
            "/r:${File(framework, "System.Drawing.dll")}",
            "/r:${File(metadata, "Windows.ApplicationModel.winmd")}",
            "/r:${File(metadata, "Windows.Storage.winmd")}",
            "/r:${File(metadata, "Windows.Foundation.winmd")}",
            sourceFile.absolutePath
        )
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(2, TimeUnit.MINUTES)) process.destroyForcibly()
        } catch (e: Exception) {
            debugLine(TAG, "Cannot compile the share helper: ${e.message}")
        }
        if (!exe.isFile) {
            debugLine(TAG, "The share helper did not compile: ${if (log.isFile) log.readText().take(500) else "no output"}")
            return@withLock null
        }
        sourceFile.delete()
        log.delete()
        exe
    }
}
