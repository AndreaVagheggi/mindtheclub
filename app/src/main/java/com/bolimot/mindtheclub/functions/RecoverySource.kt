package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.sending.computeDeliveryDocId
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picks who to ask for the missing chunks of a stalled group content.
 *
 * Preference order, and nothing else:
 *  1. a member that registered a COMPLETE copy in the delivery doc (it can serve right now, and
 *     it is usually closer than the origin);
 *  2. [fallback], the peer the caller would have asked anyway (original sender or announcer).
 *
 * This replaces the blind member rotation removed on 15 Aug: asking members that provably hold
 * nothing produced only allMissing noise, and the inherited round counters made round 0, the only
 * informed guess, almost unreachable. Here the choice is informed or it does not happen: niente
 * contatori, niente stato, one bounded Firestore read with a hard timeout, and any failure
 * degrades to the exact pre 1.30 behaviour.
 *
 * Callers in periodic workers cap their lookups per pass: a pathological pile of stalled contents
 * must never turn the pass into a Firestore crawl (the mistake that sank the 15 Aug rotation).
 */
suspend fun pickRecoverySource(
    chatGroupId: String?,
    originalSenderId: String?,
    date: Long,
    fallback: String
): String {
    if (chatGroupId.isNullOrEmpty() || originalSenderId.isNullOrEmpty() || date <= 0L) return fallback
    return try {
        val docId = computeDeliveryDocId(chatGroupId, originalSenderId, date)
        val doc = withTimeoutOrNull(5_000L) {
            Firebase.firestore.collection("groupDelivery").document(docId).get().await()
        }
        if (doc == null) {
            // Distinguishable from "map empty" apposta: on 16 Aug a seeder registered six
            // minutes earlier was not found, and the silent fallback made the two cases, slow
            // network and genuinely no seeder, impossible to tell apart from the logs.
            debugLine("RecoverySource", "Seeder lookup timed out for $docId, falling back to $fallback")
            return fallback
        }
        if (!doc.exists()) return fallback
        @Suppress("UNCHECKED_CAST")
        val complete = doc.get("complete") as? Map<String, Boolean> ?: emptyMap()
        val myId = MySelf.userId()
        val seeders = complete.filterValues { it }.keys.filter { it != myId }
        if (seeders.isEmpty()) {
            // fromCache=true means Firestore answered from its local copy, which may predate a
            // seeder's registration (Romy, 16 Aug: doc cached at 09:55, Noemi's flag written
            // 09:56, lookup at 10:02 saw the stale snapshot). The fallback stays correct either
            // way, this line only makes the reason visible.
            debugLine("RecoverySource", "No complete member known for $docId (fromCache=${doc.metadata.isFromCache}), falling back to $fallback")
            return fallback
        }
        // Random among seeders spreads the load when several exist.
        val pick = seeders.random()
        if (pick != fallback) {
            debugLine("RecoverySource", "Seeder for $docId: $pick (${seeders.size} complete), asking it instead of $fallback")
        }
        pick
    } catch (e: Exception) {
        debugLine("RecoverySource", "Seeder lookup failed, falling back to $fallback: ${e.message}")
        fallback
    }
}
