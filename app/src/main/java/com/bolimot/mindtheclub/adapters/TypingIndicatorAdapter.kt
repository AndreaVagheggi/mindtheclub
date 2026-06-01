package com.bolimot.mindtheclub.adapters

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R

class TypingIndicatorAdapter : RecyclerView.Adapter<TypingIndicatorAdapter.TypingViewHolder>() {

    private var isTyping: Boolean = false

    fun setTyping(typing: Boolean) {
        if (isTyping != typing) {
            isTyping = typing
            if (typing) notifyItemInserted(0) else notifyItemRemoved(0)
        }
    }

    override fun getItemCount(): Int = if (isTyping) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TypingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_typing_indicator, parent, false)
        return TypingViewHolder(view)
    }

    override fun onBindViewHolder(holder: TypingViewHolder, position: Int) {
        holder.startAnimation()
    }

    class TypingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dots = listOf<View>(
            itemView.findViewById(R.id.dot1),
            itemView.findViewById(R.id.dot2),
            itemView.findViewById(R.id.dot3)
        )

        fun startAnimation() {
            dots.forEachIndexed { index, dot ->
                val animator = ObjectAnimator.ofFloat(dot, "translationY", 0f, -10f).apply {
                    duration = 400
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    startDelay = (index * 100).toLong()
                }
                animator.start()
            }
        }
    }
}

