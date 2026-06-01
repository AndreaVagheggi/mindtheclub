package com.bolimot.mindtheclub.database.video

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Video")
data class Video(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val messageId: String,
    val url: String,
    val date: Long,
    val status: String,
    val userId: String
)