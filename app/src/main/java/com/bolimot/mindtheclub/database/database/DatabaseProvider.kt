package com.bolimot.mindtheclub.database.database

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bolimot.mindtheclub.tools.MySelf
import java.io.File

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

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

    fun provideDatabase(context: Context): AppDatabase {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                deviceProtectedContext,
                AppDatabase::class.java,
                "mtc.db"
            )
                .addMigrations(MIGRATION_1057_1058, MIGRATION_1058_1059, MIGRATION_1059_1060, MIGRATION_1060_1061, MIGRATION_1061_1062)
                .fallbackToDestructiveMigration(false)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        super.onDestructiveMigration(db)
                        deleteDebugLogFile(deviceProtectedContext)
                    }
                })
                .build()
            INSTANCE = instance
            instance
        }
    }

    private fun deleteDebugLogFile(deviceProtectedContext: Context) {
        try {
            val safeName = MySelf.name()?.trim() ?: "unknown_user"
            val filename = "$safeName.txt"
            val logFile = File(deviceProtectedContext.filesDir, filename)
            if (logFile.exists()) {
                logFile.delete()
                Log.d("##", "DatabaseProvider;Debug log file deleted after destructive migration")
            }
        } catch (e: Exception) {
            Log.e("##", "DatabaseProvider;Failed to delete log file: ${e.message}")
        }
    }
}