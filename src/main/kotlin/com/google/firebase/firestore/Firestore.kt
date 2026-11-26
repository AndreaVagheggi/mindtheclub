package com.google.firebase.firestore

import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Date

/*
 * DESKTOP: a stand-in for the part of the Firestore SDK the App's group code uses, over
 * Firestore's REST API: one document read by id, updated field by field (also with
 * FieldValue.increment and FieldValue.delete), written whole, deleted. Nothing is listed,
 * queried or listened to, and there is no offline cache: a read always goes to the server.
 *
 * A phone has no Firebase credentials either (there is no Firebase Authentication in
 * MindTheClub), so every call here is judged by the same rules as the phone's
 * (C:\mtcShared\firestore.rules): nothing new is allowed on the server.
 *
 * The SDK's semantics kept, because the App's code relies on them:
 *   - update() fails when the document does not exist (currentDocument.exists);
 *   - a key of update() is a field PATH: "members.<userId>" changes one entry of the map;
 *   - set() replaces the whole document;
 *   - FieldValue.increment and serverTimestamp are applied by the server, so two devices
 *     incrementing at once cannot lose a count.
 */

private const val PROJECT_DOCUMENTS = "projects/mindtheclub-new/databases/(default)/documents"
private const val API = "https://firestore.googleapis.com/v1/"

/** Firebase.firestore. */
val Firebase.firestore: FirebaseFirestore
    get() = FirebaseFirestore.getInstance()

/** A Firestore call refused or failed. [status] is Firestore's, e.g. NOT_FOUND. */
class FirebaseFirestoreException(message: String, val status: String) : Exception(message)

class FirebaseFirestore private constructor() {

    fun collection(collectionPath: String): CollectionReference = CollectionReference(collectionPath)

    companion object {
        private val instance = FirebaseFirestore()

        @JvmStatic
        fun getInstance(): FirebaseFirestore = instance

        internal val client: HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }
}

class CollectionReference internal constructor(val path: String) {
    val id: String get() = path.substringAfterLast('/')

    fun document(documentPath: String): DocumentReference = DocumentReference("$path/$documentPath")
}

class DocumentReference internal constructor(val path: String) {

    val id: String get() = path.substringAfterLast('/')

    private val name: String get() = "$PROJECT_DOCUMENTS/$path"

    fun get(): Task<DocumentSnapshot> = Task.start {
        val url = API + PROJECT_DOCUMENTS + "/" + path.split('/').joinToString("/") { encodeSegment(it) }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = FirebaseFirestore.client.send(request, HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            404 -> DocumentSnapshot(id, null)
            200 -> {
                val fields = Json.parseToJsonElement(response.body()).jsonObject["fields"] as? JsonObject
                DocumentSnapshot(id, fields?.let { decodeFields(it) } ?: emptyMap())
            }
            else -> throw failure(response)
        }
    }

    /** One field (a path) to one value, plus more pairs: update("a", 1, "b.c", true). */
    fun update(field: String, value: Any?, vararg moreFieldsAndValues: Any?): Task<Void?> {
        val updates = LinkedHashMap<String, Any?>()
        updates[field] = value
        var i = 0
        while (i + 1 < moreFieldsAndValues.size) {
            updates[moreFieldsAndValues[i] as String] = moreFieldsAndValues[i + 1]
            i += 2
        }
        return update(updates)
    }

