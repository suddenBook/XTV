# XTV — Rate limits & account-safety on the user's MAIN account

> **Status: RESEARCH SNAPSHOT / NON-AUTHORITATIVE FOR BILLING.** This document records dated source
> reading. The X Developer Console remains the billing authority and its hard limit is mandatory;
> the production rate card was subsequently checked against the Console.

Research date: 2026-07-26. Method: static reading of maintained scraper source + primary docs + repo
issues. No live requests to X were made. Every claim is tagged.

Tag key: **[V]** = VERIFIED (primary source cited) · **[L]** = LIKELY (stated reasoning) ·
**[U]** = UNKNOWN, needs a live test (experiment given).

Local copies of every source file read are in this directory (`gdl_twitter.py`, `ytdlp_twitter.py`,
`tws_queue_client.py`, `nit_auth.nim`, `nit_apiutils.nim`, `nit_types.nim`, `gdl_config.rst`, …).

---

## 0. Executive answer

A foreground-only, ~8-request-per-launch client on the user's own session is **far** below every
rate limit number anyone has ever published, and below the volume threshold in X's own liquidated-damages
clause by ~5 orders of magnitude. The realistic risks are, in descending order:

1. **Getting soft-throttled and misreading it** (X returns 200-with-errors and *truncated* data far
   more often than a clean 429). Design for this, not for 429.
2. **A hidden secondary limiter** that fires while `x-rate-limit-remaining` is still ~99% — verified
   below. Header-based self-throttling is necessary but **not sufficient**.
3. **Session invalidation / account lock (error 326)** requiring a captcha or phone check. Recoverable,
   but frightening on a main account.
4. Outright suspension. No evidence found of this happening to low-volume read-only personal use;
   plenty of evidence for high-volume multi-account fan-out farms.

The single highest-leverage safety decision is architectural, not numeric: **do the requests from
inside the WebView on the `x.com` origin** so the client is byte-for-byte a browser (§5.1).

---

## 1. Rate-limit response headers

### 1.1 The three headers exist and are readable — quadruple-verified

**[V]** Four independently maintained projects read exactly these names off `x.com/i/api` responses:

| Project | Source | Code |
|---|---|---|
| Nitter | `src/apiutils.nim:8-10` | `rlRemaining = "x-rate-limit-remaining"` / `rlReset = "x-rate-limit-reset"` / `rlLimit = "x-rate-limit-limit"` |
| gallery-dl | `gallery_dl/extractor/twitter.py:1911` | `remaining = int(response.headers.get("x-rate-limit-remaining", 6))` |
| twscrape | `twscrape/queue_client.py:197-199` | `limit_remaining = int(rep.headers.get("x-rate-limit-remaining", -1))` … `limit_reset` … (`limit_max` present but commented out) |
| twscrape (logging) | `queue_client.py:89-96` | `req_id()` formats every log line as `{remaining}/{limit} - {username}` |

Lower-case names; HTTP headers are case-insensitive, and OkHttp normalises. **[V]**

**[V] `x-rate-limit-reset` is an absolute Unix timestamp in seconds**, not a delta. Three independent
confirmations:
- X's own docs: "`x-rate-limit-reset` — Unix timestamp when window resets" (https://docs.x.com/x-api/fundamentals/rate-limits).
- Nitter compares it directly against `epochTime().int` (`nit_auth.nim:160`, `:127`).
- twscrape compares it against `utc.ts()` and passes it to `lock_until` (`queue_client.py:215-217`).

**[L] A native client can absolutely read them to self-throttle.** With OkHttp/Ktor on Android these are
just response headers. **Caveat if you fetch from inside the WebView via JS `fetch()`:** `x-rate-limit-*`
are not CORS-safelisted response headers, so JS can only read them on a **same-origin** request. Requests
issued from a page on the `x.com` origin are same-origin and can read them; requests issued from a
`file://` or custom-origin WebView page cannot, and will silently see `null`. **[L — standard CORS
semantics, not X-specific]**

### 1.2 Per-endpoint numbers I can actually cite

Limits are **per (credential, GraphQL operation)**, not global. **[V]** — this is structural in two
codebases, not inferred:
- Nitter keys its rate-limit table by endpoint: `session.apis[api] = RateLimit(limit:, remaining:, reset:)`
  (`nit_auth.nim:200-211`), where `api = req.endpoint(session)`.
- twscrape's `QueueClient` is constructed with `queue = op.split("/")[-1]` (the operation name, e.g.
  `"Bookmarks"`) and locks accounts per-queue: `locks: dict[str, datetime]  # queue: datetime`
  (`tws_account.py:28`).

**[V] Observed `x-rate-limit-limit` values, with dates.** These are the only real numbers I found. Treat
them as *historical observations*, not constants — the gallery-dl maintainer explicitly says they drift:

| Endpoint | Limit / 15 min | Date | Source |
|---|---|---|---|
| `UserMedia` | **500** | 2024-03-15 | mikf (gallery-dl maintainer), https://github.com/mikf/gallery-dl/issues/5330#issuecomment |
| `UserByScreenName` | **95** | 2024-03-15 | ibid. |
| `UserTweets` | **50** | 2024-03-15 | ibid. |
| `SearchTimeline` | **50** | 2024-03-15 | ibid. |
| *(unnamed, 500-class)* | **500** | **2026-01-10** | header dump in https://github.com/mikf/gallery-dl/issues/8864 |
| `SearchTimeline` | **50** | **2025-12-15** | twscrape issue #286 log line `200 - {N}/50 - {username}`; `/50` is `x-rate-limit-limit` per `req_id()` |

