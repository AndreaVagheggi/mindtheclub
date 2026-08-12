package com.bolimot.mindtheclub.database.database

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bolimot.mindtheclub.tools.MySelf
import java.io.File

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    // This database (device protected storage) holds ALL user data: Peer,
    // Message, Inbox, Club, everything the user sees. Two rules keep it safe:
    //
    // 1. Migrations come exclusively from AppDatabase.ALL_MIGRATIONS. This file
    //    used to carry its own hand-copied list, which silently stopped at
    //    1061_1062 while the class version moved on: the first in-place upgrade
    //    after that found no migration path and the destructive fallback erased
    //    every table (12 Aug: all contacts and messages wiped).
    //
    // 2. The destructive fallback is allowed ONLY for downgrades (installing an
    //    older build over a newer database, a development-only scenario). A
    //    missing forward migration must CRASH, loudly, in testing: for user
    //    data a crash is recoverable, a silent wipe is not.
    fun provideDatabase(context: Context): AppDatabase {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                deviceProtectedContext,
                AppDatabase::class.java,
                "mtc.db"
            )
                .addMigrations(*AppDatabase.ALL_MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade(false)
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