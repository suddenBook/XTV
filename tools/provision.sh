#!/usr/bin/env bash
# Securely provision an XTV install with the operator's own X credentials.
#
# Usage:
#   XURL_APP=... XURL_USER=... ./tools/provision.sh [adb-serial]
# The script interactively prompts for both credential values and never echoes the bearer.
set -euo pipefail
# A caller may accidentally invoke this credential-handling script with `bash -x`. Disable tracing
# before any secret is read, assigned, selected, or passed to adb.
set +x

CLIENT_ID=""
BEARER=""
EXPECTED_SIGNER="${XTV_EXPECTED_SIGNER_SHA256:-9ebbd0a688de30aedfe6b98a32c16e3d3579733d3581bbbf4de240648233c10b}"
REQUESTED_SERIAL="${1:-}"
AUTH_FILE="${HOME}/.xurl/auth.yml"
unset XTV_CLIENT_ID XTV_BEARER XTV_EXPECTED_SIGNER_SHA256

fail() {
  echo "!! $*" >&2
  exit 1
}

[ -t 0 ] || fail "Run this script from an interactive trusted terminal."
read -r -p "OAuth 2.0 client id: " CLIENT_ID
read -r -s -p "App-only bearer token: " BEARER
printf '\n'
[ -n "$CLIENT_ID" ] || fail "Provide the OAuth 2.0 client id at the prompt."
[ -n "$BEARER" ] || fail "Provide the app-only bearer token at the hidden prompt."
[[ "$EXPECTED_SIGNER" =~ ^[[:xdigit:]]{64}$ ]] ||
  fail "The expected APK signer fingerprint must be exactly 64 hexadecimal characters."
[ -f "$AUTH_FILE" ] || fail "No xurl auth file. Run: xurl auth oauth2 --headless"

mapfile -t CONNECTED < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [ -n "$REQUESTED_SERIAL" ]; then
  SERIAL="$REQUESTED_SERIAL"
  printf '%s\n' "${CONNECTED[@]}" | grep -Fxq "$SERIAL" ||
    fail "adb device '$SERIAL' is not connected and authorised."
else
  [ "${#CONNECTED[@]}" -eq 1 ] ||
    fail "Pass an adb serial explicitly when zero or multiple devices are connected."
  SERIAL="${CONNECTED[0]}"
fi
ADB=(adb -s "$SERIAL")

# A serial without a network port is a direct USB transport. Network adb is accepted only when the
# host's paired-adb discovery service identifies the exact TLS connection. Device-reported
# properties are not transport proof and are deliberately ignored.
if [[ "$SERIAL" == *:* ]]; then
  REMOTE_PORT="${SERIAL##*:}"
  [ "$REMOTE_PORT" != "5555" ] ||
    fail "Refusing legacy plaintext adb on port 5555. Use USB or pair Wireless debugging (TLS)."

  MDNS="$(adb mdns services 2>/dev/null || true)"
  if ! awk -v serial="$SERIAL" \
    '$2 ~ /^_adb-tls-connect\._tcp\.?$/ && $3 == serial { found = 1 } END { exit !found }' \
    <<<"$MDNS"; then
    fail "Could not verify '$SERIAL' as an active adb TLS endpoint. Use USB or Android Wireless debugging."
  fi
fi

# Refuse to send credentials to an untrusted/repackaged install. The default fingerprint is the
# public XTV v0.1 update lineage; local debug builds require an explicit independently obtained
# XTV_EXPECTED_SIGNER_SHA256 override.
PACKAGE_DUMP="$("${ADB[@]}" shell dumpsys package com.xtv.app 2>/dev/null || true)"
grep -Fq "com.xtv.app.ProvisioningActivity" <<<"$PACKAGE_DUMP" ||
  fail "Installed XTV has no dedicated provisioning component. Install the current build first."
grep -Fq "android.permission.DUMP" <<<"$PACKAGE_DUMP" ||
  fail "Installed provisioning component is not reported with the required DUMP permission."
grep -Eq 'versionCode=3([[:space:]]|$)' <<<"$PACKAGE_DUMP" ||
  fail "Installed XTV is not version code 3."
grep -Fq 'versionName=1.1.0' <<<"$PACKAGE_DUMP" ||
  fail "Installed XTV is not version 1.1.0."

APKSIGNER="${XTV_APKSIGNER:-}"
if [ -z "$APKSIGNER" ]; then
  APKSIGNER="$(command -v apksigner || true)"
fi
if [ -z "$APKSIGNER" ]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$SDK_ROOT" ]; then
    APKSIGNER="$(
      find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner 2>/dev/null |
        sort -V |
        tail -n 1
    )"
  fi
fi
[ -x "$APKSIGNER" ] ||
  fail "Could not find apksigner. Set XTV_APKSIGNER to an Android build-tools apksigner."

