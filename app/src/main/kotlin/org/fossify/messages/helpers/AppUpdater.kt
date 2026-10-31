package org.fossify.messages.helpers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.fossify.messages.BuildConfig
import org.fossify.messages.extensions.config
import org.fossify.messages.receivers.UpdateInstallReceiver
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-updater for Vault Messages.
 *
 * Reads the latest GitHub release of enachealex/Vault-Messages (tags look like "build-N",
 * matching versionCode 1000 + N), downloads its APK and installs it through PackageInstaller.
 * On Android 12+ updates after the first one install without a prompt, because the app
 * becomes the installer of record for itself.
 */
object AppUpdater {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/enachealex/Vault-Messages/releases/latest"
    private const val BUILD_OFFSET = 1000
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 20_000

    const val ACTION_INSTALL_STATUS = "org.fossify.messages.UPDATE_INSTALL_STATUS"

    private val running = AtomicBoolean(false)

    sealed class Result {
        data class UpToDate(val build: Int) : Result()
        data class Installing(val build: Int) : Result()
        data object NeedsInstallPermission : Result()
        data object AlreadyRunning : Result()
        data class Failed(val reason: String) : Result()
    }

    private data class Release(val build: Int, val apkUrl: String)

    fun currentBuild(): Int = BuildConfig.VERSION_CODE - BUILD_OFFSET

    /** Called when the app opens: checks at most every few hours, only if auto-update is on. */
    fun autoCheck(context: Context) {
        val config = context.config
        if (!config.autoUpdateEnabled) return
        if (System.currentTimeMillis() - config.lastUpdateCheck < AUTO_CHECK_INTERVAL_MS) return
        Thread { checkAndInstall(context.applicationContext) }.start()
    }

    /** Blocking: call from a background thread. */
    fun checkAndInstall(context: Context): Result {
        if (!running.compareAndSet(false, true)) return Result.AlreadyRunning
        return try {
            context.config.lastUpdateCheck = System.currentTimeMillis()
            val release = fetchLatestRelease() ?: return Result.Failed("No release found")
            if (release.build <= currentBuild()) return Result.UpToDate(currentBuild())
            if (!canInstall(context)) return Result.NeedsInstallPermission

            val apk = download(context, release)
            install(context, apk)
            Result.Installing(release.build)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }

    fun canInstall(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun installPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    private fun fetchLatestRelease(): Release? {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VaultMessages/${currentBuild()}")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Update server returned ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null

            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return Release(build, asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val target = File(dir, "vault-messages-${release.build}.apk")

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VaultMessages/${currentBuild()}")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Download failed (${connection.responseCode})")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
        }
    }
}
