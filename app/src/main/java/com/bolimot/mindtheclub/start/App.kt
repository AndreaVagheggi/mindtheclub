package com.bolimot.mindtheclub.start

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.SoundManager
import com.bolimot.mindtheclub.transport.BluetoothPresence
import com.bolimot.mindtheclub.voip.ManagedTelecom
import com.bolimot.mindtheclub.works.WorkStateSwapper
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
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

        FirebaseApp.initializeApp(this)

        val firebaseAppCheck = FirebaseAppCheck.getInstance()

        if (BuildConfig.DEBUG) {
            firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        } else {
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }
        ManagedTelecom.init(this)

        debugLine("App", "Application starting")

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