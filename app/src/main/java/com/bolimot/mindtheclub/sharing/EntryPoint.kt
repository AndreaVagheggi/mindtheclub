package com.bolimot.mindtheclub.sharing

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.functions.copyUri
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.decodeQRCode
import com.bolimot.mindtheclub.functions.extractUrl
import com.bolimot.mindtheclub.start.MainActivity
import com.bolimot.mindtheclub.tools.Share
import com.bolimot.mindtheclub.tools.Type
import com.bolimot.mindtheclub.views.AppTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EntryPoint : AppCompatActivity() {
    private var myUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = this.intent
        if (intent?.action == Intent.ACTION_SEND && intent.type != null) {
            lifecycleScope.launch {
                handleIncomingIntent(intent)
            }
        } else {
            finish()
        }
    }

    private suspend fun handleIncomingIntent(intent: Intent) {
        val type = intent.type ?: return
        val uri = getUriFromIntent(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val isTextType = type.contains("text", ignoreCase = true)

        when {
            uri == null && text.isNullOrEmpty() -> {
                debugLine("handleSendIntent", "uri is null and text is empty")
                finish()
                return
            }

            !isTextType && uri == null -> {
                debugLine("handleSendIntent", "uri is null and type is not text")
                finish()
                return
            }
        }

        val myCopiedUri = withContext(Dispatchers.IO) {
            if (uri != null) {
                if (!canAccessUri(uri)) {
                    debugLine("handleSendIntent", "Cannot access the provided URI.")
                    null
                } else {
                    copyUri(uri, this@EntryPoint)
                }
            } else {
                null
            }
        }

        if (uri != null && myCopiedUri == null) {
            finish()
            return
        }

        myUri = myCopiedUri

        debugLine("EntryPoint", "Received intent with type: $type, uri: $myUri, text: $text")
        val loweredType = type.lowercase()
        when {
            "gif" in loweredType -> startMainApp(myUri, Type.GIF)
            "video" in loweredType -> startMainApp(myUri, Type.VIDEO)
            "image" in loweredType -> handleProfile(intent)
            "audio" in loweredType -> startMainApp(myUri, Type.AUDIO)
            "text" in loweredType -> when {
                text.isNullOrEmpty() -> {
                    debugLine("EntryPoint", "No text content to share.")
                    finish()
                }

                extractUrl(text) != null -> startMainApp(null, Type.WEB, text)

                else -> startMainApp(null, Type.TEXT, text)
            }
            else -> {
                debugLine("EntryPoint", "Unsupported MIME type: $type")
                finish()
            }
        }
    }

    private fun canAccessUri(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.close()
            true
        } catch (e: SecurityException) {
            debugLine("canAccessUri", "Security exception: ${e.message}")
            false
        } catch (e: Exception) {
            debugLine("canAccessUri", "Exception: ${e.message}")
            false
        }
    }

    private fun startMainApp(uri: Uri?, type: String, text: String = "") {

        debugLine("StartMainApp", "Starting MainApp, uri = ${uri.toString()}, type = $type")

        val startAppIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("sharing", Share.CONTENT)
            putExtra("type", type)
            putExtra("text", text)

            uri?.let { putExtra("uri", it.toString()) }

            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        startActivity(startAppIntent)
        finish()
    }

    private suspend fun handleProfile(intent: Intent) {
        getUriFromIntent(intent)?.let { incomingUri ->
            val qrCodeData = withContext(Dispatchers.IO) {
                decodeQRCode(incomingUri)
            }

            if (qrCodeData != null && qrCodeData.name.isNotEmpty() && qrCodeData.userId.isNotEmpty()) {
                val startAppIntent = Intent(this, AppTab::class.java).apply {
                    putExtra("sharing", Share.PROFILE)
                    putExtra("name", qrCodeData.name)
                    putExtra("userId", qrCodeData.userId)
                    putExtra("bio", qrCodeData.bio)
                    putExtra("fingerprint", qrCodeData.fingerprint)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(startAppIntent)
                finish()
                return
            }

            debugLine("handleProfile", "It's not a profile, it's an image")

            val hasAlpha = withContext(Dispatchers.IO) {
                hasTransparency(incomingUri)
            }

            if(!hasAlpha) {
                startMainApp(myUri, Type.IMAGE)
            } else {
                startMainApp(myUri, Type.STICKER)
            }
        }
    }

    private fun hasTransparency(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val hasAlpha = bitmap.hasAlpha()
                bitmap.recycle()
                hasAlpha
            } ?: false
        } catch (e: Exception) {
            debugLine("hasTransparency", "Exception: ${e.message}")
            false
        }
    }

    private fun getUriFromIntent(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
