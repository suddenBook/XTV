# XTV — Extracting playable/displayable media from an X tweet object

Researched 2026-07-26. Evidence labels: **[V]** = verified against a primary source (source code / typed
schema / my own live HTTP probe), **[L]** = likely / inferred with stated reasoning, **[U]** = unknown,
needs a live authenticated test.

## Source inventory (what I actually read)

| Ref | Source | Retrieved |
|---|---|---|
| `YTDLP` | `yt_dlp/extractor/twitter.py` @ master (1767 lines) | raw.githubusercontent, 2026-07-26 |
| `GDL` | `gallery_dl/extractor/twitter.py` @ master (2488 lines) | raw.githubusercontent, 2026-07-26 |
| `GDLTEST` | `gallery-dl/test/results/twitter.py` (839 lines) — live-verified URL fixtures | same |
| `GDLDOC` | `gallery-dl/docs/configuration.rst` `extractor.twitter.*` | same |
| `SPEC` | `fa0311/twitter-openapi` `dist/docs/openapi-3.0.yaml` (12496 lines) — types generated from observed real responses | same |
| `ALGO` | **`twitter/the-algorithm`** `visibilitylib/.../rules/Action.scala`, `generators/TombstoneGenerator.scala`, `tweetypie/.../VisibilityResultToFilteredState.scala` — X's own open-sourced visibility library | same |
| `PROBE` | My own unauthenticated `curl` probes against `pbs.twimg.com` / `video.twimg.com`, plus `cdn.syndication.twimg.com/tweet-result` (the public endpoint `YTDLP` documents) for 5 real tweets | 2026-07-26 |

`PROBE` tweets used: `1790637656616943991` (video), `1839019491835129889` (animated_gif),
`1577924293023133696` (photo + video), plus media IDs `EqcpviCVoAAG-QG`, `FeXpxOyaYAA9L88`,
`GxC2eRJWAAAH_NM` from `GDLTEST`. I did **not** log in.

**Maintenance signal [V]**: both extractors have 2026 commits (GDL through 2026-04-04, YTDLP through
2026-06-18) but **none of them touch the media-extraction paths below**. 2026 churn was articles, cards,
pagination, `view_count`, error handling. The shapes in this document are the stable part of the API.

---

## 0. Where media lives — the container

### 0.1 Envelope
**[V SPEC 3335-3349 `TweetUnion`; YTDLP 1077-1120 `_graphql_to_legacy`; GDL 1474-1486]**
GraphQL tweet results are a `__typename`-discriminated union. Unwrap in this order:

```
data.<timelineRoot>… → itemContent.tweet_results.result          # timelines
data.tweetResult.result                                          # TweetResultByRestId
  ├─ __typename == "Tweet"                        → use as-is
  ├─ __typename == "TweetWithVisibilityResults"   → real tweet at  .tweet     ← MUST unwrap
  ├─ __typename == "TweetTombstone"               → .tombstone       (no media)
  ├─ __typename == "TweetUnavailable"             → .reason          (no media)
  └─ __typename == "TweetPreviewDisplay"          → .tweet + .limited_action_results (paywalled preview)
```
`GDL 1475-1476` and `2253-2254` both do the same defensive `if "tweet" in tweet: tweet = tweet["tweet"]`
without checking `__typename` — do that, it is cheaper and handles both.

Full `__typename` enum **[V SPEC 3384-3412]**: `Tweet`, `TweetWithVisibilityResults`, `TweetTombstone`,
`TweetUnavailable`, `TweetPreviewDisplay`, `User`, `UserUnavailable`, `TimelineTweet`,
`TimelineTimelineItem`, `TimelineTimelineModule`, `TimelineTimelineCursor`, `TimelineTombstone`,
`ContextualTweetInterstitial`, `TimelinePrompt`, `TimelineMessagePrompt`, `TimelineUser`,
`TimelineCommunity`, `TimelineTrend`, `TimelineNotification`, … (+ Community* variants).

### 0.2 The media array
**[V GDL 157-160; YTDLP 1348-1349; SPEC 983-990 `ExtendedEntities`]**

```
tweet.legacy.extended_entities.media[]     ← THE ONLY container XTV should read
```

- **`legacy.entities.media` is NOT used by either scraper for media extraction.** `GDL 157` tests only
  `"extended_entities" in data`. `YTDLP 1349` traverses only `'extended_entities','media'`. **[V]**
  Reason **[L]**: `entities.media` is the legacy truncated-to-1-item view; `extended_entities.media`
  carries all up to 4 items and is the only one with `video_info`. Ignore `entities.media` entirely.
- **There is no "modern" replacement container for normal tweets.** **[V]** The modern GraphQL response
  still nests the classic v1.1 `legacy` blob. `SPEC` models `ExtendedEntities.media` as
  `MediaExtended[]`, which is `Media` plus `mediaStats.viewCount`.
