package com.bolimot.mindtheclub.functions

import android.graphics.BitmapFactory
import android.net.Uri
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.QRClubCodeData
import com.bolimot.mindtheclub.tools.QRCodeData
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

public fun decodeQRCode(uri: Uri): QRCodeData? {
    var bitmap: android.graphics.Bitmap? = null
    val context = App.context()

    try {
        bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }

        if (bitmap == null) {
            debugLine("EntryPoint", "Failed to decode bitmap from URI: $uri")
            return null
        }

        val source = RGBLuminanceSource(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        })

        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val result = MultiFormatReader().decode(binaryBitmap)

        bitmap.recycle()

        return parseQRCode(result.text)


    } catch (e: NotFoundException) {
        debugLine("EntryPoint", "QR code not found in image.")
        return null
    } catch (e: Exception) {
        debugLine("EntryPoint", "Error decoding QR code: ${e.message}")
        return null
    } finally {
        bitmap?.recycle()
    }
}

public fun decodeClubQRCode(uri: Uri): QRClubCodeData? {
    var bitmap: android.graphics.Bitmap? = null
    val context = App.context()

    try {
        bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }

        if (bitmap == null) {
            debugLine("EntryPoint", "Failed to decode bitmap from URI: $uri")
            return null
        }

        val source = RGBLuminanceSource(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        })

        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val result = MultiFormatReader().decode(binaryBitmap)

        bitmap.recycle()

        return parseClubQRCode(result.text)
    } catch (e: NotFoundException) {
        debugLine("EntryPoint", "QR code not found in image.")
        return null
    } catch (e: Exception) {
        debugLine("EntryPoint", "Error decoding QR code: ${e.message}")
        return null
    } finally {
        bitmap?.recycle()
    }
}