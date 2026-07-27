# XTV — X GraphQL timeline endpoints & pagination

> **Status: ARCHIVED / REJECTED PATH.** These web GraphQL notes are historical evidence, not the
> v1.1.0 endpoint contract. XTV uses the official API; none of the query IDs or feature flags below
> should be copied into production code.

Research date: **2026-07-26**. Method: static reading of maintained scraper source, X's own daily-scraped
bundle dump, and official docs. No live X requests were made.

Confidence tags: `[V]` = VERIFIED against a named source · `[L]` = LIKELY (reasoning stated) ·
`[?]` = UNKNOWN, needs live test.

Primary sources used (raw files fetched today):
| id | source | vintage |
|---|---|---|
| **GDL** | `gallery-dl` `gallery_dl/extractor/twitter.py` @ master, v1.32.8 | last twitter.py commit 2026-04-04 |
| **TWS** | `twscrape` `twscrape/api.py` + `queue_client.py` @ main | gql ops updated 2026-07-21 (`8ce44e44`) |
| **RSSH** | RSSHub `lib/routes/twitter/api/web-api/{api,constants,utils,gql-id-resolver}.ts` @ master | 2026 |
| **TIAD** | `fa0311/TwitterInternalAPIDocument` `docs/json/GraphQL.json` @ `2865ea9e` | **2026-07-24, regenerated daily from X's own JS bundle** |
| **OAPI** | `fa0311/twitter-openapi` `dist/docs/openapi-3.0.yaml` + `src/openapi/schemas/*.yaml` | schema of real captured traffic |
| **TREK** | `trekhleb.github.io` `.../api-design-x-home-timeline/types/x.ts` | Dec 2024 HomeTimeline capture |
| **TCLI** | `public-clis/twitter-cli` `twitter_cli/graphql.py` | 2026 |
| **XARC** | `sytelus/xarchive` `FINDINGS.md` / `PLAN.md` | 2026, derived doc — treat as secondary |
| **XDOC** | `docs.x.com` official API v2 docs + changelog | live |

`yt-dlp/yt_dlp/extractor/twitter.py` was read and is **not useful for this topic**: it only implements
single-tweet (`TweetResultByRestId`, `TweetDetail`), cards, broadcasts and Spaces. It has **no timeline /
bookmark / user-media extractor** `[V]`. Read it for media-variant extraction (topic 03), not endpoints.

---

## 0. TL;DR decisions for XTV

1. **Do not hardcode a queryId as the only path.** Resolve at runtime from X's JS bundle, cache for
   ~24h, and keep a pinned fallback. Rotation is real and fast (§1).
2. **`features` and `queryId` are coupled** — one `features` object per bundle vintage. Resolve both from
   the same source or you invite HTTP 400 / error code **336** (§3).
3. **HTTP 404 on a GraphQL path usually means a bad `x-client-transaction-id`, not a rotated queryId.**
   This is the single most misdiagnosed failure in the ecosystem (§1.4, §8).
4. All four channels use the **same URT instruction/entry/cursor machinery**, and (as of 2026-07-24) the
   **same 39-flag `features` set and same 8 `fieldToggles`** — write one pager, parameterise the data root
   path and the `variables` (§2, §3).
5. **The official API v2 is now a genuinely serious option for XTV** and I think you're wrong to have
   dismissed it: "Owned Reads" (your own bookmarks + your own reverse-chronological timeline) dropped to
   **$0.001/resource on 2026-04-20**, and v2 *does* return playable video `variants`. But it caps
   bookmarks at ~800 and gives you no per-creator media tab. See the blunt verdicts in §9.
6. RSS bridges and Nitter: dead or strictly worse. §9.

---

## 1. Operation IDs (`queryId`) — rotation, and how to survive it

### 1.1 URL shape `[V]` (GDL, TWS, RSSH, OAPI, XARC — unanimous)

```
GET https://x.com/i/api/graphql/<queryId>/<OperationName>
      ?variables=<urlencoded compact JSON>
      &features=<urlencoded compact JSON>
      &fieldToggles=<urlencoded compact JSON>     # only when the op declares fieldToggles
```
Base is `https://x.com/i/api` `[V GDL:1326 self.root]`. `api.x.com` is only used for the legacy `/1.1/`
and `/2/` REST endpoints `[V yt-dlp:36-37]`.

All four timeline ops are **GET** `[V OAPI: paths `/graphql/{pathQueryId}/Bookmarks`, `.../HomeLatestTimeline`,
`.../ListLatestTweetsTimeline`, `.../UserMedia` all declare only `get:`]`. TREK documents the browser using
**POST** with `{variables, features, queryId}` in the JSON body for `HomeTimeline`; both work
`[L — GDL has shipped GET for `HomeLatestTimeline` for years]`. **Use GET.** It keeps one code path and
matches every maintained scraper.

`operationType` in TIAD is `"query"` for all four — that's the *GraphQL* op type, not the HTTP method.
Don't confuse them.

### 1.2 Measured rotation rate `[V]` — this is the headline number

I pulled `docs/json/GraphQL.json` from TIAD at 7 points in time and extracted the queryIds for exactly the
ops XTV needs. Every value below is verbatim from that file at that date:

| date | `Bookmarks` | `HomeLatestTimeline` | `UserMedia` | `ListLatestTweetsTimeline` | #featureSwitches |
|---|---|---|---|---|---|
| 2025-10-01 | `ire7TB3NNzZOIa2SeD8pLA` | `CbqC-3PKr4m5zDk66V-QDw` | `1sfLYBlfEneWDhkHSv_9hw` | `vjJFDV5e1ixUOcijn0ov5g` | 34 |
| 2026-01-01 | `E6jlrZG4703s0mcA9DfNKQ` | `_qO7FJzShSKYWi9gtboE6A` | `MMnr49cP_nldzCTfeVDRtA` | `fqNUs_6rqLf89u_2waWuqg` | 34 |
| 2026-03-01 | `VFdMm9iVZxlU6hD86gfW_A` | `csRxUH5ocwnJtPnB3-wr4g` | `7cY8tGRcM6ypCK6AaY0abg` | `zfC9biNzR7KEplrp1U3GNw` | 37 |
| 2026-05-01 | `1nFKbANnLDDNT2nyLFZxtQ` | `eObmT5Nuapp04u8bYWf49Q` | `d_uaoPr42_nSDblfvi7NPw` | `gJSs2LdqumQ2a5G1J4VWFw` | 37 |
| 2026-06-01 | `XD0ViOeSOW4YoeNTGjVaYw` | `0dateTVgvXjpkf7kyBZy0g` | `9EovraBTXJYGSEQXZqlLmQ` | `7UuJsFvnWuZo0HmxrzU42Q` | 39 |
| 2026-07-01 | `tUVliYsHyxrQIT4HXUWNdA` | `g9NSjyYXOBsmMiP9TmYGaA` | `DpzwOu8Idtlbfqh-Hf718Q` | `Iql5aRVyFxNZ-ORcDV_TwQ` | 39 |
| 2026-07-20 | `LoLaMO4GuHLEPJOhH9kjAw` | `lyhT5o5ECF6_kYqTqpUUew` | `IS3w9vvPg1SJysLErvnFGg` | `LV64djPRhnsVhGCK76s13w` | 39 |
| **2026-07-24** | **`xtj3H29MLXk0r_3TIgdU5g`** | **`ddVhUphMh60ZnRiTVVF3RA`** | **`6k_h0NmaKHYxL0lScGLJSw`** | **`I_2igI-CwKBiHDHJgXNxfA`** | 39 |

Read this table for its **shape**, not its values — every row is already stale except possibly the last.
Conclusions:
* **All timeline ops rotate in lockstep** `[V]`. They share one persisted tweet-fragment; edit it and every
  containing op re-hashes. So "did Bookmarks rotate?" is really "did the tweet fragment change?".
