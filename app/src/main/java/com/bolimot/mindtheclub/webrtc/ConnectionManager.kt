package com.bolimot.mindtheclub.webrtc
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bolimot.mindtheclub.voip.shutdownRTC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.PeerConnection
import java.util.concurrent.ConcurrentHashMap

class ConnectionManager {
    private val rtcClientsRepository = ConcurrentHashMap<String, RTCClientWrapper>()
    private val webRTCleanUpMutexes = ConcurrentHashMap<String, Mutex>()
    private val webRTConnectMutexes = ConcurrentHashMap<String, Mutex>()

    private val lastConnectFailure = ConcurrentHashMap<String, Long>()

    // Newest wins among queued data connections. Every dataCall carries a fresh
    // signalling room id and the other side abandons that room after
    // PEER_TIMEOUT_MS, so a request overtaken while waiting on the mutex can only
    // join a room nobody is in any more. The mutex is FIFO, which without this
    // made us always process the OLDEST, and therefore the deadest, room id: on
    // 3 Aug it left the two phones one or more rooms apart for ten minutes.
    private val latestDataChannelId = ConcurrentHashMap<String, String>()

    /**
     * Records [channelId] as the newest data request for [remoteUserId].
     *
     * Callers MUST do this, and check [isSupersededDataChannel], BEFORE tearing
     * anything down. The superseded check used to live only inside
     * webRTCConnect, i.e. after DataSyncService had already run webRTCCleanUp:
     * every incoming dataCall therefore destroyed whatever was running and only
     * then discovered its own request was stale. With two media transfers in
     * flight from the same peer the two dispatch workers kept demolishing each
     * other, and a 165 chunk photo crawled at a handful of chunks per reconnect
     * (12 Aug, Raoul's group photos: 101 chunks in 19 minutes, while 7 chunks
     * arrived in the two seconds right after each reconnect).
     */
    fun claimLatestDataChannel(remoteUserId: String, channelId: String) {
        latestDataChannelId[remoteUserId] = channelId
    }

    /** True when [channelId] has since been overtaken by a newer data request. */
    fun isSupersededDataChannel(remoteUserId: String, channelId: String): Boolean =
        latestDataChannelId[remoteUserId] != channelId

    /**
     * True when a data connection to [remoteUserId] is usable RIGHT NOW, i.e.
     * peer connection up and data channel actually open.
     *
     * The open data channel is required on purpose. An earlier draft accepted a
     * merely CONNECTED peer connection, reasoning that a still-negotiating one
     * was about to become useful. That would have been a deadlock: isConnected()
     * only reads PeerConnectionState, so a connection whose data channel died or
     * never opened would report "live" for ever and make every later dataCall
     * refuse to rebuild it. Being strict here costs nothing: a request that
     * finds no usable connection falls through to the superseded check, which is
     * what actually prevents the storm.
     */
    fun hasLiveConnection(remoteUserId: String): Boolean {
        val client = rtcClientsRepository[remoteUserId]?.rtcClient ?: return false
        return !client.cleanedUp && client.isConnected() && client.isDataChannelOpen()
    }

    private val dispatchContentHint = ConcurrentHashMap<String, String>()

    fun setDispatchContentHint(remoteUserId: String, contentKey: String) {
        if (contentKey.isEmpty()) dispatchContentHint.remove(remoteUserId)
        else dispatchContentHint[remoteUserId] = contentKey
    }

    fun consumeDispatchContentHint(remoteUserId: String): String? =
        dispatchContentHint.remove(remoteUserId)

    private val activeSendersByContent = ConcurrentHashMap<String, MutableSet<String>>()
    private val activeSendersLock = Any()

    fun tryAdmitSender(contentKey: String, fromUserId: String): Boolean {
        if (contentKey.isEmpty()) return true
        synchronized(activeSendersLock) {
            val set = activeSendersByContent.getOrPut(contentKey) { mutableSetOf() }
            if (set.contains(fromUserId)) return true // idempotent
            if (set.size >= MAX_CONCURRENT_SENDERS_PER_CONTENT) return false
            set.add(fromUserId)
            return true
        }
    }

