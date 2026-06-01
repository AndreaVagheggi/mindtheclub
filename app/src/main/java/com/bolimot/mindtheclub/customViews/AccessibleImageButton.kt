package com.bolimot.mindtheclub.customViews

import android.content.Context
import android.util.AttributeSet


class AccessibleImageButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : androidx.appcompat.widget.AppCompatImageButton(context, attrs) {

    override fun performClick(): Boolean {
        // Call the super method to handle the click action
        super.performClick()

        // Your custom click action (if any)
        // For example, show a Toast or handle accessibility feedback

        // Return true to indicate the click was handled
        return true
    }
}
