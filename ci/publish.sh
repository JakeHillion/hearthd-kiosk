#!/usr/bin/env bash
# Publish a signed APK + its stream manifest to the R2 bucket behind
# assets.hearthd.dev.
#
# The APK is content-addressed by its own sha256, so identical builds across
# streams dedupe to one immutable object and never collide. The stream is just
# the manifest filename (main.json / canary.json), a mutable pointer at the
# current build for that stream.
#
#   android/kiosk/<sha256>.apk   immutable, long cache
#   android/kiosk/<stream>.json  mutable pointer, no-cache
#
# Needs `aws` on PATH (provided by the `.#ci` dev shell CI enters). Expects:
#   R2_ENDPOINT, R2_BUCKET, PUBLIC_BASE, VERSION_CODE, VERSION_NAME, GITHUB_SHA
#   AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION=auto
#
# Usage: ci/publish.sh <apk> <stream>
set -euo pipefail

apk="${1:?usage: publish.sh <apk> <stream>}"
stream="${2:?usage: publish.sh <apk> <stream>}"

: "${R2_ENDPOINT:?}" "${R2_BUCKET:?}" "${PUBLIC_BASE:?}"
: "${VERSION_CODE:?}" "${VERSION_NAME:?}" "${GITHUB_SHA:?}"

sha256="$(sha256sum "$apk" | cut -d' ' -f1)"
apk_key="android/kiosk/${sha256}.apk"
manifest_key="android/kiosk/${stream}.json"
apk_url="${PUBLIC_BASE}/${apk_key}"
published_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

r2() { aws --endpoint-url "$R2_ENDPOINT" "$@"; }

# APK first, so the pointer never references a missing object. Content-addressed,
# so re-uploading the same bytes is a harmless idempotent overwrite.
r2 s3 cp "$apk" "s3://${R2_BUCKET}/${apk_key}" \
  --content-type application/vnd.android.package-archive \
  --cache-control "public, max-age=31536000, immutable"

manifest="$(mktemp)"
trap 'rm -f "$manifest"' EXIT
printf '{\n  "versionCode": %s,\n  "versionName": "%s",\n  "apkUrl": "%s",\n  "sha256": "%s",\n  "gitSha": "%s",\n  "publishedAt": "%s"\n}\n' \
  "$VERSION_CODE" "$VERSION_NAME" "$apk_url" "$sha256" "$GITHUB_SHA" "$published_at" \
  > "$manifest"

# Pointer second, never cached, so devices see the new build promptly.
r2 s3 cp "$manifest" "s3://${R2_BUCKET}/${manifest_key}" \
  --content-type application/json \
  --cache-control "no-cache"

echo "Published '${stream}': versionCode=${VERSION_CODE} versionName=${VERSION_NAME}"
echo "  APK:      ${apk_url}"
echo "  Manifest: ${PUBLIC_BASE}/${manifest_key}"
