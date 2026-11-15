package com.bolimot.mindtheclub.desktop.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

/**
 * The shared MindTheClub backend, reached over plain HTTPS.
 *
 * There is no official Firebase client for a desktop JVM, and none is needed for this:
 *   - a callable Cloud Function is a POST of {"data": ...} answering {"result": ...} or
 *     {"error": {"status", "message"}};
 *   - a single Firestore document is a GET on the REST API, judged by the same security rules
 *     as a phone (C:\mtcShared\firestore.rules).
 *
 * Nothing here is new on the server: these are the calls the de-Google phone app (mtcx)
 * already makes through the Firebase SDK.
 */
object Backend {

    private const val FUNCTIONS = "https://europe-west1-mindtheclub-new.cloudfunctions.net/"
    private const val FIRESTORE_USERS =
        "https://firestore.googleapis.com/v1/projects/mindtheclub-new/databases/(default)/documents/users/"
    private const val FIRESTORE_COMMIT =
        "https://firestore.googleapis.com/v1/projects/mindtheclub-new/databases/(default)/documents:commit"
    private const val DOCUMENT_NAME_PREFIX = "projects/mindtheclub-new/databases/(default)/documents/"

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** A callable function refused the request. [status] is e.g. PERMISSION_DENIED. */
    class CallableException(val status: String, message: String) : Exception(message)

    /** Calls a callable Cloud Function and returns its result object. */
    fun call(name: String, data: JsonObject): JsonObject {
        val body = buildJsonObject { put("data", data) }.toString()
        val request = HttpRequest.newBuilder(URI.create(FUNCTIONS + name))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        val root = try {
            Json.parseToJsonElement(response.body()).jsonObject
        } catch (e: Exception) {
            throw CallableException("HTTP_${response.statusCode()}", "risposta non leggibile da $name")
        }
        val error = root["error"] as? JsonObject
        if (error != null) {
            throw CallableException(
                (error["status"] as? JsonPrimitive)?.contentOrNull ?: "HTTP_${response.statusCode()}",
                (error["message"] as? JsonPrimitive)?.contentOrNull ?: "errore da $name"
            )
        }
        if (response.statusCode() != 200) {
            throw CallableException("HTTP_${response.statusCode()}", "errore da $name")
        }
        return root["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /**
     * users/{userId}.publicKey, the text the identity's current owner published. Null when the
     * document or the field does not exist. Throws when the server cannot be reached.
     */
    fun publishedPublicKey(userId: String): String? =
        userDocument(userId)?.get("publicKey")?.takeIf { it.isNotEmpty() }

    /**
     * The text fields of users/{userId}, or null when the document does not exist. Throws when
     * the server cannot be reached. What matters to a device about its OWN document is
     * publicKey and installationId, the marker of which installation holds the identity.
     */
    fun userDocument(userId: String): Map<String, String>? {
        val request = HttpRequest.newBuilder(URI.create(FIRESTORE_USERS + URLEncoder.encode(userId, Charsets.UTF_8)))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 200) throw IllegalStateException("risposta ${response.statusCode()}")

        val fields = Json.parseToJsonElement(response.body()).jsonObject["fields"] as? JsonObject
            ?: return emptyMap()
        val result = HashMap<String, String>()
        for ((name, value) in fields) {
            val text = ((value as? JsonObject)?.get("stringValue") as? JsonPrimitive)?.contentOrNull ?: continue
            result[name] = text
        }
        return result
    }

    // ------------------------------------------------------------------ contact requests

    /*
     * users/{owner}/requests/{fromUserId}: the inbox of contact requests (firestore.rules,
     * "Contact request inbox"). Whoever asks writes its request into the inbox of the person
     * it asks; the owner reads and lists its own inbox; both delete. The rules allow exactly
     * this to a client with no credentials, which is what a phone is too: nothing is new on
     * the server.
     */

    /** One contact request document, as contactAcquisition reads it from a DocumentSnapshot. */
    class ContactRequest(
        val fromUserId: String,
        val name: String?,
        val bio: String?,
        val picture: String?,
        val fingerprint: String?,
        val enc: Boolean
    )

    /**
     * writeMySelfOnRemotePeerFirestore: the request of [fromUserId] in the inbox of [toUserId],
     * written whole (the App's set(), which replaces the document) with the server's time in
     * "timestamp" (FieldValue.serverTimestamp()). Throws when the server refuses or cannot be
     * reached.
     */
    fun writeContactRequest(
        toUserId: String,
        fromUserId: String,
        name: String?,
        bio: String?,
        picture: String?,
        fingerprint: String?,
        enc: Boolean
    ) {
        fun text(value: String?): JsonElement =
            if (value == null) buildJsonObject { put("nullValue", JsonNull) } else buildJsonObject { put("stringValue", value) }

        val body = buildJsonObject {
            put("writes", buildJsonArray {
                add(buildJsonObject {
                    put("update", buildJsonObject {
                        put("name", DOCUMENT_NAME_PREFIX + "users/$toUserId/requests/$fromUserId")
                        put("fields", buildJsonObject {
                            put("userId", text(fromUserId))
                            put("name", text(name))
                            put("bio", text(bio))
                            put("picture", text(picture))
                            put("fingerprint", text(fingerprint))
                            put("enc", buildJsonObject { put("booleanValue", enc) })
                        })
                    })
                    put("updateTransforms", buildJsonArray {
                        add(buildJsonObject {
                            put("fieldPath", "timestamp")
                            put("setToServerValue", "REQUEST_TIME")
                        })
                    })
                })
            })
        }.toString()

        val request = HttpRequest.newBuilder(URI.create(FIRESTORE_COMMIT))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IllegalStateException("risposta ${response.statusCode()}")
    }

    /**
     * The request of [fromUserId] in the inbox of [ownerUserId], or null when there is none.
     * Throws when the server cannot be reached.
     */
    fun contactRequest(ownerUserId: String, fromUserId: String): ContactRequest? {
        val request = HttpRequest.newBuilder(URI.create(requestsUrl(ownerUserId) + "/" + segment(fromUserId)))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 200) throw IllegalStateException("risposta ${response.statusCode()}")
        return contactRequestOf(Json.parseToJsonElement(response.body()).jsonObject)
    }

