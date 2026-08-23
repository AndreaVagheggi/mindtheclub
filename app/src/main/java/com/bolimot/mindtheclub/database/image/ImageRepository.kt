package com.bolimot.mindtheclub.database.image

import androidx.lifecycle.LiveData

class ImageRepository(private val imageDao: ImageDao) {
    fun getImages(userId: String): LiveData<List<Image>> {
        return imageDao.getAll(userId)
    }

    suspend fun insertImage(image: Image) {
        val filename = image.url.substringAfterLast('/')
        if (imageDao.countByFilename(filename, image.userId, image.messageId) == 0) {
            imageDao.insert(image)
        }
    }
}


