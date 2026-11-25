package com.bolimot.mindtheclub.contactAcquisition

// DESKTOP: the App's file, with these differences:
//
//   - a request document is a Backend.ContactRequest (read over REST) instead of a Firestore
//     DocumentSnapshot, and the Firestore reads and deletes go through Backend;
//   - acceptNewContact has no NewPeerView parameter: on the phone it is the screen of the
//     request list, used only when auto-invite is off. The PC has no switch for auto-invite
//     (OptionsActivity on the phone), so it keeps the App's default, ON, and every request is
//     accepted the way the phone accepts it in that mode;
//   - a contact whose request carries a different key is not recorded for the key change
//     dialog (PeerIdentityChange), which is not on the PC yet: the request is left alone, never
//     accepted in silence;
//   - the request's picture is not downloaded (saveFirebaseImageToDisk) and the leftover in
//     Storage is not deleted. Since the App wrote NO_PICTURE in every request
//     (writeMySelfOnRemotePeerFirestore), the picture travels with the profile, and the
//     Storage file the App still deletes is a leftover of older builds;
//   - the view model calls are the repository's bodies (addOrUpdatePeer in functions/database.kt)
//     and Outgoing.sendMyProfile, the PC's sendMyProfileToRemotePeer.

import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.desktop.engine.Outgoing
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.addOrUpdatePeer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.AcquisitionStatus
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.ProfileType
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preference key controlling auto-invite mode. Default ON (only "false" disables it). */
const val PREF_AUTO_INVITE_MODE = "mtc_auto_invite_mode_enabled"

fun isAutoInviteEnabled(context: Context): Boolean =
    getPreference(PREF_AUTO_INVITE_MODE, context) != "false"

/** Requesters currently being auto-accepted, shared across the two triggers (the engine's start
 *  and the CONTACT_REQUEST signal) so the same request is never processed twice. */
private val requestAcceptInFlight: MutableSet<String> =
    java.util.Collections.synchronizedSet(HashSet())

/**
 * Processes one incoming contact request document: unseals the encrypted payload and accepts the
 * requester. Single source of truth for auto-invite.
 */
suspend fun autoAcceptRequestDocument(
    doc: Backend.ContactRequest,
    context: Context
): Boolean {
    val userId = doc.fromUserId
    if (!requestAcceptInFlight.add(userId)) return false

    try {
        // Do not downgrade an existing contact back to a pending request, and NEVER auto-accept a
        // key change: with no server to trust, a silently swapped key is indistinguishable from an
        // attack.
        val existingPeer = getPeerDao(context).getPeer(userId)
        if (existingPeer != null) {
            var sameKey = false
            try {
                val incomingFp = if (doc.enc) doc.fingerprint
                    ?.let { com.bolimot.mindtheclub.crypto.KeyManager.decrypt(it) } else null
                val storedFp = existingPeer.publicKey?.takeIf { it.isNotEmpty() }
                    ?.let { com.bolimot.mindtheclub.crypto.KeyManager.fingerprintOf(it) }
                if (!incomingFp.isNullOrEmpty() && storedFp != null && incomingFp != storedFp) {
                    // DESKTOP: PeerIdentityChange.record is not on the PC, see the top of the file.
                    debugLine("autoAcceptRequest", "Request from $userId carries a different key, left alone")
                }
                sameKey = !incomingFp.isNullOrEmpty() && incomingFp == storedFp
            } catch (e: Exception) {
                debugLine("autoAcceptRequest", "Key change check failed for $userId: ${e.message}")
            }
            // An active contact asking again with the very key we verified: its side lost us
            // (removed by mistake, reinstalled) and cannot complete without our profile. Re-send
            // it and close the request, which otherwise hung forever. Our row is not touched; a
            // different key is never taken.
            if (sameKey && existingPeer.status == Contact.ACTIVE) {
                debugLine("autoAcceptRequest", "Repeat request from active contact $userId, re-sending my profile")
                Outgoing.sendMyProfile(userId)
                removeRequestFromFirestore(userId)
                return true
            }
            return false
        }

        fun unseal(v: String?): String? =
            if (doc.enc && v != null && v != com.bolimot.mindtheclub.tools.NO_PICTURE)
                com.bolimot.mindtheclub.crypto.KeyManager.decrypt(v)
            else v

        val name = unseal(doc.name)
        val bio = unseal(doc.bio)
        val picture = unseal(doc.picture)
        val fingerprint = if (doc.enc) unseal(doc.fingerprint) else null

        val ok = acceptNewContact(userId, name, bio, picture, fingerprint, context)
        if (!ok) {
            debugLine("autoAcceptRequest", "Auto-invite accept failed for $userId")
        }
        return ok
    } catch (e: Exception) {
        debugLine("autoAcceptRequest", "Auto-invite error for $userId: ${e.message}")
        return false
    } finally {
        requestAcceptInFlight.remove(userId)
    }
}

