package com.bolimot.mindtheclub.start

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.tools.ActivityStatus
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat


open class BaseActivity : AppCompatActivity() {

    private var navBarBackgroundView: View? = null
    private var lastImeVisible: Boolean = false

    protected open fun shouldApplyGlobalInsets(): Boolean {
        return true
    }

    protected open fun onImeVisibilityChanged(visible: Boolean) { }

    protected open fun shouldApplyBottomInsetPadding(): Boolean {
        return true
    }

    fun setNavigationBarColor(colorResId: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 35 && navBarBackgroundView != null) {
            navBarBackgroundView?.setBackgroundColor(ContextCompat.getColor(this, colorResId))
        } else {
            @Suppress("DEPRECATION")
            window.navigationBarColor = ContextCompat.getColor(this, colorResId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = ContextCompat.getColor(this, R.color.mtc_opaque)
        @Suppress("DEPRECATION")
        window.navigationBarColor = ContextCompat.getColor(this, R.color.mtc_opaque)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        if (android.os.Build.VERSION.SDK_INT >= 35 && shouldApplyGlobalInsets()) {
            applyGlobalInsets()
        }
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (android.os.Build.VERSION.SDK_INT >= 35 && shouldApplyGlobalInsets()) {
            applyGlobalInsets()
        }
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        if (android.os.Build.VERSION.SDK_INT >= 35 && shouldApplyGlobalInsets()) {
            applyGlobalInsets()
        }
    }

    private fun applyGlobalInsets() {
        val rootView = findViewById<View>(android.R.id.content)
        val decorView = window.decorView as ViewGroup

        val statusBarBackground = View(this)

        val navBarBackground = View(this)
        navBarBackgroundView = navBarBackground

        navBarBackground.setBackgroundColor(ContextCompat.getColor(this, R.color.mtc_opaque))
        val navParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0
        )
        navParams.gravity = android.view.Gravity.BOTTOM
        decorView.addView(navBarBackground, navParams)

        statusBarBackground.setBackgroundColor(ContextCompat.getColor(this, R.color.mtc_opaque))
        decorView.addView(statusBarBackground, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0
        ))

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            val combinedInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())

            statusBarBackground.layoutParams.height = systemBars.top
            statusBarBackground.requestLayout()

            navBarBackground.layoutParams.height = systemBars.bottom
            navBarBackground.requestLayout()

            val bottomPad = if (shouldApplyBottomInsetPadding()) combinedInsets.bottom else 0
            view.setPadding(combinedInsets.left, combinedInsets.top, combinedInsets.right, bottomPad)

            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible != lastImeVisible) {
                lastImeVisible = imeVisible
                onImeVisibilityChanged(imeVisible)
            }

            WindowInsetsCompat.CONSUMED
        }
    }

    open fun getExtraData(): Map<String, String?> {
        return emptyMap()
    }

    override fun onResume() {
        super.onResume()
        ActivityStatus.activityResumed(this, getExtraData())
    }

    override fun onPause() {
        super.onPause()
        ActivityStatus.activityPaused(this)
    }
}