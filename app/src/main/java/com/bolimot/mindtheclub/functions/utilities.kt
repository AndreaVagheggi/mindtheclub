package com.bolimot.mindtheclub.functions

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat.getString
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.chat.FileDetails
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Broadcast
import com.bolimot.mindtheclub.tools.CallControlEvent
import com.bolimot.mindtheclub.tools.CallEvent
import com.bolimot.mindtheclub.tools.CallEventBus
import com.bolimot.mindtheclub.tools.Type
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WebsiteType {
    YOUTUBE,
    TIKTOK,
    INSTAGRAM,
    NOT_SUPPORTED
}

fun setPreference(key: String, value: String, context: Context){
    val deviceContext = context.createDeviceProtectedStorageContext()
    val sharedPreferences = deviceContext.getSharedPreferences("default", Context.MODE_PRIVATE)

    sharedPreferences.edit {
        putString(key, value)
    }
}

fun getPreference(key: String, context: Context): String? {
    val deviceContext = context.createDeviceProtectedStorageContext()
    val sharedPreferences = deviceContext.getSharedPreferences("default", Context.MODE_PRIVATE)

    return sharedPreferences.getString(key, null)
}

fun guid(): String{
    return UUID.randomUUID().toString().replace("-", "")
}

fun showSnackbarAtTop(message: String, view: View) {
    val snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)

    val snackbarView = snackbar.view
    val params = snackbarView.layoutParams as ViewGroup.MarginLayoutParams

    params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, 0)

    snackbarView.layoutParams = params

    if (snackbarView.layoutParams is FrameLayout.LayoutParams) {
        (snackbarView.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.TOP
    } else if (snackbarView.layoutParams is CoordinatorLayout.LayoutParams) {
        (snackbarView.layoutParams as CoordinatorLayout.LayoutParams).gravity = Gravity.TOP
    }
    snackbar.show()
}

fun keypadIsOpen(rootView: View): Boolean{
    val rect = Rect()

    rootView.getWindowVisibleDisplayFrame(rect)
    val keypadHeight = abs(rootView.height - rect.bottom)

    return keypadHeight < 500
}

fun closeKeyboard(activity: Activity) {
    val currentFocusView = activity.currentFocus ?: View(activity)
    val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
}


fun showToast(message: String?, context: Context) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

fun formatTime(time: Long): String {
    val date = Date(time)
    return SimpleDateFormat("HH.mm", Locale.getDefault()).format(date)
}

fun formatDate(time: Long): String {
    val date = Date(time)
    return if (isToday(time)) {
        getString(App.context(), R.string.today)
    } else {
        SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(date)
    }
}

fun isToday(timestamp: Long): Boolean {
    val today = LocalDate.now(ZoneId.systemDefault())
    val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    return timestamp in startOfDay..endOfDay
}

fun isDifferentDay(timestamp1: Long, timestamp2: Long?): Boolean {
    if (timestamp2 == null) return true

    val date1 = Instant.ofEpochMilli(timestamp1).atZone(ZoneId.systemDefault()).toLocalDate()
    val date2 = Instant.ofEpochMilli(timestamp2).atZone(ZoneId.systemDefault()).toLocalDate()

    return date1 != date2
}

fun emptyString(): String{
    return ""
}

fun appIsForeground(): Boolean {
    return App.instance?.isForeground ?: false
}

fun deleteFile(uri: Uri): Boolean {
    val context = App.context()

    return try {
        when (uri.scheme) {
            "content" -> {
                val rowsDeleted = context.contentResolver.delete(uri, null, null)
                rowsDeleted > 0
            }
            "file" -> {
                val path = uri.path ?: return false
                val file = File(path)
                file.delete()
            }
            else -> false
        }
    } catch (e: Exception) {
        debugLine("deleteFileFromUri", "Exception: ${e.message}")
        false
    }
}

fun toCSVString(uriList: List<Uri>): String {
    return uriList.joinToString(",") { it.toString() }
}

fun splitToList(csvString: String): List<String> {
    return csvString.split(",").map { it.trim() }
}

