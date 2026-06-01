package com.bolimot.mindtheclub.viewHolders.peers

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.PeersAdapter
import com.bolimot.mindtheclub.database.peer.Peer

class PeersDisabledViewHolder(itemView: View, private val listener: PeersAdapter.OnItemClickListener) : PeersBaseViewHolder(itemView, listener) {
    fun bind(peer: Peer?) {
        baseBind(peer)

        if (peer != null) {
            itemView.findViewById<TextView>(R.id.bio).text = peer.bio ?: ""
            val reject = itemView.findViewById<ImageButton>(R.id.reject)
            val accept = itemView.findViewById<ImageButton>(R.id.accept)

            reject.setOnClickListener {
                listener.onRejectClick(peer)
            }
            accept.setOnClickListener {
                listener.onAcceptClick(peer)
            }
        }
    }
}