package com.bolimot.mindtheclub.adapters

import android.icu.text.SimpleDateFormat
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
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bumptech.glide.Glide
import java.util.Date
import java.util.Locale

sealed class SearchItem {
    data class SectionHeader(val title: String) : SearchItem()
    data class ContactResult(val peer: Peer) : SearchItem()
    data class MessageResult(
        val message: Message,
        val peerName: String,
        val peerPicture: String?,
        val remoteUserId: String
    ) : SearchItem()
}

class SearchResultsAdapter(
    private val onContactClick: (Peer) -> Unit,
    private val onMessageClick: (message: Message, remoteUserId: String, peerName: String, peerPicture: String?) -> Unit
) : ListAdapter<SearchItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CONTACT = 1
        private const val TYPE_MESSAGE = 2

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchItem>() {
            override fun areItemsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean {
                return when {
                    oldItem is SearchItem.SectionHeader && newItem is SearchItem.SectionHeader ->
                        oldItem.title == newItem.title
                    oldItem is SearchItem.ContactResult && newItem is SearchItem.ContactResult ->
                        oldItem.peer.userId == newItem.peer.userId
                    oldItem is SearchItem.MessageResult && newItem is SearchItem.MessageResult ->
                        oldItem.message.messageId == newItem.message.messageId
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: SearchItem, newItem: SearchItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SearchItem.SectionHeader -> TYPE_HEADER
            is SearchItem.ContactResult -> TYPE_CONTACT
            is SearchItem.MessageResult -> TYPE_MESSAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.search_section_header, parent, false)
            )
            TYPE_CONTACT -> ContactViewHolder(
                inflater.inflate(R.layout.search_result_contact_item, parent, false)
            )
            TYPE_MESSAGE -> MessageViewHolder(
                inflater.inflate(R.layout.search_result_message_item, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is SearchItem.ContactResult -> (holder as ContactViewHolder).bind(item)
            is SearchItem.MessageResult -> (holder as MessageViewHolder).bind(item)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.sectionTitle)

        fun bind(item: SearchItem.SectionHeader) {
            titleView.text = item.title
        }
    }

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.contact_image)
        private val nameView: TextView = itemView.findViewById(R.id.contact_name)
        private val bioView: TextView = itemView.findViewById(R.id.contact_bio)

        fun bind(item: SearchItem.ContactResult) {
            val peer = item.peer
            nameView.text = peer.name
            bioView.text = peer.bio ?: ""

            loadPeerImage(imageView, peer)

            itemView.setOnClickListener { onContactClick(peer) }
        }
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.message_contact_image)
        private val nameView: TextView = itemView.findViewById(R.id.message_contact_name)
        private val previewView: TextView = itemView.findViewById(R.id.message_preview)
        private val dateView: TextView = itemView.findViewById(R.id.message_date)

        fun bind(item: SearchItem.MessageResult) {
            nameView.text = item.peerName
            previewView.text = item.message.text
            dateView.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(Date(item.message.date))

            if (item.remoteUserId.startsWith("group")) {
                Glide.with(itemView.context)
                    .load(R.drawable.group_placeholder)
                    .into(imageView)
            } else {
                loadPeerPicture(imageView, item.peerPicture)
            }

            itemView.setOnClickListener {
                onMessageClick(item.message, item.remoteUserId, item.peerName, item.peerPicture)
            }
        }
    }

    private fun loadPeerImage(imageView: ImageView, peer: Peer) {
        if (peer.userId.startsWith("group")) {
            Glide.with(imageView.context)
                .load(R.drawable.group_placeholder)
                .into(imageView)
        } else {
            peer.picture?.let { pictureUri ->
                val file = java.io.File(pictureUri.toUri().path ?: "")
                val signature = com.bumptech.glide.signature.ObjectKey(file.lastModified())
                Glide.with(imageView.context)
                    .load(pictureUri)
                    .signature(signature)
                    .placeholder(R.drawable.peer)
                    .error(R.drawable.peer)
                    .into(imageView)
            } ?: Glide.with(imageView.context)
                .load(R.drawable.peer)
                .into(imageView)
        }
    }

    private fun loadPeerPicture(imageView: ImageView, picture: String?) {
        picture?.let { pictureUri ->
            val file = java.io.File(pictureUri.toUri().path ?: "")
            val signature = com.bumptech.glide.signature.ObjectKey(file.lastModified())
            Glide.with(imageView.context)
                .load(pictureUri)
                .signature(signature)
                .placeholder(R.drawable.peer)
                .error(R.drawable.peer)
                .into(imageView)
        } ?: Glide.with(imageView.context)
            .load(R.drawable.peer)
            .into(imageView)
    }
}

