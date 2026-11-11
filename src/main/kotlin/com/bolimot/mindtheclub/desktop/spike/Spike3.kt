package com.bolimot.mindtheclub.desktop.spike

import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.desktop.backend.Relay
import com.bolimot.mindtheclub.desktop.backup.BackupEnvelope
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import com.bolimot.mindtheclub.desktop.push.NtfySubscriber
import com.bolimot.mindtheclub.desktop.push.WebPushKeys
import com.bolimot.mindtheclub.desktop.rtc.DataSession
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/*
 * Phase 0, check 3: can a Windows program RECEIVE a MindTheClub message from a phone?
 *
 * This is the one that decides the approach. It is check 2 plus the answer:
 *
 *   dataCall   -> join the signalling room, answer the phone's WebRTC offer, read the chunks
 *                 the phone writes into the data channel, print the text, and confirm with
 *                 allReceived so the bubble on the phone turns to Delivered.
 *   pending    -> ask for the message with sendMe, as a phone does. This is how a message that
 *                 was announced while nobody answered (check 2's "Buongiorno") gets here.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *   - It stores nothing. A text it confirms is shown on the screen and is then gone: the phone
 *     that sent it marks it Delivered and drops its copy of the chunks, so that message will
 *     NOT be on the identity's phone when the identity goes back. Test identities only.
 *   - It confirms ONLY single chunk text messages. A confirmation makes the sender delete its
 *     batch tables (transport.md section 2); confirming a photo this program does not keep
 *     would lose it for good. Media are counted and reported, never confirmed.
 *   - Groups are ignored.
 *
 * Signals are acted on only when they come from a contact in the backup, as the phone does
 * (isRecognisedPeer in MyFirebaseMessagingService).
 */

private val clock3: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** A contact from the backup. */
private class Contact(val name: String, val publicKey: String?)

/** Everything check 3 needs while it runs. One instance, shared by the callbacks. */
private class Receiver(
    val userId: String,
    val identity: IdentityKeys,
    val contacts: Map<String, Contact>,
    val factory: PeerConnectionFactory
) {
    val sessions = ConcurrentHashMap<String, DataSession>()
    val delivered = ConcurrentHashMap.newKeySet<String>()
    val lastSendMe = ConcurrentHashMap<String, Long>()
    val chunksSeen = ConcurrentHashMap<String, MutableSet<Int>>()
    val textsReceived = AtomicInteger(0)

    // Network calls made in reaction to a signal never run on the push thread.
    val background = Executors.newCachedThreadPool { task ->
        Thread(task, "mtc-background").apply { isDaemon = true }
    }
}