- Media can also appear in **three secondary containers** that XTV may want, listed by value:
  * `tweet.card.legacy.binding_values.unified_card.string_value` → JSON string → parse →
    `.media_entities` (a **dict** keyed by media id, values are the same `Media` shape).
    **[V GDL 347-351, YTDLP 1314-1317]** `GDL` feeds `.values()` straight into the same `_extract_media`.
    Since 2025-12-30 `YTDLP` deliberately ignores non-video unified_card items (commit #15431).
  * `tweet.article.…media_entities` **[V GDL 392]** — X "Articles"; added Feb–Apr 2026 (GDL #8995).
  * `tweet.legacy.retweeted_status_result.result.legacy.extended_entities` — **retweets do not copy
    media up**. `GDL 2294-2297` explicitly hoists `extended_entities` from the retweet's legacy onto
    the outer tweet when the outer lacks it. **[V]** XTV must do this or it will silently drop most
    of a Following timeline.
  * `quoted_status_result.result.legacy.extended_entities` — `YTDLP 1349` searches
    `(None,'quoted_status')`, i.e. self *and* quoted. **[V]** Decide deliberately whether XTV counts
    quoted-tweet media as items (recommend: no, to avoid dupes; `GDL` has a `quoted` toggle).

### 0.3 `Media` object — full field list
**[V SPEC 1319-1388 (`Media`) / 1411-1481 (`MediaExtended`)]** Required fields marked `*`:

```
*type            : enum { "photo" | "video" | "animated_gif" }     ← EXACTLY these three
*id_str          : string ^[0-9]+$
*media_key       : string        (GraphQL only; ABSENT from syndication [V PROBE])
*media_url_https : uri           (pbs.twimg.com — poster frame for video/gif)
*url             : uri           (t.co shortlink)
*expanded_url    : uri           (https://x.com/<user>/status/<id>/photo|video/<n>)
*display_url     : string        ("pic.x.com/…")
*indices         : int[2]        (offsets into full_text — strip these for the caption)
*sizes           : { thumb,small,medium,large : { w,h,resize:"fit"|"crop" } }
*original_info   : { width:int, height:int, focus_rects?: [{x,y,w,h}] }
 ext_media_availability : { status: "Available"|"Unavailable", reason?: string }   (*on Media)
 video_info      : { aspect_ratio:[int,int], duration_millis?:int, variants:[…] }
 ext_alt_text    : string        ← alt text / description
 sensitive_media_warning : { adult_content?:bool, graphic_violence?:bool, other?:bool }
 additional_media_info   : { monetizable:bool, title?, description?, embeddable?, source_user? }
 source_status_id_str, source_user_id_str : string   (media lifted from another tweet)
 allow_download_status   : { allow_download: bool }
 features, grok_post_id, mediaStats.viewCount, media_results
```
Note `ext_media_availability` is required on `Media` but **not** on `MediaExtended` **[V SPEC 1471-1481]**
— treat it as optional everywhere.

### 0.4 `include_ext_*` params: you do NOT need them on GraphQL
**[V GDL 1361-1395 vs 1714/1722/1770; PROBE]** This is a common misconception. `GDL`'s big `self.params`
dict (`include_ext_media_availability`, `include_ext_sensitive_media_warning`, `include_ext_alt_text`,
`include_ext_limited_action_results`, `tweet_mode=extended`, …) is `.copy()`-ed **only** for `/1.1/*` and
`/2/*` REST endpoints. GraphQL calls send **only** `variables`, `features`, `fieldToggles`. Yet `GDL`
reads `ext_media_availability` and `sensitive_media_warning` off GraphQL timeline results. Corroborated
by `PROBE`: the unauthenticated syndication endpoint returned `ext_media_availability` with no such
param. **Conclusion: if XTV uses GraphQL (UserMedia / Bookmarks), send no `include_ext_*` at all.**

**Naming trap [V]**: `ext_sensitive_media_warning` **is not a JSON field**. The *parameter* is
`include_ext_sensitive_media_warning`; the *field* is `sensitive_media_warning`. Same for
`include_ext_media_availability` → `ext_media_availability` (this one does keep the `ext_` prefix), and
`include_ext_alt_text` → `ext_alt_text`.

---

## 1. Photos

### 1.1 URL construction
**[V GDL 262-272]** — the exact algorithm, worth copying:

```python
url = media["media_url_https"]          # "https://pbs.twimg.com/media/GxC2eRJWAAAH_NM.jpg"
if url[-4] == ".":                      # bare form, ends .jpg/.png
    base, _, fmt = url.rpartition(".")
    base = f"{base}?format={fmt}&name="  # → ".../GxC2eRJWAAAH_NM?format=jpg&name="
else:                                   # already query form ".../ID?format=jpg&name=small"
    base = url.rpartition("=")[0] + "="  # strip the existing name value
final = base + size                     # size = "orig" | "4096x4096" | "large" | …
```

Robust restatement for Kotlin: split `media_url_https` on the **last** `.`; the extension becomes
`format`, the stem becomes the path. Handle the pre-parameterised variant too (X returns it for
`card_img` and article media). Do **not** hardcode `format=jpg`.

**`format` must match the stored format [V PROBE]** — on a JPEG-origin media id,
`?format=webp&name=orig` → **404** and `?format=png&name=orig` → **404**. Only the real stored format
works. `GDLTEST 651` shows X serves both: `card_img/…?format=(jpg|png)&name=orig`. This is exactly why
`GDL` derives `format` from the extension instead of guessing.

**`name` is mandatory in the query form [V PROBE]**: `?format=jpg` with no `name` → **404**.

### 1.2 Size variants — measured
**[V PROBE]** Byte sizes for `https://pbs.twimg.com/media/<id>?format=jpg&name=<n>`:

| `name=` | `EqcpviCVoAAG-QG` | `FeXpxOyaYAA9L88` (src 1800×2000) | `GxC2eRJWAAAH_NM` |
|---|---|---|---|
| `small` (≤680) | 32 993 | 81 047 | 58 390 |
| `medium` (≤1200) | 94 257 | 264 770 | 160 878 |
| `large` (≤2048) | 247 873 | 663 422 | **517 770** |
| `4096x4096` | 247 873 | 663 422 | **1 879 963** |
| `orig` | 247 873 | 663 422 | **1 879 963** |
| `900x900` | 51 122 | 141 896 | 94 302 |
| `360x360` | 13 981 | 26 574 | 20 136 |
| bare `<id>.jpg` | **94 257** | **264 770** | **160 878** |
| `<id>.jpg:orig` | 247 873 | 663 422 | 1 879 963 |
| `99999x99999` | — | — | **404** |

Readings:
- **Bare `media_url_https` == `medium` == max 1200px [V PROBE, 3/3 byte-identical].** Rendering
  `media_url_https` verbatim gives you a 1200px image on a 4K panel. Always append a size.
- **`large` caps at 2048; `orig`/`4096x4096` are the uncapped tier [V PROBE].** They diverge only when
  the source exceeds 2048px (`GxC2eRJWAAAH_NM`: 518 KB vs 1.88 MB, 3.6×). For the two samples whose
  source was ≤2048, `large == 4096x4096 == orig` exactly.
- **`orig` and `4096x4096` were byte-identical on 3/3 samples [V PROBE].** `4096x4096` is a bounded
  resize, `orig` is nominally the unresized upload. X resizes uploads to a 4096px bound, so they
  coincide in practice.
- **Arbitrary `WxH` is rejected [V PROBE]**. The accepted set is an allowlist. Known-good
  **[V GDLDOC `extractor.twitter.size` + PROBE]**: `orig`, `4096x4096`, `large`, `medium`, `small`,
  `900x900`, `360x360` (`thumb` also exists per `sizes`).
- **Old colon suffixes STILL WORK [V PROBE + GDLTEST 804]**: `<id>.jpg:orig` and `<id>.jpg:large`
  both 200 and byte-identical to the query form. Use the query form anyway — it is what `GDL` emits and
  what X's own web client uses, so it is the better-tested path.
- **Is `orig` reliable?** 3/3 in my probe, and it is `GDL`'s default first choice **[V GDLDOC]**. But
  `GDL` ships a 4-deep fallback chain `orig → 4096x4096 → large → medium → small` **[V GDL 79-80]**
  because `orig` can 404; maintainer confirms 404 triggers immediate fallback while HTTP/SSL errors get
  retried **[V discussion #5034]**. **[L]** Occasional `orig` 404s are real but rare. Implement the
  fallback chain — it is ~10 lines.

### 1.3 Recommendation for a TV client
**[L — reasoning, not verified against a device]**

| Surface | Use | Why |
|---|---|---|
| Grid thumbnail | `name=small` (≤680) or `900x900` | 680px is already > a 5-across 4K grid cell; ~30-80 KB |
| Fullscreen on 1080p | **`name=large`** | 2048 cap ≥ 1080p in both axes; ~3.6× less data than `orig`; bitmap ≤ 2048×2048×4 = **16 MB** |
| Fullscreen on 4K | `name=4096x4096`, fallback `orig` → `large` | uncapped tier needed to exceed 2048px |

Prefer **`4096x4096` over `orig` as the 4K primary [L]**: it is byte-identical in practice but *bounded*,
which guarantees the decoded bitmap can't exceed 4096×4096×4 = **67 MB**. On a memory-constrained
Android TV box an unbounded `orig` decode is an OOM risk. Also set Coil/Glide to downsample to the
actual view size — never decode 4096² into a 1080p ImageView.

### 1.4 `ext_media_availability`
**[V SPEC 974-982]** `{ status: "Available" | "Unavailable", reason?: string }`. **[V PROBE]** When
available, `reason` is **absent** and the object is just `{"status":"Available"}` — do not require
`reason`.

**[V GDL 221-227]** Handling:
```python
if media.get("ext_media_availability", {}).get("status") == "Unavailable":
    warn("Media unavailable (%s - '%s')", tweet_id, ext.get("reason"))
    continue      # unless the 'unavailable' option is on
```
**[V GDLDOC `extractor.twitter.unavailable`]** — "Try to download media marked as `Unavailable`, e.g.
**`Geoblocked`** videos." So `reason == "Geoblocked"` is a real observed value. **[L]** Geoblocked media
sometimes still downloads from a permitted network, which is why `GDL` makes skipping optional.
Other `reason` values: **[U]** — the field is an untyped `string` in `SPEC`; do not switch on an
enum you invented. Treat `status != "Available"` as the gate and surface `reason` verbatim in the UI.

Note the syndication endpoint uses a **different, lowercased** shape:
`video.mediaAvailability.status == "available"` **[V PROBE]**. Not relevant if XTV uses GraphQL, but
don't cross-wire them.

### 1.5 `original_info` for the grid's aspect logic
**[V SPEC 1482-1494]** `{ width:int, height:int (both required), focus_rects?: [{x,y,w,h}] }`.
**[V GDL 251-252, 278-279]** read as `media["original_info"].get("width", 0)` — i.e. **the object is
assumed present but the ints are not**. Default to 0 and guard against divide-by-zero.

**[V PROBE]** Real values: photo `1800×2000` with 5 `focus_rects`
(`1800×1008`, `1800×1800`, `1754×2000`, `1000×2000`, `1800×2000` — the 16:9/1:1/4:5/1:2/full crop
candidates, `x`/`y` offsets included). Video/gif posters had `focus_rects: []`.

**Aspect for the grid**: use `original_info.width / original_info.height` for photos. **[V PROBE]** For
video/animated_gif, `video_info.aspect_ratio` is exactly `original_info` reduced by GCD
(`886×876 → [443,438]`, GCD 2; `1696×768 → [53,24]`, GCD 32), so either works — but see §2.4:
`original_info` is **not** the served pixel size for video.

`focus_rects` is a free win for a TV grid: if you need a 16:9 cell, crop to the rect whose ratio is
closest to 16:9 instead of centre-cropping and decapitating people. **[L]** — the semantics (X's
saliency crop hints) are inferred from the values, not documented in any source I read.

---

## 2. Videos

### 2.1 `video_info` shape
**[V SPEC 1561-1587]**
```
video_info:
  aspect_ratio    : [int,int]   *required
  duration_millis : int          OPTIONAL  ← absent for animated_gif [V PROBE]
  variants        : [ … ]       *required
variant:
  url          : uri     *required
  content_type : string  *required
  bitrate      : int      OPTIONAL  ← ABSENT on the HLS variant [V PROBE]
```

### 2.2 The ladder — real captured data
**[V PROBE, tweet `1790637656616943991`]**
```json
"video_info": {
 "aspect_ratio": [443,438],
 "duration_millis": 15488,
 "variants": [
  {"content_type":"application/x-mpegURL",                        "url":".../amplify_video/1790637589910654976/pl/osiKl6ALz2B8l9Cw.m3u8?tag=14&v=cfc"},
  {"bitrate":288000, "content_type":"video/mp4","url":".../amplify_video/1790637589910654976/vid/avc1/272x270/du7PkQofwIDrjAEz.mp4?tag=14"},
  {"bitrate":832000, "content_type":"video/mp4","url":".../amplify_video/1790637589910654976/vid/avc1/364x360/MhZUmBoG84GGC0fb.mp4?tag=14"},
  {"bitrate":2176000,"content_type":"video/mp4","url":".../amplify_video/1790637589910654976/vid/avc1/728x720/kF5AeH6MK-1qDlBg.mp4?tag=14"}
 ]}
```
Second sample (`1577924293023133696`, an `ext_tw_video`) is structurally identical:
`content_type:"application/x-mpegURL"` first with **no `bitrate`**, then mp4 at 632k/950k/2176k with
`/pu/vid/320x354/`, `/480x532/`, `/720x800/`, all `?tag=12`.

**Established facts:**
- `content_type` values: **`"video/mp4"`** and **`"application/x-mpegURL"`** **[V PROBE ×2]** (note the
  capital `URL`; compare case-insensitively).
- **The HLS variant is the one that lacks `bitrate` [V PROBE ×2]**, and it is **variant[0]**. Never sort
  by `bitrate` without defaulting the missing key — `GDL 236-239` does
  `max(variants, key=lambda v: v.get("bitrate", 0))`, which is precisely a "pick best mp4, ignore HLS"
  selector.
- `YTDLP 46` discriminates on the **URL** (`'.m3u8' in variant_url`), not `content_type`. **[L]** Both
  work; checking both is safest.
- mp4 URLs embed resolution in the path: `/vid/avc1/<W>x<H>/`. `YTDLP 87-93`
  (`_search_dimensions_in_video_url`) extracts it with `r'/(?P<width>\d+)x(?P<height>\d+)/'`. **[V]**
  This is the **only** reliable source of the served resolution — use it to pick the variant matching
  the panel instead of blindly taking max bitrate.
- Path prefixes seen: `/amplify_video/<mediaId>/…` (promoted/Media-Studio), `/ext_tw_video/<mediaId>/pu/…`
  (normal uploads), `/tweet_video/<id>.mp4` (GIFs). **[V PROBE + GDLTEST 349, 371]**

### 2.3 mp4 vs HLS — what XTV should play
**[V PROBE]** The HLS master for the same video:
```
#EXT-X-VERSION:6
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-MEDIA:NAME="Audio",TYPE=AUDIO,GROUP-ID="audio-32000",AUTOSELECT=YES,URI="/amplify_video/…/pl/mp4a/32000/….m3u8"
#EXT-X-MEDIA:… audio-64000 … audio-128000
#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=191874,BANDWIDTH=205175,RESOLUTION=272x270,CODECS="mp4a.40.2,avc1.4D400D",AUDIO="audio-32000"
/amplify_video/…/pl/avc1/272x270/OGg6bjyy9Mm23vdi.m3u8
#EXT-X-STREAM-INF:…RESOLUTION=364x360,CODECS="mp4a.40.2,avc1.4D4015",AUDIO="audio-64000"
#EXT-X-STREAM-INF:…RESOLUTION=728x720,CODECS="mp4a.40.2,avc1.64001F",AUDIO="audio-128000"
```
- **Same resolution ladder as the progressive mp4s** (272×270 / 364×360 / 728×720) — HLS buys no extra
  quality here. **[V PROBE, n=1]**
- **Rendition URIs are absolute *paths*, not full URLs.** Must be resolved against
  `https://video.twimg.com`. **[V PROBE]** ExoPlayer does this correctly; a hand-rolled parser will not.
- Child playlist: `#EXT-X-PLAYLIST-TYPE:VOD`, `#EXT-X-TARGETDURATION:4`, `#EXT-X-MAP:URI=…/0/0/728x720/….mp4`
  + `.m4s` fMP4 segments. **[V PROBE]** Standard fMP4 HLS; ExoPlayer/Media3 handles it natively.
- `YTDLP 1271` sets `'_format_sort_fields': ('res','proto:m3u8','br','size')` — it **prefers HLS at equal
  resolution** for compatibility (yt-dlp issue #8117) and notes `# http format codec is unknown`. **[V]**

**Recommendation for XTV [L]:** default to the **progressive mp4** whose `/WxH/` best fits the panel.
Reasons: single request, instant start, trivial `Range`-based seeking, no manifest parse, and the HLS
ladder tops out at the same resolution. Keep the `.m3u8` as a fallback for (a) any tweet where no
`video/mp4` variant exists, (b) very long videos if ABR matters. Media3 `DefaultMediaSourceFactory`
picks `ProgressiveMediaSource` vs `HlsMediaSource` automatically from the extension, so you can just
hand it the chosen URL.

### 2.4 `original_info` LIES about video resolution
**[V PROBE]** `original_info` = `886×876` but the largest mp4 variant is `728×720`. For the GIF,
`original_info` = `1696×768` while the actual decoded mp4 is **`1280×578`** (confirmed with `ffprobe`).
Corroborated by an independent user report **[GDL issue #9191, 2026-03-05: metadata says 2160×2160,
actual video is 1080p]** — no maintainer reply, so treat the *cause* as **[L]**: `original_info`
describes the **source upload**, the variants are transcodes.

Consequences for XTV:
- Use `original_info` / `aspect_ratio` **only** for layout aspect (they agree, and the transcode
  preserves DAR to within rounding: 886/876 = 1.011 vs 728/720 = 1.011).
- Never display `original_info.width×height` as "quality", and never use it to pick a variant. Parse
  `/WxH/` out of the chosen mp4 URL instead.

### 2.5 Auth, headers, and expiry — the important one
**[V PROBE — direct HTTP, no cookies, no Referer, no User-Agent]**

```
GET https://video.twimg.com/amplify_video/…/vid/avc1/728x720/kF5AeH6MK-1qDlBg.mp4?tag=14
  Range: bytes=0-1023   →  206, 1024 bytes            (with -H "User-Agent:" i.e. UA suppressed)
GET  …same URL with ?tag=14 REMOVED                   →  206
GET  …same URL with ?tag=99 (wrong tag)               →  404
HEAD …?tag=14 →
   HTTP/2 200
   content-type: video/mp4
   content-length: 1404023
   expires: Sun, 02 Aug 2026 14:31:48 GMT      (7 days out)
   cache-control: public, max-age=604800       (7 days)
   accept-ranges: bytes
   access-control-allow-origin: *
   x-cache: HIT
   (no Set-Cookie, no signature/token query params)
```

**Answers:**
- **No cookie, no `Referer`, no `Authorization`, no token, no User-Agent required.** **[V PROBE]**
  Corroborated structurally: `YTDLP` sets `http_headers` on Twitter formats in exactly one place —
  line 1705/1736, and that is the **Spaces** extractor (`live_video_stream/status` HLS), not tweet
  video. Tweet mp4/HLS formats get **no** headers at all. **[V YTDLP 42-62, 1264-1272]**
- **URLs are NOT signed and NOT session-bound. [V PROBE]** There is no signature parameter; the only
  query param is an opaque `tag`; `cache-control: public, max-age=604800`. Protection is
  URL-obscurity only. Independently corroborated by ODU WS-DL's 2022 analysis ("no HTTP Cookies are
  required… protected only through their opaque URLs").
  → **XTV can cache a video URL for an hour. It can cache it for days.** **[V]** Tie cache TTL to your
  tweet-object TTL, not to any imagined URL expiry. Widely-repeated blog claims that X video URLs are
  "short-lived signed URLs tied to the browser session" are **false** — I checked.
- **`accept-ranges: bytes` → ExoPlayer seeking/scrubbing works. [V PROBE]**
- **`?tag=N`: pass through verbatim, never rewrite.** Present → works; absent → works; *wrong* → 404.
  **[V PROBE]** (cobalt issue #1486 reports 403s from the tag param; I could not reproduce — I got
  206 either way. **[L]** that report is stale or region/edge-specific.) There is zero upside to
  stripping it and one clear downside to mangling it.
- HLS master and child playlists are equally unauthenticated and also work with the query stripped.
  **[V PROBE]**

### 2.6 Is HLS ever the only option?
**[U — needs live test]** In both `PROBE` samples mp4 variants existed. I found no primary-source
evidence of an `extended_entities.media` entry with an HLS-only `variants` array. Circumstantial:
`GDL 236-239` would silently save a `.m3u8` text file in that case and there is no issue in
`mikf/gallery-dl` reporting that, which suggests it is rare or nonexistent for ordinary tweets.

What *is* certain: **live broadcasts and Spaces are a different code path entirely and never appear in
`extended_entities.media`.** **[V YTDLP 1291-1308]** They surface as **cards** —
`card.name` ∈ {`periscope_broadcast`, `broadcast`, `audiospace`, `player`} — and resolve via separate
endpoints (`live_video_stream/status/<media_key>`, `AudioSpaceById`), are HLS-only, and *do* need
`Referer: https://twitter.com/` **[V YTDLP 1705-1719]**. **Recommendation: XTV should skip cards
entirely in v1** — read-only media browsing of photos/videos/GIFs from `extended_entities.media` only.
That sidesteps live, Spaces, vmap/amplify, and DRM-adjacent surfaces in one decision.

*Experiment to close the HLS-only unknown:* iterate a few hundred tweets from your own timeline and log
any `video_info` where `not any(v["content_type"] == "video/mp4" for v in variants)`. Also specifically
test a >10-minute video (X premium allows up to 4h) and a Media-Studio `amplify_video` post.

---

## 3. Animated GIFs

**[V PROBE, tweet `1839019491835129889` + `ffprobe` on the actual file]**
```json
{ "type": "animated_gif",
  "media_url_https": "https://pbs.twimg.com/tweet_video_thumb/GYWCeZAaMAQ32uh.jpg",
  "original_info": {"width":1696,"height":768,"focus_rects":[]},
  "video_info": {
    "aspect_ratio":[53,24],
    "variants":[{"bitrate":0,"content_type":"video/mp4",
                 "url":"https://video.twimg.com/tweet_video/GYWCeZAaMAQ32uh.mp4"}]
  }}
```
`ffprobe` on that mp4: **exactly one stream** — `codec_type=video, codec_name=h264, 1280×578,
nb_frames=8, duration=0.800000`. **No audio stream at all.** **[V PROBE]**

Confirmed properties:
- **They are silent mp4s, not GIFs. [V PROBE + GDLTEST 355-362]** (`GDLTEST` expects
  `"https://video.twimg.com/tweet_video/GYWCeZAaMAQ32uh.mp4"`, `extension: mp4`,
  `type: animated_gif`.)
- **Exactly one variant**, `content_type: "video/mp4"`, **`bitrate: 0` (present but zero)**. No HLS
  variant. **[V PROBE]**
- **`duration_millis` is ABSENT** from `video_info` for GIFs (only `aspect_ratio` + `variants`).
  **[V PROBE]** ← Your model must make this nullable or you will NPE on every GIF.
- URL shape `https://video.twimg.com/tweet_video/<ID>.mp4` — **no `?tag=`**, no `/vid/WxH/` segment, so
  `_search_dimensions_in_video_url` finds nothing. **[V PROBE]** Get display aspect from
  `video_info.aspect_ratio` / `original_info`.
- Poster at `https://pbs.twimg.com/tweet_video_thumb/<ID>.jpg` (note: `tweet_video_thumb`, distinct from
  `ext_tw_video_thumb` and `amplify_video_thumb`). **[V PROBE]**
- Typically sub-second and a handful of frames (0.8 s / 8 frames here) — **looping is not cosmetic, it
  is required** or the item flashes and advances.

**Detection [V]:** `media.type == "animated_gif"`. That is authoritative — it is a closed 3-value enum
in `SPEC 1366-1371`, and `YTDLP 1349` filters video candidates with `m['type'] != 'photo'` (so GIFs ride
the video path) while `GDL 229` branches purely on presence of `video_info`. For XTV:

```kotlin
when (media.type) {
  "photo"        -> Image(bestPhotoUrl(media))
  "animated_gif" -> Video(url = media.videoInfo!!.variants[0].url,
                          loop = true, muted = true, autoAdvance = false)
  "video"        -> Video(url = pickMp4(media.videoInfo!!.variants),
                          loop = false, muted = false,
                          autoAdvance = true, durationMs = media.videoInfo.durationMillis)
}
```
Set `Player.REPEAT_MODE_ONE` for GIFs and suppress the "next" timer; do not rely on
`duration_millis` to schedule advancement for them (it's null).

---

## 4. Sensitive / NSFW media — the critical section

There are **two completely different mechanisms**. Conflating them is the main design risk.

### 4.1 Mechanism A — client-side blur hint (media IS in the response)
**[V SPEC 3362-3377, 1588-1602]**
```
result.__typename == "TweetWithVisibilityResults"
result.mediaVisibilityResults.blurred_image_interstitial : {
    opacity : number,
    title   : TweetInterstitialText,   # { text, rtl, entities[] }
    text    : TweetInterstitialText
}
result.tweet          ← the real Tweet, WITH extended_entities.media fully populated
result.tweetInterstitial : { __typename, displayType: "NonCompliant"|"EntireTweet", text, revealText }
result.limitedActionResults : object
```
**[L, corroborated ×3]** The blur is **purely a client-side render hint. The media URLs are present and
playable.** Sources:
1. `Castrozan/.dotfiles` `x-age-bypass.user.js` states it explicitly: *"X's GraphQL API returns tweets
   with age-gated media as type `TweetWithVisibilityResults`. **The media URLs are fully present in the
   response — the gate is purely client-side.** The key field is
   `mediaVisibilityResults.blurred_image_interstitial.interstitial_action = "AgeVerificationPrompt"`."*
   It works by hooking `JSON.parse` and nulling that one field.
2. `Robot-Inventor/shadowban-scanner` `propsAnalyzer.ts` detects age restriction via
   `typeof props.mediaVisibilityResults?.blurred_image_interstitial?.opacity === "number"` — a pure
   render property, and reads the same props the React tree already has.
3. `ALGO Action.scala 227`: `case r if NSFW_MEDIA.contains(r) => Some(InterstitialReason.ContainsNsfwMedia)`
   — NSFW *media* maps to an **Interstitial** action (overlay), categorically distinct from a **Drop**.

→ **XTV should ignore `mediaVisibilityResults` entirely and render the media.** Optionally read
`blurred_image_interstitial.title.text` to show a "sensitive" chip. Note `interstitial_action`
(value `"AgeVerificationPrompt"`) is **not** in `SPEC` — it is newer than the generated schema, so treat
the object as open. The userscript is region-titled "(BR)", so the AgeVerificationPrompt flavour is
**[L]** tied to regional age-assurance law (Brazil / UK OSA-style), gated by feature flags
`rweb_age_assurance_flow_enabled` and `age_verification_gate_enabled`.

### 4.2 Mechanism B — server-side drop (media is GONE)
**[V ALGO `Action.scala` 64-68, 151-158; `TombstoneGenerator.scala` 67-92; `VisibilityResultToFilteredState.scala` 27, 93]**
X's own `Reason`/`DropReason`/`TombstoneReason` enums:

```scala
// Action.scala 64-68
case object Nsfw                     extends Reason   // → DropReason.NsfwAuthor
case object NsfwMedia                extends Reason   // → Interstitial (blur), NOT a drop
case object NsfwViewerIsUnderage     extends Reason   // → DROP
case object NsfwViewerHasNoStatedAge extends Reason   // → DROP
case object NsfwLoggedOut            extends Reason   // → DROP
```
When it's a Drop, the tweet arrives as `TweetUnavailable` with a `reason` string, or as a tombstone.
`VisibilityResultToFilteredState.scala:27` groups exactly
`Drop(NsfwViewerIsUnderage | NsfwViewerHasNoStatedAge | NsfwLoggedOut, _)` as one age bucket.

Client handling **[V GDL 1478-1484; YTDLP 1087-1093]** — both scrapers agree:
```python
if tweet.get("__typename") == "TweetUnavailable":
    reason = tweet.get("reason")
    if reason in {"NsfwViewerHasNoStatedAge", "NsfwLoggedOut"}: → "NSFW tweet, auth/age required"
    if reason == "Protected":                                   → "not authorized"
    else: → f"Tweet unavailable ('{reason}')"
```
**Naming trap [V]**: the GraphQL `reason` string is **not** identical to the Scala enum. `ALGO` has
`ProtectedAuthor`/`SuspendedAuthor`; the wire value both scrapers match on is `"Protected"`. So
**prefix/substring-match, don't equality-match an enum you derived from the Scala** — e.g.
`reason.startsWith("Nsfw")`, `reason.contains("Protected")`. Neither scraper handles
`NsfwViewerIsUnderage`, which `ALGO` proves exists — **[L]** so their lists are incomplete; yours
should be open-ended.

### 4.3 So what does the user actually have to do?
- **Be logged in.** `NsfwLoggedOut` is a hard drop. XTV's WebView cookie approach satisfies this. **[V ALGO]**
- **Have a stated birth date on the account.** `NsfwViewerHasNoStatedAge` is a hard drop keyed on the
  viewer having *no stated age at all*. **[V ALGO Action.scala 67 + VisibilityResultToFilteredState 27]**
  Set it at Profile → Edit profile → Birth date on the web. **[L]** — I could not read
  `help.x.com/en/safety-and-security/sensitive-media` (HTTP 403 to WebFetch), so the UI path is from
  secondary sources; the *requirement* is verified from X's own code.
- **Be 18+ per that birth date.** `NsfwViewerIsUnderage` is a separate hard drop. **[V ALGO]**
- **Enable the sensitive-media display setting** (web: Settings and privacy → Privacy and safety →
  Content you see → "Display media that may contain sensitive content"). **[L]** This one is *not*
  provable from `ALGO` and I could not fetch X's help page; it is corroborated by multiple
  gallery-dl threads (issue #8006, discussion #5441) where users confirm having it on. **[L]** Based on
  §4.1, this setting most likely toggles the **interstitial/blur** (Mechanism A) rather than the drop,
  so a client that ignores `mediaVisibilityResults` may not even need it — but enable it anyway; it
  costs nothing and removes a variable.
- **Region matters.** `LocalLawsWithheld`, `LegalDemandsWithheld`, `LocalLawsWithheldMedia` and the
  age-assurance feature flags are all real. **[V ALGO]** A VU/NL-resident account may hit
  age-verification prompts a US account does not. **[U]**
- **Is there anything a client must SEND to see sensitive media?** **No request parameter grants
  access. [V]** `GDL`'s `include_ext_sensitive_media_warning` only asks for the *warning metadata*,
  and it is a legacy-REST param that GraphQL ignores (§0.4). Visibility is computed server-side from
  the **viewer's account state**, per `ALGO`. **[V]** Do not add "magic" params — there aren't any.
  The one feature flag worth carrying because both scrapers set it:
  `tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled: true`
  **[V GDL 1442-1443, YTDLP 1141]**, which is what makes X return `TweetWithVisibilityResults` +
  `limitedActionResults` instead of stripping.

### 4.4 Per-media warning flags
**[V SPEC 1967-1974; GDL 202-219]**
```
media.sensitive_media_warning : { adult_content?:bool, graphic_violence?:bool, other?:bool }
```
`GDL` maps them to labels: `adult_content → "Nudity"`, `other → "Sensitive"`,
`graphic_violence → "Violence"`, then unions the per-media flags into a per-tweet
`sensitive_flags` set **[V GDL 205-217, 285-286]**. Note `GDL` uses `in` on the dict (key presence),
not truthiness — X may send `false` values, so **check presence AND truthiness**.

`legacy.possibly_sensitive : bool` **[V SPEC 3120]** is tweet-level and coarse — `YTDLP 1235` maps it to
`age_limit = 18 if possibly_sensitive else 0`. `legacy.possibly_sensitive_editable : bool`
**[V SPEC 3122]**: `shadowban-scanner` treats `possibly_sensitive && possibly_sensitive_editable == false`
as *the author cannot un-flag it* → hard age restriction **[L]**. Useful signal for a "will be gated"
prediction.

### 4.5 Reporting "gated" instead of an empty channel — concrete rule
Per media item, classify in this order (first match wins):
1. Tweet resolved to `TweetUnavailable` / tombstone → **GATED / UNAVAILABLE**, show `reason` or
   tombstone text. *No media exists — do not render an empty tile.*
2. `ext_media_availability.status != "Available"` → **UNAVAILABLE**, show `reason` (e.g. `Geoblocked`).
3. `mediaVisibilityResults.blurred_image_interstitial` present → **SENSITIVE, but playable.** Render
   it (optionally behind a click-through built from `blurred_image_interstitial.title.text`).
4. `sensitive_media_warning.*` truthy or `possibly_sensitive` → **label only**, fully playable.

Then, at the **channel** level: if a fetch returns N entries and *zero* usable media while ≥1 entry was
classified 1 or 2, surface *"X hid N posts (age/region gate)"* with the distinct reasons — never an
empty grid. Count tombstones separately from deletions; `GDL 2434` deliberately logs skipped tombstones
at debug level and this is exactly the failure users report as "downloads randomly skipping posts"
(gallery-dl issue #8006, closed with no root cause).

---

## 5. Failure states to code for

| State | Detection | Notes |
|---|---|---|
| **Deleted tweet** | timeline entry where `item.itemContent.tweet_results.result` has no `legacy` → `KeyError` | **[V GDL 2255-2261]** `GDL` catches `KeyError` and logs *"Skipping %s (deleted)"*. Also `TombstoneReason.Deleted` / `Bounced` / `BounceDeleted` **[V ALGO Tombstone 69-71]** |
| **Timeline tombstone** | `entryId.startsWith("tombstone-")` → `entry.content.itemContent.tombstoneInfo` | **[V GDL 2197-2201]** `GDL` normalises it to `{"result":{"tombstone": tombstoneInfo}}` so one code path handles both |
| **Tombstone text** | `(tombstone.richText ?: tombstone.text).text` | **[V GDL 2426 + YTDLP 1085]** both read `.text.text` — i.e. `text` is an **object**. `SPEC 2663-2669` types it as a bare `string`. **Parse defensively: accept string OR `{text}`.** `YTDLP 1085` also strips a trailing `". Learn more"` |
| **Age-restricted tombstone** | tombstone text `startsWith("Age-restricted")` | **[V GDL 2429]** — but this is **[L]** English-only and brittle. `x-twitter-client-language: en` **[V GDL 1351]** keeps it English. Exact strings live in X's translation `Resource` table, **not** in the open-sourced `EpitaphToLocalizedMessage.scala` **[V ALGO]** — so no authoritative string list exists publicly. Prefer structural signals |
| **Protected account** | `TweetUnavailable.reason == "Protected"`; or `UserUnavailable` | **[V GDL 1482-1483, 1851-1852; YTDLP 1091-1092]**. `ALGO`: `TombstoneReason.ProtectedAuthor` |
| **Suspended account** | `__typename == "UserUnavailable"` → `user.message`; `TombstoneReason.SuspendedAuthor` | **[V GDL 1851-1852; ALGO Tombstone 73]** |
| **Deactivated / offboarded** | `TombstoneReason.DeactivatedAuthor`, `Unavailable.Author.Offboarded` | **[V ALGO Tombstone 79; VisibilityResultToFilteredState 173-175]** |
| **Blocked / muted / blocked-by** | `AuthorBlocksViewer`, `ViewerBlocksAuthor`, `ViewerMutesAuthor` | **[V ALGO]** |
| **Geo-blocked media** | `ext_media_availability.status == "Unavailable"`, `reason == "Geoblocked"` | **[V GDLDOC + GDL 221-227]** |
| **Geo-withheld tweet** | `legacy.withheld_scope` present; `legacy.withheld_in_countries: string[]` | **[V GDL 130-132, 625, 2299-2302; shadowban-scanner]**. `GDL` warns and still processes. `ALGO`: `LocalLawsWithheld(Media)`, `LegalDemandsWithheld(Media)`, `DmcaWithheldMedia` |
| **Limited actions** | `legacy.limited_actions` (string) or `TweetWithVisibilityResults.limitedActionResults` | **[V SPEC 3108-3118]** enum: `limited_replies`, `non_compliant`, `dynamic_product_ad`, `stale_tweet`, `community_tweet_non_member_public_community`, `community_tweet_non_member_closed_community`, `blocked_viewer`. `limitedActionResults.limited_actions[].action` ∈ {`Reply`,`Retweet`,`QuoteTweet`,`Like`,`React`,`AddToBookmarks`,`AddToMoment`,`PinToProfile`,`ViewHiddenReplies`,`VoteOnPoll`,`ShowRetweetActionMenu`} **[V SPEC 1266+, 3169-3176]**. **These restrict *actions*, not viewing — XTV is read-only, so log and ignore.** |
| **Subscriber-only / paywalled** | `__typename == "TweetPreviewDisplay"` → `.tweet` is a truncated preview + `.cta` | **[V SPEC 3193-3208]**. `ALGO`: `ExclusiveTweet`, `SuperFollowsContent` |
| **Media stripped from a live tweet** | tweet resolves fine, `extended_entities` absent | **[V GDL 134-136]** `if not files and not textonly: continue`. Distinguish "text tweet" from "media removed" — you generally cannot; treat as text tweet |
| **GraphQL partial failure** | HTTP **400/401/403/404 still carries a usable `data` payload** | **[V YTDLP 129]** `allowed_status = {400,401,403,404} if graphql`. **Parse the body on 4xx — do not throw on status alone.** |
| **False-positive API error** | `errors[].message == "Dependency: Unspecified"` | **[V YTDLP 136-139]** — must be **ignored**; it is a spurious error (yt-dlp #15963, fixed 2026-02) that appears alongside perfectly good data |
| **"not authorized"** | `errors[].message` contains `not authorized` | **[V YTDLP 140-141]** → re-login required |
| **Account locked** | error message contains `this account is temporarily locked` | **[V GDL ~1930]** — **stop immediately**, do not retry. This is the main-account risk |
| **Rate limit** | `x-rate-limit-remaining` response header | **[V GDL 1911-1914]** `GDL` backs off when `remaining < 6` using a randomised threshold. Also HTTP 429 **[V YTDLP 1198]**. Read this header on every response; exact numeric limits per endpoint: **[U]** |
| **Empty page ≠ end of timeline** | `cursor.stopOnEmptyResponse == false` → keep paginating | **[V GDL 2206-2208]** — a zero-tweet page is not necessarily the end |

---

## 6. Copy-paste extraction contract for XTV

```
tweetResults.result
  ├ (unwrap .tweet if present)
  ├ legacy.extended_entities.media[]              ← primary; also hoist from
  │    legacy.retweeted_status_result.result.legacy.extended_entities  when absent
  └ per media m:
       kind        = m.type                        # photo | video | animated_gif
       gated       = m.ext_media_availability?.status != "Available"   (reason = .reason)
       aspectW/H   = m.video_info?.aspect_ratio ?: [m.original_info.width, m.original_info.height]
       altText     = m.ext_alt_text
       warnFlags   = m.sensitive_media_warning     # adult_content|graphic_violence|other
       poster      = photoUrl(m.media_url_https, "large")
       photo  → photoUrl(m.media_url_https, size)  # see §1.1; fallback orig→4096x4096→large→medium→small
       video  → best m.video_info.variants where content_type=="video/mp4",
                ranked by /WxH/ parsed from url (NOT by original_info), tie-break bitrate;
                fallback = the application/x-mpegURL variant
                durationMs = m.video_info.duration_millis            # nullable
       gif    → m.video_info.variants[0].url  (single, bitrate 0, no audio, no duration) → loop+mute
```
Playback/loading: **no cookies, no Referer, no auth on `pbs.twimg.com` or `video.twimg.com`.**
Send your session cookies **only** to `x.com/i/api/graphql/*`. Keep the media HTTP client cookie-less —
it is faster, cacheable, and avoids leaking `auth_token` to a CDN.

---

## 7. Open unknowns — and the experiment that closes each

| # | Unknown | Experiment |
|---|---|---|
| 1 | Full value set of `ext_media_availability.reason` (only `Geoblocked` confirmed) | Log every non-`Available` status over a few thousand items from your own timeline; `reason` is an untyped string in `SPEC` |
| 2 | Whether HLS is ever the only variant | Assert `any(content_type == "video/mp4")` across a large sample; specifically probe a >10-min video and an `amplify_video` Media-Studio post |
| 3 | Whether `orig` 404s in practice, and how often | Request `name=orig` for ~500 photos, count 404s, verify the fallback chain fires. Compare `orig` vs `4096x4096` bytes to see if they ever diverge |
| 4 | Whether the "Display media that may contain sensitive content" setting changes the **response** or only the blur hint | Fetch the same known-sensitive tweet id twice with the setting off then on; diff for presence of `extended_entities.media` and of `mediaVisibilityResults`. This is the single highest-value test for XTV |
| 5 | Exact per-endpoint rate limits for UserMedia / Bookmarks | Log `x-rate-limit-limit` / `-remaining` / `-reset` headers per endpoint. Never guess a number |
| 6 | Whether a NL/EU account hits `AgeVerificationPrompt` / age-assurance flags | Inspect `mediaVisibilityResults.blurred_image_interstitial` for an `interstitial_action` field on your own account |
| 7 | Exact English tombstone strings (for the `startsWith("Age-restricted")` heuristic) | Not publicly obtainable — X's translation `Resource` table is not in the open-sourced repo. Collect empirically and prefer structural signals |
| 8 | Whether `tombstoneInfo.text` is a string or `{text}` on your endpoints | `SPEC` says string, both scrapers read `.text.text`. Log both shapes and parse defensively |

## 8. Things I explicitly could NOT verify — do not trust these elsewhere
- **"X video URLs are short-lived signed URLs tied to the browser session."** Repeated by several SEO
  blogs. **Disproved by direct probe** (§2.5): `cache-control: public, max-age=604800`, no signature, no
  cookie needed, works with a suppressed User-Agent.
- **"`?tag=N` causes a 403 and must be stripped."** cobalt issue #1486 claims this; I got `206` with,
  without, and only `404` with a *wrong* tag. Pass it through unchanged.
- **`orig` is capped at 4096×4096.** A blog asserts this; my probes show `orig` and `4096x4096`
  byte-identical on 3/3 samples, which is consistent but not proof of a cap.
- **X help-centre pages** (`help.x.com/en/safety-and-security/sensitive-media`) return **HTTP 403** to
  automated fetches. Every claim here about the *account setting UI path* is therefore **[L]** from
  secondary sources; the *server-side requirements* are **[V]** from `twitter/the-algorithm`.
