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
# Usage:  XTV_CLIENT_ID=<id> XTV_BEARER=<app-only bearer> ./tools/provision.sh [adb-serial]
set -euo pipefail

CLIENT_ID="${XTV_CLIENT_ID:-}"
BEARER="${XTV_BEARER:-}"
SERIAL="${1:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

if [ -z "$CLIENT_ID" ]; then
  echo "Set XTV_CLIENT_ID (your X app's OAuth 2.0 Client ID)." >&2
  exit 1
fi

# Required, not optional. Without it the spend figure would be a local guess over a calendar month,
# while X bills over a period ending on cap_reset_day — two windows that do not line up. Paste it
# exactly as the console shows it: the '%2F' and '%3D' in it are literal characters, and
# URL-decoding them produces a token X rejects with a 401.
if [ -z "$BEARER" ]; then
  echo "Set XTV_BEARER (the app-only Bearer Token from your app's Keys and tokens page)." >&2
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
# Clear first. The checks below read the whole buffer, so a rejection logged by an earlier run would
# otherwise be reported as this run's result.
"${ADB[@]}" logcat -c 2>/dev/null || true
"${ADB[@]}" shell am start -n com.xtv.app/.MainActivity \
  --es client_id "$CLIENT_ID" \
  --es refresh_token "$REFRESH" \
  --es bearer "$BEARER" >/dev/null
sleep 4

LOG=$("${ADB[@]}" logcat -d 2>/dev/null || true)

if grep -q "injected token rejected" <<<"$LOG"; then
  echo "!! X rejected the refresh token — it was probably already spent."
  echo "   Run: xurl auth oauth2 --headless   then re-run this script."
  exit 1
fi

# Positive confirmation, rather than "no bad news". A missing or unstored credential produces no
# rejection at all, so the absence of an error used to print "Done." over a setup screen.
START=$(grep -o 'start: [A-Za-z]*' <<<"$LOG" | tail -n 1 | cut -d' ' -f2)
case "$START" in
  Home)
    echo "Done. The token is now stored on the device; you will not need this again unless you"
    echo "uninstall, clear data, or install a build signed with a different key."
    ;;
  NeedsSetup)
    echo "!! The app is still on the setup screen — a credential did not stick. It reports:" >&2
    grep -o 'start: NeedsSetup([^)]*)' <<<"$LOG" | tail -n 1 >&2
    exit 1
    ;;
  NeedsLogin)
    echo "!! Credentials stored, but there is no session: the refresh token was not accepted." >&2
    echo "   Run: xurl auth oauth2 --headless   then re-run this script." >&2
    exit 1
    ;;
  *)
    echo "!! Could not tell how the app started. Is it installed, and is this the right device?" >&2
    exit 1
    ;;
esac
