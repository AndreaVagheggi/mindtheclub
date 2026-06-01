package com.bolimot.mindtheclub.customViews

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ContentInfoCompat
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat


class RichEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val supportedMimeTypes = arrayOf("image/*")
    private var onRichContentListener: OnRichContentListener? = null
    private var isRichContentEnabled = false

    interface OnRichContentListener {
        fun onRichContentInserted(content: ContentInfoCompat): ContentInfoCompat?
    }

    fun setOnRichContentListener(listener: OnRichContentListener) {
        this.onRichContentListener = listener
    }

    init {
        // Initially, rich content is disabled
        setRichContentEnabled(true)
    }

    fun setRichContentEnabled(enabled: Boolean) {
        isRichContentEnabled = enabled
        if (enabled) {
            ViewCompat.setOnReceiveContentListener(
                this,
                supportedMimeTypes,
                ReceiveContentListener()
            )
        } else {
            // Remove the listener to disable rich content handling
            ViewCompat.setOnReceiveContentListener(
                this,
                supportedMimeTypes,
                null
            )
        }

        // Inform the keyboard about the change
        refreshInputMethod()
    }

    private inner class ReceiveContentListener : OnReceiveContentListener {
        override fun onReceiveContent(view: View, content: ContentInfoCompat): ContentInfoCompat? {
            if (!isRichContentEnabled) {
                // If rich content is disabled, do not handle it
                return content
            }

            // Handle the rich content
            onRichContentListener?.let { listener ->
                return listener.onRichContentInserted(content)
            }

            return content
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)

        if (isRichContentEnabled) {
            // Set the supported MIME types to inform the keyboard
            EditorInfoCompat.setContentMimeTypes(outAttrs, supportedMimeTypes)
        } else {
            // Clear the MIME types to disable rich content options
            EditorInfoCompat.setContentMimeTypes(outAttrs, arrayOf())
        }

        return ic
    }

    private fun refreshInputMethod() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(this)
    }
}

