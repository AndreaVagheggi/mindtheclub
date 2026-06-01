package com.bolimot.mindtheclub.start

import com.bolimot.mindtheclub.firebase.updateMyFcmToken
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import com.bolimot.mindtheclub.crypto.KeyManager

fun initApplication(): String {
    val context = App.context()
    var myUserId = MySelf.userId()

    debugLine("initApplication", "Initializing application")

    if (myUserId == null) {
        myUserId = guid()
        setPreference(MySelf.USER_ID_KEY, myUserId, context)
        debugLine("initFirebase", "New Profile Generated: $myUserId")
    }

    if (MySelf.privateId() == null) {
        val myPrivateId = guid()
        setPreference(MySelf.PRIVATE_ID_KEY, myPrivateId, context)
    }

    KeyManager.ensureKeyPair()

    return myUserId
}

suspend fun syncFirebaseTokenInBackground(myUserId: String) {
    try {
        val freshToken = FirebaseMessaging.getInstance().token.await()
        val storedToken = MySelf.fcmTokenGet()
        val isDocInFirestore = checkUserDocumentExists(myUserId)
        val hasPublicKey = isDocInFirestore && checkUserPublicKeyExists(myUserId)

        debugLine("initFirebase", "Sync Check: Fresh=$freshToken, Stored=$storedToken, InFirestore=$isDocInFirestore, HasPublicKey=$hasPublicKey")

        val needsUpdate = when {
            storedToken == null -> true
            freshToken != storedToken -> true
            !isDocInFirestore -> true
            !hasPublicKey -> true
            else -> false
        }

        if (needsUpdate) {
            debugLine("initFirebase", "State mismatch detected. Updating Firestore...")
            val success = updateMyFcmToken(myUserId, freshToken, storedToken)
            if (!success && !isDocInFirestore) {
                debugLine("initFirebase", "Critical: Failed to sync token to Firestore.")
            }
        } else {
            debugLine("initFirebase", "State is in sync. No write needed.")
        }
    } catch (e: Exception) {
        debugLine("initFirebase", "Error during token sync (Offline?): ${e.message}")
    }
}

suspend fun forceTokenSyncAfterRestore(userId: String) {
    try {
        val freshToken = FirebaseMessaging.getInstance().token.await()
        val db = Firebase.firestore
        val doc = db.collection("users").document(userId).get().await()
        val firestoreToken = if (doc.exists()) doc.getString("fcmToken") else null

        debugLine("initFirebase", "Restore sync: fresh=$freshToken, firestoreToken=$firestoreToken")

        if (freshToken == firestoreToken) {
            MySelf.fcmTokenSet(freshToken)
            debugLine("initFirebase", "Restore sync: tokens already match, stored locally")
            return
        }

        // Pass the actual Firestore token as oldToken so the Cloud Function accepts it
        val success = updateMyFcmToken(userId, freshToken, firestoreToken)
        if (success) {
            debugLine("initFirebase", "Restore sync: token updated successfully")
        } else {
            debugLine("initFirebase", "Restore sync: token update failed")
        }
    } catch (e: Exception) {
        debugLine("initFirebase", "Restore sync error: ${e.message}")
    }
}

suspend fun checkUserDocumentExists(userId: String): Boolean {
    debugLine("checkUserDocumentExists", "Checking if user document exists")

    if (userId.isBlank()) {
        return false
    }

    return try {
        val db = Firebase.firestore
        val userDocRef = db.collection("users").document(userId)
        val document = userDocRef.get().await()

        document.exists()
    } catch (e: Exception) {
        debugLine("checkUserDocumentExists", "Error checking document existence: ${e.message}")
        false
    }
}

suspend fun checkUserPublicKeyExists(userId: String): Boolean {
    if (userId.isBlank()) {
        return false
    }

    return try {
        val db = Firebase.firestore
        val document = db.collection("users").document(userId).get().await()
        val key = document.getString("publicKey")
        !key.isNullOrEmpty()
    } catch (e: Exception) {
        debugLine("checkUserPublicKeyExists", "Error checking public key existence: ${e.message}")
        false
    }
}