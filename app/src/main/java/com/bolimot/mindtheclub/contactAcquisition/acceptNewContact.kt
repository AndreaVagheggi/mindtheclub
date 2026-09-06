package com.bolimot.mindtheclub.contactAcquisition

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.saveFirebaseImageToDisk
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.AcquisitionStatus
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.ProfileType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Preference key controlling auto-invite mode. Default ON (only "false" disables it). */
const val PREF_AUTO_INVITE_MODE = "mtc_auto_invite_mode_enabled"

fun isAutoInviteEnabled(context: Context): Boolean =
    getPreference(PREF_AUTO_INVITE_MODE, context) != "false"

/** Requesters currently being auto-accepted, shared across the two triggers (the AppTab requests
 *  listener and the CONTACT_REQUEST FCM handler) so the same request is never processed twice. */
private val requestAcceptInFlight: MutableSet<String> =
    java.util.Collections.synchronizedSet(HashSet())

/**
 * Processes one incoming contact request document: unseals the encrypted payload and accepts the
 * requester. Single source of truth for auto-invite, used by both the AppTab Firestore listener
 * (app open) and the CONTACT_REQUEST FCM nudge (app closed, background, doze).
 */
suspend fun autoAcceptRequestDocument(
    doc: com.google.firebase.firestore.DocumentSnapshot,
    context: Context
): Boolean {
    val userId = doc.id
    if (!requestAcceptInFlight.add(userId)) return false

    try {
        // Do not downgrade an existing contact back to a pending request, and NEVER auto-accept a
        // key change: with no server to trust, a silently swapped key is indistinguishable from an
        // attack. But a request from a peer we already know carrying a DIFFERENT fingerprint is
        // exactly what a contact who changed phone looks like: record it so the chat surfaces the
        // explicit accept dialog instead of dying in silenzio.
        val existingPeer = getPeerViewModel().getPeer(userId)
        if (existingPeer != null) {
            try {
                val enc = doc.getBoolean("enc") == true
                val incomingFp = if (enc) doc.getString("fingerprint")
                    ?.let { com.bolimot.mindtheclub.crypto.KeyManager.decrypt(it) } else null
                val storedFp = existingPeer.publicKey?.takeIf { it.isNotEmpty() }
                    ?.let { com.bolimot.mindtheclub.crypto.KeyManager.fingerprintOf(it) }
                if (!incomingFp.isNullOrEmpty() && storedFp != null && incomingFp != storedFp) {
                    com.bolimot.mindtheclub.functions.PeerIdentityChange
                        .record(context, userId, incomingFp)
                }
            } catch (e: Exception) {
                debugLine("autoAcceptRequest", "Key change check failed for $userId: ${e.message}")
            }
            return false
        }

        val isEnc = doc.getBoolean("enc") == true
        fun unseal(v: String?): String? =
            if (isEnc && v != null && v != com.bolimot.mindtheclub.tools.NO_PICTURE)
                com.bolimot.mindtheclub.crypto.KeyManager.decrypt(v)
            else v

        val name = unseal(doc.getString("name"))
        val bio = unseal(doc.getString("bio"))
        val picture = unseal(doc.getString("picture"))?.toUri()
        val fingerprint = if (isEnc) unseal(doc.getString("fingerprint")) else null

        val ok = acceptNewContact(null, userId, name, bio, picture, fingerprint, context)
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
    newPeerView: NewPeerView?,
    userId: String, name: String?, bio: String?, picture: Uri?,
    expectedFingerprint: String?,
    context: Context
): Boolean {
    val localUserId = MySelf.userId() ?: return false

    val requestDoc = FirebaseFirestore.getInstance()
        .collection("users").document(localUserId)
        .collection("requests").document(userId)
        .get().await()

    if (!requestDoc.exists()) {
        debugLine("acceptNewContact", "Request from $userId no longer exists, aborting")
        newPeerView?.finish()
        return false
    }

    // Verify the requester's public key against the sealed fingerprint from the request BEFORE
    // we admit them as a contact.
    val verified = com.bolimot.mindtheclub.crypto.KeyManager
        .fetchAndStorePublicKeyVerified(userId, expectedFingerprint, context)
    if (!verified) {
        debugLine("acceptNewContact", "Key verification failed for $userId. Refusing to accept.")
        // Leave the request in Firestore so user can retry; do not finish() the view here.
        return false
    }

    newPeerView?.finish()

    App.instance!!.applicationScope.launch(Dispatchers.IO) {
        try {
            val peerViewModel = getPeerViewModel()

            val downloadedPicUri = saveFirebaseImageToDisk(picture, "pic_${userId}", 100)
            val resolvedPicture = downloadedPicUri?.toString() ?: peerViewModel.getPeer(userId)?.picture

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

            if (peerViewModel.addOrUpdatePeer(newPeer)) {
                // The verification above ran BEFORE this peer row existed, so its
                // updatePeerPublicKey (an SQL UPDATE) touched zero rows and the verified key was
                // silently lost. Re-run it now that the row exists, so outgoing wake-up FCMs and
                // signalling can encrypt from the start instead of leaning on the one shot in
                // signal key recovery.
                val stored = com.bolimot.mindtheclub.crypto.KeyManager
                    .fetchAndStorePublicKeyVerified(userId, expectedFingerprint, context)
                if (!stored) {
                    debugLine("acceptNewContact", "Post-insert key store failed for $userId; signalling will recover it from Firestore")
                }

                peerViewModel.sendMyProfileToRemotePeer(userId)
                removeRequestFromFirestore(userId)

                val myself = MySelf.userId()
                if (myself != null) {
                    try {
                        FirebaseFirestore.getInstance()
                            .collection("users").document(userId)
                            .collection("requests").document(myself)
                            .delete().await()
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

                val myUserId = MySelf.userId()
                if (myUserId != null) {
                    try {
                        val storageRef = FirebaseStorage.getInstance().reference
                        val picRef = storageRef.child("profile_pictures/${userId}_${myUserId}.jpg")
                        picRef.delete().await()
                    } catch (e: Exception) {
                        debugLine("acceptNewContact", "Picture cleanup failed (may not exist): ${e.message}")
                    }
                }

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

    val db = FirebaseFirestore.getInstance()

    val requestDocRef = db.collection("users").document(localUserId)
        .collection("requests").document(remoteUserId)

    try {
        withTimeout(5000L) {
            requestDocRef.delete().await()
        }

        debugLine(tag, "Successfully removed request document for $remoteUserId")
        return true

    } catch (e: TimeoutCancellationException) {
        debugLine(tag, "FAILED: Delete timed out: ${e.message}")
        return false

    } catch (e: Exception) {
        debugLine(tag, "FAILED: Error removing request: ${e.message}")
        return false
    }
}