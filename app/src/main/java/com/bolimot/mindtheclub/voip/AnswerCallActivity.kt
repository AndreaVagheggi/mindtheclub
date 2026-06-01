package com.bolimot.mindtheclub.voip

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.functions.debugLine
import kotlinx.coroutines.launch

class AnswerCallActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugLine("AnswerCallActivity", "Activity created to handle answer action.")

        val callId = intent.getStringExtra(CallActionReceiver.EXTRA_CALL_ID)

        if (callId == null) {
            debugLine("AnswerCallActivity", "Call ID is null, cannot process action. Finishing.")
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                debugLine("AnswerCallActivity", "Handling answer for callId: $callId")
                ManagedTelecom.acceptPendingCall(callId, applicationContext)
            } finally {
                val notificationManager = CallNotificationManager(applicationContext)
                notificationManager.dismissCallNotification()
                finish()
            }
        }
    }
}
