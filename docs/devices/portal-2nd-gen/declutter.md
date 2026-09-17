# Portal (2nd generation) declutter

Device-specific notes for stripping a Meta Portal (2nd gen) down to a hearthd
kiosk over ADB. This is a record of what was actually done on one unit, not a
polished supported procedure. Read the warnings before repeating it.

## ADB is session-only — keep Meta's settings app

Portal disables ADB on every boot, and it can't be made persistent. Setting
`persist.sys.usb.config=adb`, `adb_enabled=1` and `development_settings_enabled=1`
before a reboot does **not** hold — the device comes back up in USB-accessory
mode with no ADB interface. Something in the retained Aloha framework re-applies
the USB config at boot (confirmed by reboot test on this unit).

The only on-device way to turn ADB back on is **Settings → Debug → ADB Enabled**
in Meta's settings app. So **do not remove `com.facebook.alohaapps.settings`** —
it is kept on purpose. After any reboot, re-enable ADB there and re-accept the
"Allow USB debugging?" prompt over USB-C.

Removing the settings app would swap Meta's cut-down Settings for the full AOSP
one, but it also removes the only ADB re-enable path: a later reboot then strands
the device with no shell and forces a factory reset. Not worth it — keep it.

The hardware factory reset is unaffected by any of this — see the end.

## Starting state (what the device must be at before you begin)

- **Logged in** and through the full out-of-box setup (a Facebook or WhatsApp
  account; there is no account-free path through the wizard on this generation).
- **Up to date.** Let the setup wizard's software-update step finish. ADB is not
  usefully available until the device is on current firmware.
- **On WiFi.**
- **ADB enabled and authorised**: Settings → Debug → ADB Enabled, then accept the
  "Allow USB debugging?" prompt for this computer over USB-C.

### Version this was done on

| Field | Value |
| --- | --- |
| Model | Portal (`ro.product.model=Portal`, `ro.product.device=omni`) |
| Android | 10 (API 29) |
| Build | `QKQ1.210213.001.3051355900018050` |
| Build date | 2025-10-14 |
| Security patch | 2020-08-05 |
| hearthd-kiosk installed | `0.1.0+b4a4be5` (signed `main` from assets.hearthd.dev) |

### Host notes

- All removals use `pm uninstall --user 0 <pkg>`. This removes the package for
  user 0 but keeps the system APK, so **a factory reset restores everything**.
  To undo a single one before a reset: `adb shell cmd package install-existing <pkg>`.
- On Linux the Portal shows up as `2ec6:1903` with an "ADB Interface" once ADB is
  on. If `adb devices` shows `no permissions`, the raw USB node is root-owned;
  `chmod a+rw /dev/bus/usb/<bus>/<dev>` (path from the device's `busnum`/`devnum`)
  fixes it without needing a udev rule.
- After each batch below, sanity-check the device stayed alive:
  `adb get-state`, `adb shell pidof com.android.systemui`, and that a HOME
  activity still resolves.

## Install hearthd-kiosk and make it the launcher

Needed before removing Meta's launcher, so the device still has a home screen.
Full instructions are in the repo README; in short:

```
url=$(curl -fsSL https://assets.hearthd.dev/android/kiosk/main.json | jq -r .apkUrl)
curl -fsSL "$url" -o hearthd-kiosk.apk        # verify sha256 against the manifest
adb install -r hearthd-kiosk.apk
adb shell cmd package set-home-activity dev.hearthd.android.kiosk/.MainActivity
```

## What was removed

Everything in this section was removed with:

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

### SDK service wrappers and resource overlays

Meta wrappers around Bluetooth/location/etc. and two runtime resource overlays.
(System Bluetooth/location remain — these are the Aloha shims, not the AOSP
services.)

- `com.facebook.alohasdk.bluetooth`
- `com.facebook.alohasdk.location`
- `com.facebook.alohasdk.pushnotification`
- `com.facebook.alohasdk.settings`
- `com.facebook.alohasdk.virtualcameramanager`
- `com.facebook.aloha.rro.niu.android`
- `com.facebook.aloha.rro.niu.settings`

### Placeholder packages

Empty "dummy" system packages.

- `com.facebook.portal.dummybp`
- `com.facebook.portal.dummymk`

### Package verifier

Meta's install verifier that rejects non-Meta-signed installs. Removing the
package achieves the same end as the README's
`settings put global package_verifier_enable 0` (which you can also set).
ADB sideloads are exempt from it regardless.

- `com.facebook.appverifier`

### Launcher, setup and personalisation

Remove these **only after** hearthd-kiosk is installed and set as home. Do **not**
remove `com.facebook.alohaapps.settings` — it's the ADB re-enable path (see the
top); it stays.

