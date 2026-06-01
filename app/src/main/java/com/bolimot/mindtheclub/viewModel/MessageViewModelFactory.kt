package com.bolimot.mindtheclub.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bolimot.mindtheclub.database.message.MessageRepository

class MessageViewModelFactory(
    private val application: Application,
    private val messageRepository: MessageRepository,
    private val myUserId: String,
    private val remoteUserId: String
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MessageViewModel::class.java)) {
            return MessageViewModel(application, messageRepository, myUserId, remoteUserId) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class")
    }
}