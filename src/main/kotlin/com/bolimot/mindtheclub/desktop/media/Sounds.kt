package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10

/**
 * The App's SoundManager (tools/SoundManager.kt): the short sound of a message arriving in the
 * chat on screen, and of a message of ours being sent.
 *
 * The sounds are the App's own, res/raw/message_in.mp3 and message_out.mp3, converted once to
 * WAV (desktop/sounds) because Java plays WAV and not MP3. Played at the App's volume, 0.1 of
 * full scale, which is SoundPool's linear factor: -20 dB here.
 *
 * Each sound is loaded once and kept open, as SoundPool keeps it. A PC without a sound device
 * plays nothing and says so once in the log.
 */
object Sounds {

    private const val VOLUME = 0.1f

    private val incoming: Clip? by lazy { load("message_in") }
    private val outgoing: Clip? by lazy { load("message_out") }

    fun playIncoming() = play(incoming)

    fun playOutgoing() = play(outgoing)

    private fun play(clip: Clip?) {
        if (clip == null) return
        try {
            synchronized(clip) {
                clip.stop()
                clip.framePosition = 0
                clip.start()
            }
        } catch (e: Exception) {
            debugLine("Sounds", "play failed: ${e.message}")
        }
    }

    private fun load(name: String): Clip? = try {
        val resource = Sounds::class.java.getResourceAsStream("/desktop/sounds/$name.wav")
            ?: throw IllegalStateException("missing resource")
        val stream = AudioSystem.getAudioInputStream(BufferedInputStream(resource))
        val clip = AudioSystem.getClip()
        clip.open(stream)
        stream.close()
        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            gain.value = (20f * log10(VOLUME)).coerceIn(gain.minimum, gain.maximum)
        }
        clip
    } catch (e: Exception) {
        debugLine("Sounds", "$name not loaded: ${e.message}")
        null
    }
}
