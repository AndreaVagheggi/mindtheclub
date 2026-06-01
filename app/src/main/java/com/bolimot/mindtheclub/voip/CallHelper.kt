package com.bolimot.mindtheclub.voip

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.telecom.DisconnectCause
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.notifications.MessageReceivedNotification
import com.bolimot.mindtheclub.receiving.chatScreenIsInForeground
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.views.AppTab
import com.bolimot.mindtheclub.views.AudioCall
import com.bolimot.mindtheclub.views.VideoCall
import com.bolimot.mindtheclub.voip.ManagedTelecom.CallSession
import com.bolimot.mindtheclub.voip.ManagedTelecom.disconnectCall
import com.bolimot.mindtheclub.voip.ManagedTelecom.setCallActive
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.webrtc.RTCClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun startCall(context: Context, remoteUserId: String, isVideo: Boolean) {

    val callId = guid()

    debugLine("startCall", "Creating callId: $callId")

    val intent = Intent(context, CallService::class.java).apply {
        action = CallService.ACTION_OUTGOING_CALL
        putExtra(CallService.EXTRA_REMOTE_USER_ID, remoteUserId)
        putExtra(CallService.EXTRA_CALL_ID, callId)
        putExtra(CallService.EXTRA_IS_VIDEO, isVideo)
    }
    context.startService(intent)
}

suspend fun sendCallEventToPeer(remoteUserId: String, action: String, callId: String? = null): Boolean {
    return fcmSendInstant(remoteUserId, "callEvent", callId ?: action, action, action)
}

fun receiveCallEventFromPeer(action: String, callId: String) {
    val tag = "onCallEvent"
    val context = App.context()

    when (action) {
        CallEvent.ACCEPT -> {
            debugLine(tag, "Call accepted by remote")

            ManagedTelecom.stopRingbackTone()

            scope.launch {
                setCallActive(callId)
            }
        }
        CallEvent.NO_ANSWER -> {
            debugLine(tag, "Call not answered by remote")
            disconnectCall(callId, DisconnectCause(DisconnectCause.MISSED))
        }
        CallEvent.REJECT -> {
            debugLine(tag, "Call rejected by remote")
            disconnectCall(callId, DisconnectCause(DisconnectCause.REJECTED))
        }
        CallEvent.BUSY -> {
            debugLine(tag, "Call remote is busy. Playing busy tone for 3s, then disconnecting callId: $callId")

            ManagedTelecom.stopRingbackTone()

            val toneGenerator: ToneGenerator? = try {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
            } catch (e: Exception) {
                debugLine(tag, "Failed to create ToneGenerator: ${e.message}")
                null
            }

            if (toneGenerator == null) {
                debugLine(tag, "ToneGenerator null, disconnecting immediately.")
                disconnectCall(callId, DisconnectCause(DisconnectCause.REMOTE))
            } else {
                try {
                    toneGenerator.startTone(ToneGenerator.TONE_SUP_BUSY, 3000)

                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            toneGenerator.stopTone()
                            toneGenerator.release()
                        } catch (e: Exception) {
                            debugLine(tag, "Exception during tone stop/release: ${e.message}")
                        }

                        debugLine(tag, "Busy tone finished, disconnecting call.")
                        disconnectCall(callId, DisconnectCause(DisconnectCause.REMOTE))
                    }, 3000)

                } catch (e: Exception) {
                    debugLine(tag, "Failed to start busy tone: ${e.message}. Disconnecting immediately.")
                    try {
                        toneGenerator.release()
                    } catch (e2: Exception) { debugLine(tag,"Failed to start busy tone: ${e2.message}" ) }

                    disconnectCall(callId, DisconnectCause(DisconnectCause.REMOTE))
                }
            }
        }
        CallEvent.CLOSE -> {
            debugLine(tag, "Call closed by remote")
            ManagedTelecom.cancelPendingCall(callId, context)
        }
        CallEvent.FAILED -> {
            debugLine(tag, "Call failed by remote")
            ManagedTelecom.cancelPendingCall(callId, context)
        }
        CallEvent.CANCEL -> {
            debugLine(tag, "Call canceled by remote")
            ManagedTelecom.cancelPendingCall(callId, context)
        }
        CallEvent.VIDEO_ON -> {
            debugLine(tag, "Call remotely turned on video")
            ConnectionManager.instance.notifyRemoteVideoState(isPaused = false)
        }
        CallEvent.VIDEO_OFF -> {
            debugLine(tag, "Call remotely turned off video")
            ConnectionManager.instance.notifyRemoteVideoState(isPaused = true)
        }
        CallEvent.HELD -> {
            debugLine(tag, "Call remotely held")
            ManagedTelecom.isRemotelyHeldMutable.value = true
        }
        CallEvent.UNHELD -> {
            debugLine(tag, "Call remotely unheld")
            ManagedTelecom.isRemotelyHeldMutable.value = false
        }
    }
}

