package dev.hearthd.android.kiosk.update

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.hearthd.android.kiosk.settings.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches a channel manifest, downloads + verifies the APK, and installs it.
 *
 * Install uses a single [PackageInstaller] session for both paths: if the app
 * is the device owner the commit installs silently; otherwise the system raises
 * a confirm prompt (handled by [InstallStatusReceiver]).
 */
class Updater(private val context: Context) {
    private val baseUrl = "https://assets.hearthd.dev/android/kiosk"

    suspend fun fetchManifest(channel: Channel): UpdateManifest = withContext(Dispatchers.IO) {
        val conn = (URL("$baseUrl/${channel.id}.json").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("manifest fetch failed: HTTP ${conn.responseCode}")
            }
            UpdateManifest.fromJson(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the manifest's APK into the cache and verifies its sha256. */
    suspend fun download(manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        // Drop any previously downloaded APKs (installed ones we never cleaned
        // up, plus partial/failed downloads) before fetching a new one, so the
        // cache can't grow without bound and we have room to stage the install.
        cleanDownloads()
        val out = File(context.cacheDir, "update-${manifest.sha256}.apk")
        URL(manifest.apkUrl).openStream().use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        val actual = sha256(out)
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            out.delete()
            throw IOException("sha256 mismatch: expected ${manifest.sha256}, got $actual")
        }
        out
    }

    suspend fun install(apk: File): Unit = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Tell the installer up front how much it needs so it can reserve the
        // space (and fail cleanly if it can't) rather than allocating blindly.
        params.setSize(apk.length())
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(context, InstallStatusReceiver::class.java)
            // PackageInstaller must be able to fill in the status intent, so it
            // has to be mutable. FLAG_MUTABLE only exists from API 31; below that
            // PendingIntents are mutable by default.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Removes every downloaded update APK from the cache. */
    fun cleanDownloads() {
        context.cacheDir
            .listFiles { file -> file.name.startsWith("update-") && file.name.endsWith(".apk") }
            ?.forEach { it.delete() }
    }

    fun isDeviceOwner(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
