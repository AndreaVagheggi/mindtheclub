package com.bolimot.mindtheclub.desktop.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/**
 * The sender's batch tables on the PC.
 *
 * A message being sent is not a queue in memory: it is one or more SQLite tables created at
 * send time, named batch<messageId><n>, one row per chunk with a "sent" flag. The batch table
 * IS the sender's state: a restarted program resumes from it, and "do the tables still exist"
 * is the test for "can I still serve this message" (C:\mtc\docs\arch\transport.md section 2).
 *
 * On the phone these tables live inside the main database and are reached through
 * SupportSQLiteDatabase and Cursor, neither of which exists outside Android. Here they live in
 * a file of their own, batches.db, opened with the SQLite driver directly. That is a deliberate
 * simplification and it costs nothing: batch tables are the one piece of sender state that
 * never needs to travel between phone and PC, because the engine rebuilds them from the saved
 * message when a peer asks for it (the sendMe handler does exactly that).
 *
 * One connection, used by one thread at a time. Nothing here suspends: read the rows, release,
 * and only then do slow work with them.
 */
object BatchDb {

    private val lock = Any()
    private var connection: SQLiteConnection? = null

    private fun file(): File = File(DataDir.root(), "batches.db")

    /** Runs [block] with the connection, opened on first use. */
    fun <T> use(block: (SQLiteConnection) -> T): T {
        synchronized(lock) {
            val open = connection ?: BundledSQLiteDriver().open(file().absolutePath).also { connection = it }
            return block(open)
        }
    }

    fun exec(sql: String) = use { it.execSQL(sql) }

    /**
     * Runs a query and maps every row. [args] are bound in order, 1 based as SQLite numbers
     * them; a String binds as text, a number as integer, null as NULL.
     */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), row: (SQLiteStatement) -> T): List<T> = use { db ->
        val statement = db.prepare(sql)
        try {
            bind(statement, args)
            val result = ArrayList<T>()
            while (statement.step()) result.add(row(statement))
            result
        } finally {
            statement.close()
        }
    }

    /** Runs a statement that returns no rows (INSERT, UPDATE, DELETE) with bound arguments. */
    fun update(sql: String, args: List<Any?>) = use { db ->
        val statement = db.prepare(sql)
        try {
            bind(statement, args)
            statement.step()
            Unit
        } finally {
            statement.close()
        }
    }

    /** All or nothing: the block's statements are committed together, or rolled back. */
    fun <T> transaction(block: () -> T): T = use { db ->
        db.execSQL("BEGIN IMMEDIATE")
        try {
            val result = block()
            db.execSQL("COMMIT")
            result
        } catch (e: Throwable) {
            try {
                db.execSQL("ROLLBACK")
            } catch (rollback: Throwable) {
                // The original failure is the one worth reporting.
            }
            throw e
        }
    }

    private fun bind(statement: SQLiteStatement, args: List<Any?>) {
        for ((index, value) in args.withIndex()) {
            when (value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
                is Number -> statement.bindLong(index + 1, value.toLong())
                else -> statement.bindText(index + 1, value.toString())
            }
        }
    }

    /** Closes the file and deletes it: used by a load that replaces everything. */
    fun deleteFile() {
        synchronized(lock) {
            try {
                connection?.close()
            } catch (e: Exception) {
                // Nothing to do.
            }
            connection = null
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file().absolutePath + suffix).delete()
        }
    }
}
