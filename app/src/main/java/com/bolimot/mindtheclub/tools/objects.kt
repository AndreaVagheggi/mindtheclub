package com.bolimot.mindtheclub.tools

import android.app.Activity
import android.graphics.Bitmap
import androidx.annotation.Keep
import androidx.core.net.toUri
import com.bolimot.mindtheclub.dataModels.ActivityInfo
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class QRCodeData(
    val name: String,
    val userId: String,
    val bio: String,
    val fingerprint: String = ""
)

@Keep
@Serializable
data class QRClubCodeData(
    val name: String,
    val clubId: String,
    val description: String,
)

object Voip {
    const val VOIP_SCHEME = "com.bolimot.mindtheclub.voipScheme"
    const val WEBRTC_CONNECTION_TIMEOUT = 75000L
}

object Broadcast {
    const val ACTION_CALL_FAILED = "com.bolimot.mindtheclub.ACTION_CONNECTION_FAILED"
    const val ACTION_CALL_UNKNOWN_REMOTE_EVENT = "com.bolimot.mindtheclub.ACTION_CALL_UNKNOWN_REMOTE_EVENT"
    const val ACTION_WEBRTC_CONNECTION_OPEN = "com.bolimot.mindtheclub.ACTION_WEBRTC_CONNECTION_OPEN"
    const val ACTION_DATA_CHANNEL_OPEN = "com.bolimot.mindtheclub.JOINED_CHANNEL"
    const val ACTION_START_TYPING = "com.bolimot.mindtheclub.ACTION_CALL_TYPING"
    const val ACTION_STOP_TYPING = "com.bolimot.mindtheclub.ACTION_CALL_TYPING_STOP"
    const val ACTION_CONTENT = "com.bolimot.mindtheclub.ACTION_SIGNAL_CONTENT"
    const val ACTION_FINISH_CALL = "com.bolimot.mindtheclub.ACTION_FINISH_CALL"
}

object Notify {
    const val VIDEO_CALL = "videoCall"
    const val AUDIO_CALL = "audioCall"
    const val DATA_CALL = "dataCall"
    const val PENDING = "pending"
    const val SEND_ME = "sendMe"
    const val ALL_RECEIVED = "allReceived"
    const val SEEN = "Seen"
    const val RECEIVED_SEEN = "ReceivedSeen"
    const val ALL_MISSING = "allMissing"
    const val SOME_MISSING = "someMissing"
    const val COMPLETED = "completed"
    const val TYPING = "typing"
    const val STOP_TYPING = "stopTyping"
    const val JOINED = "joined"
    const val GROUP = "group"
    const val GROUP_PENDING = "groupPending"
    const val GROUP_REMOVED = "groupRemoved"
    const val GROUP_SEEN = "groupSeen"
    const val REQUEST_PROFILE = "requestProfile"
    const val CONNECTION_BUSY = "connectionBusy"
}

const val NO_PICTURE = "//no-picture"

object CallEvent {
    const val ACCEPT = "accept"
    const val CANCEL = "cancel"
    const val REJECT = "reject"
    const val NO_ANSWER = "noAnswer"
    const val BUSY = "busy"
    const val CLOSE = "close"
    const val FAILED = "failed"
    const val HELD = "held"
    const val UNHELD = "unheld"
    const val CONNECTION_OPEN = "connectionOpen"
    const val CONNECTION_FAILED = "connectionFailed"
    const val WEBRTC_SHUTDOWN = "webrtcShutdown"
    const val DATA_CHANNEL_OPEN = "dataChannelOpen"
    const val PEER_JOINED = "peerJoined"
    const val VIDEO_ON = "videoOn"
    const val VIDEO_OFF = "videoOff"
    const val CONNECTION_BUSY = "connectionBusy"
}

object CallEventBus {
    val callControlFlow = MutableSharedFlow<CallControlEvent>(extraBufferCapacity = 1)
}

data class CallControlEvent(
    val action: String,
    val remoteUserId: String,
    val reason: String? = null
)

object SearchKey {
    const val YOUTUBE = "\\\"microformat\\\":{"
    const val TIKTOK = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
    const val INSTAGRAM = "instagram.com"
}

object Share {
    const val CONTENT = "content"
    const val PROFILE = "profile"
}

