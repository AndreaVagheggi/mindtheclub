package com.bolimot.mindtheclub.database.image

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Image")
data class Image(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val messageId: String,
    val url: String,
    val date: Long,
    val status: String,
    val userId: String
)