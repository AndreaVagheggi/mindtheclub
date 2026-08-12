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
import com.bolimot.mindtheclub.functions.convertDpToPx
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatMessageTime
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.viewHolders.openReactionsOnClick
import com.bumptech.glide.Glide

class StickerViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View

    fun bind(message: Message?, displayDateHeader: Boolean, isSelected: Boolean) {
        try {
            message?.let {
                val image = itemView.findViewById<ImageView>(R.id.image)

                val previewUri = it.uri.toUri()
                val dimension = convertDpToPx(itemView.context, 82)

                Glide.with(itemView.context)
                    .load(previewUri)
                    .override(dimension, dimension)
                    .into(image)

                selectableView = itemView.findViewById(R.id.selectable_area)
                reaction = itemView.findViewById(R.id.emoji)
                reaction.openReactionsOnClick(it.messageId)
                reaction.text = it.reaction

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatMessageTime(it)

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
            }

            selectableView.setOnClickListener {
                message?.let {
                    if (listener.isAnyMessageSelected()) {
                        listener.onItemLongClick(it, selectableView, displayDateHeader)
                    } else {
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

