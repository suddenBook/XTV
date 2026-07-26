# XTV — handoff

Working notes for whoever picks this up next. Delete this file once the work below is done.

The app works end to end today: it fetches the real timeline, plays it, resumes, and stays inside its
budget. Everything in this document is either a **known regression I introduced and did not finish
fixing**, or a **change the owner asked for after that point**.

---

## Current state

| | |
|---|---|
| Remote `origin/main` | `c7a0835` — **clean and working**. Credentials come from `local.properties` → `BuildConfig`. |
| Local working tree | Has uncommitted work described below, **including one regression**. Do not push as-is. |
| Device tested on | Google TV Streamer, `adb connect 192.168.1.4:5555`. Also a Xiaomi MiTV-MOOR2 (API 30) as the minSdk floor. |
| Tests | `./gradlew testDebugUnitTest` — 16 golden tests, all passing. |

The owner also hand-edited `release.yml` on the remote to put the whole provisioning command on one
line and add `--es bearer`. Keep that.

---

## 1. THE REGRESSION — fix this first

**Symptom:** launching with `--es client_id <id>` shows the Setup screen instead of proceeding, even
though `BuildConfig.X_CLIENT_ID` is populated and should serve as a fallback. Confirmed the intent
*does* carry the extras (`dumpsys activity` shows `(has extras)`).

**What I changed just before it broke** (`app/src/main/java/com/xtv/app/core/auth/Credentials.kt`):
`clientId()` used to be a plain function using `runBlocking` plus an in-memory cache. I made it
`suspend` and uncached, because the cache could memoise a `null` read that raced the write of a
just-injected id. In `MainActivity` the result is now held in a Compose state (`var clientId by
remember { ... }`), assigned inside `LaunchedEffect(Unit)` and read a few lines later in the same
`when`.

**What I ruled out:** no crash, no exception in logcat, process alive, extras present, BuildConfig
value present in `app/build/generated/.../BuildConfig.java`.

**The thing that makes no sense and is therefore the lead:** I added
`Log.i(TAG, "clientId resolved: ...")` immediately after the assignment and **it never printed**,
while the app still rendered the Setup screen. Either that build never reached the device (I piped
the install output to `/dev/null` — check that first, it is the boring explanation and the most
likely one), or the effect body is not running to that point.

**Suggested approach:** put a log on the *first* line of `LaunchedEffect(Unit)`, before any DataStore
call, and confirm it fires. That single data point splits the problem in half. If it fires, the
suspect is the DataStore read/write; if not, the effect is being cancelled or replaced.

**Fallback if it resists:** revert `Credentials.clientId()` to the non-suspend form and instead fix
the original race by writing the injected credentials in `onCreate` *before* `setContent`, using
`runBlocking` there (it is cold-start code, blocking once is acceptable). That sidesteps the Compose
state entirely.

---

## 2. Change the credential strategy to "all three, required"

The owner's decision, replacing the current "client id + refresh token, bearer optional" model:

- **Require all three**: `client_id`, `refresh_token`, `bearer`.
- Keep the current local estimate as a **fallback** for when the bearer is missing or the usage call
  fails, rather than as the default.
- Say so plainly in the setup screen, the README and the workflow's release notes.