    /** Every request in the inbox of [ownerUserId]. Throws when the server cannot be reached. */
    fun contactRequests(ownerUserId: String): List<ContactRequest> {
        val result = ArrayList<ContactRequest>()
        var pageToken: String? = null
        do {
            val url = requestsUrl(ownerUserId) + "?pageSize=100" +
                    (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) throw IllegalStateException("risposta ${response.statusCode()}")
            val root = Json.parseToJsonElement(response.body()).jsonObject
            (root["documents"] as? JsonArray)?.forEach { document ->
                (document as? JsonObject)?.let { contactRequestOf(it) }?.let { result.add(it) }
            }
            pageToken = (root["nextPageToken"] as? JsonPrimitive)?.contentOrNull
        } while (!pageToken.isNullOrEmpty())
        return result
    }

    /** Deletes the request of [fromUserId] from the inbox of [ownerUserId]. Throws on failure. */
    fun deleteContactRequest(ownerUserId: String, fromUserId: String) {
        val request = HttpRequest.newBuilder(URI.create(requestsUrl(ownerUserId) + "/" + segment(fromUserId)))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        // Deleting a document that is not there is a success, as with the Firebase SDK.
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw IllegalStateException("risposta ${response.statusCode()}")
        }
    }

    private fun segment(id: String): String = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

    private fun requestsUrl(ownerUserId: String): String = FIRESTORE_USERS + segment(ownerUserId) + "/requests"

    /** A REST document: its id is the last part of its name. */
    private fun contactRequestOf(document: JsonObject): ContactRequest? {
        val name = (document["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val fields = document["fields"] as? JsonObject ?: JsonObject(emptyMap())
        fun text(field: String): String? =
            ((fields[field] as? JsonObject)?.get("stringValue") as? JsonPrimitive)?.contentOrNull
        return ContactRequest(
            fromUserId = name.substringAfterLast('/'),
            name = text("name"),
            bio = text("bio"),
            picture = text("picture"),
            fingerprint = text("fingerprint"),
            enc = ((fields["enc"] as? JsonObject)?.get("booleanValue") as? JsonPrimitive)?.booleanOrNull == true
        )
    }
}
