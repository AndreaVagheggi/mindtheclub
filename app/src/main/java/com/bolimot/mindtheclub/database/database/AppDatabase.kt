package com.bolimot.mindtheclub.database.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bolimot.mindtheclub.database.club.Club
import com.bolimot.mindtheclub.database.club.ClubDao
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.image.ImageDao
import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageDao
import com.bolimot.mindtheclub.database.outbox.Outbox
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.peer.PeerDao
import com.bolimot.mindtheclub.database.reaction.Reaction
import com.bolimot.mindtheclub.database.reaction.ReactionDao
import com.bolimot.mindtheclub.database.video.Video
import com.bolimot.mindtheclub.database.video.VideoDao
import com.bolimot.mindtheclub.database.blockeduser.BlockedUser
import com.bolimot.mindtheclub.database.blockeduser.BlockedUserDao
import com.bolimot.mindtheclub.database.groupMessageStatus.GroupMessageStatus
import com.bolimot.mindtheclub.database.groupMessageStatus.GroupMessageStatusDao

@Database(entities = [
    Reaction::class,
    Peer::class,
    Club::class,
    Video::class,
    Image::class,
    Inbox::class,
    Outbox::class,
    BlockedUser::class,
    Message::class,
    GroupMessageStatus::class],
    version = 1064)

abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun clubDao(): ClubDao
    abstract fun imageDao(): ImageDao
    abstract fun videoDao(): VideoDao
    abstract fun inboxDao(): InboxDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao
    abstract fun blockedUserDao(): BlockedUserDao
    abstract fun groupMessageStatusDao(): GroupMessageStatusDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private val MIGRATION_1057_1058 = object : Migration(1057, 1058) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Peer ADD COLUMN lastMessageAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
            UPDATE Peer SET lastMessageAt = COALESCE(
                (SELECT MAX(date) FROM Message 
                 WHERE Message.fromUserId = Peer.userId 
                    OR Message.toUserId = Peer.userId), 
                0)
        """.trimIndent())
            }
        }

        private val MIGRATION_1058_1059 = object : Migration(1058, 1059) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS BlockedUser (
                uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                userId TEXT NOT NULL,
                name TEXT NOT NULL
            )
        """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_BlockedUser_userId ON BlockedUser (userId)")
            }
        }

        private val MIGRATION_1059_1060 = object : Migration(1059, 1060) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS GroupMessageStatus (
                uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                messageId TEXT NOT NULL,
                memberUserId TEXT NOT NULL,
                status TEXT NOT NULL
            )
        """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_GroupMessageStatus_messageId_memberUserId ON GroupMessageStatus (messageId, memberUserId)")
            }
        }

        private val MIGRATION_1060_1061 = object : Migration(1060, 1061) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Inbox ADD COLUMN contentKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("""
                    UPDATE Inbox
                    SET contentKey = CASE
                        WHEN chatGroupId IS NOT NULL AND chatGroupId != ''
                         AND originalSenderId IS NOT NULL AND originalSenderId != ''
                         AND date > 0
                        THEN 'grp_' || chatGroupId || '_' || originalSenderId || '_' || date
                        ELSE messageId
                    END
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_Inbox_contentKey ON Inbox (contentKey)")
            }
        }

        private val MIGRATION_1061_1062 = object : Migration(1061, 1062) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_Inbox_messageId_sequenceNo")
                db.execSQL("DROP INDEX IF EXISTS index_Inbox_contentKey")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Inbox_contentKey_sequenceNo ON Inbox (contentKey, sequenceNo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_Inbox_messageId ON Inbox (messageId)")
            }
        }

        private val MIGRATION_1062_1063 = object : Migration(1062, 1063) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Peer ADD COLUMN publicKey TEXT")
            }
        }

        private val MIGRATION_1063_1064 = object : Migration(1063, 1064) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Peer ADD COLUMN bluetoothMac TEXT")
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "mtc.db"
            )
                .addMigrations(MIGRATION_1057_1058, MIGRATION_1058_1059, MIGRATION_1059_1060, MIGRATION_1060_1061, MIGRATION_1061_1062, MIGRATION_1062_1063, MIGRATION_1063_1064).fallbackToDestructiveMigration(false)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}