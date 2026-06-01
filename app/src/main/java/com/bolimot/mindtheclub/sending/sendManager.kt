package com.bolimot.mindtheclub.sending

import android.content.Context
import android.widget.EditText
import androidx.core.content.ContextCompat.getString
import androidx.lifecycle.LifecycleCoroutineScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.video.Video
import com.bolimot.mindtheclub.functions.buildMultiImagePreview
import com.bolimot.mindtheclub.functions.fetchWebsiteInfo
import com.bolimot.mindtheclub.functions.getImageRepository
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getVideoRepository
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.saveBitmap
import com.bolimot.mindtheclub.functions.splitToList
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.SoundManager
import com.bolimot.mindtheclub.tools.SubType
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.viewModel.MessageViewModel
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun sendReaction(message: Message, emoji: String) {

    val isGroup = message.chatGroupId != null

    val msg = Message(
        uid = 0,
        fromUserId = MySelf.userId()!!,
        toUserId = if (isGroup) message.chatGroupId!! else message.fromUserId,
        messageId = message.messageId,
        replyId = "",
        groupId = "",
        groupSize = 0,
        text = emoji,
        textAttached = "",
        nameAttached = "",
        uri = "",
        type = Type.REACTION,
        subType = "",
        date = System.currentTimeMillis(),
        status = getString(App.context(), R.string.sending),
        chatGroupId = message.chatGroupId,
        originalSenderId = if (isGroup) MySelf.userId() else null
    )

    sendMessage(MessageData.fromMessage(msg))
}

fun sendMultipleImage(
    toUserIdList: List<String>,
    uriCSVString: String,
    caption: String,
    textAttached: String?,
    nameAttached: String?,
    lifecycleScope: CoroutineScope,
    viewModel: MessageViewModel,
    groupId: String = "",
    groupSize: Int = 0) {

    for(toUserId in toUserIdList) {

        val message = Message(
            uid = 0,
            fromUserId = MySelf.userId()!!,
            toUserId = toUserId,
            messageId = guid(),
            replyId = "",
            groupId = groupId,
            groupSize = groupSize,
            text = caption,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = uriCSVString,
            type = Type.MULTIPLE_IMAGES,
            subType = if(!textAttached.isNullOrEmpty()) SubType.FORWARD else "",
            date = System.currentTimeMillis(),
            status = getString(App.context(), R.string.sending),
            chatGroupId = if (toUserId.startsWith("group")) toUserId else null,
            originalSenderId = if (toUserId.startsWith("group")) MySelf.userId() else null,
        )

        lifecycleScope.launch {
            if (viewModel.insert(message)) {
                SoundManager.playOutgoing()
                CoroutineScope(Dispatchers.IO).launch {
                    addObjectForBrowsing(message)
                    sendMessage(MessageData.fromMessage(message))
                }
            }
        }
    }
}

suspend fun sendWeb(toUserIdList: List<String>, link: String){
    var imageUrl: String
    var webTitle: String
    var webDescription: String

    for(userId in toUserIdList) {

        imageUrl = ""
        webTitle = ""
        webDescription = ""

        fetchWebsiteInfo(link, App.context()).let {
            imageUrl = it.imageUrl
            webTitle = it.title
            webDescription = it.description
        }

        val message = Message(
            uid = 0,
            fromUserId = MySelf.userId()!!,
            toUserId = userId,
            messageId = guid(),
            replyId = imageUrl,
            groupId = "",
            groupSize = 0,
            text = link,
            textAttached = webDescription,
            nameAttached = webTitle,
            uri = "",
            type = Type.WEB,
            subType = "",
            date = System.currentTimeMillis(),
            getString(App.context(), R.string.sending),
            chatGroupId = if (userId.startsWith("group")) userId else null,
            originalSenderId = if (userId.startsWith("group")) MySelf.userId() else null,
        )

        val repo = getMessageRepository(App.context())
        if(repo.saveMessage(message, false)){
            SoundManager.playOutgoing()
            sendMessage(MessageData.fromMessage(message))
        }
    }
}

