package com.bolimot.mindtheclub.start

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.TaskStackBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.PREF_PENDING_INVITE_SEED
import com.bolimot.mindtheclub.functions.captureInstallReferrerOnce
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.parseInviteReferrer
import com.bolimot.mindtheclub.functions.printAppSignature
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.views.AppTab
import com.bolimot.mindtheclub.views.MyProfile
import com.bolimot.mindtheclub.views.OnboardingActivity
import com.bolimot.mindtheclub.works.AppCheckWorker
import com.bolimot.mindtheclub.works.InboxRecoveryWorker
import com.bolimot.mindtheclub.functions.VideoCompressor
import com.bolimot.mindtheclub.works.PendingRetryWorker
import com.bolimot.mindtheclub.works.SoakTestWorker
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.bolimot.mindtheclub.tools.Share

class MainActivity : BaseActivity() {
    private var sharing: String? = null
    private var uri: String? = null
    private var type: String? = null
    private var text: String? = null

    private var inviteName: String? = null
    private var inviteUserId: String? = null
    private var inviteBio: String? = null
    private var inviteFingerprint: String? = null

    companion object {
        const val PERMISSIONS_REQUEST_CODE = 100
    }

    private fun getParameters(i: Intent){
        uri = i.getStringExtra("uri")
        type = i.getStringExtra("type")
        sharing = i.getStringExtra("sharing")
        text = i.getStringExtra("text")

        if (i.action == Intent.ACTION_VIEW && i.data != null) {
            val data = i.data!!
            inviteUserId = data.getQueryParameter("u")
            inviteName = data.getQueryParameter("n")
            inviteBio = data.getQueryParameter("b")
            inviteFingerprint = data.getQueryParameter("f")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.MindTheClub)
        super.onCreate(savedInstanceState)

        printAppSignature(this)

        getParameters(intent)

        // Deferred-invite handoff: if this install came from an invite link while the
        // app wasn't installed, the Play Store carries the inviter's profile in the
        // install referrer. Read it once now; AppTab consumes it after onboarding.
        captureInstallReferrerOnce(this)

        maybeInjectDebugReferrer(intent)
    }

    /**
     * Debug-only: simulate a deferred-install referrer locally, with no Play Store
     * round-trip. Stashes a pending invite seed exactly as the real referrer would,
     * so the full post-install flow (onboarding -> "add this contact?" dialog ->
     * acquisition) can be exercised on a sideloaded build. Never active in release.
     *
     * Usage (debug build; pass a real second account's invite params for a true
     * end-to-end, or any fake n/u for just the dialog):
     *   adb shell am start -n com.bolimot.mindtheclub/.start.MainActivity \
     *     -e mtc_debug_referrer 'n=Mario&u=testuser123&b=Hi&f=ABCD1234'
     */
    private fun maybeInjectDebugReferrer(i: Intent) {
        if (!BuildConfig.ENABLE_DEBUG_TOOLS) return
        val raw = i.getStringExtra("mtc_debug_referrer") ?: return
        val seed = parseInviteReferrer(raw) ?: return
        setPreference(PREF_PENDING_INVITE_SEED, seed, this)
        debugLine("InstallReferrer", "DEBUG injected pending invite seed: $seed")
    }

    override fun onResume() {
        super.onResume()

        // Set up Firebase / App Check only if Play Services is ready right now. This
        // is best-effort and must NEVER block or delay the UI — otherwise a transient
        // Play Services state (common on the very first launch after install, while
        // it is still updating) would strand the user on the splash screen.
        setUpFirebaseIfAvailable()

        // Always start the app, regardless of Play Services state. Token/push sync
        // runs in the background and retries on its own; nothing here needs Play
        // Services to show onboarding or the main screen.
        checkAndRequestPermissions()
    }

