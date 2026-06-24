package com.bolimot.mindtheclub.views

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.app.TaskStackBuilder
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.start.BaseActivity
import com.bolimot.mindtheclub.tools.MySelf
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

/**
 * Onboarding step 4 (final): encourage inviting friends.
 *
 * Inviting is the pivotal step — without peers the app can't be used. The "Invite"
 * button is identical to the one in [MyProfile] (same payload, same [InviteActivity]).
 * "Skip for now" is offered as a low-key text link, since the user can't be forced
 * to invite someone immediately. Either choice opens the app. Onboarding won't run
 * again because the name was already saved on the first screen.
 */
class OnboardingInviteActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding_invite)

        findViewById<ExtendedFloatingActionButton>(R.id.inviteButton).setOnClickListener {
            // Same behaviour as MyProfile's "Invite a friend" button.
            val name = MySelf.name()?.trim() ?: ""
            val userId = MySelf.userId() ?: ""
            val bio = MySelf.bio()?.trim() ?: ""

            if (listOf(name, userId).any { it.isEmpty() }) {
                showToast("No profile to share", this)
                return@setOnClickListener
            }

            val fingerprint = KeyManager.getMyPublicKeyFingerprint() ?: ""
            val payload = "mtc;$name;$userId;$bio;$fingerprint"

            // Open the app with the invite screen on top, so Back returns to the app
            // (not to onboarding).
            val appTabIntent = Intent(this, AppTab::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val inviteIntent = Intent(this, InviteActivity::class.java).putExtra("payload", payload)

            TaskStackBuilder.create(this).apply {
                addNextIntentWithParentStack(appTabIntent)
                addNextIntent(inviteIntent)
                startActivities()
            }
            finish()
        }

        findViewById<View>(R.id.skipText).setOnClickListener {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        startActivity(Intent(this, AppTab::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
