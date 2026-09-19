package com.bolimot.mindtheclub.start

import com.bolimot.mindtheclub.billing.TrialManager
import com.bolimot.mindtheclub.functions.InstallationIdentity
import com.bolimot.mindtheclub.push.PushEndpointStore
import com.bolimot.mindtheclub.push.updateMyPushEndpoint
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
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

        // mtcx: the "token" is the UnifiedPush endpoint. freshToken is the one the distributor
        // gave us, storedToken the one last published; the rest of the logic is unchanged.
        val endpoint = PushEndpointStore.current()
        val freshToken = endpoint?.url
        val storedToken = PushEndpointStore.published()
        val isDocInFirestore = checkUserDocumentExists(myUserId)
        val hasPublicKey = isDocInFirestore && checkUserPublicKeyExists(myUserId)

        // mtcx: the endpoint is a capability, never in a log line; only whether it is in sync.
        debugLine("initFirebase", "Sync Check: HasEndpoint=${freshToken != null}, Published=${freshToken != null && freshToken == storedToken}, InFirestore=$isDocInFirestore, HasPublicKey=$hasPublicKey")

        // One installation per identity. A remote id that exists and is not ours means another
        // install (a restored backup on a new phone) took this identity over: deactivate instead
        // of fighting for delivery. Only a successfully READ different id triggers it; null (doc
        // from an older app version) means unclaimed, and errors change nothing. One read serves
        // both checks, the ownership marker and the trial anchor.
        val userDoc = if (isDocInFirestore) fetchUserDoc(myUserId) else null
        val remoteInstallation = userDoc?.getString("installationId")?.takeIf { it.isNotEmpty() }

        // The trial belongs to the identity, so a phone that starts up with a later start date
        // than the one published adopts the earlier one. This is what stops "uninstall, restore,
        // 30 more days" from working.
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
            // Unclaimed doc (pre installationId app version): claim it, the update below writes
            // our id alongside the token.
            remoteInstallation == null && isDocInFirestore -> true
            // The trial anchor is only ever written by updateMyFcmToken, and on a fresh install
            // that call happens BEFORE the clock starts (it starts on the first outgoing
            // message), so it published null. Without this condition nothing wrote it again while
            // the token stayed put, the anchor stayed empty for ever, and uninstall-reinstall
            // handed out a brand new 30 days: proprio l'abuso che deve fermare. adoptStartedAt
            // ran above and already took the EARLIER of local and remote, so publishing the local
            // value can never shorten anybody's trial.
            TrialManager.startedAt(context)
                ?.let { it != userDoc?.getLong("trialStartedAt") } == true -> true
            else -> false
        }

        if (needsUpdate && endpoint == null) {
            // No distributor answer yet: onNewEndpoint publishes as soon as it arrives.
            debugLine("initFirebase", "State mismatch, but no UnifiedPush endpoint yet")
        } else if (needsUpdate && endpoint != null) {
            debugLine("initFirebase", "State mismatch detected. Updating Firestore...")
            val success = updateMyPushEndpoint(myUserId, endpoint)
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
        // mtcx: the restored keyset is what proves ownership now (getPushChallenge), not an old
        // token. Publishing also claims the identity for this installation, exactly like the
        // Play app's restore: the previous phone deactivates itself on its next start.
        val endpoint = PushEndpointStore.current()
        if (endpoint == null) {
            debugLine("initFirebase", "Restore sync: no UnifiedPush endpoint yet, onNewEndpoint will publish")
            return
        }
        val success = updateMyPushEndpoint(userId, endpoint)
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