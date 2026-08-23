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

    /**
     * Whether THIS message already has a row for this file.
     *
     * Scoped to the message since 23 Aug. Without it the same photo sent or
     * received a second time produced no row at all, because one already existed
     * for that peer, and the gallery indexes by messageId: the new album had no
     * entries, ImageGallery could not find it, and it silently opened on the
     * oldest photo of the whole chat. Files on disk are still deduplicated by
     * content hash before we get here, so this only ever adds an index row, never
     * a second copy of the image.
     */
    @Query(
        "SELECT COUNT(*) FROM Image " +
        "WHERE url LIKE '%' || :filename AND userId = :userId AND messageId = :messageId"
    )
    suspend fun countByFilename(filename: String, userId: String, messageId: String): Int

    @Delete
    suspend fun delete(image: Image)

    @Query("SELECT * FROM Image WHERE userId = :userId ORDER BY date")
    fun getAll(userId: String): LiveData<List<Image>>

    @Query("DELETE FROM Image WHERE messageId IN (:messageIdList)")
    suspend fun deleteImages(messageIdList: List<String>)
}
