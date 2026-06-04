package com.bolimot.mindtheclub.start

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
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
import com.bolimot.mindtheclub.works.AppCheckWorker
import com.bolimot.mindtheclub.works.InboxRecoveryWorker
import com.bolimot.mindtheclub.works.PendingRetryWorker
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    private var sharing: String? = null
    private var uri: String? = null
    private var type: String? = null
    private var text: String? = null

    companion object {
        const val PERMISSIONS_REQUEST_CODE = 100
    }

    private fun getParameters(i: Intent){
        uri = i.getStringExtra("uri")
        type = i.getStringExtra("type")
        sharing = i.getStringExtra("sharing")
        text = i.getStringExtra("text")
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
        val basePermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.MANAGE_OWN_CALLS,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            basePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val requiredPermissions = basePermissions + storagePermissions

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        var shouldShowNotificationSettings = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                shouldShowNotificationSettings = true
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
        } else if (shouldShowNotificationSettings) {
            showToast(getString(R.string.enable_notification), this)
            finishAffinity()
        } else {
            startApplication()
        }
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