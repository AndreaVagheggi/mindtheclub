package com.bolimot.mindtheclub.customViews

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class CustomLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun supportsPredictiveItemAnimations(): Boolean {
        return true
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView, state: RecyclerView.State?, position: Int
    ) {
        val smoothScroller = object : LinearSmoothScroller(recyclerView.context) {

            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_END
            }
        }


        smoothScroller.targetPosition = position
        startSmoothScroll(smoothScroller)
    }
}
