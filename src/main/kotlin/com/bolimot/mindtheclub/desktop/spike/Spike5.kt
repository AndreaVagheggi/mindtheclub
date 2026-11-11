package com.bolimot.mindtheclub.desktop.spike

import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.engine.DataLink
import com.bolimot.mindtheclub.desktop.engine.DesktopEvents
import com.bolimot.mindtheclub.desktop.engine.Engine
import com.bolimot.mindtheclub.desktop.engine.SignalHandler
import com.bolimot.mindtheclub.desktop.engine.SignalSender
import com.bolimot.mindtheclub.functions.debugLogPath
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * Phase 1, step 2: does the PC RECEIVE with the App's own code, and KEEP what it receives?
 *
 * Unlike check 3, nothing here decides what to do with a chunk or a signal. The engine does:
 * the App's receiveData, MessageProcessor and receiveText, copied or adapted, write the Inbox,
 * save the message in the database and send the confirmation. This program only starts the
 * engine and shows what it reports.
 *
 * Needs an identity on the PC (prova4.bat). Run it, send a text from a contact, press Enter,
 * then run it again: the text must be among the last messages, read from the database.
 *
 * Only 1:1 text is processed. Media stay in the Inbox unconfirmed, group content is refused.
 */

private val time5: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTime5: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

fun main() {
    println()
    println("MindTheClub per Windows - Prova 5: ricezione con il motore della App")
    println("----------------------------------------------------------------------")

    val names = ConcurrentHashMap<String, String>()
    val myUserId = MySelf.userId() ?: ""
    if (myUserId.isEmpty()) {
        mark5("NO", "Su questo PC non c'e' un'identita': lancia prima prova4.bat e carica un backup.")
        return
    }
    try {
        runBlocking {
            for (peer in DatabaseProvider.provideDatabase().peerDao().getAllPeers()) names[peer.userId] = peer.name
        }
    } catch (e: Throwable) {
        mark5("NO", "Il database non si apre: ${e.javaClass.simpleName}: ${e.message}")
        return
    }

    println("Prima di cominciare, gli ultimi messaggi nel database:")
    showLast(myUserId, names)
    println()

    val saved = AtomicInteger(0)
    SignalHandler.onSignal = { type, from ->
        println("  ${now5()}  segnale ${type.padEnd(12)} da ${names[from] ?: "sconosciuto"}")
    }
    DataLink.onProgress = { from, line ->
        println("  ${now5()}    [${names[from] ?: from.take(8)}] $line")
    }
    SignalSender.onSent = { to, type, result ->
        println("  ${now5()}    inviato $type a ${names[to] ?: to.take(8)} -> $result")
    }
    DesktopEvents.onMessageSaved = { message ->
        if (message.fromUserId != myUserId) {
            saved.incrementAndGet()
            val body = if (message.type == "text") message.text else "[${message.type}, ${message.status}]"
            println()
            println("  >>>>>  SALVATO nel database, da ${names[message.fromUserId] ?: message.fromUserId.take(8)}:  $body")
            println()
        }
    }
    Engine.onPushLost = { reason ->
        mark5("??", "Canale push interrotto: $reason. Chiudi e rilancia la prova.")
    }

    try {
        Engine.start()
    } catch (e: Engine.StartFailed) {
        mark5("NO", e.message ?: "Avvio fallito.")
        return
    } catch (e: Throwable) {
        mark5("NO", "Avvio fallito: ${e.javaClass.simpleName}: ${e.message}")
        return
    }
    mark5("OK", "Motore avviato: i segnali per questa identita' arrivano a questo PC")

    println()
    println("IN ASCOLTO. Da un telefono fra i contatti manda uno o piu' messaggi di TESTO.")
    println("Ognuno deve comparire come SALVATO e diventare \"Consegnato\" sul telefono.")
    println("Poi premi Invio: il programma rilegge il database e mostra gli ultimi messaggi.")
    println()
    readlnOrNull()

    println()
    println("Dal database, dopo la ricezione:")
    showLast(myUserId, names)
    println()
    if (saved.get() > 0) {
        println("ESITO: ${saved.get()} messaggi ricevuti e salvati in questa sessione.")
        println("Per la prova completa rilancia prova5.bat: devono comparire in alto, fra gli ultimi messaggi, PRIMA di ricevere altro.")
    } else {
        println("ESITO: nessun messaggio ricevuto in questa sessione.")
    }
    println("Diario del motore: ${debugLogPath()}")

    Engine.stop()
    kotlin.system.exitProcess(0)
}

private fun showLast(myUserId: String, names: Map<String, String>) {
    try {
        runBlocking {
            val messages = DatabaseProvider.provideDatabase().messageDao().getAllMessages()
            println("  (${messages.size} messaggi in tutto)")
            for (message in messages.sortedByDescending { it.date }.take(6).reversed()) {
                val mine = message.fromUserId == myUserId
                val other = if (mine) message.toUserId else message.fromUserId
                val who = names[other] ?: other.take(8)
                val direction = if (mine) "io -> $who" else "$who -> io"
                val body = if (message.type == "text") message.text.replace('\n', ' ').take(50) else "[${message.type}]"
                println("    ${dateTime5.format(Instant.ofEpochMilli(message.date))}  ${direction.padEnd(22)}  ${message.status.padEnd(11)}  $body")
            }
        }
    } catch (e: Throwable) {
        mark5("NO", "Lettura del database fallita: ${e.javaClass.simpleName}: ${e.message}")
    }
}

private fun now5(): String = LocalTime.now().format(time5)

private fun mark5(mark: String, message: String) = println("  [$mark]   $message")
