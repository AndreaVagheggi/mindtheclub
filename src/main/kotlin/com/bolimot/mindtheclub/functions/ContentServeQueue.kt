package com.bolimot.mindtheclub.functions

/**
 * Sender side serving discipline for group content: one active transfer per content, everyone
 * else in coda.
 *
 * The sendMe guard is keyed per content AND target, so recovery requests from DIFFERENT members
 * each opened their own DispatchWorker: six requesters meant six concurrent uploads slicing the
 * same uplink, nobody completed, and the gossip cascade (which needs one COMPLETE copy to
 * engage) never started (15 Aug: a 1137 chunk album from a holiday network kept six members at
 * partial copies for hours). This queue makes the origin serve one member to completion; the
 * first complete copy then relays onward and registers itself in the delivery doc, so the queue
 * drains sideways as well.
 *
 * In memory apposta: if the process dies the claim dies with it, and the requesters' own retry
 * ladders re-ask minutes later. Persisting claims would trade that benign delay for the risk of
 * a stale lock surviving a crash.
 *
 * When a transfer finishes, success or give up, the caller pops the next queued member and sends
 * it a tiny `pending` FCM: the member re-asks through the existing reactive path and finds the
 * lock free. No new protocol, no new serving code, just a well timed invitation.
 */
object ContentServeQueue {

    private class State(
        var servingTarget: String,
        var touchedAt: Long,
        // target -> announce payload ("msgId#chatGroupId") to invite it with later.
        val queue: LinkedHashMap<String, String> = LinkedHashMap()
    )

    private val states = HashMap<String, State>()

    // A serving claim not refreshed for this long counts as dead (worker killed, process
    // replaced). Live DispatchWorker runs refresh it on every attempt, cooldown deferrals
    // included, so a running retry ladder never goes stale.
    private const val STALE_MS = 6 * 60_000L

    private const val MAX_QUEUE = 12

    /** Content identity for the queue; null means "not group content, no discipline needed". */
    fun contentIdOf(chatGroupId: String?, originalSenderId: String?, date: Long): String? =
        if (chatGroupId.isNullOrEmpty() || originalSenderId.isNullOrEmpty() || date <= 0L) null
        else "${chatGroupId}_${originalSenderId}_$date"

    /**
     * Ask to serve [target] now. True: go ahead, the claim is yours (new, renewed, or taken over
     * from a stale one). False: another member's transfer is active, [target] has been queued
     * and will be invited when the line moves.
     */
    @Synchronized
    fun admit(contentId: String, target: String, announceKey: String): Boolean {
        val now = System.currentTimeMillis()
        val s = states[contentId]
        if (s == null || s.servingTarget == target || now - s.touchedAt > STALE_MS) {
            val carriedQueue = s?.queue ?: LinkedHashMap()
            carriedQueue.remove(target)
            states[contentId] = State(target, now, carriedQueue)
            return true
        }
        if (s.queue.size < MAX_QUEUE) s.queue[target] = announceKey
        return false
    }

    /**
     * Claim the content for [target] only if nobody holds it (or the holder is stale); refresh
     * the claim when [target] already holds it. Never queues and never steals a live claim: used
     * by DispatchWorker at the start of every run so that transfers submitted outside the sendMe
     * path (the initial group dispatch) are visible to the queue too.
     */
    @Synchronized
    fun claimIfFree(contentId: String, target: String) {
        val now = System.currentTimeMillis()
        val s = states[contentId]
        if (s == null || s.servingTarget == target || now - s.touchedAt > STALE_MS) {
            val carriedQueue = s?.queue ?: LinkedHashMap()
            carriedQueue.remove(target)
            states[contentId] = State(target, now, carriedQueue)
        }
    }

    /** Who is being served right now, for log lines only. */
    @Synchronized
    fun servingTargetOf(contentId: String): String? = states[contentId]?.servingTarget

    /**
     * Release the claim held by [target] and hand back the next queued member with its announce
     * payload, or null when nothing is queued (or the claim belongs to somebody else, in which
     * case nothing is touched).
     *
     * The claim passes to the invited member right away, so the rest of the line survives while
     * the invitation (a pending FCM and the sendMe it triggers) is in flight. If the invitee
     * never shows up, the stale window lets anyone take over.
     */
    @Synchronized
    fun finish(contentId: String, target: String): Pair<String, String>? {
        val s = states[contentId] ?: return null
        if (s.servingTarget != target) return null
        val next = s.queue.entries.firstOrNull()
        if (next == null) {
            states.remove(contentId)
            return null
        }
        s.queue.remove(next.key)
        s.servingTarget = next.key
        s.touchedAt = System.currentTimeMillis()
        return next.key to next.value
    }
}
