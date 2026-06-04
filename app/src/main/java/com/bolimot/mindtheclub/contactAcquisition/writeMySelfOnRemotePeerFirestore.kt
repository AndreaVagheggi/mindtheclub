package com.bolimot.mindtheclub.contactAcquisition

import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.NO_PICTURE
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

suspend fun writeMySelfOnRemotePeerFirestore(remoteUserId: String): Boolean {
    val tag = "loadNewContact"
    val localUserId = MySelf.userId()
    val localUserName = MySelf.name()
    val localBio = MySelf.bio()

    if(localUserId == null) {
        debugLine(tag, "loadNewContact: localUserId is null")
        return false
    }

    val db = FirebaseFirestore.getInstance()

    try {
        val recipientKey = getPeerDao(App.context()).getPeer(remoteUserId)?.publicKey

        fun seal(value: String?): String? {
            if (value == null) return null
            if (recipientKey.isNullOrEmpty()) return value  // fallback: key not yet known
            return KeyManager.encryptFor(recipientKey, value) ?: value
        }

        val requestPayload = mapOf(
            "userId" to localUserId,
            "name" to seal(localUserName),
            "bio" to seal(localBio),
            "picture" to NO_PICTURE,
            "fingerprint" to seal(KeyManager.getMyPublicKeyFingerprint()),
            "enc" to !recipientKey.isNullOrEmpty(),
            "timestamp" to FieldValue.serverTimestamp()
        )

        val requestDocRef = db.collection("users").document(remoteUserId)
            .collection("requests")
            .document(localUserId)

        withTimeout(10_000L) {
            requestDocRef.set(requestPayload).await()
        }

        debugLine(tag, "Successfully created contact request entry.")
        return true

    } catch (e: TimeoutCancellationException) {
        debugLine(tag, "Operation timed out, ${e.message}")
        return false
    } catch (e: Exception) {
        debugLine(tag, "Error writing contact request: ${e.message}")
        return false
    }
}