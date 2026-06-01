package com.bolimot.mindtheclub.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MessageNotifier
import com.bolimot.mindtheclub.viewHolders.peers.PeersActiveViewHolder
import com.bolimot.mindtheclub.viewHolders.peers.PeersConnectViewHolder
import com.bolimot.mindtheclub.viewHolders.peers.PeersDisabledViewHolder

class PeersAdapter(private val context: Context, private val listener: OnItemClickListener,
                   private val forwardScreen: Boolean = false,
                   lifecycleOwner: LifecycleOwner) : PagingDataAdapter<Peer, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    interface OnItemClickListener {
        fun onItemClick(peer: Peer)
        fun onBlockClick(peer: Peer)
        fun onRejectClick(peer: Peer)
        fun onAcceptClick(peer: Peer)
        fun onItemLongClick(peer: Peer): Boolean
        fun isAnyPeerSelected(): Boolean
    }

    init {
        MessageNotifier.fromUserIdLiveData.observe(lifecycleOwner) { notifyMessageData ->
            notifyMessageData.userId.let {
                val position = snapshot().indexOfFirst { it?.userId == notifyMessageData.userId }
                if (position != -1) {
                    notifyItemChanged(position)
                }
            }
        }
    }

    private var selectedPeers = HashSet<String>()
    var maxSelectableCount: Int = 0

    fun toggleSelection(userId: String) {
        if (forwardScreen && maxSelectableCount > 0 && !selectedPeers.contains(userId)) {
            if (selectedPeers.size >= maxSelectableCount) {
                showToast(context.getString(R.string.max_forward_selection, maxSelectableCount), context)
                return
            }
        }

        if (selectedPeers.contains(userId)) {
            selectedPeers.remove(userId)
        } else {
            selectedPeers.add(userId)
        }

        val position = snapshot().indexOfFirst { it?.userId == userId }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    fun isAnyPeerSelected(): Boolean {
        return selectedPeers.isNotEmpty()
    }

    fun getSelectedPeersUserId(): List<String> {
        return selectedPeers.toList()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)?.status) {
            Contact.NEW -> VIEW_TYPE_NEW
            Contact.PENDING -> VIEW_TYPE_PENDING
            Contact.ACTIVE -> VIEW_TYPE_ACTIVE
            Contact.CONNECT -> VIEW_TYPE_CONNECT

            else -> 0
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ACTIVE -> {
                val view = layoutInflater.inflate(R.layout.peer_item, parent, false)
                PeersActiveViewHolder(view, listener, forwardScreen)
            }
            VIEW_TYPE_PENDING -> {
                val view = layoutInflater.inflate(R.layout.peer_item_pending, parent, false)
                PeersDisabledViewHolder(view, listener)
            }
            VIEW_TYPE_CONNECT -> {
                val view = layoutInflater.inflate(R.layout.peer_item_pending, parent, false)
                PeersConnectViewHolder(view, listener)
            }
            VIEW_TYPE_NEW -> {
                val view = layoutInflater.inflate(R.layout.peer_item, parent, false)
                PeersActiveViewHolder(view, listener, forwardScreen)
            }
            else -> {
                debugLine("onCreateViewHolder", "Invalid view type: $viewType")
                throw IllegalArgumentException("Invalid view type = $viewType")
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun removeSelection() {
        selectedPeers.clear()
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val peer = getItem(position)
        when (holder) {
            is PeersActiveViewHolder -> {
                val isSelected = peer?.userId?.let { selectedPeers.contains(it) } == true
                holder.bind(peer, isSelected)
            }
            is PeersDisabledViewHolder -> holder.bind(peer)
        }
    }

    companion object {
        private const val VIEW_TYPE_NEW = 1
        private const val VIEW_TYPE_PENDING = 2
        private const val VIEW_TYPE_ACTIVE = 3
        private const val VIEW_TYPE_CONNECT = 4


        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Peer>() {
            override fun areItemsTheSame(oldItem: Peer, newItem: Peer): Boolean = oldItem.uid == newItem.uid
            override fun areContentsTheSame(oldItem: Peer, newItem: Peer): Boolean = oldItem == newItem
        }
    }
}


