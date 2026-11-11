package com.bolimot.mindtheclub.database.blockeduser

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockedUserDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(blockedUser: BlockedUser): Long

    @Query("DELETE FROM BlockedUser WHERE userId = :userId")
    suspend fun unblock(userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM BlockedUser WHERE userId = :userId)")
    suspend fun isBlocked(userId: String): Boolean

    @Query("SELECT * FROM BlockedUser ORDER BY name ASC")
    suspend fun getAll(): List<BlockedUser>
}
