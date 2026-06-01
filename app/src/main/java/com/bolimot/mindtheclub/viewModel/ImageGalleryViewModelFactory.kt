package com.bolimot.mindtheclub.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bolimot.mindtheclub.database.image.ImageRepository

class ImageGalleryViewModelFactory(
    private val imageRepository: ImageRepository,

) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ImageGalleryViewModel::class.java)) {
            return ImageGalleryViewModel(imageRepository) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class")
    }
}