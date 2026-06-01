package com.bolimot.mindtheclub.database.message

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageDao{
    @Delete
    suspend fun delete(message: Message)

    @Query("SELECT * FROM Message")
    suspend fun getAllMessages(): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    @Query("SELECT messageId, originalSenderId FROM Message WHERE status != 'Seen' AND status != 'Receiving' AND fromUserId = :groupId AND originalSenderId IS NOT NULL")
    suspend fun getUnseenGroupMessages(groupId: String): List<UnseenGroupMessage>

    data class UnseenGroupMessage(
        val messageId: String,
        val originalSenderId: String
    )

    @Query("""
    SELECT * FROM Message 
    WHERE (fromUserId = :myUserId AND toUserId = :remoteUserId) 
       OR (fromUserId = :remoteUserId AND toUserId = :myUserId)
     ORDER BY date DESC
    """)
    fun getMessagesPagingSource(myUserId: String, remoteUserId: String): PagingSource<Int, Message>

    @Query("""
    SELECT COUNT(*) FROM Message
    WHERE ((fromUserId = :myUserId AND toUserId = :remoteUserId) 
           OR (fromUserId = :remoteUserId AND toUserId = :myUserId))
      AND (
          date > (SELECT date FROM Message WHERE messageId = :messageId)
          OR (date = (SELECT date FROM Message WHERE messageId = :messageId)
              AND messageId > :messageId)
      )
""")
    suspend fun getMessageGlobalPosition(
        myUserId: String,
        remoteUserId: String,
        messageId: String
    ): Int

    @Query("""
        SELECT date FROM Message 
        WHERE (fromUserId = :myUserId AND toUserId = :remoteUserId) 
           OR (fromUserId = :remoteUserId AND toUserId = :myUserId)
        ORDER BY date DESC
    """)
    suspend fun getAllMessageDates(myUserId: String, remoteUserId: String): List<Long>

    @Query("""
    SELECT * FROM Message 
    WHERE text LIKE '%' || :query || '%' 
    AND type IN ('text', 'web')
    ORDER BY date DESC 
    LIMIT 50
""")
    suspend fun searchMessages(query: String): List<Message>

    @Query("""
        SELECT COUNT(*) FROM Message
        WHERE ((fromUserId = :myUserId AND toUserId = :remoteUserId) 
               OR (fromUserId = :remoteUserId AND toUserId = :myUserId))
          AND date >= :timestamp
    """)
    suspend fun countMessagesNewerThan(myUserId: String, remoteUserId: String, timestamp: Long): Int

    @Query("""
    SELECT * FROM Message 
    WHERE ((fromUserId = :myUserId AND toUserId = :remoteUserId) 
       OR (fromUserId = :remoteUserId AND toUserId = :myUserId))
    AND text LIKE '%' || :query || '%'
    ORDER BY date DESC
""")
    fun getFilteredMessagesPagingSource(
        myUserId: String,
        remoteUserId: String,
        query: String
    ): PagingSource<Int, Message>

    @Query("SELECT * FROM Message WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): Message?

    @Query("SELECT * FROM Message WHERE status = 'Receiving'")
    suspend fun getReceivingMessages(): List<Message>

    @Query("SELECT messageId FROM Message WHERE status != 'Seen' AND status != 'Receiving' AND fromUserId = :remoteUserId")
    suspend fun getUnseenMessages(remoteUserId: String): List<String>

    @Query("SELECT messageId FROM Message WHERE fromUserId = :peerUserId")
    suspend fun getPeerMessages(peerUserId: String): List<String>

    @Query("SELECT uri FROM Message WHERE messageId = :replyId LIMIT 1")
    suspend fun getReplyImage(replyId: String): String?

    @Query("SELECT replyId FROM Message WHERE messageId = :replyId LIMIT 1")
    suspend fun getReplyImageWeb(replyId: String): String?

    @Query("SELECT type FROM Message WHERE messageId = :replyId LIMIT 1")
    suspend fun getReplyType(replyId: String): String?

    @Query("SELECT messageId FROM Message WHERE messageId = :replyId LIMIT 1")
    suspend fun getReplyMessageId(replyId: String): String?

    @Query("UPDATE Message SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String): Int

    @Query("""
    UPDATE Message 
    SET reaction = :emoji 
    WHERE messageId = :messageId 
    """)
    suspend fun updateReaction(messageId: String, emoji: String): Int

    @Query("""
    SELECT COUNT(*) FROM Message 
    WHERE (fromUserId = :myUserId AND toUserId = :remoteUserId) 
       OR (fromUserId = :remoteUserId AND toUserId = :myUserId)
    """)
    fun getMessageCount(myUserId: String, remoteUserId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM Message WHERE messageId = :messageId)")
    suspend fun messageExists(messageId: String): Boolean

    @Query("DELETE FROM Message WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String): Int

    @Query("SELECT * FROM Message WHERE uid = :uid LIMIT 1")
    suspend fun getMessageById(uid: Int): Message?

    @Query("SELECT COUNT(*) FROM Message WHERE groupId = :groupId")
    fun getImagesCount(groupId: String): Int

    @Query("SELECT uri FROM Message WHERE groupId = :groupId")
    fun getUriList(groupId: String): List<String>

    @Query("SELECT * FROM Message WHERE fromUserId = :remoteUserId OR toUserId = :remoteUserId")
    fun getMessagesByRemotePeer(remoteUserId: String): List<Message>

    @Query("DELETE FROM Message WHERE groupId = :groupId")
    suspend fun deleteMessagesByGroupId(groupId: String)

    @Query("SELECT * FROM Message WHERE fromUserId = :peerId OR toUserId = :peerId ORDER BY date DESC LIMIT 1")
    suspend fun getLastMessage(peerId: String): Message?

    @Query("SELECT COUNT(*) FROM Message WHERE chatGroupId = :chatGroupId AND originalSenderId = :senderId AND date = :date AND status != 'Receiving'")
    suspend fun countGroupMessage(chatGroupId: String, senderId: String, date: Long): Int

    @Query("SELECT * FROM Message WHERE chatGroupId = :chatGroupId AND originalSenderId = :senderId AND date = :date AND status != 'Receiving' LIMIT 1")
    suspend fun getGroupMessage(chatGroupId: String, senderId: String, date: Long): Message?

    @Query("""
    SELECT * FROM Message 
    WHERE status = 'Sent' 
    AND date < (
        CAST(strftime('%s','now') AS INTEGER) * 1000 
        - (20 * 60 * 1000)
    )
""")
    suspend fun getPendingMessages(): List<Message>
}
