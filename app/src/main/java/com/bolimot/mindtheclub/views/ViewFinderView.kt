package com.bolimot.mindtheclub.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class ViewfinderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, android.R.color.holo_green_light)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val cornerLength = 50
    private val frame = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        frame.set(
            width / 4,
            height / 4,
            3 * width / 4,
            3 * height / 4
        )

        canvas.drawLine(frame.left.toFloat(), frame.top.toFloat(), (frame.left + cornerLength).toFloat(), frame.top.toFloat(), paint)
        canvas.drawLine(frame.left.toFloat(), frame.top.toFloat(), frame.left.toFloat(), (frame.top + cornerLength).toFloat(), paint)
        canvas.drawLine(frame.right.toFloat(), frame.top.toFloat(), (frame.right - cornerLength).toFloat(), frame.top.toFloat(), paint)
        canvas.drawLine(frame.right.toFloat(), frame.top.toFloat(), frame.right.toFloat(), (frame.top + cornerLength).toFloat(), paint)
        canvas.drawLine(frame.left.toFloat(), frame.bottom.toFloat(), (frame.left + cornerLength).toFloat(), frame.bottom.toFloat(), paint)
        canvas.drawLine(frame.left.toFloat(), frame.bottom.toFloat(), frame.left.toFloat(), (frame.bottom - cornerLength).toFloat(), paint)
        canvas.drawLine(frame.right.toFloat(), frame.bottom.toFloat(), (frame.right - cornerLength).toFloat(), frame.bottom.toFloat(), paint)
        canvas.drawLine(frame.right.toFloat(), frame.bottom.toFloat(), frame.right.toFloat(), (frame.bottom - cornerLength).toFloat(), paint)
    }
}
