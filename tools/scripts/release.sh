#!/usr/bin/env bash
# Cut a signed Grandpa Pager release from the machine that holds the keystore.
#
#   tools/scripts/release.sh docs/release-notes.md
#
# Why local: Android identifies an installed app by its signing certificate, so
# the APK must be signed with android/pager-release.jks (never committed). CI can
# only do it when the same keystore is provided as repository secrets — see
# docs/runbook.md §5d.
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
NOTES=${1:-}

[ -f android/keystore.properties ] || {
  echo "error: android/keystore.properties is missing — the release build refuses to run unsigned."
  exit 1
}
[ -f android/pager-release.jks ] || {
  echo "error: android/pager-release.jks is missing (the key that installed apps trust)."
  exit 1
}
command -v gh >/dev/null || { echo "error: gh is not installed."; exit 1; }

VER=$(grep -oE 'versionName = "[^"]+"' android/app/build.gradle.kts | head -1 | cut -d'"' -f2)
CODE=$(grep -oE 'versionCode = [0-9]+' android/app/build.gradle.kts | head -1 | awk '{print $3}')
[ -n "$VER" ] || { echo "error: could not read versionName."; exit 1; }

echo "==> building $VER (versionCode $CODE)"
(cd android && ./gradlew testDebugUnitTest assembleRelease)

APK=android/app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "error: $APK was not produced."; exit 1; }

APKSIGNER=$(command -v apksigner || true)
if [ -z "$APKSIGNER" ]; then
  APKSIGNER="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/34.0.0/apksigner"
fi
[ -x "$APKSIGNER" ] || { echo "error: apksigner not found (install build-tools 34.0.0)."; exit 1; }

EXPECTED=1099d30fb2319c594f2a49ba53d44f1af4da128f269eac9a8e3efd5561329674
CERT=$("$APKSIGNER" verify --print-certs "$APK" | awk '/SHA-256 digest/{print $NF; exit}')
if [ "$CERT" != "$EXPECTED" ]; then
  echo "error: signing certificate mismatch."
  echo "  expected $EXPECTED"
  echo "  got      $CERT"
  echo "A differently signed APK would be rejected as an update — refusing to publish."
  exit 1
fi

OUT="/tmp/grandpa-pager-${VER}.apk"
cp "$APK" "$OUT"
SHA=$(sha256sum "$OUT" | cut -d' ' -f1)
echo "==> apk: $OUT"
echo "    versionCode $CODE, sha256 $SHA"

if [ -z "$NOTES" ]; then
  echo "==> no notes file given; artifact ready, not publishing."
  echo "    rerun with: tools/scripts/release.sh <release-notes.md>"
  exit 0
fi
[ -f "$NOTES" ] || { echo "error: notes file '$NOTES' not found."; exit 1; }

echo "==> publishing v$VER"
gh release create "v$VER" "$OUT" \
  --title "v$VER" \
  --notes-file "$NOTES" \
  --latest
