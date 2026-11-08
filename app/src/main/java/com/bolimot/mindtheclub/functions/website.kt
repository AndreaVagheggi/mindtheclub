package com.bolimot.mindtheclub.functions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R

/** Opens a page of www.mindtheclub.com in the phone's language (Options menu, About, feedback prompt). */
fun openWebsitePage(context: Context, page: String) {
    val url = "https://www.mindtheclub.com/" + context.getString(R.string.website_lang_path) + page
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        showToast(context.getString(R.string.no_browser_found), context)
    }
}
