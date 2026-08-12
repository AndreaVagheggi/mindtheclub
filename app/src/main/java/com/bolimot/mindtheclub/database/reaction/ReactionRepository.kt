package com.bolimot.mindtheclub.database.reaction

class ReactionRepository(private val reactionDao: ReactionDao) {

    /**
     * Stores one member's reaction to [messageId] and returns the message's new pill caption,
     * or null when nothing changed and the caller has no redraw to do.
     *
     * An empty [emoji] removes that member's reaction, which is the toggle performed when you tap
     * the emoji you already picked. Reactions travel the group as gossip, so the same one can come
     * back after it was superseded: an update older than the row already stored for that member is
     * dropped, making the newest tap per (message, member) the one that wins.
     */
    suspend fun applyReaction(
        messageId: String,
        reactorUserId: String,
        emoji: String,
        date: Long
    ): String? {
        val existing = reactionDao.getReaction(messageId, reactorUserId)

        if (existing != null && date < existing.date) return null

        if (emoji.isEmpty()) {
            if (existing == null) return null
            reactionDao.deleteReaction(messageId, reactorUserId)
        } else {
            if (existing != null && existing.emoji == emoji) return null
            reactionDao.insert(
                Reaction(
                    uid = 0,
                    messageId = messageId,
                    reactorUserId = reactorUserId,
                    emoji = emoji,
                    date = date
                )
            )
        }

        return reactionDao.getReactions(messageId).toPillText()
    }

    suspend fun getReactions(messageId: String): List<Reaction> {
        return reactionDao.getReactions(messageId)
    }

    /** The emoji this device's user put on [messageId], or null if they have not reacted. */
    suspend fun getMyEmoji(messageId: String, myUserId: String): String? {
        return reactionDao.getReaction(messageId, myUserId)?.emoji
    }

    suspend fun deleteReactions(messageId: String) {
        reactionDao.deleteReactions(messageId)
    }
}
