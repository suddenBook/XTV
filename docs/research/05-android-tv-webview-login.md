# 05 — Logging into x.com in an Android TV WebView & harvesting the session cookie

> **Status: ARCHIVED / REJECTED PATH.** XTV v1.1.0 removes the login WebView and does not harvest
> cookies. OAuth authorization happens off-device with `xurl`; the TV accepts a staged candidate
> only through the DUMP-protected provisioning activity over secure ADB.

Research date: **2026-07-26**. Claim tags: `[VERIFIED src]` / `[LIKELY reasoning]` / `[UNKNOWN — needs live test]`.
Live HTTP probes below were unauthenticated `curl` requests only (no login attempted).

Source files fetched to `<scratchpad>/`: `awsettings.java` (Chromium `AwSettings.java`), `awcookie.java`
(`AwCookieManager.java`), `gdl_twitter.py` (gallery-dl), `ytdlp_twitter.py` (yt-dlp),
`hdr_WV.txt`/`hdr_CH.txt`/`body_WV.html`/`body_CH.html` (live x.com probe).

---

## 0. Verdict up front

WebView login is **the right call** and will very probably work. The three things that will actually
bite you, in order:

1. **Arkose/FunCaptcha on a new-device login** — the only genuinely unresolved failure mode. §3.4.
2. **Typing the password with a D-pad** — solvable, see the Autofill trick in §4.4.
3. **Passkey-only or security-key-2FA accounts cannot log in** — hard blocker, WebView has no usable
   WebAuthn for a domain you don't own. §3.1–3.2.

Two findings materially de-risk the plan and are worth reading first:

- **X does not UA-sniff or block WebView.** The famous "browser not supported" page is X's `<noscript>`
  block. Android WebView ships with `javaScriptEnabled = false`. That's the whole bug. §2.
- **Chromium enables spatial (D-pad) navigation by default on non-touchscreen devices**, i.e. exactly on
  Android TV. You get focus-moves-with-D-pad inside web pages for free, no JS injection. §4.2.

---

## 1. Cookie harvesting

### 1.1 `CookieManager.getCookie()` returns HttpOnly cookies — VERIFIED

The web-search consensus on this is **wrong**. Primary source, Chromium:

`android_webview/browser/cookie_manager.cc`:
```cpp
void CookieManager::GetCookieListAsyncHelper(const GURL& host,
                                             net::CookieList* result,
                                             base::OnceClosure complete) {
  net::CookieOptions options = net::CookieOptions::MakeAllInclusive();
```
`net/cookies/cookie_options.cc`:
```cpp
CookieOptions CookieOptions::MakeAllInclusive() {
  CookieOptions options;
  options.set_include_httponly();
  options.set_same_site_cookie_context(SameSiteCookieContext::MakeInclusive());
  options.set_do_not_update_access_time();
  return options;
}
```
`[VERIFIED — chromium/main android_webview/browser/cookie_manager.cc + net/cookies/cookie_options.cc]`

