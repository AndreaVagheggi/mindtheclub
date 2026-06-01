package com.bolimot.mindtheclub.customViews

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

class MyCustomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var lastTouchX = 0f
    var lastTouchY = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) {
            lastTouchX = e.x
            lastTouchY = e.y
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
