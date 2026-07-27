# XTV

An Android TV app that buys a fixed-size slice of your X (Twitter) **Following** timeline and plays
the videos in it, back to back, on your television.

It is not a Twitter client. There is no text feed, no infinite scroll, no posting, and no background
polling. You press one button, it fetches one batch, and you watch it.

The app follows your TV's system language: English, 简体中文, 繁體中文.

---

## What it costs

XTV uses X's official API on **your own** developer app, so X bills **you** for every batch you
fetch. Nothing is free and nothing is subsidised.

Billing is **per Post read**, at `$0.005` each — not per video. Setup costs one extra `$0.010` User
read.

| You press | Posts read | You pay | You get, roughly |
|---|---|---|---|
| Short | 30 | $0.15 | ~18 videos |
| Standard | 60 | $0.30 | ~36 videos |
| Long | 100 | $0.50 | ~60 videos |

Every offer shows its price before you press it, as a range — the high end is a conservative upper
bound and your bill will not exceed it. A batch is only fetched when you accept a priced offer.

**Set a hard spending limit in the X Developer Console before you start.** That limit is the only
thing that can actually stop a charge. XTV shows you what you are spending; it does not cap it.

---

## Before you begin

You need:

- An **Android TV / Google TV** box running Android 11 (API 30) or newer.
- A computer with **`adb`** (Android platform tools) installed.
- **Node.js**, for X's `xurl` CLI.
- An **X account** and about ten minutes.

Everything below happens on the computer. The TV never shows a login screen and never asks for your
X password.

---

## Step 1 — Create your X developer app

