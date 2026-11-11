package com.bolimot.mindtheclub.desktop.spike

import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.engine.DataLink
import com.bolimot.mindtheclub.desktop.engine.DesktopEvents
import com.bolimot.mindtheclub.desktop.engine.DesktopStrings
import com.bolimot.mindtheclub.desktop.engine.Engine
import com.bolimot.mindtheclub.desktop.engine.Outgoing
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

/*
 * Phase 1, step 3: does the PC SEND with the App's own code?
 *
 * A small chat in a text window: pick a contact, type, press Enter. Each line is saved in the
 * database and sent the way the phone sends it: batch table, DispatchWorker, dataCall, WebRTC
 * connection, chunks, completed. The status of the line changes as the phone's bubble would:
 * sending, then sent, then delivered when the receiver's allReceived arrives, then Seen.
 *
 * Messages from the contact arrive and are saved while you type, as in check 5.
 *
 * Needs an identity on the PC (prova4.bat). Only 1:1 text.
 *
 * To see the recovery layer work: turn the receiving phone's network off, send a line, wait
 * for the three attempts and the pending (about four minutes), then turn the network back on.
 * The phone gets the pending, asks with sendMe, and the line is delivered.
 */

private val time6: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTime6: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

fun main() {
    println()
    println("MindTheClub per Windows - Prova 6: invio dal PC")
    println("-------------------------------------------------")

    val myUserId = MySelf.userId() ?: ""
    if (myUserId.isEmpty()) {
        mark6("NO", "Su questo PC non c'e' un'identita': lancia prima prova4.bat e carica un backup.")
        return
    }

    val names = ConcurrentHashMap<String, String>()
    val contacts = try {
        runBlocking {
            DatabaseProvider.provideDatabase().peerDao().getAllPeers()
                .filter { !it.userId.startsWith("group") && it.userId != myUserId }
                .sortedBy { it.name.lowercase() }
        }
    } catch (e: Throwable) {
        mark6("NO", "Il database non si apre: ${e.javaClass.simpleName}: ${e.message}")
        return
    }
    for (peer in contacts) names[peer.userId] = peer.name
    if (contacts.isEmpty()) {
        mark6("NO", "Nessun contatto nel database.")
        return
    }

    // What the engine reports, shown as it happens.
    val sentTexts = ConcurrentHashMap<String, String>()
    SignalHandler.onSignal = { type, from ->
        println("  ${now6()}  segnale ${type.padEnd(12)} da ${names[from] ?: "sconosciuto"}")
    }
    DataLink.onProgress = { peer, line ->
        println("  ${now6()}    [${names[peer] ?: peer.take(8)}] $line")
    }
    SignalSender.onSent = { to, type, result ->
        println("  ${now6()}    inviato $type a ${names[to] ?: to.take(8)} -> $result")
    }
    DesktopEvents.onMessageSaved = { message ->
        if (message.fromUserId != myUserId) {
            val body = if (message.type == "text") message.text else "[${message.type}]"
            println()
            println("  <<<<<  da ${names[message.fromUserId] ?: message.fromUserId.take(8)}:  $body")
            println()
        }
    }
    DesktopEvents.onStatusChanged = { messageId, status ->
        // The first change can arrive before the line below has filed the text under its id.
        val text = sentTexts[messageId] ?: "messaggio ${messageId.take(8)}"
        println("  ${now6()}  STATO  \"${text.take(40)}\"  ->  $status")
    }
    Engine.onPushLost = { reason ->
        mark6("??", "Canale push interrotto: $reason. Chiudi e rilancia la prova.")
    }

    try {
        Engine.start()
    } catch (e: Engine.StartFailed) {
        mark6("NO", e.message ?: "Avvio fallito.")
        return
    } catch (e: Throwable) {
        mark6("NO", "Avvio fallito: ${e.javaClass.simpleName}: ${e.message}")
        return
    }
    mark6("OK", "Motore avviato. Stati dei messaggi in lingua \"${DesktopStrings.language}\" " +
            "(${DesktopStrings.sent()} / ${DesktopStrings.delivered()}), " +
            if (DesktopStrings.detectedFromData) "letta dai messaggi del telefono" else "NON trovata nei dati: e' la lingua di questo PC")

    println()
    println("Contatti:")
    for ((index, peer) in contacts.withIndex()) println("  ${index + 1}. ${peer.name}")
    println()
    println("Scrivi il NUMERO del contatto e premi Invio:")
    val chosen = readlnOrNull()?.trim()?.toIntOrNull()?.let { contacts.getOrNull(it - 1) }
    if (chosen == null) {
        mark6("NO", "Numero non valido.")
        finish6()
        return
    }

    println()
    println("Chat con ${chosen.name}. Scrivi un messaggio e premi Invio per spedirlo.")
    println("Una riga VUOTA chiude il programma.")
    println()

    while (true) {
        val line = readlnOrNull() ?: break
        if (line.isBlank()) break
        val message = try {
            runBlocking { Outgoing.sendText(chosen.userId, line) }
        } catch (e: Throwable) {
            mark6("NO", "Invio fallito: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (message == null) {
            mark6("NO", "Messaggio non salvato, non inviato.")
        } else {
            sentTexts[message.messageId] = line
            println("  ${now6()}  STATO  \"${line.take(40)}\"  ->  ${message.status}")
        }
    }

    println()
    println("Dal database, gli ultimi messaggi con ${chosen.name}:")
    try {
        runBlocking {
            val messages = DatabaseProvider.provideDatabase().messageDao().getMessagesByRemotePeer(chosen.userId)
            for (message in messages.sortedByDescending { it.date }.take(10).reversed()) {
                val mine = message.fromUserId == myUserId
                val direction = if (mine) "io ->" else "   <-"
                val body = if (message.type == "text") message.text.replace('\n', ' ').take(50) else "[${message.type}]"
                println("    ${dateTime6.format(Instant.ofEpochMilli(message.date))}  $direction  ${message.status.padEnd(12)}  $body")
            }
        }
    } catch (e: Throwable) {
        mark6("NO", "Lettura del database fallita: ${e.javaClass.simpleName}: ${e.message}")
    }
    println()
    println("Diario del motore: ${debugLogPath()}")
    finish6()
}

private fun finish6() {
    Engine.stop()
    kotlin.system.exitProcess(0)
}

private fun now6(): String = LocalTime.now().format(time6)

private fun mark6(mark: String, message: String) = println("  [$mark]   $message")
