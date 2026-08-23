#!/usr/bin/env bash
# Re-sign a Nix-built APK with the stable hearthd release key.
#
# The Nix build stays pure and secret-free: it emits a debug-signed APK whose
# key is regenerated every build. Android only lets an app be updated by an APK
# signed with the *same* key, so CI re-signs that APK here with a stable release
# key decoded from GitHub secrets. This is what makes the future in-app updater
# possible.
#
# Expects apksigner/zipalign on ANDROID_HOME (run under `nix develop .#ci`) and the
# signing material in the environment:
#   SIGNING_KEYSTORE_BASE64    base64 of the release keystore (.jks)
#   SIGNING_KEYSTORE_PASSWORD  keystore password
#   SIGNING_KEY_PASSWORD       key password
#   SIGNING_KEY_ALIAS          key alias (optional; defaults to hearthd-portal, the
#                              alias inside the existing keystore — not a brand name)
#
# Usage: ci/sign-apk.sh <in.apk> <out.apk>
set -euo pipefail

in_apk="${1:?usage: sign-apk.sh <in.apk> <out.apk>}"
out_apk="${2:?usage: sign-apk.sh <in.apk> <out.apk>}"

: "${SIGNING_KEYSTORE_BASE64:?SIGNING_KEYSTORE_BASE64 not set}"
: "${SIGNING_KEYSTORE_PASSWORD:?SIGNING_KEYSTORE_PASSWORD not set}"
: "${SIGNING_KEY_PASSWORD:?SIGNING_KEY_PASSWORD not set}"
key_alias="${SIGNING_KEY_ALIAS:-hearthd-portal}"

build_tools="${ANDROID_HOME:?ANDROID_HOME not set}/build-tools/36.0.0"
apksigner="$build_tools/apksigner"
zipalign="$build_tools/zipalign"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
keystore="$workdir/release.jks"
printf '%s' "$SIGNING_KEYSTORE_BASE64" | base64 -d > "$keystore"

# AGP already zipaligns the APK; confirm rather than re-align (apksigner
# preserves alignment when it inserts the signing block).
"$zipalign" -c -v 4 "$in_apk" >/dev/null

cp "$in_apk" "$out_apk"

# minSdk is 28, so the v1 (JAR) signature is unnecessary; sign with v2+v3 only.
# apksigner replaces any existing (debug) signature.
"$apksigner" sign \
  --ks "$keystore" \
  --ks-key-alias "$key_alias" \
  --ks-pass env:SIGNING_KEYSTORE_PASSWORD \
  --key-pass env:SIGNING_KEY_PASSWORD \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  "$out_apk"

# Fail loudly if the result isn't a valid, release-signed APK.
"$apksigner" verify --verbose --print-certs "$out_apk"
