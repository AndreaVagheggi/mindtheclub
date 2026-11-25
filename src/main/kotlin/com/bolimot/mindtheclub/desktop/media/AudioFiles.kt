package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.computeSha256
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Voice messages on the PC.
 *
 * A phone records a voice message as MPEG-4 with AAC sound (ChatScreen.startRecording:
 * MediaRecorder, OutputFormat.MPEG_4, AudioEncoder.AAC) and plays whatever arrives with
 * MediaPlayer. Java can do neither: it records and plays plain PCM only. Windows can do both,
 * with the transcoder that is part of Windows 10 and 11, the same one that re-encodes videos
 * (src/main/resources/desktop/audio.ps1, like transcode.ps1 for video). So:
 *
 *   - a recording is made as WAV (AudioRecorder) and turned into M4A before it is sent, which
 *     is the file a phone would have sent;
 *   - a voice message is turned into WAV the first time it is played, and the WAV is kept in
 *     the scratch folder for the next time.
 *
 * Each conversion takes about a second.
 */
object AudioFiles {

    private const val TAG = "AudioFiles"
    private const val SCRIPT_RESOURCE = "/desktop/audio.ps1"

    /** A voice message lasts minutes, not hours: a conversion still running after this is given up. */
    private const val TIMEOUT_MINUTES = 5L

    /** The scratch folder where the playable copies are kept. */
    private fun playableDir(): File = File(App.context().filesDir, "audio_play").apply { mkdirs() }

    /**
     * The extension a received sound should be kept under, read from its first bytes: Windows
     * chooses how to read a file by its extension. A phone's own recording is "m4a"; a sound
     * shared from another app keeps what it is (mp3, ogg, ...). "m4a" when nothing is recognised.
     */
    fun extensionOf(file: File): String {
        val head = ByteArray(12)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        if (read < 12) return "m4a"

        fun ascii(from: Int, length: Int) = String(head, from, length, Charsets.ISO_8859_1)
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        return when {
            ascii(4, 4) == "ftyp" -> if (ascii(8, 3) == "3gp") "3gp" else "m4a"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" -> "wav"
            ascii(0, 4) == "OggS" -> "ogg"
            ascii(0, 4) == "fLaC" -> "flac"
            ascii(0, 5) == "#!AMR" -> "amr"
            ascii(0, 3) == "ID3" -> "mp3"
            // ADTS, a bare AAC stream: sync word with layer 0.
            b0 == 0xFF && (b1 and 0xF6) == 0xF0 -> "aac"
            // An MPEG audio frame: sync word with a layer.
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> "mp3"
            else -> "m4a"
        }
    }

    /**
     * Turns a recording into the voice message that is sent: M4A, kept in the media folder under
     * the hash of its bytes like every other file there. [wav] is deleted. Null when Windows
     * could not convert it.
     */
    suspend fun keepRecording(wav: File): File? = withContext(Dispatchers.IO) {
        val m4a = File(App.context().filesDir, "voice_message_${guid()}.m4a")
        try {
            if (!convert(wav, m4a, "m4a")) return@withContext null
            val hash = computeSha256(m4a.inputStream()) ?: return@withContext null
            MediaFiles.keep(m4a, "$hash.m4a")
        } finally {
            wav.delete()
            if (m4a.isFile) m4a.delete()
        }
    }

    /**
     * The copy of [voiceMessage] that Java can play, made the first time it is asked for. Null
     * when Windows cannot read the sound.
     */
    suspend fun playable(voiceMessage: File): File? = withContext(Dispatchers.IO) {
        val target = File(playableDir(), voiceMessage.nameWithoutExtension + ".wav")
        if (target.isFile && target.length() > 0) return@withContext target
        val temp = File(playableDir(), "converting_${guid()}.wav")
        if (!convert(voiceMessage, temp, "wav")) {
            temp.delete()
            return@withContext null
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return@withContext if (target.isFile) target else null
        }
        target
    }

    /** Runs audio.ps1. True when [target] was written. */
    private fun convert(source: File, target: File, format: String): Boolean {
        val scratch = App.context().filesDir
        val script = File(scratch, "audio.ps1")
        val report = File(scratch, "audio_${guid()}.txt")
        try {
            // The script travels inside the program and is written out each time: what runs is
            // always the version this program was built with.
            val text = AudioFiles::class.java.getResourceAsStream(SCRIPT_RESOURCE)?.use { it.readBytes() }
            if (text == null) {
                debugLine(TAG, "The audio script is not in the program")
                return false
            }
            script.writeBytes(text)

            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath,
                "-Source", source.absolutePath,
                "-Target", target.absolutePath,
                "-Format", format
            )
                .redirectErrorStream(true)
                // To a file, not a pipe, as for video: a process that hangs cannot hang this one.
                .redirectOutput(report)
                .start()

            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                debugLine(TAG, "Conversion to $format still running after $TIMEOUT_MINUTES minutes, given up")
                target.delete()
                return false
            }

            val outcome = try {
                report.readLines().lastOrNull { it.isNotBlank() } ?: ""
            } catch (e: Exception) {
                ""
            }
            if (!outcome.startsWith("OK") || !target.isFile || target.length() == 0L) {
                debugLine(TAG, "Not converted to $format (${outcome.ifEmpty { "no answer from the script" }})")
                target.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            debugLine(TAG, "Conversion to $format failed: ${e.message}")
            target.delete()
            return false
        } finally {
            report.delete()
        }
    }
}