So the native API *does* see HttpOnly, unlike JS `document.cookie`. The widespread "Android only returns
non-httpOnly cookies" claim traces to React Native wrappers
([react-native-cookies#76](https://github.com/react-native-cookies/cookies/issues/76), open, no
resolution) whose `getAll()` path does not use `CookieManager.getCookie()`. Ignore it.

`AwCookieManager.getCookie` javadoc:
> "Get cookie(s) for a given url so that it can be set to "cookie:" in http request header.
> @return The cookies in the format of NAME=VALUE [; NAME=VALUE]"

Returns `null` (not `""`) when empty — "Return null if the string is empty to match legacy behavior".
`[VERIFIED — AwCookieManager.java:126–139]`

**No attributes** are returned, just `name=value` pairs. If you want flags:
`androidx.webkit.CookieManagerCompat.getCookieInfo(cookieManager, url)`, gated on
`WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)`, androidx.webkit ≥ 1.6.0 —
> "Get the attributes of any cookie(s) for a given url. @return The cookies as a list of Strings
> formatted like http set cookie headers."

`[VERIFIED — AwCookieManager.java:167–177 + androidx.webkit release notes]`. Useful for one-time
debugging (confirm `Secure`/`HttpOnly`/`Max-Age` on the real cookies); not needed at runtime.

### 1.2 Flushing to disk

`android.webkit.CookieManager.flush()`:
> "Ensures all cookies currently accessible through the getCookie API are written to persistent storage.
> This call will block the caller until it is done and may perform I/O."

`[VERIFIED — AOSP frameworks/base core/java/android/webkit/CookieManager.java javadoc]`

Implementation is `AwCookieManager.flushCookieStore()` → `FlushCookieStoreAsyncHelper` →
`GetMojoCookieManager()->FlushCookieStore()` or `GetCookieStore()->FlushStore()`
`[VERIFIED — AwCookieManager.java:220 + cookie_manager.cc]`.

- Blocking + I/O → **call off the main thread** once login completes.
- WebView cookies live in the app's private data dir, so they survive process death anyway; `flush()`
  guards against an unclean kill before Chromium's lazy write-back.
- Path is `/data/data/<pkg>/app_webview/Default/Cookies` (SQLite) `[LIKELY — standard Chromium profile
  layout; not verified for this WebView version]`. **Don't read it directly**; `getCookie()` is the
  supported route and avoids WAL/locking issues with a live browser process.

### 1.3 `.x.com` vs `.twitter.com` — only `.x.com` matters

Live probe, `GET https://x.com/i/flow/login`, `Set-Cookie` headers (values elided):
```
guest_id=<v>; Max-Age=34214400; Expires=Thu, 26 Aug 2027 ...; Path=/; Domain=.x.com; Secure; SameSite=None
ct0=<v>;      Max-Age=-1785076633; Expires=Thu, 01 Jan 1970 ...; Path=/; Domain=.x.com; Secure; SameSite=Lax
__cf_bm=<v>;  HttpOnly; SameSite=None; Secure; Path=/; Domain=x.com; Expires=...
```
`[VERIFIED — live probe 2026-07-26, hdr_WV.txt]`

Load-bearing detail: **`ct0` carries no `HttpOnly` flag.** It is deliberately JS-readable (double-submit
CSRF: cookie value is echoed into the `x-csrf-token` header). `auth_token` is not set on an
unauthenticated request so I could not observe its flags — but every scraper reads it from the cookie
jar rather than `document.cookie`, consistent with `HttpOnly`. `[VERIFIED for ct0 — live probe]`
`[LIKELY for auth_token = HttpOnly]` — irrelevant either way, since §1.1 means `getCookie()` gets both.

Also observed inline in the page body:
```html
<script nonce="...">document.cookie="gt=2081388379258712411; Max-Age=9000; Domain=.x.com; Path=/; Secure";</script>
```
The guest token `gt` is set from JS. Matches gallery-dl's `cookies.set("gt", guest_token, domain=".x.com")`
(`gdl_twitter.py:1868`). `[VERIFIED]`

**twitter.com is dead for the app shell.** `GET https://twitter.com/home` → **HTTP 403**, no redirect,
and sets `Domain=.twitter.com` cookies that nothing consumes (`https://twitter.com/` root → 200).
`[VERIFIED — live probe]`. Corroborating: gallery-dl hardcodes `cookies_domain = ".x.com"`
(`gdl_twitter.py:27`); yt-dlp reads cookies for `_API_BASE = 'https://api.x.com/1.1/'`
(`ytdlp_twitter.py:36,98,117`). X also forcibly migrated security-key/passkey RP IDs off twitter.com with
a 2025-11-10 re-enrol deadline.

→ **Harvest with `getCookie("https://x.com/")`.** `https://` matters (`Secure` cookies). A `Domain=.x.com`
cookie is also sent to `api.x.com`, so one read covers the API host too.

### 1.4 Which cookies you actually need: two

`auth_token` + `ct0`. Nothing else.

- yt-dlp: `is_logged_in` = `bool(self._get_cookies(self._API_BASE).get('auth_token'))` (`:98`);
  `'x-csrf-token': ...['ct0'].value` (`:117`). `[VERIFIED]`
- gallery-dl: `cookies_names = ("auth_token",)` (`:27`); `x-csrf-token` from `ct0`;
  `"x-twitter-auth-type": "OAuth2Session" if auth_token else None` (`:1349`). `[VERIFIED]`
- Neither tool references `att`, `kdt`, `twid`, `_twitter_sess`, or `auth_multi` for read paths.
  `[VERIFIED — grep of both files]`

Curiosity worth knowing: gallery-dl will **mint its own** `ct0` if absent —
```python
if not csrf_token:
    csrf_token = util.generate_token()
    cookies.set("ct0", csrf_token, domain=cookies_domain)
```
(`gdl_twitter.py:1338–1340`). Pure double-submit; the server only checks cookie==header. `[VERIFIED]`
Use the real `ct0` anyway — a self-minted one is one more anomaly signal on a main account.

### 1.5 Harvest *after* the flow finishes, not on first sight of `auth_token`

The login subtask chain sets cookies incrementally: `att` during the initial `login` response,
`auth_token` around `AccountDuplicationCheck`/post-2FA, and **`ct0` twice — a short value during
`LoginTwoFactorAuthChallenge`, then the long value from the `Viewer` step**.
`[VERIFIED — blog.nest.moe/posts/how-to-login-to-twitter, a detailed flow reconstruction; secondary but
specific and consistent with the scrapers]`

Practical rule: don't latch on `onPageFinished` seeing `auth_token`. Wait until the WebView has navigated
to an authenticated app URL (`https://x.com/home`), then read. Better: re-read `getCookie()` on every
`doUpdateVisitedHistory`/`onPageFinished` and only accept when both `auth_token` and a `ct0` of stable
length are present, then `flush()`.

### 1.6 Sharing with OkHttp

There is **no shared cookie store** — WebView's jar is Chromium's, OkHttp's is a `CookieJar` in your JVM.
Bridge it manually. Two sane shapes:

- **Simplest:** treat `CookieManager` as the single source of truth and implement
  `CookieJar.loadForRequest()` → parse `CookieManager.getCookie("https://x.com/")`;
  `saveFromResponse()` → `CookieManager.setCookie(url, setCookieHeaderString)` (note: `setCookie` wants a
  full Set-Cookie-style string, and there's a `setCookie(url, value, callback)` async form,
  `AwCookieManager.java:95,118`). Then `flush()` periodically. Keeps one jar, survives ct0 rotation.
- **Or:** snapshot `auth_token`/`ct0` once into your own store and set a literal `Cookie:` header.
  Fragile — X rotates `ct0` on some responses (gallery-dl re-reads it from responses at `:1908–1909`,
  citing issue #1170, and again at `:1877–1881` citing #7467). If you snapshot, you must also re-read
  `Set-Cookie: ct0` off every OkHttp response and update the header.

`[VERIFIED — API shapes from AwCookieManager.java; ct0 rotation from gallery-dl comments]`

---

## 2. Will x.com render in a WebView? Yes — and the famous blocker is your own default settings

### 2.1 No server-side UA gating — VERIFIED by experiment

I fetched `https://x.com/i/flow/login` twice, changing only the UA:

| UA | Result |
|---|---|
| `Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/125.0.0.0 Mobile Safari/537.36` (default reduced WebView UA, **has `wv`**) | HTTP 200, 266,915 bytes |
| same string minus `; wv` and `Version/4.0` (Chrome-on-Android) | HTTP 200, 266,915 bytes |

Byte counts identical; a token-normalised `diff` shows the *only* differences are the per-response CSP
nonce, `guest_id`, the `gt` guest token, `serverDate`, and `twitter-site-verification`. Same JS bundles
(`vendor.6f0bbb5a.js`, `main.2a1d8c9a.js`, `bundle.LoggedOutShell.932526aa.js`,
`bundle.LoggedOutRoutes.3851846a.js`, `bundle.Ocf.f42ed0da.js`, all from `abs.twimg.com`).
`[VERIFIED — live probe 2026-07-26; body_WV.html vs body_CH.html]`

**X serves the identical login page to a self-identified WebView.** No `wv` blocklist at the edge.

### 2.2 "Browser not supported" is X's `<noscript>` block

Extracted verbatim from the page I fetched:
> `<h1>JavaScript is not available.</h1>`
> `<p>We've detected that JavaScript is disabled in this browser. Please enable JavaScript or switch to a
> supported browser to continue using x.com. You can see a list of supported browsers in our Help Center.</p>`
> `<p class="errorButton"><a href="https://help.x.com/using-x/x-supported-browsers">Help Center</a></p>`

`[VERIFIED — body_WV.html]`

And Android WebView's default:
```java
private boolean mJavaScriptEnabled;      // AwSettings.java:168 — no initializer ⇒ false
private boolean mDomStorageEnabled;      // AwSettings.java:173 — no initializer ⇒ false
```
`[VERIFIED — chromium/main android_webview/.../AwSettings.java]`

This explains every report in the wild:
[react-native-webview#3473](https://github.com/react-native-webview/react-native-webview/issues/3473)
("X-Twitter is said that the browser is not support", Jun 2024, closed as not-planned),
[electron#25421](https://github.com/electron/electron/issues/25421), and the
[Apple WKWebView thread](https://developer.apple.com/forums/thread/653357) whose accepted fix was a
config that sets `preferences.javaScriptEnabled = true`. **Nobody was being blocked; JS was off.**

### 2.3 Required `WebSettings`

```kotlin
settings.javaScriptEnabled = true          // MANDATORY — default false [VERIFIED]
settings.domStorageEnabled = true          // MANDATORY — SPA needs localStorage; default false [VERIFIED]
settings.userAgentString = <see 2.4>       // recommended
settings.mediaPlaybackRequiresUserGesture = false   // default true [VERIFIED AwSettings.java:178]
settings.javaScriptCanOpenWindowsAutomatically = true  // default false [VERIFIED :171]
settings.setSupportMultipleWindows(true)   // default false [VERIFIED :172]; + onCreateWindow in WebChromeClient
CookieManager.getInstance().setAcceptCookie(true)                     // default true [VERIFIED]
CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) // default FALSE — see 2.5
```
`mixedContentMode` defaults to `MIXED_CONTENT_NEVER_ALLOW` (`AwSettings.java:184`) — correct, leave it.
`loadsImagesAutomatically`/`imagesEnabled` default true (`:166–167`).

Multiple-windows + `onCreateWindow` matter only if you'd ever use "Sign in with Google/Apple" (X's CSP
allows `accounts.google.com`, `appleid.cdn-apple.com`). For password login you can skip them, but leaving
them on avoids a dead-end if X routes something through a popup. `[LIKELY]`

### 2.4 User-Agent

Override it. Not because the edge blocks `wv` (it doesn't, §2.1) but because client-side bundles can gate
on it, and you get determinism. Safest recipe: **take `WebSettings.getDefaultUserAgent(context)` and strip
the `; wv` token**, leaving everything else (crucially the real `Chrome/<major>` of the installed WebView)
intact. Fabricating a version that doesn't match the engine is worse than leaving `wv` in.
`[LIKELY — reasoning; the reduced-UA blog confirms "Custom User-Agent strings won't be affected" and that
`wv` is the documented WebView marker]`

Heads-up on UA reduction: Google announced the reduced default WebView UA
`Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/125.000 Mobile Safari/537.36`.
The Dec-2024 blog post and @AndroidDev tweet say **Android 16**; the page as served today says **Android 17**.
Treat the exact release as `[UNKNOWN]`; treat "the default UA will lose OS/build detail" as certain. If you
set your own string you're insulated from the whole thing.

### 2.5 Third-party cookies — you probably need them ON for the login WebView

`AwSettings.java:209` — `private boolean mAcceptThirdPartyCookies;` no initializer ⇒ **false by default**,
per-WebView. `[VERIFIED]` (There is also a `WEBVIEW_FORCE_DISABLE3PCS` command-line switch that makes
`setAcceptThirdPartyCookies()` a no-op, `AwSettings.java:581–584` — a rollout/testing switch, not
app-settable in release. `[VERIFIED]`)

Why it matters: X's own CSP names the Arkose iframe origins:
```
frame-src 'self' ... https://client-api.arkoselabs.com/ https://iframe.arkoselabs.com/ ...
script-src 'self' 'unsafe-inline' https://*.twimg.com ... https://client-api.arkoselabs.com/ ...
```
`[VERIFIED — live CSP response header, hdr_WV.txt]`

FunCaptcha therefore runs **cross-origin in an iframe**. Whether it *needs* 3P cookies in 2026 (post
Chrome's 3PC work, Arkose has had to function without them) is `[UNKNOWN]`, but for a one-shot login
WebView there is no downside to `setAcceptThirdPartyCookies(loginWebView, true)`.

### 2.6 Other things the live headers tell you

- `server: cloudflare envoy`, `cf-ray: ...`, and an `HttpOnly __cf_bm` cookie → **Cloudflare Bot
  Management fronts x.com**. `[VERIFIED]` A real Chromium WebView is the *best* client you could bring to
  that fight; a bare OkHttp client is the worst. This is an argument for the WebView, and against
  ever replaying the session from a plain HTTP client with a fabricated UA.
- `report-uri https://x.com/i/csp_report?a=...` → **CSP violations are reported to X.** `[VERIFIED]`
  If you inject scripts into the real x.com document and trip CSP, you are actively telling X.
  Relevant to §5's "load a synthetic origin instead" recommendation.
- `x-frame-options: DENY` → you cannot iframe x.com. `[VERIFIED]`
- `connect-src` includes `'self' ... https://api.x.com https://x.com` → same-origin `fetch()` to
  `/i/api/graphql/...` is CSP-legal. `[VERIFIED]`

---

## 3. Login obstacles

### 3.1 Passkeys — confirmed blocker (with a nuance)

WebView is **not** categorically WebAuthn-less any more, but the escape hatch doesn't help you.

`WebSettingsCompat.setWebAuthenticationSupport(webView.settings, WEB_AUTHENTICATION_SUPPORT_FOR_APP)`,
gated on `WebViewFeature.WEB_AUTHENTICATION`, androidx.webkit ≥ 1.12.0, routes WebAuthn through Credential
Manager / Play Services FIDO2. **But:**
> "You will also need to associate your app with a website that your app owns using digital asset linking."

`[VERIFIED — developer.android.com/identity/sign-in/credential-manager-webview]`

You do not own x.com and cannot publish `/.well-known/assetlinks.json` there ⇒ **`FOR_APP` mode cannot
mint or use x.com passkeys.** A `WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER` value exists (recent androidx
addition, "Add browser value for get/setWebAuthenticationSupport API") intended for browser apps; whether
it permits arbitrary RP IDs and what gating it carries is `[UNKNOWN — I could not fetch the constant's
javadoc; developer.android.com's reference index kept truncating]`. Assume no.

Also documented: "The WebKit library doesn't support `mediation:"conditional"` requests" — so even in the
best case, passkey autofill on the username field won't work. `[VERIFIED — same doc]`

→ **The account must have a usable password.** A passkey-only X account cannot log in through XTV.

### 3.2 2FA

| Method | Works in WebView? |
|---|---|
| TOTP / authenticator app | **Yes.** 6 digits, D-pad typeable. `[LIKELY — plain form input]` |
| SMS | Mostly moot: X restricted SMS 2FA to paid subscribers in Mar 2023. `[LIKELY — widely reported]` |
| Hardware security key (U2F/WebAuthn) | **No** — same WebAuthn wall as §3.1. `[LIKELY]` |
| Backup code | Yes, plain text input. `[LIKELY]` |

Context: X forced re-enrolment of Yubikeys and passkeys by **2025-11-10** because they were "tied to the
twitter[.]com domain"; X's own @Safety account confirmed it "only impacts Yubikeys and passkeys - not
other 2FA methods (such as authenticator apps)". `[VERIFIED — x.com/Safety/status/1982278858457174522 via
search + The Register 2025-10-27]`

→ **Pre-flight advice for the dev: before writing the login screen, switch the account's 2FA to
authenticator-app TOTP and make sure a password is set.** That converts two hard blockers into typing.

### 3.3 The onboarding subtask flow (context for what the WebView must survive)

Web login walks `https://api.x.com/1.1/onboarding/task.json?flow_name=login` through subtasks:
`LoginJsInstrumentationSubtask` → `LoginEnterUserIdentifierSSO` → (`LoginEnterAlternateIdentifierSubtask`)
→ `LoginEnterPassword` → `AccountDuplicationCheck` → (`LoginTwoFactorAuthChooseMethod` →
`LoginTwoFactorAuthChallenge`) → `Viewer`.
`[VERIFIED — blog.nest.moe reconstruction; subtask names corroborated by twscrape / TwitterFrontendFlow /
unofficial-twitter-api-client-go]`

`LoginJsInstrumentationSubtask` makes the client fetch and **execute** a JS blob from
`https://x.com/i/js_inst?c_name=ui_metrics` and post the result. A real browser does this transparently.
This is the single strongest argument for WebView-based login over reimplementing the flow in Kotlin —
and it is precisely why gallery-dl gave up:

```python
def _login_impl(self, username, password):
    self.log.error("Login with username & password is no longer "
                   "supported. Use browser cookies instead.")
    return {}
```
`[VERIFIED — gdl_twitter.py:791–794]`

A maintained scraper with strong incentive to support password login **deleted the feature and tells users
to bring browser cookies.** That is independent confirmation that your architecture (real browser does the
login, app consumes the cookie) is the correct one.

### 3.4 Arkose / FunCaptcha — the real risk, and it's genuinely unquantified

**When:** an `ArkoseLogin` subtask is injected "when Twitter suspects activity is from a bot", and can also
displace the expected next subtask (e.g. `LoginEnterAlternateIdentifierSubtask` appears instead).
`[VERIFIED — twscrape/flow-reconstruction docs]` Manual challenge URL shape:
`https://mobile.x.com/i/ocf_arkose_challenge?publicKey=arkose_challenge_login_web_prod&data=`
`[VERIFIED — blog.nest.moe; note `mobile.x.com` and `mobile.twitter.com` are both in X's CSP `frame-src`]`

A first-ever login from a new device, new "browser" fingerprint, and a residential-but-new IP is close to
the canonical trigger profile. **Frequency: `[UNKNOWN]` — not documented anywhere, and I will not invent a
number.**

**D-pad operability — cautiously positive, not proven:**
- The dominant variant asks the user to "rotate a 3D object (an animal, a hand, a die) using **arrow
  buttons** until it matches a target orientation". Arrow *buttons* are focusable DOM elements, not a
  drag surface. `[LIKELY operable — multiple solver-industry write-ups describe on-screen arrow controls]`
- Arkose Labs is **certified WCAG 2.2 Level AA** for its enforcement challenges (announced Feb 2024), and
  claims to be the only bot-mitigation vendor with certified challenges. WCAG 2.2 AA includes SC 2.1.1
  Keyboard. `[VERIFIED — Arkose press release / TechBrew coverage]`
- Combined with spatial navigation being on by default on TV (§4.2), a D-pad should reach and activate
  those buttons.
- **But:** other variants are tile-grid selection and some are drag-based; the challenge is in a
  cross-origin iframe so you cannot inject helpers into it; and "WCAG-certified" is a vendor claim about
  *some* challenge set, not a guarantee that the variant X serves you is keyboard-only.
  `[UNKNOWN — needs live test]`

**Mitigations, ranked:**
1. Keep an escape hatch: a "pair a Bluetooth keyboard / use the Android TV Remote app" hint on the login
   screen, since a pointer-remote or a paired phone-as-touchpad turns drag challenges from impossible into
   annoying.
2. Reduce the chance of triggering it at all: use the WebView (real Chromium fingerprint), don't spoof an
   implausible UA, don't hammer the login endpoint, do the login from the home network.
3. **Fallback auth path worth building anyway:** a "paste cookie" screen. The user logs in on their
   desktop browser, copies `auth_token` and `ct0` from DevTools, and types/QR-transfers them. Ugly, but it
   is what gallery-dl and yt-dlp expect, it is a 40-character-twice one-time cost, and it removes Arkose,
   passkeys, and IME problems from the critical path in one move. Given this is a solo dev's own account,
   this may honestly be the *better* primary flow with WebView as the convenience path.

---

## 4. Android TV specifics

### 4.1 WebView is guaranteed present

CDD, Television requirements:
> **[3.4.1/T-0-1] Television device implementations MUST provide a complete implementation of the
> `android.webkit.Webview` API.**

`[VERIFIED — Android 14 and Android 16 CDD, section 3.4.1; found via exact-phrase search. Caveat: the CDD
pages truncate on fetch, so I confirmed the sentence but not the surrounding T-SR clauses.]`

Provider packaging: `com.google.android.webview` is "default, preinstalled" on GMS devices
(`com.android.webview` on AOSP), and "Vendors shipping OS images which include GMS and the Play Store must
use Google's provided WebView configuration...to ensure Google can deliver WebView updates to users."
`[VERIFIED — chromium android_webview/docs/webview-providers.md]`

So on any Play-certified Android TV / Google TV box, WebView exists and is auto-updated. Android TV ships
no standalone browser, so WebView is the *only* web engine — no "open in Chrome" fallback exists. Still
guard with `PackageManager.hasSystemFeature("android.software.webview")` and fail gracefully.

### 4.2 ★ Spatial navigation is ON by default on Android TV — the best finding in this doc

```java
private boolean mSpatialNavigationEnabled; // Default depends on device features.
...
// Best-guess a sensible initial value based on the features supported on the device.
mSpatialNavigationEnabled =
        !mAwContents
                .getProvidedContext()
                .getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
```
`[VERIFIED — chromium/main AwSettings.java:181, 404–409]`

And Android TV devices do not have a touchscreen — `android.hardware.touchscreen` is first in the official
"hardware features not available on TV devices" table, and TV apps must declare
`<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`.
`[VERIFIED — developer.android.com/training/tv/start/hardware]`

**⇒ `hasSystemFeature(FEATURE_TOUCHSCREEN) == false` on a TV ⇒ Chromium turns spatial navigation on
automatically.** D-pad up/down/left/right move focus between interactive elements inside the page. No JS
injection, no polyfill, no key-event synthesis. This is the difference between "TV WebView login is a
nightmare" and "TV WebView login is fine".

Supporting history: CL [23619024](https://codereview.chromium.org/23619024), landed as r221946 —
"Turn on the `--enable-spatial-navigation` flag, and also bubble up unhandled DPAD events to the
neighboring views in the view tree", implemented via `WebContentsDelegate::TakeFocus()`.
`[VERIFIED — codereview.chromium.org/23619024]`

Consequences and caveats:
- **Focus escapes the WebView at the page edges** into your surrounding Compose/View hierarchy (that's the
  `TakeFocus()` bubbling). Good for "back to app chrome", but it means a full-screen login WebView should
  be the only focusable thing on screen or the user will fall out of it. Handle with a focus group /
  `FocusRequester` around the `AndroidView`. `[LIKELY]`
- **You cannot force it on.** `setSpatialNavigationEnabled(boolean)` is annotated `@VisibleForTesting` on
  `AwSettings` and is **not** exposed via public `android.webkit.WebSettings` or `WebSettingsCompat`.
  `[VERIFIED — AwSettings.java:746–752]` Chromium's only other route is the
  `--enable-spatial-navigation` command line, which WebView reads from
  `/data/local/tmp/webview-command-line` on debuggable builds only — not usable in production.
- **The one device class that breaks this:** TVs/boxes that *do* report a touchscreen or pointer
  (the docs note "Some TV devices support pointer remotes and touchscreen displays", with
  `android.software.leanback.supports_touch` metadata). There, spatial nav defaults **off**.
  Detect it at runtime — `packageManager.hasSystemFeature(FEATURE_TOUCHSCREEN)` — and if true, fall back
  to injecting a spatial-navigation polyfill or synthesising `Tab`/`Shift+Tab` from D-pad
  left/right. `[VERIFIED for the default logic; the fallback itself is UNKNOWN/untested]`
- The WebView must be focusable and actually hold focus for D-pad keys to reach the page
  (`isFocusable`, `isFocusableInTouchMode`, `requestFocus()`). `[LIKELY]`

### 4.3 Text entry / IME

`[UNKNOWN — needs live test]` whether the leanback IME reliably pops for a web `<input>` on a given TV.
Evidence of long-standing friction rather than a clean answer: Google issuetracker
[36915710](https://issuetracker.google.com/issues/36915710) "WebView with input/textarea never get virtual
keyboard focus" (couldn't read the thread — sign-in wall), plus a large body of "keyboard covers the input
in WebView" reports whose fixes are all layout-level (`fitsSystemWindows`, resizing the WebView rather than
moving it).

Known-bad UX baseline: "typing passwords letter-by-letter using a D-pad is one of life's most frustrating
experiences" — and X passwords are long.

### 4.4 ★ The fix for §4.3: Autofill works inside WebView

The Android **Autofill framework is available on API 26+ and works for input fields/forms inside a
WebView.** `[VERIFIED — developer.android.com/identity/autofill + corroborating write-ups]`

So Google Password Manager (or Bitwarden/1Password, which register as autofill services) can fill the
x.com username+password with a D-pad selection instead of character-by-character entry. Caveats from the
same sources: autofill suggestions won't show if JS is disabled or the WebView restricts form data —
both already fixed by §2.3. Check `AutofillManager.isEnabled()` before relying on it.

Layered mitigations, in the order to offer them:
1. Autofill (above) — best; one D-pad click.
2. **Android TV Remote Control app** — pop an input box on the phone and type there.
3. Bluetooth keyboard.
4. The paste-cookie fallback from §3.4.

Set `android:autofillHints` where you control the view; you don't control X's form, so this is on the
autofill service's heuristics. `[LIKELY]`

---

## 5. The persistent-WebView JS-`fetch` bridge vs plain OkHttp

### 5.1 The enabling fact: a never-attached WebView is never throttled

Chromium WebView team, verbatim:
> "If the WebView has **never** been attached to a view hierarchy since it was created, we treat it as
> visible."

`[VERIFIED — groups.google.com/a/chromium.org/g/android-webview-dev/c/sLY0DfQHS7o]`

Same thread: an *attached* WebView in a backgrounded app is treated as "not visible" (because the window
isn't) and gets background-Chrome-tab restrictions; WebView doesn't watch app foreground/background
directly. And `onPause()` does **not** pause JS — only `pauseTimers()` does, and it's global to all
WebViews in the process. `[VERIFIED — same thread + the onPause/pauseTimers write-ups]`

⇒ **Recipe: create the WebView, never `addView()` it, never call `onPause()`/`pauseTimers()`.** JS,
`setTimeout`, and `fetch` keep running with the app backgrounded. This makes the architecture real, not
hypothetical.

### 5.2 What it buys you

The WebView is on the `https://x.com` origin, so:
- `fetch('/i/api/graphql/...', {credentials:'same-origin'})` is **same-origin** — no CORS, no preflight,
  and the browser attaches `auth_token`/`ct0` automatically. CSP `connect-src` explicitly allows `'self'`,
  `https://x.com`, `https://api.x.com`. `[VERIFIED — live CSP header]`
- `document.cookie` yields `ct0` (not HttpOnly, §1.3) so JS can set `x-csrf-token` itself.
- You inherit the *real* Chromium TLS/HTTP2 fingerprint, which is what Cloudflare Bot Management (§2.6)
  and X's own signals see.
- `evaluateJavascript` runs in the **main world**, not an isolated world — verified path:
  `WebContentsAndroid::EvaluateJavaScript` → `web_contents_->GetPrimaryMainFrame()->ExecuteJavaScript(...)`
  (the non-`ForTests` variant). `[VERIFIED — chromium content/browser/web_contents/web_contents_android.cc:600–612]`
  Main world ⇒ your code can *see and call X's own bundle*, which is the only clean way to get
  client-side request signing (`x-client-transaction-id`, whose header slot gallery-dl leaves as
  `"x-client-transaction-id": None`, `gdl_twitter.py:1356`) without reimplementing it.

### 5.3 What it costs you

- **Out-of-process renderer, always.** WebView uses an out-of-process renderer on all devices from
  Android 11 / API 30 (and on 64-bit devices from Oreo/API 26). `[VERIFIED — developer.android.com
  managing-webview + chromium multiprocess discussion]` That renderer is a separate process and an
  independent low-memory-killer target.
- **Android TV RAM floors are brutal:** Android TV 14 certification allows **1 GB** (1080p) and 1.5 GB
  (4K); Google TV requires 2 GB. `[VERIFIED — widely reported Nov 2024 requirement change, 9to5Google /
  Android Police / AFTVnews]` A full x.com SPA renderer on a 1 GB box will get killed.
- **If the renderer dies and you don't handle it, your app crashes.** `WebViewClient.onRenderProcessGone`:
  return `true` to survive (you must `destroy()` and recreate the WebView), return `false` and "the app
  itself will crash". `[VERIFIED — developer.android.com managing-webview]` Pair with
  `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, /*waivedWhenNotVisible=*/false)` — note the
  second arg must be `false`, or an invisible WebView's renderer drops to `RENDERER_PRIORITY_WAIVED`
  where "the system likely kills renderer in out-of-memory situations". `[VERIFIED — same doc]`
  (Being never-attached ⇒ "visible" per §5.1 should already avoid the waive, but set it explicitly.)
- Each additional WebView **Profile** gets its own renderer process. Use one.

### 5.4 The memory fix: get the origin without the SPA

You need the **x.com origin**, not x.com's application. Two ways to have that cheaply:

**(a) Synthetic same-origin document via `shouldInterceptRequest`.** Navigate to a sentinel like
`https://x.com/__xtv_bridge` and return a ~200-byte `WebResourceResponse` (`text/html`) from
`shouldInterceptRequest`. The document's origin is `https://x.com`, so `.x.com` cookies attach to its
same-origin `fetch()`es. `[LIKELY — follows from Chromium treating the committed URL's origin as the
document origin; Chromium's own CORS doc only calls out *custom schemes* as becoming "opaque origins",
which does not apply to `https://`]` `[UNKNOWN for the exact cookie/CORS behaviour — needs live test]`

Bonus properties: your synthetic response carries **no CSP**, so no nonce fight and, importantly, **no
`report-uri` telemetry to X** (§2.6). Cost: no X bundle ⇒ **no request signing.** Chromium's docs prefer
`WebViewAssetLoader` over `shouldInterceptRequest`, but that's for serving local assets on virtual domains
you control, not for borrowing a third-party origin. `[VERIFIED — android_webview/docs/cors-and-webview-api.md]`

**(b) A tiny real x.com document**, e.g. `https://x.com/robots.txt`. Real origin, real CSP, tiny.
Whether `evaluateJavascript` behaves in a `text/plain` viewer document is `[UNKNOWN]`.

**The crux, stated honestly:** *blank synthetic origin = cookies + CORS-free fetch but no access to X's
signing code. Real SPA = signing but a heavyweight renderer that a 1 GB TV will kill.* Which you need
depends entirely on whether X's GraphQL read endpoints currently require `x-client-transaction-id` — that's
the other agent's topic and it decides this one. If signing is not required, do not run a persistent
WebView at all: OkHttp + harvested cookies is strictly better (§5.6).

### 5.5 Getting large JSON back — do not use `evaluateJavascript` return values

Use `WebViewCompat.addWebMessageListener(webView, "xtvBridge", setOf("https://x.com"), listener)`:
- Requires WebView 82+ / androidx.webkit 1.3.0+, gate on `WebViewFeature.WEB_MESSAGE_LISTENER`.
- **Origin-allowlisted**: "the WebView guarantees that it only exposes the injected JavaScript objects to
  web pages loaded from that exact origin". `allowedOriginRules` matches scheme+host+port, supports only
  subdomain wildcards (`https://*.x.com`); add `https://x.com` and `https://*.x.com` separately if needed.
- Supports **`byte[]` / ArrayBuffer** (`WebMessageCompat.TYPE_ARRAY_BUFFER`, feature
  `WEB_MESSAGE_ARRAY_BUFFER`) → post raw UTF-8 JSON bytes, no base64 (~33% saved), no string-size games.
- Bidirectional via `JavaScriptReplyProxy`.
- **Callback runs on the UI thread** → hand off to a coroutine immediately; parsing a big timeline page on
  the main thread on a 1 GB TV is an ANR.
- Install it **before** `loadUrl()`, and/or pair with `addDocumentStartJavaScript`
  (`WebViewFeature.DOCUMENT_START_SCRIPT`) so the bridge exists before page scripts run.

`[VERIFIED — developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge]`

**Correct the folklore:** the "10240 byte" WebView limit that circulates is specifically about
`prompt()`-based bridges, introduced in WebView 69.0.3497.91, failure mode = truncated string → invalid
JSON → `JSONException` (Cordova InAppBrowser used `prompt()`; a Chrome dev replied "I don't think you
should rely on `prompt()` as a means of passing data from JS to Java"). It is **not** a documented
`evaluateJavascript` limit. `[VERIFIED — apache/cordova-plugin-inappbrowser#303]` Don't design around
that number, and don't cite it as an `evaluateJavascript` cap — but also don't route megabytes through
`evaluateJavascript` callbacks; `addWebMessageListener` + ArrayBuffer is the supported path.

Avoid `addJavascriptInterface`: strings only, **invoked on a background thread**, and "available to every
frame within the WebView, including iframes. It lacks origin-based access control" — with an explicit
"We don't recommend using this method for modern applications". `[VERIFIED — same doc]` On a page that
loads third-party iframes (Arkose, ads, Google/Apple SSO), that's a real exposure, not a theoretical one.

### 5.6 Honest comparison

| | Persistent WebView + JS `fetch` bridge | OkHttp + harvested cookies |
|---|---|---|
| Request signing (`x-client-transaction-id`) | Free, if you load X's real bundle | Must be reimplemented in Kotlin |
| `js_inst`/`ui_metrics` style challenges | Handled natively | Not handled |
| TLS/HTTP2 fingerprint vs Cloudflare | Genuine Chromium | Obviously not a browser |
| Memory on a 1 GB TV | Extra renderer process; SPA is heavy; LMK target | ~nothing |
| Failure modes | `onRenderProcessGone`, silent JS errors, X ships a bundle change and your injected code breaks | HTTP status codes |
| Debuggability | Remote-debug a headless WebView; painful | Interceptor logs, trivial |
| Cancellation / backpressure / retries | Hand-rolled over a message channel | OkHttp + coroutines, free |
| Streaming media to ExoPlayer | Can't — video must go through a normal HTTP stack anyway | Native |
| Rate-limit discipline (main-account safety) | Harder to centrally throttle | One `Interceptor`, easy |
| Code you own | JS + Kotlin + a protocol between them | Kotlin |

**Recommendation.** Default to **OkHttp for all data fetching**, using cookies harvested once via the login
WebView; you must have an OkHttp path regardless, because ExoPlayer needs plain HTTP for the video/image
CDNs (`video.twimg.com`, `pbs.twimg.com`) and those are unauthenticated media URLs. Add the persistent
WebView **only** if the signing research says read endpoints reject unsigned requests — and if so, prefer
the narrowest possible use: keep the WebView as a *signing oracle* (ask JS to compute the header for a
given method+path, return a short string) and still issue the actual HTTP from OkHttp. That preserves
OkHttp's throttling, caching, cancellation, and logging while borrowing only the one thing you can't
reimplement, and it lets you use the tiny synthetic-origin page (§5.4a) *if* the signer can be extracted —
`[UNKNOWN whether the signing code can be loaded without the full SPA]`.

For a conservative main account, one more reason to prefer OkHttp: a single `Interceptor` is where you put
the request budget. A WebView running X's own bundle will also fire X's *own* telemetry and background
polling that you neither see nor control.

---

## 6. Secure storage of the cookie in 2026

### 6.1 Jetpack Security is deprecated — VERIFIED

`androidx.security:security-crypto` release notes, **1.1.0-alpha07 (2025-04-09)**:
> **API Changes** — "Deprecated all APIs in favour of existing platform APIs and direct use of Android
> Keystore."

Stable **1.1.0** shipped 2025-07-30 with the deprecation in force. `EncryptedSharedPreferences`,
`EncryptedFile`, and `MasterKeys` are all deprecated. `[VERIFIED — developer.android.com/jetpack/androidx/releases/security]`

Google's replacement guidance is exactly that sentence: platform APIs + AndroidKeyStore directly. There is
no drop-in successor. (An unofficial community fork exists — `ed-george/encrypted-shared-preferences` — not
Google-endorsed; don't take a crypto dependency on a fork for this.)

### 6.2 What XTV should actually do

**First, consider storing nothing.** The WebView's own cookie jar already lives in
`/data/data/<pkg>/app_webview/` — app-private, and exactly as protected as anything you'd write yourself.
If you keep the login WebView's profile around, `CookieManager.getCookie("https://x.com/")` *is* your
storage; read it on demand and hold the value in memory only. Fewest copies of a live session token is
the right instinct. Only persist a separate copy if you need the session without a WebView present.

If you do persist it:

1. **Key:** generate AES-256-GCM in the AndroidKeyStore.
   ```kotlin
   KeyGenParameterSpec.Builder("xtv_session", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
       .setBlockModes(BLOCK_MODE_GCM)
       .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
       .setKeySize(256)
       .setUserAuthenticationRequired(false)   // see (3)
       .build()
   ```
   Optionally `.setIsStrongBoxBacked(true)` inside try/catch on
   `StrongBoxUnavailableException` — TV SoCs frequently lack StrongBox. `[LIKELY]`
2. **Payload:** encrypt the `auth_token`/`ct0` blob; store `iv || ciphertext` in **Jetpack DataStore**
   (Preferences or Proto) or a plain file in `filesDir`. DataStore is the current recommendation for
   key-value storage and does its I/O off the main thread via coroutines/Flow — which also fixes the
   StrictMode-on-main-thread complaint that dogged `EncryptedSharedPreferences`.
3. **Do NOT set `setUserAuthenticationRequired(true)` on a TV.** There is no biometric and no guaranteed
   secure lockscreen; you'd lock yourself out of your own key. `[LIKELY — TV hardware doc lists no
   fingerprint/biometric among available features]`
4. **Manifest:** `android:allowBackup="false"` (or a `dataExtractionRules` exclusion) so the session token
   never leaves the device via backup/transfer. `[LIKELY — standard hardening]`
5. **Be honest about the threat model.** Keystore protects against offline extraction of the app's data
   directory. It does **not** protect against a root shell or an ADB-enabled TV box with the app running —
   and TV boxes are commonly rooted and have ADB-over-network enabled. This is a single-user personal app;
   Keystore + `allowBackup=false` is proportionate. Don't over-engineer past that.
6. Nice touch: keep a "sign out" that calls `CookieManager.removeAllCookies()` + `flush()` and deletes the
   Keystore alias, so revocation is local and complete.

---

## 7. Open unknowns, and the exact experiment for each

| # | Unknown | Experiment |
|---|---|---|
| 1 | Does the leanback IME reliably appear for an `<input>` in a WebView on real TV hardware? | Sideload a 30-line app: full-screen WebView, `javaScriptEnabled=true`, load `https://x.com/i/flow/login`, `requestFocus()`. D-pad to the username field, press CENTER. Test on a Chromecast/Google TV **and** a cheap Android-TV box — behaviour differs by OEM IME. |
| 2 | Does Autofill (§4.4) actually offer the x.com credentials inside the WebView on TV? | Same harness; save the x.com password in Google Password Manager on a phone with the same account, then check `AutofillManager.isEnabled()` and whether a fill UI appears on field focus. |
| 3 | Is spatial navigation really on? | In the harness, `adb shell dumpsys package` → confirm `android.hardware.touchscreen` absent; then D-pad through the login page and see whether focus rings move between elements (vs the page just scrolling). If it only scrolls, the default guess failed and you need the polyfill fallback. |
| 4 | Does an Arkose challenge appear, and is it D-pad-solvable? | Only observable by attempting a real login. Do it **once**, on the home network, and screenshot whatever appears. If it's the arrow-button rotation variant, D-pad it. Have a Bluetooth keyboard/pointer remote in reach before you start. |
| 5 | Does `shouldInterceptRequest`-served `https://x.com/__xtv_bridge` get the real x.com origin, with `.x.com` cookies attached to its same-origin `fetch()`? | In the harness, intercept that URL, return minimal HTML, then `evaluateJavascript("fetch('/i/api/...').then(r=>r.status)")` while logged in, and independently log `document.cookie` and `window.origin`. Compare against navigating to real `https://x.com/`. |
| 6 | Does X's CSP interfere with `addDocumentStartJavaScript` / `addWebMessageListener` injection on the *real* x.com document? | Load real `https://x.com/`, install both, and watch `onConsoleMessage` for CSP violation reports — and watch for hits to `https://x.com/i/csp_report`. If CSP fights you, that's another vote for the synthetic origin (§5.4a). |
| 7 | Is `auth_token` actually `HttpOnly`? | Post-login, call `CookieManagerCompat.getCookieInfo(cm, "https://x.com/")` and log the full attribute strings. Also confirms `Max-Age`, i.e. how long the harvested session lasts. |
| 8 | Frequency of Arkose on new-device login; whether `WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER` permits third-party RP IDs; exact Android release for the reduced default UA (16 vs 17). | Not resolvable from static sources. (2) needs reading the androidx.webkit `WebSettingsCompat` javadoc directly — developer.android.com's reference index truncated on every fetch attempt; try the `frameworks/support` git tree instead. |

---

## 8. Sources

Primary (source code / official docs):
- Chromium `android_webview/browser/cookie_manager.cc`, `net/cookies/cookie_options.cc`,
  `android_webview/java/.../AwSettings.java`, `.../AwCookieManager.java`,
  `content/browser/web_contents/web_contents_android.cc`,
  `content/public/browser/render_frame_host.h`,
  [`android_webview/docs/webview-providers.md`](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/webview-providers.md),
  [`android_webview/docs/cors-and-webview-api.md`](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/cors-and-webview-api.md)
- AOSP `frameworks/base/core/java/android/webkit/CookieManager.java`; Android 14/16 CDD §3.4.1
- [JS bridge guidance](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge),
  [Manage WebView objects](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview),
  [WebView + Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-webview),
  [TV hardware features](https://developer.android.com/training/tv/start/hardware),
  [Autofill framework](https://developer.android.com/identity/autofill),
  [security-crypto release notes](https://developer.android.com/jetpack/androidx/releases/security),
  [UA reduction on WebView](https://android-developers.googleblog.com/2024/12/user-agent-reduction-on-android-webview.html)
- [android-webview-dev: WebView lifecycle](https://groups.google.com/a/chromium.org/g/android-webview-dev/c/sLY0DfQHS7o),
  [CL 23619024 — enable spatial navigation / DPAD](https://codereview.chromium.org/23619024)
- Maintained scrapers: [gallery-dl `twitter.py`](https://raw.githubusercontent.com/mikf/gallery-dl/master/gallery_dl/extractor/twitter.py),
  [yt-dlp `twitter.py`](https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/yt_dlp/extractor/twitter.py)
- Live unauthenticated probes of `https://x.com/i/flow/login`, `https://twitter.com/home`,
  `https://twitter.com/` (2026-07-26)

Secondary: [react-native-webview#3473](https://github.com/react-native-webview/react-native-webview/issues/3473),
[cordova-plugin-inappbrowser#303](https://github.com/apache/cordova-plugin-inappbrowser/issues/303),
[react-native-cookies#76](https://github.com/react-native-cookies/cookies/issues/76),
[Apple forums 653357](https://developer.apple.com/forums/thread/653357),
[blog.nest.moe login-flow reconstruction](https://blog.nest.moe/posts/how-to-login-to-twitter),
[twscrape](https://github.com/vladkens/twscrape), Arkose WCAG 2.2 AA announcement (Feb 2024) via
[TechBrew](https://www.techbrew.com/stories/2024/03/26/arkose-labs-creative-captcha-accessibility),
[The Register on X passkey re-enrolment](https://www.theregister.com/on-prem/2025/10/27/x_assures_passkey_reset/),
[9to5Google on Android TV RAM floors](https://9to5google.com/2024/11/07/google-android-tv-ram-requirement/)
