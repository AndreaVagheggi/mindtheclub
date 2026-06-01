package com.bolimot.mindtheclub.webrtc

import org.webrtc.DataChannel

enum class IceConnectionState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CHECKING,
    COMPLETED,
    CLOSED,
    NULL
}

enum class PeerConnectionState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    NULL
}

enum class DataChannelState {
    OPEN, CLOSING, CONNECTING, CLOSED;

    companion object {
        fun fromWebRtcState(state: DataChannel.State): DataChannelState {
            return when (state) {
                DataChannel.State.OPEN -> OPEN
                DataChannel.State.CLOSING -> CLOSING
                DataChannel.State.CLOSED -> CLOSED
                DataChannel.State.CONNECTING -> CONNECTING
            }
        }
    }
}