fun isCallInProgress(): Boolean {
    // ManagedTelecom is the definitive source of truth for an ongoing call session.
    return ManagedTelecom.currentCall.value != null
}

fun debugLogTelecomSession(session: CallSession?) {
    val tag = "debugLogTelecomSession"

    if (session == null) {
        debugLine(tag, "CallSession is now null")
        return
    }
    debugLine(
        tag, "CallSession updated for callId ${session.callId}: " +
                "isConnected=${session.isConnected}, " +
                "isHeld=${session.isHeld}, " +
                "mediaState=${session.webRTCConnectionState}, " +
                "disconnectCause=${session.disconnectCause}"
    )
}

fun launchCallScreen(peer: Peer, isVideo: Boolean, callId: String, context: Context, terminateOnEnd: Boolean = false) {
    val tag = "launchCallScreen"

    debugLine(tag, "Launching call screen with synthetic back stack for user: ${peer.userId}")

    val appTabIntent = Intent(context, AppTab::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra("callOnly", terminateOnEnd)
    }

    val chatIntent = Intent(context, ChatScreen::class.java).apply {
        putExtra("userId", peer.userId)
        putExtra("name", peer.name)
        putExtra("bio", peer.bio)
        putExtra("picture", peer.picture)
    }

    debugLine(tag, "Launching Video/Audio screen with terminateOnEnd=$terminateOnEnd")

    val callIntent = if (isVideo) {
        Intent(context, VideoCall::class.java)
    } else {
        Intent(context, AudioCall::class.java)
    }.apply {
        putExtra("remoteUserId", peer.userId)
        putExtra("callId", callId)
        putExtra("terminateOnEnd", terminateOnEnd) // Close APP if it was closed before the call
    }

    context.startActivities(arrayOf(appTabIntent, chatIntent, callIntent))
}

suspend fun saveMissedCallMessage(remoteUserId: String) {
    val tag = "launchCallScreen"

    debugLine(tag, "Saving missed call message for remote user: $remoteUserId")
    val context = App.context()
    val messageRepository = getMessageRepository(context)
    val message = Message(
        uid = 0,
        fromUserId = remoteUserId,
        toUserId = MySelf.userId()!!,
        messageId = guid(),
        replyId = null,
        groupId = "",
        groupSize = 0,
        text = context.getString(R.string.missed_call),
        textAttached = null,
        nameAttached = null,
        uri = "",
        type = Type.MISSED_CALL,
        subType = null,
        date = System.currentTimeMillis(),
        status = "",
        reaction = null
    )
    if (messageRepository.saveMessage(message, messageIn = true)) {
        if (!chatScreenIsInForeground(message.fromUserId)) {
            MessageReceivedNotification.show(message)
        }
    } else {
        debugLine(tag, "Failed to save missed call message")
    }
}

fun shutdownRTC(userId: String) {
    val intent = Intent(RTCClient.ACTION_WEBRTC_SHUTDOWN).apply {
        putExtra("userId", userId)
    }
    App.context().sendBroadcast(intent)
}
