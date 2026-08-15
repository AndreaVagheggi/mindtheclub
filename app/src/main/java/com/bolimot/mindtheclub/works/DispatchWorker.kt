package com.bolimot.mindtheclub.works

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.functions.CancelledTransferRegistry
import com.bolimot.mindtheclub.functions.ContentServeQueue
import com.bolimot.mindtheclub.functions.PendingMessageTracker
import com.bolimot.mindtheclub.functions.batchTablesExists
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.deleteBatchTables
import com.bolimot.mindtheclub.functions.debugLine2
import com.bolimot.mindtheclub.sending.DispatchResult
import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.sending.dispatchMessage
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.releaseFailedGroupTarget
import com.bolimot.mindtheclub.sending.tryDispatchNextGroupMember
import com.bolimot.mindtheclub.start.App.Companion.context
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await

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
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "DispatchWorkerChannel"
        private const val PREFS_NAME = "DispatchWorkerPrefs"

        /**
         * Cooldown override window after a peer proves it is alive, see [markPeerAlive].
         * Long enough to cover the first retry ladder steps (10s, 20s), short enough
         * that a peer that answered once and went back to sleep is not hammered.
         */
        private const val PROOF_OF_LIFE_MS = 60_000L

        /**
         * Records that [peerId] just proved it is awake (it sent us an FCM asking
         * for a message), so the unreachable cooldown must not block dispatches
         * towards it.
         *
         * This is deliberately a timestamp CHECKED at cooldown time, not a removal
         * of the cooldown keys. The first version only removed the keys, and lost
         * a race observed on Noemi's 7 Aug log: the incoming sendMe replaces the
         * in-flight DispatchWorker for the same message, the replaced attempt dies
         * with GENERAL_FAILURE a moment later and re-arms the cooldown AFTER the
         * clear, and the fresh dispatch then sat on "recently unreachable" for
         * 23 minutes. A proof-of-life timestamp wins regardless of arrival order.
         *
         * The keys are still removed as immediate relief; the timestamp is the
         * authoritative guard.
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

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentText(context().getString(R.string.checking))
            .setOngoing(true)
            .build()

        debugLine("DispatchWorker", "NOTIF_FIRED id=$NOTIFICATION_ID source=DispatchWorker")
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val name = "Message Dispatching"
        val descriptionText = "Shows when a message is being sent in the background."
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString("messageId") ?: return Result.failure()
        val toUserId = inputData.getString("toUserId") ?: return Result.failure()
        val groupId = inputData.getString("groupId")
        val chatGroupId = inputData.getString("chatGroupId")
        val originalSenderId = inputData.getString("originalSenderId")

        val messageDate = inputData.getLong("messageDate", 0L)

        // Serving discipline (group content only): claim the content for this
        // target if nobody holds it, refresh the claim if we already do. Claimed
        // here, not only in the sendMe handler, so transfers submitted by the
        // initial group dispatch are visible to the queue too. A claim held by
        // ANOTHER live target is never stolen: this run simply proceeds, the
        // overlap is at most the old fanout.
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
            // A peer that just asked us for a message is awake by definition: its
            // proof of life outranks any cooldown, even one armed a moment later
            // by an attempt this very request cancelled (see markPeerAlive).
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

        // dispatchMessage carries @RequiresPermission(BLUETOOTH_CONNECT) because its
        // transport may route via Bluetooth, but it must run regardless: without the
        // permission TransportRouter falls back to WebRTC and the Bluetooth layer
        // guards its own calls. Handling SecurityException here satisfies the
        // permission contract without gating the dispatch.
        val result = try {
            dispatchMessage(messageId, toUserId, applicationContext, chatGroupId, originalSenderId, messageDate)
        } catch (se: SecurityException) {
            debugLine("DispatchWorker", "BLUETOOTH_CONNECT denied during dispatch: ${se.message}")
            DispatchResult(RTCClientResult.RTCClientGeneralFailure, 0)
        }

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
            Result.success()
        } else {
            if (newState == TIME_OUT || newState == GENERAL_FAILURE || newState == NOT_CONNECTED) {
                val newCooldownMs = TARGET_COOLDOWN_MS * attemptCount  // 15s, 30s, 45s
                sharedPreferences.edit { putLong("offline_$toUserId", System.currentTimeMillis()) }
                sharedPreferences.edit { putLong("cooldown_$toUserId", newCooldownMs) }
                debugLine2("doWork", "Marked $toUserId as offline (cooldown ${newCooldownMs}ms)")
            }
            if (attemptCount >= MAX_ATTEMPTS) {
                if (!chatGroupId.isNullOrEmpty() && !originalSenderId.isNullOrEmpty() && messageDate > 0L) {
                    debugLine2("doWork", "Max attempts reached for group target $toUserId. Moving to next member.")

                    val earlyPendingKey = "earlyPendingSent_${messageId}_${toUserId}"
                    if (!sharedPreferences.getBoolean(earlyPendingKey, false)) {
                        notifyRemotePeer(toUserId, "$messageKey#$chatGroupId", "pending")
                        sharedPreferences.edit { putBoolean(earlyPendingKey, true) }
                    } else {
                        debugLine2("doWork", "Pending FCM already sent earlier for $toUserId, skipping duplicate")
                    }
                    PendingMessageTracker.record(
                        applicationContext, messageId, toUserId, "$messageKey#$chatGroupId",
                        chatGroupId = chatGroupId,
                        originalSenderId = originalSenderId,
                        messageDate = messageDate
                    )

                    try {
                        val deliveryDocId = computeDeliveryDocId(chatGroupId, originalSenderId, messageDate)
                        val db = com.google.firebase.Firebase.firestore
                        val deliveryRef = db.collection("groupDelivery").document(deliveryDocId)

                        val doc = deliveryRef.get().await()

                        if (doc.exists()) {
                            releaseFailedGroupTarget(
                                chatGroupId = chatGroupId,
                                originalSenderId = originalSenderId,
                                messageDate = messageDate,
                                toUserId = toUserId
                            )

                            val originalMsgId = doc.getString("originalMessageId") ?: messageKey
                            tryDispatchNextGroupMember(
                                context = applicationContext,
                                originalMessageId = originalMsgId,
                                chatGroupId = chatGroupId,
                                originalSenderId = originalSenderId,
                                messageDate = messageDate
                            )
                        }
                    } catch (e: Exception) {
                        debugLine2("doWork", "Failed to pick next group member: ${e.message}")
                    }

                    // Give up on this target: free the line for whoever is waiting.
                    finishServing(serveContentId, toUserId)
                    clearInternalState(messageId, toUserId)
                    Result.success()

                } else {
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
                }
            } else {
                debugLine2("doWork", "dispatchMessage failed: work=retry ($attemptCount/$MAX_ATTEMPTS)")
                saveInternalState(newState, attemptCount)
                Result.retry()
            }
        }
    }

    // Releases the serving claim held by [toUserId] and, when someone is queued
    // for the same content, invites it back with a tiny pending FCM: the member
    // re-asks through the existing reactive path and finds the line free. Doing
    // it by invitation instead of dispatching directly reuses the battle tested
    // pending -> sendMe -> serve flow end to end.
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