The provisioning command becomes (already on the remote's release notes):

```
adb shell am start -n com.xtv.app/.MainActivity --es client_id <id> --es refresh_token <token> --es bearer <app-only bearer token>
```

Touch: `MainActivity`, `Credentials`, `SetupGuideScreen` + the three `strings.xml`, `README.md`,
`.github/workflows/release.yml`, `tools/provision.sh` (make `XTV_BEARER` required).

## 3. Show "spent this period" with the reset date

`UsageApi` is already rewritten against the **real** response and is correct — but the display still
says "this month". With a bearer present it should read something like *"$2.18 this period · resets
on the 26th"*, and only fall back to the estimate wording without one.

`UsageApi.Usage` already carries `resetDay`; `SpendGuard.stateWithUsage()` currently drops it. Thread
it through to `HomeState` and the strings.

Note the mismatch this exposes: `SpendGuard`'s local tally rolls over on the **1st**, X's period
rolls over on **`cap_reset_day`**. They count different windows. With the bearer required, the local
tally becomes a fallback only, so this is tolerable — but do not present the two as the same number.

## 4. Bump to 1.0

`app/build.gradle.kts`: `versionCode = 1` → `2`, `versionName = "0.1"` → `"1.0"`. The workflow tags
releases from `versionName`, so this produces `v1.0`.

---

## Facts that cost real time to establish — do not re-derive these

**The app-only Bearer token contains literal `%2F` and `%3D`.** They are part of the string, not
percent-encoding. I URL-decoded it and got a 401; passing it verbatim returns 200. Verified both ways.

**`GET /2/usage/tweets` does not match its own documentation.** The docs describe
`daily_project_usage` with per-day `tweets_consumed`. The live response is flat:

```json
{"data":{"cap_reset_day":26,"project_cap":"2000000","project_id":"...","project_usage":"436"}}
```

`?days=` changes nothing. It needs **app-only** auth — a user token is rejected with
`unsupported-authentication`. And it is *project*-wide, so it includes anything else using the same
credentials (my probe scripts inflated it to 436 against the app's own 86).

**There is no billing or balance endpoint.** Dollars are always `posts × $0.005` locally.

**Refresh tokens rotate on every use.** I lost the session three times: each `adb uninstall` wipes the
stored token, and by then the copy in `~/.xurl/auth.yml` had already been spent by the app. Use
`adb install -r` (preserves data). When you do need a fresh one: `xurl auth oauth2` (opens a browser)
or `xurl auth oauth2 --headless` (paste-a-code).

**Signature changes force an uninstall.** The debug build on the device is debug-signed; CI releases
use the new keystore. The first switch to a release build will require `adb uninstall`, which destroys
the session — so have a freshly issued refresh token ready for that one transition. Every release
after that is `install -r` and keeps everything.

**`setRecentsScreenshotEnabled(false)` makes `adb exec-out screencap` return a fully black frame** on
this Google TV, even though the panel renders normally. The documented contract says it should only
affect the Overview thumbnail. It is therefore gated to release builds only — if you ever see a black
screenshot from a debug build, that gate has been broken, not the UI.

**Verify UI with `screencap`, not `uiautomator dump`.** The dump comes back empty while video is
playing and is generally unreliable against Compose here.

**`grep -i xtv` on logcat is a trap.** The package name appears in dozens of framework lines; real app
logs get buried past `head -n`. Filter on the tags (`XTV-API`, `XTV-BUDGET`, `XTV-REEL`, `XTV-AUTH`)
or on the pid.

---

## Design decisions worth not re-opening

These were each argued through and, in several cases, corrected after being wrong the first time.

**The reel is finite and ends.** The timeline emits ~186 posts/hour ≈ 4,500/day; a full day's fetch
would cost ~$669/month. One evening needs ~57 posts, covering the most recent ~18 minutes. Content
arrives roughly **80× faster than it can be watched**, so "catch up" is not offerable. This is why the
app takes a fixed budget from the head and stops — the finite episode is a cost necessity, not a style
choice, and "you're caught up" would be a lie.

**Cold start must never spend.** Opening the app, resuming a reel, and browsing the grid all make zero
requests. Every fetch sits behind a keypress whose card already stated its price. Do not add a
"refresh on launch".

**Back must never toggle.** Android TV requires that repeated Back always reaches the home screen. My
first version showed/hid the info overlay on alternate presses and trapped the user in the player.
First press adds context; any press after that leaves.

**Modality needs `canFocus = false`, not just `focusGroup()`.** `focusGroup()` groups focus but does
not stop focus search descending into the group — LEFT from the leftmost dialog button landed on a
card underneath. The content group is taken out of the focus graph while a dialog is up.

**Fixed card geometry in the grid, never staggered.** Compose resolves D-pad moves with
`13·majorAxis² + minorAxis²`; uneven heights let a diagonal neighbour beat the correct target, and the
reverse geometry differs so Up does not bring you back. Non-reciprocal focus reads as "the grid is
broken".

**A full-screen scrim cannot live inside a padded container.** The overscan inset (48/27dp) belongs on
an inner box; putting it on the root left the exit dialog's scrim short of the panel edges.

**`surface_type` is XML-only, and the reel needs `texture_view`.** A `SurfaceView` keeps the previous
item's size until the new video-size event lands, which squashes a landscape clip into the preceding
portrait frame. See `res/layout/reel_player_view.xml`; the sibling PHTV app hit the identical bug.

**Errors must never look like empty results.** `PageResult` distinguishes `PaymentRequired` (HTTP 402,
out of credits), `RateLimited`, `AuthRequired` and `UpstreamChanged`. `PageStats.shapeDrift`
(`recognised < seen`) is the only way to tell "the API changed" from "nothing new tonight" — both
render as an empty screen otherwise. My own probe script shipped with exactly this bug, counting a 402
as "0 items"; there is a regression test for it now.

**Media parsing gotchas, all covered by fixtures:** photos carry their URL in `media.url` and only if
`url` is requested in `media.fields`; the HLS variant has **no `bit_rate` key**, so variant selection
must default it; `duration_ms` is absent on animated GIFs and the model type is nullable for that
reason.

---

## Things deliberately not done

- **Room.** The plan called for it on the assumption of an unbounded unseen backlog. The 80:1 ratio
  removed that: there is only a fixed head-budget reel and one cursor, so DataStore is enough. Room
  becomes justified again when durable per-item history (star, hide, watch counts) lands.
- **A navigation rail.** With one source there is nothing to choose between; it would be chrome plus a
  focus trap. The sibling apps have one because they have multiple channels.
- **Bookmarks and Likes channels.** Measured: 0 and 9 items on this account. Following is the only
  real source.
- **QR / phone pairing for credentials.** The owner chose adb, since installation is via adb anyway.
- **DreamService screensaver and Google TV Watch Next.** Living-room device; content stays out of the
  system UI.

## Unfinished, low priority

- The 30-minute soak test never ran to completion — repeated reinstalls interrupted it each time.
- `docs/research/` holds six research files including the rejected reverse-engineered GraphQL path.
  Keep them: if X changes the rules, they save re-doing the investigation.
- `tools/phase0/probe.sh` and `sanitize_fixtures.py` are the fixture-refresh ritual. Raw captures are
  git-ignored — they are somebody's real timeline.

## Environment

- `local.properties` (git-ignored) holds `sdk.dir`, and optionally `xtv.clientId` / `xtv.refreshToken`
  for a private pre-provisioned build. Never in anything published.
- The release keystore is **not** in the repo. The owner has the base64 and passwords for GitHub
  Secrets; confirm they were saved before cutting a release, because losing it means no more updates
  to an already-published APK.
