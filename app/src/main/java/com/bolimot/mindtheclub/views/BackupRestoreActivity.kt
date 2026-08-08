// NEW FILE: app/src/main/java/com/bolimot/mindtheclub/views/BackupRestoreActivity.kt

package com.bolimot.mindtheclub.views

import android.app.backup.BackupManager as AndroidBackupManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.backup.BackupManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.start.forceTokenSyncAfterRestore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupRestoreActivity : BaseActivity() {

    private lateinit var statusText: TextView

    // SAF launcher: create a new .mtcbackup file
    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@registerForActivityResult
        showPasswordDialog(isBackup = true) { password, includeMessages ->
            performBackup(password, includeMessages, uri)
        }
    }

    // SAF launcher: pick an existing .mtcbackup file
    private val pickBackupFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        showPasswordDialog(isBackup = false) { password, _ ->
            performRestore(password, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_restore)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        val backupButton: Button = findViewById(R.id.btnBackup)
        val restoreButton: Button = findViewById(R.id.btnRestore)
        val autoBackupSwitch: SwitchMaterial = findViewById(R.id.switchAutoBackup)
        statusText = findViewById(R.id.statusText)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.backup_restore)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialise auto-backup toggle
        autoBackupSwitch.isChecked = BackupManager.isAutoBackupEnabled(this)
        autoBackupSwitch.setOnCheckedChangeListener { _, isChecked ->
            BackupManager.setAutoBackupEnabled(this, isChecked)
            if (isChecked) {
                statusText.text = getString(R.string.auto_backup_enabled)
                // Notify the system that backup data has changed
                AndroidBackupManager(this).dataChanged()
            } else {
                statusText.text = getString(R.string.auto_backup_disabled)
            }
        }

        backupButton.setOnClickListener {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.UK)
                .format(java.util.Date())
            createBackupFile.launch("mindtheclub_$timestamp.mtcbackup")
        }

        restoreButton.setOnClickListener {
            pickBackupFile.launch(arrayOf("*/*"))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun showPasswordDialog(
        isBackup: Boolean,
        onConfirm: (password: String, includeMessages: Boolean) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_backup_password, null)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)
        val confirmInput = dialogView.findViewById<TextInputEditText>(R.id.confirmPasswordInput)
        val includeMessagesCheck = dialogView.findViewById<CheckBox>(R.id.checkIncludeMessages)

        // For restore: hide confirm field and messages checkbox
        if (!isBackup) {
            confirmInput.visibility = android.view.View.GONE
            includeMessagesCheck.visibility = android.view.View.GONE
        }

        val title = if (isBackup) getString(R.string.set_backup_password)
        else getString(R.string.enter_backup_password)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = passwordInput.text?.toString() ?: ""
                val confirm = confirmInput.text?.toString() ?: ""
                val includeMessages = includeMessagesCheck.isChecked

                if (password.isEmpty()) {
                    showToast(getString(R.string.password_required), this)
                    return@setPositiveButton
                }

                // The backup now carries the identity private key: a weak
                // password would make the file the easiest way to steal an
                // identity. Enforced on creation only, restore accepts any.
                if (isBackup && password.length < 8) {
                    showToast(getString(R.string.backup_password_short), this)
                    return@setPositiveButton
                }

                if (isBackup && password != confirm) {
                    showToast(getString(R.string.passwords_do_not_match), this)
                    return@setPositiveButton
                }

                onConfirm(password, includeMessages)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performBackup(
        password: String,
        includeMessages: Boolean,
        uri: android.net.Uri
    ) {
        statusText.text = getString(R.string.backup_in_progress)

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                BackupManager.createBackup(this@BackupRestoreActivity, uri, password, includeMessages)
            }

            if (success) {
                statusText.text = getString(R.string.backup_completed)
                showToast(getString(R.string.backup_completed), this@BackupRestoreActivity)
            } else {
                statusText.text = getString(R.string.backup_failed)
                showToast(getString(R.string.backup_failed), this@BackupRestoreActivity)
            }
        }
    }

    private fun performRestore(password: String, uri: android.net.Uri) {
        statusText.text = getString(R.string.restore_in_progress)

        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                BackupManager.restoreBackup(this@BackupRestoreActivity, uri, password)
            }

            if (summary != null) {
                statusText.text = summary
                showToast(getString(R.string.restore_completed), this@BackupRestoreActivity)
                debugLine("BackupRestoreActivity", summary)

                // Re-sync FCM token with Firestore under the restored userId
                val restoredUserId = com.bolimot.mindtheclub.tools.MySelf.userId()
                if (restoredUserId != null) {
                    forceTokenSyncAfterRestore(restoredUserId)
                }
            } else {
                statusText.text = getString(R.string.restore_failed)
                showToast(getString(R.string.restore_failed), this@BackupRestoreActivity)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
