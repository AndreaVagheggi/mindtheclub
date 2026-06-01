package com.bolimot.mindtheclub.dataModels

import androidx.annotation.Keep
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.functions.mergeImages
import com.bolimot.mindtheclub.tools.Type
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class MessageData(
    val fromUserId: String,
    var toUserId: String,
    var messageId: String,
    val replyId: String?,
    val groupId: String,
    val groupSize: Int,
    var text: String?,
    var textAttached: String?,
    var nameAttached: String?,
    var uri: String?,
    val type: String,
    val subType: String?,
    val date: Long,
    val chatGroupId: String? = null,
    val originalSenderId: String? = null,
) {
    companion object {
        fun fromMessage(message: Message): MessageData {
            if(message.type != Type.MULTIPLE_IMAGES) {
                return MessageData(
                    fromUserId = message.fromUserId,
                    toUserId = message.toUserId,
                    messageId = message.messageId,
                    replyId = message.replyId,
                    groupId = message.groupId,
                    groupSize = message.groupSize,
                    text = message.text,
                    textAttached = message.textAttached,
                    nameAttached = message.nameAttached,
                    uri = message.uri,
                    type = message.type,
                    subType = message.subType,
                    date = message.date,
                    chatGroupId = message.chatGroupId,
                    originalSenderId = message.originalSenderId
                )
            } else {
                return MessageData(
                    fromUserId = message.fromUserId,
                    toUserId = message.toUserId,
                    messageId = message.messageId,
                    replyId = message.replyId,
                    groupId = message.messageId,
                    groupSize = message.groupSize,
                    text = message.text,
                    textAttached = message.textAttached,
                    nameAttached = message.nameAttached,
                    uri = mergeImages(message.uri, message.messageId),
                    type = Type.MULTIPLE_IMAGES,
                    subType = message.subType,
                    date = message.date,
                    chatGroupId = message.chatGroupId,
                    originalSenderId = message.originalSenderId
                )
            }
        }
    }
}