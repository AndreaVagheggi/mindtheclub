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

    @Query("SELECT DISTINCT messageId FROM Inbox")
    suspend fun getDistinctMessageIds(): List<String>

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

    /**
     * The FIRST chunk of a content set, which is the one carrying the fields that
     * only exist once (text, replyId), hence the ORDER BY. [getFirstMessage] takes
     * whichever row the engine hands back and is not a substitute.
     *
     * Nullable since 23 Aug. It was declared non-null, so Room threw
     * IllegalStateException when the set had been deleted between the caller's
     * completeness check and this read, and the crash killed the process
     * (DefaultDispatcher-worker-4, 10:52:16). Deletion happens on other coroutines
     * (chat purge, blocked sender, orphan expiry in InboxRecovery), so the window
     * cannot be closed by the callers: they have to be able to see the absence.
     */
    @Query("SELECT * FROM Inbox WHERE messageId = :messageId ORDER BY sequenceNo ASC LIMIT 1")
    suspend fun getMessage(messageId: String): Inbox?

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

    /**
     * Every id under which leftover chunks of [chatId] are filed: the hop
     * messageId AND the groupId (the original message id), because the pending
     * trackers are keyed on the latter while the Inbox rows carry both.
     *
     * The chat predicate is deliberately narrow. Matching a group by its
     * chatGroupId is exact. Matching a one to one peer by fromUserId is only
     * done when chatGroupId is empty, or deleting a contact would take with it
     * the partial group content that same contact happened to be relaying for a
     * group still on this phone.
     */
    @Query(
        "SELECT DISTINCT messageId FROM Inbox " +
        "WHERE chatGroupId = :chatId " +
        "   OR (fromUserId = :chatId AND (chatGroupId IS NULL OR chatGroupId = '')) " +
        "UNION " +
        "SELECT DISTINCT groupId FROM Inbox " +
        "WHERE groupId != '' AND (chatGroupId = :chatId " +
        "   OR (fromUserId = :chatId AND (chatGroupId IS NULL OR chatGroupId = '')))"
    )
    suspend fun getMessageIdsForChat(chatId: String): List<String>

    /**
     * Content identities of everything [chatId] has half received. Deleting the
     * chat marks these as refused, and receiveData drops any further chunk that
     * carries them whatever messageId the relay minted for it.
     */
    @Query(
        "SELECT DISTINCT contentKey FROM Inbox " +
        "WHERE contentKey != '' AND (chatGroupId = :chatId " +
        "   OR (fromUserId = :chatId AND (chatGroupId IS NULL OR chatGroupId = '')))"
    )
    suspend fun getContentKeysForChat(chatId: String): List<String>

    /** Companion of [getMessageIdsForChat]: same predicate, deletes the rows. */
    @Query(
        "DELETE FROM Inbox " +
        "WHERE chatGroupId = :chatId " +
        "   OR (fromUserId = :chatId AND (chatGroupId IS NULL OR chatGroupId = ''))"
    )
    suspend fun deleteByChat(chatId: String): Int
}
