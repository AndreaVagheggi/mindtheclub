package com.bolimot.mindtheclub.database.groupMessageStatus

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMessageStatusDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(status: GroupMessageStatus)

    @Query("SELECT * FROM GroupMessageStatus WHERE messageId = :messageId")
    fun observeStatusesForMessage(messageId: String): Flow<List<GroupMessageStatus>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(statuses: List<GroupMessageStatus>)

    @Query("UPDATE GroupMessageStatus SET status = :status WHERE messageId = :messageId AND memberUserId = :memberUserId")
    suspend fun updateStatus(messageId: String, memberUserId: String, status: String): Int

    @Query("SELECT * FROM GroupMessageStatus WHERE messageId = :messageId")
    suspend fun getStatusesForMessage(messageId: String): List<GroupMessageStatus>

    @Query("SELECT * FROM GroupMessageStatus")
    suspend fun getAll(): List<GroupMessageStatus>

    @Query("DELETE FROM GroupMessageStatus WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("SELECT status FROM GroupMessageStatus WHERE messageId = :messageId AND memberUserId = :memberUserId LIMIT 1")
    suspend fun getStatusForMember(messageId: String, memberUserId: String): String?
}
