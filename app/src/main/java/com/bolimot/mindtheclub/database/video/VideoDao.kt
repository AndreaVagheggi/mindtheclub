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

    /**
     * Twin of the Image one, and it was looser still: the check spanned every
     * chat, so a video forwarded to a second peer got no row there at all. Scoped
     * to peer and message since 23 Aug, for the same reason. The file itself is
     * deduplicated on disk before this point.
     */
    @Query(
        "SELECT COUNT(*) FROM Video " +
        "WHERE url LIKE '%' || :filename AND userId = :userId AND messageId = :messageId"
    )
    suspend fun countByFilename(filename: String, userId: String, messageId: String): Int

    @Delete
    suspend fun delete(video: Video)

    @Query("SELECT * FROM Video WHERE userId = :userId ORDER BY date")
    fun getAll(userId: String): LiveData<List<Video>>

    @Query("DELETE FROM Video WHERE messageId IN (:messageIdList)")
    suspend fun deleteImages(messageIdList: List<String>)
}
