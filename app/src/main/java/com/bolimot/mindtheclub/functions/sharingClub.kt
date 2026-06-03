package com.bolimot.mindtheclub.functions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.ContextCompat.getString
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.bolimot.mindtheclub.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun generateQRCodeForClub(text: String): Bitmap? {
    val width = 500
    val height = 500

    try {
        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height)

        val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap[x, y] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return bitmap
    }
    catch(e: Exception) {
        debugLine("generateQRCode", "Exception: ${e.message}")
        return null
    }
}

suspend fun shareClub(textToShare: String, picture: String?, name: String?, description: String?, context: Context) {
    generateClubQRCode(textToShare, context)?.let{
        saveClubBitmap(it, picture, name, description).let{
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, it)
                type = "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Label", getString(context, R.string.share_message))
            clipboard.setPrimaryClip(clip)

            val chooser = Intent.createChooser(shareIntent, getString(context, R.string.share_via))
            context.startActivity(chooser)
        }
    }
}