- `com.facebook.alohaapps.launcher` (Meta home screen)
- `com.facebook.alohaapps.devicesetup` (out-of-box setup; already spent)
- `com.facebook.alohaapps.personaluser`

### Login / account / device-policy stack — removes the Facebook login

The device-admin app, the device-policy controller (no active admin was
enrolled, so this was safe), the account services and secure-state shim.

- `com.facebook.alohaservices.deviceadmin`
- `com.facebook.aloha.dpc`
- `com.facebook.alohaservices.alohausers`
- `com.facebook.aloha.deviceidentity`
- `com.facebook.alohasdk.platformsecurestate`

> This clears the Facebook login. The four Meta accounts
> (`aloha.hw` / `aloha.pl` / `aloha.sso` / `aloha.privowner`) drop away once
> AccountManager reconciles the removal — give it a moment, don't check
> instantly. Verify with `adb shell dumpsys account | grep -c 'Account {'`
> (expect `0`). No reboot is needed for this.

### WebView provider

`com.facebook.portal.webview` was the device's only WebView provider (fallback
disabled). It was removed after confirming hearthd-kiosk does not use WebView:
the app source has zero `android.webkit` references, and the only refs in the
APK are dead code paths inside bundled libraries. After removal the current
WebView package is `null`, and the kiosk was unaffected. **If you run any other
app that uses WebView, keep this.**

- `com.facebook.portal.webview`

## Left installed (kept on purpose)

### Meta's settings app — the ADB re-enable path

`com.facebook.alohaapps.settings` is kept so ADB can be turned back on after a
reboot (Settings → Debug → ADB Enabled). See the top of this doc.

### Deep Aloha OS substrate — not safely removable

The core Aloha framework packages. Not removed: they are the OS substrate
(hardware integration, core services, native libs), so removal risks a boot loop,
and the only way to validate removal is a reboot. A bad reboot forces a factory
reset — the one outcome this procedure is built to avoid — so these are left in
place rather than probed.

- `com.facebook.aloha.system.device`
- `com.facebook.aloha.system.services`
- `com.facebook.aloha.system.nativelibs`
- `com.facebook.aloha.state`
- `com.facebook.aloha.platformmobileconfig`
- `com.facebook.portal.sdk`

No AOSP or Qualcomm system packages were touched — those are the OS, not bloat.

### Optional next step: device owner

With the accounts gone, `dpm set-device-owner` is no longer blocked by the
"device has accounts" check, so hearthd-kiosk could be made device owner (silent
installs, stronger lockdown). Not done here: device owner can only be removed by
a factory reset, so it's a deliberate one-way choice, left to the operator.

## Factory reset is intact

Nothing here touches the recovery path. Use the **buttons** (the on-screen reset
under Meta's Settings → Debug/System may be blocked by a leftover policy
restriction, and isn't reliable):

- Unplug power **and the USB data cable**; hold **Volume Up + Volume Down**; plug
  power back in while holding; keep holding through the on-screen 10-second
  countdown. Keep USB unplugged until it's booted — a USB-attached boot during
  the reset can drop the device into Qualcomm EDL (`05c6:9008`) instead.

A reset restores every package removed above and brings back the Facebook login.
