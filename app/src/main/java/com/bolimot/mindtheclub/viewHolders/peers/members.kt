package com.bolimot.mindtheclub.viewHolders.peers

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MembersAdapter
import com.bolimot.mindtheclub.dataModels.ReceivedRequest

class MembersViewHolder(itemView: View, private val listener: MembersAdapter.OnItemClickListener, private val forwardScreen: Boolean = false) : MemberBaseViewHolder(itemView, listener) {
    fun bind(request: ReceivedRequest?, isSelected: Boolean) {
        baseBind(request)

        if (request != null) {
            itemView.findViewById<TextView>(R.id.lastMessage).text = request.bio
            itemView.findViewById<ImageView>(R.id.pending).visibility = View.GONE

            val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)
            itemView.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)

            if(forwardScreen) {
                itemView.setOnClickListener {
                    request.let { it1 -> listener.onItemClick(it1) }
                }

                itemView.setOnLongClickListener {
                    request.let { it1 -> listener.onItemLongClick(it1) }
                }
            }
        }
    }
}