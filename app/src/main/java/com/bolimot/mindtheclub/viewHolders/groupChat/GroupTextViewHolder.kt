package com.bolimot.mindtheclub.viewHolders.groupChat

import android.graphics.Color
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
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
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.viewHolders.openReactionsOnClick
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren

class GroupTextViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View
    private lateinit var messageView: TextView

    fun bind(message: Message?,
             displayDateHeader: Boolean,
             isSelected: Boolean,
             subType: String?,
             peerName: String?,
             peerPicture: String?,
             showMemberHeader: Boolean) {

        scope.coroutineContext.cancelChildren()

        try {
            message?.let {
                if (it.nameAttached == MySelf.name()) {
                    it.nameAttached = itemView.context.getString(R.string.you)
                }

                messageView = itemView.findViewById(R.id.message)
                val textAttached = itemView.findViewById<TextView>(R.id.insert_message)
                val messageTextView = messageView

                val maxBubbleWidth = (260 * itemView.resources.displayMetrics.density).toInt()

                messageTextView.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                messageTextView.maxWidth = maxBubbleWidth

                textAttached.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                // Reply box has 8dp padding per side: cap the inner text so the
                // padded preview can never push the bubble past its 260dp cap.
                textAttached.maxWidth = maxBubbleWidth - (16 * itemView.resources.displayMetrics.density).toInt()

                reaction = itemView.findViewById(R.id.emoji)
                reaction.openReactionsOnClick(it.messageId)
                reaction.text = it.reaction

                if (!subType.isNullOrEmpty()) {
                    messageTextView.minWidth = (120 * itemView.resources.displayMetrics.density).toInt()
                } else {
                    messageTextView.minWidth = 0
                }

                if (it.text.isEmpty() && subType.isNullOrEmpty()) {
                    messageTextView.visibility = View.GONE
                } else if (it.text.isEmpty()) {
                    messageTextView.text = ""
                    messageTextView.visibility = View.INVISIBLE
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
                val cardContainer = itemView.findViewById<View>(R.id.card_container)
                if (subType == SubType.FORWARD) {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.VISIBLE
                    cardContainer.minimumWidth = (140 * itemView.resources.displayMetrics.density).toInt()
                } else {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.GONE
                    cardContainer.minimumWidth = (68 * itemView.resources.displayMetrics.density).toInt()
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

            if (subType.isNullOrEmpty()) {
                itemView.findViewById<View>(R.id.reply_container).visibility = View.GONE
            } else {
                val replyContainer = itemView.findViewById<View>(R.id.reply_container)
                replyContainer.visibility = View.VISIBLE

                // Always MATCH_PARENT: the wrap_content vertical LinearLayout first measures
                // every child at its natural width, sizes the bubble to the widest, then stretches
                // match_parent children to that width (LinearLayout.forceUniformWidth). Whichever
                // is wider, preview or reply text, both end up flush. Niente previsioni di
                // larghezza: the old measureText() heuristic could not account for min widths,
                // paddings, text sizes and wrapping, and that is what caused the mismatches. Set
                // explicitly, not only in XML, so a recycled view cannot keep a stale
                // WRAP_CONTENT.
                val params = replyContainer.layoutParams
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                replyContainer.minimumWidth = 0
                replyContainer.layoutParams = params
            }

            val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)

            val selectableArea = itemView.findViewById<View>(R.id.selectable_area)
            selectableArea.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)

         } catch (ex: Exception) {
            debugLine("bind", "Exception: ${ex.message}")
        }
    }

    fun onViewRecycled() {
        scope.coroutineContext.cancelChildren()
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
