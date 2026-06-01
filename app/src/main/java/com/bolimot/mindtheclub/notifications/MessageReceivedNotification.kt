package com.bolimot.mindtheclub.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.edit
import androidx.core.net.toUri
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.ChatScreen
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.functions.makeItRound
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.views.AppTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bolimot.mindtheclub.tools.MessageNotifier
import com.bolimot.mindtheclub.tools.NO_PICTURE

class MessageReceivedNotification {
    companion object {

        private const val PREFS_UNREAD = "unread_msg_counts"
        private const val PREFS_NOTIF_IDS = "active_notification_ids"
        private const val PREFS_SHOWN_MSG = "shown_message_ids"

        fun show(message: Message) {
            val context = App.context()
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // We increment the version and append it to the ID to enforce a brand-new channel creation
            val channelVersion = 8
            val channelId = "message_received_v$channelVersion"

            // ── messageId dedup: skip if already shown ──
            val shownPrefs = context.getSharedPreferences(PREFS_SHOWN_MSG, Context.MODE_PRIVATE)
            if (shownPrefs.contains(message.messageId)) return
            shownPrefs.edit { putBoolean(message.messageId, true) }

            val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
            val lastVersion = prefs.getInt("message_channel_version", 0)

            if (lastVersion < channelVersion) {
                // Safely delete the previous channel so it doesn't clutter the user's OS Notification Settings
                val oldChannelId = if (lastVersion > 0) "message_received_v$lastVersion" else "message_received"
                notificationManager.deleteNotificationChannel(oldChannelId)
                notificationManager.deleteNotificationChannel("message_received") // Fallback cleanup

                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()

                val soundUri =
                    ("android.resource://" + context.packageName + "/" + R.raw.notification_sound).toUri()

                val channel = NotificationChannel(
                    channelId, "Message Channel", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Channel for messages"
                    setSound(soundUri, audioAttributes)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
                if (notificationManager.getNotificationChannel(channelId) != null) {
                    prefs.edit { putInt("message_channel_version", channelVersion) }
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                val peer = getPeerDao(context).getPeer(message.fromUserId)
                val name = peer?.name
                val bio = peer?.bio
                val picture = peer?.picture?.takeIf { it != NO_PICTURE }
                val uriPeerProfilePic = picture?.toUri()
                val peerProfilePic =
                    uriPeerProfilePic?.let { makeItRound(loadBitmap(it, context)) }

                val unreadPrefs =
                    context.getSharedPreferences(PREFS_UNREAD, Context.MODE_PRIVATE)
                val notifIdPrefs =
                    context.getSharedPreferences(PREFS_NOTIF_IDS, Context.MODE_PRIVATE)

                val newCount: Int
                if (message.type == Type.GROUP) {
                    newCount = 0
                } else {
                    val existingCount = unreadPrefs.getInt(message.fromUserId, 0)
                    newCount = existingCount + 1
                    unreadPrefs.edit { putInt(message.fromUserId, newCount) }
                }

                MessageNotifier.notifyFromUserId(message.fromUserId, message.messageId)
                var pendingIntent: PendingIntent?

                withContext(Dispatchers.Main) {
                    val backStackIntent = Intent(context, AppTab::class.java)

                    val chatIntent = Intent(context, ChatScreen::class.java).apply {
                        putExtra("userId", message.fromUserId)
                        putExtra("name", name)
                        putExtra("bio", bio)
                        putExtra("picture", picture)
                    }

                    pendingIntent = if (message.type != Type.GROUP) {
                        TaskStackBuilder.create(context)
                            .addNextIntentWithParentStack(backStackIntent)
                            .addNextIntent(chatIntent)
                            .getPendingIntent(
                                message.fromUserId.hashCode(),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                    } else {
                        TaskStackBuilder.create(context)
                            .addNextIntentWithParentStack(backStackIntent)
                            .getPendingIntent(
                                message.fromUserId.hashCode(),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                    }

                    val text = when (message.type) {
                        Type.TEXT, Type.MISSED_CALL -> message.text
                        Type.IMAGE -> context.getString(R.string.image)
                        Type.MULTIPLE_IMAGES -> context.getString(R.string.images)
                        Type.VIDEO -> context.getString(R.string.video)
                        Type.GIF -> context.getString(R.string.gif)
                        Type.STICKER -> context.getString(R.string.sticker)
                        Type.WEB -> context.getString(R.string.link)
                        Type.FILE -> context.getString(R.string.file)
                        Type.AUDIO -> context.getString(R.string.audio)
                        Type.PROFILE -> context.getString(R.string.new_contact)
                        Type.GROUP -> context.getString(R.string.new_group)
                        else -> ""
                    }

                    val builder = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.mtc_logo_small_icon)
                        .setContentTitle(name ?: context.getString(R.string.new_message))
                        .setContentText(text)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setNumber(newCount)


                    builder.setContentIntent(pendingIntent)

                    peerProfilePic?.let {
                        builder.setLargeIcon(it)
                    } ?: run {
                        val fallbackBitmap =
                            BitmapFactory.decodeResource(context.resources, R.drawable.peer)
                        builder.setLargeIcon(fallbackBitmap)
                    }

                    notificationManager.cancel("sync_${message.fromUserId}".hashCode())

                    val notificationId = message.fromUserId.hashCode()
                    notifIdPrefs.edit { putInt(message.fromUserId, notificationId) }
                    notificationManager.notify(notificationId, builder.build())
                }
            }
        }

        fun cancel(userId: String) {
            val context = App.context()
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // ── clear unread count ──
            val unreadPrefs = context.getSharedPreferences(PREFS_UNREAD, Context.MODE_PRIVATE)
            unreadPrefs.edit { remove(userId) }

            // ── clear shown messageIds for this user (prefix-based cleanup is optional,
            //    but the dedup prefs are small booleans — fine to leave) ──

            // ── cancel the system notification using persisted ID ──
            val notifIdPrefs = context.getSharedPreferences(PREFS_NOTIF_IDS, Context.MODE_PRIVATE)
            val notifId = notifIdPrefs.getInt(userId, 0)
            if (notifId != 0) {
                notificationManager.cancel(notifId)
            }
            notifIdPrefs.edit { remove(userId) }

            MessageNotifier.notifyFromUserId(userId, "")
        }

        fun getUnreadCount(context: Context, userId: String): Int {
            return context.getSharedPreferences(PREFS_UNREAD, Context.MODE_PRIVATE)
                .getInt(userId, 0)
        }
    }
}