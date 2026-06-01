package com.bolimot.mindtheclub.contactAcquisition

import androidx.core.net.toUri
import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.loadBitmap
import com.bolimot.mindtheclub.functions.saveBitmap
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
            json.decodeFromString<Peer>(text)
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
            val bitmap = loadBitmap(content.toUri(), App.context())
            if (bitmap != null) {
                val fileName = "${profileData.userId}.jpg"
                val savedUri = saveBitmap(bitmap, fileName, 100, false)
                if (savedUri != null) {
                    profileData.picture = savedUri.toString()
                    debugLine("receiveProfile", "Picture saved: ${profileData.picture}")
                } else {
                    debugLine("receiveProfile", "saveBitmap returned null, keeping existing picture")
                    profileData.picture = null
                }
            } else {
                debugLine("receiveProfile", "loadBitmap failed for content URI, keeping existing picture")
                profileData.picture = null
            }
        }

        val existingPeer = getPeerViewModel().getPeer(profileData.userId)
        if (existingPeer == null) {
            debugLine("receiveProfile", "No local peer for ${profileData.userId}, request was likely cancelled. Ignoring.")
            return
        }

        if (existingPeer.status == Contact.ACTIVE) {
            debugLine("receiveProfile", "Profile update for active peer ${profileData.userId}")

            getPeerViewModel().updatePeerProfile(
                profileData.userId,
                profileData.name,
                profileData.bio,
                profileData.picture ?: existingPeer.picture
            )

            if (!profileData.bluetoothMac.isNullOrEmpty() && profileData.bluetoothMac != existingPeer.bluetoothMac) {
                com.bolimot.mindtheclub.functions.getPeerDao(App.context())
                    .updatePeerBluetoothMac(profileData.userId, profileData.bluetoothMac)
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


            when(myProfileStat) {
                AcquisitionStatus.SENT -> {
                    debugLine("receiveProfile", "Already sent my profile, set ACTIVE")
                    getPeerViewModel().setStatusToActive(message.fromUserId)
                }
                else -> {
                    debugLine("receiveProfile", "Need to send my profile to remote peer")
                    getPeerViewModel().sendMyProfileToRemotePeer(message.fromUserId)
                }
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