fun main(args: Array<String>) {
    println()
    println("MindTheClub per Windows - Prova 3: ricezione di un messaggio")
    println("-------------------------------------------------------------")

    if (!WebPushKeys.selfTest()) {
        stop("La decifratura Web Push non funziona su questo PC.")
        return
    }

    // 1. The identity, from the backup.
    val path = (args.firstOrNull() ?: prompt("Trascina qui il file .mtcbackup (o scrivi il percorso) e premi Invio:"))
        .trim().trim('"')
    val file = File(path)
    if (!file.isFile) {
        stop("File non trovato: $path")
        return
    }
    val password = prompt("Scrivi la password del backup e premi Invio (resta visibile sullo schermo):")
    val clear = BackupEnvelope.open(file.readBytes(), password.toCharArray())
    if (clear == null) {
        stop("Il backup non si apre: password sbagliata, oppure il file non e' un backup di MindTheClub.")
        return
    }
    val backup = try {
        Json.parseToJsonElement(clear.toString(Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        stop("Il contenuto non e' il JSON atteso: ${e.message}")
        return
    }
    val userId = backup.str("userId") ?: ""
    val name = backup.str("name") ?: "(senza nome)"
    val keysetJson = backup.str("identityKeyset")
    if (userId.isEmpty() || keysetJson == null) {
        stop("Il backup non contiene un'identita' completa.")
        return
    }
    val identity = try {
        IdentityKeys.fromBackupKeyset(keysetJson)
    } catch (e: Exception) {
        stop("Tink su Windows non legge la chiave del telefono: ${e.message}")
        return
    }
    good("Identita' letta dal backup: $name")

    val contacts = HashMap<String, Contact>()
    for (peer in (backup["peers"] as? JsonArray).orEmpty()) {
        val p = peer as? JsonObject ?: continue
        val id = p.str("userId") ?: continue
        contacts[id] = Contact(p.str("name") ?: id, p.str("publicKey")?.takeIf { it.isNotEmpty() })
    }

    val published = try {
        Backend.publishedPublicKey(userId)
    } catch (e: Exception) {
        stop("Server non raggiungibile (${e.message}). Riprova con la rete attiva.")
        return
    }
    if (published != identity.publicKey) {
        stop("La chiave del backup non e' quella pubblicata. Rifai la prova 1 con un backup appena fatto.")
        return
    }

    // 2. WebRTC, before anything is registered: if the native library does not load on this
    //    PC there is no point in taking the identity.
    val factory = try {
        PeerConnectionFactory(HeadlessAudioDeviceModule())
    } catch (e: Throwable) {
        stop("La libreria WebRTC non parte su questo PC: ${e.javaClass.simpleName}: ${e.message}")
        return
    }
    good("Libreria WebRTC caricata")

    println()
    println("ATTENZIONE. Questa prova tiene l'identita' \"$name\" su questo PC, come la prova 2,")
    println("e in piu' CONFERMA i messaggi di testo che riceve: sul telefono di chi scrive")
    println("risulteranno Consegnati, ma qui vengono solo mostrati, non salvati.")
    println("Usa solo un'identita' di prova.")
    if (prompt("Scrivi SI e premi Invio per continuare, qualunque altra cosa per uscire:").trim().uppercase() != "SI") {
        println("Uscita senza toccare nulla.")
        return
    }

    // 3. Push channel and registration, exactly as in check 2.
    val receiver = Receiver(userId, identity, contacts, factory)
    val pushKeys = WebPushKeys.generate()
    val subscriber = NtfySubscriber(
        onMessage = { body -> onPush3(body, pushKeys, receiver) },
        onClosed = { reason -> note("Canale push interrotto: $reason. Chiudi e rilancia la prova.") }
    )
    if (!subscriber.connect()) {
        stop("Non riesco ad aprire il canale push (ntfy.sh non raggiungibile).")
        return
    }
    try {
        val sealedChallenge = Backend.call("getPushChallenge", buildJsonObject { put("userId", userId) })
            .str("challenge")
        if (sealedChallenge == null) {
            stop("Il server non conosce questa identita'.")
            return
        }
        Backend.call("updateMyPushEndpoint", buildJsonObject {
            put("userId", userId)
            put("proof", identity.decrypt(sealedChallenge))
            put("endpoint", subscriber.endpoint)
            put("p256dh", pushKeys.p256dh)
            put("auth", pushKeys.auth)
            put("publicKey", identity.publicKey)
            put("installationId", UUID.randomUUID().toString().replace("-", ""))
            backup.str("trialStartedAt")?.toLongOrNull()?.let { put("trialStartedAt", it) }
        })
    } catch (e: Backend.CallableException) {
        stop("Registrazione rifiutata dal server: ${e.status} (${e.message})")
        return
    } catch (e: Exception) {
        stop("Registrazione non riuscita: ${e.message}")
        return
    }
    good("Registrato: i segnali per \"$name\" arrivano a questo PC")

    println()
    println("IN ASCOLTO. Da un telefono che ha \"$name\" fra i contatti manda un messaggio di TESTO.")
    println("Deve comparire qui sotto, e sul telefono deve diventare \"Consegnato\".")
    println("I messaggi rimasti in attesa dalla prova 2 arrivano al prossimo \"pending\" del telefono.")
    println("Premi Invio per chiudere.")
    println()
    readlnOrNull()

    println()
    if (receiver.textsReceived.get() > 0) {
        println("ESITO: SUPERATA. Messaggi di testo ricevuti e confermati: ${receiver.textsReceived.get()}.")
    } else {
        println("ESITO: NESSUN MESSAGGIO RICEVUTO. Incolla tutto quello che e' comparso qui sopra.")
    }
    kotlin.system.exitProcess(0)
}

/** One push message: open it, show it, and react to the two types this check handles. */
private fun onPush3(body: ByteArray, pushKeys: WebPushKeys, receiver: Receiver) {
    val raw = try {
        Json.parseToJsonElement(String(pushKeys.open(body), Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        stop("${now()}  messaggio push non decifrabile: ${e.message}")
        return
    }

    val sentAt = raw.str("upSentAt")?.toLongOrNull()
    val ttlSeconds = raw.str("upTtl")?.toLongOrNull()
    val expired = sentAt != null && ttlSeconds != null &&
            System.currentTimeMillis() - sentAt > ttlSeconds * 1000 + CLOCK_GRACE_MS

    val content = if (raw.str("enc") == "1") {
        try {
            Json.parseToJsonElement(receiver.identity.decrypt(raw.str("payload") ?: "")).jsonObject
        } catch (e: Exception) {
            stop("${now()}  segnale non apribile con la chiave di identita': ${e.message}")
            return
        }
    } else {
        raw
    }

    val type = content.str("type") ?: return
    val fromUserId = content.str("fromUserId") ?: return
    val value = content.str("content") ?: ""
    val contact = receiver.contacts[fromUserId]

    good("${now()}  segnale ${type.padEnd(12)} da ${contact?.name ?: "sconosciuto"}${if (expired) "  (SCADUTO, ignorato)" else ""}")

    // A phone drops what is past its time (mtcx, isExpired) and ignores unknown senders.
    if (expired || contact == null) return

    when (type) {
        "dataCall" -> receiver.background.execute { answerDataCall(value, fromUserId, contact, receiver) }
        "pending" -> receiver.background.execute { answerPending(value, fromUserId, contact, receiver) }
    }
}

/** dataCall: content is the channelId of the signalling room the sender is waiting in. */
private fun answerDataCall(channelId: String, fromUserId: String, contact: Contact, receiver: Receiver) {
    if (channelId.isEmpty()) return
    val senderKey = keyOf(fromUserId, contact) ?: run {
        stop("${now()}  nessuna chiave pubblica per ${contact.name}: non posso rispondere")
        return
    }
    val session = DataSession(
        channelId = channelId,
        myUserId = receiver.userId,
        remoteUserId = fromUserId,
        remotePublicKey = senderKey,
        identity = receiver.identity,
        factory = receiver.factory,
        initiator = false,
        log = { line -> println("         ${now()}  [${contact.name}] $line") },
        onChunk = { chunk -> onChunk(chunk, fromUserId, contact, senderKey, receiver) },
        onEnd = { reason ->
            receiver.sessions.remove(channelId)
            println("         ${now()}  [${contact.name}] sessione chiusa: $reason")
        }
    )
    if (receiver.sessions.putIfAbsent(channelId, session) != null) return
    session.start()
}

/**
 * pending: "I hold content for you, come and get it". Content is the messageId, or
 * messageId#chatGroupId for a group. The answer is exactly one sendMe, or allReceived when the
 * message is already here (C:\mtc\docs\arch\recovery.md section 2).
 */
private fun answerPending(value: String, fromUserId: String, contact: Contact, receiver: Receiver) {
    val parts = value.split("#", limit = 2)
    val messageId = parts[0]
    if (messageId.isEmpty()) return
    if (parts.size > 1) {
        println("         ${now()}  [${contact.name}] pending di gruppo: ignorato in questa prova")
        return
    }
    val senderKey = keyOf(fromUserId, contact) ?: return

    if (receiver.delivered.contains(messageId)) {
        signal(receiver, fromUserId, senderKey, contact, "allReceived", messageId, wake = false)
        return
    }
    // One sendMe per announcement burst: a phone re-announces, and each sendMe makes it dispatch.
    val last = receiver.lastSendMe[messageId] ?: 0L
    if (System.currentTimeMillis() - last < SEND_ME_INTERVAL_MS) return
    receiver.lastSendMe[messageId] = System.currentTimeMillis()
    signal(receiver, fromUserId, senderKey, contact, "sendMe", messageId, wake = true)
}

/**
 * One chunk from the data channel: the JSON of an Outbox row, as written by dispatchBatch on
 * the phone (sending/dispatch.kt) and read by receiveData (receiving/receiveData.kt).
 */
private fun onChunk(json: String, fromUserId: String, contact: Contact, senderKey: String, receiver: Receiver) {
    val chunk = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (e: Exception) {
        stop("${now()}  pezzo illeggibile da ${contact.name}: ${e.message}")
        return
    }
    val messageId = chunk.str("messageId") ?: return
    val type = chunk.str("type") ?: ""
    val sequenceNo = chunk.str("sequenceNo")?.toIntOrNull() ?: 0
    val totalNo = chunk.str("totalNo")?.toIntOrNull() ?: 0
    val isGroup = !chunk.str("chatGroupId").isNullOrEmpty()

    if (type == "text" && totalNo == 1 && !isGroup) {
        val first = receiver.delivered.add(messageId)
        if (first) {
            receiver.textsReceived.incrementAndGet()
            println()
            println("  >>>>>  MESSAGGIO da ${contact.name}:  ${chunk.str("text") ?: ""}")
            println()
        }
        // Confirmed again on a duplicate: the sender is re-sending because it never got the
        // first confirmation.
        receiver.background.execute {
            signal(receiver, fromUserId, senderKey, contact, "allReceived", messageId, wake = false)
        }
        return
    }

    val seen = receiver.chunksSeen.computeIfAbsent(messageId) { ConcurrentHashMap.newKeySet<Int>() }
    seen.add(sequenceNo)
    if (seen.size == totalNo) {
        println("         ${now()}  [${contact.name}] contenuto \"$type\" completo ($totalNo pezzi): NON confermato, questa prova non salva i media")
    } else if (seen.size == 1 || seen.size % 20 == 0) {
        println("         ${now()}  [${contact.name}] contenuto \"$type\": ${seen.size} pezzi su $totalNo")
    }
}

private fun signal(
    receiver: Receiver,
    toUserId: String,
    recipientKey: String,
    contact: Contact,
    type: String,
    content: String,
    wake: Boolean
) {
    val result = try {
        Relay.sendSignal(receiver.userId, toUserId, recipientKey, type, content, wake)
    } catch (e: Exception) {
        "errore: ${e.message}"
    }
    println("         ${now()}  [${contact.name}] inviato $type -> $result")
}

/** The contact's key from the backup, or the one it published (CloudflareSignal recoverPeerPublicKey). */
private fun keyOf(userId: String, contact: Contact): String? =
    contact.publicKey ?: try {
        Backend.publishedPublicKey(userId)
    } catch (e: Exception) {
        null
    }

private const val CLOCK_GRACE_MS = 5_000L
private const val SEND_ME_INTERVAL_MS = 60_000L

private fun now(): String = LocalTime.now().format(clock3)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun prompt(question: String): String {
    println(question)
    return readlnOrNull() ?: ""
}

private fun good(message: String) = println("  [OK]   $message")
private fun note(message: String) = println("  [??]   $message")
private fun stop(message: String) = println("  [NO]   $message")
