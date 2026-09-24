package dev.hearthd.android.kiosk.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.security.MessageDigest

/**
 * The device's identity on the `/state` request: a stable token the server keys a
 * per-device view off, derived from the hardware serial.
 *
 * Derived rather than minted because the serial is the only per-device value that
 * outlives a factory reset. Everything the app persists lives in `/data`, which a
 * reset wipes, and `ANDROID_ID` is regenerated — so a generated token would need
 * re-pairing after every reset, while a derived one comes back on its own.
 */
object DeviceToken {
    /** The permission [read] needs. Runtime-granted, so the operator sees a prompt. */
    const val PERMISSION: String = Manifest.permission.READ_PHONE_STATE

    /** The `/state` query parameter the token travels in. */
    const val QUERY_PARAM: String = "device"

    /** Whether the serial is readable right now. */
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * This device's token, or null when the serial isn't readable — the permission
     * isn't granted, or the platform withholds it. Android 10 closed `getSerial()`
     * to anything without a privileged permission, so on API 29+ this is always
     * null; the supported hardware (Portal, API 28) predates that. A null token
     * means `/state` goes out unidentified, exactly as it did before, and the
     * server answers with whatever it serves an unknown device.
     */
    // MissingPermission: guarded by the granted() check above, which lint can't
    // follow through a helper. HardwareIds: reading the serial is the point —
    // see the class comment for why a generated id won't do.
    @SuppressLint("MissingPermission", "HardwareIds")
    fun read(context: Context): String? {
        if (!granted(context)) return null
        val serial = runCatching { Build.getSerial() }.getOrNull()
        if (serial.isNullOrBlank() || serial == Build.UNKNOWN) return null
        return derive(serial)
    }

    /** Hash and truncate [serial]. Pure, and split out from [read] to stay so. */
    internal fun derive(serial: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT + serial).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(LENGTH)

    // Namespaces the digest, so the token can't be matched against a hash of the
    // same serial computed anywhere else.
    private const val SALT = "hearthd-kiosk/device/"

    // 64 bits of the digest, hex. The server only needs uniqueness across a
    // household's worth of devices, and a short token is one someone can read off
    // the Device pane and type into a config file.
    private const val LENGTH = 16
}
