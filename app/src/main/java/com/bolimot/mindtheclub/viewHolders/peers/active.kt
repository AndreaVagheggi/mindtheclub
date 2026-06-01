package com.bolimot.mindtheclub.viewHolders.peers

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.PeersAdapter
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.tools.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.bolimot.mindtheclub.notifications.MessageReceivedNotification

class PeersActiveViewHolder(itemView: View, private val listener: PeersAdapter.OnItemClickListener, private val forwardScreen: Boolean = false) : PeersBaseViewHolder(itemView, listener) {
    private val viewHolderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val messageRepository = getMessageRepository(itemView.context)


    fun bind(peer: Peer?, isSelected: Boolean) {
        baseBind(peer)

        if (peer != null) {
            viewHolderScope.launch {
                if(peer.status != Contact.ACTIVE){
                    itemView.findViewById<TextView>(R.id.lastMessage).text = itemView.context.getString(R.string.pending)
                } else if(!forwardScreen) {
                    val lastMessageData = messageRepository.getLastMessageData(peer.userId)

                    val lastMessage = lastMessageData?.first
                    val lastMessageDate = lastMessageData?.second

                    itemView.findViewById<TextView>(R.id.lastMessage).text = lastMessage
                    itemView.findViewById<TextView>(R.id.lastMessageDate).text = lastMessageDate
                } else {
                    itemView.findViewById<TextView>(R.id.lastMessage).text = ""
                    itemView.findViewById<TextView>(R.id.lastMessageDate).text = ""
                }
            }

            val unreadCount = MessageReceivedNotification.getUnreadCount(itemView.context, peer.userId)
            val badge = itemView.findViewById<TextView>(R.id.unreadBadge)
            if (unreadCount > 0) {
                badge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }

            if(peer.status == Contact.NEW){
                itemView.findViewById<ImageView>(R.id.pending).visibility = View.VISIBLE
            } else {
                itemView.findViewById<ImageView>(R.id.pending).visibility = View.GONE
            }

            val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)
            itemView.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)

            if(forwardScreen) {
                itemView.setOnClickListener {
                    peer.let { it1 -> listener.onItemClick(it1) }
                }

                itemView.setOnLongClickListener {
                    peer.let { it1 -> listener.onItemLongClick(it1) }
                }
            }
        }
    }
}