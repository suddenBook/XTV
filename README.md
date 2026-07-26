# XTV

An Android TV client that turns your X (Twitter) Following timeline into **the current reel** — a
finite, video-only run that plays end to end without touching the remote, and then stops.

It is not a Twitter client. There is no text feed, no browsing, no infinite scroll. You press one
button, it buys a fixed slice of your timeline, and it plays the videos in it.

UI follows the system language: English, 简体中文, 繁體中文.

---

## What you need

- An **Android TV** device running Android 11 or newer.
- A **computer with `adb`** on it, on the same network as the TV. (`adb` ships in Android platform
  tools; `brew install android-platform-tools`, `apt install adb`, or the Android SDK.)
- **Node.js**, for X's `xurl` CLI in step 4.
- An **X account** you can create a developer app on, with a little money in it. Reads are prepaid —
  a night's watching is about 15–50 cents.

Setup takes about ten minutes and you only do it once. The steps are in order; each one produces
something the next one needs.

---

## 1. Turn on debugging on the TV

XTV is sideloaded, so the TV has to accept a connection from your computer.

On Google TV / Android TV:

1. **Settings → System → About**, scroll to **Android TV OS build**, and press OK on it **seven
   times**. It will say *"You are now a developer"*.
2. **Settings → System → Developer options** → turn on **USB debugging**. If your device also lists
   **Network debugging** or **Wireless debugging**, turn that on too.
3. **Settings → Network & Internet** → select your network → note the TV's **IP address**.

Menu names vary a little by manufacturer, but every Android TV has this behind the build-number tap.

## 2. Connect adb to the TV

```bash
adb connect <tv-ip>:5555
adb devices -l
```

The TV shows an **"Allow USB debugging?"** prompt the first time. Accept it, and tick *Always allow
from this computer* so you do not have to do it again.

You should see your TV listed as `device`:

```
List of devices attached
192.168.1.4:5555     device product:kirkwood model:Google_TV_Streamer
```

If it says `unauthorized`, the prompt on the TV has not been accepted yet. If it says `offline` or
nothing connects, some devices need to be told to listen first — plug the TV in over USB once and run
`adb tcpip 5555`.

> If you have more than one device connected, add `-s <tv-ip>:5555` to every `adb` command below.

## 3. Create your X app

