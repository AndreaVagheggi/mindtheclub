package com.bolimot.mindtheclub.tools

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bolimot.mindtheclub.dataModels.NotifyMessageData

object MessageNotifier {
    private val _fromUserIdLiveData = MutableLiveData<NotifyMessageData>()
    val fromUserIdLiveData: LiveData<NotifyMessageData> get() = _fromUserIdLiveData

    fun notifyFromUserId(fromUserId: String, messageId: String) {
        _fromUserIdLiveData.postValue(NotifyMessageData(fromUserId, messageId))
    }
}