fun copyUri(uri: Uri?, context: Context): Uri? {
    try {

        if(uri == null) return null

        val contentResolver = context.contentResolver

        val inputStream = contentResolver.openInputStream(uri)

        if (inputStream == null) {
            debugLine("uriToContentUri", "InputStream is null")
            return null
        }

        val mimeType = contentResolver.getType(uri)
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: ""

        val fileName = guid().plus(".$extension")
        val file = File(context.filesDir, fileName)

        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    } catch(ex: Exception) {
        debugLine("uriToContentUri", "Exception: ${ex.message}")
        return null
    }
}

fun extractUrl(text: String): String? {
    val matcher = Patterns.WEB_URL.matcher(text)
    while (matcher.find()) {
        val match = matcher.group() ?: continue
        val start = matcher.start()
        if (start > 0 && text[start - 1] == '@') continue
        if (Patterns.EMAIL_ADDRESS.matcher(match).matches()) continue
        return match
    }
    return null
}

fun startBlinkingAnimation(view: View) {
    val blinkAnimation = AlphaAnimation(0.3f, 1.0f)
    blinkAnimation.duration = 700
    blinkAnimation.interpolator = LinearInterpolator()
    blinkAnimation.repeatMode = Animation.REVERSE
    blinkAnimation.repeatCount = Animation.INFINITE

    view.startAnimation(blinkAnimation)
}

fun hasNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun hasInternetConnectivity(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

fun getFileDetails(contentResolver: ContentResolver, uri: Uri): FileDetails {
    var fullName = "Unknown"
    var size: Long = 0
    var extension = "Unknown"

    when (uri.scheme) {
        "content" -> {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fullName = it.getString(nameIndex)
                    }

                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = it.getLong(sizeIndex)
                    }
                }
            }
        }
        "file" -> {
            val file = File(uri.path!!)
            fullName = file.name
            size = file.length()
        }
    }

    if (fullName.contains(".")) {
        extension = fullName.substringAfterLast(".", "Unknown")
    }

    return FileDetails(fullName, extension, size)
}

fun formatFileSize(sizeInBytes: Long): String {
    val oneKB = 1024
    val oneMB = oneKB * 1024
    val oneGB = oneMB * 1024

    return when {
        sizeInBytes >= oneGB -> "${sizeInBytes / oneGB} GB"
        sizeInBytes >= oneMB -> "${sizeInBytes / oneMB} MB"
        sizeInBytes >= oneKB -> "${sizeInBytes / oneKB} KB"
        else -> "$sizeInBytes Bytes"
    }
}

fun toImage(extension: String, context: Context): Uri? {
    val sanitizedExtension = extension.trim().uppercase()
    if (sanitizedExtension.isEmpty()) {
        return null
    }

    val fileName = "${sanitizedExtension}.fli"
    val imageFile = File(context.filesDir, fileName)

    if (imageFile.exists()) {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                imageFile
            )
        } catch (e: IllegalArgumentException) {
            debugLine("toImage", "Exception: ${e.message}")
            null
        }
    }

    val density = context.resources.displayMetrics.density
    val widthDp = 210
    val heightDp = 210
    val widthPx = (widthDp * density).toInt()
    val heightPx = (heightDp * density).toInt()
    val bitmap = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bitmap)

    canvas.drawColor(Color.BLACK)

    val paint = Paint().apply {
        color = Color.WHITE
        textSize = 60f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val xPos = widthPx / 2f
    val yPos = heightPx / 2f - (paint.descent() + paint.ascent()) / 2

    canvas.drawText(sanitizedExtension, xPos, yPos, paint)

    return try {
        FileOutputStream(imageFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            imageFile
        )
    } catch (e: IOException) {
        debugLine("toImage", "Exception: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        debugLine("toImage", "Exception: ${e.message}")
        null
    }
}

fun isFileType(type: String?): Boolean {
    return type?.take(4) == Type.FILE
}

fun getFileDetailFromType(type: String?): List<String>{
    // [0] = file name, [1] = extension, [2] = size
    if(type == null) return emptyList()
    return splitToList(type.substring(4))
}

fun typeHasImageAttached(type: String?): Boolean{
     return type == Type.IMAGE ||
            type == Type.MULTIPLE_IMAGES ||
            type == Type.GIF ||
            type == Type.VIDEO ||
            type == Type.STICKER ||
            type == Type.AUDIO ||
            type == Type.WEB ||
            type == Type.CONTACT ||
            isFileType(type)
}

fun safeUrl(url: String?): String? {
    if (url.isNullOrEmpty()) return null
    if (url.contains(",")) return null

    return try {
        val uri = url.toUri()
        when (uri.scheme?.lowercase()) {
            "content", "file" -> {
                if (url.startsWith("file:///android_asset/")) {
                    val assetPath = url.removePrefix("file:///android_asset/")
                    return try {
                        App.context().assets.open(assetPath).use { }
                        url
                    } catch (e: Exception) {
                        debugLine("safeUrl", "Asset file not found: ${e.message}")
                        null
                    }
                } else {
                    App.context().contentResolver.openInputStream(uri)?.use { }
                    url
                }
            }
            "http", "https" -> {
                if (Patterns.WEB_URL.matcher(url).matches()) url else null
            }
            else -> null
        }
    } catch (e: Exception) {
        debugLine("safeUrl", "Exception: ${e.message}")
        null
    }
}

fun makeContent(content: String, context: Context): Uri {
    val fileUri = content.toUri()
    return if (fileUri.scheme == "file") {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(fileUri.path!!))
    } else {
        fileUri
    }
}

