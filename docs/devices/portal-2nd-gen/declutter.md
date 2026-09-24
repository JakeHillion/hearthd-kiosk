# Portal (2nd generation) declutter

Device-specific notes for stripping a Meta Portal (2nd gen) down to a hearthd
kiosk over ADB. Every Meta package on the device is in one of three states:

- **[Safe to remove](#safe-to-remove)**: removed together and confirmed to
  survive a reboot with ADB re-enable still working.
- **[Known to break ADB](#known-to-break-adb--keep)**: removing it loses the
  only route back to a shell after a reboot. Keep.
- **[Unknown](#unknown--keep-testing-risks-a-factory-reset)**: untested. The
  only test is to remove it and reboot, and if it's load-bearing that reboot
  forces a factory reset. Keep.

This is not a polished supported procedure. Read the warnings before
repeating it.

## ADB is session-only

Portal disables ADB on every boot, and it can't be made persistent. Setting
`persist.sys.usb.config=adb`, `adb_enabled=1` and `development_settings_enabled=1`
before a reboot does **not** hold — the device comes back up in USB-accessory
mode with no ADB interface. Something in the retained Aloha framework re-applies
the USB config at boot.

The only on-device way to turn ADB back on is **Settings → Debug → ADB Enabled**
in Meta's settings app. After any reboot, re-enable ADB there and re-accept the
"Allow USB debugging?" prompt over USB-C.

That makes every removal a question of whether the Debug entry still exists
after a boot. A removal that loses it strands the device with no shell, and the
only way back is a factory reset. It doesn't show until the reboot: before
then the device looks fine, and `cmd package install-existing` still works.

The hardware factory reset is unaffected by any of this — see the end.

## Starting state (what the device must be at before you begin)

- **Logged in** and through the full out-of-box setup (a Facebook or WhatsApp
  account; there is no account-free path through the wizard on this generation).
- **Up to date.** Let the setup wizard's software-update step finish. ADB is not
  usefully available until the device is on current firmware.
- **On WiFi.**
- **ADB enabled and authorised**: Settings → Debug → ADB Enabled, then accept the
  "Allow USB debugging?" prompt for this computer over USB-C.

### Firmware this applies to

Verified on the Portal (`ro.product.model=Portal`) and Portal Mini
(`PortalMini`), both `ro.product.device=omni`, which share the firmware below
and the same set of 51 `com.facebook.*` packages. On other firmware, compare
the package list before relying on this.

| Field | Value |
| --- | --- |
| Android | 10 (API 29) |
| Build | `QKQ1.210213.001.3051355900018050` |
| Build date | 2025-10-14 |
| Security patch | 2020-08-05 |

### Host notes

- All removals use `pm uninstall --user 0 <pkg>`. This removes the package for
  user 0 but keeps the system APK, so **a factory reset restores everything**.
  To undo a single one before a reboot: `adb shell cmd package install-existing <pkg>`.
- On Linux the Portal shows up as `2ec6:1903` with an "ADB Interface" once ADB is
  on. If `adb devices` shows `no permissions`, the raw USB node is root-owned;
  `chmod a+rw /dev/bus/usb/<bus>/<dev>` (path from the device's `busnum`/`devnum`)
  fixes it without needing a udev rule.
- On macOS, a newly connected Portal waits behind the "Allow accessory to
  connect?" system prompt and doesn't enumerate at all until it's accepted — it
  is absent from `ioreg -p IOUSB`, not merely unauthorised in `adb devices`.
- If it enumerates but exposes only an "Android Accessory Interface" (class
  `ff/ff/00`) and `adb devices` is empty, ADB Enabled hasn't taken effect for
  this boot: toggle it off and on again in Settings → Debug.
- After each batch below, sanity-check the device stayed alive:
  `adb get-state`, `adb shell pidof com.android.systemui`, that a HOME
  activity still resolves, and that the install-source appop (below) still
  reads `allow`.

## Install hearthd-kiosk and make it the launcher

Needed before removing Meta's launcher, so the device still has a home screen.
Full instructions are in the repo README; in short:

```
url=$(curl -fsSL https://assets.hearthd.dev/android/kiosk/main.json | jq -r .apkUrl)
curl -fsSL "$url" -o hearthd-kiosk.apk        # verify sha256 against the manifest
adb install -r hearthd-kiosk.apk
adb shell cmd package set-home-activity dev.hearthd.android.kiosk/.MainActivity
```

### Allow it to install its own updates

In-app updates need two things on this device. Do both straight after
installing, before removing anything:

```
adb shell appops set dev.hearthd.android.kiosk REQUEST_INSTALL_PACKAGES allow
adb shell settings put global package_verifier_enable 0
```

The appop approves hearthd-kiosk as an install source — the per-app "Install
unknown apps" permission. Without it, the first update raises a prompt that
sends you to AOSP Settings (`com.android.settings`,
`Settings$ManageAppExternalSourcesActivity`) to approve the app, and that route
can't be relied on once the packages below are gone (`rro.niu.settings`, for
one, themes that screen). Set over ADB up front, the approval needs no screen
at all, and it persists across reboots. Check it with
`adb shell appops get dev.hearthd.android.kiosk REQUEST_INSTALL_PACKAGES`.

The verifier setting is the README's fix for Meta's verifier vetoing
non-Meta-signed installs; `com.facebook.appverifier` is also removed below.

On API 29 each update still raises the system "confirm install" prompt, which
someone has to accept on the device.

## Safe to remove

All 38 packages in this section, removed together, survive a reboot: ADB
Enabled is still offered in Settings → Debug and works, hearthd-kiosk comes up
as home, and the install-source appop and verifier setting hold. Remove each
with:

```
adb shell pm uninstall --user 0 <package>
```

### User-facing Meta apps

The calling/social/photo apps — the actual Portal product surface.

- `com.facebook.aloha.app.messenger`
- `com.facebook.aloha.app.whatsapp`
- `com.facebook.aloha.app.portalfeed`
- `com.facebook.aloha.app.storytime`
- `com.facebook.aloha.app.cameraeditor`
- `com.facebook.alohaapps.contacts`

### Assistant, voice and TTS

- `com.facebook.portal.aiservice`
- `com.facebook.aloha.app.ttsservice`
- `com.facebook.aloha.fbttsservice`

### Telemetry, diagnostics and "health"

- `com.facebook.aloha.analytics`
- `com.facebook.alohaapps.bugreporter`
- `com.facebook.aloha.cnshealthmonitor`
- `com.facebook.aloha.wifidiagnostic`
- `com.facebook.aloha.websafety`
- `com.facebook.aloha.disv2`

### Presence, abilities and social services

- `com.facebook.alohaservices.presence`
- `com.facebook.alohaservices.abilitymanager`
- `com.facebook.alohaservices.abilities.pages`
- `com.facebook.alohaservices.player2`

### App store, installer and OTA updater

Removing these stops Meta's own app installs and firmware OTA. Fine for a kiosk
that self-updates through hearthd instead. Note it removes the *user-facing*
update path (`otaui` is the update UI); the low-level updater may live in the
retained core, so background checks aren't guaranteed off. To deliberately pick
up a future Portal firmware update, factory reset — that restores these. The
kiosk app's own updates are unaffected either way.

- `com.facebook.alohainstaller`
- `com.facebook.alohaappmanager`
- `com.facebook.aloha.otaui`
- `com.facebook.aloha.alohaotasetup`

### Launcher extras

The ambient photo frame and the quick-settings control center. Removing these
degrades Meta's launcher, which is fine because it gets removed below.

- `com.facebook.alohaapps.superframe`
- `com.facebook.alohaapps.controlcenter`

### SDK service wrappers and the Settings overlay

Meta wrappers around Bluetooth/location/etc. (System Bluetooth/location
remain — these are the Aloha shims, not the AOSP services.) `alohasdk.settings`
and `rro.niu.settings` sit close to the settings plumbing, but removing them
does not lose the Debug entry.

- `com.facebook.alohasdk.bluetooth`
- `com.facebook.alohasdk.location`
- `com.facebook.alohasdk.pushnotification`
- `com.facebook.alohasdk.settings` (`PlatformSettingsService`, a priv-app)
- `com.facebook.alohasdk.virtualcameramanager`
- `com.facebook.aloha.rro.niu.settings` (overlay targeting `com.android.settings`)

### Placeholder packages

Empty "dummy" system packages.

- `com.facebook.portal.dummybp`
- `com.facebook.portal.dummymk`

### Package verifier

Meta's install verifier that rejects non-Meta-signed installs. Removing the
package achieves the same end as `settings put global package_verifier_enable 0`
(set above). ADB sideloads are exempt from it regardless.

- `com.facebook.appverifier`

### Launcher, setup and personalisation

Remove these **only after** hearthd-kiosk is installed and set as home, or the
device is left with no home screen.

- `com.facebook.alohaapps.launcher` (Meta home screen)
- `com.facebook.alohaapps.devicesetup` (out-of-box setup; already spent)
- `com.facebook.alohaapps.personaluser`

### WebView provider

`com.facebook.portal.webview` is the device's only WebView provider (fallback
disabled). hearthd-kiosk does not use WebView: the app source has zero
`android.webkit` references, and the only refs in the APK are dead code paths
inside bundled libraries. After removal the current WebView package is `null`,
and the kiosk is unaffected. **If you run any other app that uses WebView, keep
this.**

- `com.facebook.portal.webview`

## Known to break ADB — keep

### Meta's settings app

`com.facebook.alohaapps.settings` holds Settings → Debug → ADB Enabled, the only
ADB re-enable path. Removing it swaps Meta's cut-down Settings for the full AOSP
one, but a later reboot then strands the device with no shell.

- `com.facebook.alohaapps.settings`

### Login / account / device-policy stack

The device-admin app, the device-policy controller, the account services and
the secure-state shim. With the safe set above removed, removing these five as
well loses the Debug entry after a reboot. Which of the five carries it is not
known; they have only been removed as a batch, so treat all five as
load-bearing.

- `com.facebook.alohaservices.deviceadmin`
- `com.facebook.aloha.dpc`
- `com.facebook.alohaservices.alohausers`
- `com.facebook.aloha.deviceidentity`
- `com.facebook.alohasdk.platformsecurestate`

Keeping them keeps the Facebook login and the four Meta accounts
(`aloha.hw` / `aloha.pl` / `aloha.sso` / `aloha.privowner`). That rules out
making hearthd-kiosk device owner (silent installs, stronger lockdown):
`dpm set-device-owner` refuses a device with accounts.

## Unknown — keep, testing risks a factory reset

Untested. Each could only be tested by removing it and rebooting, and if it
turns out to be load-bearing, that reboot forces a factory reset.

### Framework overlay

- `com.facebook.aloha.rro.niu.android` (theme overlay targeting `android`)

### Deep Aloha OS substrate

The core Aloha framework packages: hardware integration, core services, native
libs. Beyond the ADB risk, removing these risks a boot loop.

- `com.facebook.aloha.system.device`
- `com.facebook.aloha.system.services`
- `com.facebook.aloha.system.nativelibs`
- `com.facebook.aloha.state`
- `com.facebook.aloha.platformmobileconfig`
- `com.facebook.portal.sdk`

No AOSP or Qualcomm system packages are touched — those are the OS, not bloat.

## Factory reset is intact

Nothing here touches the recovery path. Use the **buttons** (the on-screen reset
under Meta's Settings → Debug/System may be blocked by a leftover policy
restriction, and isn't reliable):

- Unplug power **and the USB data cable**; hold **Volume Up + Volume Down**; plug
  power back in while holding; keep holding through the on-screen 10-second
  countdown. Keep USB unplugged until it's booted — a USB-attached boot during
  the reset can drop the device into Qualcomm EDL (`05c6:9008`) instead.

A reset restores every package removed above.
