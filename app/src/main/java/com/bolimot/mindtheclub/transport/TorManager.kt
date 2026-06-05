@file:Suppress("DEPRECATION")
package com.bolimot.mindtheclub.transport

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Socket

@Suppress("unused")
object TorManager {

    private const val SOCKS_PORT = 9050
    private val ready = MutableStateFlow(false)

    @Volatile private var bound = false
    @Volatile private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(TorService.EXTRA_STATUS)
            debugLine("TorManager", "Tor status: $status")
            when (status) {
                TorService.STATUS_ON -> ready.value = true
                TorService.STATUS_OFF,
                TorService.STATUS_STOPPING,
                TorService.STATUS_STARTING -> ready.value = false
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            debugLine("TorManager", "TorService bound")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            debugLine("TorManager", "TorService unbound/disconnected")
            ready.value = false
            bound = false
        }
    }

    @Synchronized
    fun start() {
        val ctx = App.context()
        try {
            if (!receiverRegistered) {
                LocalBroadcastManager.getInstance(ctx)
                    .registerReceiver(statusReceiver, IntentFilter(TorService.ACTION_STATUS))
                receiverRegistered = true
            }
            if (!bound) {
                val intent = Intent(ctx, TorService::class.java)
                bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                debugLine("TorManager", "bindService requested (bound=$bound)")
            }
        } catch (e: Exception) {
            debugLine("TorManager", "start failed: ${e.message}")
        }
    }

    private suspend fun socksAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 1500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun awaitReady(timeoutMs: Long): Int? {
        if (ready.value && socksAlive()) return SOCKS_PORT

        start()

        val gotOn = withTimeoutOrNull(timeoutMs) { ready.first { it } } != null
        if (!gotOn) {
            debugLine("TorManager", "Tor not ready within ${timeoutMs}ms")
            return null
        }
        return if (socksAlive()) {
            debugLine("TorManager", "Tor ready on SOCKS $SOCKS_PORT")
            SOCKS_PORT
        } else {
            debugLine("TorManager", "Tor reported ON but SOCKS not accepting")
            null
        }
    }
}

