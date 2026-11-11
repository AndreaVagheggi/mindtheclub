package com.bolimot.mindtheclub.desktop.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
}
