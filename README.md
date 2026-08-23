# hearthd-kiosk

> [!WARNING]
> **This is for tinkerers, not production.** hearthd-kiosk is an experimental
> Android app you sideload onto a repurposed smart display. It's very much in
> flux, and not well reviewed for security.
>
> Once this is production ready it'll be merged into the hearthd core repo. For
> now, it serves as a tech demo, and something I use at home.

## Supported devices

The app is a plain Android app and will probably run on more than this, but
these are the devices it's actually developed and tested against:

| Device | Android | ABI | Notes |
| --- | --- | --- | --- |
| Portal (2nd generation) | 9 (API 28) | `arm64-v8a` | Needs the package verifier disabled for in-app updates, and can't be made device owner. See [Device notes](#device-notes-portal-2nd-generation). |

Other Portal variants (Mini, +, Go, TV) are untested. The build targets
`arm64-v8a` only and a minimum of API 28, so anything older or on a different
ABI won't install.

## Install

Grab the latest `main` build from R2 (see [Release channels](#release-channels))
and sideload it with the device connected over USB in ADB mode:

    url=$(curl -fsSL https://assets.hearthd.dev/android/kiosk/main.json | jq -r .apkUrl)
    curl -fsSL "$url" -o hearthd-kiosk.apk
    adb install -r hearthd-kiosk.apk
    adb shell am start -n dev.hearthd.android.kiosk/.MainActivity

Or build it yourself (debug-signed): `nix build .#hearthd-kiosk`.

> [!NOTE]
> The published APK is release-signed with a stable key (the `publish` CI job
> runs `ci/sign-apk.sh`) so the app can update in place later. Locally built
> APKs are debug-signed with a throwaway key. Because Android refuses to update
> an app across signing keys, switching a device between a locally built (debug)
> and a published (release) APK requires uninstalling first:
>
>     adb uninstall dev.hearthd.android.kiosk

### Run as the kiosk (home app)

To make the device boot straight into hearthd and stay there, set it as the
home (launcher) app. Devices like this rarely expose a home-app picker in their
UI, but they do honour the app's `HOME` intent-filter, so you can point the
system at it over ADB — no device owner needed:

    adb shell cmd package set-home-activity dev.hearthd.android.kiosk/.MainActivity

After this the Home button lands on hearthd, and the device launches straight
into it on boot (confirmed on a Portal — no lock screen in the way, at least on
a device with no screen lock set).

## Automatic updates

The app can update itself in place from its release channel (opt in under
**Settings → Updates**). It polls the channel manifest, verifies the APK's
sha256, and installs it through `PackageInstaller`. Without device owner the
system raises a "confirm install" prompt, which someone has to accept on the
device.

Some devices need extra setup before this works at all — see below.

## Device notes: Portal (2nd generation)

Portal firmware ships a system app verifier (`com.facebook.appverifier`) that
silently rejects any install whose signing certificate isn't on Meta's internal
allowlist. Our release key isn't, so the system "confirm install" dialog appears
but pressing **Install** does nothing — the verifier vetoes the
`PackageInstaller` session after you confirm.

To allow in-app updates, disable package verification on the device over ADB:

    adb shell settings put global package_verifier_enable 0

This persists across reboots. It only affects installs that go through the
package verifier — ADB sideloads (`adb install`) are already exempt
(`verifier_verify_adb_installs` defaults to `0`), which is why the manual
install above works regardless. To restore the verifier:

    adb shell settings put global package_verifier_enable 1

> [!NOTE]
> This is a per-device change and can't be shipped in the app: Portal blocks
> the device-owner path that would otherwise grant silent installs
> (`dpm set-device-owner` is refused once the device has accounts, which every
> provisioned Portal does). Disabling the verifier is currently the only way to
> let a non-Meta-signed build update itself.

To hand control back to the stock Portal launcher:

    adb shell cmd package set-home-activity com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeActivity

## Migrating from hearthd-portal

This project was called hearthd-portal, and its app id was
`dev.hearthd.android.portal`. The rename changed the app id to
`dev.hearthd.android.kiosk`, which Android treats as an entirely different app —
there is no in-place upgrade path. The old app stops seeing new builds (its
channel manifests are frozen at the last hearthd-portal release) and otherwise
sits inert. Migrate a device once:

    adb install -r hearthd-kiosk.apk
    adb shell cmd package set-home-activity dev.hearthd.android.kiosk/.MainActivity
    adb uninstall dev.hearthd.android.portal

Settings don't carry over — they live in the old app's data directory — so
re-enter them under **Settings**, and re-grant microphone access if you use
wake-word detection.

## Release channels

Pushes to `main` and `canary` publish the signed APK and a per-stream manifest to
R2, served at `assets.hearthd.dev`:

    https://assets.hearthd.dev/android/kiosk/main.json
    https://assets.hearthd.dev/android/kiosk/canary.json

Each manifest points at a content-addressed APK
(`android/kiosk/<sha256>.apk`) and carries its `versionCode`, `versionName`,
`sha256`, and originating `gitSha`. The `versionCode` is the tip commit's
committer time, and the running build is shown on the app's home screen. Canary
only publishes when it contains everything on `main`.

The pre-rename `android/portal/*.json` manifests still exist but are frozen, so
any device still running hearthd-portal reports itself up to date rather than
downloading an APK it can't install over itself.
