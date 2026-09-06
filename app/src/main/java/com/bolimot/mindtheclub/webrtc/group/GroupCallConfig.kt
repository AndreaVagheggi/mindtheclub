package com.bolimot.mindtheclub.webrtc.group

/**
 * Every number that decides what a group call costs, in one place.
 *
 * The economics behind them: one participant sends one stream and receives N-1, so an eight
 * person call moves about 12 GB/hour in total and roughly 1.5 GB/hour per phone. Metering is
 * therefore per device, each phone counting its own SFU bytes against its own allowance, which
 * is what makes the feature impossible to abuse by dragging cost onto somebody else's meter.
 */
object GroupCallConfig {

    /** Participant cap. A cost decision, not a technical limit; the worker enforces it too. */
    const val MAX_PARTICIPANTS = 8

    /**
     * Monthly video allowance for an active subscription (mtc_standard): nessuna.
     *
     * Somebody who is paying for the app is never told to stop talking. What still bounds the
     * spend is RELAY_CAP_BYTES in RelayUsageTracker, which every video byte also counts against,
     * and DAILY_SFU_BUDGET in the mtc-sfu worker.
     */
    const val ALLOWANCE_SUBSCRIBED_BYTES = Long.MAX_VALUE

    /** Monthly video allowance during the free trial. Enough to actually try the feature. */
    const val ALLOWANCE_TRIAL_BYTES = 500L * 1_000_000L

    /** Fraction of the allowance at which the user is warned once. */
    const val WARN_AT = 0.80

    /**
     * Fraction at which video is dropped and the call carries on in audio, about thirty times
     * cheaper. La chiamata non si taglia mai a meta' frase: running out of allowance degrades
     * the picture, it does not hang up.
     */
    const val AUDIO_ONLY_AT = 0.90

    /** Hard lifetime of a call, matching ROOM_TTL_MS in the mtc-sfu worker. */
    const val MAX_CALL_DURATION_MS = 4L * 60 * 60 * 1000

    /** How often each phone reads its own transport counters from WebRTC. */
    const val USAGE_POLL_MS = 15_000L

    /**
     * Frame level encryption of the media. The SFU terminates the transport encryption on each
     * leg, so without this Cloudflare could see the picture; with it the relay forwards sealed
     * frames exactly as TURN forwards sealed packets today, which is the property the whole app
     * is sold on.
     *
     * A flag because media plumbing and crypto must never be debugged at the same time: turn it
     * off to isolate a connectivity problem, mai per spedire.
     */
    const val E2EE_ENABLED = true

    /** Capture size for group calls. Lower than a 1:1 call: N-1 encodes, N-1 decodes. */
    const val CAPTURE_WIDTH = 640
    const val CAPTURE_HEIGHT = 360
    const val CAPTURE_FPS = 24

    /** Capture size on a low-end device. */
    const val CAPTURE_WIDTH_LOW = 320
    const val CAPTURE_HEIGHT_LOW = 180
    const val CAPTURE_FPS_LOW = 15
}