suspend fun emitWebRtcControlEvent(action: String, remoteUserId: String, reason: String? = null) {
    debugLine("emitWebRtcControlEvent", "action: $action, reason: $reason")
    CallEventBus.callControlFlow.emit(CallControlEvent(action = action, remoteUserId = remoteUserId, reason = reason))
    broadcastWebRTCEvents(action, reason)
}

fun isLowEndDevice(): Boolean {
    val isOlderAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
    val activityManager = App.context().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()

    activityManager.getMemoryInfo(memoryInfo)

    val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
    val isLowMemory = availableMemoryMB < 1024

    return isOlderAndroid || isLowMemory
}

suspend fun waitForInternetConnection(
    context: Context,
    timeoutMillis: Long = 30000L,
    checkIntervalMillis: Long = 1000L
): Boolean {
    debugLine("NetworkWait", "Starting to wait for internet connection for ${timeoutMillis / 1000}s...")
    val result = withTimeoutOrNull(timeoutMillis) {
        while (isActive) {
            if (hasInternetConnectivity(context)) {
                debugLine("NetworkWait", "Internet connection established.")
                return@withTimeoutOrNull true
            }
            debugLine("NetworkWait", "No internet yet, waiting ${checkIntervalMillis / 1000}s before next check...")
            delay(checkIntervalMillis)
        }

        debugLine("NetworkWait", "Coroutine became inactive before connection was established (not due to timeout).")
        false
    }

    if (result == null) {
        debugLine("NetworkWait", "Timed out after ${timeoutMillis / 1000}s waiting for internet connection.")
        return false
    }

    return result
}

@SuppressLint("Wakelock")
fun Activity.wakeUpPhone() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!powerManager.isInteractive) {
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MindTheClub:Wakeup"
        )

        wakeLock?.acquire(10000L)
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
        @Suppress("DEPRECATION")
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    } else {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }
}

fun View.applyNavigationBarPadding() {
    val initialPaddingBottom = this.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        view.updatePadding(bottom = initialPaddingBottom + navBarInsets.bottom)
        insets
    }
}

fun broadcastWebRTCEvents(action: String, content: String? = null) {
    val broadcastMessage: String = when (action) {
        CallEvent.CONNECTION_FAILED -> Broadcast.ACTION_CALL_FAILED
        CallEvent.CONNECTION_OPEN -> Broadcast.ACTION_WEBRTC_CONNECTION_OPEN
        CallEvent.DATA_CHANNEL_OPEN -> Broadcast.ACTION_DATA_CHANNEL_OPEN

        else -> Broadcast.ACTION_CALL_UNKNOWN_REMOTE_EVENT
    }

    val intent = Intent(broadcastMessage)

    content?.let {
        intent.putExtra(Broadcast.ACTION_CONTENT, it)
    }

    debugLine("emitBroadcast", "Sending broadcast: $broadcastMessage, Content: $content")
    LocalBroadcastManager.getInstance(App.context()).sendBroadcast(intent)
}

suspend fun computeSha256(inputStream: java.io.InputStream): String? = withContext(Dispatchers.IO) {
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        debugLine("computeSha256", "Failed: ${e.message}")
        null
    } finally {
        inputStream.close()
    }
}