* Cadence: **~2–4 weeks, sometimes twice in a week** (`2026-07-20` → `2026-07-24` is 4 days). XARC's
  independent estimate is "every 2–4 weeks" `[XARC §1.3]`. twscrape's git log shows re-scrapes on
  2026-06-15, 2026-06-26, 2026-07-21 `[V TWS commits 92c2eb96, be33a337, 8ce44e44]`.
* **`featureSwitches` count grew 34 → 37 → 39 across the same window** `[V]`. That's the real breakage
  driver, not the hash.

### 1.3 Old queryIds keep working for a long time `[V]` — important and under-appreciated

gallery-dl master today ships `pLtjrO4ubNh996M_Cubwsg/Bookmarks`, `SFxmNKWfN9ySJcXG_tjX8g/HomeLatestTimeline`,
`jCRhbOzdgOHp6u9H4g2tEg/UserMedia`, `06JtmwM8k_1cthpFZITVVA/ListLatestTweetsTimeline`
`[V GDL:1589, 1703, 1557, 1729]`. Those hashes were last bulk-updated in commit `ea298f18`
**2025-08-18** ("[twitter] update API endpoint query hashes & parameters") `[V git log]` — i.e. they are
**~11 months stale and gallery-dl still works** (v1.32.8, no open "bookmarks 404" issue).

Stronger: the maintainer's own post-mortem of the *previous* break, issue #7382 (2025-04): mikf wrote
*"Twitter API endpoints and associated query hashes and parameters haven't been updated in over a year …
so it is no surprise that it finally broke"* — i.e. **>1 year on a frozen hash** `[V mikf, gallery-dl#7382]`.

⇒ X keeps retired persisted queries resolvable for a long grace period `[L, strong]`. A pinned fallback is
therefore a *usable* degraded mode, not a dead end. But note: an old queryId requires the **old** feature
set, so pin the pair together.

### 1.4 The 404 trap `[V]`

In gallery-dl#7382 the symptom was `404 Not Found` on `UserTweetsAndReplies` while `UserTweets` and
`UserMedia` still worked. Root cause was **not** the queryId. mikf: *"Updating API endpoints didn't help.
`/with_replies` requires a valid `x-client-transaction-id` header value, and computing those is
complicated."* `[V]` twscrape encodes the same lesson in code:

```python
# queue_client.py — "if code 404 on first try then generate new x-client-transaction-id and retry"
# https://github.com/vladkens/twscrape/issues/248
while tries < 3:
    gen = await XClIdGenStore.get(..., fresh=tries > 0)
    hdr = {"x-client-transaction-id": gen.calc(method, path)}
    rep = await self.clt.request(method, url, params=params, headers=hdr)
    if rep.status_code != 404: return rep
```
`[V TWS queue_client.py]`

**XTV rule:** on 404, retry up to 3× with a freshly-derived transaction id *before* concluding the queryId
rotated. Enforcement is per-operation and X can turn it on for an op at any time `[V — that's exactly what
happened to `with_replies`]`.

### 1.5 How to resolve queryIds at runtime — three concrete recipes

**(a) Scrape X's own bundle (what RSSHub does).** Verbatim `[V RSSH gql-id-resolver.ts]`:
1. `GET https://x.com` (HTML), match `/\/client-web\/main\.([a-z0-9]+)\./`
2. `GET https://abs.twimg.com/responsive-web/client-web/main.<hash>.js`
3. `content.matchAll(/queryId:"([^"]+)".+?operationName:"([^"]+)"/g)`
4. cache (RSSHub uses `config.cache.contentExpire`), merge over hardcoded `fallbackIds`.

