package com.bolimot.mindtheclub.desktop.spike

import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.backup.BackupExporter
import com.bolimot.mindtheclub.desktop.engine.Engine
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.desktop.storage.Prefs
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Phase 1, step 4: THE WAY BACK. The PC writes a backup in the phone's own format and gives
 * the identity up; the phone restores it with the restore it already has.
 *
 * After this the PC is deactivated: it keeps its data but will not start the engine again
 * until a backup is loaded (prova4.bat, NUOVO). One identity, one installation.
 *
 * Entirely local: it writes one file and one flag. Nothing is sent to the server; the phone's
 * restore is what moves the identity there.
 */

fun main() {
    println()
    println("MindTheClub per Windows - Prova 7: ritorno al telefono")
    println("--------------------------------------------------------")

    if (!KeyVault.exists()) {
        mark7("NO", "Su questo PC non c'e' un'identita'.")
        return
    }
    val profile = Prefs.of("default")
    val name = profile.getString("myName") ?: "(senza nome)"
    val userId = profile.getString("myUserId") ?: ""

    if (Engine.isDeactivated()) {
        println("Questo PC ha gia' restituito l'identita' \"$name\".")
        println("Puoi comunque scrivere di nuovo il file di backup, per esempio se l'hai perso.")
        println()
    }

    println("Verra' scritto un backup di \"$name\" da ripristinare sul telefono.")
    println("Dopo, questo PC smette di usare l'identita' finche' non ricarichi un backup.")
    println()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
    val suggested = File(File(System.getProperty("user.home"), "Downloads"), "mindtheclub_pc_$stamp.mtcbackup")
    println("Dove salvo il file? Premi Invio per usare questo percorso, oppure scrivine un altro:")
    println("  ${suggested.absolutePath}")
    val typed = (readlnOrNull() ?: "").trim().trim('"')
    val destination = if (typed.isEmpty()) suggested else File(typed)
    if (destination.isDirectory) {
        mark7("NO", "Quello e' una cartella, serve il nome di un file.")
        return
    }

    println("Scegli la password del backup (almeno 8 caratteri, resta visibile sullo schermo):")
    val password = readlnOrNull() ?: ""
    if (password.length < 8) {
        mark7("NO", "Password troppo corta: nulla e' stato scritto.")
        return
    }
    println("Riscrivila per conferma:")
    if ((readlnOrNull() ?: "") != password) {
        mark7("NO", "Le due password sono diverse: nulla e' stato scritto.")
        return
    }

    val result = try {
        runBlocking { BackupExporter.write(destination, password.toCharArray()) }
    } catch (e: BackupExporter.Refused) {
        mark7("NO", e.message ?: "Esportazione rifiutata.")
        return
    } catch (e: Throwable) {
        mark7("NO", "Esportazione fallita: ${e.javaClass.simpleName}: ${e.message}")
        return
    } finally {
        DatabaseProvider.close()
    }

    mark7("OK", "Backup scritto e riletto: ${destination.absolutePath}")
    println()
    println("  Impronta        ${result.fingerprint}")
    println("  Contatti        ${result.peers}")
    println("  Messaggi        ${result.messages}")
    println("  Reazioni        ${result.reactions}")
    println("  Dimensione      ${result.sizeBytes / 1024} KB")
    println()

    // Only now, with a file that has been read back: the PC lets go.
    Engine.handBack()
    mark7("OK", "Questo PC ha lasciato l'identita'")

    println()
    println("ORA SUL TELEFONO:")
    println("  1. Porta il file sul telefono.")
    println("  2. Apri MindTheClub: se mostra \"identita' spostata\", usa il pulsante di ripristino;")
    println("     altrimenti Opzioni, Backup e ripristino.")
    println("  3. Scegli il file e scrivi la password.")
    println()
    println("NOTA per un telefono con la App di Google Play, finche' updateMyFcmToken non accetta la")
    println("prova con chiave: prima del ripristino va aggiunto a mano, nella console Firebase, il campo")
    println("fcmToken (un testo qualunque) al documento users/$userId. Con la App mtcx non serve.")
    println()
    println("Dopo il ripristino, per controllare che il PC si sia fatto da parte lancia prova6.bat:")
    println("deve rifiutarsi di partire dicendo che l'identita' non e' piu' qui.")
    kotlin.system.exitProcess(0)
}

private fun mark7(mark: String, message: String) = println("  [$mark]   $message")
