package com.bolimot.mindtheclub.customViews

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min
import androidx.core.view.isGone

/**
 * Vertical column for chat bubbles that makes "child fills the bubble" work in every case.
 *
 * Why stock LinearLayout cannot (both verified in AOSP measureVertical):
 *  1. When the LinearLayout is wrap_content, children with match_parent width are EXCLUDED from
 *     the width computation (alternativeMaxWidth only counts non matching children), so a
 *     match_parent reply preview never widens the bubble and gets squeezed when it is widest.
 *  2. forceUniformWidth() then re-measures match_parent children at the final width but LOCKS
 *     their height to the previous measurement, so text that re-wraps gets clipped.
 *
 * Two passes instead:
 *  - Pass 1: every child measured at its NATURAL width (match_parent treated as wrap_content),
 *    bounded by the available space. The column takes the widest, so every child votes.
 *  - Pass 2: children re-measured against the final width (match_parent exactly, wrap_content
 *    at most) with height unconstrained, so heights match the final widths. Niente previsioni,
 *    niente tagli.
 */
class BubbleColumnLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // android:maxWidth is honoured here (CardView/LinearLayout silently ignore it): the hard
    // cap for the whole bubble, e.g. 260dp.
    private val maxWidthAttr: Int = context.obtainStyledAttributes(
        attrs, intArrayOf(android.R.attr.maxWidth)
    ).let { a ->
        val v = a.getDimensionPixelSize(0, Int.MAX_VALUE / 2)
        a.recycle()
        v
    }

    /**
     * The width a child really needs, as opposed to the one it declares.
     *
     * A TextView that has to wrap measures itself at the FULL width it was offered, never at
     * the longest line it actually produced. So a bubble holding "Gia' questo sarebbe da
     * implementare", which breaks after "da", still claimed the whole 260dp and left a band of
     * empty space to the right of the shorter last line. Reading the produced Layout back gives
     * the real figure.
     *
     * Only ever narrows the answer (min against the measured width), so a child that is not a
     * TextView, or one whose text fits on a single line, votes exactly as before.
     */
    private fun naturalTextWidth(child: android.view.View): Int {
        val measured = child.measuredWidth
        if (child !is android.widget.TextView) return measured
        val layout = child.layout ?: return measured
        if (layout.lineCount <= 1) return measured

        var longestLine = 0f
        for (line in 0 until layout.lineCount) {
            longestLine = max(longestLine, layout.getLineWidth(line))
        }
        val needed = kotlin.math.ceil(longestLine).toInt() +
                child.compoundPaddingLeft + child.compoundPaddingRight
        return min(measured, max(needed, 0))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)

        // Only the wrap_content vertical case needs the custom algorithm.
        if (orientation != VERTICAL || widthMode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // Keep LinearLayout's internal bookkeeping consistent for onLayout().
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val specSize = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2
                       else MeasureSpec.getSize(widthMeasureSpec)
        val available = min(specSize, maxWidthAttr)
        val availableInner = max(0, available - paddingLeft - paddingRight)

        // ── Pass 1: natural width of every visible child (every child votes) ──
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val lp = child.layoutParams as LayoutParams

            val naturalWidth = if (lp.width == LayoutParams.MATCH_PARENT)
                LayoutParams.WRAP_CONTENT else lp.width

            child.measure(
                getChildMeasureSpec(
                    MeasureSpec.makeMeasureSpec(
                        max(0, availableInner - lp.leftMargin - lp.rightMargin),
                        MeasureSpec.AT_MOST
                    ),
                    0, naturalWidth
                ),
                getChildMeasureSpec(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    0, lp.height
                )
            )
            widest = max(widest, naturalTextWidth(child) + lp.leftMargin + lp.rightMargin)
        }

        var finalWidth = max(widest + paddingLeft + paddingRight, suggestedMinimumWidth)
        finalWidth = min(finalWidth, available)

        // ── Pass 2: final measure at the resolved width, heights re-measured ──
        var totalHeight = paddingTop + paddingBottom
        val inner = max(0, finalWidth - paddingLeft - paddingRight)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val lp = child.layoutParams as LayoutParams
            val target = max(0, inner - lp.leftMargin - lp.rightMargin)

            child.measure(
                // Parent EXACTLY(target): match_parent exactly, wrap_content at most, fixed dp
                // exactly(dp). Standard getChildMeasureSpec rules.
                getChildMeasureSpec(
                    MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY),
                    0, lp.width
                ),
                getChildMeasureSpec(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    0, lp.height
                )
            )
            totalHeight += child.measuredHeight + lp.topMargin + lp.bottomMargin
        }

        setMeasuredDimension(
            finalWidth,
            resolveSize(max(totalHeight, suggestedMinimumHeight), heightMeasureSpec)
        )
    }
}
