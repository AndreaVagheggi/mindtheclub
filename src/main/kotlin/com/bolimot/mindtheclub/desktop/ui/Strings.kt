package com.bolimot.mindtheclub.desktop.ui

/**
 * Every text the window shows, in one place, so the PC version can be translated the way the
 * App is (eight languages). English for now, with the App's own wording where the App has the
 * same thing.
 *
 * No em dashes in texts shown to users, as in the App.
 */
object Strings {
    const val APP_NAME = "MindTheClub"

    const val STARTING = "Starting…"
    const val CONNECTING = "Connecting…"
    const val LOADING_BACKUP = "Restoring backup…"

    const val NO_IDENTITY =
        "MindTheClub is not set up on this PC yet. To use it here, make a backup on your phone " +
                "(Options, Backup & Restore), copy the file to this PC and restore it below."
    const val IDENTITY_MOVED =
        "Your MindTheClub identity is now active on another device, so this PC has been paused " +
                "to avoid losing messages between the two. To use MindTheClub here again, make a " +
                "backup on that device and restore it below."

    fun handedBack(path: String) =
        "Your identity has been handed back. Copy this file to your phone and restore it there " +
                "(Backup & Restore, Restore from Backup):\n\n$path\n\nThis PC is paused until you " +
                "restore a new backup here."

    const val RESTORE_TITLE = "Restore from Backup"
    const val CHOOSE_FILE = "Choose backup file…"
    const val NO_FILE = "No file chosen"
    const val PASSWORD = "Backup password"
    const val RESTORE = "Restore"
    const val RESTORE_WARNING =
        "Restoring moves your identity to this PC: your phone will pause until you move it back."

    const val START_FAILED = "MindTheClub could not start."
    const val RETRY = "Try again"

    const val NO_CHATS = "No contacts"
    const val PICK_A_CHAT = "Choose a contact to start chatting"
    const val MESSAGE_HINT = "Message"
    const val SEND = "Send"
    const val SEEN = "Seen"

    const val RETURN_TO_PHONE = "Return to phone"
    const val RETURN_TITLE = "Return to phone"
    const val RETURN_TEXT =
        "This writes a backup with everything exchanged on this PC and pauses MindTheClub here. " +
                "Restore the backup on your phone to continue there."
    const val RETURN_PASSWORD = "Choose a password (at least 8 characters)"
    const val RETURN_PASSWORD_AGAIN = "Repeat the password"
    const val PASSWORD_TOO_SHORT = "The password must be at least 8 characters."
    const val PASSWORDS_DIFFER = "The two passwords are different."
    const val CONFIRM = "Write backup and pause this PC"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"

    const val PUSH_LOST = "Connection lost: new messages are not arriving. Close and reopen MindTheClub."
    const val SEND_FAILED = "The message could not be saved."
    const val DATABASE_ERROR = "The database could not be read."
}
