# XTV Research 01 — Authenticating & signing X GraphQL requests (as of 2026-07-26)

> **Status: ARCHIVED / REJECTED PATH.** This is a dated investigation of reverse-engineered web
> GraphQL authentication. XTV v1.1.0 uses the official API and off-device OAuth provisioning; it
> does not implement transaction-ID signing, cookie harvesting, or a login WebView.

Evidence tags: **[V]** = verified in a primary source (code/issue quoted), **[L]** = likely (named reasoning),
**[U]** = unknown, needs a live test. Nothing below is invented; every constant is quoted from source.

---

## 0. BOTTOM LINE (architecture verdict)

**A plain OkHttp client CAN work — but only if you port the `x-client-transaction-id` (TID) algorithm to
Kotlin.** You do **not** need to proxy through a WebView for signing.

Reasoning: `gallery-dl` signs authenticated requests with a **pure-Python, ~250-line, no-JS-engine**
implementation and works in 2026 over plain `requests`/`urllib3` (HTTP/1.1, no TLS impersonation).
`twscrape` does the same in pure Python. Two independent maintained implementations agree byte-for-byte on
the algorithm. **[V]** — `gallery_dl/extractor/utils/twitter_transaction_id.py` (Copyright "2025-2026"),
`twscrape/xclid.py`.

But note the real cost: **TID is not a pure function of the request.** Initialization must fetch and parse
X's live web bundle (HTML + one JS chunk), and X has broken that parse **at least four times between
2025-04 and 2026-07**. That parser is your permanent maintenance liability, not the crypto.

The WebView is still needed for **login** (harvest `auth_token` + `ct0`) and is a useful **fallback**
(see §6.3), but it is the wrong primary path.

---

## 1. What an authenticated x.com web GraphQL request carries

### 1.1 URL shape **[V]**

```
GET https://x.com/i/api/graphql/<queryId>/<OperationName>?variables=<urlencoded-json>&features=<urlencoded-json>
```
- Host/base: `self.root = "https://x.com/i/api"` — gallery-dl `twitter.py:1326` **[V]**
- yt-dlp: `_GRAPHQL_API_BASE = 'https://x.com/i/api/graphql/'`, `_API_BASE = 'https://api.x.com/1.1/'` **[V]**
- Legacy REST v1.1 lives on `api.x.com`; GraphQL lives on `x.com/i/api`. Both hosts are used. **[V]**

### 1.2 The bearer token — still hardcoded, still the same string

```
AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA
```
Sent as `Authorization: Bearer <above>`.

**[V] — byte-identical in three independently maintained repos**, all current:
- yt-dlp `twitter.py:39` → `_AUTH`
- gallery-dl `twitter.py:1357-1359` → `headers["authorization"]`
- twscrape `account.py:13` → `TOKEN`

What it is: the **public web-app bearer**, not a per-user credential. It identifies the *client app*
("Twitter Web App"), gates you into the API surface, and selects rate-limit buckets. Your *identity* comes
entirely from the `auth_token` cookie. It has not rotated in years. **[L]**

There is a second, older token in yt-dlp `twitter.py:40` (`_LEGACY_AUTH`,
`AAAAAAAAAAAAAAAAAAAAAIK1zgAAAAAA2tUWuhGZ2JceoId5GwYWU5GspY4%3DUq7gzFoCZs1QfwGoVdvSac3IniczZEYXIcDyumCauIXpcAPorE`),
used only for the legacy `api.x.com/1.1/` path when *not* logged in. Irrelevant to XTV. **[V]**

### 1.3 Cookies

| Cookie | Role | Notes |
|---|---|---|
| `auth_token` | **the** session credential | Presence = "logged in". gallery-dl declares `cookies_names = ("auth_token",)` (`twitter.py:28`) **[V]** |
| `ct0` | CSRF token; must be **mirrored** into `x-csrf-token` | twscrape `has_required_cookies()` requires **both** `auth_token` **and** `ct0` (`account.py:17`) **[V]** |
| `gt` | guest token, set alongside `x-guest-token` in guest mode only | gallery-dl `twitter.py:1869` **[V]** |
| `guest_id` | fingerprinting input for XPFF (see §5.5) | **[L]** |

Cookie domain must be `.x.com` (gallery-dl scopes all cookie get/set by `cookies_domain`). **[V]**

### 1.4 Header set

Consolidated from all three scrapers. "Required?" column is my assessment with tags.

