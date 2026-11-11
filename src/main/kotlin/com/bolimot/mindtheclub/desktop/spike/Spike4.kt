package com.bolimot.mindtheclub.desktop.spike

import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.backup.BackupImporter
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import com.bolimot.mindtheclub.desktop.storage.DataDir
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.desktop.storage.Prefs
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Phase 1, step 1: does this PC KEEP an identity and its data?
 *
 * First run: asks for a backup and loads it, replacing whatever was here. The database gets
 * the phone's tables and rows, the identity key is sealed by Windows, the profile goes into
 * the preferences.
 *
 * Every later run: asks for nothing. It opens what is on disk and checks it against what was
 * loaded: same key fingerprint, same number of contacts and messages. That is the proof that
 * the backup and its password are no longer needed.
 *
 * Entirely local: no network, nothing registered, the phone is not affected.
 */

private val dateTime: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

fun main() {
    println()
    println("MindTheClub per Windows - Prova 4: database e identita' sul PC")
    println("---------------------------------------------------------------")
    println("Cartella dati: ${DataDir.root().absolutePath}")
    println()

    val hasIdentity = try {
        KeyVault.exists()
    } catch (e: Throwable) {
        line4("NO", "Non riesco a leggere la cartella dati: ${e.message}")
        return
    }

    if (hasIdentity) {
        val shown = showStored()
        println()
        val answer = input4("Premi Invio per uscire, oppure scrivi NUOVO e Invio per sostituire tutto con un altro backup:")
        if (answer.trim().uppercase() != "NUOVO") {
            if (!shown) println("Per ripartire da zero rilancia e scrivi NUOVO.")
            finish()
            return
        }
    } else {
        println("Su questo PC non c'e' ancora nessuna identita'.")
    }

    load()
    finish()
}

/** Opens what is on disk and compares it with what the last load recorded. */
private fun showStored(): Boolean {
    val identity = try {
        val keyset = KeyVault.load()
        if (keyset == null) {
            line4("NO", "La chiave di identita' e' sparita dalla cartella dati.")
            return false
        }
        IdentityKeys.fromBackupKeyset(keyset)
    } catch (e: Throwable) {
        line4("NO", "Windows non apre la chiave di identita' (altro utente o altro PC?): ${e.message}")
        return false
    }
    line4("OK", "Chiave di identita' aperta da Windows, senza password")

    val profile = Prefs.of("default")
    val myUserId = profile.getString("myUserId") ?: ""
    val loaded = Prefs.of("desktop_loaded")

    println()
    println("  Nome            ${profile.getString("myName") ?: "(nessuno)"}")
    println("  userId          $myUserId")
    println("  Impronta        ${identity.fingerprint}")
    println("  Backup del      ${format4(loaded.getLong("backupTimestamp"))}")
    println("  Caricato il     ${format4(loaded.getLong("loadedAt"))}")

    val counts = try {
        runBlocking {
            val db = DatabaseProvider.provideDatabase()
            val peers = db.peerDao().getAllPeers()
            val messages = db.messageDao().getAllMessages()
            println("  Contatti        ${peers.size}")
            println("  Messaggi        ${messages.size}")
            println("  Reazioni        ${db.reactionDao().getAllReactions().size}")
            println("  Bloccati        ${db.blockedUserDao().getAll().size}")
            println("  Club            ${db.clubDao().countRecords()}")
            println()
            println("  Ultimi messaggi:")
            val names = peers.associate { it.userId to it.name }
            for (message in messages.sortedByDescending { it.date }.take(5).reversed()) {
                val mine = message.fromUserId == myUserId
                val other = if (mine) message.toUserId else message.fromUserId
                val who = names[other] ?: other.take(8)
                val direction = if (mine) "io -> $who" else "$who -> io"
                val body = if (message.type == "text") message.text.replace('\n', ' ').take(50) else "[${message.type}]"
                println("    ${format4(message.date)}  ${direction.padEnd(22)}  ${message.status.padEnd(11)}  $body")
            }
            Pair(peers.size, messages.size)
        }
    } catch (e: Throwable) {
        println()
        line4("NO", "Il database non si apre: ${e.javaClass.simpleName}: ${e.message}")
        return false
    } finally {
        DatabaseProvider.close()
    }

    println()
    val sameKey = loaded.getString("fingerprint") == identity.fingerprint
    val samePeers = loaded.getLong("peers") == counts.first.toLong()
    val sameMessages = loaded.getLong("messages") == counts.second.toLong()
    line4(if (sameKey) "OK" else "NO", "Impronta uguale a quella caricata")
    line4(if (samePeers) "OK" else "NO", "Contatti: ${counts.first}, caricati ${loaded.getLong("peers")}")
    line4(if (sameMessages) "OK" else "NO", "Messaggi: ${counts.second}, caricati ${loaded.getLong("messages")}")
    println()
    if (sameKey && samePeers && sameMessages) {
        println("ESITO: SUPERATA. Identita' e dati sono sul PC e si riaprono senza backup ne' password.")
    } else {
        println("ESITO: FALLITA. Quello che c'e' sul disco non corrisponde a quello che era stato caricato.")
    }
    return true
}

private fun load() {
    println()
    val path = input4("Trascina qui il file .mtcbackup (o scrivi il percorso) e premi Invio:").trim().trim('"')
    val file = File(path)
    if (!file.isFile) {
        line4("NO", "File non trovato: $path")
        return
    }
    val password = input4("Scrivi la password del backup e premi Invio (resta visibile sullo schermo):")

    val result = try {
        runBlocking { BackupImporter.loadReplacing(file, password.toCharArray()) }
    } catch (e: BackupImporter.Refused) {
        line4("NO", e.message ?: "Backup rifiutato.")
        return
    } catch (e: Throwable) {
        line4("NO", "Caricamento fallito: ${e.javaClass.simpleName}: ${e.message}")
        return
    } finally {
        DatabaseProvider.close()
    }

    line4("OK", "Backup caricato: ${result.name}")
    println()
    println("  userId          ${result.userId}")
    println("  Impronta        ${result.fingerprint}")
    println("  Contatti        ${result.peers}")
    println("  Messaggi        ${result.messages}")
    println("  Reazioni        ${result.reactions}")
    println("  Bloccati        ${result.blocked}")
    println("  Club            ${result.clubs}")
    println()
    println("SALVATO. Ora chiudi questa finestra e rilancia prova4.bat:")
    println("deve mostrare gli stessi dati senza chiedere ne' il backup ne' la password.")
}

private fun finish() {
    // The database and its driver keep threads alive: end the program for certain.
    kotlin.system.exitProcess(0)
}

private fun format4(millis: Long?): String =
    if (millis == null || millis <= 0L) "(sconosciuto)" else dateTime.format(Instant.ofEpochMilli(millis))

private fun input4(question: String): String {
    println(question)
    return readlnOrNull() ?: ""
}

private fun line4(mark: String, message: String) = println("  [$mark]   $message")
