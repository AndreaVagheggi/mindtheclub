package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.contentKeyOf
import com.bolimot.mindtheclub.functions.DeliveryHealth
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getInboxDao
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.pickRecoverySource
import com.bolimot.mindtheclub.receiving.isProcessingActive
import com.bolimot.mindtheclub.receiving.missingChunksByContent
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type
import java.util.concurrent.TimeUnit

class InboxRecoveryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "InboxRecoveryWorker"
        private const val INTERVAL_MINUTES = 15L
        private const val TAG = "InboxRecovery"

        // Pass 3 bounds. Younger sets are still being fed by the reactive someMissing path;
        // older ones are dead transfers whose chunks would otherwise pin the DataSyncService
        // "assembly pending" loop for ever.
        private const val MIN_ORPHAN_AGE_MS = 5 * 60 * 1000L
        private const val MAX_ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L

        /** Ceiling for pass 2, same window as [MAX_ORPHAN_AGE_MS]. See the use site. */
        private const val MAX_RECEIVING_AGE_MS = 24 * 60 * 60 * 1000L

        private const val MAX_SEEDER_LOOKUPS_PER_PASS = 5

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InboxRecoveryWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            debugLine(TAG, "Scheduled periodic worker (${INTERVAL_MINUTES}min)")
        }
    }

    override suspend fun doWork(): Result {
        DeliveryHealth.recordHeartbeat(applicationContext)

        val inboxDao = getInboxDao(applicationContext)
        val messageDao = getMessageDao(applicationContext)

        // Pass 1: complete messages whose assembly needs re-triggering (via AssembleMessageWorker)
        val completeIds = inboxDao.getCompleteMessageIds()

        if (completeIds.isNotEmpty()) {
            debugLine(TAG, "Found ${completeIds.size} complete inbox message(s) to recover")

            for (messageId in completeIds) {
                if (isProcessingActive(messageId)) {
                    debugLine(TAG, "Skipping $messageId — already being processed")
                    continue
                }

                try {
                    val firstChunk = inboxDao.getFirstMessage(messageId)
                    if (firstChunk == null) {
                        debugLine(TAG, "No inbox data for $messageId, skipping")
                        continue
                    }

                    val type = firstChunk.type
                    if (type == Type.PROFILE || type == Type.REACTION) {
                        debugLine(TAG, "Cleaning up stale $type inbox chunks for $messageId")
                        val recoveryContentKey = contentKeyOf(firstChunk)
                        inboxDao.deleteByContent(recoveryContentKey)
                        continue
                    }

                    // Assembly must run under WorkManager protection, never as a fire and
                    // forget coroutine: doWork() returning while assembly is still running
                    // drops the doze protection mid write.
                    debugLine(TAG, "Re-triggering assembly for $messageId ($type)")
                    AssembleMessageWorker.enqueue(applicationContext, messageId)
                } catch (e: Exception) {
                    debugLine(TAG, "Failed to recover $messageId: ${e.message}")
                }
            }
        }

        // Pass 2: incomplete RECEIVING messages (placeholders stuck with partial chunks)
        try {
            val receivingMessages = messageDao.getReceivingMessages()
            // Bounded seeder lookups for the whole pass: an informed choice is worth one doc
            // read, a Firestore crawl over a pile of stalled contents is the 15 Aug mistake and
            // must stay impossible.
            var seederLookups = 0
            if (receivingMessages.isNotEmpty()) {
                debugLine(TAG, "Found ${receivingMessages.size} RECEIVING message(s) to check")

                for (msg in receivingMessages) {
                    try {
                        val ck = contentKeyOf(msg.messageId, msg.chatGroupId, msg.originalSenderId, msg.date)
                        val count = inboxDao.countChunksByContent(ck)
                        val total = inboxDao.getTotalChunksByContent(ck)

                        if (count > 0 && count >= total) {
                            debugLine(TAG, "RECEIVING $msg.messageId is actually complete ($count/$total), skipping")
                            continue
                        }

                        // The one recovery track that had no ceiling of any kind. Pass 3 below
                        // stops at MAX_ORPHAN_AGE_MS and drops the chunks; the outgoing tracker
                        // prunes at 14 days; the incoming one caps at 6 requests. This loop had
                        // neither an age nor a retry limit: a placeholder whose content no
                        // longer exists anywhere asked for it every INTERVAL_MINUTES for ever,
                        // 96 requests a day, until the message was deleted by hand. Each one is
                        // an FCM plus a signalling room, and on 19 and 20 Aug several were
                        // still going out twelve hours later against holders that answered "no
                        // message in DB and no batch tables" and said nothing back, so the
                        // asker never learned to stop.
                        //
                        // Capped at the same 24 hours as pass 3, apposta: past that point pass
                        // 3 has already deleted every orphan chunk set of that age, so a later
                        // request cannot be served by anyone anyway. The placeholder is left
                        // alone, only the soliciting stops.
                        val age = System.currentTimeMillis() - msg.date
                        if (age > MAX_RECEIVING_AGE_MS) {
                            debugLine(
                                TAG,
                                "RECEIVING ${msg.messageId} is ${age / 3600000}h old, no longer soliciting"
                            )
                            continue
                        }

                        // originalSenderId is only populated for group messages. For a 1:1 the
                        // placeholder's fromUserId IS the sender, so fall back to it: without
                        // this the whole pass silently skipped every stalled 1:1 transfer and
                        // only groups ever got re-solicited.
                        val originalSender = msg.originalSenderId?.takeIf { it.isNotEmpty() }
                            ?: msg.fromUserId.takeIf { !it.startsWith("group") }
                        if (!originalSender.isNullOrEmpty()) {
                            // Resume aware: report the missing range so the sender re-sends
                            // only those chunks (see the PENDING/SEND_ME handlers).
                            val missing = missingChunksByContent(inboxDao, ck)
                            val missingRange = if (missing != null && missing.isNotEmpty()) {
                                "${missing.min()},${missing.max()}"
                            } else {
                                null
                            }
                            // Ask a member holding a COMPLETE copy when one is known, the
                            // original sender otherwise. The origin may be on a weak holiday
                            // uplink while a seeder sits on the same Wi-Fi.
                            val recoveryTarget = if (!msg.chatGroupId.isNullOrEmpty()
                                && seederLookups < MAX_SEEDER_LOOKUPS_PER_PASS
                            ) {
                                seederLookups++
                                pickRecoverySource(msg.chatGroupId, msg.originalSenderId, msg.date, originalSender)
                            } else {
                                originalSender
                            }
                            debugLine(TAG, "RECEIVING ${msg.messageId} stalled at $count/$total, sending sendMe to $recoveryTarget (missing: ${missingRange ?: "all"})")
                            notifyRemotePeer(recoveryTarget, msg.messageId, Notify.SEND_ME, missingRange)
                        } else {
                            debugLine(TAG, "RECEIVING ${msg.messageId} has no resolvable sender, skipping")
                        }
                    } catch (e: Exception) {
                        debugLine(TAG, "Failed to process RECEIVING ${msg.messageId}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            debugLine(TAG, "Failed to query RECEIVING messages: ${e.message}")
        }

        // Pass 3: incomplete chunk sets with NO Message row. Profiles never create a receiving
        // placeholder (receiveData skips Type.PROFILE apposta, a profile must not show as a
        // bubble in the chat), so a profile interrupted mid transfer was invisible to pass 2 and
        // had exactly one recovery shot: the reactive someMissing sent when the peer's
        // `completed` arrived. If that single round failed the contact sat on "Pending" for
        // ever, which is what stuck Romy at 36/41 chunks on 7 Aug. This pass re-solicits ANY
        // orphan incomplete set every run, same cadence and same resume aware sendMe as pass 2.
        try {
            val orphanIds = inboxDao.getDistinctMessageIds()
            for (messageId in orphanIds) {
                try {
                    if (isProcessingActive(messageId)) continue
                    if (CancelledTransferRegistry.isCancelled(applicationContext, messageId)) continue

                    val firstChunk = inboxDao.getFirstMessage(messageId) ?: continue
                    val ck = contentKeyOf(firstChunk)
                    val count = inboxDao.countChunksByContent(ck)
                    val total = inboxDao.getTotalChunksByContent(ck)
                    if (count <= 0 || count >= total) continue // complete sets are pass 1 territory

                    // Sets with a Message row are pass 2 territory; asking twice per run would
                    // double the sendMe traffic for the same transfer.
                    val placeholderKey = firstChunk.groupId.ifEmpty { firstChunk.messageId }
                    if (messageDao.getMessage(placeholderKey) != null) continue

                    val age = System.currentTimeMillis() - firstChunk.date
                    if (age > MAX_ORPHAN_AGE_MS) {
                        val dropped = inboxDao.deleteByContent(ck)
                        debugLine(TAG, "Orphan set $messageId expired (${age / 3600000}h old), dropped $dropped chunk(s)")
                        continue
                    }
                    if (age < MIN_ORPHAN_AGE_MS) continue // in flight, the reactive path is still hot

                    val sender = firstChunk.originalSenderId?.takeIf { it.isNotEmpty() }
                        ?: firstChunk.fromUserId
                    if (sender.startsWith("group")) continue

                    val missing = missingChunksByContent(inboxDao, ck) ?: continue
                    val missingRange = if (missing.isNotEmpty()) "${missing.min()},${missing.max()}" else null
                    debugLine(TAG, "Orphan set $messageId (${firstChunk.type}) stalled at $count/$total, sending sendMe to $sender (missing: ${missingRange ?: "all"})")
                    notifyRemotePeer(sender, messageId, Notify.SEND_ME, missingRange)
                } catch (e: Exception) {
                    debugLine(TAG, "Failed to process orphan set $messageId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            debugLine(TAG, "Failed to query orphan sets: ${e.message}")
        }

        // Status import required. Suppress unused warning on the imported symbol.
        return Result.success()
    }
}