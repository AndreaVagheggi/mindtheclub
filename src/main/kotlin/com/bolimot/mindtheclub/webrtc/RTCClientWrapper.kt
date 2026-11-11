package com.bolimot.mindtheclub.webrtc

// DESKTOP: this file is NOT a copy of the App's RTCClientWrapper.kt. In the App the wrapper
// builds an RTCClient, sends the wake up signal and waits for the connection; on the PC that
// work is done by DataLink and DataSession (desktop/engine, desktop/rtc). What is kept is what
// the rest of the engine sees: a Transport, the thing dispatchBatch writes chunks into.

import com.bolimot.mindtheclub.desktop.rtc.DataSession
import com.bolimot.mindtheclub.transport.Transport

class RTCClientWrapper(private val session: DataSession) : Transport {

    /** True when the write into the data channel buffer succeeded. NOT an acknowledgement. */
    override suspend fun sendData(data: ByteArray): Boolean {
        return session.sendData(data)
    }
}
