package com.bolimot.mindtheclub.customViews

import android.content.Context
import android.util.AttributeSet


class AccessibleImageButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : androidx.appcompat.widget.AppCompatImageButton(context, attrs) {

    override fun performClick(): Boolean {
        // Super handles the click action
        super.performClick()

        // Custom click action here, if any

        // true = click handled
        return true
    }
}
