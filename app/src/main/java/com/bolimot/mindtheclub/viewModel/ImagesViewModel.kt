package com.bolimot.mindtheclub.viewModel

import android.app.Application
import android.database.Cursor
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bolimot.mindtheclub.dataModels.ImagesItem
import com.bolimot.mindtheclub.functions.createImageItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImagesViewModel(application: Application) : AndroidViewModel(application) {

    private val _images = MutableLiveData<List<ImagesItem>>()
    val images: LiveData<List<ImagesItem>> = _images

    fun loadImages(isCameraRoll: Boolean) {
        viewModelScope.launch {
            val imageList = withContext(Dispatchers.IO) {
                val selection: String
                val selectionArgs: Array<String>

                if (isCameraRoll) {
                    selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}=?"
                    selectionArgs = arrayOf("Camera")
                } else {
                    selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}!=?"
                    selectionArgs = arrayOf("Camera")
                }

                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_ADDED
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                val cursor: Cursor? = getApplication<Application>().contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

                cursor?.use { createImageItems(it) } ?: emptyList()
            }
            _images.value = imageList
        }
    }
}