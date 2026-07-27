# XTV

XTV is an Android TV app that buys a finite slice of an X Following timeline and plays only the
videos found in that slice. It is deliberately not a general Twitter client: there is no text feed,
infinite scroll, posting, or background timeline polling.

> **Release status — v1.3.0 candidate:** the architecture and safeguards described here are the
> release contract. The X Developer Console is the billing authority throughout.

English, 简体中文, and 繁體中文 follow the TV's system language.

## Safety contract

Every timeline request costs the owner of the X developer app real money, so **configure a hard
spending limit in the X Developer Console before provisioning the TV.** That limit is the only
thing that can actually stop a charge, and XTV does not attempt to be a second one.

Earlier versions kept a fixed $20 local guard per UTC month, which trimmed the offers it would make
and demanded a confirmation past the line. It is gone. It could only ever disagree with the real
limit — the figure it guarded was an estimate of one app's share of a project-wide bill — and the
practical effect was an app rationing money its owner had already decided to spend.

What remains is that nothing is bought without being priced first. Each offer is quoted as a
**range**: the low end is the expected charge at the measured media density, the high end is a
conservative upper bound, and the actual bill does not exceed it. One OK press accepts one immutable
offer; it cannot dispatch the same purchase twice.

Project usage shown in Settings is X's resource count for X's own reporting period, alongside the
day of the month it resets. It is not a dollar balance and is never represented as one. Check
`console.x.com` for actual charges, credits, and the hard limit.

XTV makes **no timeline request on cold launch**, and fetching a batch always requires accepting a
priced offer. It does make exactly one metering call at launch: `GET /2/usage/tweets`, which returns
the project's Post-read count and reset day. That endpoint fetches nothing to watch and cannot start
a paid timeline read; without it, Settings opens on a stale figure and leaves keeping it honest to
whoever remembers to press Refresh.

Settings shows that count alongside what it costs, at `$0.005` per Post read. It covers the whole
developer project — including any other tool using the same credentials — and X's period resets on
its own `cap_reset_day`, not on the 1st. It is X's own meter, which is why it is the figure XTV
shows rather than a private tally of its own.

## Requirements

- Android TV / Google TV running Android 11 (API 30) or newer.
- Android platform tools (`adb`) on a trusted computer.
- USB debugging, or Android's paired **Wireless Debugging** (authenticated TLS).
- Node.js and X's [`xurl`](https://github.com/xdevplatform/xurl) CLI.
- Your own X developer app with pay-per-use credits and a Console hard limit.

The assisted provisioning script accepts USB or paired Wireless Debugging. The original direct ADB
injection workflow remains available for any exact serial already listed by `adb devices`.

## 1. Create and limit the X developer app

