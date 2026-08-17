package com.bolimot.mindtheclub.database.reaction

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getReactionRepository
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.viewModel.ViewModelProviderHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for reacting, wherever the reaction comes from.
 *
 * Reactions live one row per member in the Reaction table. `Message.reaction` is kept alongside as
 * a denormalised caption of those rows, so the chat list can draw a bubble's pill without a second
 * query per row while scrolling. Everything that changes a reaction goes through [apply], which is
 * what keeps the two in step.
 */
object ReactionManager {

    /**
     * Applies [emoji] on behalf of [reactorUserId] and refreshes the cached caption plus the open
     * chat. An empty [emoji] removes that member's reaction.
     */
    suspend fun apply(
        messageId: String,
        reactorUserId: String,
        emoji: String,
        date: Long
    ) {
        val pill = withContext(Dispatchers.IO) {
            try {
                val summary = getReactionRepository(App.context())
                    .applyReaction(messageId, reactorUserId, emoji, date)

                if (summary != null) {
                    getMessageDao(App.context()).updateReaction(messageId, summary)
                }
                summary
            } catch (e: Exception) {
                debugLine("ReactionManager", "apply failed for $messageId: ${e.message}")
                null
            }
        } ?: return

        debugLine("ReactionManager", "Reaction by $reactorUserId on $messageId -> pill '$pill'")

        withContext(Dispatchers.Main) {
            ViewModelProviderHolder.messageViewModel?.notifyReactionUpdate(messageId, pill)
        }
    }

    /** Reactions on [messageId], oldest first. */
    suspend fun reactionsOf(messageId: String): List<Reaction> {
        return withContext(Dispatchers.IO) {
            getReactionRepository(App.context()).getReactions(messageId)
        }
    }

    /** The emoji this device's user put on [messageId], or null if they have not reacted. */
    suspend fun myEmoji(messageId: String): String? {
        val myUserId = MySelf.userId() ?: return null
        return withContext(Dispatchers.IO) {
            getReactionRepository(App.context()).getMyEmoji(messageId, myUserId)
        }
    }
}
