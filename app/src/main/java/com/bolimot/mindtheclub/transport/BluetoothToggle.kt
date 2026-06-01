package com.bolimot.mindtheclub.transport

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.start.App

object BluetoothToggle {

    const val PERMISSIONS_REQUEST_CODE = 7311

    private val blePermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun isEnabled(context: Context = App.context()): Boolean =
        BluetoothPresence.isEnabledByUser(context)

    enum class EnableStep { STARTED, AWAITING_PERMISSIONS, NEEDS_ADAPTER }

    fun isAdapterOn(context: Context = App.context()): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
        return adapter != null && adapter.isEnabled
    }

    fun enable(activity: Activity): EnableStep {
        val missing = blePermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            return EnableStep.AWAITING_PERMISSIONS
        }
        if (!isAdapterOn(activity)) {
            return EnableStep.NEEDS_ADAPTER
        }
        persistAndStart(activity)
        return EnableStep.STARTED
    }

    fun disable(context: Context = App.context()) {
        setPreference(BluetoothPresence.PREF_BLUETOOTH_ENABLED, "false", context)
        BluetoothPresence.stop()
        debugLine("BluetoothToggle", "Disabled")
    }

    fun onPermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != PERMISSIONS_REQUEST_CODE) return false
        val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            return true
        }
        debugLine("BluetoothToggle", "BLE permissions denied; staying off")
        return false
    }

    private fun persistAndStart(context: Context) {
        setPreference(BluetoothPresence.PREF_BLUETOOTH_ENABLED, "true", context)
        BluetoothPresence.start(context)
        debugLine("BluetoothToggle", "Enabled")
    }
}
