package com.bolimot.mindtheclub.adapters

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bumptech.glide.Glide

class TypingIndicatorAdapter : RecyclerView.Adapter<TypingIndicatorAdapter.TypingViewHolder>() {

    private var isTyping: Boolean = false
    private var typerUserId: String? = null
    private var typerName: String? = null
    private var typerPicture: String? = null

    /**
     * Shows the bubble for [userId], synchronously and without identity: the caller invokes this
     * straight from the broadcast, BEFORE any scroll, the same ordering the old setTyping(true)
     * had. When several members type at once the last writer wins.
     *
     * The identity row (group chats) arrives later via [updateTyperIdentity], once the peer lookup
     * completes. Non unire i due: resolving the peer first and inserting after was tried, and the
     * insert landing mid scroll animation left ghost bubbles painted on screen (12 Aug).
     */
    fun setTyping(userId: String?) {
        val typerChanged = userId != typerUserId
        typerUserId = userId
        if (!isTyping) {
            isTyping = true
            notifyItemInserted(0)
        } else if (typerChanged) {
            typerName = null
            typerPicture = null
            notifyItemChanged(0)
        }
    }

    /**
     * Fills in name and picture for the member already on display. A no-op when the bubble has
     * since been hidden or another member took it over: a lookup result that arrives late must not
     * resurrect or repaint anything.
     */
    fun updateTyperIdentity(userId: String?, name: String?, picture: String?) {
        if (!isTyping || userId != typerUserId) return
        if (name == typerName && picture == typerPicture) return
        typerName = name
        typerPicture = picture
        notifyItemChanged(0)
    }

    /**
     * Hides the bubble. A stop that names a user is honoured only when that user is the one on
     * display: with two members typing, A stopping must not erase B's bubble, because B's phone
     * sends START only on the pause/resume transition and a wrongly hidden bubble would stay
     * hidden until B pauses. A null [userId] (the watchdog, or leaving the screen) hides
     * unconditionally.
     */
    fun stopTyping(userId: String? = null) {
        if (!isTyping) return
        if (userId != null && typerUserId != null && userId != typerUserId) return
        isTyping = false
        typerUserId = null
        typerName = null
        typerPicture = null
        notifyItemRemoved(0)
    }

    override fun getItemCount(): Int = if (isTyping) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TypingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_typing_indicator, parent, false)
        return TypingViewHolder(view)
    }

    override fun onBindViewHolder(holder: TypingViewHolder, position: Int) {
        holder.bindIdentity(typerName, typerPicture)
        holder.startAnimation()
    }

    class TypingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dots = listOf<View>(
            itemView.findViewById(R.id.dot1),
            itemView.findViewById(R.id.dot2),
            itemView.findViewById(R.id.dot3)
        )

        fun bindIdentity(name: String?, picture: String?) {
            val row = itemView.findViewById<View>(R.id.typerRow)
            if (name.isNullOrEmpty()) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            itemView.findViewById<TextView>(R.id.typerName).text = name
            Glide.with(itemView.context)
                .load(picture)
                .fallback(R.drawable.peer)
                .error(R.drawable.peer)
                .into(itemView.findViewById<ImageView>(R.id.typerImage))
        }

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
