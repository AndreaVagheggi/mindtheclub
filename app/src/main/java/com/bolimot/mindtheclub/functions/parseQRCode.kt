package com.bolimot.mindtheclub.functions

import androidx.core.net.toUri
import com.bolimot.mindtheclub.tools.QRClubCodeData
import com.bolimot.mindtheclub.tools.QRCodeData

fun parseQRCode(qr: String?): QRCodeData? {
    if(qr.isNullOrEmpty()) return null

    // Invite-link form: https://www.mindtheclub.com/add?n=..&u=..&b=..&f=..
    // The QR codes carry this link so that third-party scanners fall into the
    // same install/add-contact flow as tapping the link; legacy "mtc;" QR codes
    // are still accepted below.
    if (qr.startsWith("https://") || qr.startsWith("http://")) {
        val uri = qr.toUri()
        val name = uri.getQueryParameter("n")
        val userId = uri.getQueryParameter("u")
        if (name.isNullOrEmpty() || userId.isNullOrEmpty()) return null

        return QRCodeData(name, userId, uri.getQueryParameter("b") ?: "", uri.getQueryParameter("f") ?: "")
    }

    val parts = qr.split(";")

    if (parts.size >= 4 && parts[0] == "mtc") {
        val name = parts[1]
        val userId = parts[2]
        val bio = parts[3]
        val fingerprint = if (parts.size >= 5) parts[4] else ""

        return QRCodeData(name, userId, bio, fingerprint)
    } else {
        return null
    }
}

fun parseClubQRCode(qr: String?): QRClubCodeData? {
    if(qr.isNullOrEmpty()) return null

    val parts = qr.split(";")

    if (parts.size == 4 && parts[0] == "mtcl") {
        val name = parts[1]
        val clubId = parts[2]
        val description = parts[3]

        return QRClubCodeData(name, clubId, description)
    } else {
        return null
    }
}