fun sendProfile(message: Message, lifecycleScope: CoroutineScope) {
    lifecycleScope.launch {
        val repo = getMessageRepository(App.context())
        if (repo.saveMessage(message, false)) {
            sendMessage(MessageData.fromMessage(message))
        }
    }
}

fun sendObject(
    toUserIdList: List<String>,
    filePath: String,
    caption: String,
    textAttached: String?,
    nameAttached: String?,
    lifecycleScope: CoroutineScope,
    viewModel: MessageViewModel?,
    objectType: String,
    replyId: String = "",
    groupId: String = "",
    groupSize: Int = 0) {


    for(toUserId in toUserIdList) {

        val message = Message(
            uid = 0,
            fromUserId = MySelf.userId()!!,
            toUserId = toUserId,
            messageId = guid(),
            replyId = replyId,
            groupId = groupId,
            groupSize = groupSize,
            text = caption,
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = filePath,
            type = objectType,
            subType = if(!textAttached.isNullOrEmpty()) SubType.FORWARD else "",
            date = System.currentTimeMillis(),
            getString(App.context(), R.string.sending),
            chatGroupId = if (toUserId.startsWith("group")) toUserId else null,
            originalSenderId = if (toUserId.startsWith("group")) MySelf.userId() else null,
        )

        lifecycleScope.launch {
            if(viewModel != null) {
                if (viewModel.insert(message)) {
                    SoundManager.playOutgoing()
                    sendMessage(MessageData.fromMessage(message))
                    addObjectForBrowsing(message)
                }
            } else {
                val repo = getMessageRepository(App.context())
                if (repo.saveMessage(message, false)) {
                    SoundManager.playOutgoing()
                    sendMessage(MessageData.fromMessage(message))
                    addObjectForBrowsing(message)
                }
            }
        }
    }
}

fun sendText(context: Context,
             userId: String,
             editText: EditText,
             textAttached: String?,
             nameAttached: String?,
             subType: String?,
             lifecycleScope: LifecycleCoroutineScope,
             viewModel: MessageViewModel,
             replyId: String = "",
             type: String = Type.TEXT) {

    if(editText.text.isNotEmpty()){
        val textContent = editText.text.toString()
        editText.text.clear()

        lifecycleScope.launch {
            val processedMessage = kotlinx.coroutines.withContext(Dispatchers.IO) {

                val message = Message(
                    uid = 0,
                    fromUserId = MySelf.userId() ?: "",
                    toUserId = userId,
                    messageId = guid(),
                    replyId = replyId,
                    groupId = "",
                    groupSize = 0,
                    text = textContent,
                    textAttached = textAttached,
                    nameAttached = nameAttached,
                    uri = "",
                    type = type,
                    subType = subType,
                    date = System.currentTimeMillis(),
                    status = context.getString(R.string.sending),
                    chatGroupId = if (userId.startsWith("group")) userId else null,
                    originalSenderId = if (userId.startsWith("group")) MySelf.userId() else null,
                )

                if (message.subType == SubType.REPLY && !message.replyId.isNullOrBlank()) {
                    val replyType = getReplyType(message.replyId, context)
                    var replyUri = getReplyImage(message.replyId, replyType ?: "", context)

                    if (replyUri != null) {
                        if (replyType == Type.MULTIPLE_IMAGES) {
                            replyUri = buildMultiImagePreview(replyUri).toString()
                        } else if (replyType == Type.VIDEO) {
                            replyUri = saveFirstVideoFrame(replyUri, context)
                        }
                    }

                    val shouldAttachPreview = replyType != null &&
                            (replyType == Type.IMAGE ||
                                    replyType == Type.VIDEO ||
                                    replyType == Type.GIF ||
                                    replyType == Type.STICKER ||
                                    replyType == Type.MULTIPLE_IMAGES ||
                                    replyType == Type.CONTACT)

                    message.uri = if (shouldAttachPreview) replyUri ?: "" else ""
                }
                message
            }

            if(viewModel.insert(processedMessage)){
                SoundManager.playOutgoing()
                sendMessage(MessageData.fromMessage(processedMessage))
            }
        }
    }
}

