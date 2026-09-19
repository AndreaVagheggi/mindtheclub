package com.bolimot.mindtheclub.push

import android.content.Context
import androidx.core.content.edit
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.billing.TrialManager
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.InstallationIdentity
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.google.firebase.Firebase
import com.google.firebase.functions.functions
import kotlinx.coroutines.tasks.await
import org.unifiedpush.android.connector.UnifiedPush

/*
 * mtcx only: the wake-up channel is UnifiedPush instead of FCM.
 *
 * Flow:
 *   1. UnifiedPushSetup.start picks the distributor (ntfy, Sunup, ...) and registers,
 *      passing our VAPID public key.
 *   2. The distributor answers with an endpoint: MyFirebaseMessagingService.onNewEndpoint
 *      stores it here and publishes it with updateMyPushEndpoint.
 *   3. The mtc-fcm relay, finding no FCM token for us, posts to that endpoint through the
 *      sendUnifiedPush Cloud Function; the message lands in the same handler FCM used.
 *
 * The endpoint is a capability (whoever holds it can push to this phone), so it never goes
 * into users/{id}: the Cloud Function keeps it in pushEndpoints/{id}, which no client can
 * read. Publishing it requires proof that we hold the identity's private key: the server
 * encrypts a short lived token to our public key and only KeyManager can open it.
 */

data class PushEndpointData(val url: String, val p256dh: String, val auth: String)

/** The endpoint the distributor gave us, and the one last published to the server. */
object PushEndpointStore {
    private const val PREFS = "mtcx_push"
    private const val KEY_URL = "url"
    private const val KEY_P256DH = "p256dh"
    private const val KEY_AUTH = "auth"
    private const val KEY_PUBLISHED = "publishedUrl"

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context = App.context()): PushEndpointData? {
        val p = prefs(context)
        val url = p.getString(KEY_URL, null) ?: return null
        val p256dh = p.getString(KEY_P256DH, null) ?: return null
        val auth = p.getString(KEY_AUTH, null) ?: return null
        return PushEndpointData(url, p256dh, auth)
    }

    fun setCurrent(endpoint: PushEndpointData?, context: Context = App.context()) {
        prefs(context).edit {
            if (endpoint == null) {
                remove(KEY_URL); remove(KEY_P256DH); remove(KEY_AUTH)
            } else {
                putString(KEY_URL, endpoint.url)
                putString(KEY_P256DH, endpoint.p256dh)
                putString(KEY_AUTH, endpoint.auth)
            }
        }
    }

    fun published(context: Context = App.context()): String? = prefs(context).getString(KEY_PUBLISHED, null)

    fun setPublished(url: String?, context: Context = App.context()) {
        prefs(context).edit { if (url == null) remove(KEY_PUBLISHED) else putString(KEY_PUBLISHED, url) }
    }
}

/**
 * Publishes (or, with endpoint = null, withdraws) this phone's endpoint, together with what
 * updateMyFcmToken carries in the Play app: public key, installation id, trial anchor.
 */
suspend fun updateMyPushEndpoint(userId: String, endpoint: PushEndpointData?): Boolean {
    val tag = "updateMyPushEndpoint"
    return try {
        // Step 1: the challenge. Null means the identity is not on the server yet.
        val challengeResult = Firebase.functions
            .getHttpsCallable("getPushChallenge")
            .call(hashMapOf("userId" to userId))
            .await()
        val sealed = (challengeResult.data as? Map<*, *>)?.get("challenge") as? String
        val proof = sealed?.let { KeyManager.decrypt(it) }
        if (sealed != null && proof == null) {
            debugLine(tag, "Cannot open the challenge: this phone does not hold the identity key")
            return false
        }

        // Step 2: the registration.
        val context = App.context()
        val payload = hashMapOf<String, Any?>(
            "userId" to userId,
            "proof" to proof,
            "endpoint" to endpoint?.url,
            "p256dh" to endpoint?.p256dh,
            "auth" to endpoint?.auth,
            "publicKey" to KeyManager.getMyPublicKey(),
            "installationId" to InstallationIdentity.get(context),
            "trialStartedAt" to TrialManager.startedAt(context)
        )
        Firebase.functions.getHttpsCallable("updateMyPushEndpoint").call(payload).await()

        PushEndpointStore.setPublished(endpoint?.url)
        debugLine(tag, if (endpoint == null) "Endpoint withdrawn" else "Endpoint published")
        true
    } catch (e: Exception) {
        debugLine(tag, "Failed: ${e.message}")
        false
    }
}

object UnifiedPushSetup {
    private const val TAG = "UnifiedPushSetup"

    /** True when at least one UnifiedPush distributor (ntfy, Sunup, ...) is installed. */
    fun hasDistributor(context: Context): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /**
     * Uses the distributor already chosen, else the system default; with a single
     * distributor installed and no default, that one. Then registers with our VAPID key.
     * Idempotent: the distributor answers a repeated registration with the same endpoint.
     */
    @Volatile
    private var started = false

    fun start(context: Context) {
        // Once per process: MainActivity calls this on every resume, and with several
        // distributors and no default the connector would reopen its chooser every time.
        // Nothing installed yet: stay retryable, so reopening the app after installing a
        // distributor (what the AppTab notice asks) registers straight away.
        if (started || !hasDistributor(context)) return
        started = true
        UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
            if (!success) {
                val installed = UnifiedPush.getDistributors(context)
                if (installed.size == 1) {
                    UnifiedPush.saveDistributor(context, installed[0])
                } else {
                    debugLine(TAG, "No distributor selected (${installed.size} installed)")
                    return@tryUseCurrentOrDefaultDistributor
                }
            }
            debugLine(TAG, "Registering with ${UnifiedPush.getSavedDistributor(context)}")
            // "default" is the connector's own default instance name (INSTANCE_DEFAULT).
            UnifiedPush.register(context, "default", null, BuildConfig.VAPID_PUBLIC_KEY)
        }
    }
}
