package com.bolimot.mindtheclub.desktop.spike

import com.bolimot.mindtheclub.desktop.backup.BackupEnvelope
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Phase 0, check 1: can a Windows program take over a MindTheClub identity from a backup?
 *
 * It opens a .mtcbackup written by the phone, rebuilds the identity key pair with Tink for
 * the JVM, and proves the pair is the phone's by comparing it with the public key the phone
 * published in Firestore:
 *
 *   A. the public key text is identical, so the fingerprint contacts verified is too;
 *   B. a message sealed to the PUBLISHED key opens with the key from the backup.
 *
 * Read only. Nothing is written to disk, nothing is written to the server, and the phone is
 * not affected: the identity is not claimed here, that is check 2.
 *
 * Output is plain ASCII on purpose: the Windows console mangles accented letters.
 */

private const val FIRESTORE_USERS =
    "https://firestore.googleapis.com/v1/projects/mindtheclub-new/databases/(default)/documents/users/"

fun main(args: Array<String>) {
    println()
    println("MindTheClub per Windows - Prova 1: lettura del backup")
    println("------------------------------------------------------")

    // 1. The file.
    val path = (args.firstOrNull() ?: ask("Trascina qui il file .mtcbackup (o scrivi il percorso) e premi Invio:"))
        .trim().trim('"')
    val file = File(path)
    if (!file.isFile) {
        fail("File non trovato: $path")
        return
    }

    // 2. The envelope.
    val password = ask("Scrivi la password del backup e premi Invio (resta visibile sullo schermo):")
    val clear = BackupEnvelope.open(file.readBytes(), password.toCharArray())
    if (clear == null) {
        fail("Il backup non si apre: password sbagliata, oppure il file non e' un backup di MindTheClub.")
        return
    }
    ok("Backup aperto (${file.length() / 1024} KB cifrati)")

    // 3. The content. Read as a generic tree: the Room entities are not copied here yet,
    //    and this check only needs the identity and a few counts.
    val backup = try {
        Json.parseToJsonElement(clear.toString(Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        fail("Il contenuto non e' il JSON atteso: ${e.message}")
        return
    }

    val userId = backup.text("userId") ?: ""
    println()
    println("  Nome            ${backup.text("name") ?: "(nessuno)"}")
    println("  userId          $userId")
    println("  Creato il       ${formatMillis(backup.text("timestamp")?.toLongOrNull())}")
    println("  Contatti        ${backup.count("peers")}")
    println("  Messaggi        ${backup.count("messages")}")
    println("  Reazioni        ${backup.count("reactions")}")
    println("  Bloccati        ${backup.count("blockedUsers")}")
    println("  Club            ${backup.count("clubs")}")
    println("  Media (nomi)    ${(backup["mediaFileNames"] as? JsonObject)?.size ?: 0}")
    println()

    if (userId.isEmpty()) {
        fail("Il backup non contiene un userId.")
        return
    }

    // 4. The identity.
    val keysetJson = backup.text("identityKeyset")
    if (keysetJson == null) {
        fail("Il backup non contiene la chiave di identita' (fatto da una versione vecchia della App).")
        return
    }
    val identity = try {
        IdentityKeys.fromBackupKeyset(keysetJson)
    } catch (e: Exception) {
        fail("Tink su Windows non legge la chiave del telefono: ${e.message}")
        return
    }
    ok("Chiave di identita' ricostruita, impronta ${identity.fingerprint}")

    // Sanity check with no network involved: seal to our own public key, open it again.
    val probe = "mtc-desktop-" + System.nanoTime()
    val localRoundTrip = try {
        identity.decrypt(IdentityKeys.encryptFor(identity.publicKey, probe)) == probe
    } catch (e: Exception) {
        false
    }
    if (!localRoundTrip) {
        fail("La chiave ricostruita non cifra e decifra correttamente su questo PC.")
        return
    }
    ok("Cifratura e decifratura funzionano su questo PC")

    // 5. Is it the phone's identity? Compare with what the phone published.
    println()
    println("Confronto con la chiave pubblicata dal telefono...")
    val published = try {
        fetchPublishedPublicKey(userId)
    } catch (e: Exception) {
        warn("Server non raggiungibile (${e.message}). Riprova con la rete attiva.")
        println()
        println("ESITO: PARZIALE. Il backup si legge e la chiave funziona, ma il confronto con il telefono non e' stato fatto.")
        return
    }
    if (published == null) {
        warn("Nessuna chiave pubblicata per questo userId: il telefono non ha mai completato la registrazione.")
        println()
        println("ESITO: PARZIALE. Il backup si legge e la chiave funziona, ma non c'e' nulla con cui confrontarla.")
        return
    }

    val sameKey = try {
        identity.decrypt(IdentityKeys.encryptFor(published, probe)) == probe
    } catch (e: Exception) {
        false
    }
    val sameText = published == identity.publicKey
    val publishedFingerprint = IdentityKeys.fingerprintOf(published)

    if (sameKey) ok("Un messaggio sigillato per il telefono si apre sul PC: la chiave e' la stessa")
    else fail("Un messaggio sigillato per il telefono NON si apre sul PC: la chiave e' diversa")

    if (sameText) ok("Impronta identica a quella del telefono ($publishedFingerprint)")
    else fail("Impronta diversa: telefono $publishedFingerprint, PC ${identity.fingerprint}")

    println()
    when {
        sameKey && sameText ->
            println("ESITO: SUPERATA. Questo PC puo' prendere l'identita' del telefono, e i contatti non vedrebbero alcun cambio di chiave.")
        sameKey ->
            println("ESITO: DA SISTEMARE. La chiave e' giusta ma Tink su Windows la scrive in modo diverso dal telefono, quindi l'impronta cambia. Va corretto prima di proseguire.")
        else ->
            println("ESITO: FALLITA. Il backup e' piu' vecchio della chiave pubblicata (il telefono ha cambiato identita' dopo il backup?), oppure la chiave non e' compatibile.")
    }
}

/**
 * users/{userId}.publicKey over the Firestore REST API, with no credentials: the security
 * rules allow anybody to get a single user document (C:\mtcShared\firestore.rules,
 * "match /users/{userId} { allow get: if true }"), which is how phones read each other's key.
 * Null when the document or the field does not exist.
 */
private fun fetchPublishedPublicKey(userId: String): String? {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val request = HttpRequest.newBuilder(URI.create(FIRESTORE_USERS + URLEncoder.encode(userId, Charsets.UTF_8)))
        .timeout(Duration.ofSeconds(20))
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 404) return null
    if (response.statusCode() != 200) throw IllegalStateException("risposta ${response.statusCode()}")

    val fields = Json.parseToJsonElement(response.body()).jsonObject["fields"] as? JsonObject ?: return null
    val publicKey = (fields["publicKey"] as? JsonObject)?.text("stringValue")
    return publicKey?.takeIf { it.isNotEmpty() }
}

/** A string or number field as text; null when absent or JSON null. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.count(key: String): Int = (this[key] as? JsonArray)?.size ?: 0

private fun formatMillis(millis: Long?): String {
    if (millis == null || millis <= 0L) return "(sconosciuto)"
    return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}

private fun ask(question: String): String {
    println(question)
    return readlnOrNull() ?: ""
}

private fun ok(message: String) = println("  [OK]   $message")
private fun warn(message: String) = println("  [??]   $message")
private fun fail(message: String) = println("  [NO]   $message")
