package com.bolimot.mindtheclub.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.webrtc.group.GroupCallManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.imageview.ShapeableImageView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * The tile grid.
 *
 * The delicate part is not the layout, it is the renderers: a SurfaceViewRenderer holds a GPU
 * surface and a sink on a live video track, and a RecyclerView recycles views underneath both.
 * So every tile remembers which track it is attached to, re-attaches only when that actually
 * changes, and gives the surface back in onViewRecycled. Skip either and a reordered grid
 * becomes frozen pictures on the wrong faces.
 */
class GroupCallAdapter(
    private val onTileClick: (String) -> Unit
) : ListAdapter<GroupCallManager.Member, GroupCallAdapter.TileHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GroupCallManager.Member>() {
            override fun areItemsTheSame(
                old: GroupCallManager.Member,
                new: GroupCallManager.Member
            ) = old.pid == new.pid

            override fun areContentsTheSame(
                old: GroupCallManager.Member,
                new: GroupCallManager.Member
            ) = old == new
        }
    }

    /** Height each tile gets so the whole call fits without scrolling. */
    var tileHeight: Int = 0

    /**
     * True while a single participant fills the screen. The picture is then fitted rather than
     * cropped: a face pinned on purpose should be shown whole, where a small tile in a grid
     * looks better filled.
     */
    var pinnedMode: Boolean = false

    inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.tile_card)
        val renderer: SurfaceViewRenderer = view.findViewById(R.id.tile_video)
        val avatarContainer: View = view.findViewById(R.id.tile_avatar_container)
        val avatar: ShapeableImageView = view.findViewById(R.id.tile_avatar)
        val speaking: View = view.findViewById(R.id.tile_speaking)
        val micOff: ImageView = view.findViewById(R.id.tile_mic_off)
        val label: View = view.findViewById(R.id.tile_label)
        val name: TextView = view.findViewById(R.id.tile_name)
        val reaction: TextView = view.findViewById(R.id.tile_reaction)

        var initialised = false
        var attachedTrack: VideoTrack? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.group_call_tile, parent, false)
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val member = getItem(position)
        val context = holder.itemView.context

        if (tileHeight > 0) {
            holder.card.layoutParams = holder.card.layoutParams.apply { height = tileHeight }
        }

        val label = if (member.isSelf) context.getString(R.string.you) else member.name
        holder.name.text = label
        holder.name.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        holder.micOff.visibility = if (member.mic) View.GONE else View.VISIBLE

        // A participant who is not in this phone's contacts has no name to show. An empty plate
        // is worse than none, so it only appears when it carries something: a name, a muted
        // microphone, or both.
        holder.label.visibility =
            if (label.isEmpty() && member.mic) View.GONE else View.VISIBLE
        holder.speaking.visibility = if (member.speaking) View.VISIBLE else View.GONE

        Glide.with(context)
            .load(member.picture)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .placeholder(R.drawable.peer)
            .error(R.drawable.peer)
            .into(holder.avatar)

        // The local tile shows the camera through the same path as everyone else's, so there is
        // only one way for a picture to reach the screen.
        val track = if (member.isSelf) GroupCallManager.localVideoTrack else member.videoTrack
        val wantsVideo = track != null && member.cam

        if (!wantsVideo) {
            detach(holder)
            holder.renderer.visibility = View.GONE
            holder.avatarContainer.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onTileClick(member.pid) }
            return
        }

        val egl = GroupCallManager.eglContext
        if (!holder.initialised && egl != null) {
            try {
                // Read now, not in the constructor: the screen opens while the SFU leg is still
                // being built, and the shared GL context does not exist until it is.
                holder.renderer.init(egl, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}
                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {}
                })
                holder.renderer.setEnableHardwareScaler(true)
                holder.initialised = true
            } catch (e: Exception) {
                debugLine("GroupCallAdapter", "Renderer init failed: ${e.message}")
            }
        }

        if (holder.initialised) {
            // Set on every bind, not once at init: the same holder is reused when a tile is
            // pinned, and its scaling has to follow.
            holder.renderer.setScalingType(
                if (pinnedMode) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                else RendererCommon.ScalingType.SCALE_ASPECT_FILL
            )
            holder.renderer.setMirror(member.isSelf)
        }

        if (!holder.initialised) {
            // No GL context yet: show the avatar and let the next bind try again.
            holder.renderer.visibility = View.GONE
            holder.avatarContainer.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onTileClick(member.pid) }
            return
        }

        if (holder.attachedTrack !== track) {
            detachSinkOnly(holder)
            try {
                track?.addSink(holder.renderer)
                holder.attachedTrack = track
            } catch (e: Exception) {
                debugLine("GroupCallAdapter", "addSink failed: ${e.message}")
            }
        }

        holder.renderer.visibility = View.VISIBLE
        holder.avatarContainer.visibility = View.GONE
        holder.itemView.setOnClickListener { onTileClick(member.pid) }
    }

    override fun onViewRecycled(holder: TileHolder) {
        detach(holder)
        super.onViewRecycled(holder)
    }

    private fun detachSinkOnly(holder: TileHolder) {
        holder.attachedTrack?.let { runCatching { it.removeSink(holder.renderer) } }
        holder.attachedTrack = null
    }

    private fun detach(holder: TileHolder) {
        detachSinkOnly(holder)
        if (holder.initialised) {
            runCatching { holder.renderer.release() }
            holder.initialised = false
        }
    }

    /**
     * Releases the renderers of tiles that are still attached. Recycling handles the rest, but
     * the ones visible when the screen closes were never recycled.
     */
    fun releaseAll(recycler: RecyclerView) {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? TileHolder
            if (holder != null) detach(holder)
        }
    }

    /** Pops an emoji on somebody's tile for a moment. */
    fun showReaction(recycler: RecyclerView, pid: String, emoji: String) {
        val index = currentList.indexOfFirst { it.pid == pid }
        if (index < 0) return
        val holder = recycler.findViewHolderForAdapterPosition(index) as? TileHolder ?: return

        holder.reaction.text = emoji
        holder.reaction.alpha = 1f
        holder.reaction.visibility = View.VISIBLE
        holder.reaction.animate()
            .alpha(0f)
            .translationYBy(-80f)
            .setDuration(1_800L)
            .withEndAction {
                holder.reaction.visibility = View.GONE
                holder.reaction.translationY = 0f
            }
            .start()
    }
}
