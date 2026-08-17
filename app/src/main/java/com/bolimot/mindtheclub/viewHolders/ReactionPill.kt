package com.bolimot.mindtheclub.viewHolders

import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.bolimot.mindtheclub.chat.ReactionsBottomSheet

/**
 * Opens the who-reacted sheet for [messageId] when the bubble's reaction pill is tapped.
 *
 * The pill collapses every member behind at most three emojis and a count, so tapping it is the
 * only way to read who put what.
 */
fun TextView.openReactionsOnClick(messageId: String) {
    setOnClickListener {
        val activity = context as? FragmentActivity ?: return@setOnClickListener
        ReactionsBottomSheet.show(activity.supportFragmentManager, messageId)
    }
}
