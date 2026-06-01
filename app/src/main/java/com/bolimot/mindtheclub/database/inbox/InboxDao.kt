package com.bolimot.mindtheclub.database.inbox

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface InboxDao{
    @Query("SELECT * FROM Inbox WHERE messageId = :messageId")
    suspend fun getMessages(messageId: String): List<Inbox>

    @Query("SELECT * FROM Inbox WHERE messageId = :messageId LIMIT 1")
    suspend fun getFirstMessage(messageId: String): Inbox?

    @Query("SELECT sequenceNo FROM Inbox WHERE messageId = :messageId")
    suspend fun getAllMessageSequences(messageId: String): List<Int>

    @Query("SELECT messageId FROM Inbox GROUP BY messageId HAVING COUNT(*) = MAX(totalNo)")
    suspend fun getCompleteMessageIds(): List<String>

    @Insert
    suspend fun insert(inbox: Inbox): Long

    @Delete
    suspend fun delete(inbox: Inbox)

    @Query("DELETE FROM Inbox")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM Inbox")
    suspend fun countRecords(): Int

    @Query("SELECT COUNT(*) FROM Inbox WHERE messageId = :messageId")
    suspend fun countMessageRecords(messageId: String): Int

    @Query("SELECT totalNo FROM Inbox WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageTotalChunksNumber(messageId: String): Int

    @Query("SELECT COUNT(*) FROM Inbox WHERE messageId = :messageId AND sequenceNo = :sequenceNo")
    suspend fun countBySequenceNo(sequenceNo: Int, messageId: String): Int

    @Query("SELECT * FROM Inbox WHERE messageId = :messageId ORDER BY sequenceNo ASC")
    suspend fun getOrderedMessageChunks(messageId: String): List<Inbox>

    @Query("SELECT content FROM Inbox WHERE messageId = :messageId AND sequenceNo = :sequenceNo")
    suspend fun getChunkContent(messageId: String, sequenceNo: Int): String?

    @Query("DELETE FROM Inbox WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String): Int

    @Query("DELETE FROM Inbox WHERE groupId = :groupId")
    suspend fun deleteGroupMessage(groupId: String): Int

    @Query("SELECT * FROM Inbox WHERE messageId = :messageId ORDER BY sequenceNo ASC LIMIT :limit OFFSET :offset")
    suspend fun getMessageChunksBatch(messageId: String, limit: Int, offset: Int): List<Inbox>

    @Query("SELECT * FROM Inbox WHERE messageId = :messageId ORDER BY sequenceNo ASC LIMIT 1")
    suspend fun getMessage(messageId: String): Inbox

    @Query("""
    SELECT groupId
    FROM (
        SELECT groupId, MIN(date) AS earliestDate
        FROM Inbox
        WHERE groupId IS NOT NULL
          AND groupId != ''
          AND date < (
              CAST(strftime('%s','now') AS INTEGER)*1000 
              - (20 * 60 * 1000)
          )
        GROUP BY groupId
    )
""")
    suspend fun getPendingMultipleMessages(): List<String>

    @Query("""
    SELECT i.*
    FROM Inbox i
    INNER JOIN (
        SELECT messageId, MIN(date) AS earliestDate
        FROM Inbox
        WHERE (groupId IS NULL OR groupId = '')
          AND date < (
              CAST(strftime('%s','now') AS INTEGER) * 1000 
              - (20 * 60 * 1000)
          )
        GROUP BY messageId
    ) sub
      ON i.messageId = sub.messageId 
     AND i.date = sub.earliestDate
""")
    suspend fun getPendingMessages(): List<Inbox>


    @Query("""
    SELECT fromUserId
    FROM Inbox
    WHERE groupId = :groupId
    ORDER BY date ASC
    LIMIT 1
""")
    suspend fun getSender(groupId: String): String?

    @Query("SELECT contentKey FROM Inbox WHERE messageId = :messageId LIMIT 1")
    suspend fun getContentKeyForMessageId(messageId: String): String?

    @Query("SELECT COUNT(*) FROM Inbox WHERE contentKey = :contentKey")
    suspend fun countChunksByContent(contentKey: String): Int

    @Query("SELECT totalNo FROM Inbox WHERE contentKey = :contentKey LIMIT 1")
    suspend fun getTotalChunksByContent(contentKey: String): Int

    @Query("SELECT sequenceNo FROM Inbox WHERE contentKey = :contentKey")
    suspend fun getAllSequencesByContent(contentKey: String): List<Int>

    @Query("SELECT COUNT(*) FROM Inbox WHERE contentKey = :contentKey AND sequenceNo = :sequenceNo")
    suspend fun countBySequenceNoByContent(sequenceNo: Int, contentKey: String): Int

    @Query("SELECT * FROM Inbox WHERE contentKey = :contentKey LIMIT 1")
    suspend fun getFirstByContent(contentKey: String): Inbox?

    @Query("SELECT content FROM Inbox WHERE contentKey = :contentKey AND sequenceNo = :sequenceNo LIMIT 1")
    suspend fun getChunkContentByContent(contentKey: String, sequenceNo: Int): String?

    @Query("DELETE FROM Inbox WHERE contentKey = :contentKey")
    suspend fun deleteByContent(contentKey: String): Int
}