suspend fun saveFirstVideoFrame(uri: String?, context: Context): String? {
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val bitmap = Glide.with(context)
                .asBitmap()
                .load(uri)
                .frame(1000000)
                .submit()
                .get()

            saveBitmap(bitmap, guid(), 50, false).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

private suspend fun getReplyType(replyId: String, context: Context): String? {
    return getMessageRepository(context).getReplyType(replyId)
}

private suspend fun getReplyImage(replyId: String, type: String, context: Context): String? {
    return getMessageRepository(context).getReplyImage(replyId, type)
}

fun forwardAudio(userId: String, originId: String?, uri: String, lifecycleScope: LifecycleCoroutineScope,  messageViewModel: MessageViewModel ) {
    lifecycleScope.launch {
        val message = Message(
            uid = 0,
            fromUserId = MySelf.userId()!!,
            toUserId = userId,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = "",
            textAttached = "",
            nameAttached = originId,
            uri = uri,
            type = Type.AUDIO,
            subType = SubType.FORWARD,
            date = System.currentTimeMillis(),
            status = getString(App.context(), R.string.sending),
            chatGroupId = if (userId.startsWith("group")) userId else null,
            originalSenderId = if (userId.startsWith("group")) MySelf.userId() else null,
        )

        if(messageViewModel.insert(message)){
            sendMessage(MessageData.fromMessage(message))
        }
    }
}

fun forwardText(
    uid: String,
    textAttached: String?,
    nameAttached: String?,
    lifecycleScope: LifecycleCoroutineScope,
    messageViewModel: MessageViewModel
) {

    lifecycleScope.launch {

        val message = Message(
            uid = 0,
            fromUserId = MySelf.userId()!!,
            toUserId = uid,
            messageId = guid(),
            replyId = "",
            groupId = "",
            groupSize = 0,
            text = "",
            textAttached = textAttached,
            nameAttached = nameAttached,
            uri = "",
            type = Type.TEXT,
            subType = SubType.FORWARD,
            date = System.currentTimeMillis(),
            status = getString(App.context(), R.string.sending),
            chatGroupId = if (uid.startsWith("group")) uid else null,
            originalSenderId = if (uid.startsWith("group")) MySelf.userId() else null,
        )

        if(messageViewModel.insert(message)){
            sendMessage(MessageData.fromMessage(message))
        }
    }
}

fun forwardWeb(
    uid: String,
    imageUrl: String,
    webTitle: String,
    webDescription: String,
    webLink: String,
    lifecycleScope: LifecycleCoroutineScope,
    messageViewModel: MessageViewModel
) {

    lifecycleScope.launch {
        val message = Message(
            uid = 0,
            fromUserId = MySelf.userId()!!,
            toUserId = uid,
            messageId = guid(),
            replyId = imageUrl,
            groupId = "",
            groupSize = 0,
            text = webLink,
            textAttached = webDescription,
            nameAttached = webTitle,
            uri = "",
            type = Type.WEB,
            subType = SubType.FORWARD,
            date = System.currentTimeMillis(),
            status = getString(App.context(), R.string.sending),
            chatGroupId = if (uid.startsWith("group")) uid else null,
            originalSenderId = if (uid.startsWith("group")) MySelf.userId() else null,
        )

        if(messageViewModel.insert(message)){
            sendMessage(MessageData.fromMessage(message))
        }
    }
}

suspend fun addObjectForBrowsing(message: Message) {
    when(message.type) {
        Type.IMAGE -> {
            getImageRepository(App.context()).insertImage(
                Image(0, message.messageId, message.uri, message.date, "", message.toUserId)
            )
        }
        Type.MULTIPLE_IMAGES ->{
            val imageRepository = getImageRepository(App.context())
            val uriList = splitToList(message.uri)

            uriList.forEach { uri ->
                imageRepository.insertImage(
                    Image(0, message.messageId, uri, message.date, "", message.toUserId)
                )
            }
        }
        Type.VIDEO -> {
            getVideoRepository(App.context()).insertImage(
                Video(0, message.messageId, message.uri, message.date, "", message.toUserId)
            )
        }
    }
}
