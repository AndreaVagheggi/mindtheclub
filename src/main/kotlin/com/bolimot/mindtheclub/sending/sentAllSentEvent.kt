package com.bolimot.mindtheclub.sending

import com.bolimot.mindtheclub.functions.debugLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun sentAllSentEvent(messageId: String, userId: String) {
    debugLine("successEvent", "ALL SENT: $messageId")

    CoroutineScope(Dispatchers.IO).launch {
        notifyRemotePeer(userId, messageId,"completed")
    }
}