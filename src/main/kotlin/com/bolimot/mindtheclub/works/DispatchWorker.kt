package com.bolimot.mindtheclub.works

// DESKTOP: this is the App's DispatchWorker.kt with the parts that cannot exist on a PC taken
// out. Everything else, the states, the three attempts, the cooldowns, the proof of life, the
// global circuit breaker, the pending announcement and the record in PendingMessageTracker, is
// the App's code unchanged. Removed:
//
//   - getForegroundInfo and the notification channel (an Android foreground notification);
//   - the "Android state" log line and the "stopped by the system" one (doze, standby bucket,
//     expedited quota: none of it exists here);
//   - the catch of SecurityException around dispatchMessage (a missing Bluetooth permission);
//   - THE GROUP BRANCH AT EXHAUSTION, which releases the failed member in the Firestore
//     delivery document and picks the next one. Sending to a group is not on the PC yet
//     (sendMessage refuses it), so no dispatch with group coordinates can reach this worker.
//     If one ever did it is handled as a 1:1 target, which announces and records it: nothing
//     is lost, only the move to the next member is missing.

import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.ContentServeQueue
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.batchTablesExists
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.sending.dispatchMessage
import com.bolimot.mindtheclub.sending.notifyRemotePeer

class DispatchWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private lateinit var internalStateKey: String
    private lateinit var messageKey: String
    private lateinit var attemptCountKey: String

    companion object {
        private const val NEW = 0
        private const val NOT_CONNECTED = 1
        private const val NOT_CREATED = 2
        private const val SUCCESS = 3
        private const val BUSY = 4
        private const val NOT_USABLE = 5
        private const val GENERAL_FAILURE = 6
        private const val TIME_OUT = 7

        private const val MAX_ATTEMPTS = 3
        private const val MAX_GLOBAL_CYCLES = 3
        private const val GLOBAL_CYCLE_WINDOW_MS = 30 * 60 * 1000L
        private const val TARGET_COOLDOWN_MS = 15_000L
        private const val PREFS_NAME = "DispatchWorkerPrefs"

        /**
         * Cooldown override window after a peer proves it is alive, see [markPeerAlive].
         * Long enough for the first retry rungs (10s, 20s), short enough that a peer that
         * answered once and went back to sleep is not hammered.
         */
        private const val PROOF_OF_LIFE_MS = 60_000L

        /**
         * Records that [peerId] just proved it is awake (it sent us a signal asking for a
         * message), so the unreachable cooldown must not block dispatches towards it.
         *
         * Deliberately a timestamp CHECKED at cooldown time, not a removal of the keys: the
         * incoming sendMe replaces the in flight DispatchWorker, the replaced attempt dies a
         * moment later and re-arms the cooldown AFTER the clear. A proof of life timestamp wins
         * whatever the arrival order (see the App's file, Noemi's log of 7 Aug).
         */
        fun markPeerAlive(context: Context, peerId: String) {
            if (peerId.isEmpty()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                putLong("alive_$peerId", System.currentTimeMillis())
                remove("offline_$peerId")
                remove("cooldown_$peerId")
            }
            debugLine2("markPeerAlive", "Peer $peerId proved alive, cooldown overridden for ${PROOF_OF_LIFE_MS / 1000}s")
        }
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString("messageId") ?: return Result.failure()
        val toUserId = inputData.getString("toUserId") ?: return Result.failure()
        val groupId = inputData.getString("groupId")
        val chatGroupId = inputData.getString("chatGroupId")
        val originalSenderId = inputData.getString("originalSenderId")

        val messageDate = inputData.getLong("messageDate", 0L)

        debugLine2("doWork", "attempt=$runAttemptCount")

        // Serving discipline (group content only): claim the content for this target if
        // nobody holds it, refresh the claim if we already do.
        val serveContentId = ContentServeQueue.contentIdOf(chatGroupId, originalSenderId, messageDate)
        serveContentId?.let { ContentServeQueue.claimIfFree(it, toUserId) }

        if (CancelledTransferRegistry.isCancelled(applicationContext, messageId)) {
            debugLine("DispatchWorker", "Transfer $messageId was cancelled, dropping dispatch")
            deleteBatchTables(messageId)
            PendingMessageTracker.remove(applicationContext, messageId, toUserId)
            finishServing(serveContentId, toUserId)
            return Result.success()
        }

        val globalCycleKey = "globalCycle_${messageId}_${toUserId}"
        val globalCycleTimeKey = "globalCycleTime_${messageId}_${toUserId}"
        val firstCycleTime = sharedPreferences.getLong(globalCycleTimeKey, 0L)
        val globalCycles = sharedPreferences.getInt(globalCycleKey, 0)

        if (firstCycleTime > 0 && System.currentTimeMillis() - firstCycleTime > GLOBAL_CYCLE_WINDOW_MS) {
            sharedPreferences.edit {
                putInt(globalCycleKey, 0)
                putLong(globalCycleTimeKey, 0L)
            }
        } else if (globalCycles >= MAX_GLOBAL_CYCLES) {
            debugLine("DispatchWorker", "Global circuit breaker: $messageId to $toUserId exhausted $globalCycles cycles. Giving up.")
            clearInternalState(messageId, toUserId)
            sharedPreferences.edit {
                remove(globalCycleKey)
                remove(globalCycleTimeKey)
            }
            return Result.success()
        }

        val lastFailure = sharedPreferences.getLong("offline_$toUserId", 0L)
        val cooldownMs = sharedPreferences.getLong("cooldown_$toUserId", TARGET_COOLDOWN_MS)

        if (lastFailure > 0 && System.currentTimeMillis() - lastFailure < cooldownMs) {
            // A peer that just asked us for a message is awake by definition: its proof
            // of life outranks any cooldown, even one armed a moment later by an attempt
            // this very request cancelled (see markPeerAlive).
            val provedAliveAt = sharedPreferences.getLong("alive_$toUserId", 0L)
            if (System.currentTimeMillis() - provedAliveAt < PROOF_OF_LIFE_MS) {
                debugLine2("doWork", "Cooldown overridden for $toUserId, peer proved alive ${(System.currentTimeMillis() - provedAliveAt) / 1000}s ago")
            } else {
                debugLine2("doWork", "Target $toUserId recently unreachable, deferring retry for $messageId")
                return Result.retry()
            }
        }

        var newState = NEW

        internalStateKey = "work$messageId"
        attemptCountKey = "attemptCount$messageId"

        messageKey = if (groupId.isNullOrEmpty()) {
            messageId
        } else {
            groupId
        }

        val internalState = sharedPreferences.getInt(internalStateKey, NEW)
        var attemptCount = sharedPreferences.getInt(attemptCountKey, 0)

        if(internalState != BUSY) attemptCount++

        debugLine2("doWork", "internalState: $internalState, attemptCount: $attemptCount")

        val result = dispatchMessage(messageId, toUserId, applicationContext, chatGroupId, originalSenderId, messageDate)

        when (result.clientResult) {
            RTCClientResult.RTCClientNotConnected -> {
                newState = NOT_CONNECTED
            } // Failure cause was unable to contact peer
            RTCClientResult.RTCClientNotCreated -> {
                newState = NOT_CREATED
            } // Failure cause was unable to create data client
            RTCClientResult.RTCClientSuccess -> {
                newState = SUCCESS
            } // Message sent successfully
            RTCClientResult.RTCClientBusy -> {
                newState = BUSY
                debugLine2("doWork", "Peer $toUserId replied connectionBusy for $messageId — will retry with backoff")
                val busyBackoff = 30_000L + (Math.random() * 60_000L).toLong()
                sharedPreferences.edit { putLong("offline_$toUserId", System.currentTimeMillis()) }
                sharedPreferences.edit { putLong("cooldown_$toUserId", busyBackoff) }
            } // Receiver is busy (too many active senders) or data client being created
            RTCClientResult.RTCClientGeneralFailure -> {
                newState = GENERAL_FAILURE
            } // Failure cause was unable to create data client with Exception
            RTCClientResult.RTCClientNotUsable -> {
                newState = NOT_USABLE
            } // webRTC client created but without working data channel
            RTCClientResult.RTCClientNotNeeded -> {
                newState = SUCCESS
            } // webRTC client not needed, there are no batches to dispatch
            RTCClientResult.RTCClientTimeOut -> {
                newState = TIME_OUT
            } // webRTC signaling timed out, remote peer did not respond

            is RTCClientResult.Success -> { }

        }

        debugLine2("doWork", "newState: ${getState(newState)}, chunksSent: ${result.chunksSent}")

        if (newState != SUCCESS
            && result.chunksSent == 0
            && !chatGroupId.isNullOrEmpty()
            && !originalSenderId.isNullOrEmpty()
            && messageDate > 0L
        ) {
            val earlyPendingKey = "earlyPendingSent_${messageId}_${toUserId}"
            if (!sharedPreferences.getBoolean(earlyPendingKey, false)) {
                debugLine2("doWork", "Early pending: group attempt failed with 0 chunks for $toUserId, sending pending FCM now")
                notifyRemotePeer(toUserId, "$messageKey#$chatGroupId", "pending")
                sharedPreferences.edit { putBoolean(earlyPendingKey, true) }
            }
        }
        return if (newState == SUCCESS) {
            debugLine2("doWork", "dispatchMessage successful: work=success, for messageId: $messageId, part of: $groupId")
            sharedPreferences.edit { remove("offline_$toUserId") }
            // The line moves: whoever queued behind this transfer gets invited now.
            finishServing(serveContentId, toUserId)

            if (batchTablesExists(messageId)) {
                val pendingKey = if (!chatGroupId.isNullOrEmpty()) "$messageKey#$chatGroupId" else messageKey
                PendingMessageTracker.record(
                    applicationContext, messageId, toUserId, pendingKey,
                    chatGroupId = chatGroupId ?: "",
                    originalSenderId = originalSenderId ?: "",
                    messageDate = messageDate
                )
                debugLine2("doWork", "Recorded pending for $messageId → $toUserId (awaiting allReceived)")
            }

            clearInternalState(messageId, toUserId)
            // The exhaust one shot is the only prefs entry that outlives a cycle, so it
            // is cleared here, where the transfer finally went through.
            sharedPreferences.edit { remove("exhaustPendingSent_${messageId}_${toUserId}") }
            Result.success()
        } else {
            if (newState == TIME_OUT || newState == GENERAL_FAILURE || newState == NOT_CONNECTED) {
                val newCooldownMs = TARGET_COOLDOWN_MS * attemptCount  // 15s, 30s, 45s
                sharedPreferences.edit { putLong("offline_$toUserId", System.currentTimeMillis()) }
                sharedPreferences.edit { putLong("cooldown_$toUserId", newCooldownMs) }
                debugLine2("doWork", "Marked $toUserId as offline (cooldown ${newCooldownMs}ms)")
            }
            if (attemptCount >= MAX_ATTEMPTS) {
                // DESKTOP: the App has a group branch here, see the top of the file.
                val currentCycles = sharedPreferences.getInt(globalCycleKey, 0) + 1
                sharedPreferences.edit {
                    putInt(globalCycleKey, currentCycles)
                    if (currentCycles == 1) putLong(globalCycleTimeKey, System.currentTimeMillis())
                }

                if (currentCycles >= MAX_GLOBAL_CYCLES) {
                    debugLine2("doWork", "Global circuit breaker: $messageId to $toUserId after $currentCycles cycles. Abandoning.")
                    notifyRemotePeer(toUserId, messageKey, "pending")
                    PendingMessageTracker.record(applicationContext, messageId, toUserId, messageKey)
                    clearInternalState(messageId, toUserId)
                    sharedPreferences.edit {
                        remove(globalCycleKey)
                        remove(globalCycleTimeKey)
                    }
                    return Result.success()
                }

                debugLine2("doWork", "Max attempts ($attemptCount) reached, cycle $currentCycles/$MAX_GLOBAL_CYCLES. Sending pending message.")
                notifyRemotePeer(toUserId, messageKey, "pending")
                PendingMessageTracker.record(applicationContext, messageId, toUserId, messageKey)

                clearInternalState(messageId, toUserId)
                Result.success()
            } else {
                debugLine2("doWork", "dispatchMessage failed: work=retry ($attemptCount/$MAX_ATTEMPTS)")
                saveInternalState(newState, attemptCount)
                Result.retry()
            }
        }
    }

    // Releases the serving claim held by [toUserId] and, when someone is queued for the
    // same content, invites it back with a tiny pending signal.
    private fun finishServing(contentId: String?, toUserId: String) {
        if (contentId == null) return
        try {
            val next = ContentServeQueue.finish(contentId, toUserId) ?: return
            debugLine2("ServeQueue", "Done serving $toUserId for $contentId, inviting queued ${next.first}")
            notifyRemotePeer(next.first, next.second, "pending")
        } catch (e: Exception) {
            debugLine2("ServeQueue", "Failed to invite next for $contentId: ${e.message}")
        }
    }

    private fun getState(newState: Int): String {
        return when (newState) {
            NEW -> "NEW"
            NOT_CONNECTED -> "NOT_CONNECTED"
            NOT_CREATED -> "NOT_CREATED"
            SUCCESS -> "SUCCESS"
            BUSY -> "BUSY"
            NOT_USABLE -> "NOT_USABLE"
            GENERAL_FAILURE -> "GENERAL_FAILURE"
            TIME_OUT -> "TIME_OUT"
            else -> "UNKNOWN"
        }
    }

    private fun saveInternalState(state: Int, attemptCount: Int) {
        sharedPreferences.edit {
            putInt(internalStateKey, state).putInt(
                attemptCountKey,
                attemptCount
            )
        }
    }

    private fun clearInternalState(messageId: String, toUserId: String) {
        sharedPreferences.edit {
            remove(internalStateKey)
            remove(attemptCountKey)
            remove("earlyPendingSent_${messageId}_${toUserId}")
        }
    }
}
