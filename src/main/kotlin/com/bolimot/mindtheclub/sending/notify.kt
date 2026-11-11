package com.bolimot.mindtheclub.sending

// DESKTOP: this file is NOT a copy of the App's notify.kt. notifyRemotePeer keeps its name,
// signature and the way it builds the content and the collapse key; what differs is who
// carries the signal out. In the App it is a WorkManager job (SendFcmWorker); here it is
// SignalSender, until the PC has its own WorkManager stand-in.
//
// notifyGroupTyping is not here: groups are not on the PC yet.

import com.bolimot.mindtheclub.desktop.engine.SignalSender
import com.bolimot.mindtheclub.functions.debugLine

fun notifyRemotePeer(userId: String, messageId: String, information: String, missingItems: String? = null) {
    val content: String = missingItems?.let { "$messageId#$it" } ?: messageId

    if (userId.startsWith("group")) return
    // DESKTOP: the App also returns here for its two local pseudo peers, the AI assistant and
    // the note to self. Neither exists on the PC yet.

    debugLine("notifyRemotePeer", "Queued notification to be sent to $userId")

    SignalSender.enqueue(
        userId = userId,
        content = content,
        information = information,
        collapseKey = "$messageId$information"
    )
}
