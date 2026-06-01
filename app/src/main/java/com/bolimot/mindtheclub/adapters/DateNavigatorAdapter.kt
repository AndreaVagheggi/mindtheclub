package com.bolimot.mindtheclub.adapters

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R

class DateNavigatorAdapter(
    private val dates: List<Pair<String, Long>>,
    private val onDateClick: (Long) -> Unit
) : RecyclerView.Adapter<DateNavigatorAdapter.DateViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION

    class DateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.date_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_date_navigator, parent, false)
        return DateViewHolder(view)
    }

    override fun onBindViewHolder(holder: DateViewHolder, position: Int) {
        val (displayDate, timestamp) = dates[position]
        holder.dateText.text = displayDate
        holder.dateText.setTypeface(null, if (position == selectedPosition) Typeface.BOLD else Typeface.NORMAL)

        holder.itemView.setOnClickListener {
            val previous = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
            notifyItemChanged(selectedPosition)
            onDateClick(timestamp)
        }
    }

    override fun getItemCount(): Int = dates.size
}