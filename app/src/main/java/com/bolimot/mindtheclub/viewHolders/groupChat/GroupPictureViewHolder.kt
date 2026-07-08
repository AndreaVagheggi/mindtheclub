package com.bolimot.mindtheclub.viewHolders.groupChat

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
import com.bolimot.mindtheclub.functions.calculateTargetDimensions
import com.bolimot.mindtheclub.functions.convertDpToPx
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatMessageTime
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.SubType
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView

class GroupPictureViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View

    fun bind(message: Message?,
             displayDateHeader: Boolean,
             isSelected: Boolean,
             subType: String?,
             peerName: String?,
             peerPicture: String?,
             showMemberHeader: Boolean) {

        try {
            message?.let {
                if(it.nameAttached == MySelf.name()){
                    it.nameAttached = itemView.context.getString(R.string.you)
                }

                val image = itemView.findViewById<ImageView>(R.id.image)
                val insertContainer = itemView.findViewById<MaterialCardView>(R.id.insert)
                val imageContainer = itemView.findViewById<MaterialCardView>(R.id.image_container)
                val messageTextView = itemView.findViewById<TextView>(R.id.message)
                selectableView = itemView.findViewById(R.id.selectable_area)

                val previewUri = it.uri.toUri()
                val dimensions = calculateTargetDimensions(previewUri, 230)

                if (dimensions != null) {
                    imageContainer.visibility = View.VISIBLE

                    val layoutParams = image.layoutParams
                    layoutParams.width = dimensions.first
                    layoutParams.height = dimensions.second
                    image.layoutParams = layoutParams

                    val layoutParams2 = insertContainer.layoutParams
                    layoutParams2.width = dimensions.first
                    insertContainer.layoutParams = layoutParams2

                    Glide.with(itemView.context)
                        .load(previewUri)
                        .override(dimensions.first, dimensions.second)
                        .into(image)
                } else {
                    imageContainer.visibility = View.VISIBLE
                    image.visibility = View.VISIBLE
                    val fallbackSize = convertDpToPx(itemView.context, 230)

                    val layoutParams = image.layoutParams
                    layoutParams.width = fallbackSize
                    layoutParams.height = fallbackSize
                    image.layoutParams = layoutParams

                    val layoutParams2 = insertContainer.layoutParams
                    layoutParams2.width = fallbackSize
                    insertContainer.layoutParams = layoutParams2

                    Glide.with(itemView.context)
                        .load(R.drawable.blur2)
                        .override(fallbackSize, fallbackSize)
                        .into(image)
                }

                reaction = itemView.findViewById(R.id.emoji)
                reaction.text = it.reaction
                if (subType == SubType.FORWARD) {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.VISIBLE
                } else {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.GONE
                }

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatMessageTime(it)
                itemView.findViewById<TextView>(R.id.nameAttached).text = it.nameAttached
                itemView.findViewById<TextView>(R.id.textAttached).text = it.textAttached

                val memberHeader = itemView.findViewById<View>(R.id.member_header)
                val cardMessage = itemView.findViewById<View>(R.id.card_message)
                val cardParams = cardMessage.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

                if (showMemberHeader) {
                    memberHeader.visibility = View.VISIBLE
                    cardParams.topMargin = (46 * itemView.resources.displayMetrics.density).toInt()
                    val memberImage = itemView.findViewById<ImageView>(R.id.memberImage)
                    itemView.findViewById<TextView>(R.id.member).text = peerName
                    Glide.with(itemView.context)
                        .load(peerPicture)
                        .fallback(R.drawable.peer)
                        .error(R.drawable.peer)
                        .into(memberImage)
                } else {
                    memberHeader.visibility = View.GONE
                    cardParams.topMargin = (4 * itemView.resources.displayMetrics.density).toInt()
                }
                cardMessage.layoutParams = cardParams

                status = itemView.findViewById(R.id.status)

                if(it.fromUserId == MySelf.userId()){
                    status.text = it.status
                    status.visibility = View.VISIBLE
                } else {
                    status.visibility = View.GONE
                }

                val mediaUnavailableLabel = itemView.findViewById<TextView>(R.id.media_unavailable_label)

                if (dimensions != null) {
                    mediaUnavailableLabel.visibility = View.GONE
                    messageTextView.text = it.text
                    if(it.text.isEmpty()){
                        messageTextView.visibility = View.GONE
                    } else {
                        messageTextView.visibility = View.VISIBLE
                    }
                } else {
                    messageTextView.text = it.text
                    if(it.text.isEmpty()){
                        messageTextView.visibility = View.GONE
                    } else {
                        messageTextView.visibility = View.VISIBLE
                    }
                    imageContainer.visibility = View.VISIBLE
                    mediaUnavailableLabel.visibility = View.VISIBLE
                }

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

