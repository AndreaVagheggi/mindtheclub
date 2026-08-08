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

    private fun checkAgain() {
        lifecycleScope.launch {
            val userId = MySelf.userId()
            val remote = if (userId != null) fetchRemoteInstallationId(userId) else null
            val mine = InstallationIdentity.get(this@IdentityMovedActivity)
            if (remote == null || remote == mine) {
                debugLine("IdentityMoved", "Ownership back on this installation, reactivating")
                InstallationIdentity.clearDeactivated(this@IdentityMovedActivity)
                val intent = Intent(this@IdentityMovedActivity, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            } else {
                showToast(getString(R.string.identity_moved_still), this@IdentityMovedActivity)
            }
        }
    }
}
