package com.bolimot.mindtheclub.views

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.DisconnectCause
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.functions.applyImmersiveFullScreen
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerRepository
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OutgoingCall : AppCompatActivity() {

    private lateinit var remoteUserName: TextView
    private lateinit var callingType: TextView
    private lateinit var hangUpButton: ImageButton
    private lateinit var peerPicture: ShapeableImageView

    private var callTimeOut = 50000L
    private var tag = "OutgoingCall"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calling_screen)

        setFullScreen()
        
        val peerRepository = getPeerRepository(this)

        remoteUserName = findViewById(R.id.peer_name)
        hangUpButton = findViewById(R.id.button_cancel_call)
        peerPicture = findViewById(R.id.peer_pic)
        callingType = findViewById(R.id.calling_type)

        val remoteUserId = intent.getStringExtra("remoteUserId")
        val isVideo = intent.getBooleanExtra("isVideo", false)
        val callId = intent.getStringExtra("callId")

        callingType.text = if (isVideo) getString(R.string.video_call) else getString(R.string.phone_call)

        // Shouldn't happen, but you never know, nowdays
        if (callId == null) {
            debugLine(tag, "Call ID is null. Cannot proceed.")
            ManagedTelecom.disconnectCall("", DisconnectCause(DisconnectCause.LOCAL))
            return
        }

        // Shouldn't happen, but you never know, nowdays
        if (remoteUserId == null) {
            debugLine(tag, "Remote user ID is null. Cannot proceed.")
            ManagedTelecom.disconnectCall(callId, DisconnectCause(DisconnectCause.LOCAL))
            return
        }

        // In my dialing screen I want to show the Callee name and picture
        // I'm so lucky to have a Peer repository
        lifecycleScope.launch {
            val peer = peerRepository.getPeer(remoteUserId)
            if (peer != null) {
                remoteUserName.text = peer.name

                Glide.with(this@OutgoingCall)
                    .load(peer.picture)
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .placeholder(R.drawable.peer)
                    .error(R.drawable.peer)
                    .into(peerPicture)
            }
        }

        // Timeout for the call, it shouldn't happen, but you never know
        lifecycleScope.launch {
            delay(callTimeOut)
            if (ManagedTelecom.currentCall.value?.isConnected == false) {
                debugLine(tag, "Call timed out.")
                ManagedTelecom.disconnectCall(callId, DisconnectCause(DisconnectCause.LOCAL))
            }
        }

        // In case I change my mind and don't want to call him anymore
        hangUpButton.setOnClickListener {
            ManagedTelecom.disconnectCall(callId, DisconnectCause(DisconnectCause.LOCAL))
            return@setOnClickListener
        }

        // Listen to changes to Telecom states and react accordingly
        lifecycleScope.launch {
            ManagedTelecom.currentCall.collect { callSession ->
                // Disconnection
                if (callSession == null || callSession.disconnectCause != null) {
                    debugLine(tag, "Call disconnected: ${callSession?.disconnectCause}")

                    val disconnectCode = callSession?.disconnectCause?.code

                    if(disconnectCode == DisconnectCause.BUSY)
                        showToast("Call disconnected: User Busy", this@OutgoingCall)

                    finish()
                    return@collect
                }

                // Connection
                if (callSession.isConnected) {
                    debugLine(tag, "Call connected, is Video = $isVideo")

                    val callIntent = if (isVideo) {
                        Intent(this@OutgoingCall, VideoCall::class.java)
                    } else {
                        Intent(this@OutgoingCall,AudioCall::class.java)
                    }.apply {
                        putExtra("remoteUserId", remoteUserId)
                        putExtra("callId", callId)
                    }

                    startActivity(callIntent)
                    finish() // Don't need the dialing screen active once the call is started
                }
            }
        }

        // Timeout for the call
        lifecycleScope.launch {
            delay(50000)
            // If after 50 seconds the call is still not connected, disconnect it.
            if (ManagedTelecom.currentCall.value?.isConnected == false) {
                debugLine(tag, "Call timed out.")
                ManagedTelecom.disconnectCall(callId, DisconnectCause(DisconnectCause.LOCAL))
            }
        }
    }

    companion object {
        fun getIntent(context: Context, remoteUserId: String, isVideo: Boolean, callId: String): Intent {
            return Intent(context, OutgoingCall::class.java).apply {
                putExtra("remoteUserId", remoteUserId)
                putExtra("callId", callId)
                putExtra("isVideo", isVideo)
            }
        }
    }

    private fun setFullScreen() {
        applyImmersiveFullScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system drops the immersive flags whenever the window loses focus,
        // and a dialling screen loses it to every notification that lands.
        if (hasFocus) setFullScreen()
    }
}