    private fun releaseSender(fromUserId: String) {
        synchronized(activeSendersLock) {
            val toRemove = mutableListOf<String>()
            for ((ck, set) in activeSendersByContent) {
                if (set.remove(fromUserId) && set.isEmpty()) toRemove.add(ck)
            }
            toRemove.forEach { activeSendersByContent.remove(it) }
        }
    }

    var remoteUserId = ""
    private var rtcClientWrapper: RTCClientWrapper? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var rtcClient: RTCClient? = null
    var activeMediaRtcClient: RTCClient? = null
    var isClosing: Boolean = true
    var cleaningUpWebRTC: Boolean = false
    var peerConnection: PeerConnection? = null

    var mediaCallEstablished = MutableStateFlow(false)

    private val _remoteVideoState = MutableSharedFlow<Boolean>()
    val remoteVideoState = _remoteVideoState.asSharedFlow()

    // Audio to video upgrade handshake, carried as CallEvent values. Buffered because
    // the peer's answer can land while the call screen is being swapped.
    private val _videoUpgradeEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val videoUpgradeEvents = _videoUpgradeEvents.asSharedFlow()

    private val tag = "ConnectionManager"

    private var isVideoCall = false

    fun notifyRemoteVideoState(isPaused: Boolean) {
        scope.launch {
            _remoteVideoState.emit(isPaused)
        }
    }

    fun notifyVideoUpgradeEvent(event: String) {
        scope.launch {
            _videoUpgradeEvents.emit(event)
        }
    }

    private suspend fun deleteRTCClient(remoteUserId: String, forced: Boolean = false): Boolean {
        val clientToRemove = rtcClientsRepository[remoteUserId]?.rtcClient

        if (clientToRemove != null) {
            rtcClientsRepository.remove(remoteUserId)
            clientToRemove.cleanup(forced)

            if(!waitForClientDeletion(clientToRemove)) {
                debugLine("deleteRTCClient", "Timed out waiting for client cleanup.")
                return false
            }
            debugLine("deleteRTCClient", "Client successfully removed and cleaned up.")
            return true
        } else {
            debugLine("deleteRTCClient", "Cannot remove Client, it's already removed or was never created.")
            return true
        }
    }

    fun getExistingClient(remoteUserId: String): RTCClientWrapper? {
        return rtcClientsRepository[remoteUserId]
    }

