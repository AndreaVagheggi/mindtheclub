package com.bolimot.mindtheclub.webrtc

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    var candidate: Candidate? = null,
    val call: Boolean = false
)

@Keep
@Serializable
data class Candidate(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String
)