**(b) Walk *all* bundles (what twscrape does)** — more complete, because `main.*.js` does not contain
every op `[V TWS scripts/update_gql_ops.py]`:
* seed from `https://x.com/xdevelopers` **and** `https://x.com/home`
* two URL patterns: `https://<host>/x-web/<path>.js` and `https://<host>/responsive-web/client-web/<path>.js`
* recursively follow relative JS imports: `` /(?:from|import)\s*\(?\s*[`"]((?:\.{1,2}\/)[^`"]+?\.js)[`"]/ ``
* skip i18n / icons / syntax-highlighter chunks
* two extraction regexes:
  * `` /queryId:[`"](.+?)[`"].+?operationName:[`"](.+?)[`"]/ ``
  * `` /params:\{id:[`"]([^`"]+)[`"].+?name:[`"]([^`"]+)[`"].+?operationKind:[`"]/ ``
* **priority: responsive-web (2) beats x-web (1)** — *"This prevents version conflicts when IDs differ
  across sources."* `[V]` ⇒ **two different valid queryIds for the same op can be live simultaneously.**
  That plausibly explains why TWS@2026-07-21 has `LoLaMO4GuHLEPJOhH9kjAw/Bookmarks` while TIAD@2026-07-24
  has `xtj3H29MLXk0r_3TIgdU5g` `[L]`.

**(c) Piggyback on a community feed.** twitter-cli fetches
`https://raw.githubusercontent.com/fa0311/twitter-openapi/refs/heads/main/src/config/placeholder.json`
as a queryId source before falling back to bundle scanning `[V TCLI `_fetch_from_github`]`.
Better target: **`fa0311/TwitterInternalAPIDocument/docs/json/GraphQL.json`**, regenerated **daily**
(commits 2026-07-22, -23, -24 all touch it `[V]`), and it carries `queryId` **plus the exact
`featureSwitches` list, their default values, and `fieldToggles`** per operation. Note
`src/config/placeholder.json` in the *other* repo is a stale snapshot — its `Bookmarks` is
`XD0ViOeSOW4YoeNTGjVaYw`, matching TIAD's **2026-06-01** row `[V]`.

**Recommended for XTV** `[L]`: (a) as primary — one HTML fetch + one JS fetch, cheap, no third party, and
it's the same origin you're already authenticated to. Cache 12–24h. Fall back to a pinned pair. Consider
(c) as a second fallback only if you accept a GitHub dependency in an Android TV app.

A `main.*.js` bundle is multi-MB; on Android TV, stream it and regex incrementally rather than buffering
`[L]`. `[?]` Whether `main.*.js` alone contains all four ops XTV needs — RSSHub's own logging admits ops
can be `Missing:` from it. **Test:** fetch it and count matches for the four names.

---

## 2. The four channels

### (a) Bookmarks — `Bookmarks` — **primary v1 channel**

```
GET /i/api/graphql/<queryId>/Bookmarks
variables = {"count": 20, "includePromotedContent": false, "cursor": "<optional>"}
```
* `variables`: `count`* + `includePromotedContent`* required; `cursor` added for page 2+
  `[V OAPI: only those two are `required`; V GDL:1590-1593 sends exactly `{count, includePromotedContent}`]`.
  GDL defaults `count` to 50 `[V GDL:1591 + docs `extractor.twitter.limit` default `50`]`; the browser
  sends 20 `[V OAPI default]`; `count: 100` is historically accepted `[V gallery-dl#3172 log shows a live
  `Bookmarks?variables={"count":100,…}` request]`; XARC uses ~100/page `[XARC]`.
* **No `userId`** — the endpoint is implicitly "the authenticated user's bookmarks" `[V]`. There is no
  way to read someone else's bookmarks.
* `[V]` **Data root: `data.bookmark_timeline_v2.timeline.instructions`** — `[V GDL:1594-1596
  `("bookmark_timeline_v2", "timeline")`; V OAPI `BookmarksResponseData.bookmark_timeline_v2` → `BookmarksTimeline.timeline` → `Timeline.instructions`; V XARC §2.4]`.
  Note the `_v2` suffix is baked into the field name; there is no `bookmark_timeline` (v1) any more.
* **Historical `features` gotcha, still instructive:** twscrape sends
  `{"graphql_timeline_v2_bookmark_timeline": true}` as a *Bookmarks-only extra flag* `[V TWS api.py:613]`.
  That flag is **absent from the 2026-07-24 live set** `[V TIAD]` — it was the 2023-era switch that produced
  `400 … The following features cannot be null: graphql_timeline_v2_bookmark_timeline`
  `[V gallery-dl#3859, fixed by commits 0182680f / d07c9e5e]`. Sending it today is harmless but useless
  `[L]`. Take this as the canonical worked example of the whole failure class.
* **Bookmark date:** `sortIndex` on a bookmark entry encodes *when you bookmarked it*, not the tweet's
  date. GDL: `date_bookmarked = (int(tweet["sortIndex"]) >> 20) / 1000` as a unix-ms epoch
  `[V GDL:1049-1053]`. **Use this to sort XTV's bookmarks rail** — the tweet's own `created_at` gives you
  reverse-chronological-by-authorship, which is *not* the order the Bookmarks UI shows.
* **Empty-page severity is worst here.** GDL passes `stop_tweets=128` for Bookmarks only — i.e. it tolerates
  **128 consecutive pages with zero renderable tweets** before declaring the end `[V GDL:1594-1596]`.
  Every other timeline gets 0 or 3. Cause: long runs of bookmarks whose tweets are deleted / protected /
  age-gated, which arrive as tombstones or as entries with no `tweet_results` `[L, matches
  gallery-dl#3172 where bookmarks "stops on something I bookmarked 3-4 days ago"]`.
  **XTV must not stop on the first empty bookmarks page.**

**Bonus ops on the same data** — verbatim from TIAD 2026-07-24 (queryIds will rotate; names won't):

| operation | queryId @2026-07-24 | use for XTV |
|---|---|---|
| `BookmarkSearchTimeline` | `lSWl6Ugg54irJbFz8kuT7w` | `[L]` server-side `filter:media` inside bookmarks ⇒ far fewer requests per screenful of media |
| `BookmarkFoldersSlice` | `i78YDd0Tza-dV4SYs58kRg` | list bookmark folders; `variables = {}`, **zero featureSwitches, zero fieldToggles** `[V TIAD]` |
| `BookmarkFolderTimeline` | `FX00xnSGsSKOdHGJV8CaKg` | one folder; `variables = {"bookmark_collection_id": "<id>", "count": 50, "cursor": …, "includePromotedContent": false}` `[XARC §1.4 — secondary source, unverified]` |

`BookmarkSearchTimeline` is the most interesting unexplored lever for XTV. `[?]` Its `variables` shape is
not in OAPI and I could not verify it. `[L]` It is almost certainly `SearchTimeline`-like:
`{"rawQuery": "filter:media", "count": N, "querySource": "typed_query", "product": "Latest"}` — because
`SearchTimeline` takes exactly that `[V GDL:1613-1624]` and X names timelines after their variables shape.
**Test:** open `x.com/i/bookmarks`, type into the bookmark search box, read the request in devtools.
`[?]` Folder ops caps: XARC claims the *official v2* folder endpoint caps at 20 with no pagination; whether
the *GraphQL* `BookmarkFoldersSlice` paginates is unknown.

### (b) Following / chronological home — `HomeLatestTimeline`

```
GET /i/api/graphql/<queryId>/HomeLatestTimeline
variables = {"count":20,"includePromotedContent":false,"latestControlAvailable":true,
             "requestContext":"launch","seenTweetIds":[], "cursor":"<optional>"}
```
* **`HomeLatestTimeline` = the "Following" tab. `HomeTimeline` = the algorithmic "For You" tab.** `[V GDL
  `TwitterHomeExtractor`: pattern `/home(?:/fo(?:llowing|r[-_ ]?you()))?/?$`, and
  `if self.groups[0] is None: return self.api.home_latest_timeline()` — so `/home` and `/home/following`
  → `HomeLatestTimeline`, `/home/for_you` → `HomeTimeline` (GDL:797-807)]`.
* Required `variables` per OAPI: `count`, `includePromotedContent`, `latestControlAvailable`,
  `requestContext`, `seenTweetIds` `[V]`. GDL sends only
  `{count, includePromotedContent:false, latestControlAvailable:true}` and works `[V GDL:1703-1709]`;
  RSSHub adds `requestContext:"launch"` and `withCommunity:true` `[V RSSH api.ts getHomeLatestTimeline]`;
  TREK shows the browser sending `seenTweetIds: [...]` `[V]`.
  ⇒ "required" in OAPI means "the browser always sends it", not "the server rejects without it" `[L]`.
* **`seenTweetIds` is a telemetry write-back.** For a read-only client send `[]` — do not echo ids you
  rendered `[L]`. It exists so X can suppress already-seen posts; feeding it changes what you get and adds
  a behavioural fingerprint you don't need. `requestContext` is `"launch"` on cold start; the web app also
  uses other values `[?]` — `"launch"` is safe.
* **`includePromotedContent: false` — set it.** GDL sets it false everywhere `[V]`; RSSHub sets it **true**
  for Home `[V]`. False means X doesn't inject ad entries you then have to filter. This is both cheaper
  and cleaner for XTV.
* `[V]` **Data root: `data.home.home_timeline_urt.instructions`** — identical for `HomeTimeline` and
  `HomeLatestTimeline` `[V GDL:1699 & 1709 both use `("home","home_timeline_urt")`; V TREK; V OAPI
  `HomeTimelineResponseData.home.home_timeline_urt`]`.
* `[V]` Home responses carry `responseObjects.feedbackActions` (the "not interested" menu) — ignore it
  `[V TREK]`.
* `[!]` **This is a *post* timeline, not a media timeline.** Most entries have no media. XTV will burn
  N requests to fill one media row. Two mitigations: (i) large `count`, (ii) prefer Lists (§d) or
  `SearchTimeline` with `filter:media` for a media-dense "Following"-like rail `[L]`.
* `[?]` Whether `HomeLatestTimeline` has a bounded history depth. `[L]` It does in practice — home
  timelines are materialised feeds, not full archives. **Test:** page until the bottom cursor stops
  changing and record how many tweets / how far back you got.

### (c) One creator's media tab — `UserMedia`

```
GET /i/api/graphql/<queryId>/UserMedia
variables = {"userId":"<rest_id>","count":40,"includePromotedContent":false,
             "withClientEventToken":false,"withBirdwatchNotes":false,"withVoice":true,
             "cursor":"<optional>"}
fieldToggles = {"withArticlePlainText": false}
```
`[V OAPI: all six listed as required, `count` default 40, `userId` default `"44196397"`, and
`fieldToggles={"withArticlePlainText":false}`]` — and GDL:1557-1570 and TWS:516-530 and RSSH send the
same set (TWS/RSSH additionally send the legacy `withV2Timeline: true`, which OAPI no longer lists `[V]`).

* **Needs a numeric `userId`, not a handle.** Resolve via `UserByScreenName`
  (`variables = {"screen_name": "...", "withSafetyModeUserFields": true}`, `fieldToggles =
  {"withAuxiliaryUserLabels": false}`) → `data.user.result.rest_id`
  `[V GDL:1816, RSSH api.ts getUserData]`. **Cache this mapping in XTV forever** — it never changes and
  it's a full extra request per drill-down. `UserByScreenName` is a much smaller op: only **13**
  featureSwitches and 2 fieldToggles `[V TIAD]`.
* `[V]` **Data root: `data.user.result.timeline.timeline.instructions`** `[V GDL:2098-2100 (the `path=None`
  default); V OAPI `UserTweetsData.user.result(UserTweetsResultV1).timeline(TimelineResult).timeline(Timeline)`]`.
  **Legacy variants you must tolerate** `[V RSSH utils.ts:278]`:
  `user.result.timeline.timeline` ‖ `user.result.timeline.timeline_v2` ‖ `user.result.timeline_v2.timeline`.
  Also check `data.user.result.__typename === "UserUnavailable"` before descending `[V RSSH api.ts getUser]`.
* **Does it differ from the tweets tab? Yes, three ways** `[V]`:
  1. **Entries are grid modules, not items.** Media-tab entries arrive as
     `profile-grid-<n>` with `content.items[]` (each `{entryId: "profile-grid-0-tweet-<id>", item:{itemContent:…}}`),
     not as flat `tweet-<id>` entries. GDL handles `profile-grid-`, `search-grid-`, `communities-grid-`
     prefixes and does `tweets.extend(entry["content"]["items"])` `[V GDL:2180-2188]`. RSSHub hardcodes
     `entries.find(i => i.entryId === 'profile-grid-0')?.content?.items` and matches
     `profile-grid-0-tweet-` `[V RSSH utils.ts:294, 325]` — **that's a bug to not copy**: only grid *0* is
     read. Handle `profile-grid-*` generically. `displayType` on these modules is `VerticalGrid`
     `[V OAPI `DisplayType` enum: Vertical | VerticalConversation | VerticalGrid | Carousel]`.
  2. **It is media-*mostly*, not media-only.** twscrape post-filters: *"sometimes some tweets without
     media, so skip them"*, counting `photos + videos + animated > 0` `[V TWS api.py:534-543]`.
     XTV must still check `legacy.extended_entities` itself.
  3. **It's the cheapest way to get media density** — every page is ~`count` media tweets instead of
     ~`count` mostly-text tweets. Same request cost, far higher yield. Use it for drill-downs. `[V by
     construction + L on cost: same per-request weight, no evidence media tab is priced differently.]`
* **Media tab does not reach the full archive.** gallery-dl's `/USER/timeline` extractor deliberately runs
  `UserMedia` first, then switches to `SearchTimeline` with `from:<user> max_id:<oldest_seen> filter:links`
  to keep going `[V GDL:909-978, and docs `extractor.twitter.timeline.strategy` = "`/media` timeline +
  search"]`. Users report the boundary hardens over time: *"In March, I could still fully crawl an account
  with over 4,000 media items, but now it's impossible"* `[V gallery-dl#9600]`.
  For XTV ("more from @creator") this is a non-issue — you want the recent few hundred, not the archive.
  `[?]` The exact depth at which `UserMedia` stops/rate-limits. **Test:** page one high-volume account
  and log `x-rate-limit-remaining` + oldest `created_at` per page.
* `[!]` Deep `UserMedia` pagination is the known **ban vector**. See §8.

### (d) List timeline — `ListLatestTweetsTimeline` (later)

```
GET /i/api/graphql/<queryId>/ListLatestTweetsTimeline
variables = {"listId":"<id>","count":20,"cursor":"<optional>"}
fieldToggles = {"withArticleRichContentState": false}    # what twscrape sends
```
`[V OAPI: only `listId` + `count` required; V GDL:1729-1736; V TWS api.py:549 + `fieldToggles` for
`("SearchTimeline","ListLatestTweetsTimeline")`]`

* `[V]` **Data root: `data.list.tweets_timeline.timeline.instructions`** `[V GDL:1734, RSSH, OAPI]`.
* Companion: `ListMembers` → `data.list.members_timeline.timeline` `[V GDL:1738-1743]`.
* **Strategic note for XTV:** a hand-curated List of the accounts whose media you actually want is the
  single best lever you have. It is chronological like `HomeLatestTimeline`, but you control the
  signal-to-noise ratio, so media density per request goes way up and total request count goes way down.
  `[L]` I'd promote this above (b) in the roadmap.

---

## 3. `features`, `fieldToggles`, and the 400 that will bite you

### 3.1 The live set `[V TIAD @2026-07-24]`

**`Bookmarks`, `HomeTimeline`, `HomeLatestTimeline`, `UserMedia`, `ListLatestTweetsTimeline`,
`BookmarkSearchTimeline`, `BookmarkFolderTimeline` all declare the identical 39 `featureSwitches` and the
identical 8 `fieldToggles`.** One object serves all of XTV's channels. Verbatim, with the bundle's own
default values:

```json
{
  "rweb_video_screen_enabled": false,
  "rweb_cashtags_enabled": true,
  "profile_label_improvements_pcf_label_in_post_enabled": true,
  "responsive_web_profile_redirect_enabled": true,
  "rweb_tipjar_consumption_enabled": false,
  "verified_phone_label_enabled": false,
  "creator_subscriptions_tweet_preview_api_enabled": true,
  "responsive_web_graphql_timeline_navigation_enabled": true,
  "responsive_web_graphql_skip_user_profile_image_extensions_enabled": false,
  "premium_content_api_read_enabled": false,
  "communities_web_enable_tweet_community_results_fetch": true,
  "c9s_tweet_anatomy_moderator_badge_enabled": true,
  "responsive_web_grok_analyze_button_fetch_trends_enabled": false,
  "responsive_web_grok_analyze_post_followups_enabled": false,
  "rweb_cashtags_composer_attachment_enabled": true,
  "responsive_web_jetfuel_frame": true,
  "responsive_web_grok_share_attachment_enabled": true,
  "responsive_web_grok_annotations_enabled": true,
  "articles_preview_enabled": true,
  "responsive_web_edit_tweet_api_enabled": true,
  "rweb_conversational_replies_downvote_enabled": null,
  "graphql_is_translatable_rweb_tweet_is_translatable_enabled": true,
  "view_counts_everywhere_api_enabled": true,
  "longform_notetweets_consumption_enabled": true,
  "responsive_web_twitter_article_tweet_consumption_enabled": true,
  "content_disclosure_indicator_enabled": true,
  "content_disclosure_ai_generated_indicator_enabled": true,
  "responsive_web_grok_show_grok_translated_post": true,
  "responsive_web_grok_analysis_button_from_backend": true,
  "post_ctas_fetch_enabled": false,
  "freedom_of_speech_not_reach_fetch_enabled": true,
  "standardized_nudges_misinfo": true,
  "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled": true,
  "longform_notetweets_rich_text_read_enabled": true,
  "longform_notetweets_inline_media_enabled": false,
  "responsive_web_grok_image_annotation_enabled": true,
  "responsive_web_grok_imagine_annotation_enabled": true,
  "responsive_web_grok_community_note_auto_translation_is_enabled": true,
  "responsive_web_enhance_cards_enabled": false
}
```
```json
// fieldToggles, all 8, for every timeline op above
{ "withPayments": false, "withAuxiliaryUserLabels": false,
  "withArticleRichContentState": false, "withArticlePlainText": false,
  "withArticleSummaryText": false, "withArticleVoiceOver": false,
  "withGrokAnalyze": false, "withDisallowedReplyControls": false }
```
`[V]` the *names* and the *set membership* come straight from TIAD. `[V]` the boolean values above are
TIAD's `metadata.featureSwitch[<name>].value`, **except** `fieldToggles` values, which TIAD lists as names
only — the `false` values above are `[L]`, chosen because GDL/TWS send `false`/`{withArticlePlainText:false}`
and because `false` is the "don't fetch the extra payload" direction, which is what a read-only media
client wants `[V GDL:1552, 1568; TWS api.py:184-186]`.

`rweb_conversational_replies_downvote_enabled` has **no default value in the bundle** `[V TIAD: `null`]`.
No maintained scraper sends it and all of them work `[V]`, so it isn't enforced yet `[L]`. Send `false`.

`[!]` **Do not paste this block into XTV as a permanent constant.** It matches the 2026-07-24 queryIds.
The set grew 34 → 37 → 39 in nine months (§1.2). Pin it *alongside* the queryIds you pinned, and refresh
both together.

### 3.2 What happens when a flag is missing `[V]`

```
HTTP 400
{"errors":[{"code":336,"message":"The following features cannot be null: <comma-separated names>"}]}
```
* Error **code 336** `[V TWS queue_client.py: `if err_msg.startswith("(336) The following features cannot be
  null")` → `logger.error("[DEV] Update required")`; exit(1)]`.
* Real-world payload, verbatim from gallery-dl#7382: *"400 Bad Request (The following features cannot be
  null: rweb_tipjar_consumption_enabled, communities_web_enable_tweet_community_results_fetch,
  responsive_web_grok_analyze_post_followups_enabled, premium_content_api_read_enabled,
  rweb_video_screen_enabled, responsive_web_grok_show_grok_translated_post, responsive_web_jetfuel_frame,
  profile_label_improvements_pcf_label_in_post_enabled, responsive_web_grok_share_attachment_enabled,
  responsive_web_grok_analyze_button_fetch_trends_enabled, creator_subscriptions_quote_tweet_preview_enabled,
  responsive_web_grok_image_annotation_enabled, articles_preview_enabled,
  responsive_web_grok_analysis_button_from_backend)"* `[V]`
* Single-flag version, verbatim: `400 Bad Request (The following features cannot be null:
  graphql_timeline_v2_bookmark_timeline)` `[V gallery-dl#3859]`.

**The error message names every missing flag. Exploit that.** `[L, and I recommend it]`
Implement a one-shot self-heal: on 400/336, regex the names out of `message`, add each with `false`
(safe default: `false` = "don't ask for the extra field"), retry once, and persist the augmented set.
That converts XTV's most likely breakage from "app is dead until I ship an update" into "app logs a
warning and keeps working". Neither gallery-dl nor twscrape does this — twscrape literally `exit(1)`s.

### 3.3 Extra flags, and the URL-length trap

* **Extra/unknown flags appear to be ignored.** GDL sends its `features_pagination` superset (with
  `payments_enabled`, `rweb_xchat_enabled`, `responsive_web_grok_imagine_annotation_enabled` …) to every
  timeline op including Bookmarks, and ships `graphql_timeline_v2_bookmark_timeline` nowhere yet works
  `[V]`. RSSHub sends a **much smaller** set (23 flags for its feed ops) and also works `[V RSSH
  constants.ts `gqlFeatureFeed`]`. ⇒ enforcement is "must contain what this queryId references"; surplus is
  tolerated `[L, strong]`.
* **Conflicting claim, flagged deliberately:** twitter-cli strips all `false` values before building the
  URL — *"Only includes True-valued feature flags in the URL to avoid 414 URI Too Long. Twitter's API
  defaults missing features to False."* `[V TCLI `_build_graphql_url`]`. That directly contradicts the 336
  error, which fires on *presence*, not on value. I could not resolve this. **Send the full explicit set**
  (GDL's battle-tested behaviour) and treat 414/431 as the thing to watch for.
* 39 flags + 8 toggles + variables in a GET query string is a long URL. `[?]` Whether it can exceed X's
  limit. **Test:** measure the encoded length; if it approaches ~8 KB, switch that op to POST (TREK shows
  the browser doing exactly that for `HomeTimeline` `[V]`). twitter-cli hitting 414 is evidence the
  ceiling is reachable `[V]`.
* **Live feature *values* are also scrapeable.** twitter-cli parses X's homepage HTML for
  `/"([a-z][a-z0-9_]+)":\s*\{\s*"value"\s*:\s*(true|false)/` and updates only keys it already knows —
  *"Only UPDATES existing keys — never adds new ones … Adding new keys inflates URL length, causing
  414/431 errors"* `[V TCLI `_update_features_from_html`]`. Same trick, cheaper: TIAD's daily JSON already
  has `metadata.featureSwitch[name].value` per op `[V]`.

---

## 4. Headers

`[V GDL:1344-1359 self.headers]`, corroborated by `[V XARC §2.2]`:

| header | value | note |
|---|---|---|
| `authorization` | `Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA` | public web-app constant; **byte-identical in GDL, RSSHub, XARC** `[V×3]` |
| `Cookie` | must carry `auth_token` **and** `ct0` | your WebView session |
| `x-csrf-token` | **exactly** the `ct0` cookie value | |
| `x-twitter-auth-type` | `OAuth2Session` | only when `auth_token` present |
| `x-twitter-active-user` | `yes` | |
| `x-twitter-client-language` | `en` | |
| `x-client-transaction-id` | derived per (method, path) | see §1.4 |
| `content-type` | `application/json` | GDL sends it even on GET |
| `Referer` | `https://x.com/` | |
| `Sec-Fetch-Dest/Mode/Site` | `empty` / `cors` / `same-origin` | |

**Rotate `x-csrf-token` from responses.** X re-issues `ct0` mid-session; GDL re-reads it off every response
(`# update 'x-csrf-token' header (#1170)`) `[V GDL:1907-1909]`. If XTV pins `ct0` from login forever it
will eventually 403. Persist the refreshed value back to the WebView cookie jar.

`x-client-transaction-id` is derived from X's homepage HTML + `ondemand.s.<key>a.js` and is a moving
target: gallery-dl broke twice in 2026 when the webpack chunk-map format changed
`[V gallery-dl#9602 "webpack chunk map format changed", #9630 "404 for ondemand.s.a.js"]`. Notably, in both
threads *refreshing cookies fixed it* `[V #9602 comments]`. Reference implementations:
`iSarabjitDhiman/XClientTransaction` (Python), `swyxio/XClientTransactionJS` (JS) `[V XARC §2.2]`,
gallery-dl's `transaction_id.py` `[V GDL:1885-1891, cached 10800 s]`. **That's topic 01's problem** — but
note here that it gates §1.4's 404 handling.

---

## 5. Response shape — one walker for all four channels

### 5.1 Instruction types `[V]` — complete enum

`[V fa0311/twitter-openapi src/openapi/schemas/instruction.yaml `InstructionType`]`:
```
TimelineAddEntries, TimelineAddToModule, TimelineClearCache, TimelinePinEntry,
TimelineReplaceEntry, TimelineShowAlert, TimelineTerminateTimeline, TimelineShowCover,
TimelineClearEntriesUnreadState, TimelineMarkEntriesUnreadGreaterThanSortIndex
```
Field shapes that matter (verbatim from that schema) `[V]`:

| instruction | fields | XTV action |
|---|---|---|
| `TimelineAddEntries` | `entries: TimelineAddEntry[]` | the payload. **May appear more than once in one response** — GDL *extends* rather than assigns `[V GDL:2110-2114]` |
| `TimelineAddToModule` | `moduleItems: ModuleItem[]`, `moduleEntryId`, `prepend?` | "load more inside this grid/thread". GDL *replaces* `entries` with `moduleItems` when seen `[V GDL:2115-2116]` |
| `TimelinePinEntry` | `entry: TimelineAddEntry` | profile pinned post; **not part of the cursor stream** — hoist it once and never again, or you'll re-emit it on every page `[V GDL:2117-2119, 2176-2178]` |
| `TimelineReplaceEntry` | `entry_id_to_replace` (**snake_case!**), `entry` | how a *cursor* gets updated with no new entries. GDL: if `entry.entryId.startswith("cursor-bottom-")` → `cursor = entry.content.value` `[V GDL:2120-2124]`. **Miss this and pagination silently stalls.** |
| `TimelineTerminateTimeline` | `direction: Top \| Bottom \| TopAndBottom` | see §6 |
| `TimelineShowAlert` | `alertType: NewTweets`, `usersResults`, `richText`, … | ignore |
| `TimelineClearCache` / `ShowCover` / `*UnreadState` | — | ignore |

Note the casing inconsistency: `entry_id_to_replace` is snake_case while `moduleEntryId` is camelCase `[V]`.

### 5.2 Entry → tweet `[V]`

`TimelineAddEntry = { entryId: string, sortIndex: string, content: ContentUnion }` `[V instruction.yaml]`.
`ContentUnion` discriminates on `entryType` ∈ `{TimelineTimelineItem, TimelineTimelineCursor,
TimelineTimelineModule}` `[V content.yaml]`.

Dispatch **on `entryId` prefix** — that's what every scraper does, and it's more robust than `entryType`:

| `entryId` prefix | shape | source |
|---|---|---|
| `tweet-<id>` | `content.itemContent.tweet_results.result` | `[V GDL:2179-2180]` |
| `profile-grid-<n>` / `search-grid-` / `communities-grid-` | `content.items[]`, each `{entryId, item:{itemContent:…}}` | `[V GDL:2181-2187]` |
| `homeConversation-` / `profile-conversation-` / `conversationthread-` | `content.items[]` | `[V GDL:2188-2191]` |
| `tombstone-<id>` | `content.itemContent.tombstoneInfo` (no `tweet_results`) | `[V GDL:2192-2196]` |
| `cursor-top-…` / `cursor-bottom-…` | `content.value` + `content.cursorType` | `[V GDL:2197-2203; V TREK]` |
| `who-to-follow-` / `messageprompt-` / `module-` | injected junk | `[V TWS `_gql_entries` filter list]` |

Inside a module the item is under **`item`** (not `content`): `(entry.get("content") or entry["item"])["itemContent"]`
`[V GDL:2208-2209]`. RSSHub also handles a rarer `content.content.tweetResult.result` (singular
`tweetResult`) alongside `content.itemContent.tweet_results.result` `[V RSSH utils.ts:339]`.

`itemContent.__typename` ∈ `{TimelineTweet, TimelineTimelineCursor, TimelineUser, TimelinePrompt,
TimelineMessagePrompt, TimelineCommunity, TimelineTombstone, TimelineTrend, TimelineNotification}`
`[V content.yaml `ContentItemType`]`. `TimelineTweet` also has
`tweetDisplayType ∈ {Tweet, SelfThread, MediaGrid, CondensedTweet}` and optional `promotedMetadata`
`[V content.yaml]`.

### 5.3 `tweet_results.result` unwrapping `[V]`

`TweetUnion` discriminates on `__typename` `[V tweet.yaml]`:

| `__typename` | handling |
|---|---|
| `Tweet` | normal |
| **`TweetWithVisibilityResults`** | **real tweet is nested one level deeper at `.tweet`**; siblings `limitedActionResults`, `tweetInterstitial`, `mediaVisibilityResults` `[V tweet.yaml]`. Every scraper does `if "tweet" in tweet: tweet = tweet["tweet"]` `[V GDL:2253-2254, 2276-2277; RSSH utils.ts:362-364]`. **Forget this and you lose ~all sensitive-media posts.** |
| `TweetTombstone` | no tweet — skip |
| `TweetUnavailable` | has `reason`. GDL maps `NsfwViewerHasNoStatedAge` / `NsfwLoggedOut` → "NSFW", `Protected` → "protected" `[V GDL:1478-1485]` |
| `TweetPreviewDisplay` | subscriber-only paywalled preview; real content under `.tweet` but truncated `[V RSSH utils.ts:341-361]` |

Then:
* **Retweets:** `tweet.legacy.retweeted_status_result.result` (itself possibly `TweetWithVisibilityResults`
  → unwrap `.tweet` again). The **outer** tweet has no media; the media lives on the inner one.
  GDL copies `extended_entities`, `note_tweet`, `withheld_scope`, `full_text` inward from the retweet
  `[V GDL:2273-2301]`. **For XTV: dedupe by the inner `rest_id`** or the same image appears once per
  retweeter in the Following rail.
* **Quotes:** `tweet.quoted_status_result.result` — a second, separate tweet with its own media. GDL yields
  it as an additional item `[V GDL:2306-2320]`, gated behind `quoted: false` by default `[V docs]`.
  `[L]` For XTV default to **not** ingesting quoted media — it's someone else's post, surfaced twice.
* **Ads:** `itemContent.promotedMetadata` present ⇒ skip `[V GDL:2210-2215]`. Belt-and-braces on top of
  `includePromotedContent:false`.
* **Missing `core`:** GDL warns *"Received Tweet results without 'core' data … Retrying"* and re-requests
  the same page `[V GDL:2263-2271, added in commit 9a4e7103 "implement workarounds for empty 'core' data"
  (#8613)]`. This is a real transient. XTV: retry once, then skip the entry, don't abort the page.
* **User field moved.** `screen_name`/`name` are now under
  `tweet.core.user_results.result.core.{screen_name,name}`, not `…result.legacy.*`
  `[V GDL:2311-2313 reads `["core"]["user_results"]["result"]["core"]["screen_name"]`; V RSSH
  `getCoreUser` overlays `core` onto `legacy`]`. Also tolerate `user_result` (singular) alongside
  `user_results` `[V RSSH utils.ts:299-301]`. Older docs claiming `core.user_results.result.legacy.screen_name`
  (e.g. XARC §2.5) are **stale** — don't follow them.
* `sortIndex` is a string; keep it (it's the stable ordering key, and for Bookmarks it's the bookmark
  timestamp — §2a).

### 5.4 Cursors `[V]`

```json
{ "entryId": "cursor-bottom-1866561354846122412",
  "sortIndex": "1867231621095096312",
  "content": { "__typename": "TimelineTimelineCursor",
               "value": "DAABCgABGVKi5lE___oKAAIYbfYNcxrQLggAAwAAAAIAAA",
               "cursorType": "Bottom",
               "stopOnEmptyResponse": true,
               "displayTreatment": { "actionText": "Show more" } } }
```
`[V TREK x.ts `TimelineCursor`; V content.yaml `TimelineTimelineCursor`; V GDL:2197-2203]`

* `cursorType` ∈ `{Top, Bottom, ShowMore, ShowMoreThreads, Gap, ShowMoreThreadsPrompt}` `[V content.yaml
  `CursorType`]`. **XTV wants `Bottom`** (older content). `Top` is for polling for new posts.
* **Three places a bottom cursor can hide** — you must check all three:
  1. a top-level `cursor-bottom-*` entry in `TimelineAddEntries` `[V]`
  2. inside a `TimelineReplaceEntry` whose `entry.entryId` starts `cursor-bottom-` `[V GDL:2120-2124]`
  3. wrapped one level deeper as `content.itemContent` instead of `content` — GDL:
     `cursor = entry["content"]; if "itemContent" in cursor: cursor = cursor["itemContent"]`
     `[V GDL:2197-2200]`
* twscrape's generic version: find *any* object anywhere in the response with
  `cursorType == "Bottom"` and take `.value`, else fall back to `slice_info.next_cursor` (a plain string,
  used by Community endpoints) `[V TWS `_get_cursor`]`. Pragmatic; use as a last-resort fallback.
* **`stopOnEmptyResponse: false` means "keep going even though this page had nothing"** — GDL treats it as
  a signal to continue: `if not cursor.get("stopOnEmptyResponse", True): tweet = True` (i.e. pretend we got
  results) `[V GDL:2201-2202]`. Honour this flag.
* **Cursors are scoped to one timeline.** gallery-dl docs, verbatim: *"A `cursor` value from one timeline
  cannot be used with another."* `[V docs/configuration.rst `extractor.twitter.cursor`]` ⇒ XTV must key
  its saved cursors by `(channel, target)`, and invalidate them when the queryId rotates `[L]`.
* Cursor values are opaque, ~40-60 char base64-ish blobs. Never parse them.

---

## 6. "End of timeline" vs "transient empty page"

This is the hardest correctness problem in the whole app, and every scraper gets it slightly differently.

**Definitive stop conditions** `[V, composite]`:
1. **No bottom cursor found anywhere in the response** → real end. `[V GDL:2344-2346 `if not cursor …
   return`; V XARC §2.4 "Only the absence of a bottom cursor signals completion"]`
2. **Returned cursor == the cursor you sent** → real end (X is looping you). `[V GDL:2344
   `or cursor == variables.get("cursor")`]` — cheap, catches the "stuck at the same cursor" bug class
   `[V gallery-dl#9410]`.
3. **`TimelineTerminateTimeline` with `direction` `Bottom` or `TopAndBottom`** → authoritative end
   downward. `direction: "Top"` means *only* "stop polling upward" and must **not** end your backfill.
   `[V instruction.yaml enum `[Top, Bottom, TopAndBottom]`; V TREK where the Home response's instruction
   union is `(TimelineAddEntries | TimelineTerminateTimeline)` with `direction: 'Top'`]`
   `[!]` **Neither gallery-dl nor twscrape nor RSSHub handles this instruction at all** `[V — grep: absent
   from all three]`. XTV should, because it's the only *positive* end signal X gives you.
4. **No entries AND no cursor** → end. `[V GDL:2125-2128]`

**Not stop conditions:**
* A page with a cursor and zero tweet entries. **Keep going.** Tolerances actually shipped:
  - Bookmarks: **128** consecutive empty pages `[V GDL:1594 `stop_tweets=128`]`
  - Search: **3** `[V GDL:1631-1633, commit 16871c9e "always use '3' as 'search-stop' default" (#8613)]`
  - Home / UserMedia / List: **0** in gallery-dl (stops immediately) — `[L]` too aggressive for XTV
  - twscrape: **3** for every op, `empty_pages` counter reset on any non-empty page
    `[V TWS `_gql_items`: *"cursor exists → data may follow after empty/filtered pages (e.g. promo)"*,
    commit 206f0942 "fix: continue pagination past empty pages (#265, #247)"]`
* A rate-limit hit. Explicitly called out by a long-time user in gallery-dl#9476, verbatim title:
  *"[PSA] twitter rate limiting twice in a row does not mean there is no more media"*, body: *"Until now,
  I thought if you triggered the rate limit twice in a row, you had downloaded all the media of a profile
  … But today I did [receive more] for the first time, retroactively rendering all my previous scrapes …
  potentially incomplete."* `[V]` Also #9476's counterpart bug: *"triggers a rate limit loop after all
  media has been downloaded"* `[V gallery-dl#9503]` — the inverse failure.
* A page whose only entries are ads / who-to-follow / prompts.

**Recommended XTV policy** `[L]`:
```
consecutive_empty = 0
loop:
  page = fetch(cursor)
  if terminate_instruction.direction in {Bottom, TopAndBottom}: END
  new = extract_tweets(page)               # after ad/tombstone/dedupe filtering
  next = find_bottom_cursor(page)          # all three locations
  if next is None or next == cursor:       END
  if new is empty:
      consecutive_empty += 1
      threshold = 128 if channel == Bookmarks else 5
      if consecutive_empty > threshold:    END (report "gave up", not "complete")
  else:
      consecutive_empty = 0
  cursor = next
```
Plus gallery-dl's **count-ladder** trick, which is a genuinely clever unsticker: `extractor.twitter.limit`
accepts a list, e.g. `[40, 30, 20, 10, 5]`, and *"start with the first element as `count` parameter and
switch to the next element whenever no results are returned"* `[V docs/configuration.rst; V GDL:2075-2082,
2338-2342]`. A large `count` can produce empty pages that a smaller `count` doesn't. Worth porting.

**Distinguish "complete" from "gave up" in XTV's UI state.** Showing "that's everything" when you actually
hit an empty-page threshold is the bug that makes users think media is missing.

**Duplicates:** promoted entries, pinned entries, retweets of the same original, and quote-of-quote all
produce repeats. GDL ships a whole `unique` option for this `[V docs]`. XTV should dedupe on
`(inner tweet rest_id, media_key)` in the repository layer, not the UI `[L]`.

---

## 7. Cost & rate limits

* Rate-limit signals are per-response headers `x-rate-limit-limit`, `x-rate-limit-remaining`,
  `x-rate-limit-reset` (epoch seconds) `[V GDL:1911, TWS:197-199, XDOC "limits are shown per 15 minutes
  unless otherwise noted"]`.
* Buckets are **per operation**, not global: twscrape locks an account *per `queue`*, where
  `queue = op.split("/")[-1]` i.e. the operation name `[V TWS `_gql_items`, `QueueClient(self.pool, queue,…)`]`.
  ⇒ exhausting `UserMedia` does not necessarily block `Bookmarks` `[L, strong]`.
* `[?]` **The actual numeric limits for `Bookmarks` / `HomeLatestTimeline` / `UserMedia` /
  `ListLatestTweetsTimeline`.** I found no trustworthy figure and I am not going to invent one.
  **Test:** issue one request per op and log `x-rate-limit-limit` + `x-rate-limit-reset`; that header *is*
  the answer, per-bucket, no guessing needed. Do this once at XTV startup and cache it — then you can
  budget precisely.
* Conservative practice from the field, for a main account:
  - gallery-dl config advice that stopped bans: `"sleep-request": 30` `[V gallery-dl#8864 comment: *"I
    stopped getting banned when I set `sleep-request` to 30 seconds"*]`
  - XARC: 2–2.5 s between paginated requests, ×0.7–1.5 jitter, exponential backoff, 5-min cooldown after
    3 consecutive 429s, ≤5 retries `[XARC §2.3 — secondary]`
  - GDL backs off *before* exhaustion: `if remaining < 6 and remaining <= random.randrange(1,6):` wait
    `[V GDL:1911-1914]` — randomised so many clients don't all resume together. Nice pattern to copy.
* `[!]` **Ban risk is real and reported at low volumes.** gallery-dl#9476, verbatim: *"Be careful when
  hitting rate limits, one of my accounts got banned just for that and it wasn't even aggressive scraping,
  just one single media feed. Since then I run in extremely slow mode never hitting rate limit."* `[V]`
  For a main account, **treat any 429 as a bug in XTV's scheduler, not as flow control.** Budget from the
  headers and never intentionally hit the wall.

**Ban / auth error taxonomy** `[V TWS `_check_rep`]` — the single most useful triage table I found:

| signal | meaning | XTV response |
|---|---|---|
| `x-rate-limit-remaining == 0` && `reset > 0` | ordinary rate limit | sleep until `reset` |
| error **88** "Rate limit exceeded" **while `remaining > 0`** | *"Ban detected"* | **stop everything, tell the user** — this is not a rate limit |
| error **326** "Authorization: Denied by access control" | *"Ban detected"* | stop, surface to user |
| error **32** "Could not authenticate you" | session expired or banned | force WebView re-login |
| HTTP **403** with no `errors` body | session expired or banned | force re-login |
| error **336** "features cannot be null" | stale features | self-heal (§3.2) |
| error **131** "Dependency: Internal error" | transient; **ignore if `data` is present** | retry / partial-accept |
| HTTP **404** on a graphql path | almost always bad `x-client-transaction-id` | refresh ctid, retry ×3 (§1.4) |
| `content-type: text/html` && status ≥ 400 (`cf-ray` present) | Cloudflare / edge block | abort, long backoff |
| `"_Missing: No status found with that ID"` at HTTP 200 | deleted tweet | skip entry |

gallery-dl adds: message contains `"this account is temporarily locked"` → account lock (needs human
interaction on x.com), and `msg.lower().startswith("timeout")` → retryable `[V GDL:1930-1950]`.

---

## 8. Request budget for XTV (arithmetic, not measurement)

Per page you get ≤ `count` tweets, of which only a fraction carry media:
* Bookmarks: high media fraction (people bookmark media) `[L]`
* `UserMedia`: ~100 % by construction `[V]`
* `HomeLatestTimeline`: low — mostly text/replies `[L]`
* List timeline: whatever you curated `[V by construction]`

⇒ Priority for a *media* TV app should be **Bookmarks → List → UserMedia → HomeLatestTimeline**, which
inverts (b) and (d) from the brief. And `BookmarkSearchTimeline` with `filter:media`, if it works as
expected (§2a), would cut the Bookmarks channel's request count by whatever your non-media bookmark
fraction is `[L]`.

---

## 9. Cheaper / more stable routes — blunt verdicts

### Official API v2 — **worth a serious second look; I'd prototype it.**

The pricing model changed twice in 2026 and the old "$5 000/mo Pro" framing is dead.

* **Feb 6 2026:** pay-per-use became the default; Developer Console at `console.x.com`; legacy free-tier
  users got $10 vouchers `[V XDOC changelog, "Pay-Per-Use Launch (Feb 6, 2026)"]`. Basic/Pro are
  **closed to new signups**; the only self-serve option is pay-per-use `[secondary: multiple 2026 pricing
  write-ups; not on docs.x.com]`.
* **Apr 20 2026 — the important one:** *"Owned Reads now $0.001"* per resource `[V XDOC changelog entry
  dated Apr 16 2026, effective Apr 20 2026]`. Owned Reads = requests by **your own app for your own**
  posts, bookmarks, followers, likes, lists etc., across ~12 endpoints — including
  **`GET /2/users/{id}/bookmarks`** and the **reverse-chronological timeline**
  `[secondary: devcommunity announcement thread 263025, which WebFetch could not retrieve (403); the
  $0.001 figure and the effective date are confirmed on docs.x.com/changelog]`.
  Also in that change: `POST /2/tweets` $0.015 ($0.20 with a URL); Following/Likes/Quote-Posts removed
  from self-serve `[V XDOC changelog]`. Non-owned reads ~$0.005/post `[secondary]`.
* **Endpoints XTV would use** `[V docs.x.com/x-api/fundamentals/rate-limits]`:
  | endpoint | per-user limit |
  |---|---|
  | `GET /2/users/:id/bookmarks` | **180 / 15 min** (no app-only tier) |
  | `GET /2/users/:id/timelines/reverse_chronological` | **180 / 15 min** (no app-only tier) |
  `reverse_chronological` is *"Posts composed by a single user and the accounts they follow"* — i.e. the
  Following tab equivalent `[L, from the endpoint's own name/description]`.
* **v2 *does* give playable video URLs.** `media.fields=variants` returns an array of
  `{bit_rate, content_type, url}`; docs verbatim: *"Each media object may have multiple display or playback
  variants, with different resolutions or formats."* Also `preview_image_url`, `duration_ms`, `height`,
  `width`, `alt_text`, `type ∈ {animated_gif, photo, video}`; `url` *"is only returned for photos; it is
  null for videos"* `[V docs.x.com/x-api/fundamentals/data-dictionary, Media object]`.
  Request shape: `?expansions=attachments.media_keys&media.fields=variants,preview_image_url,duration_ms,alt_text`
  `[V, corroborated by devcommunity threads]`. **This kills the usual "official API can't do video"
  objection.**
* **Pagination:** `meta.next_token` → `pagination_token`; *"When there are no more results, `next_token`
  is omitted."* `[V docs.x.com/x-api/fundamentals/pagination]` — vastly simpler than URT cursors: no
  instruction walking, no tombstones, no `TweetWithVisibilityResults`, no queryId rotation, no
  transaction-id, **no ban risk to your main account**.
* **Hard limits that hurt XTV** `[secondary but consistently reported]`:
  - **Bookmarks caps at the 800 most recent** — *"800 most recent bookmarks -- confirmed by X engineering,
    not a bug"*, and *"Pagination stops returning `meta.next_token` after 2-3 pages"*
    `[XARC §1.1, citing devcommunity threads 169433 and 257339]`. **`[?]` I could not confirm 800 on
    docs.x.com** (the bookmarks intro page doesn't state it). **Test:** page it and count.
  - **No bookmark folders** `[XARC]`.
  - **No per-creator media tab.** There is no v2 equivalent of `UserMedia`. `GET /2/users/:id/tweets` is a
    *non-owned* read (≈$0.005/post, 5× the owned-read price) and returns all posts, not media-only.
* **Verdict:** For channel **(a) Bookmarks up to 800** and channel **(b) Following**, v2 at $0.001/resource
  is *cheaper in risk* than anything GraphQL, needs no reverse engineering, won't break every 3 weeks, and
  cannot get your main account banned. At 800 bookmarks + a few thousand timeline posts you are looking at
  single-digit dollars. For **(c) "more from @creator" media** you need GraphQL regardless.
  ⇒ **Recommendation `[L]`: hybrid. v2 for (a) and (b); GraphQL/session for (c) and deep-bookmark
  backfill past 800.** At minimum, budget a day to prototype v2 before committing the whole app to a
  reverse-engineered surface. `[?]` Whether pay-per-use requires a paid card on file with no free
  allowance — verify in `console.x.com` before designing around it.

### Nitter and forks — **dead. Don't.**
Upstream `zedeus/nitter` was discontinued Feb 2024 when X removed guest accounts; the last guest tokens
expired Feb 26 2024 `[V multiple 2024 sources]`. 2026 status reports: *"functionally dead in 2026 for most
readers … public instances keep dropping"*, surviving mirrors *"don't offer consistent timeline viewing"*
`[secondary, 2026 status write-ups]`. Structurally it cannot help XTV anyway: Nitter has **no concept of
your bookmarks or your Following feed** — those require *your* session, which is precisely what Nitter was
built to avoid.

### RSS bridges (RSSHub, rss-bridge) — **strictly worse than calling X yourself.**
RSSHub's X routes require **`TWITTER_COOKIE="auth_token=xxx; ct0=xxx;"`** — the *same* credentials, with
the same ban exposure, handed to a third-party server `[V RSSHub docs/discussions]`. And:
* **No bookmarks route.** RSSHub's twitter dir has `home.ts`, `home-latest.ts`, `media.ts`, `likes.ts`,
  `list.ts`, `keyword.ts`, `user.ts`, `tweet.ts`, `trends.ts` — **no `bookmarks.ts`** `[V, directory
  listing]`. Your primary v1 channel isn't available.
* RSS flattens away exactly what XTV needs: media variants, cursors, `sortIndex`, resumability.
* You'd inherit RSSHub's bugs on top of X's. (Its `UserMedia` handler only reads `profile-grid-0` — §2c.)
RSSHub's source is however an **excellent reference implementation** — its `gql-id-resolver.ts` is the
cleanest queryId self-healing code I found (§1.5a). Read it; don't deploy it.

### Third-party paid scraper APIs
Out of scope for a solo TV app, but note RSSHub supports a `thirdPartyApi` base-URL swap for a subset of
ops (`UserByScreenName, UserByRestId, UserTweets, UserTweetsAndReplies, ListLatestTweetsTimeline,
SearchTimeline, UserMedia`) `[V RSSH constants.ts `thirdPartySupportedAPI`]` — note **`Bookmarks` and
`HomeLatestTimeline` are absent**, because they need *your* session. That's the structural point: the two
channels you care most about can never be outsourced.

---

## 10. Open unknowns and the exact experiment for each

| # | unknown | experiment |
|---|---|---|
| 1 | Numeric rate limits per op | one request each to `Bookmarks`/`HomeLatestTimeline`/`UserMedia`/`ListLatestTweetsTimeline`; log `x-rate-limit-limit`, `-remaining`, `-reset`. The headers *are* the answer. |
| 2 | Max accepted `count` per op | binary-search `count` ∈ {20,40,50,100,200}; watch for 400, silently-clamped page sizes, and empty-page rate. `count:100` is attested for Bookmarks `[V #3172]`. |
| 3 | Does `main.<hash>.js` alone contain all 4 queryIds? | fetch it, count regex matches for the 4 names; if short, implement twscrape's multi-bundle walk. |
| 4 | Is `x-client-transaction-id` enforced on these 4 ops? | send a request *without* it; a 404 (not 401/403) ⇒ enforced. Repeat per op — enforcement is per-op and can be switched on any day. |
| 5 | `BookmarkSearchTimeline` `variables` shape | devtools on `x.com/i/bookmarks` search box. Big potential win (server-side `filter:media`). |
| 6 | Does v2 bookmarks really cap at 800? | page `GET /2/users/:id/bookmarks` with `max_results=100` until `next_token` disappears; count. |
| 7 | Encoded GET URL length with 39 features + 8 toggles | measure; if near ~8 KB, move to POST with `{variables, features, queryId}` body (browser does this for HomeTimeline `[V TREK]`). |
| 8 | `HomeLatestTimeline` history depth | page to exhaustion; record tweet count and oldest `created_at`. |
| 9 | `UserMedia` depth before stall/rate-limit | page one high-volume account; log oldest `created_at` + rate-limit headers per page. |
| 10 | Do stale queryIds still resolve *today*? | fire the 2025-10-01 `Bookmarks` id `ire7TB3NNzZOIa2SeD8pLA` with a period-appropriate 34-flag features set. Strong indirect evidence says yes (§1.3); confirming it tells you how much slack your pinned fallback buys. |
| 11 | Correct `fieldToggles` values | all 8 names are `[V]`; the booleans are `[L]`. Flip each and diff the payload. |
| 12 | pay-per-use free allowance / card requirement | `console.x.com`. |
