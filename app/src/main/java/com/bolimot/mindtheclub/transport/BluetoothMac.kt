package com.bolimot.mindtheclub.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App

object BluetoothMac {

    fun getMyMac(context: Context = App.context()): String? {
        val viaSettings = tryReadFromSettings(context)
        if (viaSettings != null) {
            debugLine("BluetoothMac", "MAC obtained via Settings.Secure")
            return viaSettings
        }
        val viaReflection = tryReadViaReflection(context)
        if (viaReflection != null) {
            debugLine("BluetoothMac", "MAC obtained via reflection")
            return viaReflection
        }
        debugLine("BluetoothMac", "MAC unavailable on this device")
        return null
    }

    private fun tryReadFromSettings(context: Context): String? {
        return try {
            @Suppress("HardwareIds")
            val mac = Settings.Secure.getString(context.contentResolver, "bluetooth_address")
            if (mac.isNullOrBlank() || mac == "02:00:00:00:00:00") null else mac
        } catch (e: Exception) {
            debugLine("BluetoothMac", "Settings.Secure read failed: ${e.message}")
            null
        }
    }

    private fun tryReadViaReflection(context: Context): String? {
        return try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return null
            val field = BluetoothAdapter::class.java.getDeclaredField("mAddress")
            field.isAccessible = true
            val mac = field.get(adapter) as? String
            if (mac.isNullOrBlank() || mac == "02:00:00:00:00:00") null else mac
        } catch (e: Exception) {
            debugLine("BluetoothMac", "Reflection read failed: ${e.message}")
            null
        }
    }
}