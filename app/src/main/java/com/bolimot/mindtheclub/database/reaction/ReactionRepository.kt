package com.bolimot.mindtheclub.database.reaction

class ReactionRepository(private val reactionDao: ReactionDao) {

    suspend fun insertReaction(reaction: Reaction) {
        reactionDao.insert(reaction)
    }

    suspend fun deleteReaction(messageId: String) {
        reactionDao.deleteReaction(messageId)
    }

    suspend fun getEmoji(messageId: String): String? {
        return reactionDao.getEmoji(messageId)
    }

    fun exists(messageId: String): Boolean {
        return reactionDao.exists(messageId)
    }
}


