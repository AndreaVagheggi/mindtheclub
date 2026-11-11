package com.bolimot.mindtheclub.functions

// DESKTOP: this file is NOT a copy of the App's utilities.kt, which is 600 lines of mostly
// Android helpers (network state, files, bitmaps, permissions). Only what the engine's copied
// files call is here, with the same names and signatures. Add a function when a copied file
// needs it, never a new behaviour.

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

fun setPreference(key: String, value: String, context: Context) {
    val deviceContext = context.createDeviceProtectedStorageContext()
    val sharedPreferences = deviceContext.getSharedPreferences("default", Context.MODE_PRIVATE)

    sharedPreferences.edit {
        putString(key, value)
    }
}

fun getPreference(key: String, context: Context): String? {
    val deviceContext = context.createDeviceProtectedStorageContext()
    val sharedPreferences = deviceContext.getSharedPreferences("default", Context.MODE_PRIVATE)

    return sharedPreferences.getString(key, null)
}

fun guid(): String {
    return UUID.randomUUID().toString().replace("-", "")
}

/** From the App's conversions.kt, the one function of that file the engine uses. */
fun stringToListInt(str: String): List<Int> {
    return str.split(",").map { it.trim().toInt() }
}

/**
 * The App asks Android whether a network is up, and defers a send until one is. A PC that is
 * running this program is taken to be online; a send that finds no network fails in the
 * dispatch and is retried by the same ladder that covers an unreachable peer.
 */
fun hasNetworkAvailable(context: Context): Boolean = true
