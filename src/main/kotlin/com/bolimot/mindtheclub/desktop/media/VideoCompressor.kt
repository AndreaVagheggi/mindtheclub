package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Re-encodes a video down to a size that can cross a phone to PC link, before it is sent: the
 * PC's counterpart of the phone's functions/VideoCompressor.kt, with the same targets and the
 * same rules.
 *
 *   - a video of 8 MB or less is left alone;
 *   - a video whose longest side is already 720 or less is left alone;
 *   - otherwise it is re-encoded no taller than 720, H.264 with AAC, at 2 Mbit/s;
 *   - a result that is not smaller than the original is thrown away.
 *
 * WHY IT MATTERS. A video sent as the camera wrote it is some 100 MB a minute, which is
 * thousands of chunks: on the phone such transfers arrived hours late, through repeated network
 * drops and a new WebRTC negotiation at each. That, and not disk space, is what the phone's
 * compressor was written for.
 *
 * HOW. Java cannot encode video. Windows can: this runs the transcoder that is part of Windows
 * 10 and 11 (the one the Photos app uses) through a small PowerShell script kept with the
 * program, src/main/resources/desktop/transcode.ps1. Nothing is installed or downloaded.
 *
 * As on the phone, a null result is not an error to show the user. It means "send the
 * original": compression is an optimisation and must never be the reason a message does not
 * go out.
 */
object VideoCompressor {

    private const val TAG = "VideoCompressor"

    /** VideoCompressor.TARGET_HEIGHT and TARGET_BITRATE in the App. */
    private const val TARGET_HEIGHT = 720
    private const val TARGET_BITRATE = 2_000_000

    /** VideoCompressor.SKIP_BELOW_BYTES in the App. */
    const val SKIP_BELOW_BYTES = 8L * 1024 * 1024

    /** A transcode still running after this long is given up, and the original is sent. */
    private const val TIMEOUT_MINUTES = 30L

    private const val SCRIPT_RESOURCE = "/desktop/transcode.ps1"

    /** Whether [source] is big enough for a transcode to be tried at all. */
    fun mayCompress(source: File): Boolean = source.length() > SKIP_BELOW_BYTES

    /**
     * Returns the re-encoded copy of [source], a new file in the scratch folder that the caller
     * owns and deletes, or null when the original is what should be sent.
     */
    suspend fun compress(source: File): File? = withContext(Dispatchers.IO) {
        val sourceBytes = source.length()
        if (sourceBytes <= SKIP_BELOW_BYTES) {
            debugLine(TAG, "Skipping: ${sourceBytes / 1024 / 1024} MB is already small")
            return@withContext null
        }

        val scratch = App.context().filesDir
        val script = File(scratch, "transcode.ps1")
        val output = File(scratch, "compressed_${guid()}.mp4")
        val report = File(scratch, "compressed_${guid()}.txt")

        try {
            // The script travels inside the program and is written out each time: what runs is
            // always the version this program was built with.
            val text = VideoCompressor::class.java.getResourceAsStream(SCRIPT_RESOURCE)?.use { it.readBytes() }
            if (text == null) {
                debugLine(TAG, "The transcode script is not in the program, sending the original")
                return@withContext null
            }
            script.writeBytes(text)

            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath,
                "-Source", source.absolutePath,
                "-Target", output.absolutePath,
                "-TargetHeight", TARGET_HEIGHT.toString(),
                "-Bitrate", TARGET_BITRATE.toString()
            )
                .redirectErrorStream(true)
                // To a file, not a pipe: nothing has to be read while the process runs, so a
                // process that hangs cannot hang this program with it.
                .redirectOutput(report)
                .start()

            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                debugLine(TAG, "Transcode still running after $TIMEOUT_MINUTES minutes, sending the original")
                output.delete()
                return@withContext null
            }

            val outcome = try {
                report.readLines().lastOrNull { it.isNotBlank() } ?: ""
            } catch (e: Exception) {
                ""
            }

            if (!outcome.startsWith("OK") || !output.isFile || output.length() == 0L) {
                debugLine(TAG, "Not transcoded (${outcome.ifEmpty { "no answer from the script" }}), sending the original")
                output.delete()
                return@withContext null
            }

            val newBytes = output.length()
            debugLine(TAG, "Transcoded ${sourceBytes / 1024} KB -> ${newBytes / 1024} KB")

            // A transcode that produced a BIGGER file is a loss twice over, in size and in
            // quality: keep the original, as the phone does.
            if (newBytes >= sourceBytes) {
                debugLine(TAG, "The result is not smaller, sending the original")
                output.delete()
                return@withContext null
            }
            output
        } catch (e: Exception) {
            debugLine(TAG, "Transcode failed (${e.javaClass.simpleName}: ${e.message}), sending the original")
            output.delete()
            null
        } finally {
            report.delete()
        }
    }
}