    /** Each key is a field path; the document must exist. */
    fun update(data: Map<String, Any?>): Task<Void?> = Task.start {
        val mask = ArrayList<String>()
        val transforms = ArrayList<JsonObject>()
        val fields = LinkedHashMap<String, Any?>()
        for ((path, value) in data) {
            val segments = path.split('.')
            val fieldPath = segments.joinToString(".") { quoteSegment(it) }
            when (value) {
                is FieldValue.Delete -> mask.add(fieldPath)
                is FieldValue.Increment, is FieldValue.ServerTimestamp -> transforms.add(transformOf(fieldPath, value as FieldValue))
                else -> {
                    mask.add(fieldPath)
                    putPath(fields, segments, value)
                }
            }
        }
        val write = buildJsonObject {
            put("update", buildJsonObject {
                put("name", name)
                put("fields", encodeFields(fields))
            })
            put("updateMask", buildJsonObject {
                put("fieldPaths", buildJsonArray { mask.forEach { add(JsonPrimitive(it)) } })
            })
            if (transforms.isNotEmpty()) put("updateTransforms", JsonArray(transforms))
            put("currentDocument", buildJsonObject { put("exists", true) })
        }
        commit(write)
        null
    }

    /** Replaces the whole document. */
    fun set(data: Map<String, Any?>): Task<Void?> = Task.start {
        val transforms = ArrayList<JsonObject>()
        val fields = LinkedHashMap<String, Any?>()
        for ((key, value) in data) {
            when (value) {
                is FieldValue.Increment, is FieldValue.ServerTimestamp -> transforms.add(transformOf(quoteSegment(key), value as FieldValue))
                is FieldValue.Delete -> Unit
                else -> fields[key] = value
            }
        }
        val write = buildJsonObject {
            put("update", buildJsonObject {
                put("name", name)
                put("fields", encodeFields(fields))
            })
            if (transforms.isNotEmpty()) put("updateTransforms", JsonArray(transforms))
        }
        commit(write)
        null
    }

    fun delete(): Task<Void?> = Task.start {
        commit(buildJsonObject { put("delete", name) })
        null
    }

