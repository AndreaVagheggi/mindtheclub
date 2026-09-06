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
    // Ack for GROUP_SEEN, sent back by the original sender so the member can stop retrying (see
    // GroupSeenTracker). Older versions ignore unknown FCM types, so it is safe in a mixed
    // fleet.
    const val GROUP_SEEN_ACK = "groupSeenAck"
    // Group video calls. The invitation carries the call key in its extra data, safe because
    // every instant FCM payload is already sealed to the recipient's identity key before it
    // leaves the phone.
    const val GROUP_CALL = "groupCall"
    const val GROUP_CALL_REKEY = "groupCallRekey"
    const val GROUP_CALL_DECLINE = "groupCallDecline"
    const val GROUP_CALL_END = "groupCallEnd"

    const val REQUEST_PROFILE = "requestProfile"
    const val CONNECTION_BUSY = "connectionBusy"
    const val CONTACT_REQUEST = "contactRequest"
    const val CANCEL_TRANSFER = "cancelTransfer"

    // Liveness probe used before choosing who to send a heavy group message to. Both travel on
    // the instant path, so they carry the 15 second TTL: an answer arriving two minutes later is
    // not an answer, the sender has moved on. See PeerProbe for why the flag in the delivery
    // document is not enough.
    const val PING = "ping"
    const val PONG = "pong"
}

const val NO_PICTURE = "//no-picture"

/**
 * Whether this build proves its identity to Firebase with App Check.
 *
 * Off since 22 Aug. It was protecting an app nobody has installed yet, and the price was paid on
 * every single outgoing signal: the token is fetched before the request is even attempted, and a
 * Play Integrity attestation is a network round trip that fails exactly when the network is
 * already struggling. On 21 Aug one phone lost 448 outgoing FCMs out of 448 and went completely
 * mute for two hours, unable even to tell anyone it had something pending, while Firestore
 * answered its reads with PERMISSION_DENIED for the same reason. The money is guarded by the
 * daily budget brakes in the Cloudflare workers, che sono quelli che davvero fermano un abuso.
 *
 * Turning it back on means flipping this AND `enforceAppCheck` in the four cloud functions, plus
 * the console. One without the other only breaks things: enforcing without a client token
 * refuses every call, and sending a token nobody verifies protects nothing.
 */
const val APP_CHECK_ENABLED = false

// Size cap for messages sent to a group, checked in the Send* screens and applied AFTER the
// video transcoding pass (see VideoCompressor), so it judges what actually goes on the wire and
// not what came out of the camera. The user facing strings said 250 MB from an old stress test;
// they now match this number.
const val MAX_GROUP_MESSAGE_BYTES = 52428800L

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

    // Mid call switch from audio to video. The peer is asked first, perche' accepting turns
    // their camera on.
    const val VIDEO_UPGRADE_REQUEST = "videoUpgradeRequest"
    const val VIDEO_UPGRADE_ACCEPT = "videoUpgradeAccept"
    const val VIDEO_UPGRADE_REJECT = "videoUpgradeReject"
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