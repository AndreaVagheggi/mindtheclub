package com.bolimot.mindtheclub.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.start.App

object BluetoothPresence {

    @Suppress("MemberVisibilityCanBePrivate")
    const val PREF_BLUETOOTH_ENABLED = "mtc_bluetooth_transport_enabled"

    @Volatile private var running = false

    @Suppress("MemberVisibilityCanBePrivate")
    fun isEnabledByUser(context: Context = App.context()): Boolean =
        getPreference(PREF_BLUETOOTH_ENABLED, context) == "true"

    fun start(context: Context = App.context()) {
        if (running) return
        if (!isEnabledByUser(context)) {
            debugLine("BluetoothPresence", "Not starting: disabled by user")
            return
        }
        val adapter = adapter(context) ?: run {
            debugLine("BluetoothPresence", "Not starting: no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            debugLine("BluetoothPresence", "Not starting: adapter disabled")
            return
        }
        running = true
        BluetoothServer.start(context)
        debugLine("BluetoothPresence", "Started")
    }

    fun stop() {
        if (!running) return
        running = false
        BluetoothServer.stop()
        debugLine("BluetoothPresence", "Stopped")
    }

    fun deviceForMac(mac: String, context: Context = App.context()): BluetoothDevice? {
        val adapter = adapter(context) ?: return null
        return try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            debugLine("BluetoothPresence", "Invalid MAC: $mac")
            null
        }
    }

    fun isBonded(device: BluetoothDevice): Boolean =
        try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            debugLine("BluetoothPresence", "bondState SecurityException: ${e.message}")
            false
        }

    private val bondRequestedThisSession = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    fun requestBondOncePerSession(userId: String, device: BluetoothDevice): Boolean {
        if (!bondRequestedThisSession.add(userId)) return false
        return try {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_BONDING -> false
                else -> {
                    debugLine("BluetoothPresence", "Initiating pairing with $userId")
                    device.createBond()
                }
            }
        } catch (e: SecurityException) {
            debugLine("BluetoothPresence", "createBond SecurityException: ${e.message}")
            false
        }
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}