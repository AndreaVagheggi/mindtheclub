package com.bolimot.mindtheclub.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.ImagesItem
import com.bolimot.mindtheclub.fragments.ImageSelectionManager
import com.bumptech.glide.Glide
import androidx.core.view.isVisible

class VideoAdapter(
    private val items: List<ImagesItem>,
    private val listener: OnImageClickListener,
    private val multipleSelection: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_DATE_HEADER = 0
        const val TYPE_IMAGE = 1
    }

    fun toggleSelection(uri: Uri) {
        ImageSelectionManager.toggleSelection(uri, items, this)
    }

    fun isAnyImageSelected(): Boolean {
        return ImageSelectionManager.selectedImages.isNotEmpty()
    }

    fun getSelectedImageUris(): List<Uri> {
        return ImageSelectionManager.selectedImages
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ImagesItem.DateHeader -> TYPE_DATE_HEADER
            is ImagesItem.ImageItem -> TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_DATE_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.date_header_layout, parent, false)
                DateHeaderViewHolder(view)
            }
            TYPE_IMAGE -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.single_image, parent, false)
                ImageViewHolder(view, multipleSelection)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ImagesItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item)
            is ImagesItem.ImageItem -> (holder as ImageViewHolder).bind(item, listener, ImageSelectionManager.selectedImages.contains(item.uri))
        }
    }

    override fun getItemCount(): Int = items.size

    class DateHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val dateTextView: TextView = view.findViewById(R.id.dateTextView)

        fun bind(header: ImagesItem.DateHeader) {
            dateTextView.text = header.date
        }
    }

    class ImageViewHolder(view: View, private val multipleSelection: Boolean) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.imageView)
        private val checkBox: ImageView = view.findViewById(R.id.checkBox)

        fun bind(imageItem: ImagesItem.ImageItem, listener: OnImageClickListener, isSelected: Boolean) {
            Glide.with(itemView.context)
                .load(imageItem.uri)
                .into(imageView)

            if (multipleSelection) {
                if(isSelected) checkBox.visibility = View.VISIBLE else checkBox.visibility = View.GONE
            }

            itemView.setOnClickListener {
                listener.onImageClicked(imageItem.uri)
                if (multipleSelection) {
                    if(checkBox.isVisible) checkBox.visibility = View.GONE else checkBox.visibility = View.VISIBLE
                }
            }
        }
    }

    interface OnImageClickListener {
        fun onImageClicked(imageUri: Uri)
    }
}