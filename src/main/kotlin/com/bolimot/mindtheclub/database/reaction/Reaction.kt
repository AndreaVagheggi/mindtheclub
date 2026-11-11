package com.bolimot.mindtheclub.database.reaction

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * One member's reaction to one message.
 *
 * The pair (messageId, reactorUserId) is unique: a member holds at most one emoji per message, and
 * picking a different one replaces only their own row, mai quella di un altro. [date] is the
 * moment the reactor tapped, and it settles ordering when the same reaction is gossiped back
 * around the group out of order.
 *
 * [reactorUserId] and [date] carry defaults so backups written before reactions became per member
 * still deserialize.
 */
@Entity(
    tableName = "Reaction",
    indices = [Index(value = ["messageId", "reactorUserId"], unique = true)]
)
@Keep
@Serializable
data class Reaction(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    val messageId: String,
    val reactorUserId: String = "",
    val emoji: String,
    val date: Long = 0L,
)
