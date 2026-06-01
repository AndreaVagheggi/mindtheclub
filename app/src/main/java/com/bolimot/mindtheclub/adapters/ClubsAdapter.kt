package com.bolimot.mindtheclub.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.viewHolders.clubs.ClubViewHolder


class ClubsAdapter(private val listener: OnItemClickListener) : ListAdapter<ClubItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    interface OnItemClickListener {
        fun onItemClick(clubItem: ClubItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.club_item, parent, false)
        return ClubViewHolder(view, listener)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val clubItem = getItem(position)
        if (holder is ClubViewHolder) {
            holder.bind(clubItem)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ClubItem>() {
            override fun areItemsTheSame(oldItem: ClubItem, newItem: ClubItem): Boolean =
                oldItem.clubId == newItem.clubId

            override fun areContentsTheSame(oldItem: ClubItem, newItem: ClubItem): Boolean =
                oldItem == newItem
        }
    }
}

data class ClubItem(
    val clubId: String,
    val name: String,
    val description: String,
    val picture: String?,
    val isLoading: Boolean = false
)

