package com.bolimot.mindtheclub.desktop.spike

import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.desktop.backup.BackupEnvelope
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import com.bolimot.mindtheclub.desktop.push.NtfySubscriber
import com.bolimot.mindtheclub.desktop.push.WebPushKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/*
 * Phase 0, check 2: can a Windows program receive MindTheClub's signals in place of the phone?
 *
 * It takes the identity from a backup, opens a push channel of its own, registers that channel
 * on the backend with proof that it holds the identity key, and then prints every signal that
 * arrives: pending, dataCall, sendMe and so on, opened with the identity key.
 *
 * UNLIKE CHECK 1 THIS ONE WRITES TO THE SERVER. Registering moves the identity here:
 * users/{id}.installationId changes and the FCM token is removed (updateMyPushEndpoint.js),
 * so the phone the backup came from deactivates itself at its next start. Test identities
 * only, never a production one.
 *
 * It only listens. It does not answer a dataCall and opens no connection: that is check 3.
 * The sender therefore sees its message stay pending, which is correct.
 *
 * What is printed is the signal's type and who sent it. Both are sealed on the wire (sealed
 * sender) and are shown here only to the identity's owner, on the screen: never write them to
 * a file.
 */

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

fun main(args: Array<String>) {
    println()
    println("MindTheClub per Windows - Prova 2: ricezione dei segnali")
    println("---------------------------------------------------------")

    // 0. Before anything touches the server: does this PC decrypt what the server encrypts?
    if (!WebPushKeys.selfTest()) {
        fail("La decifratura Web Push non funziona su questo PC. Mi fermo prima di registrare qualcosa.")
        return
    }
    ok("Decifratura Web Push verificata")

    // 1. The identity, from the backup.
    val path = (args.firstOrNull() ?: ask("Trascina qui il file .mtcbackup (o scrivi il percorso) e premi Invio:"))
        .trim().trim('"')
    val file = File(path)
    if (!file.isFile) {
        fail("File non trovato: $path")
        return
    }
    val password = ask("Scrivi la password del backup e premi Invio (resta visibile sullo schermo):")
    val clear = BackupEnvelope.open(file.readBytes(), password.toCharArray())
    if (clear == null) {
        fail("Il backup non si apre: password sbagliata, oppure il file non e' un backup di MindTheClub.")
        return
    }
    val backup = try {
        Json.parseToJsonElement(clear.toString(Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        fail("Il contenuto non e' il JSON atteso: ${e.message}")
        return
    }
    val userId = backup.text("userId") ?: ""
    val name = backup.text("name") ?: "(senza nome)"
    val keysetJson = backup.text("identityKeyset")
    if (userId.isEmpty() || keysetJson == null) {
        fail("Il backup non contiene un'identita' completa.")
        return
    }
    val identity = try {
        IdentityKeys.fromBackupKeyset(keysetJson)
    } catch (e: Exception) {
        fail("Tink su Windows non legge la chiave del telefono: ${e.message}")
        return
    }
    ok("Identita' letta dal backup: $name")

    // userId -> name of the contacts, to show who a signal comes from.
    val contacts = HashMap<String, String>()
    for (peer in (backup["peers"] as? JsonArray).orEmpty()) {
        val p = peer as? JsonObject ?: continue
        val id = p.text("userId") ?: continue
        contacts[id] = p.text("name") ?: id
    }

    // 2. Never publish a key text that differs from the phone's: every contact would get a
    //    key change dialog for a key that did not change (see IdentityKeys.publicKey).
    val published = try {
        Backend.publishedPublicKey(userId)
    } catch (e: Exception) {
        fail("Server non raggiungibile (${e.message}). Riprova con la rete attiva.")
        return
    }
    if (published != identity.publicKey) {
        fail("La chiave del backup non e' quella pubblicata. Rifai la prova 1 con un backup appena fatto.")
        return
    }
    ok("La chiave coincide con quella pubblicata")

    // 3. The point of no return, said plainly.
    println()
    println("ATTENZIONE. Questa prova sposta l'identita' \"$name\" su questo PC:")
    println("  - il telefono da cui viene il backup si disattiva al prossimo avvio;")
    println("  - se quel telefono ha la App di Google Play, oggi NON riesce a riprendersi l'identita'")
    println("    da solo con un restore (serve la correzione a updateMyFcmToken, oppure un intervento")
    println("    a mano sul server). Con la App mtcx invece ci riesce.")
    println("Usa solo un'identita' di prova.")
    val answer = ask("Scrivi SI e premi Invio per continuare, qualunque altra cosa per uscire:")
    if (answer.trim().uppercase() != "SI") {
        println("Uscita senza toccare nulla.")
        return
    }

    // 4. Our own push channel. Subscribed BEFORE it is registered, so nothing is missed.
    val pushKeys = WebPushKeys.generate()
    val received = AtomicInteger(0)
    val subscriber = NtfySubscriber(
        onMessage = { body -> onPush(body, pushKeys, identity, contacts, received) },
        onClosed = { reason -> warn("Canale push interrotto: $reason. Chiudi e rilancia la prova.") }
    )
    if (!subscriber.connect()) {
        fail("Non riesco ad aprire il canale push (ntfy.sh non raggiungibile).")
        return
    }
    ok("Canale push aperto")

    // 5. Registration, with proof of possession of the identity key (getPushChallenge.js,
    //    updateMyPushEndpoint.js): the server seals a short lived token to the published key,
    //    and only the holder of the private key can send it back.
    try {
        val sealedChallenge = Backend.call("getPushChallenge", buildJsonObject { put("userId", userId) })
            .text("challenge")
        if (sealedChallenge == null) {
            fail("Il server non conosce questa identita'.")
            return
        }
        val proof = identity.decrypt(sealedChallenge)
        ok("Prova di possesso della chiave superata")

        Backend.call("updateMyPushEndpoint", buildJsonObject {
            put("userId", userId)
            put("proof", proof)
            put("endpoint", subscriber.endpoint)
            put("p256dh", pushKeys.p256dh)
            put("auth", pushKeys.auth)
            put("publicKey", identity.publicKey)
            // A new installation, as on any phone that restores a backup
            // (functions/InstallationIdentity.kt): this is what the old one notices.
            put("installationId", UUID.randomUUID().toString().replace("-", ""))
            // The trial clock belongs to the identity; the server only ever moves it earlier.
            backup.text("trialStartedAt")?.toLongOrNull()?.let { put("trialStartedAt", it) }
        })
    } catch (e: Backend.CallableException) {
        fail("Registrazione rifiutata dal server: ${e.status} (${e.message})")
        return
    } catch (e: Exception) {
        fail("Registrazione non riuscita: ${e.message}")
        return
    }
    ok("Registrato: i segnali per \"$name\" ora arrivano a questo PC")

    // 6. Listen.
    println()
    println("IN ASCOLTO. Da un altro telefono che ha \"$name\" fra i contatti, manda un messaggio di testo.")
    println("Qui devono comparire i segnali (di solito prima dataCall, poi pending entro un minuto).")
    println("Il messaggio sul telefono restera' \"Inviato\" senza consegna: e' giusto, questa prova ascolta soltanto.")
    println("Premi Invio per chiudere.")
    println()
    readlnOrNull()

    println()
    if (received.get() > 0) {
        println("ESITO: SUPERATA. Ricevuti e aperti ${received.get()} segnali.")
    } else {
        println("ESITO: NESSUN SEGNALE. La registrazione e' riuscita ma non e' arrivato nulla.")
    }
    // The push connection keeps threads alive: end the program for certain.
    kotlin.system.exitProcess(0)
}

/**
 * One push message, exactly what MyFirebaseMessagingService does with it on a phone (mtcx,
 * onMessage then openPayload): open the Web Push layer, read the routing map, open the part
 * sealed to the identity.
 */
private fun onPush(
    body: ByteArray,
    pushKeys: WebPushKeys,
    identity: IdentityKeys,
    contacts: Map<String, String>,
    received: AtomicInteger
) {
    val now = LocalTime.now().format(clock)

    val raw = try {
        Json.parseToJsonElement(String(pushKeys.open(body), Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        fail("$now  messaggio push non decifrabile: ${e.message}")
        return
    }

    // sendUnifiedPush stamps when it sent the message and how long it is worth; a phone
    // drops what is past its time (mtcx, isExpired). Here it is only reported.
    val sentAt = raw.text("upSentAt")?.toLongOrNull()
    val ttlSeconds = raw.text("upTtl")?.toLongOrNull()
    val ageMs = sentAt?.let { System.currentTimeMillis() - it }
    val timing = when {
        ageMs == null -> ""
        ttlSeconds != null && ageMs > ttlSeconds * 1000 -> "  (arrivato dopo ${ageMs / 1000}s, SCADUTO: valeva ${ttlSeconds}s)"
        else -> "  (arrivato in ${ageMs} ms)"
    }

    val content = if (raw.text("enc") == "1") {
        val sealed = raw.text("payload")
        try {
            Json.parseToJsonElement(identity.decrypt(sealed ?: "")).jsonObject
        } catch (e: Exception) {
            fail("$now  segnale arrivato ma NON apribile con la chiave di identita': ${e.message}")
            return
        }
    } else {
        // The sender had no key for us and fell back to clear text (firebase/fcmSend.kt).
        raw
    }

    val type = content.text("type") ?: "(tipo assente)"
    val fromUserId = content.text("fromUserId") ?: ""
    val from = contacts[fromUserId] ?: fromUserId.ifEmpty { "(mittente assente)" }
    val clearNote = if (raw.text("enc") == "1") "" else "  [IN CHIARO]"

    received.incrementAndGet()
    ok("$now  ${type.padEnd(14)} da $from$timing$clearNote")
}

/** A string or number field as text; null when absent or JSON null. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun ask(question: String): String {
    println(question)
    return readlnOrNull() ?: ""
}

private fun ok(message: String) = println("  [OK]   $message")
private fun warn(message: String) = println("  [??]   $message")
private fun fail(message: String) = println("  [NO]   $message")
