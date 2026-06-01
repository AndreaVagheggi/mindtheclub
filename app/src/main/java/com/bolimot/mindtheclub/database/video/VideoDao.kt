package com.bolimot.mindtheclub.database.video

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VideoDao {
    @Insert
    suspend fun insert(video: Video): Long

    @Query("SELECT COUNT(*) FROM Video WHERE url LIKE '%' || :filename")
    suspend fun countByFilename(filename: String): Int

    @Delete
    suspend fun delete(video: Video)

    @Query("SELECT * FROM Video WHERE userId = :userId ORDER BY date")
    fun getAll(userId: String): LiveData<List<Video>>

    @Query("DELETE FROM Video WHERE messageId IN (:messageIdList)")
    suspend fun deleteImages(messageIdList: List<String>)
}
