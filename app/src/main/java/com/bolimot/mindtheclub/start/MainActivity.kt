package com.bolimot.mindtheclub.start

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.TaskStackBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerViewModel
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.printAppSignature
import com.bolimot.mindtheclub.functions.showToast
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.views.AppTab
import com.bolimot.mindtheclub.views.MyProfile
import com.bolimot.mindtheclub.views.OnboardingActivity
import com.bolimot.mindtheclub.works.AppCheckWorker
import com.bolimot.mindtheclub.works.InboxRecoveryWorker
import com.bolimot.mindtheclub.works.PendingRetryWorker
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
    }

    override fun onResume() {
        super.onResume()

        if (checkPlayServices()) {
            initializeFirebaseAndAppCheck()
        }
    }

    private fun initializeFirebaseAndAppCheck() {
        debugLine("AppCheck", "Enqueuing App Check worker.")

        val appCheckRequest = OneTimeWorkRequest.Builder(AppCheckWorker::class.java).build()

        WorkManager.getInstance(this).enqueue(appCheckRequest)

        checkAndRequestPermissions()
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

        val myUserId = initApplication()

        App.instance!!.applicationScope.launch(Dispatchers.IO) {
            syncFirebaseTokenInBackground(myUserId)
        }


        PendingRetryWorker.schedule(this)
        InboxRecoveryWorker.schedule(this)

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

    private fun checkPlayServices(): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = apiAvailability.isGooglePlayServicesAvailable(this)
        if (resultCode != ConnectionResult.SUCCESS) {
            if (apiAvailability.isUserResolvableError(resultCode)) {
                debugLine("PlayServices", "This device does not support Play services.")
                apiAvailability.getErrorDialog(this, resultCode, 9000)?.show()
            } else {
                debugLine("PlayServices", "This device is not supported.")
                showToast("This device is not supported.", this)
                finish()
            }
            return false
        }
        debugLine("PlayServices", "This device is supported, Play services OK!.")
        return true
    }
}
