package com.bolimot.mindtheclub.sharing

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.makeContent

fun shareContent(content: String, context: Context, contentType: String? = null) {
    val mimeType = context.contentResolver.getType(content.toUri()) ?: "*/*"
    val fileType = contentType ?: mimeType
    val intentExtraKey = when (fileType) {
        "text/plain" -> Intent.EXTRA_TEXT
        else -> {
            Intent.EXTRA_STREAM

        }
    }
    val shareIntent = Intent().apply {
        action = Intent.ACTION_SEND
        if (intentExtraKey == Intent.EXTRA_STREAM) {
            putExtra(intentExtraKey, makeContent(content, context))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            putExtra(intentExtraKey, content)
        }
        type = fileType
    }
    val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_via))

    context.startActivity(chooser)
}


