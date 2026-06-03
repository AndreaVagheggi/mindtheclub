package com.bolimot.mindtheclub.functions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

public fun printAppSignature(context: Context) {
    val tag = "APP_SIGNATURE"
    try {
        val packageName = context.packageName
        val packageManager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }

        if (signatures.isNullOrEmpty()) {
            debugLine(tag, "No signatures found.")
            return
        }

        for (signature in signatures) {
            val messageDigest = MessageDigest.getInstance("SHA-1")
            messageDigest.update(signature.toByteArray())
            val sha1 = messageDigest.digest().joinToString(":") {
                String.format("%02X", it)
            }
            debugLine(tag, "SHA-1 Fingerprint: $sha1")
        }
    } catch (e: Exception) {
        debugLine(tag, "Error getting signature: ${e.message}")
    }
}