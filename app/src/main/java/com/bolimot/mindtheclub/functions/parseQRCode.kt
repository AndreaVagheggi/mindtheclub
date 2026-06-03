package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.tools.QRClubCodeData
import com.bolimot.mindtheclub.tools.QRCodeData

fun parseQRCode(qr: String?): QRCodeData? {
    if(qr.isNullOrEmpty()) return null

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