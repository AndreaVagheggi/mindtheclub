package com.bolimot.mindtheclub.views

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.InstallationIdentity
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.start.MainActivity
import com.bolimot.mindtheclub.start.fetchRemoteInstallationId
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.launch

/**
 * Shown when this installation detected that the identity now lives on another phone (see
 * InstallationIdentity). The device stays paused so the two installs cannot fight over delivery.
 *
 * "Check again" is the safety valve against a false positive: it re-reads the remote installation
 * id and lifts the pause when this install owns the identity again, or the field is gone.
 *
 * Moving the identity BACK here has exactly one route: make a backup on the phone that currently
 * holds it and restore that backup here. The old one tap "use this phone again" button went away
 * (17 Aug 2026): it brought delivery back without bringing the DATA back, so a user returning to
 * their previous handset landed in an interface missing everything said in between. A restore is
 * one step longer and always leaves the two phones consistent.
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

        // The message tells the user to restore a backup here, so the restore screen has to be
        // reachable FROM here: MainActivity gates everything behind the deactivation check, so
        // Options, and with it Backup and restore, cannot be opened while this screen is up.
        root.addView(Button(this).apply {
            text = getString(R.string.identity_moved_restore)
            setOnClickListener {
                startActivity(Intent(this@IdentityMovedActivity, BackupRestoreActivity::class.java))
            }
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
     * Coming back from the restore screen: a restore that carried the identity clears the
     * deactivation itself (BackupManager), so this local check is enough to let the user straight
     * back in, senza chiamate di rete.
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
