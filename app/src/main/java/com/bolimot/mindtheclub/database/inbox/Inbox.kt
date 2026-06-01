package com.bolimot.mindtheclub.database.inbox

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [
    Index(value = ["contentKey", "sequenceNo"], unique = true),
    Index(value = ["messageId"])
])

data class Inbox(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val fromUserId: String,
    val messageId: String,
    val replyId: String?,
    val groupId: String,
    val groupSize: Int,
    val chunkId: String,
    val sequenceNo: Int,
    val totalNo: Int,
    val text: String,
    var textAttached: String?,
    var nameAttached: String?,
    val content: String,
    val type: String,
    val subType: String?,
    val date: Long,
    val sent: Boolean,
    val chatGroupId: String? = null,
    val originalSenderId: String? = null,
    val contentKey: String = "",
)