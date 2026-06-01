package com.bolimot.mindtheclub.dataModels

import com.bolimot.mindtheclub.webrtc.RTCClientWrapper

sealed class RTCClientResult {
    data class Success(val RTCClientWrapper: RTCClientWrapper) : RTCClientResult()
    data object RTCClientNotCreated : RTCClientResult()
    data object RTCClientNotConnected : RTCClientResult()
    data object RTCClientSuccess : RTCClientResult()
    data object RTCClientBusy : RTCClientResult()
    data object RTCClientNotUsable: RTCClientResult()
    data object RTCClientGeneralFailure: RTCClientResult()
    data object RTCClientNotNeeded: RTCClientResult()
    data object RTCClientTimeOut: RTCClientResult()
}