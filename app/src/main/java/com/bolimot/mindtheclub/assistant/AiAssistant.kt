package com.bolimot.mindtheclub.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.notifications.MessageReceivedNotification
import com.bolimot.mindtheclub.receiving.chatScreenIsInForeground
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.APP_CHECK_ENABLED
import com.bolimot.mindtheclub.tools.Broadcast
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.NO_PICTURE
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.SoundManager
import com.bolimot.mindtheclub.tools.Type
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * The AI Assistant is a local pseudo peer backed by the mtc-ai Cloudflare Worker. Messages
 * addressed to [USER_ID] never enter the P2P pipeline: they are POSTed to the Worker (App Check
 * protected) and the model reply is inserted as a normal incoming message, so the rest of the app
 * treats this like any other conversation.
 */
object AiAssistant {

    const val USER_ID = "mtc-ai-assistant"
    const val DISCLOSURE_ACCEPTED_KEY = "aiAssistantDisclosureAccepted"
    const val SHOW_CLUBBY_KEY = "showClubbyOption"

    private const val WORKER_URL = "https://mtc-ai.long-sun-7368.workers.dev"
    private const val AVATAR_FILE_NAME = "mtcAssistantAvatar"
    private const val HISTORY_LIMIT = 12
    private const val TAG = "AiAssistant"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun isAssistant(userId: String?): Boolean = userId == USER_ID

    /** "Show Clubby" option (OptionsActivity), on by default. Hides the pinned row, forward
     *  selection and search entries; the chat data stays intact. */
    fun isVisible(context: Context): Boolean =
        getPreference(SHOW_CLUBBY_KEY, context) != "false"

    /**
     * Creates the Assistant peer and its welcome message once, on first launch. If the user
     * blocked the peer it is not re-created: getPeer() returns blocked peers too, so the
     * existence check keeps their choice.
     */
    suspend fun ensureSeeded(context: Context) {
        try {
            val myUserId = MySelf.userId() ?: return
            val peerDao = getPeerDao(context)
            val name = context.getString(R.string.assistant_name)

            val existing = peerDao.getPeer(USER_ID)
            if (existing != null) {
                // Identity rebrand (name/avatar changed in an app update): refresh in place.
                if (existing.name != name) {
                    peerDao.updatePeerProfile(
                        USER_ID, name,
                        context.getString(R.string.assistant_bio),
                        savedAvatarUri(context)
                    )
                }
                return
            }

            val peer = Peer(
                uid = 0,
                userId = USER_ID,
                token = "",
                name = name,
                bio = context.getString(R.string.assistant_bio),
                picture = savedAvatarUri(context),
                status = Contact.ACTIVE,
                privateId = ""
            )

            if (!getPeerViewModel().addNewPeer(peer)) {
                debugLine(TAG, "Failed to insert assistant peer")
                return
            }

            val welcome = Message(
                uid = 0,
                fromUserId = USER_ID,
                toUserId = myUserId,
                messageId = guid(),
                replyId = "",
                groupId = "",
                groupSize = 0,
                text = context.getString(R.string.assistant_welcome),
                textAttached = "",
                nameAttached = "",
                uri = "",
                type = Type.TEXT,
                subType = "",
                date = System.currentTimeMillis(),
                status = ""
            )
            getMessageRepository(context).saveMessage(welcome, messageIn = true)
            debugLine(TAG, "Assistant peer seeded")
        } catch (e: Exception) {
            debugLine(TAG, "ensureSeeded failed: ${e.message}")
        }
    }

    /**
     * Replaces the whole outbound P2P path for messages addressed to the Assistant. Called from
     * sendMessage(), where the outgoing message is already stored and visible.
     */
    fun handleOutgoing(data: MessageData) {
        CoroutineScope(Dispatchers.IO).launch {
            val context = App.context()
            val repo = getMessageRepository(context)

            try {
                // System traffic (profile updates, reactions) must not trigger a reply.
                if (data.type == Type.PROFILE || data.type == Type.REACTION) {
                    return@launch
                }

                repo.updateStatus(data.messageId, Notify.SEEN)

                // Forwarded texts carry their content in textAttached, not text.
                val userText = data.text?.takeIf { it.isNotBlank() } ?: data.textAttached
                if (data.type != Type.TEXT || userText.isNullOrBlank()) {
                    insertReply(context.getString(R.string.assistant_text_only))
                    return@launch
                }

                setTypingIndicator(true)
                val reply = try {
                    requestReply(context)
                } finally {
                    setTypingIndicator(false)
                }
                insertReply(reply)
            } catch (e: Exception) {
                debugLine(TAG, "handleOutgoing failed: ${e.message}")
                setTypingIndicator(false)
                insertReply(context.getString(R.string.assistant_error_generic))
            }
        }
    }

