package com.bolimot.mindtheclub.database.groupMessageStatus

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [Index(value = ["messageId", "memberUserId"], unique = true)]
)
data class GroupMessageStatus(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val messageId: String,
    val memberUserId: String,
    val status: String // "Sent", "Delivered", "Seen"
)
