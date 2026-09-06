package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.firebase.fcmSendInstant
import com.bolimot.mindtheclub.tools.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks a few group members whether they are awake, before choosing who to send to.
 *
 * The member list carries an "available" flag, but that flag is a RESERVATION held in
 * Firestore, not a statement about reachability, and nothing on this device can tell the
 * difference. On 22 Aug a 444 chunk video went to a phone that had been switched off: the
 * sender had seen that phone alive 39 seconds earlier, at 11:32:20, in a groupSeen. Nessuna
 * euristica storica could have avoided it. It then spent 10m52s discovering the truth, three
 * WebRTC attempts with their timeouts and cooldowns, and only at 11:43:51 walked to the next
 * member, who took the whole file in 25 seconds.
 *
 * That discovery is not cheap either: those eleven minutes cost five high priority dataCall
 * FCMs, four Cloudflare signalling rooms and four ICE fetches. A ping and a pong are two small
 * FCMs and nothing else, so asking first is cheaper than finding out the hard way, in the worst
 * case as well as the common one.
 *
 * The numbers come from measurements, not from taste. Reaction time between an incoming FCM and
 * the answer actually leaving was 1, 1, 1 and 3 seconds across the phones on 22 Aug; flight
 * time around 2 seconds; a phone that had just cold started after a reboot was sending again 5
 * seconds later. So a round trip sits between 4 and 9 seconds, and what can stretch it is not
 * the network but App Check: a stale token makes the pong wait for a Play Integrity
 * attestation.
 *
 * Il silenzio non e' mai un verdetto. A member that does not answer keeps its place in the list
 * and can still be chosen, which matters because a peer on an older build has no PONG branch at
 * all and would otherwise be shut out for ever, and because a pong needs an App Check token, so
 * a phone that receives perfectly well can still fail to answer.
 */
object PeerProbe {

    private const val TAG = "PeerProbe"

    /** Never interrogate a whole group: one live member is all the caller needs. */
    const val MAX_PROBES = 5

    /**
     * Gap before pinging the next candidate. Chosen from the measured reaction time above: a
     * genuinely awake peer usually answers inside it, so the second ping is most often never
     * sent at all.
     */
    const val STAGGER_MS = 3_000L

    /** Whole probe budget. Past this the caller proceeds exactly as it did before. */
    const val DEADLINE_MS = 20_000L

    private const val POLL_MS = 250L

    /** When each peer last proved itself awake by answering a ping. */
    private val lastPong = ConcurrentHashMap<String, Long>()

    /** Called from the PONG branch of the FCM service. */
    fun onPong(userId: String) {
        if (userId.isEmpty()) return
        lastPong[userId] = System.currentTimeMillis()
    }

    /**
     * Returns [candidates] with a member that answered a ping moved to the front, or the list
     * untouched when nobody answers inside [DEADLINE_MS].
     *
     * A reordering and not a filter, apposta: the caller keeps exactly the same set of choices
     * it had, so a probe that fails for any reason can only cost time, never a delivery.
     */
    suspend fun preferLive(candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates

        val probed = candidates.take(MAX_PROBES)
        val startedAt = System.currentTimeMillis()

        // Pings are fired into their own scope and never awaited: sendFcmMessage retries three
        // times against a 30 second http timeout, so waiting for one would blow the whole
        // stagger on a single unreachable peer.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            var sent = 0
            var lastPingAt = 0L

            while (System.currentTimeMillis() - startedAt < DEADLINE_MS) {
                val now = System.currentTimeMillis()

                if (sent < probed.size && (sent == 0 || now - lastPingAt >= STAGGER_MS)) {
                    val peer = probed[sent]
                    sent++
                    lastPingAt = now
                    debugLine(TAG, "Ping $sent/${probed.size} to $peer")
                    scope.launch {
                        fcmSendInstant(peer, "ping", "NotACall", Notify.PING, Notify.PING)
                    }
                }

                // Only answers arriving AFTER this probe began count: a pong left over from an
                // earlier round says nothing about right now.
                val answered = probed.firstOrNull { (lastPong[it] ?: 0L) >= startedAt }
                if (answered != null) {
                    val took = (System.currentTimeMillis() - startedAt) / 1000
                    debugLine(TAG, "Pong from $answered after ${took}s, electing it (pings sent: $sent)")
                    return listOf(answered) + candidates.filterNot { it == answered }
                }

                delay(POLL_MS)
            }
        } finally {
            scope.cancel()
        }

        debugLine(TAG, "No pong from ${probed.size} candidate(s) in ${DEADLINE_MS / 1000}s, keeping the original order")
        return candidates
    }
}
