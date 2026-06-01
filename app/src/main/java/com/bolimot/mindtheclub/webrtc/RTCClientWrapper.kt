package com.bolimot.mindtheclub.webrtc

import android.content.Context
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.debugLine4
import com.bolimot.mindtheclub.tools.CallControlEvent
import com.bolimot.mindtheclub.tools.CallEventBus
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Voip
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.bolimot.mindtheclub.transport.Transport

class RTCClientWrapper : Transport {
    lateinit var rtcClient: RTCClient

    companion object {
        suspend fun create(
            channelId: String,
            callId: String,
            remoteUserId: String,
            initiator: Boolean,
            context: Context,
            video: Boolean,
            dataOnly: Boolean
        ): RTCClientResult {
            val client = RTCClientWrapper()
            return client.initialize(channelId, callId, remoteUserId, initiator, context, video, dataOnly)
        }
    }

    private suspend fun initialize(channelId: String, callId: String, remoteUserId: String, initiator: Boolean, context: Context, video: Boolean, dataOnly: Boolean): RTCClientResult {
        val tag = "RTCClientWrapper / Initialize"

        debugLine(tag, "Initializing RTC for channelId: $channelId, for callId = $callId")

        val notificationType = when {
            dataOnly -> Notify.DATA_CALL
            video    -> Notify.VIDEO_CALL
            else     -> Notify.AUDIO_CALL
        }

        val eventType = when {
            dataOnly -> CallEvent.DATA_CHANNEL_OPEN
            else     -> CallEvent.CONNECTION_OPEN
        }

        val failureEvents = setOf(
            CallEvent.CONNECTION_FAILED,
            CallEvent.WEBRTC_SHUTDOWN,
            CallEvent.FAILED,
            CallEvent.CANCEL,
            CallEvent.CONNECTION_BUSY
        )

        if (initiator && dataOnly) {
            val contentHint = ConnectionManager.instance.consumeDispatchContentHint(remoteUserId)
            val extra = if (!contentHint.isNullOrEmpty()) mapOf("contentKey" to contentHint) else emptyMap()
            if (!fcmSendInstant(remoteUserId, channelId, callId, notificationType, notificationType, extra)) {
                debugLine(tag, "Failed to send FCM message, unable to wake up peer")
                return RTCClientResult.RTCClientNotCreated
            } else {
                debugLine(tag, "Successfully sent wake up FCM, channelId=$channelId, callId=$callId, contentKey=${contentHint ?: "-"}")
            }
        }

        rtcClient = RTCClient.create(channelId, callId, remoteUserId, initiator, video, context, dataOnly)

        val result: CallControlEvent? = withTimeoutOrNull(Voip.WEBRTC_CONNECTION_TIMEOUT) {
            CallEventBus.callControlFlow
                .filterNotNull()
                .first { event ->
                    event.remoteUserId == remoteUserId && ( //SOAK CHANGE
                    event.action == eventType ||
                    event.action == CallEvent.BUSY ||
                    event.action in failureEvents
                    )
                }
        }

        when (result?.action) {
            eventType -> {
                debugLine4(tag, "Connection is OPEN. isOnlyData = $dataOnly")
                return RTCClientResult.Success(this)
            }
            null -> {
                debugLine4(tag, "Incoming connection timed out, returning general failure")

                rtcClient.cleanup(true)

                var safetyWait = 0
                while (!rtcClient.cleanedUp && safetyWait < 50) {
                    kotlinx.coroutines.delay(100)
                    safetyWait++
                }

                debugLine4(tag, "Cleanup finished (waited ${safetyWait * 100}ms). Returning failure.")
                return RTCClientResult.RTCClientGeneralFailure
            }
            else -> {
                debugLine4(tag, "Incoming connection failed, Result = : ${result.reason}")
                rtcClient.cleanup(true)

                var safetyWait = 0
                while (!rtcClient.cleanedUp && safetyWait < 50) {
                    kotlinx.coroutines.delay(100)
                    safetyWait++
                }

                return if (result.action == CallEvent.CONNECTION_BUSY) {
                    RTCClientResult.RTCClientBusy
                } else {
                    RTCClientResult.RTCClientGeneralFailure
                }
            }
        }
    }

    override suspend fun sendData(data: ByteArray): Boolean {
        return rtcClient.sendData(data)
    }
}