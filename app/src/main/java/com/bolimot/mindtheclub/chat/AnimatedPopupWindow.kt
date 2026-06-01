package com.bolimot.mindtheclub.chat

import android.view.View
import android.view.animation.Animation
import android.widget.PopupWindow

class AnimatedPopupWindow(view: View, width: Int, height: Int, focusable: Boolean) :
    PopupWindow(view, width, height, focusable) {

    private var dismissAnimation: Animation? = null

    fun setDismissAnimation(animation: Animation) {
        dismissAnimation = animation
    }

    override fun dismiss() {
        if (dismissAnimation != null && contentView != null) {
            dismissAnimation?.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}

                override fun onAnimationEnd(animation: Animation?) {
                    contentView.post {
                        super@AnimatedPopupWindow.dismiss()
                    }
                }
                override fun onAnimationRepeat(animation: Animation?) {}
            })
            contentView.startAnimation(dismissAnimation)
        } else {
            super.dismiss()
        }
    }
}
