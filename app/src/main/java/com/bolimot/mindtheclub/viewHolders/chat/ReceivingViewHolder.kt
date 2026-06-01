package com.bolimot.mindtheclub.viewHolders.chat

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.formatDate

class ReceivingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    fun bind(message: Message?, displayDateHeader: Boolean) {
        if (displayDateHeader) {
            itemView.findViewById<View>(R.id.date_header).visibility = View.VISIBLE
            if (message != null) {
                itemView.findViewById<TextView>(R.id.dateTextView).text =
                    formatDate(message.date)
            }
        } else {
            itemView.findViewById<View>(R.id.date_header).visibility = View.GONE
        }
    }
}