mikf's exact words, worth quoting because they set the epistemics correctly:

> This depends on a lot of factors, I think. — API endpoint / geo location / overall Twitter use and
> abuse by other users / date / moon phase / …
>
> For me, rate limits are currently at 95/15m UserByScreenName, 500/15m UserMedia, 50/15m UserTweets,
> 50/15m SearchTimeline

**Interpretation for XTV:** the observed spread is **50–500 per 15 min per endpoint**. The 500-class
number was still 500 in Jan 2026 and the 50-class was still 50 in Dec 2025, so the two tiers appear
stable in magnitude. **[L]**

**[U] `Bookmarks` and `HomeLatestTimeline` limits are UNKNOWN.** Nobody has published them. These are
precisely the two endpoints XTV depends on. Neither gallery-dl, twscrape, yt-dlp nor Nitter hardcodes or
documents a limit for them. Do not guess.
*Experiment:* log `x-rate-limit-limit` from the first response to each of
`/i/api/graphql/<id>/Bookmarks` and `/i/api/graphql/<id>/HomeLatestTimeline` on a cold 15-min window.
One request each answers it definitively. Put this behind a debug toggle in XTV from day one.

### 1.3 Window mechanics: fixed 15-minute windows — proved arithmetically

**[V]** From the header dump in gallery-dl issue #8864:

```
Date: Sat, 10 Jan 2026 22:03:18 GMT
x-rate-limit-limit:     500
x-rate-limit-remaining: 497
x-rate-limit-reset:     1768083433     # = 2026-01-10T22:17:13Z
```

`reset − Date = 835 s = 13 m 55 s`. Window start therefore ≈ `22:02:13Z`, i.e. **reset − 900 s**. The
observed request landed 65 s into the window and had consumed 3 of 500.

Conclusions:
- **[V] 15-minute (900 s) window**, consistent with X's own docs ("usually 15 minutes or 24 hours").
- **[V] Fixed window, not rolling** — `reset` is a wall-clock boundary, and the whole budget becomes
  available again at once. Nothing "drips back".
- **[L] The window is anchored to your first request in it**, which is X's documented v2 behaviour
  ("Counters reset every 15 minutes from your first request in that window"), but I did not verify the
  anchoring rule for the *web GraphQL* path specifically.
- **[V] X's docs also mention 24-hour windows** for some endpoints. This is the documented basis for the
  otherwise-folkloric "locked out for a day" reports (§2.3).

### 1.4 ⚠️ The headers are NOT sufficient — there is a hidden secondary limiter

This is the most important finding in this document, and it has two independent confirmations.

**[V] Evidence A** — the 2026-01-10 dump above: the client was being rate-limited with
`remaining: 497 / limit: 500`. **99.4% of the header budget was unused.** (Caveat: it's a verbose
multi-request log, so I cannot be 100% certain the 429 and this header set are the same HTTP response —
though the reporter frames them as such. The *window arithmetic* in §1.3 holds regardless.)

**[V] Evidence B** — twscrape encodes this exact signature as its **ban detector**
(`queue_client.py:220-224`):

```python
# no way to check is account banned in direct way, but this check should work
if err_msg.startswith("(88) Rate limit exceeded") and limit_remaining > 0:
    logger.warning(f"Ban detected: {log_msg}")
    await self._close_ctx(-1, inactive=True, msg=err_msg)
```

A maintainer deliberately treating "error 88 while remaining > 0" as *a different condition from being
rate limited* is strong evidence that X operates a second limiter — a longer-window budget, a
per-IP/per-ASN cap, or a behavioural heuristic — that is invisible in the headers.

**Design consequence for XTV:** header-driven throttling handles the *soft* limit. You must
independently enforce your own conservative ceiling (§4) and treat `88 && remaining > 0` as a
**red-alert stop**, not as a normal backoff. **[L]**

---

## 2. What hitting a limit looks like, and correct backoff

### 2.1 Failure shapes, ranked by how often they'll actually bite you

| Shape | Meaning | Verified where |
|---|---|---|
| **HTTP 200 + `errors[]` in body** | The common case. Often with *partial* data. | gallery-dl `twitter.py:1916-1953` parses `errors` before checking status; twscrape branches on `rep.status_code == 200 and "Authorization" in err_msg`; yt-dlp `twitter.py:135-143` |
| **HTTP 429** (± code 88) | Classic rate limit. | gdl `:1958`; yt-dlp `:1198` (`e.cause.status != 429`); Nitter `:166` (`result.startsWith("429 Too Many Requests")`) |
| **HTTP 404, empty body, no GraphQL error** | Rejected/stale `x-client-transaction-id`. **Not** a rate limit. | twscrape #322 (2026-07-23); twscrape `queue_client.py:63-82` retries with a fresh TID on any 404; Nitter `:139-141` treats empty-404 as transient |
| **HTTP 403, no errors** | Session dead or account actioned. | twscrape `:236-239` → marks account inactive |
| **HTTP 503** | "Bad client" — X rejected the request shape. | Nitter `:135-137` `badClient = true; raise BadClientError` |
| **`text/html` body + status ≥ 400 + `cf-ray` header** | Cloudflare edge block, not the API. | twscrape `:187-190` |
| **200 + truncated results, no error** | Silent degradation. | twscrape #286 (2025-12-15): "returns results, though only a very small subset … my accounts never reach the rate limit" |

### 2.2 Verified numeric error codes