| Header | Value | Required? |
|---|---|---|
| `authorization` | `Bearer <§1.2>` | **Yes [V]** — all three send it always |
| `x-csrf-token` | **exact current value of the `ct0` cookie** | **Yes [V]** — mismatch ⇒ HTTP 403 + code 353 (§4.2) |
| `x-client-transaction-id` | computed per request (§2) | **Yes for many ops [V]** — see §2.1 |
| `x-twitter-auth-type` | `OAuth2Session` | **[L] yes when authenticated.** gallery-dl + yt-dlp both set it and use it as the is-authenticated switch **[V]**; twscrape sets it during login and persists it per-account **[V]** |
| `x-twitter-active-user` | `yes` | **[L] send it** — all three send it |
| `x-twitter-client-language` | `en` | **[L] send it** — all three send it |
| `content-type` | `application/json` | **[L]** gallery-dl + twscrape set it even on GETs |
| `Accept` | `*/*` | **[L]** gallery-dl |
| `Referer` | `https://x.com/` | **[L]** gallery-dl `twitter.py:1346` |
| `Sec-Fetch-Dest/Mode/Site` | `empty` / `cors` / `same-origin` | **[L] not enforced** — gallery-dl sends them, yt-dlp sends none and works **[V]** |
| `x-guest-token` | guest token | **Only in guest mode.** Never sent alongside `auth_token` **[V]** |
| `x-xp-forwarded-for` | encrypted fingerprint | **[L] NOT enforced for reads** — see §5.5 |

`User-Agent`: gallery-dl's Twitter extractor pins `browser = "firefox"` at class level (`twitter.py:30`),
which in `extractor/common.py` swaps in the full Firefox 140 header block **and** a Firefox TLS cipher
list. **[V]** Not proof of necessity, but it's the maintainer's chosen default for this site specifically.
UA string used: `Mozilla/5.0 ({platform}; rv:140.0) Gecko/20100101 Firefox/140.0` (`common.py:1229`). **[V]**

**Critical consistency rule:** the UA you send on API calls must match the UA you used to fetch the
bundle for TID init, because the bundle/animation data is build-specific and the logged-out vs logged-in
app differ (§2.4). **[L]**

---

## 2. `x-client-transaction-id` — THE decisive question

### 2.1 Is it enforced? — **YES, for a large and growing subset of operations. Enforcement is per-operation and rolls out gradually.**

This is the strongest evidence I found, in chronological order. All quotes verbatim.

**(a) 2025-04-24 — gallery-dl maintainer `mikf`, issue #7382:**
> "`/with_replies` requires a valid `x-client-transaction-id` header value, and computing those is complicated."

**(b) 2025-04-25 — `aidanharris`, same issue** (scope at that time):
> "Somebody will have to properly reverse-engineer how this header is generated. **Other endpoints still work without it for now. It's only with_replies that requires it.**"

**(c) 2025-04-25 — `yogthot`, same issue:**
> "It seems they finally started removing the old endpoints and **the new endpoints require the x-client-transaction-id header**."

**(d) 2025-04-25 — `GiovanH`, same issue** (non-reusability):
> "`x-client-transaction-id` appears to actually be tied to specific transactions, and isn't reusable."

**(e) 2026-05-10 — twscrape issue #306. This is the cleanest controlled observation in the whole corpus:**
> "Even after patching the `OP_*` constants to the above identifiers, the GraphQL endpoint still returns an
> **HTTP 404 with `content-length: 0`** (**the request is counted — `x-rate-limit-remaining` decreases — so
> authentication is correct**). X now requires that every API call include the `x-client-transaction-id`
> header, generated client-side from the `ondemand.s` JS file. **Without it, the WAF responds with a 404
> instead of a 4[01/403]**."

The parenthetical is the key part: **auth was accepted (the rate limiter counted the request) and the
response was still 404/empty.** That isolates the TID as the cause. Ops listed as affected in #306
included `UserTweets`, `UserTweetsAndReplies`, `SearchTimeline`, `TweetDetail`, `UserByScreenName`,
`UserByRestId`, `Followers`, `Following`, **`User Media`**, `GenericTimelineById`. **[V]**

**(f) 2026-07-23 (three days ago) — twscrape issue #322**, showing enforcement is *still* uneven:
> "`Followers` GraphQL requests: HTTP 404 / Empty response body / **No GraphQL error returned**.
> `Following` and some other endpoints: Continue working normally … likely because they have **more
> tolerant validation or different protection rules**. The issue does not appear to be related to: queryId
> changes, authentication cookies, account suspension, rate limits."

**Conclusion for XTV:** treat TID as **mandatory**. The ops XTV needs (`UserMedia`, `Bookmarks`,
`HomeTimeline`, `UserTweets`) are exactly the timeline family that #306 lists as TID-gated. **[V]/[L]**

### 2.2 Failure signature (and its confound — read this twice)

