package com.bolimot.mindtheclub.viewHolders.peers

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.PeersAdapter
import com.bolimot.mindtheclub.database.peer.Peer
import com.bumptech.glide.Glide
import androidx.core.net.toUri

abstract class PeersBaseViewHolder(itemView: View, private val listener: PeersAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    fun baseBind(peer: Peer?) {
        val imageView = itemView.findViewById<ImageView>(R.id.peerImage)

        if (peer?.userId?.startsWith("group") == true) {
            val groupPicUrl = peer.picture
            if (!groupPicUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(groupPicUrl)
                    .placeholder(R.drawable.group_placeholder)
                    .error(R.drawable.group_placeholder)
                    .into(imageView)
            } else {
                Glide.with(itemView.context)
                    .load(R.drawable.group_placeholder)
                    .into(imageView)
            }
        } else {
            peer?.picture?.let { pictureUri ->
                val file = java.io.File(pictureUri.toUri().path ?: "")
                val signature = com.bumptech.glide.signature.ObjectKey(file.lastModified())

                Glide.with(itemView.context)
                    .load(pictureUri)
                    .signature(signature)
                    .placeholder(imageView.drawable)
                    .error(R.drawable.peer)
                    .into(imageView)
            } ?: run {
                Glide.with(itemView.context)
                    .load(R.drawable.peer)
                    .into(imageView)
            }
        }

        itemView.findViewById<TextView>(R.id.name).text = peer?.name

        itemView.setOnClickListener {
            peer?.let { it1 -> listener.onItemClick(it1) }
        }

        itemView.setOnLongClickListener {
            peer?.let { it1 -> listener.onItemLongClick(it1) } == true
        }
    }
}