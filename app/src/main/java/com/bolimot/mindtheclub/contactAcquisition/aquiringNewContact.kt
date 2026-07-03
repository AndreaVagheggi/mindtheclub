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
                                finishOnAccept: Boolean = true) {

    val failureReason = when {
        userId == getPreference("myUserId", context) -> "Trying to add myself"
        getPeerViewModel().getPeer(userId) != null -> "Peer already exists"
        else -> null
    }

    failureReason?.let {
        debugLine("acquiringNewContact", it)
        return
    }

    val dialog = NewPeerDialog.newInstance(userId, name, bio, fingerprint, finishOnAccept)
    dialog.show(supportFragmentManager, "confirmNewPeer")
}