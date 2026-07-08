package com.bolimot.mindtheclub.viewHolders.chat

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MessagesAdapter
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.calculateVideoTargetDimensions
import com.bolimot.mindtheclub.functions.convertDpToPx
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatMessageTime
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.SubType
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView

class VideoViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View
    private var isMissing = false

    fun bind(message: Message?, displayDateHeader: Boolean, isSelected: Boolean, subType: String?) {
        try {
            message?.let {
                if(it.nameAttached == MySelf.name()){
                    it.nameAttached = itemView.context.getString(R.string.you)
                }

                val image = itemView.findViewById<ImageView>(R.id.image)
                val insertContainer = itemView.findViewById<MaterialCardView>(R.id.insert)

                reaction = itemView.findViewById(R.id.emoji)
                reaction.text = it.reaction

                val previewUri = it.uri.toUri()
                val dimensions = calculateVideoTargetDimensions(this.itemView.context, previewUri, 230)
                isMissing = dimensions == null
                val finalDimensions = dimensions ?: Pair(
                    convertDpToPx(itemView.context, 230),
                    convertDpToPx(itemView.context, 230)
                )
                val layoutParams = image.layoutParams
                layoutParams.width = finalDimensions.first
                layoutParams.height = finalDimensions.second
                image.layoutParams = layoutParams

                val layoutParams2 = insertContainer.layoutParams
                layoutParams2.width = finalDimensions.first
                insertContainer.layoutParams = layoutParams2

                val mediaUnavailableLabel = itemView.findViewById<TextView>(R.id.media_unavailable_label)
                val centerIcon = itemView.findViewById<View>(R.id.center_icon)
                val cameraIcon = itemView.findViewById<View>(R.id.bottom_right_camera_icon)

                if (isMissing) {
                    Glide.with(itemView.context)
                        .load(R.drawable.blur2)
                        .override(finalDimensions.first, finalDimensions.second)
                        .into(image)
                    mediaUnavailableLabel.visibility = View.VISIBLE
                    centerIcon.visibility = View.GONE
                    cameraIcon.visibility = View.GONE
                } else {
                    Glide.with(itemView.context)
                        .load(previewUri)
                        .override(finalDimensions.first, finalDimensions.second)
                        .frame(1000000)
                        .into(image)
                    mediaUnavailableLabel.visibility = View.GONE
                    centerIcon.visibility = View.VISIBLE
                    cameraIcon.visibility = View.VISIBLE
                }

                val messageTextView = itemView.findViewById<TextView>(R.id.message)
                if (subType == SubType.FORWARD) {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.VISIBLE
                } else {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.GONE
                }

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatMessageTime(it)
                itemView.findViewById<TextView>(R.id.nameAttached).text = it.nameAttached
                itemView.findViewById<TextView>(R.id.textAttached).text = it.textAttached

                selectableView = itemView.findViewById(R.id.selectable_area)
                status = itemView.findViewById(R.id.status)

                if(it.fromUserId == MySelf.userId()){
                    status.text = it.status
                    status.visibility = View.VISIBLE
                } else {
                    status.visibility = View.GONE
                }

                if(it.reaction.isNullOrEmpty()){
                    reaction.visibility = View.GONE
                } else {
                    reaction.visibility = View.VISIBLE
                }

                messageTextView.text = it.text
                if(it.text.isEmpty()){
                    messageTextView.visibility = View.GONE
                } else {
                    messageTextView.visibility = View.VISIBLE
                }
            }

            selectableView.setOnClickListener {
                message?.let {
                    if (listener.isAnyMessageSelected()) {
                        listener.onItemLongClick(it, selectableView, displayDateHeader)
                    } else if (!isMissing) {
                        listener.onItemClick(it)
                    }
                }
            }

            selectableView.setOnLongClickListener {
                message?.let { it1 -> listener.onItemLongClick(it1, selectableView, displayDateHeader) } ?: false
            }

            if (displayDateHeader) {
                itemView.findViewById<View>(R.id.date_header).visibility = View.VISIBLE
                if (message != null) {
                    itemView.findViewById<TextView>(R.id.dateTextView).text =
                        formatDate(message.date)
                }

            } else {
                itemView.findViewById<View>(R.id.date_header).visibility = View.GONE
            }

            if(message?.textAttached.isNullOrEmpty()){
                itemView.findViewById<View>(R.id.insert).visibility = View.GONE
            } else {
                itemView.findViewById<View>(R.id.insert).visibility = View.VISIBLE
            }

            val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)
            val selectableArea = itemView.findViewById<View>(R.id.selectable_area)

            selectableArea.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)

        } catch (ex: Exception) {
            debugLine("bind", "Exception: ${ex.message}")
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

