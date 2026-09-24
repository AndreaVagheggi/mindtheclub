package com.bolimot.mindtheclub.start

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.billing.BillingManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.SoundManager
import com.bolimot.mindtheclub.transport.BluetoothPresence
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bolimot.mindtheclub.works.WorkStateSwapper
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.webrtc.PeerConnectionFactory

class App : Application(), DefaultLifecycleObserver {

    var isForeground: Boolean = false
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this

        clearLogOnNewInstall()

        com.bolimot.mindtheclub.functions.setPreference(
            BluetoothPresence.PREF_BLUETOOTH_ENABLED, "false", this
        )

        // No App Check. It was switched off on 22 Aug 2026, after a Play Integrity outage left a
        // phone unable to send anything for two hours, and removed from the code on 24 Sep 2026.
        // The cloud functions do not enforce it and the Cloudflare workers never read a token;
        // abuse is held back by the daily budget brakes in the workers.
        FirebaseApp.initializeApp(this)

        ManagedTelecom.init(this)

        // Connects to Google Play Billing and refreshes the cached subscription
        // entitlement (also re-acknowledges any purchase missed at buy time).
        BillingManager.init(this)

        // The build stamp goes in every log an exporter ever produces. Without it a tester's log
        // says nothing about WHICH version produced it, so reading a problem means guessing
        // whether a given fix was already in place on that phone.
        debugLine(
            "App",
            "Application starting, version ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
        )

        SoundManager.init()

        val webRtcOptions = PeerConnectionFactory
            .InitializationOptions.builder(this)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(webRtcOptions)
        debugLine("App", "WebRTC native libraries initialized")

        setupLifecycleListener()
    }

    private fun clearLogOnNewInstall() {
        val deviceContext = createDeviceProtectedStorageContext()
        val prefs = deviceContext.getSharedPreferences("install_tracker", Context.MODE_PRIVATE)
        val currentVersion = try {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                packageManager.getPackageInfo(packageName, 0)
            )
        } catch (_: Exception) {
            -1L
        }
        val storedVersion = prefs.getLong("installed_version", -1L)

        if (storedVersion != currentVersion) {
            deviceContext.filesDir.listFiles()?.filter { it.extension == "txt" }?.forEach {
                it.delete()
            }
            prefs.edit { putLong("installed_version", currentVersion) }
        }
    }

    private fun setupLifecycleListener() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        WorkStateSwapper.swapDispatchWorkers(true, this, applicationScope)
        BluetoothPresence.start(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        WorkStateSwapper.swapDispatchWorkers(false, this, applicationScope)
        BluetoothPresence.stop()
    }

    companion object {
        var instance: App? = null
        fun context(): Context = instance!!.applicationContext
    }
}