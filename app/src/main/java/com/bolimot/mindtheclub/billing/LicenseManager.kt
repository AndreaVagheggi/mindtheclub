package com.bolimot.mindtheclub.billing

import android.content.Context
import androidx.core.content.edit
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.functions.debugLine
import com.google.firebase.Firebase
import com.google.firebase.functions.functions
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Licences bought outside Google Play (Stripe, quarterly, renewed automatically), for the
 * de-Google build (mtcx) and for Play installs on phones without the Play Store.
 *
 * The code is activated on this device by the redeemLicense Cloud Function and re-checked at
 * most once a day by checkLicense; the expiry is cached so the access gate works offline, like
 * the Play entitlement in BillingManager. Nothing here identifies the user: the server sees
 * the code and a random device id created only for this purpose (never the userId, never the
 * installationId).
 */
object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val PREFS = "mtc_license"
    private const val KEY_CODE = "code"
    private const val KEY_PAID_UNTIL = "paidUntil"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_LAST_CHECK = "lastCheck"
    private const val KEY_PENDING = "pendingCode"
    private const val CHECK_INTERVAL_MS = 24 * 3600 * 1000L

    /**
     * Stripe Payment Link, shown ONLY in the de-Google build (Play policy forbids steering Play
     * users to another payment method). Empty until the Stripe account exists: the Buy button
     * stays hidden while it is.
     */
    const val PURCHASE_URL = ""

    /** Where a buyer cancels or changes the subscription (Stripe Managed Payments, Onelink). */
    const val MANAGE_URL = "https://app.link.com"

    /** The de-Google build: its own package, same code. */
    val isDeGoogleBuild: Boolean get() = BuildConfig.APPLICATION_ID == "com.bolimot.mindtheclub.x"

    enum class Status { VALID, EXPIRED, INVALID, MOVED, REVOKED, TOO_MANY_MOVES, NETWORK_ERROR }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun code(context: Context): String? = prefs(context).getString(KEY_CODE, null)

    fun paidUntil(context: Context): Long = prefs(context).getLong(KEY_PAID_UNTIL, 0L)

    /** Synchronous and offline: what the access gate reads. */
    fun hasValidLicense(context: Context): Boolean = paidUntil(context) > System.currentTimeMillis()

    private fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        p.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    /** A code that arrived through the https://www.mindtheclub.com/license?code= link. */
    fun setPendingCode(context: Context, code: String) = prefs(context).edit { putString(KEY_PENDING, code) }
    fun takePendingCode(context: Context): String? {
        val code = prefs(context).getString(KEY_PENDING, null) ?: return null
        prefs(context).edit { remove(KEY_PENDING) }
        return code
    }
    fun hasPendingCode(context: Context): Boolean = prefs(context).contains(KEY_PENDING)

    private fun statusOf(value: Any?): Status = when (value) {
        "valid" -> Status.VALID
        "expired" -> Status.EXPIRED
        "moved" -> Status.MOVED
        "revoked" -> Status.REVOKED
        "too-many-moves" -> Status.TOO_MANY_MOVES
        else -> Status.INVALID
    }

    /** Activates a code on this device. Keeps it only if the server knows it. */
    suspend fun redeem(context: Context, code: String): Status {
        return try {
            val result = Firebase.functions.getHttpsCallable("redeemLicense")
                .call(hashMapOf("code" to code.trim(), "deviceId" to deviceId(context)))
                .await()
            val data = result.data as? Map<*, *> ?: return Status.NETWORK_ERROR
            val status = statusOf(data["status"])
            if (status == Status.VALID || status == Status.EXPIRED) {
                val paidUntil = (data["paidUntil"] as? Number)?.toLong() ?: 0L
                prefs(context).edit {
                    putString(KEY_CODE, code.trim())
                    putLong(KEY_PAID_UNTIL, paidUntil)
                    putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                }
            }
            debugLine(TAG, "Redeem: $status")
            status
        } catch (e: Exception) {
            debugLine(TAG, "Redeem failed: ${e.message}")
            Status.NETWORK_ERROR
        }
    }

    /**
     * At most once a day: picks up renewals, and drops a licence the server no longer honours
     * on this device (moved to another phone, revoked). A network failure changes nothing, the
     * cached expiry keeps working offline.
     */
    suspend fun refresh(context: Context, force: Boolean = false) {
        val code = code(context) ?: return
        val p = prefs(context)
        if (!force && System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        try {
            val result = Firebase.functions.getHttpsCallable("checkLicense")
                .call(hashMapOf("code" to code, "deviceId" to deviceId(context)))
                .await()
            val data = result.data as? Map<*, *> ?: return
            when (statusOf(data["status"])) {
                Status.VALID, Status.EXPIRED -> p.edit {
                    putLong(KEY_PAID_UNTIL, (data["paidUntil"] as? Number)?.toLong() ?: 0L)
                    putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                }
                else -> {
                    debugLine(TAG, "Licence no longer valid here: ${data["status"]}")
                    p.edit { remove(KEY_CODE); remove(KEY_PAID_UNTIL); putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
                }
            }
        } catch (e: Exception) {
            debugLine(TAG, "Check failed, keeping the cached expiry: ${e.message}")
        }
    }
}
