package com.bolimot.mindtheclub.viewHolders.groupChat

import android.graphics.Color
import android.net.Uri
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
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.viewHolders.openReactionsOnClick
import com.bumptech.glide.Glide

class GroupMultiPictureViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private val uriList: ArrayList<Uri> = ArrayList()
    private val imageList: ArrayList<ImageView> = ArrayList()
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

            imageList.clear()
            uriList.clear()

            message?.let { it ->
                if(it.nameAttached == MySelf.name()){
                    it.nameAttached = itemView.context.getString(R.string.you)
                }

                val imageIds = listOf(R.id.image1, R.id.image2, R.id.image3, R.id.image4)

                imageIds.forEach { id ->
                    val image = itemView.findViewById<ImageView>(id)
                    imageList.add(image)
                }

                uriList.addAll(it.uri.split(",").map { it.trim().toUri() })

                uriList.take(4).forEachIndexed { index, uri ->
                    Glide.with(itemView.context)
                        .load(uri)
                        .override(convertDpToPx(itemView.context, 112), convertDpToPx(itemView.context, 112))
                        .into(imageList[index])
                }

                selectableView = itemView.findViewById(R.id.selectable_area)
                reaction = itemView.findViewById(R.id.emoji)
                reaction.openReactionsOnClick(it.messageId)
                reaction.text = it.reaction

                if (subType == SubType.FORWARD) {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.VISIBLE
                } else {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.GONE
                }

                val more = uriList.size - 4
                val moreTextView = itemView.findViewById<TextView>(R.id.more)
                if(more > 0){
                    "+$more".also { moreTextView.text = it }
                    moreTextView.visibility = View.VISIBLE
                } else {
                    moreTextView.visibility = View.GONE
                }

                val messageTextView = itemView.findViewById<TextView>(R.id.message)

                if (it.text.isEmpty()) {
                    messageTextView.visibility = View.GONE
                } else {
                    messageTextView.visibility = View.VISIBLE
                }

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatMessageTime(it)
                itemView.findViewById<TextView>(R.id.nameAttached).text = it.nameAttached
                itemView.findViewById<TextView>(R.id.textAttached).text = it.textAttached
                messageTextView.text = it.text

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