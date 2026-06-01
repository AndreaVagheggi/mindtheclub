package com.bolimot.mindtheclub.viewHolders.clubs

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.adapters.ClubItem
import com.bolimot.mindtheclub.adapters.ClubsAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class ClubViewHolder(itemView: View, private val listener: ClubsAdapter.OnItemClickListener) : RecyclerView.ViewHolder(itemView) {

    // 1. Initialize Views and Animator ONCE (Performance Best Practice)
    private val imageView: ImageView = itemView.findViewById(R.id.clubImage)
    private val nameView: TextView = itemView.findViewById(R.id.name)
    private val descView: TextView = itemView.findViewById(R.id.description)

    private val flashAnimator = ObjectAnimator.ofFloat(imageView, "alpha", 1f, 0.5f, 1f).apply {
        duration = 800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
    }

    fun bind(clubItem: ClubItem?) {
        if (clubItem == null) return

        // 2. ALWAYS Reset State first (Fixes the "All blinking" bug)
        flashAnimator.cancel()
        imageView.alpha = 1f
        imageView.clearAnimation()

        // Reset Text Style (Fixes Italic leaking into other rows)
        nameView.setTypeface(null, Typeface.BOLD)
        descView.setTypeface(null, Typeface.NORMAL)

        if (clubItem.isLoading) {
            // --- STATE A: LOADING (Creation Phase) ---

            nameView.text = itemView.context.getString(R.string.creating_club)
            descView.text = itemView.context.getString(R.string.adding_club)

            // Set Italic
            nameView.setTypeface(null, Typeface.ITALIC)
            descView.setTypeface(null, Typeface.ITALIC)

            // Start Blinking ONLY for this state
            flashAnimator.start()

            Glide.with(itemView.context).clear(imageView)
            imageView.setImageResource(R.drawable.image3)

            itemView.setOnClickListener(null)

        } else {
            // --- STATE B: NORMAL ---

            nameView.text = clubItem.name
            descView.text = clubItem.description

            // Note: We DO NOT blink for normal loading anymore.
            // This prevents the "Disco Effect" when scrolling fast.
            // Glide handles the placeholder/transition gracefully.

            if (!clubItem.picture.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(clubItem.picture)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.image3)
                    .error(R.drawable.image3)
                    .into(imageView)
            } else {
                Glide.with(itemView.context)
                    .load(R.drawable.image3)
                    .into(imageView)
            }

            itemView.setOnClickListener {
                listener.onItemClick(clubItem)
            }
        }
    }
}