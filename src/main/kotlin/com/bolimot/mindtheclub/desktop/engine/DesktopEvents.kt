package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.database.message.Message

/**
 * Where the engine tells the user interface that something happened.
 *
 * On the phone the engine reaches the screens directly: a ViewModel to add the bubble, a
 * notification, a sound, a check on which Activity is in front. Those are the points where a
 * copied file cannot stay a copy, and on the PC they all come here instead. The engine never
 * knows what, if anything, is listening: today a text window, later the real interface.
 *
 * Listeners are called on the engine's threads and must return quickly.
 */
object DesktopEvents {

    /** A message was saved in the database, or a RECEIVING placeholder was created for it. */
    @Volatile var onMessageSaved: ((Message) -> Unit)? = null

    /**
     * A complete incoming message the user is not looking at: what is a notification on the
     * phone. Not called for a message that arrives in the chat currently on screen.
     */
    @Volatile var onMessageToNotify: ((Message) -> Unit)? = null

    /** The status of a message changed: sent, delivered, Seen. Texts as stored, see DesktopStrings. */
    @Volatile var onStatusChanged: ((messageId: String, status: String) -> Unit)? = null

    fun statusChanged(messageId: String, status: String) {
        try {
            onStatusChanged?.invoke(messageId, status)
        } catch (e: Exception) {
            // A listener's failure is never the engine's.
        }
    }

    /** Whether the chat with this peer is the one on screen (ChatScreen in foreground). */
    @Volatile var chatOnScreen: (String) -> Boolean = { false }

    fun incomingMessage(message: Message) {
        try {
            onMessageSaved?.invoke(message)
        } catch (e: Exception) {
            // A listener's failure is never the engine's.
        }
    }

    fun notifyMessage(message: Message) {
        try {
            onMessageToNotify?.invoke(message)
        } catch (e: Exception) {
            // As above.
        }
    }
}
