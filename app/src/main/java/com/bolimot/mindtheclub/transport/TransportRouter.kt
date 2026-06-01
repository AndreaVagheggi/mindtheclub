package com.bolimot.mindtheclub.transport

import android.content.Context
import com.bolimot.mindtheclub.dataModels.RTCClientResult
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.webrtc.ConnectionManager
import com.bolimot.mindtheclub.functions.debugLine

sealed class TransportResult {
    data class Connected(val transport: Transport) : TransportResult()
    data class Failed(val result: RTCClientResult) : TransportResult()
}

object TransportRouter {

    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(
        remoteUserId: String,
        senderContentKey: String,
        context: Context
    ): TransportResult {
        if (BluetoothPresence.isEnabledByUser(context)) {
            val bt = tryBluetooth(remoteUserId, context)
            if (bt != null) {
                debugLine("TransportRouter", "Bluetooth selected for $remoteUserId")
                return TransportResult.Connected(bt)
            }
        }
        return connectWebRtc(remoteUserId, senderContentKey, context)
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun tryBluetooth(remoteUserId: String, context: Context): Transport? {
        val dao = com.bolimot.mindtheclub.functions.getPeerDao(context)
        val storedMac = dao.getPeer(remoteUserId)?.bluetoothMac

        if (!storedMac.isNullOrEmpty()) {
            val device = BluetoothPresence.deviceForMac(storedMac, context)
            if (device != null && BluetoothPresence.isBonded(device)) {
                val bt = BluetoothTransport.open(remoteUserId, context)
                if (bt != null) return bt
                debugLine("TransportRouter", "Stored MAC failed for $remoteUserId, will probe paired devices")
            }
        }

        return discoverAndOpen(remoteUserId, context, dao)
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun discoverAndOpen(
        remoteUserId: String,
        context: Context,
        dao: com.bolimot.mindtheclub.database.peer.PeerDao
    ): Transport? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter ?: return null

        val pairedDevices = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            debugLine("TransportRouter", "bondedDevices SecurityException: ${e.message}")
            return null
        }

        if (pairedDevices.isEmpty()) {
            debugLine("TransportRouter", "No paired devices, cannot use Bluetooth")
            return null
        }

        debugLine("TransportRouter", "Probing ${pairedDevices.size} paired device(s) for $remoteUserId")

        for (device in pairedDevices) {
            val discoveredUserId = BluetoothTransport.probeAndGetRemoteUserId(device) ?: continue
            val deviceAddress = try { device.address } catch (e: SecurityException) { null } ?: continue

            dao.updatePeerBluetoothMac(discoveredUserId, deviceAddress)
            debugLine("TransportRouter", "Stored MAC $deviceAddress for $discoveredUserId via probe")

            if (discoveredUserId == remoteUserId) {
                val bt = BluetoothTransport.open(remoteUserId, context)
                if (bt != null) return bt
                debugLine("TransportRouter", "Open failed after successful probe for $remoteUserId")
                return null
            }
        }

        debugLine("TransportRouter", "No MTC peer found among paired devices for $remoteUserId")
        return null
    }

    private suspend fun connectWebRtc(
        remoteUserId: String,
        senderContentKey: String,
        context: Context
    ): TransportResult {
        ConnectionManager.instance.setDispatchContentHint(remoteUserId, senderContentKey)

        val channelId = guid()
        val callId = "NotACall"

        val result = ConnectionManager.instance.webRTCConnect(
            channelId, callId, remoteUserId, true, context,
            video = false,
            dataOnly = true
        )

        return if (result is RTCClientResult.Success) {
            TransportResult.Connected(result.RTCClientWrapper)
        } else {
            TransportResult.Failed(result)
        }
    }
}
