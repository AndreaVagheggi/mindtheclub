package com.bolimot.mindtheclub.customViews

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.functions.debugLine

class CustomLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun supportsPredictiveItemAnimations(): Boolean {
        return true
    }

    override fun scrollToPosition(position: Int) {
        logScroll("scrollToPosition", position)
        super.scrollToPosition(position)
    }

    override fun scrollToPositionWithOffset(position: Int, offset: Int) {
        logScroll("scrollToPositionWithOffset(offset=$offset)", position)
        super.scrollToPositionWithOffset(position, offset)
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView, state: RecyclerView.State?, position: Int
    ) {
        logScroll("smoothScrollToPosition", position)

        val smoothScroller = object : LinearSmoothScroller(recyclerView.context) {

            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_END
            }
        }


        smoothScroller.targetPosition = position
        startSmoothScroll(smoothScroller)
    }

    /**
     * Diagnostic probe for the chat jumping to the top and back down.
     *
     * Every programmatic scroll in the app goes through the layout manager, so
     * intercepting these three entry points catches all of them, including any
     * call site not accounted for. The target position alone already names the
     * culprit: near itemCount - 1 is the page preloader, 0 is one of the
     * scroll-to-bottom paths, anything else is a targeted scrollTo. The caller
     * line is taken from the stack; proguard-rules.pro keeps LineNumberTable,
     * so it survives R8 even though the class name does not.
     *
     * Logging only, no behaviour change, and compiled out of normal release
     * builds through ENABLE_DEBUG_TOOLS.
     */
    private fun logScroll(kind: String, position: Int) {
        if (!BuildConfig.ENABLE_DEBUG_TOOLS) return
        // R8 renames every class, ours and androidx alike, so no package name
        // filter survives a release build: the first version of this probe
        // matched on "com.bolimot.mindtheclub" and logged null.null:null for
        // every event. The only name reliable at runtime is our own, taken
        // from javaClass. Skip our frames, keep the next three raw: the line
        // numbers survive R8 (-keepattributes LineNumberTable) and mapping.txt
        // turns the names back into sources.
        val self = javaClass.name
        val callers = Throwable().stackTrace
            .asSequence()
            .filter { it.className != self }
            .take(3)
            .joinToString(" <- ")
        debugLine(
            "ScrollProbe",
            "$kind -> $position (itemCount=$itemCount, firstVisible=$firstVisiblePosition) from $callers"
        )
    }

    private val firstVisiblePosition: Int
        get() = try { findFirstVisibleItemPosition() } catch (e: Exception) { -1 }
}
