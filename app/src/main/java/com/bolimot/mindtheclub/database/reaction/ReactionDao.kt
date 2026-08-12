package com.bolimot.mindtheclub.database.reaction

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReactionDao{
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reaction: Reaction): Long

    @Query("SELECT * FROM Reaction WHERE messageId = :messageId ORDER BY date ASC")
    suspend fun getReactions(messageId: String): List<Reaction>

    @Query("SELECT * FROM Reaction WHERE messageId = :messageId AND reactorUserId = :reactorUserId LIMIT 1")
    suspend fun getReaction(messageId: String, reactorUserId: String): Reaction?

    @Query("DELETE FROM Reaction WHERE messageId = :messageId AND reactorUserId = :reactorUserId")
    suspend fun deleteReaction(messageId: String, reactorUserId: String)

    @Query("DELETE FROM Reaction WHERE messageId = :messageId")
    suspend fun deleteReactions(messageId: String)

    @Query("SELECT * FROM Reaction")
    suspend fun getAllReactions(): List<Reaction>
}
