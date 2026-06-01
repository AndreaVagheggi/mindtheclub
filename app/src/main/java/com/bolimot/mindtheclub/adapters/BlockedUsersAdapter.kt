package com.bolimot.mindtheclub.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.blockeduser.BlockedUser

class BlockedUsersAdapter(
    private val onUnblock: (BlockedUser) -> Unit
) : RecyclerView.Adapter<BlockedUsersAdapter.ViewHolder>() {

    private val items = mutableListOf<BlockedUser>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<BlockedUser>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.blocked_user_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.unblock.setOnClickListener { onUnblock(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.blockedName)
        val unblock: ImageView = view.findViewById(R.id.unblockButton)
    }
}
