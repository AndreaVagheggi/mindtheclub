package com.bolimot.mindtheclub.database.club

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "Club",
    indices = [
        Index(value = ["clubId"], unique = true)
    ]
)

@Keep
@Serializable
data class Club(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val clubId: String,
    val owner: Boolean
)