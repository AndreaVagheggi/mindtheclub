package com.bolimot.mindtheclub.functions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.bolimot.mindtheclub.BuildConfig
import com.bolimot.mindtheclub.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "AppUpdater"
private const val REPO = "https://fdroid.mindtheclub.com/repo"

/**
 * "Check for updates" in the About dialog (OptionsActivity).
 *
 * MTCX BUILD. The Play version of this file uses Google Play In-App Updates; this one reads the
 * F-Droid archive directly, because F-Droid itself refreshes its index only when it decides to
 * (or from My apps, ⋮, Check for updates), so a fresh release can stay invisible there for hours.
 *
 *  1. reads index-v1.json from the archive and takes the highest versionCode of this package
 *  2. newer than the installed one: asks the user, downloads the APK, checks its SHA-256
 *     against the index
 *  3. hands it to Android's PackageInstaller. Android installs it over this App only if it is
 *     signed with the same key, so a wrong or tampered file is always refused by the system.
 *
 * The user sees Android's own "Update" confirmation every time: this App is not the installer
 * of record (F-Droid is), so the confirmation cannot be skipped. The first time, Android also
 * asks to allow MindTheClub to install apps (REQUEST_INSTALL_PACKAGES, mtcx manifest only).
 *
 * Same class name and public API as the Play version, so OptionsActivity is identical in both
 * apps. When merging main into degoogle, keep THIS version of the file.
 */
class AppUpdater(private val activity: ComponentActivity) {

    private class Release(
        val versionCode: Long,
        val versionName: String,
        val apkName: String,
        val sha256: String,
        val size: Long
    )

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** [onChecked] runs once the answer is known (to re-enable the button). */
    fun check(onChecked: () -> Unit = {}) {
        activity.lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { fetchLatest() }
            onChecked()
            when {
                latest == null ->
                    showToast(activity.getString(R.string.update_check_failed), activity)

                latest.versionCode <= BuildConfig.VERSION_CODE.toLong() ->
                    showToast(activity.getString(R.string.update_up_to_date), activity)

                else -> askToUpdate(latest)
            }
        }
    }

    private fun fetchLatest(): Release? = try {
        val request = Request.Builder()
            .url("$REPO/index-v1.json")
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                debugLine(TAG, "index HTTP ${response.code}")
                return null
            }
            val packages = JSONObject(response.body!!.string())
                .getJSONObject("packages")
                .optJSONArray(BuildConfig.APPLICATION_ID)
                ?: return null
            var best: Release? = null
            for (i in 0 until packages.length()) {
                val p = packages.getJSONObject(i)
                if (p.optString("hashType") != "sha256") continue
                val code = p.getLong("versionCode")
                if (best == null || code > best.versionCode) {
                    best = Release(
                        code,
                        p.optString("versionName"),
                        p.getString("apkName"),
                        p.getString("hash").lowercase(),
                        p.optLong("size", -1)
                    )
                }
            }
            best
        }
    } catch (e: Exception) {
        debugLine(TAG, "index read failed: ${e.message}")
        null
    }

    private fun askToUpdate(release: Release) {
        val shownVersion = release.versionName.removePrefix("Release ").trim()
        MaterialAlertDialogBuilder(activity)
            .setMessage(activity.getString(R.string.update_available, shownVersion))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(release) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startUpdate(release: Release) {
        // Android 8+: installing needs the per-app "install unknown apps" permission.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.update_allow_install)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val progress = LinearProgressIndicator(activity).apply {
            isIndeterminate = release.size <= 0
            max = 100
            setPadding(64, 32, 64, 0)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.update_downloading)
            .setView(progress)
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) {
                download(release) { percent -> activity.runOnUiThread { progress.progress = percent } }
            }
            dialog.dismiss()
            if (apk == null) {
                showToast(activity.getString(R.string.update_failed), activity)
                return@launch
            }
            val committed = withContext(Dispatchers.IO) { install(apk) }
            if (!committed) showToast(activity.getString(R.string.update_failed), activity)
        }
    }

    /** Downloads the APK into the cache and checks it against the index hash. */
    private fun download(release: Release, onProgress: (Int) -> Unit): File? {
        val dir = File(activity.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "mtcx-${release.versionCode}.apk")
        return try {
            val request = Request.Builder().url("$REPO/${release.apkName}").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    debugLine(TAG, "apk HTTP ${response.code}")
                    return null
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastPercent = -1
                response.body!!.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (release.size > 0) {
                                val percent = (done * 100 / release.size).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != release.sha256) {
                    debugLine(TAG, "apk hash mismatch")
                    file.delete()
                    return null
                }
                file
            }
        } catch (e: Exception) {
            debugLine(TAG, "apk download failed: ${e.message}")
            file.delete()
            null
        }
    }

    /** Writes the APK into a PackageInstaller session; the result arrives in the receiver. */
    private fun install(apk: File): Boolean = try {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(activity.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // FLAG_MUTABLE: the system writes the status extras into this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(
                activity,
                sessionId,
                Intent(activity, AppUpdateInstallReceiver::class.java),
                flags
            )
            session.commit(status.intentSender)
        }
        apk.delete()
        true
    } catch (e: Exception) {
        debugLine(TAG, "install session failed: ${e.message}")
        apk.delete()
        false
    }
}

/**
 * Result of the PackageInstaller session started by [AppUpdater]. Declared in the mtcx manifest,
 * not exported. On success there is nothing to do: Android replaces and stops this App.
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }

            PackageInstaller.STATUS_SUCCESS -> debugLine(TAG, "update installed")

            else -> {
                // STATUS_FAILURE_ABORTED is the user tapping Cancel on Android's dialog: no toast.
                debugLine(TAG, "install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    showToast(context.getString(R.string.update_failed), context)
                }
            }
        }
    }
}
