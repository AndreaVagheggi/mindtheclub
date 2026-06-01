package com.bolimot.mindtheclub.viewModel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.bolimot.mindtheclub.database.reaction.Reaction
import com.bolimot.mindtheclub.database.reaction.ReactionRepository

class ReactionViewModel(private val repository: ReactionRepository) : ViewModel() {
    val reactionReceived = MutableLiveData<String>()

    suspend fun insertReaction(reaction: Reaction) {
        repository.insertReaction(reaction)
        reactionReceived.postValue(reaction.messageId)
    }

    suspend fun deleteReaction(messageId: String) {
        repository.deleteReaction(messageId)
    }

    suspend fun getEmoji(messageId: String): String? {
        return repository.getEmoji(messageId)
    }
}

