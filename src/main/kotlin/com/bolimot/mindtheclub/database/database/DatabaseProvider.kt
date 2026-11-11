package com.bolimot.mindtheclub.database.database

// DESKTOP: this file is NOT a copy of the App's DatabaseProvider.kt. Same object, same role
// (the one place that opens the database holding ALL user data), built with Room's JVM
// builder and the SQLite library bundled with the program instead of Android's.

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bolimot.mindtheclub.desktop.storage.DataDir
import kotlinx.coroutines.Dispatchers
import java.io.File

object DatabaseProvider {

    @Volatile
    private var INSTANCE: AppDatabase? = null

    /**
     * The database, opened on first use. No migrations and NO destructive fallback: a file
     * written with another schema version makes Room throw, and for user data a crash is
     * recoverable while a silent wipe is not. See AppDatabase for what to do when the App's
     * schema version changes.
     */
    fun provideDatabase(): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder<AppDatabase>(name = databaseFile().absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
                .also { INSTANCE = it }
        }
    }

    fun databaseFile(): File = File(DataDir.root(), "mtc.db")

    fun exists(): Boolean = databaseFile().isFile

    /** Closes the database if it is open. The next provideDatabase() reopens it. */
    fun close() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }

    /**
     * Removes the database from disk, for a load that REPLACES everything. Closes it first.
     * Returns false when a file could not be deleted (another copy of the program has it open).
     */
    fun deleteFiles(): Boolean {
        close()
        var clean = true
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val file = File(databaseFile().absolutePath + suffix)
            if (file.exists() && !file.delete()) clean = false
        }
        return clean
    }
}
