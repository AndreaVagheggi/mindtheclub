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
    // messageId -> public file name of that message's media, for received media
    // only. The bytes are NOT in the backup: they live in the phone's public
    // folders (Pictures/Movies/Download + MindTheClub) and travel with the
    // standard Android phone migration. What does not travel is the MediaStore
    // row id inside the uri, so the chat bubbles would point at nothing; this
    // map lets the restore find each file again by name. A few KB in total.
    // For multipleImages the value is a comma separated list, matching uri.
    val mediaFileNames: Map<String, String> = emptyMap(),
    // When the 30-day trial clock started, epoch millis. Without it a phone
    // change silently handed out a brand new trial, and the same trick worked by
    // just uninstalling and restoring. Null in backups made before this field
    // existed, and null when the user never activated: both restore as before.
    val trialStartedAt: Long? = null,
)
