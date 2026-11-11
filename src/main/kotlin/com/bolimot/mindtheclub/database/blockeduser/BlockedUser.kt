package com.bolimot.mindtheclub.database.blockeduser

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Entity(
    tableName = "BlockedUser",
    indices = [Index(value = ["userId"], unique = true)]
)
@Keep
@Serializable
data class BlockedUser(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val userId: String,
    val name: String
)
