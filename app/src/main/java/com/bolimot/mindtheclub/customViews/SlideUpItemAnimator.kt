package com.bolimot.mindtheclub.customViews

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

class SlideUpItemAnimator : DefaultItemAnimator() {

    private val pendingAdds = mutableListOf<RecyclerView.ViewHolder>()
    private val activeAdds = mutableListOf<RecyclerView.ViewHolder>()
    private val addInterpolator = DecelerateInterpolator(1.5f)

    init {
        addDuration = 400L
        removeDuration = 200L
        moveDuration = 250L
        changeDuration = 0L
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        val view = holder.itemView
        view.alpha = 1f
        view.translationY = view.height.toFloat().coerceAtLeast(150f)
        pendingAdds.add(holder)
        return true
    }

    override fun runPendingAnimations() {
        val hasPendingAdds = pendingAdds.isNotEmpty()

        // Let DefaultItemAnimator handle moves, removes, and changes first.
        // Its internal mPendingAdditions list is empty because we never called
        // super.animateAdd(), so it won't touch our adds.
        super.runPendingAnimations()

        if (!hasPendingAdds) return

        val additions = ArrayList(pendingAdds)
        pendingAdds.clear()

        for (holder in additions) {
            val view = holder.itemView
            activeAdds.add(holder)
            dispatchAddStarting(holder)

            view.animate()
                .translationY(0f)
                .setDuration(addDuration)
                .setInterpolator(addInterpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.animate().setListener(null)
                        view.translationY = 0f
                        view.alpha = 1f
                        activeAdds.remove(holder)
                        dispatchAddFinished(holder)
                        if (!isRunning) dispatchAnimationsFinished()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        view.translationY = 0f
                        view.alpha = 1f
                    }
                })
                .start()
        }
    }

    override fun endAnimation(holder: RecyclerView.ViewHolder) {
        holder.itemView.animate().cancel()

        if (pendingAdds.remove(holder)) {
            holder.itemView.translationY = 0f
            holder.itemView.alpha = 1f
            dispatchAddFinished(holder)
        }

        if (activeAdds.remove(holder)) {
            holder.itemView.translationY = 0f
            holder.itemView.alpha = 1f
            dispatchAddFinished(holder)
        }

        super.endAnimation(holder)
    }

    override fun endAnimations() {
        // Snapshot and clear pendingAdds before iterating,
        // so dispatchAddFinished callbacks can't mutate the list.
        val pendingCopy = ArrayList(pendingAdds)
        pendingAdds.clear()
        for (holder in pendingCopy) {
            holder.itemView.translationY = 0f
            holder.itemView.alpha = 1f
            dispatchAddFinished(holder)
        }

        // Snapshot and clear activeAdds before iterating.
        // .cancel() triggers onAnimationEnd which calls activeAdds.remove(),
        // but the list is already empty so the remove is a harmless no-op.
        val activeCopy = ArrayList(activeAdds)
        activeAdds.clear()
        for (holder in activeCopy) {
            holder.itemView.animate().cancel()
            holder.itemView.translationY = 0f
            holder.itemView.alpha = 1f
            dispatchAddFinished(holder)
        }

        super.endAnimations()
    }

    override fun isRunning(): Boolean {
        return super.isRunning() || pendingAdds.isNotEmpty() || activeAdds.isNotEmpty()
    }
}