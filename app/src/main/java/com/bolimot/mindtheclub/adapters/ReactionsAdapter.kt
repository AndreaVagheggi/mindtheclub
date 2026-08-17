package com.bolimot.mindtheclub.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.tools.NO_PICTURE
import com.bumptech.glide.Glide

data class ReactionItem(
    val userId: String,
    val name: String,
    val picture: String?,
    val emoji: String,
    val isMe: Boolean
)

/** Who reacted to a message and with what, one row per member. */
class ReactionsAdapter(
    private val onRemoveMine: () -> Unit
) : ListAdapter<ReactionItem, ReactionsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.peer_item, parent, false)
        return ViewHolder(view, onRemoveMine)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onRemoveMine: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameView: TextView = itemView.findViewById(R.id.name)
        private val hintView: TextView = itemView.findViewById(R.id.lastMessage)
        private val emojiView: TextView = itemView.findViewById(R.id.lastMessageDate)
        private val pictureView: ImageView = itemView.findViewById(R.id.peerImage)
        private val pendingIcon: ImageView = itemView.findViewById(R.id.pending)
        private val badge: TextView = itemView.findViewById(R.id.unreadBadge)

        fun bind(item: ReactionItem) {
            nameView.text = item.name
            emojiView.text = item.emoji
            emojiView.textSize = 20f
            pendingIcon.visibility = View.GONE
            badge.visibility = View.GONE

            if (item.isMe) {
                hintView.text = itemView.context.getString(R.string.tap_to_remove)
                itemView.setOnClickListener { onRemoveMine() }
            } else {
                hintView.text = ""
                itemView.setOnClickListener(null)
            }

            if (!item.picture.isNullOrEmpty() && item.picture != NO_PICTURE) {
                Glide.with(itemView.context)
                    .load(item.picture.toUri())
                    .placeholder(R.drawable.peer)
                    .error(R.drawable.peer)
                    .into(pictureView)
            } else {
                pictureView.setImageResource(R.drawable.peer)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ReactionItem>() {
            override fun areItemsTheSame(oldItem: ReactionItem, newItem: ReactionItem) =
                oldItem.userId == newItem.userId

            override fun areContentsTheSame(oldItem: ReactionItem, newItem: ReactionItem) =
                oldItem == newItem
        }
    }
}
