package com.bolimot.mindtheclub.functions

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.content.ContextCompat.getString
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.firebase.getFirebaseValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

fun generateQRCode(text: String, context: Context): Bitmap? {
    val width = 500
    val height = 500

    try {
        // Error correction H (30% redundancy) keeps the QR scannable with the
        // logo covering its centre.
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H
        hints[EncodeHintType.MARGIN] = 1

        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height, hints)

        val qrBitmap = createBitmap(width, height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                qrBitmap[x, y] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.mtc_logo_icon)

        val logoSize = width / 5
        val scaledLogo = logo.scale(logoSize, logoSize, false)

        val combinedBitmap = createBitmap(width, height)
        val canvas = Canvas(combinedBitmap)

        canvas.drawBitmap(qrBitmap, 0f, 0f, null)

        val xPos = (width - scaledLogo.width) / 2f
        val yPos = (height - scaledLogo.height) / 2f

        canvas.drawBitmap(scaledLogo, xPos, yPos, null)

        return combinedBitmap
    }
    catch(e: Exception) {
        debugLine("generateQRCode", "Exception: ${e.message}")
        return null
    }
}

fun generateClubQRCode(text: String, context: Context): Bitmap? {
    val width = 400
    val height = 400

    try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H
        hints[EncodeHintType.MARGIN] = 1

        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height, hints)

        val qrBitmap = createBitmap(width, height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                qrBitmap[x, y] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.mtc_logo_icon_png)

        val logoSize = width / 5
        val scaledLogo = logo.scale(logoSize, logoSize, false)

        val combinedBitmap = createBitmap(width, height)
        val canvas = Canvas(combinedBitmap)

        canvas.drawBitmap(qrBitmap, 0f, 0f, null)

        val xPos = (width - scaledLogo.width) / 2f
        val yPos = (height - scaledLogo.height) / 2f

        canvas.drawBitmap(scaledLogo, xPos, yPos, null)

        return combinedBitmap

    } catch(e: Exception) {
        debugLine("generateQRCode", "Exception: ${e.message}")
        return null
    }
}

fun buildInviteLink(name: String, userId: String, bio: String, fingerprint: String): String {
    return Uri.Builder()
        .scheme("https")
        .authority("www.mindtheclub.com")
        .appendPath("add")
        .appendQueryParameter("n", name)
        .appendQueryParameter("u", userId)
        .appendQueryParameter("b", bio)
        .appendQueryParameter("f", fingerprint)
        .build()
        .toString()
}

suspend fun shareMyProfile(textToShare: String, context: Context) {
    val inviteLink = parseQRCode(textToShare)?.let { qr ->
        buildInviteLink(qr.name, qr.userId, qr.bio, qr.fingerprint)
    } ?: getFirebaseValue("MTC_URL")

    val text = getString(context, R.string.share_message) + "\n\n" + inviteLink

    val subject = "${getPreference("myName", context)} - ${getString(context, R.string.join_message)}"

    // Plain text share: the link arrives intact and every receiving app unfurls it into a rich
    // preview (the Open Graph card from the /add landing page), consistently across WhatsApp,
    // Messenger, SMS. Attaching the QR image here would suppress that preview and, in apps like
    // Messenger, drop the text and the link entirely. The QR stays available on the dedicated
    // "Show QR code" screen for in-person scanning.
    val shareIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        type = "text/plain"
    }

    val chooser = Intent.createChooser(shareIntent, getString(context, R.string.share_via))
    context.startActivity(chooser)
}

fun shareMyQRCode(payload: String, context: Context) {
    // The QR encodes the invite link so a third-party scanner triggers the same
    // install/add-contact flow as tapping the link; the in-app scanner parses it too.
    val inviteLink = parseQRCode(payload)?.let { qr ->
        buildInviteLink(qr.name, qr.userId, qr.bio, qr.fingerprint)
    } ?: return

    val qrUri = saveBitmap(generateQRCode(inviteLink, context), "myQRCode.jpg", 100) ?: return

    // Image-only share: attaching text alongside the picture makes several apps
    // (Messenger, some SMS clients) drop one of the two, so the QR travels alone.
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, qrUri)
        putExtra(Intent.EXTRA_SUBJECT, "${getPreference("myName", context)} - ${getString(context, R.string.join_message)}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val chooser = Intent.createChooser(shareIntent, getString(context, R.string.share_via))
    context.startActivity(chooser)
}