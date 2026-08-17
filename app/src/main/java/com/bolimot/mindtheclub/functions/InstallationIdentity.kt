package com.bolimot.mindtheclub.functions

import android.content.Context
import androidx.core.content.edit
import androidx.work.WorkManager
import java.util.UUID

/**
 * One identity, one installation at a time.
 *
 * Every install generates its own random id at first use; the id is written to
 * the user's Firestore document together with the FCM token, and each start
 * compares the remote id with the local one. A different remote id means
 * another installation (a restored backup on a new phone) took the identity
 * over: this device then deactivates itself instead of fighting for delivery.
 * Without this, two phones sharing one identity are a split brain: the FCM
 * token points at whichever wrote last, messages land on one device or the
 * other, and the old phone silently steals delivery back whenever its token
 * rotates.
 *
 * The id deliberately lives in its OWN preferences file and is NEVER part of
 * the backup: a restored backup must not carry the old installation's id, or
 * the takeover could not be detected.
 *
 * Fails safe by design: the id is only ever written through updateMyFcmToken,
 * whose cloud function checks token ownership, and deactivation only triggers
 * on a successfully READ remote id that differs. Network errors change nothing.
 */
object InstallationIdentity {

    private const val PREFS_NAME = "InstallationIdentityPrefs"
    private const val KEY_ID = "installationId"
    private const val KEY_MOVED = "identityMoved"
    private const val TAG = "InstallationIdentity"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** This installation's id, generated on first access. */
    fun get(context: Context): String {
        prefs(context).getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        prefs(context).edit { putString(KEY_ID, id) }
        debugLine(TAG, "Generated installation id $id")
        return id
    }

    fun isDeactivated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MOVED, false)

    /**
     * Another installation owns the identity: quiet this one. The periodic
     * workers are cancelled so a phone left in a drawer stops sending seen
     * receipts, retries and soak traffic under an identity it no longer owns.
     */
    fun markDeactivated(context: Context) {
        if (isDeactivated(context)) return
        prefs(context).edit { putBoolean(KEY_MOVED, true) }
        try {
            val wm = WorkManager.getInstance(context)
            // Unique names as declared in each worker's companion.
            wm.cancelUniqueWork("PendingRetryWorker")
            wm.cancelUniqueWork("InboxRecoveryWorker")
            wm.cancelUniqueWork("SoakTestWorker")
        } catch (e: Exception) {
            debugLine(TAG, "Worker cancel failed: ${e.message}")
        }
        debugLine(TAG, "Deactivated: identity moved to another installation")
    }

    /** A backup was restored here, or the ownership check found nobody else: this installation owns the identity again. */
    fun clearDeactivated(context: Context) {
        if (prefs(context).contains(KEY_MOVED)) {
            prefs(context).edit { remove(KEY_MOVED) }
            debugLine(TAG, "Deactivation cleared, this installation owns the identity")
        }
    }
}
