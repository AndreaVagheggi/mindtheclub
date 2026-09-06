package com.bolimot.mindtheclub.webrtc.group

import android.content.Context
import com.bolimot.mindtheclub.billing.BillingManager
import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * The one live group call, from the invitation to the last hangup.
 *
 * Owns three things that only make sense together: the SFU leg ([GroupRtcClient]), the
 * presence room ([CallRoomSocket]) that says who else is there, and the call key that
 * seals both media and roster. The call screen reads the flows below and pushes
 * buttons, niente WebRTC o Cloudflare reaches the UI.
 *
 * Singleton apposta: a phone can be in exactly one group call, and making that an
 * architectural fact is cheaper than defending against it everywhere.
 */
object GroupCallManager : CallRoomSocket.Listener, GroupRtcClient.Listener {

    private const val TAG = "GroupCallManager"

    /** Quanto suona an unanswered invitation on the other phones. */
    const val RING_TIMEOUT_MS = 45_000L

    enum class Status {
        IDLE,
        /** Building the SFU leg and joining the room. */
        CONNECTING,
        CONNECTED,
        /** Presence lost; media may still be flowing. */
        RECONNECTING,
        /** Room piena. */
        FULL,
        /** This month's video allowance is gone, no new call. */
        NO_ALLOWANCE,
        FAILED,
        ENDED
    }