    suspend fun webRTCConnect(channelId: String, callId: String, remoteUserId: String, initiator: Boolean, context: Context, video: Boolean, dataOnly: Boolean): RTCClientResult {
        val mutex = webRTConnectMutexes.computeIfAbsent(remoteUserId) { Mutex() }
        this.remoteUserId = remoteUserId

        // Registered before queueing on the mutex, so a request arriving later
        // supersedes the ones already waiting. Calls are deliberately excluded:
        // they carry ManagedTelecom state that an early return would leave stuck
        // in PENDING, and they never produce the burst of room ids this guards.
        if (dataOnly) latestDataChannelId[remoteUserId] = channelId

        return mutex.withLock {

            if (dataOnly && latestDataChannelId[remoteUserId] != channelId) {
                debugLine(tag, "Superseded while queued: channelId=$channelId for $remoteUserId is stale, skipping")
                return@withLock RTCClientResult.RTCClientGeneralFailure
            }

            // The cooldown throttles OUR attempts to reach a peer that looks offline.
            // It must never gate an answer: initiator = false means the peer contacted
            // us and is already waiting in the signalling room, so it is demonstrably
            // reachable — refusing there just drops the incoming message or call.
            val lastFail = lastConnectFailure[remoteUserId] ?: 0L
            if (initiator && System.currentTimeMillis() - lastFail < CONNECT_COOLDOWN_MS) {
                debugLine(tag, "Skipping connection attempt — recent failure for $remoteUserId")
                return@withLock RTCClientResult.RTCClientGeneralFailure
            }

            debugLine(tag, "I'm starting to create a webRTC connection as initiator: $initiator")

            if(!dataOnly) ManagedTelecom.updateWebRTCConnectionState(callId, ManagedTelecom.WebRTCConnectionState.PENDING)

            cleaningUpWebRTC = false

            rtcClientWrapper = rtcClientsRepository[remoteUserId]

            if(rtcClientWrapper != null){
                if(!dataOnly) {
                    deleteRTCClient(remoteUserId, true)
                    rtcClientWrapper = null
                }
            }

            rtcClientWrapper?.let {
                rtcClient = it.rtcClient
                isVideoCall = video

                val hasData = it.rtcClient.isDataChannelOpen()
                val hasVideo = it.rtcClient.hasVideo
                val isConnected = it.rtcClient.isConnected()

                if (!isConnected) {
                    debugLine("webRTCConnect", "Existing RTCClient is dead (not CONNECTED). Cleaning up.")
                    if(!deleteRTCClient(remoteUserId, true)) {
                        return@withLock RTCClientResult.RTCClientGeneralFailure
                    }
                } else if (!hasData) {
                    debugLine("webRTCConnect", "RTCClient has no data channel, not usable")
                    if(!deleteRTCClient(remoteUserId)) {
                        return@withLock RTCClientResult.RTCClientGeneralFailure
                    }
                } else if (!hasVideo && video) {
                    debugLine("webRTCConnect", "RTCClient has no Video, not usable")
                    if(!deleteRTCClient(remoteUserId)) {
                        ManagedTelecom.updateWebRTCConnectionState(callId, ManagedTelecom.WebRTCConnectionState.FAILED)
                        return@withLock RTCClientResult.RTCClientGeneralFailure
                    }
                } else {
                    debugLine("webRTCConnect", "I have already a usable RTCClient in my repository")
                    return@withLock RTCClientResult.Success(it)
                }
            }

            try {
                debugLine("webRTCConnect", "Repository empty, I need to create a new RTCClient")

                when (val rtcClientWrapperResult = RTCClientWrapper.create(channelId, callId, remoteUserId, initiator, context, video, dataOnly)) {
                    is RTCClientResult.RTCClientNotConnected -> {
                        lastConnectFailure[remoteUserId] = System.currentTimeMillis() // SOAK CHANGE
                        debugLine("webRTCConnect", "New RTCClient created, but not connected")
                        if(!dataOnly) ManagedTelecom.updateWebRTCConnectionState(callId, ManagedTelecom.WebRTCConnectionState.FAILED)
                        return@withLock RTCClientResult.RTCClientNotConnected
                    }
                    is RTCClientResult.RTCClientNotCreated -> {
                        lastConnectFailure[remoteUserId] = System.currentTimeMillis() // SOAK CHANGE
                        debugLine("webRTCConnect", "New RTCClient not created")
                        if(!dataOnly) ManagedTelecom.updateWebRTCConnectionState(callId, ManagedTelecom.WebRTCConnectionState.FAILED)
                        return@withLock RTCClientResult.RTCClientNotCreated
                    }
                    is RTCClientResult.Success -> {
                        lastConnectFailure.remove(remoteUserId) // SOAK CHANGE
                        if(rtcClientWrapperResult.RTCClientWrapper.rtcClient.dataChannel == null){
                            if(dataOnly) {
                                debugLine(
                                    "webRTCConnect",
                                    "New RTCClient created, but has no data channel, not usable"
                                )
                                return@withLock RTCClientResult.RTCClientNotUsable
                            }
                        }

                        debugLine("webRTCConnect", "New RTCClient created, adding to repository and returning")
                        val newRTCClient = rtcClientWrapperResult.RTCClientWrapper
                        rtcClientsRepository[remoteUserId] = newRTCClient
                        rtcClient = newRTCClient.rtcClient

                        if (!dataOnly) {
                            activeMediaRtcClient = rtcClient
                            ManagedTelecom.updateWebRTCConnectionState(callId, ManagedTelecom.WebRTCConnectionState.CONNECTED)
                        }

                        debugLine(tag, "New RTCClient created channelId = ${channelId}, callId = $callId")

                        return@withLock RTCClientResult.Success(newRTCClient)
                    }
                    else -> {
                        debugLine("getOrCreateDataClient", "DataClient created, but general failure")
                        if(!dataOnly) ManagedTelecom.updateWebRTCConnectionState(callId, ManagedTelecom.WebRTCConnectionState.FAILED)
                        return@withLock RTCClientResult.RTCClientGeneralFailure
                    }
                }
            } catch(e: Exception) {
                // A cancellation (shutdownRTC tearing the client down while it is still
                // being created) says nothing about whether the peer is reachable.
                // Arming the cooldown here poisoned the peer for 15s and made us refuse
                // its own incoming connection moments later.
                if (e is CancellationException) {
                    debugLine(tag, "RTCClient creation cancelled, not arming cooldown: ${e.message}")
                } else {
                    lastConnectFailure[remoteUserId] = System.currentTimeMillis() // SOAK CHANGE
                    debugLine(tag, "New RTCClient creation failed with exception: ${e.message}")
                }
                if(!dataOnly) ManagedTelecom.updateWebRTCConnectionState(callId, ManagedTelecom.WebRTCConnectionState.FAILED)
                return@withLock RTCClientResult.RTCClientGeneralFailure
            }
        }
    }

