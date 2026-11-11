package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.storage.Prefs
import com.bolimot.mindtheclub.functions.debugLine

/**
 * The status texts of a sent message, IN THE PHONE'S LANGUAGE.
 *
 * The App does not store "sent" or "delivered" as a code. It stores the translated word, the
 * one in strings.xml for the phone's language ("Inviato", "Sent", "Gesendet" ...), and its
 * screens compare the stored text with the current translation to decide what to draw
 * (adapters/MessagesAdapter.kt, chat/ChatScreen.kt). "Seen" and "Receiving" are the exceptions:
 * those are fixed codes (Notify.SEEN, Status.RECEIVING).
 *
 * So a PC that alternates with a phone must write the phone's words, or the messages sent from
 * the PC would look wrong once the identity is back on the phone. The phone's language is not
 * written anywhere in a backup; it is read off the data: the statuses of the messages already
 * in the database are matched against the eight languages the App ships.
 *
 * Kept in step with C:\mtc\app\src\main\res\values*\strings.xml, names sending, sent and
 * delivered. If the App changes one of those words or adds a language, change it here too.
 */
object DesktopStrings {

    private class Words(val sending: String, val sent: String, val delivered: String)

    private val languages = linkedMapOf(
        "en" to Words("Sending…", "Sent", "Delivered"),
        "de" to Words("Wird gesendet…", "Gesendet", "Zugestellt"),
        "es" to Words("Enviando…", "Enviado", "Entregado"),
        "fr" to Words("Envoi…", "Envoyé", "Distribué"),
        "in" to Words("Mengirim…", "Terkirim", "Tersampaikan"),
        "it" to Words("Invio…", "Inviato", "Consegnato"),
        "pt" to Words("Enviando…", "Enviada", "Entregue"),
        "pl" to Words("Wysyłanie…", "Wysłano", "Dostarczono"),
    )

    @Volatile private var words: Words = languages.getValue("en")
    @Volatile var language: String = "en"
        private set

    /** False when no sent message in the database told the language and it had to be guessed. */
    @Volatile var detectedFromData: Boolean = false
        private set

    /** R.string.sending */
    fun sending(): String = words.sending

    /** R.string.sent */
    fun sent(): String = words.sent

    /** R.string.delivered */
    fun delivered(): String = words.delivered

    /**
     * Called when the engine starts: uses the language fixed at the last load. Only an
     * installation that predates this (no language stored) reads it off the data here.
     */
    suspend fun load() {
        val prefs = Prefs.of("desktop_carried")
        val stored = prefs.getString("statusLanguage")
        if (stored == null || !languages.containsKey(stored)) {
            detectFromPhoneData()
            return
        }
        language = stored
        detectedFromData = prefs.getBoolean("statusLanguageGuessed") != true
        words = languages.getValue(stored)
    }

    /**
     * Finds the phone's language from the statuses in the database and FIXES it until the next
     * load. Must run right after the phone's data arrive and before the PC writes any status
     * of its own: on 11 Nov 2026 it ran at every start, guessed English for a database with no
     * sent messages, wrote "Delivered", and at the next start found its own guess in the data
     * and took it as the phone's language.
     *
     * When the data say nothing the choice is this PC's own language, and it is recorded as a
     * guess.
     */
    suspend fun detectFromPhoneData() {
        val prefs = Prefs.of("desktop_carried")
        var found: String? = null
        try {
            val counts = HashMap<String, Int>()
            for (message in DatabaseProvider.provideDatabase().messageDao().getAllMessages()) {
                for ((code, candidate) in languages) {
                    if (message.status == candidate.sent || message.status == candidate.delivered) {
                        counts[code] = (counts[code] ?: 0) + 1
                    }
                }
            }
            found = counts.maxByOrNull { it.value }?.key
        } catch (e: Exception) {
            debugLine("DesktopStrings", "Language detection failed: ${e.message}")
        }

        // When the data says nothing the best guess is this PC's own language: a person's phone
        // and PC are usually set to the same one. Java names Indonesian "id", the App "in".
        val system = java.util.Locale.getDefault().language.let { if (it == "id") "in" else it }
            .takeIf { languages.containsKey(it) }

        val chosen = found ?: system ?: "en"
        prefs.putString("statusLanguage", chosen)
        prefs.putBoolean("statusLanguageGuessed", found == null)
        language = chosen
        detectedFromData = found != null
        words = languages[chosen] ?: languages.getValue("en")
        debugLine("DesktopStrings", "Status texts in '$chosen'" + if (found == null) " (not found in the data, guessed)" else "")
    }
}
