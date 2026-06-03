package com.bolimot.mindtheclub.functions

import android.content.Context
import android.os.PowerManager

fun acquireWakeLock(context: Context) {
    debugLine("WakeLock", "Attempting to acquire wake lock to turn screen on.")
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Suppress("DEPRECATION")
    val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "MindTheClub:IncomingCallWakeLock"
    )
    wakeLock.acquire(10000L /* 10 seconds */)
}