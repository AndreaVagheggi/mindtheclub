package com.bolimot.mindtheclub.database.message

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "Message", indices = [Index(value = ["messageId"], unique = true)])
@Keep
@Serializable
data class Message(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val fromUserId: String,
    val toUserId: String,
    val messageId: String,
    val replyId: String?,
    val groupId: String,
    val groupSize: Int,
    var text: String,
    var textAttached: String?,
    var nameAttached: String?,
    var uri: String,
    var type: String,
    val subType: String?,
    var date: Long,
    var status: String,
    var reaction: String? = null,
    val chatGroupId: String? = null,
    val originalSenderId: String? = null,
)