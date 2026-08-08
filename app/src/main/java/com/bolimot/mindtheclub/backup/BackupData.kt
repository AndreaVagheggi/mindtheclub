// NEW FILE: app/src/main/java/com/bolimot/mindtheclub/backup/BackupData.kt

package com.bolimot.mindtheclub.backup

import androidx.annotation.Keep
import com.bolimot.mindtheclub.database.blockeduser.BlockedUser
import com.bolimot.mindtheclub.database.club.Club
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.reaction.Reaction
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class BackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val userId: String,
    val privateId: String?,
    val name: String?,
    val bio: String?,
    val pictureUri: String?,
    val pictureMiniUri: String?,
    val fcmToken: String?,
    val peers: List<Peer>,
    val messages: List<Message>,
    val reactions: List<Reaction>,
    val blockedUsers: List<BlockedUser>,
    val clubs: List<Club>,
    val selfPictureBase64: String? = null,
    val selfPictureMiniBase64: String? = null,
    val peerPictures: Map<String, String> = emptyMap(),
    // Full Tink identity keyset, PRIVATE key included. This is what lets a
    // restore keep the user's identity across phones: without it the new device
    // generates a fresh keypair and every contact silently stops decrypting
    // (7 Aug). Only ever present inside the AES-GCM encrypted envelope; null in
    // backups made by older versions, which restore data only, as before.
    val identityKeyset: String? = null,
)