At **[console.x.com](https://console.x.com)**:

1. Enable **pay-per-use** and add only the credit you intend to spend.
2. **Set the hard spending limit.** Do not skip this.
3. Create an app with OAuth 2.0 user authentication:
   - App type: **Native App**
   - App permissions: **Read**
   - Callback URI: `http://localhost:8080/callback`
4. From the app's **Keys and tokens** page, copy two values and keep them somewhere safe:
   - the **OAuth 2.0 Client ID**
   - the **Bearer Token** (app-only)

> ⚠️ The Bearer Token contains literal `%2F` and `%3D` characters. They are part of the token — do
> **not** URL-decode them, and do not let a text editor "fix" them. A decoded bearer fails with a
> 401 that looks exactly like a wrong token.

You will not need the Client Secret. XTV does not use it.

---

## Step 2 — Authorise your account on the computer

Install X's CLI and log in once:

```bash
npm install -g @xdevplatform/xurl

xurl auth apps add xtv \
  --client-id '<your-client-id>' \
  --redirect-uri http://localhost:8080/callback

xurl auth default xtv
xurl auth oauth2
```

A browser opens; approve the app. If the computer has no browser, use `xurl auth oauth2 --headless`.

You should see `OAuth2 authentication successful!`. The token is stored in `~/.xurl/auth.yml`.

> ⚠️ **Refresh tokens are single-use.** Provisioning the TV consumes the one you just created. If
> you ever need to set up a device again, run `xurl auth oauth2` again first.

---

## Step 3 — Connect to the TV over adb

**USB** is simplest — plug the TV in and check it is listed:

```bash
adb devices -l
```

**Wireless**: on the TV, enable Developer options → Wireless debugging. It shows two different port
numbers, one for pairing and one for connecting. Neither is the old `5555`.

```bash
adb pair <tv-ip>:<pairing-port>
adb connect <tv-ip>:<connection-port>
adb devices -l
```

Note the exact serial shown by `adb devices` — you will pass it in the next step.

---

## Step 4 — Install the APK

Download the APK from [Releases](https://github.com/suddenBook/XTV/releases) and install it:

```bash
adb -s <serial> install -r XTV-v1.3.0.apk
```

Optionally, verify the release really came from this repository's build workflow:

```bash
gh attestation verify XTV-v1.3.0.apk --repo suddenBook/XTV
```

Opening the app now shows **"Setup needed"** with a QR code. That is expected — it has no
credentials yet.

---

## Step 5 — Provision the credentials

From the repository root:

```bash
XURL_APP='xtv' \
XURL_USER='<your-x-handle>' \
./tools/provision.sh <serial>
```

It prompts for the **Client ID**, then the **Bearer Token** (hidden as you type, so nothing lands in
your shell history). It then verifies the credentials before installing them, and prints the result.

The TV shows **"Setup complete"**, then the home screen with three offers. You are done.

<details>
<summary>If <code>provision.sh</code> refuses to run</summary>

The script deliberately refuses ambiguous or insecure setups: more than one connected device, an
unclear `xurl` account, or the legacy `5555` port. Either fix what it names, or use the direct
command below, which goes through exactly the same verification and commit path:

```bash
adb -s <serial> shell am start -n com.xtv.app/.MainActivity \
  --es client_id '<client-id>' \
  --es refresh_token '<refresh-token-from-~/.xurl/auth.yml>' \
  --es bearer '<bearer-token>'
```

Use one method or the other for a given token, never both — a successful exchange consumes it.
</details>

---

## Using it

The home screen shows three offers. Pick one, wait a few seconds, and the player starts.

| Button | Does |
|---|---|
| **OK** | Pause / resume |
| **UP** / **DOWN** | Previous / next video |
| **LEFT** / **RIGHT** | Skip back / forward 10 seconds |
| **BACK** | Show who posted it; again to leave |
| Media keys | Play, pause, next, previous |

**All N** opens a grid of the whole batch, marking what you have watched and which is next; pick any
one to jump to it. When a batch finishes it stays watchable — **Watch again from the start**.

Your place is saved continuously, so you can leave and come back mid-batch.

**Settings** shows what your developer project has read from X, what it cost, and the day X's
billing period resets. It is X's own meter and covers everything using those credentials, not just
this app. It also holds the two erase actions:

- **Reset credentials** — removes the credentials and session, keeps your videos. Setting up the
  same account again restores everything.
- **Erase everything** — removes everything. You will need to provision again.

**Diagnostics** appears on the home screen only once something has been recorded. It shows recent
requests and the rate card version — useful if a fetch fails and you want to know why.

---

## When something goes wrong

| What you see | What to do |
|---|---|
| "Setup needed" after provisioning | Check the terminal output. The most common cause is a URL-decoded bearer token. |
| Setup fails: `TOKEN_REJECTED` | The refresh token was already used or expired. Run `xurl auth oauth2` again, then reprovision. |
| Setup fails: `BEARER_REJECTED` | Wrong bearer, or its `%2F`/`%3D` were decoded. Re-copy it from the Console. |
| Setup fails: `BEARER_VALIDATION_UNAVAILABLE` | X could not be reached. Check the network and retry; your credentials are fine. |
| "Setup unfinished" | A previous attempt was interrupted. Run the same command again — it resumes. |
| "Local data can't be read" | The device's encryption key is gone (usually after a factory reset). Press **Clear and start over**, then provision again. |
| "The X project is out of API credits" | Top up in the Console. XTV will not work around a 402. |
| "X is rate limiting" | Wait a few minutes. |
| "No videos this time" | The batch was all text and images. You were still charged — that is how per-Post billing works. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app was signed by a different key. Uninstalling erases local data, so check where the other build came from first. |

---

## Privacy

Your timeline is private, and XTV treats it that way:

- Credentials, tokens, videos and progress live in **one encrypted file**, sealed with an AES-GCM
  key held in the Android keystore and never readable by the app itself.
- It is excluded from cloud backup and device-to-device transfer.
- Release builds block screenshots and screen recording.
- Diagnostics never contain tokens or media URLs.
- The home screen deliberately shows no thumbnails — it is the screen a TV sits on when nobody is
  using it.

---

## Building from source

```bash
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

No build variant contains X credentials; they are runtime provisioning values only. Debug builds
include offline fixtures for exercising the UI without spending anything, and none of that code
exists in a release build.

<details>
<summary>Cutting a release</summary>

CI runs on pull requests and `main`: unit tests, release lint, a minified release build, credential
and manifest checks, and an unsigned-candidate SHA-256.

The publishing workflow accepts only the exact `v1.3.0` tag with `versionName=1.3.0`,
`versionCode=5`. It re-runs verification, checks the signer against the published certificate
lineage, and emits the signed APK, its SHA-256, a Sigstore build-provenance attestation, and the R8
mapping file.

Required repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. No X credentials belong in CI.
</details>

---

## Licence

MIT. Not affiliated with X Corp.
