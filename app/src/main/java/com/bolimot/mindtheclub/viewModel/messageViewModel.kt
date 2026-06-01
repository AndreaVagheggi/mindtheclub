package com.bolimot.mindtheclub.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageRepository
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest

class MessageViewModel(
    application: Application,
    private val repository: MessageRepository,
    myUserId: String,
    remoteUserId: String
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val messages: Flow<PagingData<Message>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) {
                repository.getMessages(myUserId, remoteUserId)
            } else {
                repository.getFilteredMessages(myUserId, remoteUserId, query)
            }
        }
        .cachedIn(viewModelScope)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val _newMessageOutEvent = MutableLiveData<Message?>()
    private val _newMessageInEvent = MutableLiveData<Message?>()

    val statusUpdate = MutableLiveData<Pair<String, String>>()
    val reactionUpdate = MutableLiveData<Pair<String, String>>()

    suspend fun insert(message: Message): Boolean {
        return withContext(Dispatchers.IO) {
            val success: Boolean = if (message.groupId.isNotEmpty() && message.type != Type.MULTIPLE_IMAGES) {
                true
            } else {
                repository.saveMessage(message, messageIn = false)
            }

            withContext(Dispatchers.Main) {
                if (success) _newMessageOutEvent.value = message
            }

            success
        }
    }

    suspend fun getDistinctMessageDays(myUserId: String, remoteUserId: String): List<Pair<String, Long>> {
        return withContext(Dispatchers.IO) {
            val allDates = repository.getAllMessageDates(myUserId, remoteUserId)
            val grouped = mutableListOf<Pair<String, Long>>()
            var lastLocalDate: LocalDate? = null
            val today = LocalDate.now(ZoneId.systemDefault())
            val formatter = DateTimeFormatter.ofPattern("dd MMMM yy", Locale.getDefault())

            for (timestamp in allDates) {
                val localDate = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                if (localDate != lastLocalDate) {
                    val displayText = if (localDate == today) {
                        App.context().getString(R.string.today)
                    } else {
                        localDate.format(formatter)
                    }
                    val dayStartMillis = localDate
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    grouped.add(Pair(displayText, dayStartMillis))
                    lastLocalDate = localDate
                }
            }
            grouped
        }
    }

    suspend fun getPositionForDate(myUserId: String, remoteUserId: String, timestamp: Long): Int {
        return withContext(Dispatchers.IO) {
            repository.countMessagesNewerThan(myUserId, remoteUserId, timestamp)
        }
    }


    fun incomingMessage(message: Message){
        debugLine("incomingMessage", "posting newMessageInEvent")
        _newMessageInEvent.postValue(message)
    }

    suspend fun deleteMessages(messages: List<Message>): Boolean {
        return repository.deleteMessages(messages)
    }

    suspend fun getMessagesByIds(messageIds: List<String>): List<Message> {
        val messagesList = mutableListOf<Message>()

        for(messageId in messageIds){
            val message = repository.getMessage(messageId)
            if(message != null){
                messagesList.add(message)
            }
        }

        return messagesList
    }

    fun getNewMessageOutEvent(): LiveData<Message?> = _newMessageOutEvent
    fun getNewMessageInEvent(): LiveData<Message?> = _newMessageInEvent

    suspend fun updateStatus(messageId: String, status: String): Boolean {
        return withContext(Dispatchers.IO) {
            val result = repository.updateStatus(messageId, status)
            if (result) {
                withContext(Dispatchers.Main) { notifyStatusUpdate(messageId, status) }
            }
            result
        }
    }

    suspend fun sendSeenNotificationForUnseenMessages(remoteUserId: String){
        withContext(Dispatchers.IO) {
            repository.sendSeenNotificationForUnseenMessages(remoteUserId)
        }
    }

    suspend fun updateReaction(messageId: String, emoji: String): Boolean {
        val result = withContext(Dispatchers.IO) {
            repository.updateReaction(messageId, emoji)
        }
        if (result) {
            notifyReactionUpdate(messageId, emoji)
        }
        return result
    }

    private suspend fun notifyStatusUpdate(messageId: String, status: String) {
        withContext(Dispatchers.Main) {
            statusUpdate.value = Pair(messageId, status)
            debugLine("notifyStatusUpdate", "Status changed to: $status")
        }
    }

    private suspend fun notifyReactionUpdate(messageId: String, emoji: String) {
        withContext(Dispatchers.Main) {
            reactionUpdate.value = Pair(messageId, emoji)
            debugLine("notifyReactionUpdate", "Reaction changed to: $emoji")
        }
    }

    suspend fun getMessage(messageId: String): Message? {
        return repository.getMessage(messageId)
    }

    suspend fun isProfile(messageId: String): Boolean {
        return repository.getMessage(messageId)?.type == Type.PROFILE
    }
}
