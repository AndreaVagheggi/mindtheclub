package com.bolimot.mindtheclub.tools

import android.media.AudioAttributes
import android.media.SoundPool
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App

object SoundManager {

    private var soundPool: SoundPool? = null
    private var incomingSoundId: Int = 0
    private var outgoingSoundId: Int = 0
    private var loaded = false

    fun init() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attributes)
            .build()

        soundPool?.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                loaded = true
            }
        }

        val context = App.context()
        incomingSoundId = soundPool?.load(context, R.raw.message_in, 1) ?: 0
        outgoingSoundId = soundPool?.load(context, R.raw.message_out, 1) ?: 0

        debugLine("SoundManager", "Initialized")
    }

    fun playIncoming() {
        if (loaded && incomingSoundId != 0) {
            soundPool?.play(incomingSoundId, 0.1f, 0.1f, 1, 0, 1f)
        }
    }

    fun playOutgoing() {
        if (loaded && outgoingSoundId != 0) {
            soundPool?.play(outgoingSoundId, 0.1f, 0.1f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        loaded = false
    }
}

