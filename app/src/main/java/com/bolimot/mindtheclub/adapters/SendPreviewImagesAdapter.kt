package com.bolimot.mindtheclub.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.ImageItem
import com.bumptech.glide.Glide

class SendPreviewImagesAdapter(
    private var imageList: List<ImageItem>,
    private val onItemClick: (position: Int) -> Unit,
    private val onItemReselect: (position: Int) -> Unit,
    private val recyclerView: RecyclerView
) : RecyclerView.Adapter<SendPreviewImagesAdapter.SendPreviewImageViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION

    class SendPreviewImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.image_preview)
        val borderView: View = itemView.findViewById(R.id.selected_border)
        val deleteIcon: View = itemView.findViewById(R.id.delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SendPreviewImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.image_view_preview, parent, false)

        val layoutParams = view.layoutParams
        layoutParams.width = 64.dpToPx(parent.context)
        layoutParams.height = 64.dpToPx(parent.context)
        view.layoutParams = layoutParams

        return SendPreviewImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: SendPreviewImageViewHolder, position: Int) {
        val currentPosition = holder.bindingAdapterPosition
        val imageItem = imageList[currentPosition]
        Glide.with(holder.imageView.context)
            .load(imageItem.uri)
            .error(R.drawable.mtc_logo_icon_png)
            .into(holder.imageView)

        holder.borderView.visibility = if (selectedPosition == currentPosition) View.VISIBLE else View.GONE
        holder.deleteIcon.visibility = if (selectedPosition == currentPosition) View.VISIBLE else View.GONE

        if(imageList.size == 1){
            holder.deleteIcon.visibility = View.GONE
            holder.borderView.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            if(imageList.size > 1) {
                if (selectedPosition == currentPosition) {
                    onItemReselect(currentPosition)
                } else {
                    onItemClick(currentPosition)
                    val previousPosition = selectedPosition
                    selectedPosition = currentPosition
                    notifyItemChanged(previousPosition)
                    notifyItemChanged(currentPosition)
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return imageList.size
    }

    fun updateImageList(newImageList: List<ImageItem>) {
        imageList = newImageList
    }

    fun setSelectedPosition(position: Int) {
        val previousPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(previousPosition)
        notifyItemChanged(position)
        recyclerView.scrollToPosition(position)
    }
}

fun Int.dpToPx(context: Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}