object Icon {
    const val YOUTUBE = "file:///android_asset/youtube_icon.png"
    const val TIKTOK = "file:///android_asset/tiktok_icon.png"
    const val INSTAGRAM = "file:///android_asset/instagram_icon.png"
}

object Status {
    const val VISIBLE = "visible"
    const val INVISIBLE = "invisible"
    const val HIGHLIGHT = "highlight"
    const val RECEIVING = "Receiving"
}

object AcquisitionStatus {
    const val ACQUIRED = "acquired"
    const val RECEIVED = "received"
    const val SENT = "sent"
}

object Location {
    const val REMOTE = "remote"
    const val LOCAL = "local"
}

object ProfileType {
    const val REMOTE = "remote"
    const val LOCAL = "local"
}

object SubType {
    const val REPLY = "reply"
    const val FORWARD = "forward"
}

object Type {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val MULTIPLE_IMAGES = "multipleImages"
    const val STICKER = "sticker"
    const val GIF = "gif"
    const val PROFILE = "profile"
    const val WEB = "web"
    const val AUDIO = "audio"
    const val FILE = "file"
    const val REACTION = "reaction"
    const val MISSED_CALL = "missedCall"
    const val CONTACT = "contact"
    const val GROUP = "group"
}

object Contact {
    const val NEW = "new"
    const val PENDING = "pending"
    const val CONNECT = "connect"
    const val ACTIVE = "active"
}

object FCM {
    const val SUCCESS = "ok"
    const val FAILURE = "failed"
    const val TOKEN_NOT_FOUND = "not-found"
}

@Serializable
data class YoutubeApiResponse(
    val items: List<VideoItem>? = null
)

@Serializable
data class VideoItem(
    val snippet: Snippet? = null
)

@Serializable
data class Snippet(
    val title: String? = null,
    val description: String? = null,
    val thumbnails: Thumbnails? = null
)

@Serializable
data class Thumbnails(
    @SerialName("default")
    val default: ThumbnailInfo? = null,
    val medium: ThumbnailInfo? = null,
    val high: ThumbnailInfo? = null,
    val standard: ThumbnailInfo? = null,
    val maxres: ThumbnailInfo? = null
)

@Serializable
data class ThumbnailInfo(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

object MySelf {
    const val USER_ID_KEY = "myUserId"
    private const val FCM_TOKEN = "fcmToken"
    const val NAME_KEY = "myName"
    private const val BIO_KEY = "myBio"
    const val PICTURE_KEY = "myPicture"
    const val PICTURE_KEY_MINI = "myPictureMini"
    const val PRIVATE_ID_KEY = "myPrivateId"

    fun userId(): String? = getPreference(USER_ID_KEY, App.context())
    fun privateId(): String? = getPreference(PRIVATE_ID_KEY, App.context())
    fun fcmTokenGet(): String? = getPreference(FCM_TOKEN, App.context())
    fun name(): String? = getPreference(NAME_KEY, App.context())
    fun bio(): String? = getPreference(BIO_KEY, App.context())

    fun fcmTokenSet(token: String){
        setPreference(FCM_TOKEN, token, App.context())
    }

    fun picture(): Bitmap? {
        getPreference(PICTURE_KEY, App.context())?.let {
            try {
                val uri = it.toUri()
                return loadBitmap(uri, App.context())
            } catch (e: Exception) {
                debugLine("MySelf", "picture() failed: ${e.message}")
                return null
            }
        }
        return null
    }

    fun pictureUri(): String? = getPreference(PICTURE_KEY, App.context())
}

object ActivityStatus {
    private var currentActivityInfo: ActivityInfo? = null

    fun activityResumed(activity: Activity, extraData: Map<String, String?> = emptyMap()) {
        currentActivityInfo = ActivityInfo(activity.javaClass.name, extraData)
    }

    fun activityPaused(activity: Activity) {
        if (activity.javaClass.name == currentActivityInfo?.activityName) {
            currentActivityInfo = null
        }
    }

    fun inForeground(activityClassName: String, extraDataKey: String? = null, extraDataValue: String? = null): Boolean {
        return currentActivityInfo?.let {
            it.activityName == activityClassName && (extraDataKey == null || it.extraData[extraDataKey] == extraDataValue)
        } ?: false
    }
}