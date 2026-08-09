package com.bolimot.mindtheclub.contactAcquisition

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getPreference

suspend fun acquiringNewContact(userId: String,
                                name: String,
                                bio: String?,
                                fingerprint: String?,
                                context: Context,
                                supportFragmentManager: FragmentManager,
                                finishOnAccept: Boolean = true,
                                resultKey: String? = null) {

    val existing = getPeerViewModel().getPeer(userId)

    val failureReason = when {
        userId == getPreference("myUserId", context) -> "Trying to add myself"
        existing != null -> {
            // Re-scanning the QR (or re-adding the card) of a contact whose key
            // changed used to be swallowed here, leaving no way to recover the
            // contact. Record the change: the chat surfaces the accept dialog.
            val storedFp = existing.publicKey?.takeIf { it.isNotEmpty() }
                ?.let { com.bolimot.mindtheclub.crypto.KeyManager.fingerprintOf(it) }
            if (!fingerprint.isNullOrEmpty() && storedFp != null && fingerprint != storedFp) {
                com.bolimot.mindtheclub.functions.PeerIdentityChange
                    .record(context, userId, fingerprint)
                "Peer already exists with a different key, change recorded for explicit approval"
            } else {
                "Peer already exists"
            }
        }
        else -> null
    }

    failureReason?.let {
        debugLine("acquiringNewContact", it)
        return
    }

    val dialog = NewPeerDialog.newInstance(userId, name, bio, fingerprint, finishOnAccept, resultKey)
    dialog.show(supportFragmentManager, "confirmNewPeer")
}