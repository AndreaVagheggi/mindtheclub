package com.bolimot.mindtheclub.functions

import android.content.Context
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.NO_PICTURE
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Note to myself" — a purely local pseudo-peer where the user is both sender
 * and recipient. Messages never leave the device: sendMessage() diverts here,
 * the message is simply marked as read, and everything else (chat UI, media,
 * search, forwarding) works because it is a normal conversation to the app.
 */
object NoteToSelf {

    const val USER_ID = "mtc-note-to-self"
    const val SHOW_NOTE_TO_SELF_KEY = "showNoteToSelfOption"
    private const val TAG = "NoteToSelf"

    fun isNoteToSelf(userId: String?): Boolean = userId == USER_ID

    /** "Note to myself" option (OptionsActivity), on by default. Hides the pinned
     *  row, forward selection and search entries — the notes stay intact. */
    fun isVisible(context: Context): Boolean =
        getPreference(SHOW_NOTE_TO_SELF_KEY, context) != "false"

    /**
     * Creates the pinned peer once, and keeps its name (locale changes) and
     * avatar (profile picture changes) in sync on every launch. Blocking the
     * peer is respected: getPeer() also returns blocked peers, so it is not
     * re-created against the user's choice.
     */
    suspend fun ensureSeeded(context: Context) {
        try {
            if (MySelf.userId() == null) return
            val peerDao = getPeerDao(context)
            val name = context.getString(R.string.note_to_self)
            val picture = MySelf.pictureUri() ?: NO_PICTURE

            val existing = peerDao.getPeer(USER_ID)
            if (existing != null) {
                if (existing.name != name) peerDao.updatePeerName(USER_ID, name)
                if (existing.picture != picture) peerDao.updatePeerPicture(USER_ID, picture)
                return
            }

            val peer = Peer(
                uid = 0,
                userId = USER_ID,
                token = "",
                name = name,
                bio = context.getString(R.string.note_to_self_bio),
                picture = picture,
                status = Contact.ACTIVE,
                privateId = ""
            )
            if (getPeerViewModel().addNewPeer(peer)) {
                debugLine(TAG, "Note-to-self peer seeded")
            }
        } catch (e: Exception) {
            debugLine(TAG, "ensureSeeded failed: ${e.message}")
        }
    }

    /**
     * Replaces the whole outbound path: the message is already stored and
     * visible, nothing travels, so it is immediately "read".
     */
    fun handleOutgoing(data: MessageData) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (data.type == Type.PROFILE || data.type == Type.REACTION) return@launch
                getMessageRepository(App.context()).updateStatus(data.messageId, Notify.SEEN)
            } catch (e: Exception) {
                debugLine(TAG, "handleOutgoing failed: ${e.message}")
            }
        }
    }
}