    private fun commit(write: JsonObject) {
        val body = buildJsonObject { put("writes", buildJsonArray { add(write) }) }.toString()
        val request = HttpRequest.newBuilder(URI.create("$API$PROJECT_DOCUMENTS:commit"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = FirebaseFirestore.client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw failure(response)
    }
}

/** Metadata of a read: always from the server here, there is no cache. */
class SnapshotMetadata internal constructor() {
    val isFromCache: Boolean get() = false
}

class DocumentSnapshot internal constructor(val id: String, private val fields: Map<String, Any?>?) {

    val metadata: SnapshotMetadata = SnapshotMetadata()

    /** The fields, or null when the document does not exist. */
    val data: Map<String, Any?>? get() = fields

    fun exists(): Boolean = fields != null

    fun contains(field: String): Boolean = get(field) != null

    /** A field, or a path into maps ("members.<userId>"); null when absent. */
    fun get(field: String): Any? {
        var current: Any? = fields ?: return null
        for (segment in field.split('.')) {
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }

    fun getString(field: String): String? = get(field) as? String

    fun getLong(field: String): Long? = (get(field) as? Number)?.toLong()

    fun getDouble(field: String): Double? = (get(field) as? Number)?.toDouble()

    fun getBoolean(field: String): Boolean? = get(field) as? Boolean

    fun getTimestamp(field: String): Timestamp? = get(field) as? Timestamp

    fun getDate(field: String): Date? = getTimestamp(field)?.toDate()
}

/** The SDK's special values: applied by the server, not written as they are. */
sealed class FieldValue {
    internal object Delete : FieldValue()
    internal object ServerTimestamp : FieldValue()
    internal class Increment(val by: Number) : FieldValue()

    companion object {
        @JvmStatic fun delete(): FieldValue = Delete
        @JvmStatic fun serverTimestamp(): FieldValue = ServerTimestamp
        @JvmStatic fun increment(l: Long): FieldValue = Increment(l)
        @JvmStatic fun increment(d: Double): FieldValue = Increment(d)
    }
}

// ------------------------------------------------------------------ the REST encoding

private fun failure(response: HttpResponse<String>): FirebaseFirestoreException {
    val error = try {
        Json.parseToJsonElement(response.body()).jsonObject["error"] as? JsonObject
    } catch (e: Exception) {
        null
    }
    val status = (error?.get("status") as? JsonPrimitive)?.contentOrNull ?: "HTTP_${response.statusCode()}"
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Firestore answered ${response.statusCode()}"
    return FirebaseFirestoreException(message, status)
}

private fun encodeSegment(segment: String): String =
    URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

/** A segment of a field path: as it is when it is a plain name, else between backquotes. */
private fun quoteSegment(segment: String): String =
    if (segment.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) segment
    else "`" + segment.replace("\\", "\\\\").replace("`", "\\`") + "`"

@Suppress("UNCHECKED_CAST")
private fun putPath(fields: MutableMap<String, Any?>, segments: List<String>, value: Any?) {
    var map = fields
    for (segment in segments.dropLast(1)) {
        map = map.getOrPut(segment) { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>
    }
    map[segments.last()] = value
}

private fun transformOf(fieldPath: String, value: FieldValue): JsonObject = buildJsonObject {
    put("fieldPath", fieldPath)
    when (value) {
        is FieldValue.Increment -> put("increment", encodeValue(value.by))
        is FieldValue.ServerTimestamp -> put("setToServerValue", "REQUEST_TIME")
        else -> Unit
    }
}

private fun encodeFields(fields: Map<String, Any?>): JsonObject =
    JsonObject(fields.mapValues { (_, value) -> encodeValue(value) })

private fun encodeValue(value: Any?): JsonElement = when (value) {
    null -> buildJsonObject { put("nullValue", "NULL_VALUE") }
    is Boolean -> buildJsonObject { put("booleanValue", value) }
    is Int, is Long, is Short, is Byte -> buildJsonObject { put("integerValue", value.toString()) }
    is Float, is Double -> buildJsonObject { put("doubleValue", (value as Number).toDouble()) }
    is String -> buildJsonObject { put("stringValue", value) }
    is Timestamp -> buildJsonObject { put("timestampValue", value.toInstant().toString()) }
    is Date -> buildJsonObject { put("timestampValue", value.toInstant().toString()) }
    is Map<*, *> -> buildJsonObject {
        put("mapValue", buildJsonObject {
            put("fields", JsonObject(value.entries.associate { (k, v) -> k.toString() to encodeValue(v) }))
        })
    }
    is Iterable<*> -> buildJsonObject {
        put("arrayValue", buildJsonObject { put("values", JsonArray(value.map { encodeValue(it) })) })
    }
    is Array<*> -> encodeValue(value.toList())
    is FieldValue -> throw IllegalArgumentException("FieldValue is only allowed as a top level value")
    else -> throw IllegalArgumentException("Unsupported Firestore value: ${value.javaClass.name}")
}

private fun decodeFields(fields: JsonObject): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    for ((key, value) in fields) result[key] = decodeValue(value)
    return result
}

private fun decodeValue(element: JsonElement): Any? {
    val value = element as? JsonObject ?: return null
    val (kind, content) = value.entries.firstOrNull() ?: return null
    val primitive = content as? JsonPrimitive
    return when (kind) {
        "nullValue" -> null
        "booleanValue" -> primitive?.booleanOrNull
        "integerValue" -> primitive?.contentOrNull?.toLongOrNull()
        "doubleValue" -> primitive?.contentOrNull?.toDoubleOrNull()
        "stringValue" -> primitive?.contentOrNull
        "timestampValue" -> primitive?.contentOrNull?.let {
            val instant = Instant.parse(it)
            Timestamp(instant.epochSecond, instant.nano)
        }
        "mapValue" -> ((content as? JsonObject)?.get("fields") as? JsonObject)?.let { decodeFields(it) } ?: emptyMap<String, Any?>()
        "arrayValue" -> ((content as? JsonObject)?.get("values") as? JsonArray)?.map { decodeValue(it) } ?: emptyList<Any?>()
        else -> primitive?.contentOrNull
    }
}
