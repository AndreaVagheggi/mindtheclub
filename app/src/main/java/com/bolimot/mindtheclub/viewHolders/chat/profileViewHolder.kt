package com.bolimot.mindtheclub.viewHolders.chat

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MessagesAdapter
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatTime
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.SubType
import com.bumptech.glide.Glide
import kotlinx.serialization.json.Json

class ProfileViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View

    fun bind(message: Message?, displayDateHeader: Boolean, isSelected: Boolean, subType: String?) {
        try {
            message?.let {
                if(it.nameAttached == MySelf.name()){
                    it.nameAttached = itemView.context.getString(R.string.you)
                }

                val peer = Json.decodeFromString<Peer>(it.text)

                itemView.findViewById<TextView>(R.id.name).text = peer.name
                itemView.findViewById<TextView>(R.id.bio).text = peer.bio

                val viewProfileButton = itemView.findViewById<View>(R.id.view)
                val addContactButton = itemView.findViewById<View>(R.id.add)
                val currentMessage = it

                viewProfileButton.setOnClickListener {
                    listener.onViewProfileClick(currentMessage)
                }

                addContactButton.setOnClickListener {
                    listener.onAddContactClick(currentMessage)
                }

                val image = itemView.findViewById<ImageView>(R.id.peerImage)
                Glide.with(itemView.context)
                    .load(message.uri)
                    .into(image)

                reaction = itemView.findViewById(R.id.emoji)
                reaction.text = it.reaction

                val messageTextView = itemView.findViewById<TextView>(R.id.message)

                if (subType == SubType.FORWARD) {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.VISIBLE
                } else {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.GONE
                }

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatTime(it.date)
                itemView.findViewById<TextView>(R.id.nameAttached).text = it.nameAttached
                itemView.findViewById<TextView>(R.id.textAttached).text = it.textAttached

                status = itemView.findViewById(R.id.status)
                selectableView = itemView.findViewById(R.id.selectable_area)

                if(it.fromUserId == MySelf.userId()){
                    status.text = it.status
                    status.visibility = View.VISIBLE
                } else {
                    status.visibility = View.GONE
                }

                messageTextView.visibility = View.GONE

                if(it.reaction.isNullOrEmpty()){
                    reaction.visibility = View.GONE
                } else {
                    reaction.visibility = View.VISIBLE
                }
            }

            itemView.setOnClickListener {
                message?.let {
                    if (listener.isAnyMessageSelected()) {
                        listener.onItemLongClick(it, selectableView, displayDateHeader)
                    } else {
                        listener.onItemClick(it)
                    }
                }
            }

            itemView.setOnLongClickListener {
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

