package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.assistant.AiAssistant
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.NoteToSelf
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.sending.sendMessage
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Type
import java.util.concurrent.TimeUnit

/**
 * Sends a fake text message to the single paired contact every 30 minutes, so
 * delivery latency can be measured over days without waiting for a real tester
 * to write something.
 *
 * Runs only when BuildConfig.SOAK_TEST is true, which is set exclusively in the
 * debug build type. It is deliberately NOT tied to ENABLE_DEBUG_TOOLS, because
 * that one is also true in a release built with -PreleaseLogging=true, which is
 * precisely the build that goes to real testers.
 *
 * The message travels through the ordinary sendMessage path, with no shortcuts:
 * a fake message that behaved differently from a real one would measure nothing.
 */
class SoakTestWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "SoakTestWorker"
        private const val INTERVAL_MINUTES = 30L
        private const val TAG = "SoakTest"
        private const val PREFS_NAME = "SoakTestPrefs"
        private const val KEY_SEQUENCE = "sequence"

        /** Text prefix, also the grep handle for the whole test. */
        const val PREFIX = "SOAK #"

        /**
         * The single remote contact to soak, or null when the handset is not set up
         * for the test.
         *
         * The AI assistant and note-to-self live in the Peer table as ordinary
         * active non-group rows, so a phone with one real contact reports three.
         * They are local pseudo-peers with no FCM token: sending to them would
         * measure nothing. The rest of the app filters them the same way, see
         * PeersFragment.
         */
        private suspend fun soakTarget(context: Context): String? {
            val peers = try {
                getPeerDao(context).getActiveNonGroupPeers()
            } catch (e: Exception) {
                debugLine(TAG, "Peer lookup failed: ${e.message}")
                return null
            }

            val real = peers.filterNot {
                AiAssistant.isAssistant(it.userId) || NoteToSelf.isNoteToSelf(it.userId)
            }

            if (real.size != 1) {
                debugLine(
                    TAG,
                    "Expected exactly 1 real contact, found ${real.size} " +
                            "(${peers.size} rows before filtering pseudo-peers): " +
                            real.joinToString { it.name }
                )
                return null
            }
            return real.first().userId
        }

        /**
         * Schedules the periodic send.
         *
         * The two handsets run the same build, so without an offset they would try
         * to reach each other at the same instant and collide as competing ICE
         * initiators, producing failures that have nothing to do with what is being
         * measured. The offset is derived from comparing the two user ids: the
         * lower one starts immediately, the higher one half a period later. No
         * configuration, and the two devices always disagree.
         *
         * The alignment is approximate, since WorkManager counts from enqueue time
         * and Doze makes periods drift anyway. It only has to make simultaneous
         * firing unlikely, not impossible.
         */
        suspend fun schedule(context: Context) {
            if (!BuildConfig.SOAK_TEST) return

            val myUserId = MySelf.userId()
            if (myUserId.isNullOrEmpty()) {
                debugLine(TAG, "No local user id yet, not scheduling")
                return
            }

            val target = soakTarget(context)
            if (target == null) {
                debugLine(TAG, "No single contact to soak, not scheduling")
                return
            }

            val offsetMinutes = if (myUserId < target) 0L else INTERVAL_MINUTES / 2

            val request = PeriodicWorkRequestBuilder<SoakTestWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setInitialDelay(offsetMinutes, TimeUnit.MINUTES).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            debugLine(TAG, "Scheduled every ${INTERVAL_MINUTES}min, offset ${offsetMinutes}min")
        }
    }

    override suspend fun doWork(): Result {
        if (!BuildConfig.SOAK_TEST) return Result.success()

        val myUserId = MySelf.userId()
        if (myUserId.isNullOrEmpty()) {
            debugLine(TAG, "No local user id, skipping")
            return Result.success()
        }

        // Guard rather than guess: this only makes sense on the two dedicated test
        // handsets, which hold exactly one real contact each.
        val target = soakTarget(applicationContext) ?: return Result.success()

        val sequence = nextSequence()
        val now = System.currentTimeMillis()

        // The timestamp is in the text as well as in Message.date. date alone would
        // do, but repeating it keeps each log line self contained, so the analysis
        // is a grep and two columns.
        val message = Message(
            uid = 0,
            fromUserId = myUserId,
            toUserId = target,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = "$PREFIX$sequence @$now",
            textAttached = null,
            nameAttached = null,
            uri = "",
            type = Type.TEXT,
            subType = null,
            date = now,
            status = applicationContext.getString(R.string.sending),
            chatGroupId = null,
            originalSenderId = null
        )

        val saved = try {
            getMessageRepository(applicationContext).saveMessage(message, messageIn = false)
        } catch (e: Exception) {
            debugLine(TAG, "Save failed for #$sequence: ${e.message}")
            false
        }

        if (!saved) {
            debugLine(TAG, "Could not save #$sequence locally, not sending")
            return Result.success()
        }

        debugLine(TAG, "SENT #$sequence at $now to $target (messageId=${message.messageId})")
        sendMessage(MessageData.fromMessage(message))
        return Result.success()
    }

    private fun nextSequence(): Int {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_SEQUENCE, 0) + 1
        prefs.edit { putInt(KEY_SEQUENCE, next) }
        return next
    }
}

/**
 * Receiver side of the soak test: turns an arriving SOAK message into one
 * greppable line carrying the sequence number and the end to end latency.
 *
 * Called from the raw arrival point rather than after assembly, so the number is
 * the true wire latency and is not inflated by local processing.
 */
fun logSoakArrival(text: String?, sentAt: Long) {
    if (!BuildConfig.SOAK_TEST) return
    if (text == null || !text.startsWith(SoakTestWorker.PREFIX)) return

    val latencyMs = System.currentTimeMillis() - sentAt
    debugLine("SoakTest", "RECEIVED $text latency=${latencyMs / 1000}s (${latencyMs}ms)")
}
