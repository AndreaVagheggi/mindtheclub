package com.bolimot.mindtheclub.fragments

import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.dataModels.ImagesItem

object ImageSelectionManager {
    val selectedImages = mutableListOf<Uri>()

    fun toggleSelection(uri: Uri, items: List<ImagesItem>, adapter: RecyclerView.Adapter<*>) {
        val position = items.indexOfFirst { (it as? ImagesItem.ImageItem)?.uri == uri }
        if (position != -1) {
            if (selectedImages.contains(uri)) {
                selectedImages.remove(uri)
            } else {
                selectedImages.add(uri)
            }
            adapter.notifyItemChanged(position)
        }
    }
}
