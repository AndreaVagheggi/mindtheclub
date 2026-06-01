package com.bolimot.mindtheclub.database.video

import androidx.lifecycle.LiveData

class VideoRepository(private val videoDao: VideoDao) {
    fun getImages(userId: String): LiveData<List<Video>> {
        return videoDao.getAll(userId)
    }

    suspend fun insertImage(image: Video) {
        val filename = image.url.substringAfterLast('/')
        if (videoDao.countByFilename(filename) == 0) {
            videoDao.insert(image)
        }
    }
}


