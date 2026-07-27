#!/usr/bin/env bash
# Verify the unsigned, minified release artifact before it is signed or uploaded.
set -euo pipefail

fail() {
  echo "!! $*" >&2
  exit 1
}

[ "$#" -eq 2 ] ||
  fail "Usage: $0 <unsigned-release.apk> <android-build-tools-directory>"

APK="$(realpath -- "$1")"
BUILD_TOOLS="$(realpath -- "$2")"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
cd "$REPO_ROOT"

[ -f "$APK" ] || fail "Release APK does not exist: $APK"
[ -x "$BUILD_TOOLS/aapt2" ] || fail "aapt2 is missing from: $BUILD_TOOLS"
[ -x "$BUILD_TOOLS/apksigner" ] || fail "apksigner is missing from: $BUILD_TOOLS"

AUDIT_DIR="$(mktemp -d)"
cleanup() {
  rm -f -- "$AUDIT_DIR/manifest.txt" "$AUDIT_DIR/dex-strings.txt"
  rmdir -- "$AUDIT_DIR" 2>/dev/null || true
}
trap cleanup EXIT
MANIFEST="$AUDIT_DIR/manifest.txt"
DEX_STRINGS="$AUDIT_DIR/dex-strings.txt"

mapfile -d '' -t BUILD_CONFIGS < <(
  find app/build/generated -type f -name BuildConfig.java -path '*/release/*' -print0
)
[ "${#BUILD_CONFIGS[@]}" -eq 1 ] ||
  fail "Expected exactly one generated release BuildConfig.java."
if grep -Eq 'X_CLIENT_ID|XTV_(CLIENT_ID|BEARER)|ANDROID_KEYSTORE_(BASE64|PASSWORD)' \
  "${BUILD_CONFIGS[0]}"; then
  fail "A credential or signing-material field exists in release BuildConfig."
fi

set +e
SIGNATURE_OUTPUT="$("$BUILD_TOOLS/apksigner" verify --verbose "$APK" 2>&1)"
SIGNATURE_STATUS=$?
set -e
if [ "$SIGNATURE_STATUS" -eq 0 ] || [[ "$SIGNATURE_OUTPUT" != *"DOES NOT VERIFY"* ]]; then
  fail "The candidate must be a structurally valid but unsigned APK."
fi

"$BUILD_TOOLS/aapt2" dump xmltree "$APK" --file AndroidManifest.xml > "$MANIFEST"
manifest_has() {
  grep -Fq "$1" "$MANIFEST" || fail "Release manifest is missing: $1"
}

manifest_has 'A: package="com.xtv.app"'
manifest_has 'A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=3'
manifest_has 'A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="1.1.0"'
manifest_has 'A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false'
manifest_has 'A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=false'
manifest_has 'A: http://schemas.android.com/apk/res/android:dataExtractionRules'
manifest_has 'A: http://schemas.android.com/apk/res/android:fullBackupContent'
if grep -Fq 'A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true' "$MANIFEST"; then
  fail "The release manifest is debuggable."
fi

component_block() {
  grep -F -A12 \
    "A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"$1\"" \
    "$MANIFEST" || true
}
MAIN_BLOCK="$(component_block com.xtv.app.MainActivity)"
PROVISIONING_BLOCK="$(component_block com.xtv.app.ProvisioningActivity)"
PROFILE_RECEIVER_BLOCK="$(component_block androidx.profileinstaller.ProfileInstallReceiver)"
EXPORTED_TRUE='A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
DUMP_PERMISSION='A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP"'

grep -Fq "$EXPORTED_TRUE" <<<"$MAIN_BLOCK" ||
  fail "MainActivity is not the expected exported launcher."
grep -Fq "$EXPORTED_TRUE" <<<"$PROVISIONING_BLOCK" ||
  fail "ProvisioningActivity is not exported."
grep -Fq "$DUMP_PERMISSION" <<<"$PROVISIONING_BLOCK" ||
  fail "ProvisioningActivity is not protected by android.permission.DUMP."
grep -Fq "$EXPORTED_TRUE" <<<"$PROFILE_RECEIVER_BLOCK" ||
  fail "The expected ProfileInstallReceiver contract changed."
grep -Fq "$DUMP_PERMISSION" <<<"$PROFILE_RECEIVER_BLOCK" ||
  fail "ProfileInstallReceiver is not protected by android.permission.DUMP."
if [ "$(grep -Fc "$EXPORTED_TRUE" "$MANIFEST")" -ne 3 ]; then
  fail "The release manifest contains an unexpected exported Android component."
fi

# Transitive UI libraries legitimately reference WebView-compatible Android APIs. Reject only
# app-owned browser/login implementations in the production source set.
if grep -R -I -nE \
  --include='*.kt' --include='*.java' --include='*.xml' \
  'android\.webkit\.(WebView|WebViewClient|WebChromeClient|CookieManager)|addJavascriptInterface|<([A-Za-z0-9_.]+\.)?WebView' \
  app/src/main; then
  fail "A WebView implementation exists in the production source set."
fi

unzip -p "$APK" 'classes*.dex' | strings > "$DEX_STRINGS"
if grep -Eq \
  'oauth2_tokens:|refresh_token:[[:space:]]+[^"]|XTV_(CLIENT_ID|BEARER)|ANDROID_KEYSTORE_(BASE64|PASSWORD)|FixtureSource|A{16,}[A-Za-z0-9%+/=]{12,}' \
  "$DEX_STRINGS"; then
  fail "Potential credential, signing material, or debug fixture code exists in the release APK."
fi
if unzip -Z1 "$APK" | grep -Eq '^res/raw/|fixture'; then
  fail "A debug fixture asset exists in the release APK."
fi

printf 'Verified unsigned release APK: %s\n' "$(sha256sum "$APK" | cut -d' ' -f1)"