    private suspend fun requestReply(context: Context): String {
        val myUserId = MySelf.userId() ?: return context.getString(R.string.assistant_error_generic)

        // With App Check off no provider is installed, so asking for a token here would throw on
        // every request and the assistant would answer "network error" for ever. Unverified on
        // the server side: unlike mtc-ice and mtc-signal, whose sources were read and confirmed
        // to ignore the token, the mtc-ai worker is not on this machine. Se quello lo controlla,
        // this is the feature that stops working.
        val token = if (!APP_CHECK_ENABLED) null else {
            try {
                FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
            } catch (e: Exception) {
                debugLine(TAG, "App Check token failed: ${e.message}")
                return context.getString(R.string.assistant_error_network)
            }
        }

        val history = getMessageDao(context)
            .getRecentMessages(myUserId, USER_ID, HISTORY_LIMIT)
            .reversed()

        val messages = JSONArray()
        for (msg in history) {
            if (msg.type != Type.TEXT) continue
            val content = msg.text.ifBlank { msg.textAttached ?: "" }
            if (content.isBlank()) continue
            messages.put(JSONObject().apply {
                put("role", if (msg.fromUserId == USER_ID) "assistant" else "user")
                put("content", content)
            })
        }

        val payload = JSONObject().apply {
            put("userId", myUserId)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(WORKER_URL)
            .apply { if (token != null) header("X-Firebase-AppCheck", token) }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful -> {
                        val reply = JSONObject(bodyStr).optString("reply", "")
                        reply.ifBlank { context.getString(R.string.assistant_error_generic) }
                    }
                    resp.code == 429 -> {
                        val error = try { JSONObject(bodyStr).optString("error", "") } catch (_: Exception) { "" }
                        if (error == "cap_global") {
                            context.getString(R.string.assistant_limit_global)
                        } else {
                            context.getString(R.string.assistant_limit_user)
                        }
                    }
                    else -> {
                        debugLine(TAG, "Worker HTTP ${resp.code}: $bodyStr")
                        context.getString(R.string.assistant_error_generic)
                    }
                }
            }
        } catch (e: Exception) {
            debugLine(TAG, "Worker call failed: ${e.message}")
            context.getString(R.string.assistant_error_network)
        }
    }

    private suspend fun insertReply(text: String) {
        val context = App.context()
        val myUserId = MySelf.userId() ?: return

        val message = Message(
            uid = 0,
            fromUserId = USER_ID,
            toUserId = myUserId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = text,
            textAttached = "",
            nameAttached = "",
            uri = "",
            type = Type.TEXT,
            subType = "",
            date = System.currentTimeMillis(),
            status = ""
        )

        if (getMessageRepository(context).saveMessage(message, messageIn = true)) {
            if (!chatScreenIsInForeground(USER_ID)) {
                MessageReceivedNotification.show(message)
            } else {
                SoundManager.playIncoming()
            }
        }
    }

    private fun setTypingIndicator(typing: Boolean) {
        val action = if (typing) Broadcast.ACTION_START_TYPING else Broadcast.ACTION_STOP_TYPING
        val intent = Intent(action).putExtra("userId", USER_ID)
        LocalBroadcastManager.getInstance(App.context()).sendBroadcast(intent)
    }

    /**
     * Copies the bundled avatar into filesDir so Peer.picture behaves exactly like any received
     * profile picture (loadBitmap needs a real file:// path). Saved as PNG: clubby.png has
     * transparency, which JPEG would turn black.
     */
    private fun savedAvatarUri(context: Context): String {
        return try {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.clubby)
            val file = File(context.filesDir, AVATAR_FILE_NAME)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            debugLine(TAG, "Avatar copy failed: ${e.message}")
            NO_PICTURE
        }
    }
}
