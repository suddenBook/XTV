# XTV

An Android TV client that turns your X (Twitter) Following timeline into **the current reel** — a
finite, video-only run that plays end to end without touching the remote, and then stops.

It is not a Twitter client. There is no text feed, no browsing, no infinite scroll. You press one
button, it buys a fixed slice of your timeline, and it plays the videos in it.

UI follows the system language: English, 简体中文, 繁體中文.

---

## Install

Download the APK from [Releases](../../releases) and sideload it:

```bash
adb install -r XTV-v0.1.apk
```

**The released APK contains no credentials.** You supply your own on first run — see below. That is
not an inconvenience for its own sake: X bills API usage to the **owner of the developer app**, and
OAuth does not move that invoice to whoever signs in. A build with someone's client id compiled into
it would spend *their* credits for every user, and could drain their balance until their own app
stopped working. There is no configuration that makes a shared build fair, so the APK ships empty.

Launching it without credentials is harmless: it shows a setup guide instead of failing.

## Get your credentials

### 1. Register an X app

At [console.x.com](https://console.x.com):

- Enable **pay-per-use** and top up credits. Reads are prepaid; with none, the API returns HTTP 402
  and XTV says so rather than pretending your timeline is empty.
- Create an app, and under user authentication settings:
  - **Type of App: Native App** (public client)
  - **App permissions: Read** — XTV never writes
  - **Callback URI:** `http://localhost:8080/callback`
- Copy the **Client ID**.

> You will also be shown a **Client Secret**. XTV never uses it — a Native App is a public client and
> PKCE, not a secret, is what binds the authorization code. Ignore it.

### 2. Authorize once, anywhere with a browser

Use [`xurl`](https://github.com/xdevplatform/xurl), X's own CLI:

```bash
npm install -g @xdevplatform/xurl
xurl auth apps add xtv --client-id '<client id>' --redirect-uri http://localhost:8080/callback
xurl auth default xtv
xurl auth oauth2 --headless      # prints a URL; open it on any device, paste the redirect back
```

The refresh token lands in `~/.xurl/auth.yml`.

### 3. Hand both values to the TV

```bash
adb shell am start -n com.xtv.app/.MainActivity \
    --es client_id '<client id>' \
    --es refresh_token '<refresh token>'
```

They are stored on the device and never leave it. Done — the app is signed in.

> **Refresh tokens rotate.** X issues a new one on every refresh and spends the old one, so the value
> you inject is a *bootstrap*: after the first use the app's stored copy is the authority, and your
> `xurl` copy is dead (re-run `xurl auth oauth2` if you want the CLI back). Reinstalling over the top
> (`adb install -r`) keeps the session; `adb uninstall` or `pm clear` destroys it and you will need a
> freshly issued token.

### Optional: exact spend figures

By default the app estimates spend from the posts it read, which is an **upper bound** — X dedupes
repeat reads of the same resource within a UTC day. To show X's own count instead, add the app-only
**Bearer Token** from the app's Keys and tokens page:

```bash
adb shell am start -n com.xtv.app/.MainActivity --es bearer '<app-only bearer token>'
```

XTV then reads `GET /2/usage/tweets` for the authoritative consumed-post count. X publishes no
billing or balance endpoint, so dollars are still that count times the published per-post price;
`console.x.com` remains the real invoice.

## What it costs

Billing is per **post read**, not per video watched, and a timeline is mostly not video. On the
timeline this was measured against, ~69% of posts carried media and ~60% of that media was video:

| Reel | Posts read | ≈ videos | Cost |
|---|---|---|---|
| Short | 30 | ~18 | $0.15 |
| Standard | 60 | ~36 | $0.30 |
| Long | 100 | ~60 | $0.50 |

One reel a night is roughly **$9/month**. A monthly ceiling (default $20) trims a request rather than
refusing it, and only surfaces once it is actually crossed.

Two things XTV will not do: fetch anything on launch, or fetch anything mid-reel. Every request sits
behind a keypress that has already told you its price.

## Remote

```
OK            pause / resume — always, regardless of what is on screen
UP / DOWN     previous / next video
LEFT / RIGHT  seek ±10s
BACK          first press shows the post; pressing it again leaves
```

`MENU` is deliberately unused: Android TV does not guarantee remotes have one.

## Building it yourself

```bash
echo "sdk.dir=$HOME/Android/Sdk" >> local.properties
./gradlew assembleDebug
```

Optionally bake credentials into a private build so it installs already signed in — never do this to
anything you publish, since the APK then *is* an account credential:

```bash
echo "xtv.clientId=<client id>"       >> local.properties   # git-ignored
echo "xtv.refreshToken=<token>"       >> local.properties
```

### Releases

`.github/workflows/release.yml` builds and publishes on every push to `main`. Repository secrets:

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

No X credentials are configured in CI, on purpose — see the top of this file.

### Development

```bash
./gradlew testDebugUnitTest     # parser golden tests — no network, no spend
```

The parser is tested against sanitised captures of real API responses in
`app/src/test/resources/fixtures/`. To refresh them after an upstream change, capture with
`tools/phase0/probe.sh` and run `tools/phase0/sanitize_fixtures.py` — it rewrites ids, handles, text
and URLs, and refuses to write a file if any real URL survives. Raw captures are git-ignored; they
are somebody's actual timeline.

Play a fixture with no token and no spend:

```bash
adb shell am start -n com.xtv.app/.MainActivity --es fixture dead_links.json
```

`docs/research/` records why this is built on the official API rather than the reverse-engineered web
endpoints, including the paths that were investigated and rejected.

## Licence

MIT. Not affiliated with X Corp.
