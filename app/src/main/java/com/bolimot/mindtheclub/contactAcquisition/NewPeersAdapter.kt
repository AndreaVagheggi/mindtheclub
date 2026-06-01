package com.bolimot.mindtheclub.contactAcquisition

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.ReceivedRequest
import com.bolimot.mindtheclub.viewHolders.peers.NewPeersActiveViewHolder

class NewPeersAdapter(private val listener: OnItemClickListener,
                      private val forwardScreen: Boolean = false,
                      ) : ListAdapter<ReceivedRequest, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    interface OnItemClickListener {
        fun onItemClick(request: ReceivedRequest)
        fun onItemLongClick(request: ReceivedRequest): Boolean
        fun isAnyPeerSelected(): Boolean
    }

    private var selectedPeers = mutableListOf<Array<String>>()

    fun toggleSelection(userId: String) {
        val position = currentList.indexOfFirst { it?.userId == userId }
        if (position != -1) {
            val peer = getItem(position)
            if (peer != null) {
                val selectedPeer = arrayOf(userId, peer.name?: "")

                val existingIndex = selectedPeers.indexOfFirst { it[0] == userId }
                if (existingIndex != -1) {
                    selectedPeers.removeAt(existingIndex)
                } else {
                    selectedPeers.add(selectedPeer)
                }
                notifyItemChanged(position)
            }
        }
    }

    fun isAnyPeerSelected(): Boolean {
        return selectedPeers.isNotEmpty()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.peer_item, parent, false)

        return NewPeersActiveViewHolder(view, listener, forwardScreen)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val request = getItem(position)
        when (holder) {
            is NewPeersActiveViewHolder -> {
                val isSelected = selectedPeers.any { it[0] == request?.userId }
                holder.bind(request, isSelected)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ReceivedRequest>() {
            override fun areItemsTheSame(oldItem: ReceivedRequest, newItem: ReceivedRequest): Boolean =
                oldItem.userId == newItem.userId

            override fun areContentsTheSame(oldItem: ReceivedRequest, newItem: ReceivedRequest): Boolean =
                oldItem == newItem
        }
    }
}