Missing **or invalid** TID ⇒ **HTTP 404, `content-length: 0`, no JSON error object.** **[V]** (#306, #322)

**Confound:** a **stale `queryId`** produces the *same* 404 + empty body. **[V]** (#306 root cause #1;
gallery-dl #9275). A **stale `features` set** is distinguishable — it returns HTTP 400 with a real message:
> `400 Bad Request (The following features cannot be null: rweb_tipjar_consumption_enabled, communities_web_enable_tweet_community_results_fetch, …)`
— gallery-dl #7382, verbatim **[V]**; twscrape codes this as error `(336)` (`queue_client.py`) **[V]**.

So: **404+empty ⇒ "queryId stale OR TID missing/invalid"**. Your app's diagnostics must not conflate them.

gallery-dl surfaces this pair as, verbatim:
```
[twitter][debug] API error: 'Unspecified'
[twitter][error] 404 Not Found ()
```
**[V]** (#7382). "Unspecified" is gallery-dl's own fallback for `error.get("message") or "Unspecified"`
(`twitter.py:1927`) — i.e. the 404 body, when non-empty, carries an `errors` array whose objects have **no
`message` key**. **[L]**

### 2.3 The algorithm (fully specified, two-source agreement)

Both implementations are byte-identical in behaviour. Sources:
`gallery_dl/extractor/utils/twitter_transaction_id.py` and `twscrape/xclid.py` **[V]**.
Both credit `iSarabjitDhiman/XClientTransaction` (MIT) and the antibot.blog series.

**Init (once per session; cache ~3h — gallery-dl uses `_exp=10_800`) [V]:**

1. GET `https://x.com/` (gallery-dl) or `https://x.com/tesla` (twscrape) **with your session cookies**.
2. `key_bytes = base64_decode(<meta name="twitter-site-verification" content="…">)`
3. Locate the signing JS chunk (§2.4) and regex out the **KEY_BYTE indices**:
   - gallery-dl: `r"\(\w\[(\d\d?)\],\s*16\)"`
   - twscrape: `r"(\(\w{1}\[(\d{1,2})\],\s*16\))+"`
4. Extract animation frames from the homepage: `<svg id="loading-x-anim-*">`, take the **2nd `<path>`'s `d`
   attribute**, drop the first 9 chars, `split("C")`, and parse each segment's integers.
   - twscrape selector: `svg[id^='loading-x-anim'] g:first-child path:nth-child(2)` **[V]**
5. Pick the frame: `frames[key_bytes[5] % 4]` (gallery-dl, hardcoded 4) / `els[vk_bytes[5] % len(els)]`
   (twscrape, general). **Minor divergence — prefer twscrape's form.** **[V]**
6. Pick the row: `frame_row = array[key_bytes[indices[0]] % 16]`
7. `frame_time = round_js( Π(key_bytes[i] % 16 for i in indices[1:]) / 10 ) * 10`
8. `target_time = frame_time / 4096`
9. `animation_key = animate(frame_row, target_time)` — cubic-bezier solve + RGB interpolation + 2D rotation
   matrix, hex-joined, then `.replace(".","").replace("-","")`.

**Per request [V]:**

```
epoch  = 1682924400                      # = 2023-05-01T07:00:00Z  (verified by computation)
ts     = int(unix_now) - epoch
ts_le  = [ts & 0xFF, (ts>>8) & 0xFF, (ts>>16) & 0xFF, (ts>>24) & 0xFF]   # little-endian

payload = f"{METHOD_UPPER}!{path}!{ts}{KEYWORD}{animation_key}"
KEYWORD = "obfiowerehiring"
h       = sha256(payload).digest()[:16]

body = key_bytes + ts_le + h + [3]       # trailing constant 3
num  = random 0..255
out  = base64( bytes([num] + [b ^ num for b in body]) ).rstrip("=")
```

Notes:
- `path` is the **request path only — NO query string** — and **includes the `/i/api` prefix**.
  gallery-dl: `path = url[url.find("/", 8):]` → `/i/api/graphql/<qid>/UserMedia` **[V]**.
  twscrape: `urlparse(url).path` **[V]**. Independently confirmed by antibot.blog Part 1:
  "the first argument is the path, **specifically without anything after `?`**, aka any argument. Then the
  second argument is the request method, fully uppercase." **[V]**
  Corroborated by gallery-dl #7382 comment (`AlttiRi`) correcting an early patch that omitted `/i/api`. **[V]**
- Output length depends on `len(key_bytes)`: 16 B key ⇒ 51 chars, 32 B ⇒ 72, 48 B ⇒ 94. Neither
  implementation asserts a length. Actual length is **[U]** — measure it once, then use it as a sanity check.
- No JS engine, no WASM, no network call per request. SHA-256 + float math only ⇒ trivially portable to
  Kotlin (`java.security.MessageDigest`, `kotlin.math`). **[V]**
- **Generate a fresh TID for every request.** Both impls do; `GiovanH` reports IDs are not reusable. **[V]**

### 2.4 The fragile part: locating the signing JS chunk — TWO SCHEMES ARE LIVE (A/B rollout)

**Scheme A — legacy webpack** (still what gallery-dl master supports, as of its last touch 2026-03-18):
- Homepage HTML embeds a chunk map: `"ondemand.s":"b90fb2ca"`
- URL: `https://abs.twimg.com/responsive-web/client-web/ondemand.s.{hash}a.js` (note the literal `a` suffix)
- gallery-dl extraction: `homepage.find('"ondemand.s"')` → `text.rextr(...)` → `text.extract(...)` **[V]**

**Scheme B — new `x-web` / Vite build** (twscrape supports both):
- Script URLs are linked directly in the HTML under `https://abs.twimg.com/x-web/…/*.js`
  (twscrape `ASSET_URL_RE = r"https://[\w.-]+/x-web/[\w./-]+\.js"`) **[V]**
- The indices file is **`sign.o-*.js`**, **not linked** — it is `import()`-ed from another chunk, so you must
  fan out over the chunk list and grep each body for it.
  twscrape `INDICES_FILE_RE = r"(?:\.{0,2}/)?[\w./-]*?\b(?:ondemand\.s|sign\.o)[\w.-]*\.js"` **[V]**
- Confirmed working 2026-06-12 (twscrape #312): "`get_scripts_list()` now correctly finds **171**
  `x-web/*.js` chunks, `_find_indices_url()` resolves **`sign.o-*.js` referenced from `sentry-filter-*.js`**"
  **[V]**

**It is a staged rollout, not a migration.** twscrape #312, 2026-06-13, verbatim:
> "**not everyone is affected (yet)**. As of 2026-06-13, twscrape 0.18.1 still works fine for me — X is
> serving the **old** asset scheme to my accounts (`abs.twimg.com/responsive-web/client-web/{name}.{hash}a.js`,
> with the webpack chunk maps inline in the homepage HTML) … I don't see the `/x-web/x-web/assets/*.js` path
> in the HTML my accounts get served at all. So this looks like a **staged / A-B rollout** on X's side that
> hasn't reached every account/region yet."

⇒ **XTV must implement both schemes and pick at runtime.** **[V]**

Breakage timeline (each one is an app-breaking event you must be able to hotfix):
| Date | What broke | Source |
|---|---|---|
| 2025-04-17 | TID first enforced (`with_replies`) | gallery-dl #7382 **[V]** |
| 2026-03-18 | `ondemand.s.…a.js` key extraction | gallery-dl #9260, fix `b697dc269` **[V]** |
| 2026-04-24 → 2026-05-20 | JS bundle parse | twscrape #302/#303 ("PR updated because Twitter changed their code again") **[V]** |
| 2026-06-11 | asset path → `/x-web/x-web/assets/*.js` | twscrape #312, fix `81fc5c5`/#313 **[V]** |
| 2026-06-22 | webpack chunk map format: `"ondemand.s":"b90fb2ca"` → `"ondemand.s",60041:"i18n/emoji-gu"` (hash gone) | gallery-dl #9602 **[V]** |
| 2026-07-21 | asset fetch needed account cookies | twscrape #320/#321 **[V]** |
| 2026-07-23 | TIDs rejected on `Followers` after bundle update | twscrape #322 **[V]** |

### 2.5 ⚠️ You MUST fetch the bundle with the logged-in session

twscrape `xclid.py`, verbatim comment:
> "X serves a different/legacy web build to authenticated vs anonymous sessions. **Only authenticated
> sessions reliably contain the indices this parser depends on.**" **[V]**

It hard-fails on the logged-out app:
`LOGGED_OUT_ENTRY_RE = r"(?:^|/)entry-client-logged-out(?:[-.][^/?#]+)?\.js(?:[?#].*)?$"` →
`raise XClIdAccountError("Logged-out X web app")` **[V]**

Fixed 2026-07-21 in twscrape #321 ("use account cookies for XClId asset requests"), which resolved
`"Couldn't get XClientTxId indices script"`. **[V]**

**This doubles as your best session-liveness probe** (§4.3). And it explains gallery-dl #9602/#9630, where
the reported `ondemand.s.a.js` 404 was fixed not by code but by **"resetting my cookies"** / **"Grabbing new
cookies is what fixed it for me as well"** — a dead session ⇒ logged-out HTML ⇒ no chunk hash ⇒ 404 on a
malformed asset URL. **[V]** (Note: the `-o "transaction-id=false"` workaround claimed in #9602 is **false**;
maintainer reply, verbatim: *"No, it doesn't. There is no `transaction-id` option."*)

### 2.6 Working open-source implementations

| Impl | Lang | Handles Scheme B? | Notes |
|---|---|---|---|
| `mikf/gallery-dl` → `gallery_dl/extractor/utils/twitter_transaction_id.py` | Python | **No** (legacy only) | Cleanest, most readable; **best port target**. ~250 lines, stdlib only. **[V]** |
| `vladkens/twscrape` → `twscrape/xclid.py` | Python | **Yes** (both) | Most current asset-discovery logic. **Port §2.4 from here.** **[V]** |
| `iSarabjitDhiman/XClientTransaction` | Python | **[U]** | Upstream origin; both above credit it. **[V]** |
| `Lqm1/x-client-transaction-id` / `@lami/x-client-transaction-id` (JSR) | TS/JS | **[U]** | Would matter only for the WebView fallback |
| `langkor/x-client-transaction` | Rust | **[U]** | |
| `fa0311/twitter-tid-deobf`(-fork), `yeyuchen198/twitter-tid-generator` | JS | n/a | Deobfuscated original X code — the ground truth for algorithm disputes |
| **Kotlin/JVM** | — | — | **None found.** You must port. **[V]** (searched; only Py/JS/TS/Rust exist) |

Reference docs: `antibot.blog` posts `1741552025433`, `1741552092462`, `1741552163416` (cited by both
implementations). The live site 502'd during research; mirror:
`fa0311.github.io/antibot_blog_archives/web/twitter-header-part-1.html`. **[V]**

Deobfuscated call site in X's own `main.*.js` (gallery-dl #7382, `GiovanH`, verbatim) — note the generator
is built **once per page** then invoked per request with `(path, method)`:
```js
async function Gd(e, d) {
    zd = zd || new Promise((e => {
        a.e("ondemand.s").then(a.bind(a, 227900)).then((d => e(d.default())))
    }));
    const o = await zd;
    return await o(e, d)
}
```
**[V]**

---

## 3. Guest token / `activate.json` — dead for XTV's purposes

- Flow: `POST https://api.x.com/1.1/guest/activate.json` (empty body) → `{"guest_token": "..."}`; send as
  `x-guest-token` and also set cookie `gt`. **[V]** (gallery-dl `twitter.py:1856-1869`; yt-dlp
  `twitter.py:104-111`)
- **Mutually exclusive with authentication.** All three scrapers branch: `if auth_token → x-twitter-auth-type
  + TID`, `else → guest token`. Never both. **[V]** (gallery-dl `_call`, `twitter.py:1897-1901`)
- **It is also decaying on its own.** gallery-dl #9505 (2026-05-06), verbatim log:
  `"POST /1.1/guest/activate.json HTTP/1.1" 403 100` → `KeyError: 'guest_token'`. **[V]**
- ⇒ **Irrelevant to XTV.** Do not implement it. It cannot see Bookmarks or a personalized following feed
  anyway. One caveat worth knowing: guest mode is currently the path that *doesn't* need a TID, which is why
  gallery-dl users saw "works without cookies, fails with cookies" (#9267). **[V]**

---

## 4. `ct0` rotation, cookie-jar requirements, session-death detection

### 4.1 Yes, `ct0` rotates mid-session — and you must follow it

gallery-dl re-reads it from **every** response's `Set-Cookie` and updates the header, verbatim
(`twitter.py:1907-1909`, comment references issue #1170):
```python
# update 'x-csrf-token' header (#1170)
if csrf_token := response.cookies.get("ct0"):
    self.headers["x-csrf-token"] = csrf_token
```
**[V]** It does it a *second* time right after TID init (`twitter.py:1877-1881`, referencing #7467) —
because fetching `https://x.com/` for the bundle **itself rotates `ct0`**:
```python
# update 'x-csrf-token' header (#7467)
csrf_token = self.extractor.cookies.get("ct0", domain=self.extractor.cookies_domain)
if csrf_token: self.headers["x-csrf-token"] = csrf_token
```
**[V]** ← This is a real, documented footgun: gallery-dl #7467 was exactly a 403
`"This request requires a matching csrf cookie and header."` caused by the bundle fetch rotating `ct0`
while the header still held the old value.

**Cookie-jar contract for XTV:**
1. One shared, persistent, `.x.com`-scoped jar across **all** traffic — API calls **and** the
   `x.com` / `abs.twimg.com` asset fetches. **[V]**
2. `x-csrf-token` must be **derived from the jar at request-build time**, never cached in a long-lived
   header map. **[V]**
3. Persist `auth_token` + `ct0` (and `gt`, `guest_id` if present) to disk after every response. **[L]**
4. Keep the WebView's `CookieManager` and OkHttp's `CookieJar` in sync, or you get exactly the #7467 403.
   Android: bridge via `CookieManager.getInstance().getCookie("https://x.com")`. **[L]**

Note: gallery-dl will *invent* a random `ct0` if the cookie is absent (`util.generate_token()`,
`twitter.py:1338-1340`; exposed as `extractor.twitter.csrf: "auto"|"cookies"`, default `"cookies"`). **[V]**
That path exists for **guest** mode. For an authenticated session, `ct0` must be the real one bound to
`auth_token`. **[L]** — a self-minted `ct0` on an authenticated request is the textbook cause of error 353.

### 4.2 Error taxonomy (from twscrape `queue_client.py::_check_rep` — the most complete map available) **[V]**

| Signal | Meaning | XTV action |
|---|---|---|
| `(32) Could not authenticate you` | **"Session expired or banned"** (twscrape's own log string) | **Re-prompt WebView login** |
| **HTTP 403 with NO `errors` array** | **"Session expired or banned"** | **Re-prompt login** |
| `(353) This request requires a matching csrf cookie and header.` (HTTP 403) | `x-csrf-token` ≠ `ct0` cookie | Resync from jar, retry once. **Not** a login failure |
| `(326) Authorization: Denied by access control` | account locked / banned | Surface to user; don't retry |
| `(88) Rate limit exceeded` **while** `x-rate-limit-remaining > 0` | twscrape logs `"Ban detected"` | Back off hard |
| `x-rate-limit-remaining == 0` + `x-rate-limit-reset > 0` | normal rate limit | Sleep until reset |
| `(336) The following features cannot be null: …` (HTTP 400) | stale `features` map | Update features |
| `(131) Dependency: Internal error` | transient X-side; **ignorable if HTTP 200 and `data` present** | Retry |
| **HTTP 404 + `content-length: 0` + no errors** | stale `queryId` **OR** missing/invalid TID | See §2.2 |
| `content-type: text/html` + status ≥ 400 + **`cf-ray` response header** | twscrape logs `"Blocked by Cloudflare"` | Abort; see §5.1 |
| `this account is temporarily locked` | locked | gallery-dl `AuthorizationError` **[V]** |

Rate-limit headers: `x-rate-limit-remaining`, `x-rate-limit-reset`, `x-rate-limit-limit`. **[V]**

Note the asymmetry: **code 32 / bare-403 = dead session; 353 = fixable desync.** Don't nuke the login on 353.

### 4.3 Best "is my session alive?" probe

GET `https://x.com/` with the jar and check whether the HTML is the **logged-out** app
(`entry-client-logged-out*.js`, per §2.5). This is cheap, is a page you must fetch for TID init anyway, and
distinguishes "session dead" from "TID/queryId wrong" — which the 404+empty response cannot. **[V]**

---

## 5. Additional obstacles for a non-browser client

### 5.1 Cloudflare — real but not the default path **[V]**
twscrape detects it via `cf-ray` + HTML body on ≥400 and aborts. It added an **opt-in** `curl-cffi` backend
(PR #308, 2026-06-08) whose stated purpose, verbatim: *"uses libcurl with **browser-level TLS fingerprint
spoofing**, which helps bypass Cloudflare bot detection."* Made **opt-in** a week later (`b35b3d3b`,
2026-06-15). Default remains plain `httpx`.

### 5.2 TLS / JA3 fingerprinting — **not a hard gate** **[V]**, but hedge
- gallery-dl works with `requests`/`urllib3` and only a **cipher-list** tweak (`CIPHERS_FIREFOX`,
  `common.py:1305-1323`) — that changes the cipher ordering, not the full JA3.
- twscrape's default `httpx` works; TLS impersonation is opt-in.
- ⇒ OkHttp on Android (Conscrypt/BoringSSL, a genuinely browser-like stack) is **[L] fine**.
- ⇒ Mitigation if you ever get HTML+`cf-ray`: route that request through the WebView (§6.3). Do **not**
  build a JA3-spoofing TLS stack on Android.

### 5.3 HTTP/2 — **NOT required** **[V]**
gallery-dl's entire stack is `requests`/`urllib3` = HTTP/1.1 only, and its logs show
`"GET /i/api/graphql/… HTTP/1.1" 200`. OkHttp will negotiate h2 anyway; either is fine.

### 5.4 `Accept-Encoding` / `sec-*` — **not enforced** **[V]**
yt-dlp sends neither `Sec-Fetch-*` nor a browser `Accept-Encoding` and works. gallery-dl sends
`gzip, deflate, br[, zstd]` only when the codecs are actually available. Send what OkHttp does natively.

### 5.5 `x-xp-forwarded-for` (XPFF) — **[L] NOT enforced for read GraphQL. Skip it.**
- What it is: AES-GCM-encrypted fingerprint blob, key = `SHA-256(hardcoded_base_key + guest_id_cookie)`,
  output hex = `IV || ciphertext || tag`, plaintext ≈
  `{"navigator_properties":{"hasBeenActive":"true","userAgent":"…","webdriver":"false"},"created_at":<ms>}`,
  **valid 300 000 ms (5 min)**. Generated in a **WASM** module. Sources:
  `dsekz/twitter-x-xp-forwarded-for-header`, `glizzykingdreko/twitter-generator`. **[V] (repo claims)**
- Why skip: **none of gallery-dl, yt-dlp, or twscrape sends it** (I grepped all three — zero hits), and all
  three work in July 2026. **[V]**
- Watch item: if XTV starts getting 404+empty on ops where the TID is provably correct, XPFF is the next
  suspect. **[U]**

### 5.6 Interstitials / migration redirects **[V]**
twscrape's `get_tw_page_text()` handles two non-obvious hops when fetching x.com HTML:
1. a JS redirect body containing `document.location = "…"`, and
2. a form `action="https://x.com/x/migrate" method="post"` whose hidden `<input>`s must be POSTed back.
Your asset fetcher must handle both, or TID init fails intermittently.

### 5.7 Age/region restriction on media (2026) — **[V]**, plan for it
gallery-dl #9647 (2026-07-12), maintainer `mikf`: accounts now silently return **no media** for
"NSFW"-flagged accounts under *"Age Restriction" enforcement*; Nitter surfaces it as
*"Due to local laws, we are temporarily restricting access to this content until X estimates your age."*
XTV will see this as **empty timelines, HTTP 200, no error** — not an auth bug. Don't misdiagnose it.

---

## 6. Recommended architecture for XTV

### 6.1 Do this
1. **WebView for login only.** User signs in on `x.com`; harvest `auth_token` + `ct0` from `CookieManager`.
2. **Port §2.3 to Kotlin** (~200 lines: SHA-256, base64, cubic solve, rotation matrix). Port **§2.4
   asset-discovery from twscrape** (both schemes), not gallery-dl (legacy only).
3. **One OkHttp client**, one persistent `.x.com` `CookieJar` shared with the WebView, `x-csrf-token`
   injected by an `Interceptor` that reads `ct0` from the jar **at call time**.
4. **TID `Interceptor`**: lazily init keys (cache ≤ 3 h, per gallery-dl's `_exp=10_800`), then stamp a fresh
   `x-client-transaction-id` per request from `(method, request.url.encodedPath)`.
5. **Never hardcode `queryId`s.** Scrape `<queryId, operationName>` pairs from the JS bundle you already
   fetch for TID init, and cache them beside the TID keys. This kills half your breakage classes at once.
   (Snapshot from gallery-dl master today, purely as a shape reference — **these WILL rotate**:
   `jCRhbOzdgOHp6u9H4g2tEg/UserMedia`, `pLtjrO4ubNh996M_Cubwsg/Bookmarks`,
   `DXmgQYmIft1oLP6vMkJixw/HomeTimeline`, `E8Wq-_jFSaU7hxVcuOPR9g/UserTweets` **[V]**. twscrape #306 listed
   `grLxZULbmdPQ7LlCKtG_jQ` for "User Media" on 2026-05-10 — **a different value**, which is itself the
   proof that hardcoding is a dead end. **[V]**)
6. On **404+empty**: refresh TID keys once, retry once (twscrape's exact strategy: `tries < 3`, `fresh=tries>0`,
   1 s sleep **[V]**); if it persists, refresh queryIds; only then surface an error.
7. On **code 32 or bare 403**: re-prompt login. On **353**: resync `ct0`, retry once.

### 6.2 Why NOT the WebView-proxy design
Worth stating explicitly, because it's an intuitive-but-wrong idea: **an injected `fetch()` on the x.com
origin does NOT get signed.** The TID is added by X's *own application code*, not by the browser. A
`fetch()` you inject carries cookies (browser does that) but no `x-client-transaction-id`. To get signing
you would have to either (a) reach into the webpack registry and call the module by numeric ID — `227900`
in the §2.6 snippet, which changes every build, or (b) monkey-patch `window.fetch` and let X's SPA drive
navigation, which means you can't choose what to request. Capture-and-replay is also out: the TID commits
to `METHOD!path!timestamp`, so a captured value is useless for any other path. **[L], derived from the
verified algorithm + the verified call site.**

### 6.3 Keep the WebView as a narrow fallback
Only if you hit HTML+`cf-ray` (§5.1) or an interstitial (§5.6): run that one request in the WebView on the
x.com origin. Real browser TLS, real cookie handling, and the page can clear challenges. Do not make it the
hot path.

---

## 7. Open unknowns and the exact experiments that settle them

### 7.1 ⭐ THE decisive experiment: does the server *verify* the TID, or only require its presence?

This is the single highest-value unknown left, and it is **[U]**. If X only checks presence/shape, XTV
skips the entire bundle-parsing liability (§2.4) — the biggest maintenance win available.

Run all four against **the same, known-good, freshly-scraped `queryId`**, on an op known to be gated
(`UserMedia`), from the same IP, within a minute:

```bash
# Shared setup (from your live browser session)
AUTH='auth_token=<...>; ct0=<CT0>'
Q='<freshly scraped queryId>'
V='%7B%22userId%22%3A%22783214%22%2C%22count%22%3A20%7D'   # urlencoded variables
F='<urlencoded features json copied from the browser request>'
U="https://x.com/i/api/graphql/$Q/UserMedia?variables=$V&features=$F"
BEARER='AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA'

common=(-sS -o /dev/null -D - -H "authorization: Bearer $BEARER"
        -H "x-csrf-token: <CT0>" -H "x-twitter-auth-type: OAuth2Session"
        -H "x-twitter-active-user: yes" -H "x-twitter-client-language: en"
        -H "cookie: $AUTH" -H "user-agent: <exact browser UA>")

# A) no TID at all           -> expect 404, content-length: 0   (confirms enforcement)
curl "${common[@]}" "$U"

# B) syntactically valid garbage TID (51 random base64 chars)
curl "${common[@]}" -H "x-client-transaction-id: $(head -c38 /dev/urandom | base64 | tr -d '=\n')" "$U"

# C) a TID copied verbatim from DevTools for a DIFFERENT path (e.g. .../UserByScreenName)
curl "${common[@]}" -H "x-client-transaction-id: <copied>" "$U"

# D) the TID DevTools shows for THIS exact path+method, replayed within ~30 s
curl "${common[@]}" -H "x-client-transaction-id: <copied>" "$U"
```

Read the **status line, `content-length`, and `x-rate-limit-remaining`** on each (the #306 trick: if
`remaining` decrements, auth was accepted and the rejection is the WAF, not auth).

Interpretation:
- **A=404, B=200** ⇒ presence/shape only. **Huge win** — no bundle parsing needed at all. Retest monthly.
- **A=404, B=404, C=200** ⇒ content verified but path/time not bound. Unexpected; would allow key reuse.
- **A=404, B=404, C=404, D=200** ⇒ **full cryptographic verification.** Port §2.3 + §2.4 as specified.
  This is what I expect, per twscrape #322 (stale keys ⇒ rejected). **[L]**

### 7.2 Other unknowns

| # | Unknown | Experiment |
|---|---|---|
| 1 | Which of `UserMedia`, `Bookmarks`, `HomeTimeline` actually enforce TID *today* | Run §7.1(A) per operation. Per-op enforcement varies (#322: `Followers` gated, `Following` not) **[V]** |
| 2 | Byte length of `key_bytes` ⇒ expected TID length | Base64-decode the `twitter-site-verification` meta once; log `len`. Use as an assertion |
| 3 | Which asset scheme (A or B, §2.4) *your* account is served | `curl -H "cookie: $AUTH" https://x.com/ \| grep -o 'x-web[^"]*\.js' \| head` — empty ⇒ Scheme A |
| 4 | How long TID keys stay valid (gallery-dl assumes 3 h; is that measured or arbitrary?) | Init once, then re-issue one request/15 min with the same keys until it 404s |
| 5 | Is `x-twitter-auth-type: OAuth2Session` actually enforced? | §7.1(D) minus that one header |
| 6 | Does `ct0` rotate on **API** responses, or only on HTML/asset fetches? | Log every `Set-Cookie: ct0` over a 100-request session, tagged by request type |
| 7 | Is XPFF (§5.5) ever required for media/bookmark reads? | Only investigate if §7.1(D) returns 200 in curl but the app still 404s |
| 8 | Exact JSON body of the TID-rejection 404 (is it truly empty, or `{"errors":[{...no message...}]}`?) | `curl -i` and dump raw bytes; gallery-dl's "Unspecified" log hints at the latter **[L]** |

### 7.3 Monitoring you should build now
Subscribe to (or poll) `mikf/gallery-dl` and `vladkens/twscrape` issues filtered on `twitter`/`xclid`. Every
X-side breakage in §2.4's table showed up there within ~24 h, usually with a working patch. That is your
early-warning system and your fix source.

---

## 8. Source index (all fetched 2026-07-26)

**Code (raw, read in full):**
- `https://raw.githubusercontent.com/mikf/gallery-dl/master/gallery_dl/extractor/twitter.py` (2488 lines)
- `https://raw.githubusercontent.com/mikf/gallery-dl/master/gallery_dl/extractor/utils/twitter_transaction_id.py` (250 lines) ← **primary algorithm source**
- `https://raw.githubusercontent.com/mikf/gallery-dl/master/gallery_dl/extractor/common.py` (headers/ciphers/browser emulation)
- `https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/yt_dlp/extractor/twitter.py` (1767 lines)
- `https://raw.githubusercontent.com/vladkens/twscrape/main/twscrape/xclid.py` (378 lines) ← **primary asset-discovery source**
- `https://raw.githubusercontent.com/vladkens/twscrape/main/twscrape/{queue_client,account,http}.py`

Local copies: `…/scratchpad/research/src/`

**Issues/PRs (fetched verbatim via `gh`, not summarized):**
- gallery-dl #7382 (2025-04-17, TID first enforced — maintainer + community quotes), #7467 (403 csrf desync),
  #9260 / #9267 / #9602 / #9630 (asset breakages), #9505 (guest token 403), #9647 (age restriction), #9275
- twscrape #248 (2025-04-25 first 404s), **#306 (2026-05-10 — the controlled observation)**, #303, #312
  (2026-06-11 x-web rollout + A/B confirmation), #320/#321 (2026-07-21 cookies for assets),
  **#322 (2026-07-23 — per-op enforcement)**
- yt-dlp #16176 (csrf mismatch; unresolved, `cant-reproduce`)

**Reference writeups:** antibot.blog `1741552025433` / `1741552092462` / `1741552163416` (live site 502'd;
Part 1 read via `fa0311.github.io/antibot_blog_archives`); `iSarabjitDhiman/XClientTransaction`;
`dsekz/twitter-x-xp-forwarded-for-header`; `glizzykingdreko/twitter-generator`; `fa0311/twitter-tid-deobf`.

**Not done (out of scope / impossible here):** no live X requests were made; no login attempted. Every
numeric constant above is quoted from source code, and every enforcement claim is quoted from a dated issue.
