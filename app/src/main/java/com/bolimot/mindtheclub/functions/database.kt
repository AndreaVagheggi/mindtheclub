package com.bolimot.mindtheclub.functions

import android.app.Application
import android.content.Context
import com.bolimot.mindtheclub.database.club.ClubRepository
import com.bolimot.mindtheclub.database.database.AppDatabase
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.database.image.ImageDao
import com.bolimot.mindtheclub.database.blockeduser.BlockedUserDao
import com.bolimot.mindtheclub.database.blockeduser.BlockedUserRepository
import com.bolimot.mindtheclub.database.image.ImageRepository
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageDao
import com.bolimot.mindtheclub.database.message.MessageRepository
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.peer.PeerDao
import com.bolimot.mindtheclub.database.peer.PeerRepository
import com.bolimot.mindtheclub.database.reaction.ReactionRepository
import com.bolimot.mindtheclub.database.video.VideoDao
import com.bolimot.mindtheclub.database.video.VideoRepository
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.viewModel.ClubViewModel
import com.bolimot.mindtheclub.viewModel.MessageViewModel
import com.bolimot.mindtheclub.viewModel.PeerViewModel

fun getBlockedUserDao(context: Context): BlockedUserDao {
    return DatabaseProvider.provideDatabase(context).blockedUserDao()
}

fun getBlockedUserRepository(context: Context): BlockedUserRepository {
    return BlockedUserRepository(getBlockedUserDao(context))
}

fun getPeerViewModel(): PeerViewModel {
    val databaseProvider = DatabaseProvider.provideDatabase(App.context()).peerDao()
    val peerRepository = PeerRepository(databaseProvider)

    return PeerViewModel(Application(),peerRepository)
}

fun getClubViewModel(): ClubViewModel {
    val databaseProvider = DatabaseProvider.provideDatabase(App.context()).clubDao()
    val clubRepository = ClubRepository(databaseProvider)

    return ClubViewModel(Application(),clubRepository)
}

fun getPeerRepository(context: Context): PeerRepository {
    val databaseProvider = DatabaseProvider.provideDatabase(context).peerDao()
    return PeerRepository(databaseProvider)
}

fun getReactionRepository(context: Context): ReactionRepository {
    val databaseProvider = DatabaseProvider.provideDatabase(context).reactionDao()
    return ReactionRepository(databaseProvider)
}

fun getMessageRepository(context: Context): MessageRepository {
    val databaseProvider = DatabaseProvider.provideDatabase(context).messageDao()
    return MessageRepository(databaseProvider)
}

fun getImageRepository(context: Context): ImageRepository {
    val databaseProvider = DatabaseProvider.provideDatabase(context).imageDao()
    return ImageRepository(databaseProvider)
}

fun getVideoRepository(context: Context): VideoRepository {
    val databaseProvider = DatabaseProvider.provideDatabase(context).videoDao()
    return VideoRepository(databaseProvider)
}

fun getMessageViewModel(myUserId: String, remoteUserId: String): MessageViewModel{
    val databaseProvider = DatabaseProvider.provideDatabase(App.context()).messageDao()
    val messageRepository = MessageRepository(databaseProvider)

    return MessageViewModel(Application(),messageRepository, myUserId, remoteUserId)
}

fun getPeerDao(context: Context): PeerDao {
    val appDatabase = DatabaseProvider.provideDatabase(context)
    return appDatabase.peerDao()
}

fun getMessageDao(context: Context): MessageDao {
    val appDatabase = DatabaseProvider.provideDatabase(context)
    return appDatabase.messageDao()
}

fun getImageDao(context: Context): ImageDao {
    val appDatabase = DatabaseProvider.provideDatabase(context)
    return appDatabase.imageDao()
}

fun getVideoDao(context: Context): VideoDao {
    val appDatabase = DatabaseProvider.provideDatabase(context)
    return appDatabase.videoDao()
}

fun getInboxDao(context: Context): InboxDao {
    val appDatabase = DatabaseProvider.provideDatabase(context)
    return appDatabase.inboxDao()
}

