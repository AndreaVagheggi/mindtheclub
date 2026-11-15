package com.bolimot.mindtheclub.contactAcquisition

// DESKTOP: the App's file, with these differences:
//
//   - the picture: the App decodes the assembled file (loadBitmap) and saves it again as
//     "<userId>.jpg" (saveBitmap). The PC keeps the assembled file itself in its media folder,
//     named after the hash of its bytes like every other file there, once it has checked that it
//     is a picture (MediaFiles.keepReceivedPicture). Same outcome when it is not: the picture
//     stays the one the contact already had;
//   - the view model calls are the DAO's (getPeer, updatePeerProfile, updatePeerStatus) and
//     Outgoing.sendMyProfile, the PC's sendMyProfileToRemotePeer;
//   - the Bluetooth address is stored as in the App, though the PC has no Bluetooth transport.
//
// As in the App, the chunks of the profile are left in the Inbox: see InboxRecoveryWorker.

import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.desktop.engine.Outgoing
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.receiving.addIncomingContact
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.AcquisitionStatus
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.ProfileType
import kotlinx.serialization.json.Json
import com.bolimot.mindtheclub.functions.getBlockedUserRepository
import com.bolimot.mindtheclub.tools.NO_PICTURE

suspend fun receiveProfile(text: String, content: String, message: Inbox) {
    try {
        if(text.isEmpty()) {
            debugLine("receiveProfile", "Received profile is empty")
            return
        }

        val json = Json { ignoreUnknownKeys = true }

        val profileData: Peer? = try {
            json.decodeFromString(Peer.serializer(), text)
        } catch (e: Exception) {
            debugLine("receiveProfile", "Failed to parse Peer JSON: ${e.message}")
            null
        }

        val failureReason = when {
            profileData == null -> "Received profile is null"
            profileData.userId.isEmpty() -> "Received profile has no userId"
            profileData.name.isEmpty() -> "Received profile has no name"
            else -> null
        }

        failureReason?.let {
            debugLine("receiveProfile", it)
            return
        }

        debugLine("receiveProfile", "Profile received, incoming bluetoothMac=${profileData!!.bluetoothMac ?: "null"}")

        if (getBlockedUserRepository(App.context()).isBlocked(profileData.userId)) {
            debugLine("receiveProfile", "Blocked user ${profileData.userId}, ignoring profile")
            return
        }

        if (content == NO_PICTURE) {
            profileData.picture = NO_PICTURE
            debugLine("receiveProfile", "Peer has no profile picture")
        } else if (content.isNotEmpty()) {
            // DESKTOP: see the top of the file.
            val assembled = MediaFiles.fileOfUri(content)
            val kept = assembled?.let { MediaFiles.keepReceivedPicture(it) }
            if (kept != null) {
                profileData.picture = kept.toURI().toString()
                debugLine("receiveProfile", "Picture saved: ${profileData.picture}")
            } else {
                debugLine("receiveProfile", "The content is not a picture, keeping existing picture")
                profileData.picture = null
            }
        }

        val peerDao = getPeerDao(App.context())

        val existingPeer = peerDao.getPeer(profileData.userId)
        if (existingPeer == null) {
            debugLine("receiveProfile", "No local peer for ${profileData.userId}, request was likely cancelled. Ignoring.")
            return
        }

        if (existingPeer.status == Contact.ACTIVE) {
            debugLine("receiveProfile", "Profile update for active peer ${profileData.userId}")

            peerDao.updatePeerProfile(
                profileData.userId,
                profileData.name,
                profileData.bio,
                profileData.picture ?: existingPeer.picture
            )

            if (!profileData.bluetoothMac.isNullOrEmpty() && profileData.bluetoothMac != existingPeer.bluetoothMac) {
                peerDao.updatePeerBluetoothMac(profileData.userId, profileData.bluetoothMac)
            }

            notifyRemotePeer(message.fromUserId, message.messageId, Notify.ALL_RECEIVED)
            debugLine("receiveProfile", "Profile silently updated for ${profileData.userId}")
            return
        }

        if (profileData.picture == null && existingPeer.picture != null) {
            profileData.picture = existingPeer.picture
        }

        if (addIncomingContact(profileData)) {
            notifyRemotePeer(message.fromUserId, message.messageId, Notify.ALL_RECEIVED)

            setAcquisitionStatus(message.fromUserId,
                Location.LOCAL,
                ProfileType.REMOTE,
                AcquisitionStatus.RECEIVED,
                App.context())
            debugLine("receiveProfile", "Set LOCALLY Remote profile received")


            setAcquisitionStatus(message.fromUserId,
                Location.REMOTE,
                ProfileType.REMOTE,
                AcquisitionStatus.SENT,
                App.context())
            debugLine("receiveProfile", "Set REMOTELY Remote profile sent")

            val myProfileStat = getAcquisitionStatus(
                message.fromUserId,
                Location.LOCAL,
                ProfileType.LOCAL,
                App.context())
            debugLine("receiveProfile", "Getting My profileStat LOCALLY/My Profile status: $myProfileStat")


            // The peer's profile arrived: it is the proof that it accepted the request and
            // verified my key, because that is the only thing that makes it send one. The
            // contact is good now; waiting for the ack of MY profile kept it Pending for as long
            // as a low priority signal took (see the App's file, 28 Aug).
            peerDao.updatePeerStatus(message.fromUserId, Contact.ACTIVE)

            if (myProfileStat == AcquisitionStatus.SENT) {
                debugLine("receiveProfile", "My profile already delivered, contact complete")
            } else {
                debugLine("receiveProfile", "Sending my profile to remote peer")
                Outgoing.sendMyProfile(message.fromUserId)
            }
        } else {
            debugLine("receiveProfile", "Cannot save new received profile as contact")
        }

    } catch(e: Exception){
        debugLine(
            "receiveProfile",
            "Exception trying to save new received profile as contact: ${e.message}"
        )
    }
}
