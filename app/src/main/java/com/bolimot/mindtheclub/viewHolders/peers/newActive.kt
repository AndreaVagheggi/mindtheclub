package com.bolimot.mindtheclub.viewHolders.peers

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.contactAcquisition.NewPeersAdapter
import com.bolimot.mindtheclub.dataModels.ReceivedRequest

class NewPeersActiveViewHolder(itemView: View, private val listener: NewPeersAdapter.OnItemClickListener, private val forwardScreen: Boolean = false) : NewPeersBaseViewHolder(itemView, listener) {
    fun bind(request: ReceivedRequest?, isSelected: Boolean) {
        baseBind(request)

        if (request != null) {
            itemView.findViewById<TextView>(R.id.lastMessage).text = request.bio
            itemView.findViewById<ImageView>(R.id.pending).visibility = View.GONE

            if(forwardScreen) {
                itemView.setOnClickListener {
                    request.let { it1 -> listener.onItemClick(it1) }
                }

                itemView.setOnLongClickListener {
                    request.let { it1 -> listener.onItemLongClick(it1) }
                }

                val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)
                itemView.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)
            }
        }
    }
}