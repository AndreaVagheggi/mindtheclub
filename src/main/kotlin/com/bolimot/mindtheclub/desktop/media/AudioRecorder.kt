package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Records a voice message from the microphone Windows has as default: the PC's MediaRecorder
 * in ChatScreen.startRecording. One recording at a time.
 *
 * The sound is kept in memory as 16 bit PCM, one channel at 44.1 kHz (88 KB a second, five MB
 * a minute) and written out as WAV when the recording stops; AudioFiles then makes the M4A that
 * is sent. If Windows denies desktop programs the microphone (Settings, Privacy, Microphone)
 * the recording runs and is silent: Windows does not say so to the program.
 */
class AudioRecorder {

    private val format = AudioFormat(44_100f, 16, 1, true, false)

    @Volatile private var line: TargetDataLine? = null
    @Volatile private var reader: Thread? = null
    private val sound = ByteArrayOutputStream()

    /** Starts recording. False when there is no microphone this program can open. */
    @Synchronized
    fun start(): Boolean {
        if (line != null) return true
        return try {
            val opened = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
            opened.open(format)
            opened.start()
            synchronized(sound) { sound.reset() }
            line = opened
            reader = Thread({
                val buffer = ByteArray(8_192)
                while (true) {
                    val current = line ?: break
                    val count = try {
                        current.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        -1
                    }
                    if (count < 0) break
                    if (count > 0) synchronized(sound) { sound.write(buffer, 0, count) }
                    if (!current.isOpen) break
                }
            }, "voice-message-recorder").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            debugLine("AudioRecorder", "Cannot open the microphone: ${e.message}")
            line = null
            false
        }
    }

    /** Stops and returns the recording as a WAV file in the scratch folder, or null when nothing was recorded. */
    @Synchronized
    fun stop(): File? {
        val bytes = finish() ?: return null
        if (bytes.isEmpty()) return null
        return try {
            val file = File(App.context().filesDir, "voice_message_${guid()}.wav")
            val frames = bytes.size / format.frameSize
            AudioSystem.write(
                AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()),
                AudioFileFormat.Type.WAVE,
                file
            )
            file
        } catch (e: Exception) {
            debugLine("AudioRecorder", "Cannot write the recording: ${e.message}")
            null
        }
    }

    /** Stops and throws the recording away: the phone's cancelRecording. */
    @Synchronized
    fun cancel() {
        finish()
    }

    private fun finish(): ByteArray? {
        val current = line ?: return null
        line = null
        try {
            current.stop()
            current.close()
        } catch (e: Exception) {
            // Closed anyway.
        }
        try {
            reader?.join(1_000)
        } catch (e: InterruptedException) {
            // What was read so far is the recording.
        }
        reader = null
        return synchronized(sound) {
            val bytes = sound.toByteArray()
            sound.reset()
            bytes
        }
    }
}
