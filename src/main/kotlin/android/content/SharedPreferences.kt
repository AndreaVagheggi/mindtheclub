package android.content

import com.bolimot.mindtheclub.desktop.storage.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * A stand-in for Android's SharedPreferences, limited to what the App's engine uses, and
 * backed by the PC's own preferences files (desktop/storage/Prefs.kt).
 *
 * The recovery layer keeps its ladders here (IncomingPendingTracker, RecoveryProgress,
 * CancelledTransferRegistry, PendingMessageTracker and others), and they must survive a
 * restart exactly as they do on the phone. They do: every edit is written to disk before
 * apply() or commit() returns.
 *
 * One difference worth knowing: numbers are stored as JSON numbers, so a value written with
 * putInt comes back from [all] as a Long, where Android would return an Int. getInt and
 * getLong both read it correctly.
 */
interface SharedPreferences {

    fun getString(key: String, defValue: String?): String?
    fun getLong(key: String, defValue: Long): Long
    fun getInt(key: String, defValue: Int): Int
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean

    /** Every entry: String, Long or Boolean values. */
    val all: Map<String, *>

    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putLong(key: String, value: Long): Editor
        fun putInt(key: String, value: Int): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

internal class DesktopSharedPreferences private constructor(private val prefs: Prefs) : SharedPreferences {

    override fun getString(key: String, defValue: String?): String? = prefs.getString(key) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = prefs.getLong(key) ?: defValue
    override fun getInt(key: String, defValue: Int): Int = prefs.getLong(key)?.toInt() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key) ?: defValue
    override fun contains(key: String): Boolean = prefs.contains(key)

    override val all: Map<String, *>
        get() {
            val result = HashMap<String, Any?>()
            for (key in prefs.keys()) {
                result[key] = prefs.getString(key) ?: prefs.getBoolean(key) ?: prefs.getLong(key)
            }
            return result
        }

    override fun edit(): SharedPreferences.Editor = DesktopEditor()

    /** Collects the changes and writes them, in order, when apply() or commit() is called. */
    private inner class DesktopEditor : SharedPreferences.Editor {
        private val changes = ArrayList<(Prefs) -> Unit>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            // Android: putString with null is a remove.
            if (value == null) changes.add { it.remove(key) } else changes.add { it.putString(key, value) }
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            changes.add { it.putLong(key, value) }
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            changes.add { it.putLong(key, value.toLong()) }
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            changes.add { it.putBoolean(key, value) }
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            changes.add { it.remove(key) }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            changes.add { it.clear() }
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            return try {
                for (change in changes) change(prefs)
                changes.clear()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        private val open = ConcurrentHashMap<String, DesktopSharedPreferences>()

        fun of(name: String): DesktopSharedPreferences =
            open.computeIfAbsent(name) { DesktopSharedPreferences(Prefs.of(it)) }

        /** Forgets every open file: used by a load that replaces everything. */
        fun forgetAll() = open.clear()
    }
}