**[V]** Complete enum from Nitter `src/types.nim:49-73` (independently corroborated by twscrape's string
matches on 88/326/32/131/336, and by X's docs for 88):

```
0   null                  50  userNotFound          179 tweetNotAuthorized
17  noUserMatches         63  suspended             200 forbidden
22  protectedUser         88  rateLimited           214 badRequest
25  missingParams         89  expiredToken          239 badToken
29  timeout               112 listIdOrSlug          326 locked
32  couldntAuth           131 timelineUnavailable   353 noCsrf
34  doesntExist           144 tweetNotFound         421 tweetUnavailable
37  unauthorized                                    422 tweetCensored
```

Also seen in the wild but **not** in that enum: **336** "The following features cannot be null"
(feature-flag drift — twscrape `queue_client.py:210-212` calls `exit(1)` on it), and
**"Dependency: Unspecified"** / **"Dependency: Internal error"**, which are X-side flakiness that both
yt-dlp and twscrape explicitly **ignore** as false positives (yt-dlp `:136-139`, referencing
yt-dlp#15963, 2026-02-16; twscrape `:242-249`).

The ones that matter to XTV, in order:
- **88 rateLimited** — back off. But see §1.4: if `remaining > 0`, this is *not* an ordinary rate limit.
- **326 locked** — account requires human verification (captcha/phone). gallery-dl matches the message
  `"this account is temporarily locked"` and by default **aborts** (`ratelimit`/`locked` configs;
  `extractor.twitter.locked` default `"abort"`). Nitter throws the session away
  (`invalidate(session)`, `:159-161`).
- **32 couldntAuth / 89 expiredToken / 239 badToken** — your `auth_token`/`ct0` is dead. Re-login, do
  **not** retry in a loop. gallery-dl aborts outright: `"Could not authenticate you"` →
  `AbortExtraction` (`:1938-1939`).
- **353 noCsrf** — you forgot/desynced `x-csrf-token`. This is a **bug in your client**, not a limit
  (§5.2).
- **63 suspended** — the outcome the user is worried about.

### 2.3 Correct backoff

**[V] What the maintained clients actually do:**

- **gallery-dl** — `_handle_ratelimit` (`twitter.py:2399-2423`): read `x-rate-limit-reset`, sleep until
  that absolute timestamp; if the header is absent, sleep a flat **60 s**. `wait()` adds `adjust=1.0` s
  of slack past the reset (`gdl_common.py:329`). Default policy is `"wait"`; `"abort"` and `"abort:N"`
  exist. Retries on server-sourced GraphQL errors up to `retries-api` = **9**, and only when
  `errors[].source == "Server"` (`:2142-2151`) — a clean retriable/non-retriable discriminator worth
  copying.
- **gallery-dl also throttles *pre-emptively*** (`:1911-1914`) — note the jitter:
  ```python
  remaining = int(response.headers.get("x-rate-limit-remaining", 6))
  if remaining < 6 and remaining <= random.randrange(1, 6):
      self._handle_ratelimit(response); continue
  ```
  i.e. it starts probabilistically stalling once fewer than 6 remain, with randomised thresholds so
  many clients don't stampede the reset boundary simultaneously.
- **Nitter** — reserves a **10-request buffer**: `limit.remaining <= 10 and limit.reset > now` ⇒ treat as
  limited (`nit_auth.nim:159-160`). On a hard error 88 it benches the session for
  **`hourInSeconds` = 3600 s** (`:151`), while the source comment on that branch says
  `# rate limit hit, resets after 24 hours` (`nit_apiutils.nim:162`). Code and comment disagree; the
  honest reading is "somewhere between 1 h and 24 h, and the maintainers aren't sure either."
- **Nitter concurrency**: `maxConcurrentReqs = 2` per session (`nit_auth.nim:12`). **[V]** — the most
  conservative published in-flight number.
- **twscrape** — `remaining == 0 && reset > 0` ⇒ lock the account for that endpoint until `reset`
  (`:215-217`). Unknown/unhandled statuses ⇒ **15-minute** penalty box (`:268`, `:315`, `:343`).
- **yt-dlp** — degrades rather than waits: on 429 it falls back to the public syndication endpoint
  (`twitter.py:1196-1199`, `'Rate-limit exceeded; falling back to syndication endpoint'`). The
  *pattern* — on 429, serve something worse rather than block — is the right instinct for a TV UI.
- **X's own docs** recommend: wait for `x-rate-limit-reset`, "use exponential backoff if needed", with a
  **60 s minimum** floor in their sample.

**[V] Notably, gallery-dl inserts NO delay between Twitter requests by default.** Its
`extractor.*.sleep-request` default table (`gdl_config.rst:590+`) gives Instagram `"6.0-12.0"` s and
Danbooru `"0.5-1.5"` s, but Twitter falls into the `0` ("otherwise") bucket, and
`TwitterAPI` never overrides `request_interval` (which defaults to `0.0`, `gdl_common.py:53`). The
maintainers rely on the headers, not on politeness delays. That is a data point *against* the folklore
that X needs multi-second inter-request sleeps — but it's tuned for throughput on burner-ish workflows,
not for main-account safety, so XTV should still jitter (§5).

**Recommended for XTV [L]:**
1. Sleep until `x-rate-limit-reset + 2 s` — never a blind fixed delay when the header is present.
2. Absent header ⇒ 60 s, then 120 s, 300 s (cap). Full jitter (`rand(0, backoff)`), not equal jitter.
3. **Do not retry more than twice for one user action.** Give up and show cached content.
4. `88 && remaining > 0` ⇒ **stop the session entirely**, don't back off-and-retry. That's the
   ban-adjacent signature.
5. `32 / 89 / 239 / 326` ⇒ **never auto-retry.** Surface a "sign in again" / "check x.com in a browser"
   screen. Retry loops on auth errors are exactly how a soft lock becomes a hard one. **[L]**

### 2.4 Do limits count against the user's other clients? — **UNKNOWN, and it matters**

**[U]** I could not resolve this from static sources, and the search results asserting "per-account,
shared" trace to SEO blogspam, not primary evidence. Do not trust that claim.

What I can say:
- **[V]** Rate limits are tracked against a *credential set*. All four projects model one
  `(auth_token, ct0)` pair as one bucket (`has_required_cookies()` in `tws_account.py:16-17` requires
  exactly `auth_token` + `ct0`; Nitter's `Session` is `authToken` + `ct0`).
- **[V]** None of them can distinguish per-session from per-user, because each of their accounts has
  exactly one cookie set. The question is structurally invisible to all of them.
- **[L] The in-app WebView login mints a NEW `auth_token`, distinct from the one in the user's desktop
  browser and phone app.** So if X's limiter keys on session/token, XTV gets its own budget and cannot
  starve the user's real clients. If it keys on `user_id`, they share.
- **[L]** The official mobile app authenticates differently (OAuth1 with the `3nVuSoBZnx6U4vzUxf5w`
  consumer key — see Nitter `consts.nim:5-6`, which is the *legacy Twitter-for-iPhone* key) and hits
  `api.x.com`, a different surface with its own documented per-user limits. It is the *least* likely of
  the three to share a bucket with `x.com/i/api` GraphQL.

**Exact experiment** (5 minutes, no risk):
1. From XTV, issue one `HomeLatestTimeline` request. Record `limit`, `remaining`, `reset`.
2. Within the same 15-min window, in a desktop browser logged into the same account, open DevTools →
   Network, scroll the Following timeline once, and read the same three headers off its
   `HomeLatestTimeline` request.
3. **Decision rule:** if the browser's `reset` is *identical* to XTV's and its `remaining` continues
   XTV's countdown ⇒ **shared per-account bucket**. If `reset` differs (different window anchor) and
   `remaining` is near `limit` ⇒ **per-session buckets**.
   The `reset` timestamp is the reliable tell, because a shared fixed window must have a shared boundary.

Until this is settled, **assume shared** and budget accordingly. Costless conservatism. **[L]**

---

## 3. What actually correlates with flagging/suspension

### 3.1 Supported by evidence

- **[V] High-volume, multi-account, sustained fan-out gets accounts banned.** twscrape #274
  (2025-11-05): "Starting to see a lot of 429 error and accounts get banned since yesterday", reply:
  "X banned all my scraping accounts." That is the population that gets suspended: dedicated scraping
  fleets, not personal clients.
- **[V] Repeatedly hammering an endpoint through a rate limit produces long lockouts.** gallery-dl #7766
  (2025-07): a broken `--filter` caused a request storm; mikf's diagnosis: *"What you are effectively
  doing is bombarding Twitter with non-stop search API requests."* The user's log shows six consecutive
  15-min lockouts. Corroborated by gallery-dl #7308: *"this locks me out for the whole day once tripped"*
  and #5330: *"my account/IP gets permanently rate limited for at least 12 hours."*
  **The pattern that hurts is retry-through-the-limit, not raw volume.** **[L, from those three reports]**
- **[V] Account locks (326) and captchas are a real, observed outcome** for scraping-shaped traffic —
  gallery-dl handles the message `"this account is temporarily locked"` explicitly and has a
  `locked: "wait"` option for humans to solve the captcha and resume (`twitter.py:1930-1936`;
  `extractor.twitter.locked`). This is the failure mode a main-account owner should actually fear:
  recoverable, but it means a captcha/phone check.
- **[V] Datacenter IPs are worse.** twscrape #91: *"works well on my local mac, but don't work on my
  aws ec2."* **Does not apply to XTV** — an Android TV on residential broadband is the *good* case here.
- **[V] Request *shape* is validated independently of rate.** A stale/absent `x-client-transaction-id`
  produces HTTP 404 empty bodies (twscrape #322, 2026-07-23) and HTTP 503 "bad client" (Nitter
  `:135-137`). X is fingerprinting the client, not just counting requests.

### 3.2 Fan-out vs sequential pagination

**[V-structural]** Because limits are per-*endpoint*, fanning out across many followed accounts is
**not** cheaper than paginating one timeline — 200 `UserMedia` calls for 200 different users all drain
the *same* `UserMedia` bucket. There is no per-target budget to spread load across.

**[L]** So the pattern advice is the opposite of intuition: prefer **one aggregate endpoint**
(`HomeLatestTimeline`, `Bookmarks`) over N per-user endpoints. XTV's design already does this, which is
good — and it also means XTV should resist a future "browse each followed account's media tab" feature,
which is exactly the fan-out shape associated with banned accounts.

**[U]** Whether X's *heuristics* (as opposed to its counters) score fan-out as more suspicious than
sequential pagination is unknown and unknowable from source. No experiment I'd run on a main account.

### 3.3 Unlikely to matter for XTV

- **[L] Absolute request volume at XTV's scale.** ~10 req/launch is inside the noise floor of a single
  human scrolling x.com in a browser tab, which fires many `HomeLatestTimeline`/`TweetDetail` calls per
  minute.
- **[L] New-device login.** A WebView login on the user's home network is a normal browser session from a
  normal residential IP. X may email a "new login" notice; that is a notification, not an action. (Note:
  this is the one place where XTV genuinely differs from a browser, and I could not verify X's
  device-risk behaviour — help.x.com is Cloudflare-gated to both `curl` and WebFetch. **[U]**)
- **[L] Media/CDN fetches.** These are the requests that will dominate XTV by *count* (every thumbnail
  in a grid), and they do **not** touch the GraphQL budget. **[V-structural]**: gallery-dl's rate-limit
  logic lives entirely inside `TwitterAPI._call`, which only ever hits `self.root = "https://x.com/i/api"`
  (`twitter.py:1326`); `pbs.twimg.com` / `video.twimg.com` downloads go through the ordinary downloader
  with no `x-rate-limit` handling at all. Budget GraphQL calls carefully; don't agonise over image loads.
- **Foreground-only with no background worker** removes the single biggest risk multiplier — an
  unattended loop that keeps retrying while the user is asleep. Every long-lockout report in §3.1 came
  from an unattended batch run. **[L]**

---

## 4. Is the user's specific plan in a safe regime? — Yes, comfortably

**Per cold launch:**

| Requests | What |
|---|---|
| 3 | `Bookmarks` pages |
| 5 | `HomeLatestTimeline` pages |
| ~2 | `x-client-transaction-id` bootstrap: `GET https://x.com/` + `GET abs.twimg.com/…/ondemand.s.*.js` (cacheable — gallery-dl caches the whole `ClientTransaction` object for `_exp=10_800` s = 3 h, `twitter.py:1886-1888`; Nitter caches its key pairs for `ttlSec = 3600`) |
| ~1 | viewer/self bootstrap, if you need it |
| **≈ 11** | **total, ~8 of which are GraphQL** |

Against the numbers in §1.2, per 15-minute window:
- vs. the **lowest** observed per-endpoint limit (50/15 min): 3 Bookmarks + 5 HomeLatest = **6% and 10%**
  of budget. You could cold-launch **~10 times in 15 minutes** and still sit under Nitter's
  keep-10-in-reserve rule.
- vs. the 500-class limit: **~1%**.
- vs. X's ToS liquidated-damages threshold of 1,000,000 posts / 24 h: at ~20 posts/page × 8 pages ≈ 160
  posts per launch, you'd need **~6,250 launches per day** to reach it. **[V]** (§6)

### Recommended budget

| Ceiling | Value | Why |
|---|---|---|
| **Per GraphQL operation, per 15 min** | **≤ 20** | 40% of the lowest observed limit (50); leaves Nitter's 10-request reserve intact even if the real limit is 50 |
| **All GraphQL, per rolling hour** | **≤ 60** | "clearly safe" answer to the user's question. ~7× their stated launch pattern; still ≤ 30% of a single 50/15m endpoint's hourly capacity |
| **Hard per-session (per app foreground) cap** | **40** | A tripwire, not a budget — normal use should never reach it |
| **Max in-flight concurrent** | **2** | Matches Nitter's `maxConcurrentReqs = 2`, the most conservative published value **[V]** |
| **Jitter between sequential pages** | **800–2500 ms**, full jitter | Human scroll cadence. Note gallery-dl uses `0` by default **[V]**, so this is strictly more conservative than the reference implementations |
| **Pages per user-initiated scroll** | **1** | Never speculatively prefetch more than one page ahead |

**[L]** 60 GraphQL req/hr is not a discovered threshold — it is a defensible ~10× margin below the
lowest number any primary source has published. I have no evidence that 200/hr would be unsafe either;
I just can't justify it from sources, and on a main account the asymmetry favours the low number.

**One caveat on "5 pages of the Following timeline":** `HomeLatestTimeline` is a *ranked/aggregated*
endpoint. Pages 2-5 may return heavily-overlapping or thin results, and thin results are exactly what
makes naive pagination loops spin. gallery-dl guards this with `stop_tweets` — Bookmarks gets
`stop_tweets=128`, meaning it tolerates 128 consecutive empty pages before concluding it's done
(`twitter.py:1596`, logic at `:2329-2345`), and it also *shrinks the `count` variable* when it stalls.
**Do not copy that tolerance.** For XTV, stop after **2** consecutive pages that yield zero new media,
and stop unconditionally if the returned cursor equals the cursor you sent — gallery-dl's own
termination check: `if not cursor or cursor == variables.get("cursor"): return` (`:2346-2348`). **[V]**

Also: page size is a free lever. gallery-dl requests `count: 50` for `Bookmarks` / `HomeTimeline` /
`HomeLatestTimeline` (`twitter.py:1591`, `:1694`, `:1706`); twscrape uses `count: 20`
(`tws_api.py:604`). **[V]** Requesting `count: 50` instead of 20 gets 2.5× the media per request against
the same budget. Prefer fewer, larger pages.

---

## 5. Defensive engineering

The user's own list (cache aggressively, serve stale on 429, jittered delays, hard per-session cap,
visible "rate limited, showing cached" state, kill switch) is correct and sufficient as a baseline.
Additions, ordered by value:

### 5.1 Highest leverage: make the request from the WebView's origin

**[L]** You already need a WebView for login. Keep using it as the transport, or at minimum have the
native client mirror it exactly. Rationale, all verified:
- **[V]** All four maintained scrapers impersonate a desktop browser. Nitter's header set
  (`nit_apiutils.nim:68-95`) is the best published reference — note it sends `sec-ch-ua` values
  *version-matched* to its `user-agent` (both Chrome 142), plus `origin: https://x.com`,
  `referer: https://x.com/`, `accept-language`, `priority: u=1, i`, and the three `sec-fetch-*` headers.
  A mismatched `sec-ch-ua`/UA pair is a trivially detectable tell.
- **[V]** `x-client-transaction-id` requires executing/parsing X's own JS: fetch `https://x.com/`, read
  the `twitter-site-verification` meta tag, locate the `ondemand.s` webpack chunk hash, fetch
  `https://abs.twimg.com/responsive-web/client-web/ondemand.s.{hash}a.js`, extract indices, and extract
  SVG animation frames from `id="loading-x-anim-*"` elements
  (gallery-dl `extractor/utils/twitter_transaction_id.py:37-79`).
- **[V]** This pipeline breaks *often*: gallery-dl #9602 (2026-06-22, webpack chunk-map format changed →
  `404 for ondemand.s.a.js`), #9630 (2026-07-04, same), twscrape #322 (2026-07-23, "JS bundle update" →
  `Followers` returns empty 404 while `Following` still works). **This is the #1 maintenance burden in
  the whole ecosystem right now — not bans, not rate limits.**
