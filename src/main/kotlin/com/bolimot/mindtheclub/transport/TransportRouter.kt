package com.bolimot.mindtheclub.transport

// DESKTOP: this file is NOT a copy of the App's TransportRouter.kt. The App tries Bluetooth
// first, behind a user setting, and falls back to WebRTC. The PC has no Bluetooth transport, so
// connect() is the WebRTC branch alone. The names and the result type are the App's.

import android.content.Context
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.desktop.engine.DataLink

sealed class TransportResult {
    data class Connected(val transport: Transport) : TransportResult()
    data class Failed(val result: RTCClientResult) : TransportResult()
}

object TransportRouter {

    suspend fun connect(
        remoteUserId: String,
        senderContentKey: String,
        context: Context
    ): TransportResult {
        val result = DataLink.connect(remoteUserId, senderContentKey)

        return if (result is RTCClientResult.Success) {
            TransportResult.Connected(result.RTCClientWrapper)
        } else {
            TransportResult.Failed(result)
        }
    }
}