AUDIT_DIR="$(mktemp -d)"
cleanup() {
  CLIENT_ID=""
  BEARER=""
  REFRESH=""
  rm -rf -- "$AUDIT_DIR"
}
trap cleanup EXIT
BASE_APK="$(
  "${ADB[@]}" shell pm path com.xtv.app 2>/dev/null |
    tr -d '\r' |
    sed -n 's/^package://p' |
    head -n 1
)"
[ -n "$BASE_APK" ] || fail "Could not resolve the installed XTV base APK."
"${ADB[@]}" pull "$BASE_APK" "$AUDIT_DIR/base.apk" >/dev/null ||
  fail "Could not inspect the installed XTV APK."
ACTUAL_SIGNER="$(
  "$APKSIGNER" verify --print-certs "$AUDIT_DIR/base.apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
)"
if [[ "${ACTUAL_SIGNER,,}" != "${EXPECTED_SIGNER,,}" ]]; then
  fail "Installed XTV signer does not match the independently expected certificate."
fi

# Select the refresh token by its full YAML path. If an app/user was not supplied, accepting exactly
# one unambiguous match is safe; multiple matches fail instead of silently choosing the first token.
REFRESH="$(
  XTV_AUTH_FILE="$AUTH_FILE" XTV_AUTH_APP="${XURL_APP:-}" XTV_AUTH_USER="${XURL_USER:-}" \
    python3 - <<'PY'
import ast
import os
import re
import sys

path = os.environ["XTV_AUTH_FILE"]
wanted_app = os.environ.get("XTV_AUTH_APP", "")
wanted_user = os.environ.get("XTV_AUTH_USER", "")
stack = []
candidates = []

with open(path, encoding="utf-8") as stream:
    for raw in stream:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        match = re.match(r"^( *)([^:#][^:]*):(?:[ \t]*(.*))?$", raw.rstrip("\n"))
        if not match:
            continue
        indent = len(match.group(1))
        key = match.group(2).strip().strip("\"'")
        value = match.group(3).strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path_keys = [entry[1] for entry in stack] + [key]
        if key == "refresh_token" and value:
            try:
                token = ast.literal_eval(value) if value[:1] in "\"'" else value
            except (SyntaxError, ValueError):
                token = value.strip("\"'")
            if len(path_keys) >= 6 and path_keys[0] == "apps" and "oauth2_tokens" in path_keys:
                app = path_keys[1]
                marker = path_keys.index("oauth2_tokens")
                user = path_keys[marker + 1] if marker + 1 < len(path_keys) - 1 else ""
                candidates.append((app, user, str(token)))
        if not value:
            stack.append((indent, key))

matches = [
    item for item in candidates
    if (not wanted_app or item[0] == wanted_app)
    and (not wanted_user or item[1] == wanted_user)
]
if len(matches) != 1:
    choices = ", ".join(sorted(f"{app}/{user}" for app, user, _ in matches or candidates))
    print(
        "Refresh token selection is ambiguous or missing. "
        f"Set XURL_APP and XURL_USER explicitly. Candidates: {choices or 'none'}",
        file=sys.stderr,
    )
    raise SystemExit(2)
print(matches[0][2])
PY
)" || exit $?
[ -n "$REFRESH" ] || fail "The selected xurl profile has no refresh token."

REQUEST_ID="$(tr -d '\n' </proc/sys/kernel/random/uuid)"
[ -n "$REQUEST_ID" ] || fail "Could not create a provisioning request id."

echo "Provisioning XTV on $SERIAL over a verified transport…"
"${ADB[@]}" shell am start -W -n com.xtv.app/.ProvisioningActivity \
  --es request_id "$REQUEST_ID" \
  --es client_id "$CLIENT_ID" \
  --es refresh_token "$REFRESH" \
  --es bearer "$BEARER" >/dev/null ||
  fail "The protected provisioning activity could not be started. Is this build installed?"

for _ in $(seq 1 30); do
  RESULT="$(
    "${ADB[@]}" logcat -d -s XTV-PROVISION:I 2>/dev/null |
      grep -F "request=$REQUEST_ID " |
      tail -n 1 || true
  )"
  if [[ "$RESULT" == *"status=COMMITTED"* ]]; then
    if [[ "$RESULT" == *"preserve=true"* ]]; then
      echo "Done. Credentials verified; matching account/project state was preserved."
    else
      echo "Done. Credentials verified; account content was cleared where identity changed."
    fi
    exit 0
  fi
  if [[ "$RESULT" == *"status=REJECTED"* ]]; then
    REASON="$(sed -n 's/.* reason=\([A-Z_]*\).*/\1/p' <<<"$RESULT")"
    fail "Provisioning was rejected (${REASON:-unknown reason}). No candidate became canonical."
  fi
  sleep 1
done

fail "Timed out waiting for request-scoped confirmation. Inspect: adb -s '$SERIAL' logcat -s XTV-PROVISION:I"
