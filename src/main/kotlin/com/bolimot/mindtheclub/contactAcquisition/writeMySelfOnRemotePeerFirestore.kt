package com.bolimot.mindtheclub.contactAcquisition

// DESKTOP: the App's file, with the Firestore SDK replaced by a REST write (Backend
// .writeContactRequest). Same document, same fields, same sealing; the request timeout of 10
// seconds is the HTTP request's own instead of withTimeout.

import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.NO_PICTURE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun writeMySelfOnRemotePeerFirestore(remoteUserId: String): Boolean {
    val tag = "loadNewContact"
    val localUserId = MySelf.userId()
    val localUserName = MySelf.name()
    val localBio = MySelf.bio()

    if(localUserId == null) {
        debugLine(tag, "loadNewContact: localUserId is null")
        return false
    }

    try {
        val recipientKey = getPeerDao(App.context()).getPeer(remoteUserId)?.publicKey

        fun seal(value: String?): String? {
            if (value == null) return null
            if (recipientKey.isNullOrEmpty()) return value  // fallback: key not yet known
            return KeyManager.encryptFor(recipientKey, value) ?: value
        }

        // DESKTOP: the App's requestPayload map, written by Backend; "timestamp" is the server's.
        withContext(Dispatchers.IO) {
            Backend.writeContactRequest(
                toUserId = remoteUserId,
                fromUserId = localUserId,
                name = seal(localUserName),
                bio = seal(localBio),
                picture = NO_PICTURE,
                fingerprint = seal(KeyManager.getMyPublicKeyFingerprint()),
                enc = !recipientKey.isNullOrEmpty()
            )
        }

        debugLine(tag, "Successfully created contact request entry.")
        return true

    } catch (e: Exception) {
        debugLine(tag, "Error writing contact request: ${e.message}")
        return false
    }
}