- **[L]** A WebView that runs X's real JS computes a correct transaction ID *for free* and stays correct
  across bundle changes. For a solo dev, this eliminates the single largest source of ongoing breakage.
  Reimplementing TID generation in Kotlin means signing up to chase X's bundle format forever.
- **[V]** Enforcement is per-endpoint and inconsistent ("other endpoints such as `Following` are still
  working, likely because they have more tolerant validation" — twscrape #322). So a missing TID may
  work on `Bookmarks`/`HomeLatestTimeline` today and stop working with no warning.
- **[U]** Whether *omitting* TID increases flagging risk (vs. merely 404-ing) is unknown. Don't find out.

**[V] Bearer token note:** the web bearer `AAAA…ANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D…` is
identical across gallery-dl (`twitter.py:1357-1359`), yt-dlp (`_AUTH`, `:39`), twscrape
(`tws_account.py:13`) and Nitter (`bearerToken`, `consts.nim:7`) — it's a public constant, not a secret.
There are also older bearers (Nitter's `bearerToken2` = `AAAA…FXzAwAAAAAAMHCxpeSDG1g…`, yt-dlp's
`_LEGACY_AUTH` = `AAAA…IK1zgAAAAAA2tUWuhGZ2Jce…`) used for `/1.1/` paths. **[V]** Nitter pairs
`bearerToken2` with *no* transaction ID (`nit_apiutils.nim:95-99`) — suggesting the legacy bearer isn't
TID-gated. **[U]** Whether the legacy bearer still reaches the timeline endpoints XTV needs, and whether
using it looks *more* anomalous, is untested.

### 5.2 Get `ct0` / `x-csrf-token` handling exactly right

**[V]** This is the most common self-inflicted bug in this space (error **353 noCsrf**). Verified rules:
- `x-csrf-token` **must** equal the `ct0` cookie value. Both gallery-dl and twscrape simply copy it:
  `headers["x-csrf-token"] = cookies["ct0"]` (`tws_account.py:72`).
- **X rotates `ct0` mid-session.** gallery-dl re-reads it from the response on *every* call and updates
  the header — with two separate bug references in the source:
  ```python
  # update 'x-csrf-token' header (#1170)
  if csrf_token := response.cookies.get("ct0"):
      self.headers["x-csrf-token"] = csrf_token
  ```
  (`twitter.py:1907-1909`, plus the same again at `:1877-1881` for #7467). **If you snapshot `ct0` at
  login and never refresh it, you will start failing.** Wire your cookie jar and your header from the
  same live source on every request.
- `x-twitter-auth-type: "OAuth2Session"` is set **iff** an `auth_token` cookie exists (gallery-dl
  `:1349`; yt-dlp `:123-128` sends it only when `is_logged_in`, otherwise sends `x-guest-token`). Don't
  send both.
- gallery-dl #9602 comments: two users independently report that **re-grabbing cookies fixed** what
  looked like a hard breakage. Make "re-login" a one-tap action, not a reinstall.

### 5.3 Beyond the user's list

- **Persist cursors, resume from them.** gallery-dl's `extractor.twitter.cursor` option exists exactly
  for this: it logs the last cursor on interruption so a resumed run doesn't re-paginate from the top
  (`gdl_config.rst:6473-6493`). **[V]** For XTV this converts "app relaunched" from 8 requests into ~1.
  Note the documented constraint: *"A cursor value from one timeline cannot be used with another"* —
  key your cursor cache by operation name.
- **Cache by tweet ID with a long TTL, and cache negative results too.** Media URLs on
  `pbs.twimg.com`/`video.twimg.com` are stable; a tweet's media never changes. There is no reason to
  re-fetch a page you already have.
- **Circuit breaker, not just a backoff.** After 2 consecutive rate-limit or auth failures, open the
  circuit for the remainder of the window and serve cache exclusively. Every long-lockout report in
  §3.1 is a missing circuit breaker.
- **Treat "empty page with a cursor" as terminal after 2 tries** (§4). This is the #1 cause of accidental
  request storms — see gallery-dl #7766 and #9524 ("endless rate limit loop").
- **Discriminate retriable from non-retriable by `errors[].source == "Server"`** — gallery-dl's
  discriminator (`twitter.py:2142-2151`). Retry *only* `source == "Server"`. Never retry an auth error.
- **Log `x-rate-limit-limit`/`-remaining`/`-reset` per operation to a debug screen in-app.** Cheap, and
  it's the only way you'll ever learn the real `Bookmarks`/`HomeLatestTimeline` numbers (§1.2).
- **Alarm on the ban-adjacent signature.** `code == 88 && remaining > 0` ⇒ hard stop + a distinct,
  scary UI state. Do not fold this into generic backoff. **[V per §1.4]**
- **Never auto-retry `326 locked`.** Show "open x.com in a browser and complete verification." An
  automated retry loop against a locked account is the worst thing this app could do.
- **Kill switch should be local and offline-capable** — a remote-config kill switch that needs network
  is useless when the failure mode is "network responses are hostile." A local "pause all X requests
  for 24 h" toggle the user can hit is more valuable.
- **Foreground-only is a real safety property — protect it in code.** No `WorkManager`, no
  `JobScheduler`, no prefetch-on-boot. Assert it in a lint rule or a code comment, because it is the
  easiest thing to erode later.
- **Randomise nothing about identity.** Pin one UA string and one matching `sec-ch-ua` set and keep them
  stable across launches. A session whose UA changes per request is more anomalous than one that's
  slightly stale. **[L]**

---

## 6. X's stated position — factual, not legal advice

**I could not fetch X's help-centre pages** (Cloudflare 403 to both `curl` and WebFetch), so §6 rests on
the Terms of Service, which I did retrieve verbatim from the copy embedded in `https://x.com/en/tos`
(fetched 2026-07-26). Quotes are exact.

**[V] "Misuse of the Services"** — the operative clause. You may not:

> (iii) access or search or attempt to access or search the Services by any means (automated or
> otherwise) other than through our currently available, published interfaces that are provided by us
> (and only pursuant to the applicable terms and conditions), unless you have been specifically allowed
> to do so in a separate agreement with us (NOTE: crawling or scraping the Services in any form, for any
> purpose without our prior written consent is expressly prohibited)

Also in the same section **[V]**:

> You also agree not to misuse the Services, for example, by interfering with them or accessing them
> using a method other than the interface and the instructions that we provide. You agree that you will
> not work around any technical limitations in the software provided to you as part of the Services, or
> reverse engineer, decompile or disassemble the software, except and only to the extent that applicable
> law expressly permits.

and

> (viii) interfere with, or disrupt, (or attempt to do so), the access of any user, host or network,
> including, without limitation, sending a virus, overloading, flooding, spamming, mail-bombing the
> Services…

**Plainly: XTV is outside the ToS.** Not because of volume, and not because of intent — but because
clause (iii) prohibits access by any means other than X's published interfaces, and additionally names
scraping without prior written consent. Reading one's own timeline is still "accessing the Services";
the clause has no personal-use, read-only, or low-volume carve-out. Generating
`x-client-transaction-id` also sits squarely inside "work around any technical limitations" /
"reverse engineer". There is no reading of this text under which XTV is compliant.

**[V] Liquidated damages** — the only quantified threshold in the document:

> …you will be jointly and severally liable to us for liquidated damages as follows for requesting,
> viewing, or accessing more than 1,000,000 posts (including reply posts, video posts, image posts, and
> any other posts) in any 24-hour period - $15,000 USD per 1,000,000 posts.

**[V]** The EU/EEA variant of the same clause reads **€15,000 EUR per 1,000,000 posts** (the ToS
document contains both). Relevant given the user is in the Netherlands. XTV at ~160 posts/launch is
~4 orders of magnitude/day below this threshold. **[L]** It does not create liability at this scale;
it does show what X built its enforcement economics around, which is bulk AI-training-scale harvesting —
not a TV app.

**[V] Termination is discretionary and broad:**

> We may also suspend or terminate your account for other reasons, such as prolonged inactivity, risk of
> legal exposure, or commercial inviability.

**[V] Forum/law:** Texas law; disputes must proceed in Wichita County or Tarrant County, Texas. (An
EU-resident consumer generally has separate statutory protections regarding forum clauses; that is a
legal question, not one I'm answering.)

**[V] The Developer Agreement & Policy is NOT the operative document here.** It governs use of the X
API (`api.x.com` with developer credentials). XTV uses neither — it uses the private web endpoints under
`x.com/i/api/graphql/*` with the user's own browser session cookies and the public web bearer token.
So the constraint is the ToS clause above, not API tier rules or API rate limits. (I could not fetch
`developer.x.com` — 402/403 — so I did not read the Developer Agreement text; this scoping claim rests
on the fact that XTV never touches an API credential. **[L]**)

**Honest framing for the user:** the practical consequence of a ToS breach at XTV's scale is **account
action** (a lock, a captcha, at worst suspension) — an X-side enforcement decision, not litigation.
Nothing in the research suggests X pursues individuals for personal-scale reading. But the account is
the thing at stake, and it is X's discretion, so treat every §5 mitigation as protecting the account
rather than achieving compliance. Compliance is not available; only low profile is.

---

## 7. Open unknowns, ranked, with the experiment for each

| # | Unknown | Experiment | Risk of running it |
|---|---|---|---|
| 1 | `x-rate-limit-limit` for `Bookmarks` and `HomeLatestTimeline` | One request to each; log the header. | None |
| 2 | Does XTV's WebView session share a bucket with the user's browser/phone? | §2.4 — compare `reset` timestamps across clients in one window. | None |
| 3 | What is the hidden secondary limiter (§1.4) keyed on — 24 h budget, IP, or heuristic? | Only distinguishable by deliberately tripping it. **Do not run on this account.** | High |
| 4 | Is `x-client-transaction-id` required on `Bookmarks` / `HomeLatestTimeline` today? | Send one request without it; a 404-empty ⇒ required. | Low |
| 5 | Does omitting TID increase *flagging* risk (vs. just 404)? | Not determinable without risking the account. | Unacceptable |
| 6 | Does X's new-device-login flow challenge a WebView login on residential IP? | Observable on first login. Have a browser handy to clear any challenge. | Low |
| 7 | Whether error 88 lockout is 15 min, 1 h, or 24 h on a cookie session | Nitter's code says 1 h, its comment says 24 h, users report 12–24 h. Only measurable by tripping it. | High |
| 8 | X help-centre text on locked/limited accounts | Cloudflare-gated to automated fetches; read it in a real browser. | None |

---

## 8. Source index

**Primary source code (local copies in this directory):**
- gallery-dl `gallery_dl/extractor/twitter.py` — rate limit: `:1911-1914`, `:1958-1960`, `:2399-2423`;
  errors: `:1916-1969`; headers: `:1344-1360`; TID: `:1871-1891`; endpoints: `:1589` (Bookmarks),
  `:1703` (HomeLatestTimeline); pagination/stop: `:2067-2360`
- gallery-dl `gallery_dl/extractor/common.py` — `:53-55` (`request_interval*` defaults),
  `:312-344` (`wait()`), `:486-510`
- gallery-dl `gallery_dl/extractor/utils/twitter_transaction_id.py` — `:37-79`
- gallery-dl `docs/configuration.rst` — `:590+` (`sleep-request` defaults), `:6473` (cursor),
  `:6679` (ratelimit), `:6699` (locked), `:6734` (retries-api)
- yt-dlp `yt_dlp/extractor/twitter.py` — `:36-40` (bases/bearers), `:113-145` (headers, error
  handling), `:1196-1199` (429 → syndication fallback)
- twscrape `twscrape/queue_client.py` — `:63-82` (TID retry on 404), `:89-96` (`req_id`),
  `:178-269` (`_check_rep`: the full error taxonomy)
- twscrape `twscrape/account.py` — `:13` (bearer), `:16-17` (`has_required_cookies`), `:65-75` (headers)
- twscrape `twscrape/api.py` — `:29-49` (operation IDs), `:168-205` (`_gql_items`), `:601-615` (bookmarks)
- Nitter `src/apiutils.nim` — `:8-10` (header names), `:68-99` (**best published header set**),
  `:113-184` (fetch/error/rate-limit handling)
- Nitter `src/auth.nim` — `:12` (`maxConcurrentReqs = 2`), `:145-165` (10-request reserve, 1 h bench),
  `:194-211` (per-endpoint tracking)
- Nitter `src/types.nim` — `:49-73` (**numeric error code enum**)
- Nitter `src/consts.nim` — `:5-8` (consumer key + both bearers), `:10-41` (operation IDs)
- Nitter `src/tid.nim` — TID generation, 1 h pair cache

**Primary docs:**
- https://docs.x.com/x-api/fundamentals/rate-limits — header semantics, 429 + code 88, 15 min / 24 h
  windows, backoff guidance (scoped to X API v2)
- `https://x.com/en/tos` (fetched 2026-07-26) — Misuse of the Services; Liquidated Damages; termination;
  Texas forum. Note: `curl` gets the SPA shell, but the full ToS text **is** embedded in it; the CDN PDF
  at `cdn.cms-twdigitalassets.com/…/x-terms-of-service-2025-05-08.pdf` 403s without a `Referer: https://x.com/`.

**Repo issues (behavioural evidence):**
- mikf/gallery-dl#5330 (2024-03-15) — **maintainer's observed per-endpoint limits**
- mikf/gallery-dl#8864 (2026-01-10) — **header dump proving 15 min window + limited-while-remaining-497**
- mikf/gallery-dl#7766 (2025-07) — request storm → repeated lockouts; maintainer diagnosis
- mikf/gallery-dl#7308 (2025-04) — "locks me out for the whole day once tripped"
- mikf/gallery-dl#9476 (2026) — PSA: two lockouts in a row ≠ end of media (user's "limit is relative to
  date" theory is **[L] a misreading** — deep history uses `SearchTimeline`, the 50/15m endpoint)
- mikf/gallery-dl#9602 (2026-06-22), #9630 (2026-07-04) — TID/webpack breakage; "new cookies fixed it"
- mikf/gallery-dl#9524 (2026-05-13) — endless rate-limit loop on sparse timelines
- vladkens/twscrape#274 (2025-11-05) — **mass bans of scraping accounts**
- vladkens/twscrape#286 (2025-12-15) — `/50` limit + silent result truncation at HTTP 200
- vladkens/twscrape#322 (2026-07-23) — **TID rejection ⇒ empty 404; enforcement varies per endpoint**
- vladkens/twscrape#91 — datacenter IP (EC2) fails where residential works
- yt-dlp/yt-dlp#15963 (2026-02-16) — "Dependency: Unspecified" is a false-positive error

**Explicitly rejected as sources:** blog/SEO pages (unfollr, getxapi, 9meters, tendx, api.sorsa.io,
contextbolt) surfaced repeatedly in search and assert confident per-endpoint numbers and a
"limits are shared per account" claim with no primary evidence. Several conflate X API v2 limits, web
GraphQL limits, and *posting* caps. None of their numbers are used in this document.
