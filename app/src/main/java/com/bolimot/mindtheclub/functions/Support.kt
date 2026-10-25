package com.bolimot.mindtheclub.functions

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.works.AcquireContactWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * "Chat with the MTC team": a real MindTheClub identity on a phone run by the team, not a local
 * pseudo peer. The first time the user turns the option on it is added through the normal contact
 * request flow (the same worker as the QR "Yes"), and the support phone accepts on its own through
 * auto-invite. From then on the option only shows or hides it: the contact is never deleted, so
 * turning it on again needs no new handshake.
 */
object Support {

    const val USER_ID = "71ac8846f90144dea8087ae02fe0272b"
    private const val FINGERPRINT = "9c5ap61L3kkntsThNfkRQw"
    private const val NAME = "MTC Team"
    private const val BIO = "Support"

    const val SHOW_SUPPORT_KEY = "showSupportOption"
    private const val WELCOME_MESSAGE_ID = "mtc-support-welcome"
    private const val ACQUIRE_WORK_NAME = "acquireSupportContact"
    private const val TAG = "Support"

    fun isSupport(userId: String?): Boolean = userId == USER_ID

    /** True on the support phone itself, where per-contact housekeeping must not scale with users. */
    fun isMe(): Boolean = MySelf.userId() == USER_ID

    /** "Chat with the MTC team" option (OptionsActivity), off by default. Hides the contact from
     *  the list, search and forwarding; the conversation stays intact. */
    fun isVisible(context: Context): Boolean =
        getPreference(SHOW_SUPPORT_KEY, context) == "true"

    fun setVisible(context: Context, visible: Boolean) =
        setPreference(SHOW_SUPPORT_KEY, if (visible) "true" else "false", context)

    suspend fun getPeer(context: Context): Peer? = getPeerDao(context).getPeer(USER_ID)

    /**
     * Shows the disclosure and, on Add, makes the support contact visible and starts the first
     * connection. [onResult] gets true when the user accepted.
     */
    fun confirmAndConnect(activity: Activity, onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.support_dialog_title)
            .setMessage(R.string.support_dialog_text)
            .setCancelable(false)
            .setPositiveButton(R.string.support_dialog_add) { _, _ ->
                setVisible(activity, true)
                val appContext = activity.applicationContext
                CoroutineScope(Dispatchers.IO).launch { connect(appContext) }
                onResult(true)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                onResult(false)
            }
            .show()
    }

    /** First connection: the contact request worker plus the local welcome message. */
    private suspend fun connect(context: Context) {
        if (isMe()) return
        try {
            val input = workDataOf(
                "userId" to USER_ID,
                "name" to NAME,
                "bio" to BIO,
                "fingerprint" to FINGERPRINT,
            )
            val request = OneTimeWorkRequestBuilder<AcquireContactWorker>()
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            // KEEP: a second tap while the first request is still running must not start another.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ACQUIRE_WORK_NAME, ExistingWorkPolicy.KEEP, request)

            seedWelcome(context)
        } catch (e: Exception) {
            debugLine(TAG, "connect failed: ${e.message}")
        }
    }

    /** Local message shown as coming from the team. Fixed messageId, so it is saved only once. */
    private suspend fun seedWelcome(context: Context) {
        val myUserId = MySelf.userId() ?: return
        val now = System.currentTimeMillis()
        val welcome = Message(
            uid = 0,
            fromUserId = USER_ID,
            toUserId = myUserId,
            messageId = WELCOME_MESSAGE_ID,
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = context.getString(R.string.support_welcome),
            textAttached = "",
            nameAttached = "",
            uri = "",
            type = Type.TEXT,
            subType = "",
            date = now,
            status = "",
            // Set here so saveMessage() does not count it as a delivery in DeliveryHealth.
            receivedAt = now
        )
        getMessageRepository(context).saveMessage(welcome, messageIn = true)
    }
}
