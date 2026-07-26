#!/usr/bin/env bash
# Provision an XTV install with your own X credentials.
#
# The published APK deliberately contains none: X bills API usage to the owner of the developer app,
# not to whoever signs in, so a shared build would spend its author's credits for every user.
#
# Reads the refresh token straight out of ~/.xurl/auth.yml so it never has to be pasted around.
# Re-run `xurl auth oauth2 --headless` first if the app has already consumed the current one —
# refresh tokens rotate, and each is good for exactly one use.
#
# Usage:  ./tools/provision.sh [adb-serial]
set -euo pipefail

CLIENT_ID="${XTV_CLIENT_ID:-}"
BEARER="${XTV_BEARER:-}"
SERIAL="${1:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

if [ -z "$CLIENT_ID" ]; then
  echo "Set XTV_CLIENT_ID (your X app's OAuth 2.0 Client ID)." >&2
  exit 1
fi

AUTH="$HOME/.xurl/auth.yml"
[ -f "$AUTH" ] || { echo "No $AUTH — run: xurl auth oauth2 --headless" >&2; exit 1; }

REFRESH=$(python3 - "$AUTH" <<'PY'
import re, sys
m = re.search(r'refresh_token:\s*(\S+)', open(sys.argv[1]).read())
print(m.group(1).strip('"') if m else '')
PY
)
[ -n "$REFRESH" ] || { echo "No refresh_token in $AUTH — run: xurl auth oauth2 --headless" >&2; exit 1; }

echo "Provisioning ${SERIAL:-default device}…"
args=(--es client_id "$CLIENT_ID" --es refresh_token "$REFRESH")
# Optional: an app-only Bearer Token makes the spend figure X's own count rather than a local
# estimate. Paste it exactly as the console shows it — the '%2F' and '%3D' in it are literal
# characters, and URL-decoding them produces a token X rejects.
[ -n "$BEARER" ] && args+=(--es bearer "$BEARER")

"${ADB[@]}" shell am start -n com.xtv.app/.MainActivity "${args[@]}" >/dev/null
sleep 4

if "${ADB[@]}" logcat -d 2>/dev/null | grep -q "injected token rejected"; then
  echo "!! X rejected the refresh token — it was probably already spent."
  echo "   Run: xurl auth oauth2 --headless   then re-run this script."
  exit 1
fi
echo "Done. The token is now stored on the device; you will not need this again unless you"
echo "uninstall, clear data, or install a build signed with a different key."
