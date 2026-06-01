package com.bolimot.mindtheclub.viewHolders.peers

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MembersAdapter
import com.bolimot.mindtheclub.dataModels.ReceivedRequest
import com.bumptech.glide.Glide

abstract class MemberBaseViewHolder(itemView: View, private val listener: MembersAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {

    fun baseBind(request: ReceivedRequest?) {
        request?.picture?.let { pictureUrl ->
            val imageView = itemView.findViewById<ImageView>(R.id.peerImage)
            Glide.with(itemView.context)
                .load(pictureUrl)
                .placeholder(R.drawable.peer)
                .error(R.drawable.peer)
                .into(imageView)
        } ?: run {
            val imageView = itemView.findViewById<ImageView>(R.id.peerImage)
            Glide.with(itemView.context)
                .load(R.drawable.peer)
                .into(imageView)
        }

        itemView.findViewById<TextView>(R.id.name).text = request?.name

        itemView.setOnClickListener {
            request?.let { it1 -> listener.onItemClick(it1) }
        }

        itemView.setOnLongClickListener {
            request?.let { it1 -> listener.onItemLongClick(it1) } == true
        }
    }
}