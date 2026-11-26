package com.bolimot.mindtheclub.desktop.ui

import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.desktop.storage.Prefs
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.CheckboxMenuItem
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * The App's MessageReceivedNotification (notifications/MessageReceivedNotification.kt) as a
 * Windows notification.
 *
 * Same rules as on the phone:
 *   - a message is notified once only: the messageId is remembered after the notification is
 *     shown, and a message read in the chat on screen is remembered without one (markShown),
 *     so a later relay echo or sendMe resend never notifies it;
 *   - the title is the contact's name, or "New message"; the text is the message for a text,
 *     otherwise the kind of content, in the App's words and with the App's choices;
 *   - clicking it opens the chat with that contact.
 *
 * Windows shows it through the MindTheClub icon in the notification area (java.awt.TrayIcon):
 * the standard way for a Java program, which needs nothing installed. The sound is the one
 * Windows gives its notifications. The phone's large round picture of the contact and the
 * count of unread messages on the notification have no place in it; the count is in the
 * contact list, as on the phone.
 */
object Notifications {

    // The App's PREFS_SHOWN_MSG. Kept to the most recent ids only: the file is written whole
    // at every change (Prefs), and an id older than this never comes back.
    private const val PREFS_SHOWN_MSG = "shown_message_ids"
    private const val KEY_RECENT = "recent"
    private const val KEEP = 500

    @Volatile private var trayIcon: TrayIcon? = null

    /** The contact of the last notification shown: the one its click opens. */
    @Volatile private var lastUserId: String? = null

    // The PC's own settings: none of them exists on the phone.
    private const val PREFS_SETTINGS = "desktop_settings"
    private const val KEY_QUIT_ON_CLOSE = "quitOnClose"
    private const val KEY_CLOSE_NOTICE_SHOWN = "closeNoticeShown"

    /** Whether the icon is in the notification area: without it, closing the window must quit. */
    val isInstalled: Boolean get() = trayIcon != null

    /**
     * Whether closing the window quits MindTheClub. Off at first: closing leaves it running in
     * the notification area, receiving and notifying, as the phone keeps receiving when the App
     * is closed. While this PC holds the identity the phone is paused, so a PC that quits
     * leaves nobody receiving.
     */
    var quitOnClose: Boolean
        get() = Prefs.of(PREFS_SETTINGS).getBoolean(KEY_QUIT_ON_CLOSE) ?: false
        private set(value) = Prefs.of(PREFS_SETTINGS).putBoolean(KEY_QUIT_ON_CLOSE, value)

