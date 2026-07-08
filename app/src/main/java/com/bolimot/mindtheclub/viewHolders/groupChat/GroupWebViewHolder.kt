package com.bolimot.mindtheclub.viewHolders.groupChat

import android.content.Intent
import android.graphics.Color
import android.text.Spannable
import android.text.style.URLSpan
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MessagesAdapter
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatMessageTime
import com.bolimot.mindtheclub.functions.safeUrl
import com.bolimot.mindtheclub.tools.Icon
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.SubType
import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class GroupWebViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private lateinit var status: TextView
    private lateinit var reaction: TextView
    private lateinit var selectableView: View
    private var lastBoundMessageId: String? = null
    private var needsScrollAdjust = false
    private val imageLoadListener = object : RequestListener<Drawable> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean = false
        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
            if (needsScrollAdjust) {
                needsScrollAdjust = false
                itemView.post {
                    (itemView.parent as? RecyclerView)?.let { rv ->
                        val rvBottom = rv.height - rv.paddingBottom
                        if (itemView.top < rvBottom && itemView.bottom > rvBottom) {
                            rv.scrollBy(0, itemView.bottom - rvBottom)
                        }
                    }
                }
            }
            return false
        }
    }

    fun bind(message: Message?,
             displayDateHeader: Boolean,
             isSelected: Boolean,
             subType: String?,
             peerName: String?,
             peerPicture: String?,
             showMemberHeader: Boolean) {

        try {
            message?.let {
                needsScrollAdjust = it.messageId != lastBoundMessageId
                lastBoundMessageId = it.messageId
                try {
                    val imageToLoad = safeUrl(it.replyId) ?: R.drawable.image
                    val imageView = itemView.findViewById<ImageView>(R.id.image)

                    val defaultHeight = ViewGroup.LayoutParams.WRAP_CONTENT
                    val params = imageView.layoutParams
                    params.height = defaultHeight
                    imageView.layoutParams = params
                    imageView.requestLayout()

                    if (imageToLoad == Icon.TIKTOK || imageToLoad == Icon.YOUTUBE || imageToLoad == Icon.INSTAGRAM) {
                        val param = imageView.layoutParams
                        param.height = 300
                        imageView.layoutParams = param
                        imageView.requestLayout()

                        Glide.with(itemView.context)
                            .load(imageToLoad)
                            .listener(imageLoadListener)
                            .into(imageView)
                    } else {
                        Glide.with(itemView.context)
                            .load(imageToLoad)
                            .placeholder(R.drawable.image)
                            .error(R.drawable.error)
                            .listener(imageLoadListener)
                            .into(imageView)
                    }
                } catch (e: Exception) {
                   debugLine("bind", "Exception loading banner: ${e.message}")
                }

                if (subType == SubType.FORWARD) {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.VISIBLE
                } else {
                    itemView.findViewById<TextView>(R.id.forwarded).visibility = View.GONE
                }

                val nameAttached = itemView.findViewById<TextView>(R.id.nameAttached)
                val textAttached = itemView.findViewById<TextView>(R.id.textAttached)

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatMessageTime(it)
                nameAttached.text = it.nameAttached
                textAttached.text = it.textAttached
                itemView.findViewById<TextView>(R.id.message).text = it.text

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

                if (it.nameAttached.isNullOrEmpty()) {
                    nameAttached.visibility = View.GONE
                } else {
                    nameAttached.visibility = View.VISIBLE
                }

                if (it.textAttached.isNullOrEmpty()) {
                    textAttached.visibility = View.GONE
                } else {
                    textAttached.visibility = View.VISIBLE
                }

                status = itemView.findViewById(R.id.status)
                selectableView = itemView.findViewById(R.id.selectable_area)
                reaction = itemView.findViewById(R.id.emoji)

                reaction.text = it.reaction

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
                        val messageTextView = itemView.findViewById<TextView>(R.id.message)
                        val text = messageTextView.text

                        val url: String? = if (text is Spannable) {
                            val spans = text.getSpans(0, text.length, URLSpan::class.java)
                            spans.firstOrNull()?.url
                        } else {
                            val matcher = Patterns.WEB_URL.matcher(text.toString())
                            if (matcher.find()) matcher.group() else null
                        }

                        url?.let { urlString ->
                            val finalUrl = if (urlString.startsWith("http://") || urlString.startsWith("https://"))
                                urlString else "http://$urlString"
                            val intent = Intent(Intent.ACTION_VIEW, finalUrl.toUri())
                            try {
                                selectableView.context.startActivity(intent)
                            } catch (e: Exception) {
                                debugLine("WebViewHolder", "Could not open link: ${e.message}")
                            }                        }
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

