package com.bolimot.mindtheclub.functions

/**
 * When this device last did something towards SENDING.
 *
 * Exists because DataSyncService decides whether to keep the wake lock and the
 * foreground notification by counting rows arriving in the inbox, so a phone
 * that is only transmitting looks perfectly still to it. In a group that is the
 * normal case, not an edge one: whoever receives first immediately becomes the
 * one relaying onwards.
 *
 * The first attempt at this asked WorkManager whether a DispatchWorker was
 * RUNNING, and it never once returned true. On 21 Aug the relay was submitted at
 * 16:14:31, the service gave up at 16:14:36, and the worker actually started at
 * 16:14:38: for those five seconds the work sat ENQUEUED, which is precisely the
 * window that matters. The phone was then frozen for 28 minutes and the video
 * took half an hour to reach the second member and an hour to reach the third.
 * Widening the query to ENQUEUED would have been worse: retries waiting out a
 * backoff are permanently enqueued, so the service would have stayed up almost
 * always.
 *
 * So this does not infer activity from a state machine, it records the activity
 * itself: the dispatch pipeline stamps the clock here when work is submitted and
 * again on every chunk that leaves. Held in memory on purpose. It is one
 * assignment per chunk with no I/O, and if the process dies the mark dies with
 * it, which is the correct answer anyway.
 */
object OutgoingActivity {

    @Volatile
    private var lastActivityAt = 0L

    /**
     * How long a mark stays meaningful. Long enough to bridge the submission of a
     * dispatch and its first chunk, and the pauses between batches; short enough
     * that a transfer which has genuinely stopped releases the phone within a
     * minute rather than pinning it for the whole sync window.
     */
    const val FRESH_MS = 60_000L

    /** Called from the dispatch pipeline. Deliberately trivial. */
    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    /** True when something went out recently enough to count as still sending. */
    fun isSending(): Boolean {
        val last = lastActivityAt
        return last > 0L && System.currentTimeMillis() - last < FRESH_MS
    }

    /** Seconds since the last outgoing sign of life, for log lines. */
    fun secondsSinceLast(): Long {
        val last = lastActivityAt
        return if (last <= 0L) -1L else (System.currentTimeMillis() - last) / 1000
    }
}
