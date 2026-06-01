package com.bolimot.mindtheclub.webrtc

import com.bolimot.mindtheclub.functions.debugLine

@Suppress("RedundantSuspendModifier")
suspend fun deleteSignalingChannel(channelId: String): Boolean {
    debugLine("deleteSignalingChannel", "No-op (ephemeral DO room): $channelId")
    return true
}