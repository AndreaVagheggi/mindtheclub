package com.bolimot.mindtheclub.viewHolders.chat

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MessagesAdapter
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatMessageTime
import com.bolimot.mindtheclub.functions.getFileDetailFromType
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.isFileType
import com.bolimot.mindtheclub.functions.saveBitmap
import com.bolimot.mindtheclub.functions.toImage
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.viewHolders.openReactionsOnClick
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TextImageViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View
    private lateinit var messageView: TextView

    fun bind(message: Message?, displayDateHeader: Boolean, isSelected: Boolean, subType: String?) {
        scope.coroutineContext.cancelChildren()

        try {
            message?.let {
                if (it.nameAttached == MySelf.name()) {
                    it.nameAttached = itemView.context.getString(R.string.you)
                }

                messageView = itemView.findViewById(R.id.message)
                val textAttached = itemView.findViewById<TextView>(R.id.insert_message)
                val messageTextView = messageView

                reaction = itemView.findViewById(R.id.emoji)
                reaction.openReactionsOnClick(it.messageId)
                reaction.text = it.reaction

                if (it.text.isEmpty()) {
                    messageTextView.visibility = View.GONE
                } else {
                    val spannableText = SpannableString(it.text)

                    spannableText.getSpans(0, spannableText.length, URLSpan::class.java).forEach { span ->
                        spannableText.removeSpan(span)
                    }

                    Linkify.addLinks(spannableText, Linkify.WEB_URLS)

                    messageTextView.text = spannableText
                    messageTextView.visibility = View.VISIBLE
                    messageTextView.movementMethod = LinkMovementMethod.getInstance()
                }

                status = itemView.findViewById(R.id.status)
                selectableView = itemView.findViewById(R.id.selectable_area)

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

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatMessageTime(it)
                itemView.findViewById<TextView>(R.id.insert_name).text = it.nameAttached
                textAttached.text = it.textAttached

                if (subType == SubType.FORWARD) {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.VISIBLE
                } else {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.GONE
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
                message?.let {
                    it1 -> listener.onItemLongClick(it1, selectableView, displayDateHeader)
                } ?: false
            }

            messageView.setOnClickListener {
                message?.let {
                    if (listener.isAnyMessageSelected()) {
                        listener.onItemLongClick(it, selectableView, displayDateHeader)
                    } else {
                        listener.onItemClick(it)
                    }
                }
            }

            messageView.setOnLongClickListener {
                message?.let {
                        it1 -> listener.onItemLongClick(it1, selectableView, displayDateHeader)
                } ?: false
            }

            if (displayDateHeader) {
                itemView.findViewById<View>(R.id.date_header).apply{
                    visibility = View.VISIBLE
                    setBackgroundColor(Color.TRANSPARENT)
                }
                message?.let {
                    itemView.findViewById<TextView>(R.id.dateTextView).text = formatDate(it.date)
                }
            } else {
                itemView.findViewById<View>(R.id.date_header).visibility = View.GONE
            }


            itemView.findViewById<View>(R.id.reply_container).visibility = View.VISIBLE
            message?.let {
                val rightImage = itemView.findViewById<ImageView>(R.id.insert_image)

                Glide.with(itemView.context).clear(rightImage)
                rightImage.setImageDrawable(null)

                scope.launch {
                    val replyId = message.replyId
                    if (replyId.isNullOrEmpty()) {
                        itemView.findViewById<View>(R.id.reply_container).visibility = View.GONE
                        return@launch
                    }

                    val originalType = getReplyType(replyId)
                    if (originalType == null) {
                        itemView.findViewById<View>(R.id.reply_container).visibility = View.GONE
                        return@launch
                    }

                    var imageUrl: String?

                    imageUrl = if (!isFileType(originalType)) {
                        getReplyImage(replyId, originalType)
                    } else {
                        toImage(
                            getFileDetailFromType(originalType)[1],
                            itemView.context
                        ).toString()
                    }

                    if (originalType != Type.AUDIO) {
                        rightImage.visibility = View.VISIBLE

                        if (originalType == Type.MULTIPLE_IMAGES) imageUrl = message.uri

                        if (originalType == Type.VIDEO) {
                            imageUrl = saveFirstVideoFrame(imageUrl, itemView.context)
                        }

                        if (!isActive) return@launch

                        Glide.with(itemView.context)
                            .load(imageUrl)
                            .into(rightImage)
                    } else {
                        rightImage.visibility = View.GONE
                        val text = "<${itemView.context.getString(R.string.audio)}>"
                        itemView.findViewById<TextView>(R.id.insert_message).text = text
                    }
                }
            }

            val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)

            val selectableArea = itemView.findViewById<View>(R.id.selectable_area)
            selectableArea.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)

         } catch (ex: Exception) {
            debugLine("bind", "Exception: ${ex.message}")
        }
    }

    private suspend fun saveFirstVideoFrame(uri: String?, context: Context): String? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val bitmap = Glide.with(context)
                    .asBitmap()
                    .load(uri)
                    .frame(1000000)
                    .submit()
                    .get()

                saveBitmap(bitmap, guid(), 50, false).toString()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun getReplyImage(replyId: String, type: String): String? {
        return getMessageRepository(this@TextImageViewHolder.itemView.context).getReplyImage(replyId, type)
    }

    private suspend fun getReplyType(replyId: String): String? {
        return getMessageRepository(this@TextImageViewHolder.itemView.context).getReplyType(replyId)
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