suspend fun acceptNewContact(
    userId: String, name: String?, bio: String?, picture: String?,
    expectedFingerprint: String?,
    context: Context
): Boolean {
    val localUserId = MySelf.userId() ?: return false

    val requestDoc = withContext(Dispatchers.IO) { Backend.contactRequest(localUserId, userId) }

    if (requestDoc == null) {
        debugLine("acceptNewContact", "Request from $userId no longer exists, aborting")
        return false
    }

    // Verify the requester's public key against the sealed fingerprint from the request BEFORE
    // we admit them as a contact.
    val verified = com.bolimot.mindtheclub.crypto.KeyManager
        .fetchAndStorePublicKeyVerified(userId, expectedFingerprint, context)
    if (!verified) {
        debugLine("acceptNewContact", "Key verification failed for $userId. Refusing to accept.")
        // Leave the request in Firestore so it can be retried.
        return false
    }

    App.instance!!.applicationScope.launch(Dispatchers.IO) {
        try {
            // DESKTOP: no download of the request's picture, see the top of the file.
            val resolvedPicture = getPeerDao(context).getPeer(userId)?.picture

            val newPeer = Peer(
                userId = userId,
                name = name ?: "",
                bio = bio ?: "",
                picture = resolvedPicture,
                uid = 0,
                token = "",
                status = Contact.NEW,
                privateId = ""
            )

            if (addOrUpdatePeer(newPeer)) {
                // The verification above ran BEFORE this peer row existed, so its
                // updatePeerPublicKey (an SQL UPDATE) touched zero rows and the verified key was
                // silently lost. Re-run it now that the row exists.
                val stored = com.bolimot.mindtheclub.crypto.KeyManager
                    .fetchAndStorePublicKeyVerified(userId, expectedFingerprint, context)
                if (!stored) {
                    debugLine("acceptNewContact", "Post-insert key store failed for $userId; signalling will recover it from Firestore")
                }

                // Active now, not when allReceived for our profile lands: the key is verified and
                // name and bio are in, the same evidence the asking side goes active on. While
                // NEW this row opened as "pending" with a remove-request button, and a click on
                // it seconds after the auto-accept deleted the contact on this side only.
                getPeerDao(context).updatePeerStatus(userId, Contact.ACTIVE)

                Outgoing.sendMyProfile(userId)
                removeRequestFromFirestore(userId)

                val myself = MySelf.userId()
                if (myself != null) {
                    try {
                        Backend.deleteContactRequest(userId, myself)
                    } catch (e: Exception) {
                        debugLine("acceptNewContact", "Failed to clean up outgoing request: ${e.message}")
                    }
                }

                setAcquisitionStatus(userId,
                    Location.LOCAL,
                    ProfileType.REMOTE,
                    AcquisitionStatus.RECEIVED,
                    context)

                setAcquisitionStatus(userId,
                    Location.REMOTE,
                    ProfileType.REMOTE,
                    AcquisitionStatus.SENT,
                    context)

                // DESKTOP: no Storage cleanup, see the top of the file.

                debugLine("acceptNewContact", "New peer added with verified key.")
            } else {
                debugLine("acceptNewContact", "Failed to add new peer")
            }
        } catch (e: Exception) {
            debugLine("acceptNewContact", "Background accept failed: ${e.message}")
        }
    }

    return true
}

suspend fun removeRequestFromFirestore(remoteUserId: String): Boolean {
    val tag = "removeRequest"
    val localUserId = MySelf.userId()

    if (localUserId == null) {
        debugLine(tag, "Aborting: localUserId is null")
        return false
    }

    debugLine(tag, "Attempting to remove request from: $remoteUserId")

    try {
        withContext(Dispatchers.IO) { Backend.deleteContactRequest(localUserId, remoteUserId) }

        debugLine(tag, "Successfully removed request document for $remoteUserId")
        return true

    } catch (e: Exception) {
        debugLine(tag, "FAILED: Error removing request: ${e.message}")
        return false
    }
}
