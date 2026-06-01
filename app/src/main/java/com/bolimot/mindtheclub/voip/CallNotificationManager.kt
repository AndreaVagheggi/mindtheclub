package com.bolimot.mindtheclub.voip

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.edit
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerRepository
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.views.IncomingCall

class CallNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel_v2"
        private const val CALL_NOTIFICATION_ID = 110
        private const val CALL_CHANNEL_VERSION = 17
    }

    @SuppressLint("FullScreenIntentPolicy")
    suspend fun showIncomingCallNotification(callId: String, remoteUserId: String, terminateOnEnd: Boolean = false, isVideo: Boolean = false) {
        val tag = "showIncomingCallNotification"

        val peerRepository = getPeerRepository(context)
        val remotePeer = peerRepository.getPeer(remoteUserId)

        val remoteDisplayName = remotePeer?.name ?: "Unknown User"
        val remotePicture = remotePeer?.picture

        createIncomingCallChannel()

        debugLine(tag, "Starting notification with terminateOnEnd=$terminateOnEnd")

        val fullScreenIntent = Intent(context, IncomingCall::class.java).apply {
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra("EXTRA_DISPLAY_NAME", remoteDisplayName)
            putExtra("EXTRA_TERMINATE_ON_END", terminateOnEnd)
            putExtra("EXTRA_REMOTE_PICTURE", remotePicture)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 1, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = Intent(context, AnswerCallActivity::class.java).apply {
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra(CallActionReceiver.EXTRA_TERMINATE_ON_END, terminateOnEnd)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context, 2, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context, 3, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var callerIcon: IconCompat? = null
        if (remotePicture != null) {
            val callerBitmap = loadBitmap(remotePicture.toUri(), context)
            if (callerBitmap != null) {
                callerIcon = IconCompat.createWithBitmap(callerBitmap)
            }
        }

        val caller = Person.Builder()
            .setName(remoteDisplayName)
            .setImportant(true)
            .setIcon(callerIcon)
            .build()

        val resIdMessage = if(isVideo) R.string.incoming_video_call else R.string.incoming_audio_call

        val notification = NotificationCompat.Builder(context, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.mtc_logo_small_icon)
            .setContentTitle(context.getString(resIdMessage))
            .setContentText(context.getString(resIdMessage))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(caller, declinePendingIntent, answerPendingIntent)
            )
            .build()

        notification.flags = notification.flags or NotificationCompat.FLAG_INSISTENT
        notificationManager.notify(CALL_NOTIFICATION_ID, notification)
    }

    fun dismissCallNotification() {
        notificationManager.cancel(CALL_NOTIFICATION_ID)
    }


    private fun createIncomingCallChannel() {
        val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt("call_channel_version", 0)

        if (lastVersion < CALL_CHANNEL_VERSION) {
            try {
                notificationManager.getNotificationChannel(INCOMING_CALL_CHANNEL_ID)?.let {
                    notificationManager.deleteNotificationChannel(INCOMING_CALL_CHANNEL_ID)
                }
            } catch (_: Exception) { }

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for incoming call notifications"
                setSound(ringtoneUri, audioAttributes)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
            if (notificationManager.getNotificationChannel(INCOMING_CALL_CHANNEL_ID) != null) {
                prefs.edit { putInt("call_channel_version", CALL_CHANNEL_VERSION) }
            }
        }
    }
}