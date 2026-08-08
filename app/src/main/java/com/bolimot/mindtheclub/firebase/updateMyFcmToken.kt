package com.bolimot.mindtheclub.firebase

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import com.bolimot.mindtheclub.crypto.KeyManager

suspend fun updateMyFcmToken(
    userId: String,
    newToken: String,
    oldToken: String?
): Boolean {
    val payload = hashMapOf(
        "userId" to userId,
        "newToken" to newToken,
        "oldToken" to oldToken,
        "publicKey" to KeyManager.getMyPublicKey(),
        // Ownership marker: whichever installation last synced owns the
        // identity; every other one deactivates itself on its next start.
        "installationId" to com.bolimot.mindtheclub.functions.InstallationIdentity
            .get(com.bolimot.mindtheclub.start.App.context())
    )

    debugLine("updateMyFcmToken", "Trying to update token, with callable")

    return try {
        Firebase.functions
            .getHttpsCallable("updateMyFcmToken")
            .call(payload)
            .await()

        MySelf.fcmTokenSet(newToken)

        debugLine("updateMyFcmToken", "Token updated successfully and locally")

        true
    } catch (e: Exception) {
        debugLine("updateMyFcmToken", "Error updating token: ${e.message}")
        false
    }
}