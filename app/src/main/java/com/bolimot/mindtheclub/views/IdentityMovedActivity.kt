package com.bolimot.mindtheclub.views

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.backup.BackupManager
import com.bolimot.mindtheclub.functions.InstallationIdentity
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.start.MainActivity
import com.bolimot.mindtheclub.start.fetchRemoteInstallationId
import com.bolimot.mindtheclub.start.forceTokenSyncAfterRestore
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown when this installation detected that the identity now lives on another
 * phone (see InstallationIdentity). The device stays paused so the two installs
 * cannot fight over message delivery.
 *
 * "Check again" is the safety valve against any false positive: it re-reads the
 * remote installation id and lifts the pause when this install owns the
 * identity again (or the field is gone). Moving the identity BACK to this phone
 * goes through the ownership-proving path, a backup made on the other phone and
 * restored here, never through a blind reclaim.
 */
class IdentityMovedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.identity_moved_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.identity_moved_message)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        })

        // The message tells the user to restore a backup here, so the restore
        // screen must be reachable FROM here: MainActivity gates everything
        // behind the deactivation check, so Options (and with it Backup and
        // restore) cannot be opened while this screen is up.
        root.addView(Button(this).apply {
            text = getString(R.string.identity_moved_restore)
            setOnClickListener {
                startActivity(Intent(this@IdentityMovedActivity, BackupRestoreActivity::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.reclaim_button)
            setOnClickListener { startReclaim() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.identity_moved_retry)
            setOnClickListener { checkAgain() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.identity_moved_close)
            setOnClickListener { finishAffinity() }
        })

        setContentView(root)
    }

    /**
     * Coming back from the restore screen: a restore that carried the identity
     * clears the deactivation itself (BackupManager), so this local check is
     * enough to let the user straight back into the app, with no network call.
     */
    override fun onResume() {
        super.onResume()
        if (!InstallationIdentity.isDeactivated(this)) {
            debugLine("IdentityMoved", "Deactivation cleared by restore, resuming normally")
            goToMainActivity()
        }
    }

    private fun goToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    /**
     * Reclaim: bring message delivery back to THIS phone.
     *
     * The identity itself never left, deactivation only silences a device, so
     * the private keyset is still here and nothing has to be restored. All that
     * is needed is to publish this installation's token and id on the Firestore
     * document, exactly as a restore does, and lift the pause. The other phone
     * notices at its next start and steps aside on its own.
     *
     * The password gate is what keeps the old "never through a blind reclaim"
     * rule honest: whoever picks up a lost handset would otherwise be one tap
     * away from the identity. The backup file the user left here when they
     * migrated is the only thing on the device that can verify a password, since
     * the password itself is never stored, and BackupManager.verifyOwnership
     * also checks the backup belongs to THIS identity.
     */
    private val pickBackupForReclaim = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        askPassword { password -> performReclaim(uri, password) }
    }

    private fun startReclaim() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reclaim_title)
            .setMessage(R.string.reclaim_explain)
            .setPositiveButton(R.string.yes) { _, _ ->
                pickBackupForReclaim.launch(arrayOf("*/*"))
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun askPassword(onEntered: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            // Same wording the restore screen uses, this is the same password.
            .setTitle(R.string.enter_backup_password)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text.toString()
                if (password.isEmpty()) {
                    showToast(getString(R.string.password_required), this)
                } else {
                    onEntered(password)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performReclaim(uri: android.net.Uri, password: String) {
        lifecycleScope.launch {
            val userId = MySelf.userId()
            if (userId == null) {
                showToast(getString(R.string.reclaim_failed), this@IdentityMovedActivity)
                return@launch
            }

            val owns = withContext(Dispatchers.IO) {
                BackupManager.verifyOwnership(this@IdentityMovedActivity, uri, password, userId)
            }
            if (!owns) {
                showToast(getString(R.string.reclaim_wrong_password), this@IdentityMovedActivity)
                return@launch
            }

            // Publish token, public key and this installation's id. Same call the
            // restore path uses, and it reads the current Firestore token to
            // satisfy the cloud function's ownership check.
            forceTokenSyncAfterRestore(userId)

            val remote = fetchRemoteInstallationId(userId)
            val mine = InstallationIdentity.get(this@IdentityMovedActivity)
            if (remote != null && remote != mine) {
                debugLine("IdentityMoved", "Reclaim did not stick, remote id is still $remote")
                showToast(getString(R.string.reclaim_failed), this@IdentityMovedActivity)
                return@launch
            }

            InstallationIdentity.clearDeactivated(this@IdentityMovedActivity)
            debugLine("IdentityMoved", "Reclaimed: this installation owns the identity again")
            showToast(getString(R.string.reclaim_done), this@IdentityMovedActivity)
            goToMainActivity()
        }
    }

    private fun checkAgain() {
        lifecycleScope.launch {
            val userId = MySelf.userId()
            val remote = if (userId != null) fetchRemoteInstallationId(userId) else null
            val mine = InstallationIdentity.get(this@IdentityMovedActivity)
            if (remote == null || remote == mine) {
                debugLine("IdentityMoved", "Ownership back on this installation, reactivating")
                InstallationIdentity.clearDeactivated(this@IdentityMovedActivity)
                goToMainActivity()
            } else {
                showToast(getString(R.string.identity_moved_still), this@IdentityMovedActivity)
            }
        }
    }
}
