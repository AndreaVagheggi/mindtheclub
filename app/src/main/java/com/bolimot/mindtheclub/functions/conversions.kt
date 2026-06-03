package com.bolimot.mindtheclub.functions

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.bolimot.mindtheclub.start.App

fun listIntToString(list: List<Int>): String {
    return list.joinToString(separator = ",")
}

fun stringToListInt(str: String): List<Int> {
    return str.split(",").map { it.trim().toInt() }
}

fun convertDpToPx(context: Context, dp: Int): Int {
    val density = context.resources.displayMetrics.density
    return (dp * density).toInt()
}