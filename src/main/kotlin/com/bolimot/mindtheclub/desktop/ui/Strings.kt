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

    const val YOU = "You"
    const val FORWARDED = "forwarded"

    // What can be done with a picked message, and the list of a forward, in the App's own words.
    const val REPLY = "Reply"
    const val FORWARD = "Forward"
    const val SELECT_CONTACTS = "Select contacts"

    // Reactions, in the App's own words.
    const val REACTIONS = "Reactions"
    fun reactionsAll(count: Int) = "All $count"
    const val TAP_TO_REMOVE = "Tap to remove"
    const val SELECT_AN_EMOJI = "Select an Emoji"
    const val MEMBER = "Member"

    const val SEND_VIDEO_TITLE = "Send video"
    const val PREPARING_VIDEO = "Preparing video..."

    // A file and a shared contact, in the App's own words.
    const val SEND_FILE_TITLE = "Send file"
    const val FILE_UNREADABLE = "This file cannot be read."
    const val CONTACTS = "Contacts"
    const val VIEW_PROFILE = "View Profile"
    const val ADD_CONTACT = "Add Contact"
    const val NO_CONTACTS_TO_SHARE = "There are no other contacts to share."

    // The actions of a picked message, in the App's own words (R.string.delete, copy).
    const val DELETE = "Delete"
    const val COPY = "Copy"
    const val SHARE = "Share"
    const val SHARE_FAILED = "The Windows Share panel could not be opened."

    // A link, a GIF, a sticker, in the App's own words (R.string.link, gif, sticker).
    const val LINK = "Link"
    const val GIF = "GIF"
    const val STICKER = "Sticker"
    const val SEND_GIF_TITLE = "Send GIF"
    const val GIF_NOT_DOWNLOADED = "The GIF could not be downloaded."
    const val PICTURE_NOT_DOWNLOADED = "The picture could not be downloaded."

    // Voice messages, in the App's own words (R.string.audio, R.string.microphone).
    const val VOICE_MESSAGE = "Voice message"
    const val MICROPHONE = "Microphone"
    const val MICROPHONE_UNAVAILABLE = "The microphone cannot be used. Check that one is connected."
    const val VOICE_MESSAGE_UNPLAYABLE = "This voice message cannot be played on this PC."
    const val VOICE_MESSAGE_NOT_SAVED = "The voice message could not be prepared."

    // A contact's changed key, in the App's own words (R.string.key_change_*).
    const val KEY_CHANGE_TITLE = "Security code changed"
    fun keyChangeMessage(name: String) =
        "$name may have changed phone or reinstalled the app, so their security code is different now. " +
                "If you want to be sure, verify it directly with your contact, then accept the new identity to keep messaging."
    const val KEY_CHANGE_ACCEPT = "Accept new identity"
    const val KEY_CHANGE_LATER = "Not now"
    const val KEY_CHANGE_ACCEPTED = "New identity accepted"
    const val KEY_CHANGE_FAILED = "Could not verify the new identity, try again later"

    // Adding a contact and a contact being added, in the App's own words (new_peer_dialog.xml,
    // NewPeerDialog, peer_view.xml).
    const val ADD_NEW_CONTACT = "Add new contact"
    const val ADD_MESSAGE = "Do you want to add this contact?"
    const val YES = "Yes"
    const val NO = "No"
    const val PENDING = "Pending…"
    const val REMOVE_PENDING = "Remove pending contact"
    const val REMOVE_FAILED = "The pending contact could not be removed."

    // The paperclip and its four choices, in the App's own words.
    const val ATTACH = "Attachment"
    const val IMAGES = "Images"
    const val VIDEO = "Video"
    const val FILE = "File"
    const val CONTACT = "Contact"

    const val PHOTO = "Photo"
    const val SEND_PHOTO_TITLE = "Send photo"
    const val CAPTION_HINT = "Add a caption"
    const val RECEIVING = "Receiving…"
    const val MEDIA_NOT_AVAILABLE = "Media not available"
    const val PHOTO_UNREADABLE = "This picture cannot be read. Use a JPEG, PNG, BMP or GIF file."

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
