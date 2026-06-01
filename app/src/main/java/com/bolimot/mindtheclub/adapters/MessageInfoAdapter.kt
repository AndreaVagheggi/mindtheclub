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

data class MessageInfoItem(
    val userId: String,
    val name: String,
    val picture: String?,
    val deliveryStatus: String
)

class MessageInfoAdapter : ListAdapter<MessageInfoItem, MessageInfoAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.peer_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.name)
        private val statusView: TextView = itemView.findViewById(R.id.lastMessage)
        private val pictureView: ImageView = itemView.findViewById(R.id.peerImage)
        private val pendingIcon: ImageView = itemView.findViewById(R.id.pending)
        private val dateView: TextView = itemView.findViewById(R.id.lastMessageDate)
        private val badge: TextView = itemView.findViewById(R.id.unreadBadge)

        fun bind(item: MessageInfoItem) {
            nameView.text = item.name
            statusView.text = item.deliveryStatus
            pendingIcon.visibility = View.GONE
            dateView.text = ""
            badge.visibility = View.GONE

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
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MessageInfoItem>() {
            override fun areItemsTheSame(oldItem: MessageInfoItem, newItem: MessageInfoItem) =
                oldItem.userId == newItem.userId

            override fun areContentsTheSame(oldItem: MessageInfoItem, newItem: MessageInfoItem) =
                oldItem == newItem
        }
    }
}

