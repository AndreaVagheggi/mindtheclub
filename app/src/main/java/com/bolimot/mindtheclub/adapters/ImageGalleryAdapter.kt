package com.bolimot.mindtheclub.adapters

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.safeUrl
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import io.getstream.photoview.PhotoView
import kotlin.math.roundToInt

class ImageAdapter(private var images: List<Image>, private val listener: OnScaleChangeListener) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    interface OnScaleChangeListener {
        fun onScaleChanged(isZoomedIn: Boolean)
    }
    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: PhotoView = itemView.findViewById(R.id.image)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.image_view, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val image = images[position]
        var isZoomedIn = false

        val requestOptions = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)

        Glide.with(holder.imageView.context)
            .load(safeUrl(image.url))
            .apply(requestOptions)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource?,
                    isFirstResource: Boolean
                ): Boolean {
                    resource?.let {
                        val imageWidth = it.intrinsicWidth
                        val imageHeight = it.intrinsicHeight

                        holder.imageView.post {
                            if (imageWidth > imageHeight) {
                                Glide.with(holder.imageView.context)
                                    .load(safeUrl(image.url))
                                    .apply(requestOptions)
                                    .into(holder.imageView)
                            } else {
                                Glide.with(holder.imageView.context)
                                    .load(safeUrl(image.url))
                                    .apply(requestOptions)
                                    .into(holder.imageView)
                            }
                        }
                    }
                    return true
                }
            })
            .into(holder.imageView)

        if (position + 1 < images.size) {
            val nextImageUrl = images[position + 1].url
            Glide.with(holder.imageView.context)
                .load(safeUrl(nextImageUrl))
                .apply(requestOptions)
                .preload()
        }

        holder.imageView.setOnScaleChangeListener { _, _, _ ->
            val currentScale = holder.imageView.scale
            val roundedScale = (currentScale * 10).roundToInt() / 10f

            val currentlyZoomedIn = roundedScale != 1.0f

            debugLine(
                "ImageGalleryAdapter",
                "currentScale: $roundedScale, isZoomedIn: $currentlyZoomedIn, wasZoomedIn: $isZoomedIn"
            )

            if (currentlyZoomedIn != isZoomedIn) {
                isZoomedIn = currentlyZoomedIn
                listener.onScaleChanged(isZoomedIn)
            }
        }
    }

    override fun getItemCount(): Int = images.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateImages(newImages: List<Image>) {
        this.images = newImages
        notifyDataSetChanged()
    }

    fun getImageAt(position: Int): Image? {
        return if (position in images.indices) {
            images[position]
        } else {
            null
        }
    }
}