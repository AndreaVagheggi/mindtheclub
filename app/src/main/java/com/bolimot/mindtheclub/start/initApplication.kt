package com.bolimot.mindtheclub.start

import com.bolimot.mindtheclub.billing.TrialManager
import com.bolimot.mindtheclub.firebase.updateMyFcmToken
import com.bolimot.mindtheclub.functions.InstallationIdentity
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
        val context = App.context()

        if (InstallationIdentity.isDeactivated(context)) {
            debugLine("initFirebase", "Identity moved to another phone, skipping token sync")
            return
        }

        val freshToken = FirebaseMessaging.getInstance().token.await()
        val storedToken = MySelf.fcmTokenGet()
        val isDocInFirestore = checkUserDocumentExists(myUserId)
        val hasPublicKey = isDocInFirestore && checkUserPublicKeyExists(myUserId)

        debugLine("initFirebase", "Sync Check: Fresh=$freshToken, Stored=$storedToken, InFirestore=$isDocInFirestore, HasPublicKey=$hasPublicKey")

        // One installation per identity. A remote id that exists and is not
        // ours means another install (a restored backup on a new phone) took
        // this identity over: deactivate instead of fighting for delivery.
        // Only a successfully READ different id triggers this; null (doc from
        // an older app version) means unclaimed, and errors change nothing.
        // One read serves both checks: the ownership marker and the trial anchor.
        val userDoc = if (isDocInFirestore) fetchUserDoc(myUserId) else null
        val remoteInstallation = userDoc?.getString("installationId")?.takeIf { it.isNotEmpty() }

        // The trial belongs to the identity, so a phone that starts up with a
        // later start date than the one published adopts the earlier one. This
        // is what stops "uninstall, restore, 30 more days" from working.
        TrialManager.adoptStartedAt(context, userDoc?.getLong("trialStartedAt"))

        val myInstallation = InstallationIdentity.get(context)
        if (remoteInstallation != null && remoteInstallation != myInstallation) {
            debugLine("initFirebase", "Identity owned by installation $remoteInstallation, not mine ($myInstallation). Deactivating this phone.")
            InstallationIdentity.markDeactivated(context)
            val intent = android.content.Intent(context, com.bolimot.mindtheclub.views.IdentityMovedActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        val needsUpdate = when {
            storedToken == null -> true
            freshToken != storedToken -> true
            !isDocInFirestore -> true
            !hasPublicKey -> true
            // Unclaimed doc (pre installationId app version): claim it, the
            // update below writes our id alongside the token.
            remoteInstallation == null && isDocInFirestore -> true
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
            debugLine("initFirebase", "Restore sync: tokens already match")
            // Do NOT return: a restore may have replaced the local identity
            // (same phone reinstall keeps the same token), so publicKey and
            // installationId in Firestore still have to be refreshed below.
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

/** The user's own Firestore document, or null when unreadable. */
suspend fun fetchUserDoc(userId: String): com.google.firebase.firestore.DocumentSnapshot? {
    if (userId.isBlank()) return null
    return try {
        Firebase.firestore.collection("users").document(userId).get().await()
    } catch (e: Exception) {
        debugLine("initFirebase", "fetchUserDoc failed: ${e.message}")
        null
    }
}

/** The installation id currently stamped on the user's document, or null. */
suspend fun fetchRemoteInstallationId(userId: String): String? =
    fetchUserDoc(userId)?.getString("installationId")?.takeIf { it.isNotEmpty() }

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