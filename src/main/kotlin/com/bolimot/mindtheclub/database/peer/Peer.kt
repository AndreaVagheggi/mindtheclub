package com.bolimot.mindtheclub.database.peer

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "Peer",
    indices = [
        Index(value = ["userId"], unique = true),
        Index(value = ["privateId"], unique = false)
    ]
)

@Keep
@Serializable
data class Peer(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    val userId: String,
    val token: String,
    val name: String,
    val bio: String?,
    var picture: String?,
    var status: String,
    var privateId: String,
    val lastMessageAt: Long = 0,
    var publicKey: String? = null,
    var bluetoothMac: String? = null
)