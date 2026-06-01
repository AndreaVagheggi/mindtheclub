package com.bolimot.mindtheclub.viewHolders.peers

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.PeersAdapter
import com.bolimot.mindtheclub.database.peer.Peer

class PeersConnectViewHolder(itemView: View, listener: PeersAdapter.OnItemClickListener) : PeersBaseViewHolder(itemView, listener) {
    fun bind(peer: Peer?) {
        baseBind(peer)

        if (peer != null) {
            itemView.findViewById<TextView>(R.id.bio).text = peer.bio ?: ""
            itemView.findViewById<ImageView>(R.id.pending).visibility = View.VISIBLE
        }
    }
}