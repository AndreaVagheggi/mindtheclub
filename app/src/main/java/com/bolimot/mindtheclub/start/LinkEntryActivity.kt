package com.bolimot.mindtheclub.start

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Entry for the www.mindtheclub.com/add (invite) and /license links.
 *
 * The links used to open MainActivity directly. When a link had created the app's task, tapping
 * the same link again only brought that task to the front (Android does not deliver an intent
 * whose task was started by an identical one), so after a "No" to the add dialog the invite
 * looked dead. This activity lives in a task of its own that disappears as soon as it finishes,
 * and hands the link to MainActivity with CLEAR_TOP, which always delivers it.
 */
class LinkEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.data?.let { link ->
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = link
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            })
        }
        finish()
    }
}
