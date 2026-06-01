package com.bolimot.mindtheclub.database.image

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ImageDao {
    @Insert
    suspend fun insert(image: Image): Long

    @Query("SELECT COUNT(*) FROM Image WHERE url LIKE '%' || :filename AND userId = :userId")
    suspend fun countByFilename(filename: String, userId: String): Int

    @Delete
    suspend fun delete(image: Image)

    @Query("SELECT * FROM Image WHERE userId = :userId ORDER BY date")
    fun getAll(userId: String): LiveData<List<Image>>

    @Query("DELETE FROM Image WHERE messageId IN (:messageIdList)")
    suspend fun deleteImages(messageIdList: List<String>)
}
