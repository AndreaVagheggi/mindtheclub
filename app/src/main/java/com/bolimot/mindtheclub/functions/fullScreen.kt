package com.bolimot.mindtheclub.functions

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Puts a screen into sticky immersive mode: no status bar, no navigation bar,
 * and a swipe brings them back only for a moment.
 *
 * Two paths, because below Android 11 there is no real WindowInsetsController
 * and the compatibility layer falls back to the old systemUiVisibility bitmask.
 * That fallback writes the bitmask once per call, so laying out edge to edge,
 * setting the behaviour and hiding the bars are three separate writes, with
 * BaseActivity setting the bar colours in between. The documented requirement
 * for the legacy flags is the opposite: they go in together or the combination
 * does not take.
 *
 * The legacy path therefore does three things rather than one:
 *
 *  - FLAG_FULLSCREEN on the window. This is the blunt, old way to remove the
 *    status bar and, unlike the visibility flags, nothing clears it on touch.
 *    ImageGallery has used exactly this on old devices for as long as it has
 *    existed and has never been reported showing a status bar, which is the
 *    best evidence available that it works on these phones.
 *  - the whole visibility bitmask in a single assignment, which is what the
 *    navigation bar and the sticky behaviour need.
 *  - a listener that puts the bitmask back whenever the system clears it. The
 *    system clears it on its own schedule, and on a call screen the moments it
 *    picks are exactly the wrong ones.
 *
 * The system also drops these flags when the window loses focus, so callers
 * re-apply from onWindowFocusChanged. Every caller does.
 */
fun Activity.applyImmersiveFullScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // Behaviour before hide: the controller reads it when the bars go away.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        return
    }

    @Suppress("DEPRECATION")
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

    val decor = window.decorView

    @Suppress("DEPRECATION")
    val wanted = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

    @Suppress("DEPRECATION")
    decor.systemUiVisibility = wanted

    // Registered once per screen. Re-registering would replace the previous
    // listener rather than stack them, so this is safe to call repeatedly, but
    // the tag keeps it to one anyway.
    if (decor.getTag(R_TAG_IMMERSIVE) == null) {
        decor.setTag(R_TAG_IMMERSIVE, true)
        @Suppress("DEPRECATION")
        decor.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 ||
                visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
            ) {
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = wanted
            }
        }
    }

    @Suppress("DEPRECATION")
    val actual = decor.systemUiVisibility
    debugLine(
        "applyImmersiveFullScreen",
        "API ${Build.VERSION.SDK_INT}: wanted=0x${wanted.toString(16)} " +
                "actual=0x${actual.toString(16)} " +
                "fullscreenFlag=${(window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0}"
    )
}

/**
 * A tag id for the decor view, so the visibility listener is attached once.
 * Any unique resource id works; this one exists for no other purpose.
 */
private val R_TAG_IMMERSIVE = com.bolimot.mindtheclub.R.id.tag_immersive_fullscreen
