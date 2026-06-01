package com.bolimot.mindtheclub.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.ImageItem
import com.bumptech.glide.Glide

class SendImagesAdapter(
    private var imageList: List<ImageItem>
) : RecyclerView.Adapter<SendImagesAdapter.SendImageViewHolder>() {

    class SendImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.image)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SendImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.image_view, parent, false)
        return SendImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: SendImageViewHolder, position: Int) {
        val imageItem = imageList[position]
        Glide.with(holder.imageView.context)
            .load(imageItem.uri)
            .error(R.drawable.mtc_logo_icon_png)
            .into(holder.imageView)
    }

    override fun getItemCount(): Int {
        return imageList.size
    }

    fun updateImageList(newImageList: List<ImageItem>) {
        imageList = newImageList
    }
}