    suspend fun webRTCCleanUp(userId: String) {
        val mutex = webRTCleanUpMutexes.computeIfAbsent(userId) { Mutex() }

        mutex.withLock {
            debugLine(tag, "Cleaning up all WebRTC resources for user: $userId")

            releaseSender(userId)
            shutdownRTC(userId)

            val context = rtcClient?.eglContext?.let { App.context() }
            context?.let { resetAudioManager(it) }

            if (activeMediaRtcClient?.remoteUserId == userId) {
                activeMediaRtcClient = null
            }

            rtcClientsRepository.remove(userId)?.let { clientWrapper ->
                debugLine(tag, "Client found in repository, cleaning up.")
                clientWrapper.rtcClient.cleanup(true)
                waitForClientDeletion(clientWrapper.rtcClient)
            } ?: debugLine(tag, "Cleanup requested, but no client found in repository for user: $userId")
        }
    }

    private suspend fun waitForClientDeletion(clientToRemove: RTCClient): Boolean {
        debugLine("waitForClientDeletion", "Waiting for client to be cleaned up.")
        if(clientToRemove.cleanedUp) return true

        val cleanupSuccess = withTimeoutOrNull(8000) {
            while (!clientToRemove.cleanedUp) {
                delay(100)
            }
            true
        }
        if (cleanupSuccess == true) {
            debugLine("waitForClientDeletion", "Client cleaned up.")
        } else {
            debugLine("waitForClientDeletion", "Timeout waiting client to be cleaned up.")
        }

        return cleanupSuccess == true
    }

    private fun resetAudioManager(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val result = audioManager.clearCommunicationDevice()
            debugLine(tag, "resetAudioManager: clearCommunicationDevice result: $result")
        } else {
            @Suppress("DEPRECATION")
            if (audioManager.isSpeakerphoneOn) {
                audioManager.isSpeakerphoneOn = false
                debugLine(tag, "resetAudioManager: setSpeakerphoneOn deprecated: false")
            }
        }
    }

    companion object {
        val instance: ConnectionManager by lazy { ConnectionManager() }
        private const val CONNECT_COOLDOWN_MS = 15_000L
        const val MAX_CONCURRENT_SENDERS_PER_CONTENT = 3
    }
}