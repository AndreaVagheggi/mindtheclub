package com.bolimot.mindtheclub.desktop.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A named key-value file: what SharedPreferences is on the phone.
 *
 * The App keeps a lot of small durable state this way, and the recovery layer depends on it
 * surviving a restart (the four ladders in C:\mtc\docs\arch\recovery.md section 4 are all
 * SharedPreferences backed). Names used here are the App's own names, so the data means the
 * same on both sides: the profile is in "default" under myUserId, myName and so on
 * (functions/utilities.kt, tools/objects.kt MySelf).
 *
 * One JSON object per file, written whole and atomically on every change (write to a temp
 * file, then rename): a crash leaves the old file or the new one, never half of each. That is
 * fine for files of a few kilobytes; nothing large belongs here.
 */
class Prefs private constructor(private val file: File) {

    private val values = HashMap<String, JsonPrimitive>()

    init {
        if (file.isFile) {
            try {
                for ((key, value) in Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject) {
                    if (value is JsonPrimitive) values[key] = value
                }
            } catch (e: Exception) {
                // An unreadable file is treated as empty rather than stopping the program; the
                // next write replaces it.
            }
        }
    }

    @Synchronized fun getString(key: String): String? = values[key]?.takeIf { it.isString }?.contentOrNull
    @Synchronized fun getLong(key: String): Long? = values[key]?.longOrNull
    @Synchronized fun getBoolean(key: String): Boolean? = values[key]?.booleanOrNull
    @Synchronized fun contains(key: String): Boolean = values.containsKey(key)
    @Synchronized fun keys(): Set<String> = HashSet(values.keys)

    @Synchronized fun putString(key: String, value: String) = put(key, JsonPrimitive(value))
    @Synchronized fun putLong(key: String, value: Long) = put(key, JsonPrimitive(value))
    @Synchronized fun putBoolean(key: String, value: Boolean) = put(key, JsonPrimitive(value))

    @Synchronized
    fun remove(key: String) {
        if (values.remove(key) != null) save()
    }

    @Synchronized
    fun clear() {
        values.clear()
        save()
    }

    private fun put(key: String, value: JsonPrimitive) {
        values[key] = value
        save()
    }

    private fun save() {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JsonObject(values).toString(), Charsets.UTF_8)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private val open = ConcurrentHashMap<String, Prefs>()

        /** The file of that name, one shared instance per name, as on the phone. */
        fun of(name: String): Prefs {
            require(name.matches(Regex("^[A-Za-z0-9_.-]+$"))) { "invalid preferences name: $name" }
            return open.computeIfAbsent(name) { Prefs(File(DataDir.prefs(), "$it.json")) }
        }

        /** Deletes every preferences file: used by a load that replaces everything. */
        fun deleteAll() {
            open.clear()
            DataDir.clear(DataDir.prefs())
        }
    }
}
