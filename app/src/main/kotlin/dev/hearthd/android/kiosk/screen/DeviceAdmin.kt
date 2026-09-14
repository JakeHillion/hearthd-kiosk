package dev.hearthd.android.kiosk.screen

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.hearthd.android.kiosk.update.KioskDeviceAdminReceiver

/**
 * The device-admin registration behind [ScreenPowerController]'s screen-off.
 *
 * Turning the display genuinely off is not something an ordinary app can do;
 * `lockNow()` is, given an active admin holding `force-lock`. That is a normal
 * user consent — [activationIntent] raises the system's "activate this device
 * admin?" dialog — and not the device-owner provisioning the app also knows
 * about, which a device with accounts on it can never grant.
 *
 * The useful accident is that `lockNow()`'s contract on a device with no lock
 * type set is to *"force the device to go to sleep but not lock the device"* —
 * exactly a display power switch, and the reason the kiosk wants the screen
 * lock left at None. With a secure lock set it would work too, but every sleep
 * would then demand a PIN on the way back in.
 */
class DeviceAdmin(private val context: Context) {
    private val component = ComponentName(context, KioskDeviceAdminReceiver::class.java)

    private val dpm: DevicePolicyManager
        get() = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /**
     * Whether the platform offers device administration at all. From API 30 a
     * `lockNow()` on a device without the feature *returns silently* rather than
     * throwing, so a kiosk that didn't check this would simply never go dark and
     * give no clue why. Forked Android builds are where this is worth doubting.
     */
    val supported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)

    /** Whether the operator has granted us admin. */
    val active: Boolean
        get() = dpm.isAdminActive(component)

    /** True when [sleep] can be expected to do something. */
    val canSleep: Boolean
        get() = supported && active

    /** The consent dialog. Must be launched from an activity. */
    fun activationIntent(explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)

    /**
     * Hand the grant back. Worth having as a button rather than only a system
     * settings trip: an active admin can't be uninstalled, and some devices bury
     * or omit the admin list entirely, so the app needs to be able to let go of
     * itself.
     */
    fun revoke() {
        if (active) dpm.removeActiveAdmin(component)
    }

    /** Put the display out. No-op unless [canSleep]. */
    fun sleep() {
        if (!canSleep) return
        dpm.lockNow()
    }
}
