package com.bolimot.mindtheclub.viewModel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.image.ImageRepository

class ImageGalleryViewModel(private val repository: ImageRepository) : ViewModel() {

    fun getAllImages(userId: String): LiveData<List<Image>> {
        return repository.getImages(userId)
    }
}

