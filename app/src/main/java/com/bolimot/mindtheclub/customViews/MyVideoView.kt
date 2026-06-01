package com.bolimot.mindtheclub.customViews

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

class MyVideoView : VideoView {

    var onPlayPauseListener: OnPlayPauseListener? = null

    interface OnPlayPauseListener {
        fun onPlay()
        fun onPause()
        fun onCompletion()
    }

    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) { init() }

    private fun init() {
        setOnCompletionListener {
            onPlayPauseListener?.onCompletion()
        }
    }

    override fun start() {
        super.start()
        onPlayPauseListener?.onPlay()
    }

    override fun pause() {
        super.pause()
        onPlayPauseListener?.onPause()
    }
}