    /** One tile on the call screen. */
    data class Member(
        val pid: String,
        val userId: String,
        val name: String,
        val picture: String?,
        val mic: Boolean = true,
        val cam: Boolean = true,
        val videoTrack: VideoTrack? = null,
        val isSelf: Boolean = false,
        val speaking: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _micEnabled = MutableStateFlow(true)
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(true)
    val cameraEnabled: StateFlow<Boolean> = _cameraEnabled.asStateFlow()

    /** Set when the picture was dropped perche' the allowance ran low. */
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /** Raised once when the month's usage crosses the warning line. */
    private val _allowanceWarning = MutableStateFlow(false)
    val allowanceWarning: StateFlow<Boolean> = _allowanceWarning.asStateFlow()

    /** The tile the user pinned full screen, if any. */
    private val _pinned = MutableStateFlow<String?>(null)
    val pinned: StateFlow<String?> = _pinned.asStateFlow()

    /** Reactions to animate, as (pid, emoji). Consumed by the call screen. */
    private val _reactions = MutableStateFlow<Pair<String, String>?>(null)
    val reactions: StateFlow<Pair<String, String>?> = _reactions.asStateFlow()

    @Volatile var roomId: String? = null
        private set

    /** True when this phone started the call: it re-keys and owns the invitations. */
    @Volatile var isHost = false
        private set

    val eglContext: EglBase.Context?
        get() = client?.eglContext

    val localVideoTrack: VideoTrack?
        get() = client?.localVideoTrack

    private var client: GroupRtcClient? = null
    private var room: CallRoomSocket? = null

    private var myPid: String = ""
    private var callKey: ByteArray? = null
    private var epoch: Int = 0

    /** Who was invited, so the host can re-key them when the roster changes. */
    private val invited = mutableSetOf<String>()

    /**
     * Who refused the invitation, including the phones that could not enter at all.
     * Only the host fills [invited], so only the host acts on this.
     *
     * Concurrent apposta: declines arrive on the FCM thread and are handled on
     * Dispatchers.Default, so two landing together would write the same set from two
     * threads.
     */
    private val declined: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var statsJob: Job? = null
    private var durationJob: Job? = null

    /**
     * Ends a call that nobody ever entered.
     *
     * The rule in [onLeft] cannot cover this: it needs [hadCompany], true only once
     * somebody is admitted. A host whose invitees all refuse, or whose phones never
     * answer, would otherwise sit alone with the camera on for MAX_CALL_DURATION_MS,
     * quattro ore.
     */
    private var lonelyJob: Job? = null
    private var startedAt = 0L

    private var pendingBytes = 0L

    /**
     * Whether anybody ever answered. Until they do, being alone in the room is normal,
     * the invitations are still ringing; afterwards it means the call is over and
     * nothing should keep the camera on.
     */
    private var hadCompany = false

    // ───────────────────────────────────────────────────────────── entry points

    fun isBusy(): Boolean = _status.value != Status.IDLE && _status.value != Status.ENDED

    /**
     * Starts a call and rings the given peers.
     *
     * The allowance is checked before anything is built: refusing here costs the user a
     * dialog, refusing halfway costs them a call that half connected.
     */
    fun startCall(context: Context, inviteeUserIds: List<String>, withVideo: Boolean) {
        if (isBusy()) {
            debugLine(TAG, "Already in a call, ignoring startCall")
            return
        }
        if (VideoUsageTracker.isExhausted()) {
            _status.value = Status.NO_ALLOWANCE
            return
        }

        // Set synchronously, before anything is launched: the service and the call
        // screen both read it the instant they start, and a status left over from the
        // previous call would make them close on sight.
        _status.value = Status.CONNECTING

        val key = CallCrypto.newKey()
        val id = guid()

        isHost = true
        invited.clear()
        invited.addAll(inviteeUserIds)

        scope.launch {
            if (!join(context, id, key, 0, withVideo)) return@launch

            // The invitation carries the key, sealed to each recipient by the FCM
            // sender. Unico posto where the key ever travels.
            val encoded = CallCrypto.encodeKey(key)
            for (userId in inviteeUserIds) {
                fcmSendInstant(
                    userId = userId,
                    content = id,
                    callId = id,
                    type = Notify.GROUP_CALL,
                    collapseKey = Notify.GROUP_CALL,
                    extraData = mapOf(
                        "gcKey" to encoded,
                        "gcEpoch" to "0",
                        "gcHost" to (MySelf.userId() ?: "")
                    )
                )
            }

            startLonelyGuard()
        }
    }

    /**
     * A peer said no, or could not come. Ignored unless it belongs to the call this
     * phone is actually hosting.
     */
    fun onDeclined(room: String, userId: String) {
        if (room != roomId || userId.isEmpty()) return
        scope.launch {
            declined.add(userId)
            endIfNobodyIsComing("everyone invited refused")
        }
    }

    /**
     * Closes a call that is still empty and has nothing left to wait for. Silent once
     * anybody has been admitted: from that moment the call is real and [onLeft] owns
     * its ending.
     */
    private fun endIfNobodyIsComing(reason: String) {
        if (hadCompany) return
        if (_members.value.any { !it.isSelf }) return
        if (invited.isEmpty() || !declined.containsAll(invited)) return
        debugLine(TAG, "Nobody is coming ($reason), ending the call")
        leave()
    }

    /**
     * Started once the invitations are out, ten seconds past the ring timeout, so a
     * phone that is merely slow to answer is never cut off by this.
     */
    private fun startLonelyGuard() {
        lonelyJob?.cancel()
        lonelyJob = scope.launch {
            delay(RING_TIMEOUT_MS + 10_000L)
            if (!hadCompany && _members.value.none { !it.isSelf }) {
                debugLine(TAG, "Nobody answered the invitation, ending the call")
                leave()
            }
        }
    }

    /** Seats still free in the room, so the UI can stop offering to fill them. */
    fun freeSeats(): Int =
        (GroupCallConfig.MAX_PARTICIPANTS - _members.value.size).coerceAtLeast(0)

    /** Everyone currently in the call, for a picker that must not offer them again. */
    fun presentUserIds(): List<String> =
        _members.value.map { it.userId }.filter { it.isNotEmpty() }

    /**
     * Rings more people into a call that is already running.
     *
     * They get the key as it stands right now, epoch included, so they decode what is
     * already in the air instead of waiting for the next rotation. Anyone in the call
     * can invite, not just the host: the key is in every participant's hands by
     * definition, so restricting it would buy no secrecy e costerebbe una cortesia.
     */
    fun invite(userIds: List<String>) {
        val id = roomId ?: return
        val key = callKey ?: return
        if (userIds.isEmpty()) return

        val encoded = CallCrypto.encodeKey(key)
        val currentEpoch = epoch
        val me = MySelf.userId() ?: ""

        invited.addAll(userIds)

        scope.launch {
            for (userId in userIds) {
                fcmSendInstant(
                    userId = userId,
                    content = id,
                    callId = id,
                    type = Notify.GROUP_CALL,
                    collapseKey = Notify.GROUP_CALL,
                    extraData = mapOf(
                        "gcKey" to encoded,
                        "gcEpoch" to currentEpoch.toString(),
                        "gcHost" to me
                    )
                )
            }
            debugLine(TAG, "Invited ${userIds.size} more into the call")
        }
    }

    /** Answers an invitation. The key arrived inside it. */
    fun joinCall(context: Context, callRoomId: String, encodedKey: String, keyEpoch: Int, withVideo: Boolean) {
        if (isBusy()) {
            debugLine(TAG, "Already in a call, ignoring joinCall")
            return
        }
        if (VideoUsageTracker.isExhausted()) {
            _status.value = Status.NO_ALLOWANCE
            return
        }

        _status.value = Status.CONNECTING

        val key = CallCrypto.decodeKey(encodedKey)
        if (key == null) {
            _status.value = Status.FAILED
            return
        }

        isHost = false
        invited.clear()
        scope.launch { join(context, callRoomId, key, keyEpoch, withVideo) }
    }

    private suspend fun join(
        context: Context,
        callRoomId: String,
        key: ByteArray,
        keyEpoch: Int,
        withVideo: Boolean
    ): Boolean {
        _status.value = Status.CONNECTING
        roomId = callRoomId
        callKey = key
        epoch = keyEpoch
        myPid = guid()
        startedAt = System.currentTimeMillis()
        pendingBytes = 0L
        _audioOnly.value = VideoUsageTracker.isAudioOnly()
        _pinned.value = null
        _micEnabled.value = true
        _cameraEnabled.value = withVideo && !_audioOnly.value

        val rtc = GroupRtcClient(context.applicationContext)
        rtc.listener = this
        client = rtc

        // Key in before the connection publishes anything: a frame that leaves before
        // the cryptor exists is a frame Cloudflare can read.
        rtc.setCallKey(key, keyEpoch)

        val ok = rtc.start(myPid, _cameraEnabled.value)
        if (!ok) {
            debugLine(TAG, "Could not start the SFU leg")
            _status.value = Status.FAILED
            cleanup()
            return false
        }

        val me = Member(
            pid = myPid,
            userId = MySelf.userId().orEmpty(),
            name = MySelf.name().orEmpty(),
            picture = MySelf.pictureUri(),
            mic = true,
            cam = _cameraEnabled.value,
            isSelf = true
        )
        _members.value = listOf(me)

        val socket = CallRoomSocket(callRoomId, this)
        room = socket
        socket.connect(
            RoomParticipant(
                pid = myPid,
                sessionId = rtc.sessionId.orEmpty(),
                audioTrack = rtc.audioTrackName,
                videoTrack = rtc.videoTrackName,
                // Sealed with the call key: the room relays it, solo la call lo legge.
                label = CallCrypto.seal(key, MySelf.userId().orEmpty()),
                mic = true,
                cam = _cameraEnabled.value
            )
        )

        _status.value = Status.CONNECTED
        startStatsLoop()
        startDurationGuard()
        return true
    }

    /**
     * Leaves the call. The others see the tile disappear, nothing else ends.
     *
     * Synchronised and idempotent because two departures can land at the same instant:
     * on 24 Aug both remaining participants left within seconds of each other, two
     * coroutines each decided the room was empty, and the bytes pending at that moment
     * were charged to the meter twice.
     */
    @Synchronized
    fun leave() {
        val state = _status.value
        if (state == Status.IDLE || state == Status.ENDED) return
        debugLine(TAG, "Leaving the call")

        val id = roomId
        val wasHost = isHost
        val peers = invited.toList()

        _status.value = Status.ENDED
        cleanup()

        // Cortesia: a host that walks away stops the ringing on phones that never
        // answered. It does not end the call for anyone already in it.
        if (wasHost && id != null) {
            scope.launch {
                for (userId in peers) {
                    fcmSendInstant(
                        userId = userId,
                        content = id,
                        callId = id,
                        type = Notify.GROUP_CALL_END,
                        collapseKey = Notify.GROUP_CALL_END
                    )
                }
            }
        }
    }

    @Synchronized
    private fun cleanup() {
        statsJob?.cancel(); statsJob = null
        durationJob?.cancel(); durationJob = null

        // Counted but not yet committed e' comunque money spent.
        if (pendingBytes > 0L) {
            VideoUsageTracker.addVideoBytes(pendingBytes)
            pendingBytes = 0L
        }

        room?.close(); room = null
        client?.release(); client = null

        lonelyJob?.cancel(); lonelyJob = null

        _members.value = emptyList()
        _reactions.value = null
        _pinned.value = null
        roomId = null
        callKey = null
        isHost = false
        hadCompany = false
        invited.clear()
        declined.clear()
    }

    /** Returns the manager to a state where a new call can start. */
    @Synchronized
    fun reset() {
        if (_status.value == Status.CONNECTED || _status.value == Status.CONNECTING) return
        cleanup()
        _status.value = Status.IDLE
    }

    // ──────────────────────────────────────────────────────────────── controls

    fun toggleMic() {
        val enabled = !_micEnabled.value
        _micEnabled.value = enabled
        client?.setMicEnabled(enabled)
        pushState()
    }

    fun toggleCamera() {
        if (_audioOnly.value) return
        val enabled = !_cameraEnabled.value
        _cameraEnabled.value = enabled
        client?.setCameraEnabled(enabled)
        updateSelf { it.copy(cam = enabled) }
        pushState()
    }

    fun switchCamera() = client?.switchCamera()

    fun sendReaction(emoji: String) {
        room?.sendReaction(emoji)
        _reactions.value = myPid to emoji
    }

    fun pin(pid: String?) {
        _pinned.value = if (_pinned.value == pid) null else pid
    }

    fun consumeReaction() {
        _reactions.value = null
    }

    fun consumeAllowanceWarning() {
        _allowanceWarning.value = false
    }

    private fun pushState() {
        room?.sendState(
            mic = _micEnabled.value,
            cam = _cameraEnabled.value,
            videoTrack = client?.videoTrackName.orEmpty()
        )
    }

    // ────────────────────────────────────────────────────── room events (presence)

    override fun onRoster(participants: List<RoomParticipant>) {
        scope.launch {
            for (p in participants) admit(p)
            _status.value = Status.CONNECTED
        }
    }

    override fun onJoined(participant: RoomParticipant) {
        scope.launch { admit(participant) }
    }

    private suspend fun admit(participant: RoomParticipant) {
        if (participant.pid == myPid) return
        if (_members.value.size >= GroupCallConfig.MAX_PARTICIPANTS) {
            debugLine(TAG, "Participant cap reached, not subscribing to ${participant.pid}")
            return
        }

        val key = callKey
        val userId = if (key != null) CallCrypto.open(key, participant.label).orEmpty() else ""
        val peer = if (userId.isNotEmpty()) {
            runCatching { getPeerDao(App.context()).getPeer(userId) }.getOrNull()
        } else null

        val member = Member(
            pid = participant.pid,
            userId = userId,
            name = peer?.name ?: "",
            picture = peer?.picture,
            mic = participant.mic,
            cam = participant.cam
        )

        _members.value = _members.value.filter { it.pid != participant.pid } + member
        hadCompany = true

        client?.subscribe(participant)
    }

    override fun onLeft(pid: String) {
        scope.launch {
            _members.value = _members.value.filter { it.pid != pid }
            client?.unsubscribe(pid)

            // Last one in the room after a real call: end it. Sitting alone with the
            // camera and microphone running is not a call, it is a leak, and on the
            // phone it shows up as an indicator that will not go away.
            if (hadCompany && _members.value.none { !it.isSelf }) {
                debugLine(TAG, "Everyone else has left, ending the call")
                leave()
                return@launch
            }

            // Whoever left keeps the key they were given, and the room has no door.
            // Re-keying is what actually removes them: a fresh key to everyone still
            // here, and their old one stops decrypting.
            if (isHost) rekey()
        }
    }

    override fun onState(pid: String, mic: Boolean, cam: Boolean, videoTrack: String) {
        _members.value = _members.value.map {
            if (it.pid == pid) it.copy(mic = mic, cam = cam) else it
        }
    }

    override fun onReaction(pid: String, emoji: String) {
        _reactions.value = pid to emoji
    }

    override fun onFull() {
        _status.value = Status.FULL
        cleanup()
    }

    override fun onDisconnected() {
        if (_status.value == Status.CONNECTED) _status.value = Status.RECONNECTING
    }

    // ────────────────────────────────────────────────────────── media callbacks

    override fun onRemoteVideo(pid: String, track: VideoTrack) {
        _members.value = _members.value.map {
            if (it.pid == pid) it.copy(videoTrack = track) else it
        }
    }

    override fun onRemoteAudio(pid: String, track: AudioTrack) {
        // Niente da attaccare: remote audio plays through the device's audio module as
        // soon as the track arrives.
    }

    override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                if (_status.value == Status.RECONNECTING) _status.value = Status.CONNECTED
            }
            PeerConnection.PeerConnectionState.FAILED -> _status.value = Status.FAILED
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                if (_status.value == Status.CONNECTED) _status.value = Status.RECONNECTING
            }
            else -> {}
        }
    }

    // ──────────────────────────────────────────────────────────────── re-keying

    /**
     * Issues a new call key to everyone still in the room after a departure.
     *
     * The sender keeps encrypting under the old key for a moment: the key ring holds
     * both, so a phone that gets the new one a second late still decodes the frames
     * arriving meanwhile, and nobody sees the picture freeze over housekeeping.
     */
    private fun rekey() {
        val key = CallCrypto.newKey()
        val next = epoch + 1
        val id = roomId ?: return
        val encoded = CallCrypto.encodeKey(key)

        val stillHere = _members.value.filter { !it.isSelf && it.userId.isNotEmpty() }.map { it.userId }
        if (stillHere.isEmpty()) return

        scope.launch {
            for (userId in stillHere) {
                fcmSendInstant(
                    userId = userId,
                    content = id,
                    callId = id,
                    type = Notify.GROUP_CALL_REKEY,
                    collapseKey = Notify.GROUP_CALL_REKEY,
                    extraData = mapOf("gcKey" to encoded, "gcEpoch" to next.toString())
                )
            }
            delay(1_500L)
            adoptKey(key, next)
        }
    }

    /** Installs a key that arrived from the host, or the one we just issued. */
    fun adoptKey(key: ByteArray, keyEpoch: Int) {
        if (keyEpoch <= epoch) return
        epoch = keyEpoch
        callKey = key
        client?.setCallKey(key, keyEpoch)
        debugLine(TAG, "Call key rotated to epoch $keyEpoch")
    }

    fun adoptKey(encodedKey: String, keyEpoch: Int) {
        val key = CallCrypto.decodeKey(encodedKey) ?: return
        adoptKey(key, keyEpoch)
    }

    // ─────────────────────────────────────────────────────── metering and limits

    /**
     * One second loop: it decides who is in the spotlight, and every fifteen seconds it
     * commits what the call has cost so far.
     *
     * Bytes go in batches rather than per reading because each commit writes
     * preferences, and a four hour call would write fourteen thousand times.
     */
    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var sinceCommit = 0L
            while (isActive) {
                delay(1_000L)
                val snapshot = client?.pollStats() ?: continue

                applySpeaking(snapshot.audioLevels)

                pendingBytes += snapshot.bytesDelta
                sinceCommit += 1_000L

                if (sinceCommit >= GroupCallConfig.USAGE_POLL_MS) {
                    sinceCommit = 0L
                    if (pendingBytes > 0L) {
                        VideoUsageTracker.addVideoBytes(pendingBytes)
                        pendingBytes = 0L
                    }
                    enforceAllowance()
                }
            }
        }
    }

    private fun applySpeaking(levels: Map<String, Double>) {
        // Bar basso apposta: this promotes a tile, it does not decide anything, and a
        // talker who goes quiet for a moment should not flicker out of the spotlight.
        val loudest = levels.maxByOrNull { it.value }?.takeIf { it.value > 0.02 }?.key
        val current = _members.value
        if (current.none { it.speaking } && loudest == null) return
        _members.value = current.map { it.copy(speaking = it.pid == loudest) }
    }

    /**
     * Applies the monthly allowance to a call in progress.
     *
     * Running out never hangs up: the picture goes and the voices stay. Audio costs
     * about a thirtieth of video, and a call that survives is worth more than a tile
     * that looks right.
     */
    private fun enforceAllowance() {
        if (VideoUsageTracker.consumeWarning()) _allowanceWarning.value = true

        if (!_audioOnly.value && VideoUsageTracker.isAudioOnly()) {
            debugLine(TAG, "Allowance nearly spent, dropping to audio only")
            _audioOnly.value = true
            _cameraEnabled.value = false
            client?.setCameraEnabled(false)
            updateSelf { it.copy(cam = false) }
            pushState()
        }
    }

    /** The four-hour backstop, matching the room's own lifetime in the Worker. */
    private fun startDurationGuard() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                if (System.currentTimeMillis() - startedAt >= GroupCallConfig.MAX_CALL_DURATION_MS) {
                    debugLine(TAG, "Call reached its maximum duration, ending")
                    leave()
                    return@launch
                }
            }
        }
    }

    private fun updateSelf(transform: (Member) -> Member) {
        _members.value = _members.value.map { if (it.isSelf) transform(it) else it }
    }

    // ───────────────────────────────────────────────────────────────── gating

    /**
     * Whether this device may start or join a group call at all.
     *
     * Both tiers can, apposta: a trial user who cannot try the feature never becomes a
     * paying one. What separates them is the size of the allowance, not the door.
     */
    fun canUseGroupCalls(context: Context): Boolean =
        BillingManager.hasAccess(context) && !VideoUsageTracker.isExhausted()
}
