package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.hasNetworkAvailable
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AppCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        debugLine("AppCheckWorker", "Worker starting verification.")

        if (!hasNetworkAvailable(applicationContext)) {
            debugLine("AppCheckWorker", "No network, skipping verification to save quota.")
            return Result.retry()
        }

        return suspendCancellableCoroutine { continuation ->
            val appCheck = FirebaseAppCheck.getInstance()
            appCheck.getAppCheckToken(false)
            .addOnSuccessListener {
                debugLine("AppCheckWorker", "Token success.")
                if (continuation.isActive) {
                    continuation.resume(Result.success())
                }
            }
            .addOnFailureListener { exception ->
                debugLine("AppCheckWorker", "Token failed: ${exception.message}")
                if (continuation.isActive) {
                    continuation.resume(Result.success())
                }
            }
        }
    }
}