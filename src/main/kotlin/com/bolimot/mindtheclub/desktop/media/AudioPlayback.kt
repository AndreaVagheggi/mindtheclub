package com.bolimot.mindtheclub.desktop.media

import com.bolimot.mindtheclub.functions.debugLine
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/**
 * Plays one voice message: the MediaPlayer each AudioViewHolder has on the phone, with the same
 * three moves, play, pause and play on from where it stopped, and back to the start once it has
 * played to the end.
 *
 * The sound is opened the first time it is played, from its playable copy (AudioFiles.playable),
 * and held until [release], which the bubble calls when it leaves the screen, as the phone's
 * adapter does in onViewRecycled.
 */
class AudioPlayback(private val voiceMessage: File) {

    @Volatile private var clip: Clip? = null

    /** Opens the sound if needed and plays it. False when Windows cannot read it. */
    suspend fun play(): Boolean {
        val current = clip ?: open() ?: return false
        if (current.framePosition >= current.frameLength) current.framePosition = 0
        current.start()
        return true
    }

    fun pause() {
        clip?.stop()
    }

    /** How far it has played, from 0 to 1. */
    fun progress(): Float {
        val current = clip ?: return 0f
        val length = current.frameLength
        if (length <= 0) return 0f
        return (current.framePosition.toFloat() / length).coerceIn(0f, 1f)
    }

    /** True once it has stopped at its end; it then starts again from the beginning. */
    fun hasEnded(): Boolean {
        val current = clip ?: return false
        if (current.isRunning || current.framePosition < current.frameLength) return false
        current.framePosition = 0
        return true
    }

    fun release() {
        val current = clip ?: return
        clip = null
        try {
            current.stop()
            current.close()
        } catch (e: Exception) {
            // Closed anyway.
        }
    }

    private suspend fun open(): Clip? {
        val playable = AudioFiles.playable(voiceMessage) ?: return null
        return try {
            val opened = AudioSystem.getClip()
            AudioSystem.getAudioInputStream(playable).use { opened.open(it) }
            clip = opened
            opened
        } catch (e: Exception) {
            debugLine("AudioPlayback", "Cannot play ${voiceMessage.name}: ${e.message}")
            null
        }
    }
}
