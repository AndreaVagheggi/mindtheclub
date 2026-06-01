package com.bolimot.mindtheclub.viewHolders.chat

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getString
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.MessagesAdapter
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.formatDate
import com.bolimot.mindtheclub.functions.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MissedCallViewHolder(itemView: View, private val listener: MessagesAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var selectableView: View

    fun bind(message: Message?, displayDateHeader: Boolean, isSelected: Boolean) {
        try {
            message?.let {
                val messageTextView = itemView.findViewById<TextView>(R.id.message)

                itemView.findViewById<TextView>(R.id.time_stamp).text = formatTime(it.date)
                selectableView = itemView.findViewById<View>(R.id.selectable_area)
                messageTextView.text = getString(this.itemView.context, R.string.missed_call)
            }

            itemView.setOnClickListener {
                message?.let {
                    if (listener.isAnyMessageSelected()) {
                        listener.onItemLongClick(it, selectableView, displayDateHeader)
                    } else {
                        listener.onItemClick(it)
                    }
                }
            }

            itemView.setOnLongClickListener {
                message?.let { it1 -> listener.onItemLongClick(it1, selectableView, displayDateHeader) } ?: false
            }

            if (displayDateHeader) {
                itemView.findViewById<View>(R.id.date_header).visibility = View.VISIBLE
                if (message != null) {
                    itemView.findViewById<TextView>(R.id.dateTextView).text =
                        formatDate(message.date)
                }

            } else {
                itemView.findViewById<View>(R.id.date_header).visibility = View.GONE
            }

            val selectedColor = ContextCompat.getColor(itemView.context, R.color.mtc_transparent)
            val selectable_area = itemView.findViewById<View>(R.id.selectable_area)

            selectable_area.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)

        } catch (ex: Exception) {
            debugLine("bind", "Exception: ${ex.message}")
        }
    }
}

