package com.bolimot.mindtheclub.database.reaction

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReactionDao{
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reaction: Reaction): Long

    @Query("SELECT emoji FROM Reaction WHERE messageId = :messageId LIMIT 1")
    suspend fun getEmoji(messageId: String): String?

    @Query("DELETE FROM Reaction WHERE messageId = :messageId")
    suspend fun deleteReaction(messageId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM Reaction WHERE messageId = :messageId)")
    fun exists(messageId: String): Boolean

    @Query("SELECT * FROM Reaction")
    suspend fun getAllReactions(): List<Reaction>
}
