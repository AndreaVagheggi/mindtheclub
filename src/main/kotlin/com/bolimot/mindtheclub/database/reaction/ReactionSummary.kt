package com.bolimot.mindtheclub.database.reaction

/** One emoji bucket of a message's reactions: what the bubble pill draws and what the sheet counts. */
data class ReactionGroup(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)

/** Distinct emojis the bubble pill shows before the rest are represented by the count alone. */
private const val MAX_PILL_EMOJIS = 3

/**
 * Buckets ordered the way the pill and the sheet tabs read best: most popular first, ties broken
 * by whoever reacted first.
 */
fun List<Reaction>.groupByEmoji(myUserId: String?): List<ReactionGroup> {
    val firstSeen = HashMap<String, Long>()
    forEach { reaction -> firstSeen.merge(reaction.emoji, reaction.date, ::minOf) }

    return groupBy { it.emoji }
        .map { (emoji, rows) ->
            ReactionGroup(
                emoji = emoji,
                count = rows.size,
                reactedByMe = rows.any { it.reactorUserId == myUserId }
            )
        }
        .sortedWith(
            compareByDescending<ReactionGroup> { it.count }
                .thenBy { firstSeen[it.emoji] ?: 0L }
        )
}

/**
 * Caption of the bubble pill: up to [MAX_PILL_EMOJIS] distinct emojis, followed by the number of
 * reactors once more than one member has reacted. Empty when nobody has, which is what hides it.
 */
fun List<Reaction>.toPillText(): String {
    if (isEmpty()) return ""

    val emojis = groupByEmoji(null).take(MAX_PILL_EMOJIS).joinToString("") { it.emoji }
    return if (size > 1) "$emojis $size" else emojis
}