    private fun setUpFirebaseIfAvailable() {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = apiAvailability.isGooglePlayServicesAvailable(this)

        if (resultCode == ConnectionResult.SUCCESS) {
            debugLine("AppCheck", "Play Services OK — enqueuing App Check worker.")
            val appCheckRequest = OneTimeWorkRequest.Builder(AppCheckWorker::class.java).build()
            WorkManager.getInstance(this).enqueue(appCheckRequest)
        } else {
            // Not ready (e.g. still updating right after install). Don't block:
            // Firebase Messaging retries token retrieval automatically, and App Check
            // will be enqueued on a later launch once Play Services is available.
            debugLine("PlayServices", "Play Services not ready (code=$resultCode); continuing without blocking.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        getParameters(intent)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // No runtime permissions are requested at cold start. Each is requested in
        // context, where it's actually needed:
        //   - microphone, camera, notifications -> onboarding (OnboardingPermissionsActivity)
        //   - photos/videos (media)             -> ImagesTab, when the picker opens
        //   - contacts                          -> InviteActivity
        //   - Bluetooth                         -> MyProfile (transport switch)
        // READ_PHONE_STATE was requested historically but is not read by any code.
        startApplication()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val deniedPermissions = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }

            if (deniedPermissions.isNotEmpty()) {
                debugLine("Permissions", "Denied permissions: $deniedPermissions")
            } else {
                startApplication()
            }
        }
    }

    private fun startApplication() {
        debugLine("StartApplication", "Starting application.")

        // Measured here and nowhere else: the gap between the last sign of life
        // and now only exists at the moment the app comes back, and asking later
        // would always find it running and conclude all was well. See
        // DeliveryHealth.checkForSuppression.
        com.bolimot.mindtheclub.functions.DeliveryHealth.checkForSuppression(this)

        // This installation lost the identity to another phone: stay paused.
        // The moved screen has its own re-check, nothing else may run here.
        if (com.bolimot.mindtheclub.functions.InstallationIdentity.isDeactivated(this)) {
            startActivity(Intent(this, com.bolimot.mindtheclub.views.IdentityMovedActivity::class.java))
            finish()
            return
        }

        val myUserId = initApplication()

        App.instance!!.applicationScope.launch(Dispatchers.IO) {
            syncFirebaseTokenInBackground(myUserId)
        }


        PendingRetryWorker.schedule(this)
        InboxRecoveryWorker.schedule(this)

        // Transcoded videos live in the cache and are consumed by the send that
        // produced them; a send abandoned halfway leaves one behind.
        VideoCompressor.purgeStaleOutputs()

        // No-op unless BuildConfig.SOAK_TEST, i.e. anywhere but a debug build.
        App.instance!!.applicationScope.launch(Dispatchers.IO) {
            SoakTestWorker.schedule(applicationContext)
        }

        App.instance!!.applicationScope.launch(Dispatchers.IO) {
            getPeerViewModel().requestMissingProfilePictures()
        }

        App.instance!!.applicationScope.launch(Dispatchers.IO) {
            PendingRetryWorker.retryAllNow(applicationContext)
        }

        if (OnboardingActivity.shouldRun(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            return
        }

        if (getPreference(MySelf.NAME_KEY, this).isNullOrEmpty()) {
            val backStackIntent = Intent(this, AppTab::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val profileIntent = Intent(this, MyProfile::class.java)

            TaskStackBuilder.create(this).apply {
                addNextIntentWithParentStack(backStackIntent)
                addNextIntent(profileIntent)
                startActivities()
            }
        } else {
            if (!inviteUserId.isNullOrEmpty() && !inviteName.isNullOrEmpty()) {
                val inviteIntent = Intent(this, AppTab::class.java).apply {
                    putExtra("sharing", Share.PROFILE)
                    putExtra("name", inviteName)
                    putExtra("userId", inviteUserId)
                    putExtra("bio", inviteBio)
                    putExtra("fingerprint", inviteFingerprint)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(inviteIntent)
                return
            }

            val shareType = type
            val shareSharing = sharing
            val shareUri = uri
            val shareText = text

            val intent = Intent(this, AppTab::class.java).apply {
                putExtra("sharing", shareSharing)
                putExtra("shareType", shareType)
                putExtra("text", shareText)
                shareUri?.let { putExtra("uri", it) }
            }
            startActivity(intent)
        }
    }

}
