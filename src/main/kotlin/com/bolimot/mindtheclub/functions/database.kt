package com.bolimot.mindtheclub.functions

// DESKTOP: this file is NOT a copy of the App's database.kt. The accessors the engine's files
// call are here with the same names and signatures. Left out for now:
//   - the ViewModel getters (getPeerViewModel, getMessageViewModel, getClubViewModel):
//     ViewModels are the Android user interface;
//   - the Image, Video, Peer and Club repositories, until a ported file needs them.

import android.content.Context
import com.bolimot.mindtheclub.database.blockeduser.BlockedUserDao
import com.bolimot.mindtheclub.database.blockeduser.BlockedUserRepository
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.image.ImageDao
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageDao
import com.bolimot.mindtheclub.database.message.MessageRepository
import com.bolimot.mindtheclub.database.peer.PeerDao
import com.bolimot.mindtheclub.database.reaction.ReactionDao
import com.bolimot.mindtheclub.database.reaction.ReactionRepository
import com.bolimot.mindtheclub.database.video.VideoDao
import com.bolimot.mindtheclub.desktop.storage.BatchDb

// The Context parameter is kept so the callers stay as they are in the App; on the PC there is
// one database and it does not depend on it.

fun getBlockedUserDao(context: Context): BlockedUserDao {
    return DatabaseProvider.provideDatabase().blockedUserDao()
}

fun getBlockedUserRepository(context: Context): BlockedUserRepository {
    return BlockedUserRepository(getBlockedUserDao(context))
}

fun getMessageRepository(context: Context): MessageRepository {
    return MessageRepository(DatabaseProvider.provideDatabase().messageDao())
}

fun getReactionRepository(context: Context): ReactionRepository {
    return ReactionRepository(DatabaseProvider.provideDatabase().reactionDao())
}

fun getPeerDao(context: Context): PeerDao {
    return DatabaseProvider.provideDatabase().peerDao()
}

fun getMessageDao(context: Context): MessageDao {
    return DatabaseProvider.provideDatabase().messageDao()
}

fun getReactionDao(context: Context): ReactionDao {
    return DatabaseProvider.provideDatabase().reactionDao()
}

fun getImageDao(context: Context): ImageDao {
    return DatabaseProvider.provideDatabase().imageDao()
}

fun getVideoDao(context: Context): VideoDao {
    return DatabaseProvider.provideDatabase().videoDao()
}

fun getInboxDao(context: Context): InboxDao {
    return DatabaseProvider.provideDatabase().inboxDao()
}

fun isGroupChat(message: Message): Boolean {
    return message.chatGroupId != null
}

// ---------------------------------------------------------------- batch tables
// DESKTOP: same functions as the App, same SQL, on the PC's own batch file instead of
// SupportSQLiteDatabase and Cursor (desktop/storage/BatchDb.kt explains why).

/** Names of the batch tables of [messageId]; every batch table when it is empty. */
private fun batchTableNames(messageId: String): List<String> {
    val likeClause = if (messageId.isEmpty()) "batch%" else "batch$messageId%"
    return BatchDb.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ?",
        listOf(likeClause)
    ) { it.getText(0) }
}

fun deleteBatchTables(messageId: String) {
    try {
        BatchDb.transaction {
            for (tableName in batchTableNames(messageId)) {
                BatchDb.exec("DROP TABLE IF EXISTS `$tableName`")
            }
        }
    } catch (e: Exception) {
        debugLine("deleteBatchTables", "Error while deleting batch tables: ${e.message}")
    }
}

fun batchTablesExists(messageId: String): Boolean {
    return BatchDb.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ? LIMIT 1",
        listOf("batch$messageId%")
    ) { it.getText(0) }.isNotEmpty()
}

/** Group coordinates of a queued message, as stored on its batch rows. */
data class BatchContentCoordinates(
    val chatGroupId: String,
    val originalSenderId: String,
    val messageDate: Long,
    /** The ORIGINAL message id, the one every peer knows the content by. */
    val groupId: String
)

/**
 * Reads the group coordinates back from the batch rows of [messageId]. Null for a 1:1 message
 * (no group coordinates), where the messageId is identity enough.
 */
fun batchContentCoordinates(messageId: String): BatchContentCoordinates? {
    return try {
        BatchDb.query(
            "SELECT chatGroupId, originalSenderId, date, groupId FROM batch${messageId}1 LIMIT 1"
        ) { row ->
            val chatGroupId = if (row.isNull(0)) null else row.getText(0)
            val originalSenderId = if (row.isNull(1)) null else row.getText(1)
            val date = if (row.isNull(2)) 0L else row.getLong(2)
            val groupId = if (row.isNull(3)) "" else row.getText(3)
            if (chatGroupId.isNullOrEmpty() || originalSenderId.isNullOrEmpty() || date <= 0L) {
                null
            } else {
                BatchContentCoordinates(chatGroupId, originalSenderId, date, groupId)
            }
        }.firstOrNull()
    } catch (e: Exception) {
        debugLine("batchContentCoordinates", "Lookup failed for $messageId: ${e.message}")
        null
    }
}

fun countBatchChunks(messageId: String): Int {
    var totalChunks = 0
    for (tableName in batchTableNames(messageId)) {
        try {
            totalChunks += BatchDb.query("SELECT COUNT(*) FROM `$tableName`") { it.getInt(0) }.firstOrNull() ?: 0
        } catch (_: Exception) { }
    }
    return totalChunks
}

fun reconcileBatchTotalNo(messageId: String): Int {
    val actualChunks = countBatchChunks(messageId)
    if (actualChunks <= 0) return actualChunks
    for (tableName in batchTableNames(messageId)) {
        try {
            BatchDb.update("UPDATE `$tableName` SET totalNo = ? WHERE totalNo <> ?", listOf(actualChunks, actualChunks))
        } catch (_: Exception) { }
    }
    return actualChunks
}
