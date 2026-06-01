package com.bolimot.mindtheclub.voip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bolimot.mindtheclub.functions.debugLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID)

        if (callId == null) {
            debugLine("CallActionReceiver", "Call ID is null, cannot process action.")
            return
        }

        when (intent.action) {
            ACTION_ANSWER -> {
                debugLine("CallActionReceiver", "Answer action received for callId: $callId")
                scope.launch {
                    ManagedTelecom.acceptPendingCall(callId, context.applicationContext)
                }
            }
            ACTION_DECLINE -> {
                debugLine("CallActionReceiver", "Decline action received for callId: $callId")
                ManagedTelecom.declinePendingCall(callId)
            }
        }

        val notificationManager = CallNotificationManager(context)
        notificationManager.dismissCallNotification()
    }

    companion object {
        const val ACTION_ANSWER = "com.bolimot.mindtheclub.voip.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.bolimot.mindtheclub.voip.ACTION_DECLINE"
        const val EXTRA_CALL_ID = "EXTRA_CALL_ID"
        const val EXTRA_TERMINATE_ON_END = "EXTRA_TERMINATE_ON_END"
    }
}
