package com.bolimot.mindtheclub.viewHolders.groupChat

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MessagesAdapter
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatTime
import com.bolimot.mindtheclub.functions.safeUrl
import com.bolimot.mindtheclub.tools.MySelf
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import androidx.core.net.toUri

class GroupAudioViewHolder(
    itemView: View,
    private val listener: MessagesAdapter.OnItemClickListener
) : RecyclerView.ViewHolder(itemView) {

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressBarRunnable: Runnable? = null
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View

    fun bind(
        message: Message?,
        displayDateHeader: Boolean,
        isSelected: Boolean,
        peerName: String?,
        picUri: String?
    ) {

        try {
            message?.let { msg ->
                val playPauseButton = itemView.findViewById<ImageButton>(R.id.play)
                val audioProgressBar = itemView.findViewById<ProgressBar>(R.id.progress)

                Glide.with(itemView.context)
                    .load(safeUrl(picUri))
                    .fallback(R.drawable.peer)
                    .error(R.drawable.peer)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean
                        ): Boolean {
                            debugLine("GlideError", "Load failed: $e")
                            return false
                        }
                        override fun onResourceReady(
                            resource: Drawable?, model: Any?, target: Target<Drawable>?,
                            dataSource: DataSource?, isFirstResource: Boolean
                        ): Boolean {
                            debugLine("GlideSuccess", "All good man")
                            return false
                        }
                    })
                    .into(itemView.findViewById(R.id.profilePic))

                itemView.findViewById<TextView>(R.id.member).text = peerName

                setupMediaPlayer(msg.uri, audioProgressBar, playPauseButton)

                playPauseButton.setOnClickListener {
                    if (isPlaying) {
                        pauseAudio(playPauseButton)
                    } else {
                        playAudio(playPauseButton, audioProgressBar)
                    }
                }

                selectableView = itemView.findViewById(R.id.selectable_area)
                reaction = itemView.findViewById(R.id.emoji)
                reaction.text = msg.reaction

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatTime(msg.date)

                reaction.text = msg.reaction
                status = itemView.findViewById(R.id.status)
                if(msg.fromUserId == MySelf.userId()){
                    status.text = msg.status
                    status.visibility = View.VISIBLE
                } else {
                    status.visibility = View.GONE
                }

                if(msg.reaction.isNullOrEmpty()){
                    reaction.visibility = View.GONE
                } else {
                    reaction.visibility = View.VISIBLE
                }

                selectableView.setOnClickListener {
                    if (listener.isAnyMessageSelected()) {
                        listener.onItemLongClick(msg, selectableView,displayDateHeader)
                    } else {
                        listener.onItemClick(msg)
                    }
                }

                selectableView.setOnLongClickListener {
                    listener.onItemLongClick(msg, selectableView, displayDateHeader)
                    true
                }

                if (displayDateHeader) {
                    itemView.findViewById<View>(R.id.date_header).visibility = View.VISIBLE
                    itemView.findViewById<TextView>(R.id.dateTextView).text = formatDate(msg.date)
                } else {
                    itemView.findViewById<View>(R.id.date_header).visibility = View.GONE
                }

                val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)
                selectableView.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)

            }
        } catch (ex: Exception) {
            debugLine("bind", "Exception: ${ex.message}")
        }
    }

    private fun setupMediaPlayer(
        audioUri: String,
        audioSeekBar: ProgressBar,
        playPauseButton: ImageButton
    ) {
        releaseMediaPlayer()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(itemView.context, audioUri.toUri())
                prepare()
                audioSeekBar.max = duration
            } catch (ex: Exception) {
                debugLine("AudiPlayer", "Error Playing Audio: ${ex.message}")
            }
        }

        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            playPauseButton.setImageResource(R.drawable.play)
            handler.removeCallbacks(updateProgressBarRunnable!!)
            audioSeekBar.progress = 0
        }
    }

    private fun playAudio(
        playPauseButton: ImageButton,
        audioProgressBar: ProgressBar,
    ) {
        mediaPlayer?.start()
        isPlaying = true
        playPauseButton.setImageResource(R.drawable.pause)
        updateProgressBar(audioProgressBar)
    }

    private fun pauseAudio(playPauseButton: ImageButton) {
        mediaPlayer?.pause()
        isPlaying = false
        playPauseButton.setImageResource(R.drawable.play)
        handler.removeCallbacks(updateProgressBarRunnable!!)
    }

    private fun updateProgressBar(audioProgressBar: ProgressBar) {
        updateProgressBarRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    audioProgressBar.progress = it.currentPosition
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(updateProgressBarRunnable!!)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        updateProgressBarRunnable?.let{
            handler.removeCallbacks(updateProgressBarRunnable!!)
        }
    }

    fun updateStatus(newStatus: String) {
        itemView.findViewById<TextView>(R.id.status).text = newStatus
    }

    fun updateReaction(emoji: String?) {
        val reactionView = itemView.findViewById<TextView>(R.id.emoji) ?: return
        reactionView.text = emoji
        reactionView.visibility = if (emoji.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
