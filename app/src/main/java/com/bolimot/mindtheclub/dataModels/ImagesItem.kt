package com.bolimot.mindtheclub.dataModels

import android.net.Uri

sealed class ImagesItem {
    data class DateHeader(val date: String) : ImagesItem()
    data class ImageItem(val uri: Uri) : ImagesItem()
}

data class ImageItem(val uri: Uri)
