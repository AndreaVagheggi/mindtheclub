// NEW FILE: app/src/main/java/com/bolimot/mindtheclub/backup/MtcBackupAgent.kt

package com.bolimot.mindtheclub.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
/**
 * Custom BackupAgent that only writes data when the user has opted in
 * via the auto-backup toggle in Settings → Backup & Restore.
 *
 * When disabled, onFullBackup() writes nothing, so Android Auto Backup
 * stores an empty snapshot — effectively no backup.
 */
class MtcBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        val enabled = BackupManager.isAutoBackupEnabled(this)
        android.util.Log.d("MtcBackupAgent", "onFullBackup called, autoBackupEnabled=$enabled")

        if (enabled) {
            android.util.Log.d("MtcBackupAgent", "Calling super.onFullBackup()")
            super.onFullBackup(data)
            android.util.Log.d("MtcBackupAgent", "super.onFullBackup() completed")
        }
    }

    // Required overrides for key/value backup (unused, we use fullBackupOnly)
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) { /* no-op */ }

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) { /* no-op */ }
}
