package com.bolimot.mindtheclub.database.reaction

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Entity(tableName = "Reaction", indices = [Index(value = ["messageId"], unique = true)])
@Keep
@Serializable
data class Reaction(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val messageId: String,
    val emoji: String,
)