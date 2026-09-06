package com.bolimot.mindtheclub.functions

import android.content.Context
import com.bolimot.mindtheclub.billing.TrialManager
import java.util.concurrent.TimeUnit

/**
 * Decides when to remind the user that their identity has no recent backup.
 *
 * Since automatic copies were switched off (17 Aug 2026: no Google Drive backup, no device to
 * device transfer), the manual encrypted backup is the ONLY way an identity survives a lost or
 * replaced phone. It has to be, because the keyset is sealed by a master key that lives in this
 * phone's Keystore and can never leave it: without a backup file there is nothing to recover
 * from, per nessuno, noi compresi.
 *
 * Deliberately quiet, same spirit as [DeliveryHealth]. It never nags a new user: the clock only
 * starts once the person has really used the app, and a dismissal buys a full month of silence.
 */
object BackupHealth {

    private const val PREF_LAST_BACKUP_AT = "mtc_last_backup_at"
    private const val PREF_SNOOZE_UNTIL = "mtc_backup_banner_snooze_until"

    /** How stale a backup has to be before the banner appears. */
    private val STALE_MS = TimeUnit.DAYS.toMillis(30)

    /** How long the banner stays away after the user dismisses it. */
    private val SNOOZE_MS = TimeUnit.DAYS.toMillis(30)

    /** Called when a backup file has been written successfully. */
    fun recordBackup(context: Context) {
        setPreference(PREF_LAST_BACKUP_AT, System.currentTimeMillis().toString(), context)
        debugLine("BackupHealth", "Backup recorded, banner reset for ${STALE_MS / 86_400_000} days")
    }

    fun snooze(context: Context) {
        val until = System.currentTimeMillis() + SNOOZE_MS
        setPreference(PREF_SNOOZE_UNTIL, until.toString(), context)
    }

    /**
     * True when this identity is worth protecting and its last backup (or, for someone who never
     * made one, their first real use of the app) is older than [STALE_MS].
     *
     * The fallback anchor is the trial start, stamped on the first message the user ever sends.
     * So a freshly installed app stays silent for a month, someone who only ever looked around is
     * never nagged at all, and a real user who has been chatting for a month without a backup is
     * told once, politely.
     */
    fun shouldWarn(context: Context): Boolean {
        val snoozeUntil = getPreference(PREF_SNOOZE_UNTIL, context)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() < snoozeUntil) return false

        val anchor = getPreference(PREF_LAST_BACKUP_AT, context)?.toLongOrNull()
            ?: TrialManager.startedAt(context)
            ?: return false

        return System.currentTimeMillis() - anchor > STALE_MS
    }
}