At [console.x.com](https://console.x.com):

1. Enable pay-per-use, add only the credits you intend to use, and configure the Console's hard
   spending limit.
2. Create an app with OAuth 2.0 user authentication:
   - type: **Native App**;
   - permissions: **Read**;
   - callback URI: `http://localhost:8080/callback`.
3. Copy the OAuth 2.0 **Client ID** and app-only **Bearer Token**.

The Bearer Token's literal `%2F` and `%3D` characters must not be URL-decoded. XTV does not use the
Client Secret.

## 2. Authorize off-device

XTV has no WebView and never asks for an X password. Authorization happens on the trusted computer:

```bash
npm install -g @xdevplatform/xurl
xurl auth apps add xtv --client-id '<client-id>' \
  --redirect-uri http://localhost:8080/callback
xurl auth default xtv
xurl auth oauth2
```

Use `xurl auth oauth2 --headless` when the computer has no browser. `xurl` stores the rotating
refresh token in `~/.xurl/auth.yml`; do not paste it into shell history.

Refresh tokens rotate when exchanged. If provisioning reports that the candidate token was rejected,
authorize again before retrying.

## 3. Establish a secure ADB transport

USB is simplest:

```bash
adb devices -l
```

For Wireless Debugging, use the pairing and connection ports shown by the TV. They are not the
legacy `5555` port:

```bash
adb pair <tv-ip>:<pairing-port>
adb connect <tv-ip>:<connection-port>
adb mdns services
```

For paired Wireless Debugging, the host-side mDNS list should show the exact serial under
`_adb-tls-connect._tcp`. In every workflow, verify the intended serial with `adb devices` before
continuing.

## 4. Install and provision

Install the candidate APK:

```bash
adb -s <secure-serial> install -r XTV-v1.3.0.apk
```

The public v0.1 update-lineage certificate SHA-256 is
`9ebbd0a688de30aedfe6b98a32c16e3d3579733d3581bbbf4de240648233c10b`. Verify it against the
published release provenance before provisioning; the script refuses any other installed signer.

Provision from the repository root, naming the exact `xurl` app and account so the script cannot
silently choose another refresh token:

```bash
XURL_APP='xtv' \
XURL_USER='<authorized-x-account>' \
./tools/provision.sh <secure-serial>
```

The script prompts for the client ID and reads the bearer without echo, keeping both out of shell
history. All three credentials are mandatory. The script:

- refuses an ambiguous device, account, app, or insecure transport;
- sends credentials only to XTV's DUMP-permission-protected provisioning entry point;
- uses a unique request identifier and waits for that request's result;
- verifies the candidate credentials before replacing the active account;
- never prints credential values.

Successful provisioning transfers the rotating refresh-token chain into XTV's encrypted state. The
source token left in `xurl` can then be stale; run `xurl auth oauth2` again before a later fresh
provision if the app no longer has its stored session.

The original direct ADB command is also supported, both for a cold start and while XTV is already
running:

```bash
adb -s <serial> shell am start -n com.xtv.app/.MainActivity \
  --es client_id '<client-id>' \
  --es refresh_token '<refresh-token>' \
  --es bearer '<app-only-bearer>'
```

This compatibility entry forwards the three values into the same validation, token rotation, and
atomic commit path used by `tools/provision.sh`. Use either workflow for a given refresh token, not
both, because a successful exchange rotates that token.

Reprovisioning the same X account and developer project preserves the current reel and cursor.
Switching X account clears account-owned reel, cursor, and terminal state. The billing journal and
usage cache belong to the developer project, so they remain when the project is unchanged; changing
project clears those project-owned values.

X's usage endpoint validates the app-only bearer but does not expose a stable project identifier.
XTV therefore cannot prove that a valid bearer and OAuth client ID came from the same developer
project. The operator must copy both from the same Console project.

The complete private state—credentials, tokens, account identity, cursors, reels, playback progress,
the billing journal, usage cache, and sanitized migration diagnostics—is stored in one
atomic envelope encrypted with an Android Keystore AES-GCM key. It is excluded from cloud backup
and device-to-device transfer. If the key is lost, XTV fails closed; restore the Keystore or confirm
“Reset everything” before provisioning again.

## Using XTV

The home screen is a console, not a gallery: it deliberately shows no thumbnails, because it is the
screen a TV sits on when nobody is using it, and a private Following timeline should not become
ambient decoration in a living room. Entries that mean nothing in the current state are not drawn at
all rather than greyed out, so a fresh install shows only the offers and **Settings**.

Accept one offer to fetch a batch. The purchase continues in application scope even if the activity
closes. Crash recovery charges only the exact `/users/me` exposure after identity dispatch, and
charges the full reservation only after the timeline request may have been dispatched. XTV never
silently retries a possibly paid page.

A partial page is retained, and the player marks it while playing what arrived. An empty successful
result keeps the previous batch but advances the paid cursor, and says so plainly — that
is the one outcome where money left the account with nothing to show for it. If the API returns more
resources than requested, XTV keeps and accounts for all of them without commenting on it.

Finished batches remain available to watch again from the start. **All N** opens the grid, which
marks what has been watched and which item is next. HOME pauses playback; returning restores the
prior play intent. A failed playback checkpoint shows a warning and continues rather than trapping
the viewer. The screen is kept awake only while video is actually playing.

Remote controls:

```text
OK            pause / resume
MEDIA keys    play / pause / next / previous
UP / DOWN     previous / next video
LEFT / RIGHT  seek -10s / +10s, acknowledged on screen
BACK          reveal details, then return
```

**Settings** holds the money: the X project's Post-read count, what that costs at the current rate,
the day X's period resets, and the two erasures. **Diagnostics** is a
separate screen holding the dated rate card and the recent request log; it appears on the home
screen only once there is something in it. Diagnostic output must never contain credential or signed
media URLs.

Two erasure levels are available:

- **Reset credentials** removes credentials and the live session while retaining the verified
  account binding, videos, cursor, and billing journal for safe same-identity reprovisioning.
- **Erase everything** removes credentials, videos, cursors, journals, and caches and requires
  provisioning again.

If a device has no usable credentials, the setup screen names which value is missing and shows a QR
code pointing at this README. It deliberately does not reprint the commands below: they cannot be
copied off a television, and every one of those states is fixed the same way — by running the setup
script again from a computer.

## Cost model

Do not infer cost from video count. Billing is per Post read, and a Post can carry any number of
videos or none. XTV's offer uses the current dated rate card and the exact request shape; the
Developer Console remains authoritative.

**For this request shape, only Post reads are billed.** A Console statement of 640 Post reads over
21 requests came to `$3.22`: `640 x $0.005`, plus two `$0.010` User reads — one `/users/me` per
provisioning. Authors arriving in `includes` are deduplicated away and expansions are not charged as
separate Media reads, so an offer of thirty Posts is `$0.15`, not the `$0.54` a per-Post model of
one User and 0.6 Media would predict. Published prices for those resources are unchanged and are
still in the rate card; what was wrong was the assumed **quantity** of them, by a factor of 3.6.

Offers are quoted as a range. The low end is that measured charge; the high end adds 20% headroom,
so the top of the range stays an upper bound rather than a coin flip.

The rate card is dated and its version is stamped into every offer token, then re-checked before
dispatch. Correcting the card therefore invalidates any offer quoted under the old model instead of
silently charging a new price for it. If the Console changes a rate, or evidence changes the
quantity model, update the dated code-side rate card before creating new offers.

## Failure guide

| Symptom | Meaning / action |
|---|---|
| `tools/provision.sh` refuses port `5555` | Use the documented direct ADB compatibility command, or switch the assisted workflow to USB/paired Wireless Debugging. |
| Device or `xurl` identity is ambiguous | Supply the exact ADB serial, `XURL_APP`, and `XURL_USER`. |
| Refresh token rejected | Run `xurl auth oauth2` again, then reprovision. |
| Developer Console reports no credits | Top up or stop; XTV does not bypass HTTP 402. |
| Console hard limit reached | Change the Console limit only after consciously reviewing actual charges. |
| API shape changed | Stop paid testing and inspect sanitized diagnostics before another purchase. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed APK has another signer. Verify provenance before uninstalling; uninstalling erases private state. |

## Building

```bash
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

No build variant accepts or compiles X credentials. Client IDs, refresh tokens, and bearer tokens
are runtime provisioning values only.

Debug-only fixtures exercise UI and playback without network or real persistence. Fixture entry
points do not exist in release builds.

## Releasing v1.3.0

Ordinary verification CI runs on pull requests and `main`: unit tests, release lint, a minified
release build, generated credential checks, manifest security checks, and an unsigned-candidate
SHA-256.

The publishing workflow accepts only the exact `v1.3.0` tag and requires
`versionName=1.3.0`, `versionCode=5`. It repeats the verification, verifies the signer against the
public v0.1 certificate lineage, and emits the signed APK plus SHA-256 file. It also publishes a
GitHub/Sigstore build-provenance attestation and the compressed R8 mapping needed to deobfuscate
maintainer crash reports.

After downloading a release, verify its workflow provenance with:

```bash
gh attestation verify XTV-v1.3.0.apk --repo <owner>/<repository>
```

Configure the GitHub `production-release` environment with required reviewers and restrict it to
the protected `v1.3.0` tag before enabling the publishing workflow. Workflow actions are pinned to
reviewed commit SHAs, checkout does not retain repository credentials, and Gradle verifies resolved
dependency artifacts against the reviewed checksums in `gradle/verification-metadata.xml`.

If a GitHub release already exists for the tag, the workflow fails. It never edits release notes,
moves the tag, or uploads with `--clobber`.

Required repository secrets:

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing-key alias |
| `ANDROID_KEY_PASSWORD` | Signing-key password |

No X credentials belong in CI.

## Research notes

[`docs/research/`](docs/research/) contains dated investigation notes, including rejected
reverse-engineered web/API approaches. Each note has a status banner. They are historical evidence,
not the current product contract; this README and tested code are authoritative.

## Licence

MIT. Not affiliated with X Corp.
