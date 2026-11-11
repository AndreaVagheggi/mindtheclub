package com.bolimot.mindtheclub.database.club

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClubDao {
    @Query("SELECT * FROM Club")
    fun getAllPaged(): PagingSource<Int, Club>

    @Query("SELECT clubId FROM Club")
    fun getLocalClubIds(): Flow<List<String>>

    @Insert
    suspend fun insert(club: Club): Long
    @Update
    suspend fun update(club: Club): Int
    @Delete
    suspend fun delete(club: Club)

    @Query("SELECT EXISTS(SELECT 1 FROM Club WHERE clubId = :clubId)")
    suspend fun exist(clubId: String): Boolean

    @Query("SELECT * FROM Club WHERE clubId = :clubId")
    suspend fun getClub(clubId: String): Club?

    @Query("SELECT owner FROM Club WHERE clubId = :clubId")
    suspend fun checkOwnership(clubId: String): Boolean

    @Query("SELECT clubId FROM Club WHERE uid = :uid LIMIT 1")
    suspend fun getClubId(uid: Int): String

    @Query("DELETE FROM Club WHERE clubId = :clubId")
    suspend fun deleteClubByClubId(clubId: String)

    @Query("SELECT COUNT(*) FROM Club")
    suspend fun countRecords(): Int

    @Query("SELECT * FROM Club")
    suspend fun getAllClubs(): List<Club>
}