    /**
     * Puts the icon in the notification area, with its menu: Open MindTheClub, the setting,
     * Quit. A click on a notification calls [openChat] with its contact; a double click on the
     * icon, or Open, calls [openWindow]. Quit calls [quit].
     */
    @Synchronized
    fun install(openWindow: () -> Unit, openChat: (String) -> Unit, quit: () -> Unit) {
        if (trayIcon != null) return
        try {
            if (!SystemTray.isSupported()) {
                debugLine("MessageNotification", "BLOCKED: no notification area on this PC")
                return
            }
            val logo = Notifications::class.java.getResourceAsStream("/desktop/icons/mtc_logo_icon.png")
                ?.use { ImageIO.read(it) } ?: return

            val menu = PopupMenu()
            menu.add(MenuItem(Strings.OPEN_APP).apply { addActionListener { openWindow() } })
            menu.add(CheckboxMenuItem(Strings.QUIT_ON_CLOSE, quitOnClose).apply {
                addItemListener { quitOnClose = state }
            })
            menu.addSeparator()
            menu.add(MenuItem(Strings.QUIT).apply { addActionListener { quit() } })

            val icon = TrayIcon(logo, Strings.APP_NAME, menu)
            icon.isImageAutoSize = true
            // Fired by a click on a notification, and by a double click on the icon. A
            // notification's contact is used once: a later double click just opens the window.
            icon.addActionListener {
                val userId = lastUserId
                lastUserId = null
                if (userId != null) openChat(userId) else openWindow()
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        } catch (e: Exception) {
            debugLine("MessageNotification", "Tray icon not installed: ${e.message}")
        }
    }

    /**
     * The window was closed and MindTheClub keeps running: said once, the first time, so the
     * user knows where it went and how to quit it.
     */
    fun windowClosedToTray() {
        val settings = Prefs.of(PREFS_SETTINGS)
        if (settings.getBoolean(KEY_CLOSE_NOTICE_SHOWN) == true) return
        val icon = trayIcon ?: return
        lastUserId = null
        icon.displayMessage(Strings.APP_NAME, Strings.STILL_RUNNING, TrayIcon.MessageType.NONE)
        settings.putBoolean(KEY_CLOSE_NOTICE_SHOWN, true)
    }

    @Synchronized
    fun remove() {
        trayIcon?.let {
            try {
                SystemTray.getSystemTray().remove(it)
            } catch (e: Exception) {
                // Leaving anyway.
            }
        }
        trayIcon = null
    }

    // One notification at a time: two triggers for the same message (a relay echo arriving
    // with the original) must not both pass the check before either is marked.
    private val showing = Mutex()

    /** MessageReceivedNotification.show. */
    suspend fun show(message: Message) = showing.withLock {
        debugLine("MessageNotification", "show() for ${message.messageId} type=${message.type}")
        if (isShown(message.messageId)) {
            debugLine("MessageNotification", "Skip: already shown ${message.messageId}")
            return@withLock
        }
        val icon = trayIcon
        if (icon == null) {
            debugLine("MessageNotification", "BLOCKED: no tray icon")
            return@withLock
        }

        val name = try {
            getPeerDao(App.context()).getPeer(message.fromUserId)?.name
        } catch (e: Exception) {
            null
        }

        val text = when (message.type) {
            Type.TEXT, Type.MISSED_CALL -> message.text
            Type.IMAGE -> Strings.IMAGE
            Type.MULTIPLE_IMAGES -> Strings.IMAGES
            Type.VIDEO -> Strings.VIDEO
            Type.GIF -> Strings.GIF
            Type.STICKER -> Strings.STICKER
            Type.WEB -> Strings.LINK
            // As in the App: a file's type is "file,<name>,<extension>,<size>", never Type.FILE
            // alone, so a file gets no text.
            Type.FILE -> Strings.FILE
            Type.AUDIO -> Strings.VOICE_MESSAGE
            Type.PROFILE -> Strings.NEW_CONTACT
            Type.GROUP -> Strings.NEW_GROUP
            else -> ""
        }

        // As in the App: a new group's notification opens the contact list, not a chat.
        lastUserId = if (message.type == Type.GROUP) null else message.fromUserId
        icon.displayMessage(name ?: Strings.NEW_MESSAGE, text, TrayIcon.MessageType.NONE)

        // Marked as shown only after the notification was actually posted, as in the App.
        markShown(message.messageId)
        debugLine("MessageNotification", "Posted for ${message.messageId}")
    }

    /** MessageReceivedNotification.markShown: [messageId] must never be notified. */
    @Synchronized
    fun markShown(messageId: String) {
        val recent = recent()
        if (messageId in recent) return
        recent.add(messageId)
        while (recent.size > KEEP) recent.removeAt(0)
        Prefs.of(PREFS_SHOWN_MSG).putString(KEY_RECENT, recent.joinToString("\n"))
    }

    @Synchronized
    private fun isShown(messageId: String): Boolean = messageId in recent()

    private fun recent(): MutableList<String> =
        Prefs.of(PREFS_SHOWN_MSG).getString(KEY_RECENT)
            ?.split("\n")?.filter { it.isNotEmpty() }?.toMutableList()
            ?: mutableListOf()
}
