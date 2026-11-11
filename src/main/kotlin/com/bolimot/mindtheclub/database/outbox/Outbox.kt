package com.bolimot.mindtheclub.database.outbox

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity
@Keep
@Serializable
data class Outbox(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val toUserId: String,
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
)