fun deleteBatchTables(messageId: String) {
    val db = AppDatabase.getInstance(App.context()).openHelper.writableDatabase

    db.beginTransaction()
    try {
        val likeClause = if (messageId.isEmpty()) "batch%" else "batch$messageId%"
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '$likeClause'", emptyArray())
        val tableNames = mutableListOf<String>()

        while (cursor.moveToNext()) {
            tableNames.add(cursor.getString(0))
        }
        cursor.close()

        tableNames.forEach { tableName ->
            db.execSQL("DROP TABLE IF EXISTS $tableName")
        }

        db.setTransactionSuccessful()
    } catch (e: Exception) {
        debugLine("deleteBatchTables", "Error while deleting batch tables: ${e.message}")
    } finally {
        db.endTransaction()
    }
}

fun batchTablesExists(messageId: String): Boolean {
    val db = AppDatabase.getInstance(App.context()).openHelper.writableDatabase
    val query = "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ? LIMIT 1"

    return db.query(query, arrayOf("batch$messageId%")).use { it.count > 0 }
}

/** Group coordinates of a queued message, as stored on its batch rows. */
data class BatchContentCoordinates(
    val chatGroupId: String,
    val originalSenderId: String,
    val messageDate: Long,
    /**
     * The ORIGINAL message id, the one every peer knows the content by, as opposed to the per hop
     * id this batch happens to be filed under. Carried here because the recovery paths rebuild a
     * dispatch from the batch tables alone and would otherwise have no way to name the message.
     */
    val groupId: String
)

/**
 * Reads the group coordinates back from the batch rows of [messageId].
 *
 * They identify the CONTENT rather than this particular copy of it, which is what makes two
 * dispatches recognisable as the same transfer: in group gossip the same file travels under a
 * fresh messageId at every hop, so the messageId alone says nothing about what is being sent.
 *
 * Null for a 1:1 message (no group coordinates), where the messageId is identity enough.
 */
fun batchContentCoordinates(messageId: String): BatchContentCoordinates? {
    return try {
        val db = AppDatabase.getInstance(App.context()).openHelper.readableDatabase
        db.query(
            "SELECT chatGroupId, originalSenderId, date, groupId FROM batch${messageId}1 LIMIT 1",
            emptyArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val chatGroupId = cursor.getString(0) ?: return null
            val originalSenderId = cursor.getString(1) ?: return null
            val date = cursor.getLong(2)
            if (chatGroupId.isEmpty() || originalSenderId.isEmpty() || date <= 0L) return null
            val groupId = cursor.getString(3) ?: ""
            BatchContentCoordinates(chatGroupId, originalSenderId, date, groupId)
        }
    } catch (e: Exception) {
        debugLine("batchContentCoordinates", "Lookup failed for $messageId: ${e.message}")
        null
    }
}

fun isGroupChat(message: Message): Boolean {
    return message.chatGroupId != null
}

suspend fun saveNewGroupAsPeer(gId: String, groupName: String): Boolean {
    val newPeer = Peer(
        userId = gId,
        name = groupName,
        bio = "",
        picture = "",
        uid = 0,
        token = "",
        status = Contact.ACTIVE,
        privateId = ""
    )
    return getPeerViewModel().addNewPeer(newPeer)
}

fun countBatchChunks(messageId: String): Int {
    val db = AppDatabase.getInstance(App.context()).openHelper.readableDatabase
    val cursor = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ?",
        arrayOf("batch$messageId%")
    )
    var totalChunks = 0
    while (cursor.moveToNext()) {
        val tableName = cursor.getString(0)
        try {
            val countCursor = db.query("SELECT COUNT(*) FROM `$tableName`", emptyArray())
            if (countCursor.moveToFirst()) {
                totalChunks += countCursor.getInt(0)
            }
            countCursor.close()
        } catch (_: Exception) { }
    }
    cursor.close()
    return totalChunks
}

fun reconcileBatchTotalNo(messageId: String): Int {
    val actualChunks = countBatchChunks(messageId)
    if (actualChunks <= 0) return actualChunks
    val db = AppDatabase.getInstance(App.context()).openHelper.writableDatabase
    val tableCursor = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE ?",
        arrayOf("batch$messageId%")
    )
    while (tableCursor.moveToNext()) {
        val tableName = tableCursor.getString(0)
        try {
            db.execSQL("UPDATE `$tableName` SET totalNo = ? WHERE totalNo <> ?", arrayOf(actualChunks, actualChunks))
        } catch (_: Exception) { }
    }
    tableCursor.close()
    return actualChunks
}