At [console.x.com](https://console.x.com):

1. Enable **pay-per-use** and top up credits. Reads are prepaid; with no balance the API returns HTTP
   402 and XTV says so rather than pretending your timeline is empty.
2. Create an app. Under **user authentication settings**:
   - **Type of App: Native App** (a public client)
   - **App permissions: Read** — XTV never writes anything
   - **Callback URI:** `http://localhost:8080/callback`
3. From **Keys and tokens**, copy two values and keep them somewhere for the next steps:
   - the **Client ID**
   - the app-only **Bearer Token**

> **Copy the Bearer Token exactly as shown.** It contains literal `%2F` and `%3D` characters. Those
> are part of the string, not percent-encoding — "decoding" them produces a token X rejects with a
> 401. Do not let a shell or a text editor rewrite them.

> You will also be shown a **Client Secret**. XTV never uses it: a Native App is a public client, and
> PKCE rather than a secret is what binds the authorization code. Ignore it.

## 4. Authorize your account

This is the step that proves you own the account. Use [`xurl`](https://github.com/xdevplatform/xurl),
X's own CLI, on your computer:

```bash
npm install -g @xdevplatform/xurl
xurl auth apps add xtv --client-id '<client id>' --redirect-uri http://localhost:8080/callback
xurl auth default xtv
xurl auth oauth2
```

`xurl auth oauth2` opens a browser and prints **OAuth2 authentication successful!** when you approve.
If the machine has no browser, use `xurl auth oauth2 --headless` instead: it prints a URL to open
anywhere, and you paste the redirected address back.

The refresh token lands in `~/.xurl/auth.yml`. You do not need to read it — the next step does.

> **The refresh token is single-use.** X issues a new one every time it is exchanged and immediately
> retires the old one. If step 6 fails and you have to retry, run `xurl auth oauth2` again first: the
> token in `auth.yml` will already have been spent.

## 5. Install XTV

Download the APK from [Releases](../../releases), then:

```bash
adb install -r XTV-v1.0.apk
```

`-r` reinstalls over an existing copy and **keeps your session**, so this is also how you update.

## 6. Sign the TV in

From the repository root:

```bash
XTV_CLIENT_ID='<client id>' \
XTV_BEARER='<bearer token>' \
./tools/provision.sh <tv-ip>:5555
```

The script reads the refresh token out of `~/.xurl/auth.yml` for you, sends all three values to the
app, and then checks that it actually worked. On success:

```
Provisioning 192.168.1.4:5555…
Done. The token is now stored on the device; you will not need this again unless you
uninstall, clear data, or install a build signed with a different key.
```

If you would rather not use the script, the equivalent is one command — but you have to supply the
refresh token yourself:

```bash
adb shell am start -n com.xtv.app/.MainActivity \
    --es client_id '<client id>' \
    --es refresh_token '<refresh token>' \
    --es bearer '<app-only bearer token>'
```

All three are stored in the app's private storage on the device and never leave it. The OAuth session
tokens are additionally encrypted with an AES-256-GCM key that lives in the device's hardware
keystore; the client id and bearer are held as plain values in the same sandboxed storage.

## Did it work?

Look at the TV. You should see the XTV home screen: **Build a reel** with Short / Standard / Long
cards, and along the bottom a line like

```
$2.75 this period · resets on the 26th
```

That last line is the proof that all three credentials are working — it is X's own spend figure, read
back from your account.

Press OK on **Short** to buy 30 posts (about 15 cents) and start watching.

To see what the app thinks happened:

```bash
adb logcat -d -s XTV:* XTV-API:* XTV-BUDGET:* XTV-DIAG:*
```

The line beginning `start:` says which screen it chose and why.

## If something went wrong

| What you see | What it means | What to do |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | An XTV signed with a different key is already installed | `adb uninstall com.xtv.app`, then install again. This wipes the session, so redo steps 4 and 6. |
| `adb: device unauthorized` | The debugging prompt on the TV was not accepted | Accept it on the TV, then `adb connect` again |
| *"X rejected the refresh token"* | The token was already spent — they are single-use | `xurl auth oauth2`, then rerun step 6 |
| *"Credentials stored, but there is no session"* | Same thing, caught a step later | `xurl auth oauth2`, then rerun step 6 |
| Setup screen: *"no X API credentials yet"* | The client id did not arrive | Rerun step 6 and check `XTV_CLIENT_ID` is set |
| Setup screen: *"missing the app-only Bearer Token"* | The bearer did not arrive | Rerun step 6 and check `XTV_BEARER` is set |
| Spend line says *"this month · upper-bound estimate"* | The bearer is present but X rejected it — almost always because its `%2F`/`%3D` got decoded | Recopy it verbatim from Keys and tokens and rerun step 6. `adb logcat -d -s XTV-DIAG:*` shows the HTTP status. |
| *"Your X account is out of API credits"* | No prepaid balance | Top up at console.x.com |
| Home screen but nothing plays | The reel had no videos in it | Normal some nights — a timeline is mostly not video. Try a longer reel. |

---

## Why the APK ships empty

**The released APK contains no credentials**, and that is not an inconvenience for its own sake. X
bills API usage to the **owner of the developer app**, and OAuth does not move that invoice to
whoever signs in. A build with someone's client id compiled into it would spend *their* credits for
every user, and could drain their balance until their own app stopped working. There is no
configuration that makes a shared build fair, so it ships with nothing.

Launching it before setup is harmless: it shows the setup guide instead of failing.

## Why all three credentials

The client id and the refresh token are what fetch a timeline. The Bearer Token is what makes the
spend figure true, and that is why it is not optional.

Without it XTV can only estimate, by counting the posts it asked for. That estimate is an **upper
bound** — X dedupes repeat reads of the same resource within a UTC day — and, worse, it covers a
*calendar month*. X bills over its own period, ending on `cap_reset_day`, which for most accounts is
not the 1st. So the two numbers describe different windows and are not comparable. For an app whose
every button spends real money, a figure that is approximately right over the wrong month is not good
enough.

With the bearer, XTV reads `GET /2/usage/tweets` for X's own consumed-post count and says which
period it covers. If that call ever fails the app falls back to the estimate and relabels it as one,
so the two are never presented as the same number.

Two caveats worth knowing. X publishes no billing or balance endpoint, so dollars are always that
count times the published per-post price; `console.x.com` remains the real invoice. And the figure is
*project*-wide — a script sharing the same credentials is included in it. That is why it is only ever
displayed, never used to enforce XTV's own monthly ceiling, which is measured against what this app
alone has spent.

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

---

## Building it yourself

```bash
echo "sdk.dir=$HOME/Android/Sdk" >> local.properties
./gradlew assembleDebug
```

Optionally bake your client id into a private build so you do not have to pass it every time. It is a
public-client identifier rather than a secret, but it still bills to you — never put it in anything
you publish:

```bash
echo "xtv.clientId=<client id>" >> local.properties   # git-ignored
```

The refresh token and bearer are never build-time values: they arrive over adb and live only on the
device.

To reproduce what a published build actually does — no compiled-in client id, so credentials injected
over adb have nothing to fall back on — override the property with an empty value:

```bash
./gradlew assembleRelease -Pxtv.clientId=
```

### Releases

`.github/workflows/release.yml` builds and publishes on every push to `main`. Repository secrets:

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

No X credentials are configured in CI, on purpose — see above.

### Development

```bash
./gradlew testDebugUnitTest     # no network, no spend
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
endpoints, including the paths investigated and rejected. `docs/decisions.md` records what was
deliberately left out, and why.

## Licence

MIT. Not affiliated with X Corp.
