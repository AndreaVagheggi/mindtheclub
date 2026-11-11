package com.bolimot.mindtheclub.database.peer

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PeerDao {
    @Query("SELECT * FROM Peer WHERE privateId NOT LIKE 'blocked%' ORDER BY status DESC, lastMessageAt DESC, name ASC")
    fun getAllPaged(): PagingSource<Int, Peer>

    @Query("SELECT * FROM Peer WHERE userId LIKE 'group%'")
    suspend fun getGroupPeers(): List<Peer>

    @Query("UPDATE Peer SET privateId = 'blocked' || privateId WHERE userId = :userId")
    suspend fun markAsBlocked(userId: String): Int

    @Query("SELECT * FROM Peer WHERE userId LIKE 'group%' AND (picture IS NULL OR picture = '')")
    suspend fun getGroupPeersWithoutPicture(): List<Peer>

    @Query("UPDATE Peer SET picture = :picture WHERE userId = :userId")
    suspend fun updatePeerPicture(userId: String, picture: String?): Int

    @Query("UPDATE Peer SET publicKey = :publicKey WHERE userId = :userId")
    suspend fun updatePeerPublicKey(userId: String, publicKey: String?): Int

    @Query("UPDATE Peer SET bluetoothMac = :mac WHERE userId = :userId")
    suspend fun updatePeerBluetoothMac(userId: String, mac: String?): Int

    @Query("UPDATE Peer SET name = :name WHERE userId = :userId")
    suspend fun updatePeerName(userId: String, name: String): Int

    @Query("UPDATE Peer SET privateId = REPLACE(privateId, 'blocked', '') WHERE userId = :userId")
    suspend fun removeBlockedMark(userId: String): Int

    @Query("UPDATE Peer SET lastMessageAt = :timestamp WHERE userId = :userId")
    suspend fun updateLastMessageAt(userId: String, timestamp: Long)

    @Insert
    suspend fun insert(peer: Peer): Long
    @Update
    suspend fun update(peer: Peer): Int
    @Delete
    suspend fun delete(peer: Peer)

    @Query("SELECT * FROM Peer WHERE name LIKE '%' || :query || '%' AND status = 'active' ORDER BY name ASC LIMIT 20")
    suspend fun searchPeers(query: String): List<Peer>

    @Query("SELECT userId FROM Peer WHERE status = 'active' AND userId NOT LIKE 'group%'")
    suspend fun getActivePeerUserIds(): List<String>

    @Query("SELECT * FROM Peer WHERE status = 'active' AND userId NOT LIKE 'group%'")
    suspend fun getActiveNonGroupPeers(): List<Peer>

    @Query("SELECT * FROM Peer WHERE status = 'active'")
    suspend fun getAllActivePeers(): List<Peer>

    @Query("SELECT * FROM Peer")
    suspend fun getAllPeers(): List<Peer>

    @Query("UPDATE Peer SET name = :name, bio = :bio, picture = :picture WHERE userId = :userId")
    suspend fun updatePeerProfile(userId: String, name: String, bio: String?, picture: String?): Int

    @Query("SELECT EXISTS(SELECT 1 FROM Peer WHERE userId = :userId)")
    suspend fun exist(userId: String): Boolean

    @Query("SELECT * FROM Peer ORDER BY uid ASC LIMIT 1")
    suspend fun getFirstPeer(): Peer?

    @Query("UPDATE Peer SET token = :newToken WHERE userId = :userId")
    suspend fun updateTokenForUserId(userId: String, newToken: String)

    @Query("SELECT * FROM Peer WHERE userId = :userId")
    suspend fun getPeer(userId: String): Peer?

    @Query("SELECT token FROM Peer WHERE userId = :userId")
    suspend fun getToken(userId: String): String?

    @Query("SELECT status FROM Peer WHERE userId = :userId")
    suspend fun getPeerStatus(userId: String): String?

    @Query("SELECT userId FROM Peer WHERE uid = :uid LIMIT 1")
    suspend fun getUserId(uid: Int): String

    @Query("DELETE FROM Peer WHERE userId = :userId")
    suspend fun deletePeerByUserId(userId: String)

    @Query("SELECT COUNT(*) FROM Peer")
    suspend fun countRecords(): Int

    @Query("UPDATE Peer SET status = :status WHERE userId = :userId")
    suspend fun updatePeerStatus(userId: String, status: String): Int

    @Query("""
    UPDATE Peer 
    SET 
        token = :token, 
        name = :name, 
        bio = :bio, 
        picture = :picture, 
        privateId = :privateId
    WHERE userId = :userId
""")
    suspend fun updatePeer(
        userId: String,
        token: String,
        name: String,
        bio: String?,
        picture: String?,
        privateId: String
    ): Int
}

