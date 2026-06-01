package com.bolimot.mindtheclub.webrtc

import com.bolimot.mindtheclub.functions.debugLine
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class DefaultSdpObserver : SdpObserver {

    override fun onCreateSuccess(description: SessionDescription?) {
        debugLine("WebRtcObserver", "&&&& SDP creation succeeded. SDP: ${description?.description}")
    }

    override fun onSetSuccess() {
        debugLine("WebRtcObserver", "&&&& CLASS SDP set succeeded.")
    }

    override fun onCreateFailure(error: String?) {
        debugLine("WebRtcObserver", "&&&& SDP creation failed. Error: $error")
    }

    override fun onSetFailure(error: String?) {
        debugLine("WebRtcObserver", "&&&& CLASS SDP set failed. Error: $error")
    }
}

