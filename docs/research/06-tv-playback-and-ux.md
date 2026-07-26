# XTV — Compose-for-TV: ambient channel playback & UX

Research date **2026-07-26**. Tags: `[VERIFIED <source>]` / `[LIKELY <reasoning>]` / `[UNKNOWN — needs live test]`.
All version numbers below read from Google Maven / Maven Central `maven-metadata.xml` today, not from blog posts.

## 0. Version baseline [VERIFIED dl.google.com/android/maven2/…/maven-metadata.xml, repo1.maven.org]

| Artifact | Latest stable | In flight |
|---|---|---|
| `androidx.tv:tv-material` | **1.1.0** | — |
| `androidx.tv:tv-foundation` | **1.0.0** | — |
| `androidx.media3:media3-*` | **1.10.1** | 1.11.0-rc01 |
| `androidx.media3:media3-ui-compose` | **1.10.1** | 1.11.0-rc01 |
| `androidx.compose.ui:ui`, `…foundation` | **1.11.4** | 1.12.0-beta02 |
| `io.coil-kt.coil3:*` | **3.5.0** | — |

---

## 1. Seamless auto-advance across mixed media

### 1.1 What Media3 actually gives you

- `MediaItem.Builder.setImageDurationMs(long)` exists and is **`@UnstableApi`** [VERIFIED `libraries/common/src/main/java/androidx/media3/common/MediaItem.java`]. Javadoc verbatim: *"Sets the image duration in video output, in milliseconds. Must be set if {@linkplain #setUri the URI} is set and resolves to an image. Ignored otherwise. Motion photos will be rendered as images if this parameter is set, and as videos otherwise. Default value is `C#TIME_UNSET`."*
- `ExoPlayer.setImageOutput(ImageOutput)` landed in **1.3.0 (2024-03-06)**, and *"`DefaultRenderersFactory` now provides an `ImageRenderer` to the player by default with null `ImageOutput` and `ImageDecoder.Factory.DEFAULT`"* [VERIFIED media3 `RELEASENOTES.md` L2712-2715]. So a single `ExoPlayer` **can** hold a playlist of images + videos.
- `ImageOutput` is **`@UnstableApi`**, two methods, **both called on the playback thread**, each documented *"This method should have an implementation that runs fast"* [VERIFIED `exoplayer/…/image/ImageOutput.java`]:
  - `void onImageAvailable(long presentationTimeUs, Bitmap bitmap)`
  - `void onDisabled()`
- Image format support [VERIFIED developer.android.com/media/media3/exoplayer/images]: BMP, JPEG, JPEG motion photo, JPEG Ultra HDR, PNG, WebP, HEIF/HEIC, HEIC motion photo, AVIF baseline (API 34+ only). **GIF = NO, reason given: "No Extractor support."**

### 1.2 The GIF problem is not a problem

X serves "animated GIFs" as **MP4**, not `.gif`. gallery-dl branches purely on `if "video_info" in media:` and picks `max(video_info["variants"], key=bitrate)`, carrying `media["type"]` (`animated_gif`) through as metadata only [VERIFIED gallery-dl `extractor/twitter.py` ~L229-255]. So ExoPlayer's missing GIF extractor is irrelevant — a "GIF" is just a short silent MP4. Loop it with per-item repeat rather than a decoder feature. Coil's `coil-gif` artifact is therefore **not needed** for X content [LIKELY — only needed if you ever render true `.gif` bytes].

### 1.3 The two blockers on the pure-playlist design

**Blocker A — androidx/media issue #1017 is still OPEN.** *"Image is skipped when mixing image and video media items in a exoplayer playlist"*, opened 2024-01-24, label `bug`, `state: open`, 4 comments [VERIFIED GitHub API `repos/androidx/media/issues/1017`].
History from the thread:
- Google (`microkatz`, 2024-04-05): the original skip was fixed by commits `8b219b0ae69f80764183b680b44a8b0d6b03ed56` and `638b2a3c86f7362041b09faed7c3c09609e29118` — *"The original issue was addressed by the player not transitioning its clock to the subsequent media item until the item has started."* Shipped by 1.3.1.
- **Residual, unfixed bug**: `ImageOutput.onDisabled()` fires immediately after the last `onImageAvailable()`. Reporter: *"the second image is shown for a few millis instead of its full presentation time (as onDisabled is immediately called, hiding the view and showing what's below)."* Google: *"there is still a prevailing issue of the ImageRenderer finishing its rendering duties as soon as it offers that last image… We will need a little time to figure out the best solution."*
- **Google's official workaround**: *"may I suggest using the `onMediaItemTransition` callback? Hopefully that will provide ample notification for when the playback transitions to an item that is video vs image for switching your UI views."*
  → **Never drive image-layer visibility from `onDisabled()`. Drive it from `onMediaItemTransition`.** [VERIFIED issue #1017 comments]

**Blocker B — `ContentFrame` is video-only; there is no Compose image widget.**
`androidx.media3.ui.compose.ContentFrame` is `@UnstableApi` and contains **zero** references to image/bitmap/`ImageOutput` on both the `release` and `main` branches (grepped, 0 hits) [VERIFIED `libraries/ui_compose/src/main/java/androidx/media3/ui/compose/ContentFrame.kt`]. Signature:

```kotlin
@UnstableApi @Composable
fun ContentFrame(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  shutter: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Black)) },
)
```

The View-world `PlayerView` *does* display images — via an internal `ImageView` fed by a **reflection Proxy** over `ImageOutput` (`Proxy.newProxyInstance(...)` intercepting `onImageAvailable`) [VERIFIED `libraries/ui/…/PlayerView.java`]. Compose has no equivalent. Google confirmed in Feb 2025 that a Compose video+image class was *"currently in internal review"* (`tonihei`, issue #2151) — as of media3 1.10.1 / main today, **it shipped as video-only**. [VERIFIED issue #2151 + source grep]

**API churn to watch**: `release` uses `presentationState.videoSizeDp`; `main` renamed it to `presentationState.videoAspectRatio` (the only diff between branches in ContentFrame.kt) [VERIFIED diff].

### 1.4 The concrete glitch mechanism (why naive mixing flashes black)

From `PresentationState.kt` [VERIFIED `libraries/ui_compose/…/state/PresentationState.kt`]:
- `coverSurface` is *"set to false when the Player emits `Player.EVENT_RENDERED_FIRST_FRAME` and reset back to true on `Player.EVENT_TRACKS_CHANGED` depending on the number and type of tracks."*
- `private fun maybeHideSurface`: `if (hasTracks && !hasSelectedVideoTrack(player)) { coverSurface = true }`

An image `MediaItem` has **no video track** → on transition into it, `coverSurface` flips true → `ContentFrame` draws the black `shutter()`. Then on transition into the next video, the shutter stays up until `EVENT_RENDERED_FIRST_FRAME`. **That is your black flash, twice per image→video boundary.** Mitigations: `keepContentOnReset = true`, and/or supply your own `shutter` that renders the *current image* instead of black.

**Second, harder gotcha: you cannot alpha-fade a `SurfaceView`.** `SURFACE_TYPE_SURFACE_VIEW` is a separate composited window layer, so a Compose crossfade between the image layer and the video layer cannot dissolve the video. Options: `SURFACE_TYPE_TEXTURE_VIEW` (fadeable, but extra copy + a frame of latency — measurably worse on cheap SoCs), or accept a hard cut, or cover the cut with a full-screen scrim animation. [LIKELY — SurfaceView compositing is documented framework behaviour; the applied consequence for this specific layering is UNKNOWN — needs live test on the target box]

### 1.5 Recommendation

**Use a hand-rolled coordinator, with Media3 handling only the video items.** Rationale, in order of weight:
1. The image half of the playlist path requires you to hand-write the display layer anyway (§1.3B), so "use the playlist" does not save you the hard work.
2. Issue #1017's residual bug is open and lives exactly on the image→video boundary you cross constantly.
3. `setImageDurationMs`, `ImageOutput`, `ContentFrame`, `PresentationState`, `DefaultPreloadManager` are **all `@UnstableApi`** — that's your entire critical path on unstable API.
4. A coroutine timer gives you pause/resume, variable per-item dwell time, and "hold longer on tall images" for free; a playlist makes each of those a fight.

Shape: one `LaunchedEffect` state machine over `List<QueueItem>`; images → Coil `AsyncImage` + `delay(dwell)`; videos/GIFs → one long-lived `ExoPlayer` with `setMediaItem` + `REPEAT_MODE_ONE` for GIFs, advance on `Player.STATE_ENDED` (or after N loops for GIFs).

**If you do want the single-playlist design**, the supported route is: `MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE` + `ExternallyLoadedImageDecoder.Factory` backed by Coil (override `DefaultRenderersFactory.getImageDecoderFactory`) + an `ExternalLoader` on `DefaultMediaSourceFactory.setExternalImageLoader(...)`. The docs are explicit: *"During playback, the player requests to preload the next image once the previous item has fully loaded. Even if preloading is not possible, an `ExternalLoader` must still be provided."* [VERIFIED developer.android.com/media/media3/exoplayer/images — samples given for Glide; Coil equivalent is mechanical]. `ExternallyLoadedImageDecoder` added in **1.5.0 (2024-11-27)**, *"for simplified integration with external image loading libraries like Glide or Coil"*; `BitmapFactoryImageDecoder.BitmapDecoder` was **removed in 1.9.0** in favour of it [VERIFIED RELEASENOTES L1981, L866-871]. This route's bonus: Coil does the HTTP fetch, so your cookie/`auth_token` OkHttp stack applies to images automatically.

---

## 2. Preloading the next 2–3 items

### 2.1 Images — Coil 3

[VERIFIED coil `docs/faq.md`, verbatim]:
```kotlin
// Warms BOTH memory and disk cache:
val request = ImageRequest.Builder(context).data("https://example.com/image.jpg").build()
imageLoader.enqueue(request)

// Disk only (skips decode, skips memory cache):
val request = ImageRequest.Builder(context)
    .data("https://example.com/image.jpg")
    .memoryCachePolicy(CachePolicy.DISABLED)      // Disables writing to the memory cache.
    .decoderFactory(BlackholeDecoder.Factory())   // Skips the decode step.
    .build()
imageLoader.enqueue(request)
```
For an ambient channel you want the **decoded bitmap resident**, so use the plain `enqueue` form for N+1/N+2 and the disk-only form for N+3.. deeper.

**Critical detail**: Coil's memory-cache key incorporates the resolved request **size**. Preload with an explicit `.size(...)` equal to what the on-screen `AsyncImage` will resolve to, or the preload is a memory-cache miss and you paid for nothing. [LIKELY — standard Coil cache-key semantics; UNKNOWN — verify by logging `ImageResult.memoryCacheKey` for preload vs display]

Cache config [VERIFIED coil `image_loaders` docs]:
```kotlin
ImageLoader.Builder(context)
    .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
    .diskCache { DiskCache.Builder()
        .directory(context.cacheDir.resolve("image_cache"))
        .maxSizePercent(0.02).build() }
    .build()
```

**2 GB TV box memory arithmetic** (arithmetic, not a quoted constant): 1920×1080 ARGB_8888 = 1920·1080·4 ≈ **8.3 MB/bitmap**. 4K = ≈**33 MB/bitmap**. So a 3-deep decoded lookahead at 1080p ≈ 25 MB — comfortable. At 4K it is 100 MB and will OOM. **Always cap decode to display size** (`.size(Size(displayW, displayH))` / `ContentScale` + `precision`). X's `?name=large` / `4096x4096` variants will hand you 4K-class bitmaps if you let them. The actual per-app heap ceiling is device-specific: [UNKNOWN — read `ActivityManager.getMemoryClass()` / `getLargeMemoryClass()` on the target box].

### 2.2 Video — `DefaultPreloadManager`

- **`@UnstableApi`** (class-level), *"A preload manager that preloads with the `PreloadMediaSource` to load the media data into the `SampleQueue`"* [VERIFIED `exoplayer/…/source/preload/DefaultPreloadManager.java`].
- Builder surface [VERIFIED same source]: `setMediaSourceFactorySupplier`, `setDataSourceFactory`, `setRenderersFactory`, `setTrackSelectorFactory`, `setLoadControl`, `setBandwidthMeter`, `setPreloadLooper` (*"should not pass main looper"*), `setCache`, `setCachingExecutor`, plus **`buildExoPlayer()`** / `buildExoPlayer(ExoPlayer.Builder)`.
- **Use `buildExoPlayer()`, do not wire it up by hand.** 1.8.0 added: *"Throw `IllegalStateException` when `PreloadMediaSource` is played by an `ExoPlayer` with a playback thread that is different than the preload thread (#2495)"* [VERIFIED RELEASENOTES L917, under `### 1.8.0 (2025-07-30)`].
- **Memory budgeting is a real, named API as of 1.9.0** [VERIFIED RELEASENOTES L490, L506-512, under `### 1.9.0 (2025-12-17)`]: `DefaultLoadControl.Builder.setPlayerTargetBufferBytes(String playerName, int bytes)`, and for preloading specifically *"set the target buffer bytes for preloading via `DefaultLoadControl.Builder.setPlayerTargetBufferBytes(String, int)` for a `playerName` of `PlayerId.Preload.name` ("preload"), and inject the created `DefaultLoadControl` via `DefaultPreloadManager.Builder.setLoadControl(LoadControl)`"* — added *"to avoid total buffer bytes for preloading from growing arbitrarily."* **On a 2 GB box, set this explicitly.**
- 1.9.0 also added pre-*caching* (disk, not just SampleQueue): `PreloadStatus.specifiedRangeCached(startPositionMs, durationMs)` / `specifiedRangeCached(durationMs)`, and batch `addMediaItems`/`removeMediaItems`. 1.10.0 added custom `DataSource.Factory` in the Builder. 1.11.0-alpha01 adds `SimpleRankingDataComparator`. [VERIFIED RELEASENOTES L460-512, L91]

### 2.3 Second-player ping-pong

For a hand-rolled coordinator (§1.5), two `ExoPlayer` instances alternating is simpler than `DefaultPreloadManager` and stays off `@UnstableApi`. Cost: two video codec instances. Cheap TV SoCs commonly expose very few concurrent hardware AVC/HEVC decoder instances, and exceeding it throws on `MediaCodec.createByCodecName`. Keep it to **exactly 2**, and only `prepare()` the standby player (don't `play()` it). [LIKELY — reasoning from MediaCodec resource limits] [UNKNOWN — the actual concurrent-instance limit on the target box; test by enumerating `MediaCodecInfo` / `CodecCapabilities.getMaxSupportedInstances()`]

Given X videos are short and mostly progressive MP4, **the highest-leverage preload is plain HTTP warming** — put the next 2 video URLs through the same OkHttp/`Cache` you use for images. That gets you most of "instant" without a second codec. [LIKELY]

---

## 3. Keeping the screen awake

The screensaver you are fighting is **Ambient Mode**. It activates **after 10 minutes of user inactivity**, then Energy Saver powers the panel off. [VERIFIED developer.android.com/training/tv/playback/ambient-mode]

**Correct API** [VERIFIED same page]:
```kotlin
requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
// and on stop / when the channel is paused:
requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

- **`ExoPlayer.setWakeMode` is the wrong tool** — it's CPU/network. Explicitly: *"Audio playback implicitly holds a partial wake lock, which prevents Energy Saver mode but does NOT prevent Ambient Mode."* And *"Apps cannot prevent the device from entering Energy Saver mode."* [VERIFIED ambient-mode]
- **`PlayerView` will not do it for you**: grep for `keepScreenOn` / `FLAG_KEEP_SCREEN_ON` in `PlayerView.java` returns **no occurrences** [VERIFIED source grep]. Your paused/absent-player worry is correct and applies to the video case too.
- Compose idiom (no first-party API exists): `val view = LocalView.current; DisposableEffect(channelRunning) { view.keepScreenOn = channelRunning; onDispose { view.keepScreenOn = false } }`. The docs give only the Window-flag form: *"the documentation does not provide Compose-specific guidance."* [LIKELY — `View.keepScreenOn` is the documented view-level equivalent of the window flag; UNKNOWN — needs a 12-minute idle test on the target box]

**Policy tension worth knowing** (Play quality guidelines; not enforced for a sideloaded private app, but they encode the burn-in risk) [VERIFIED developer.android.com/docs/quality-guidelines/tv-app-quality]:
- **TV-BU**: *"When there is user-initiated active video playback, the app prevents the device from going into Ambient Mode."*
- **TV-BY**: *"When there is no user-initiated active video playback or animation, the app does not prevent the device from going into Ambient Mode."*
- **TV-BA**: *"For audio-only playback, the app does not prevent the device from going into Ambient Mode unless the app implements an experience of non-static imagery, such as music videos or images, while music is playing."*

Your channel is user-initiated (one OK press) and is non-static imagery, so holding the flag is defensible under TV-BU/TV-BA. But the ambient-mode page also says not to hold it for *"automatically playing video or animations"*. **OLED burn-in is the real risk, not policy**: an indefinite slideshow with any fixed chrome (progress bar, logo, letterbox bars) will burn in. Recommend: a hard session cap (e.g. auto-stop after 60–90 min), no persistent static chrome, and drop the flag the moment the channel is paused.

---

## 4. D-pad and focus in a fullscreen overlay

### 4.1 Drop `PlayerView` entirely

`PlayerView`'s constructor does `setDescendantFocusability(FOCUS_AFTER_DESCENDANTS)` and `setClickable(true)` **only** `if (useController)`; it defines `isDpadKey()` covering all 8 D-pad directions + `KEYCODE_DPAD_CENTER` and uses them to `maybeShowController(true)` [VERIFIED `PlayerView.java`]. Making it non-focusable, as you do today, is the right patch — but in Compose the cleaner move is to use **`PlayerSurface(player, modifier, surfaceType)`** (or `ContentFrame`) and delete the `AndroidView` + `PlayerView` interop entirely. No View-focus interop, no controller, nothing to suppress.

### 4.2 Key interception

`Modifier.onPreviewKeyEvent` and `Modifier.onKeyEvent` are both **stable** (no annotation) [VERIFIED `compose/ui/ui/api/current.txt` L2331-2332]. Put `onPreviewKeyEvent` on the overlay root; it runs top-down before descendants, so it wins over anything below. It only fires if focus is inside that subtree — so pair it with `focusRequester` + `focusable()` and `LaunchedEffect { focusRequester.requestFocus() }` when the overlay opens.

### 4.3 Long-press OK vs short press — solved, and verified end-to-end

`Modifier.combinedClickable(onLongClick = …)` **does** work from D-pad key events (this is not a pointer-only feature). Mechanism, read from source [VERIFIED `compose/foundation/…/Clickable.kt`]:

```kotlin
private val KeyEvent.isPress get() = type == KeyDown && isEnter
private val KeyEvent.isClick get() = type == KeyUp   && isEnter
private val KeyEvent.isEnter get() = when (key) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> true
    else -> false
}
```
`CombinedClickableNode.onClickKeyDownEvent` launches `coroutineScope.launch { delay(currentValueOf(LocalViewConfiguration).longPressTimeoutMillis); onLongClick?.invoke() }`, keyed by keyCode in `longKeyPressJobs`. `onClickKeyUpEvent` cancels that job if still active (→ short press becomes `onClick`), or if it already fired sets `longClickInvoked = true` so `onClick` is **suppressed**. Remote auto-repeat is handled: the down path is guarded by `if (longKeyPressJobs[keyCode] == null)` and `if (!currentKeyPressInteractions.containsKey(keyCode))` with the comment *"If the key already exists in the map, keyEvent is a repeat event. We ignore it."*

Better still: **tv-material components already expose it.** `onLongClick: (() -> Unit)?` appears 19× in `tv-material/api/current.txt`, including `Surface` (all 3 overloads), `Card`, `ClassicCard`, `CompactCard`, `Button`, `OutlinedButton` [VERIFIED]. No hand-rolling needed.

### 4.4 Which keys you can actually count on in 2026

- **The guaranteed set** [VERIFIED developer.android.com/training/tv/get-started/controllers]: *"In general, your app must be operable from a remote controller that only has up, down, left, right, select, Back, and Home buttons."*
- **`KEYCODE_MENU`: do not rely on it.** *"Not all game controllers provide Start, Search, or Menu buttons. Be sure your UI does not depend on the use of these buttons."* Reinforced by requirement **TV-DM**: *"The app does not depend on a remote control device having a Menu button to access user interface controls."* [VERIFIED] → your long-press-OK plan is exactly right; treat `KEYCODE_MENU` as a bonus accelerator only.
- **`KEYCODE_MEDIA_*`: handle if delivered, never require.** TV-PP: *"If the app plays video or music content, the app toggles between playing and pausing media playback when a play or pause key event is sent during playback."* And a hard warning: *"When you use a `MediaSession`, don't override the handling of media-specific buttons, such as `KEYCODE_MEDIA_PLAY` or `KEYCODE_MEDIA_PAUSE`. The system automatically triggers the appropriate `MediaSession.Callback` method."* [VERIFIED]
- **Back semantics** [VERIFIED controllers]: *"The Back button must never act as a toggle… Consecutively pressing the Back button must always eventually lead to the Android TV home screen."* Your overlay-not-nav-destination design satisfies this as long as Back closes the overlay and a second Back exits.
- **A conflict to make a conscious decision about** — TV-PC: *"While a video or audio is playing, pressing the D-pad center button pauses the media… The D-pad left and right buttons fast-forward and rewind."* Your design wants OK = advance. For a private sideloaded app this is a non-issue; just know you're deliberately diverging from the convention users have. [VERIFIED tv-app-quality]
- **[UNKNOWN — needs live test]** Exact key delivery on the specific box/remote (many OEM remotes remap or swallow keys, and Google TV voice remotes send some buttons out-of-band). Experiment: a debug overlay that logs `KeyEvent.keyCode`, `action`, `repeatCount`, `eventTime-downTime` for every key, then press every button on the actual remote.

---

## 5. Grid of mixed aspect ratios

### 5.1 The staggered-grid failure mode — actual mechanism, and a correction

I read the algorithm rather than trusting blog posts [VERIFIED `compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/focus/TwoDimensionalFocusSearch.kt`]:

```kotlin
fun weightedDistance(candidate: Rect): Long {
    val majorAxisDistance = candidate.majorAxisDistance().toLong()
    val minorAxisDistance = candidate.minorAxisDistance().toLong()
    return 13 * majorAxisDistance * majorAxisDistance + minorAxisDistance * minorAxisDistance
}
```
with the source comment: *"Fudge-factor opportunity… Warning: This fudge factor is finely tuned, run all focus tests if you dare tweak it."*

- `majorAxisDistance()` for `Down` = `top - focusedRect.bottom`, coerced to ≥ 0 (the vertical gap).
- `minorAxisDistance()` for `Up`/`Down` = *"the distance between the center horizontals"* — i.e. **centre-to-centre**, not edge overlap.
- Candidacy for `Down`: `(focusedRect.top < top || focusedRect.bottom <= top) && focusedRect.bottom < bottom`.
- `beamBeats()`: a rect *exclusively* inside the source's minor-axis beam beats one that isn't; but *"for vertical directions, beams only beat up to a point"* — `rect1.majorAxisDistance() < rect2.majorAxisDistanceToFarEdge()`.
- There is an open TODO in-file: `TODO(b/182319711)` about restricting Left/Right search to the beam.

**Failure mode**: in a staggered grid, unequal item heights vertically desynchronise the columns. Pressing Down, a card in a *neighbouring* column can have a near-zero `majorAxisDistance` while the true next card in your own column has a large one. Because the metric is `13·major² + minor²`, a small major difference gets squared *down* while the centre-to-centre minor penalty is only weight 1 — so the diagonal neighbour frequently wins, and the beam tie-break only protects you "up to a point". Focus drifts sideways, and because the reverse geometry differs, **Up does not bring you back**. Non-reciprocal focus is the thing users experience as "the grid is broken."

**Correction to a common claim**: this is *not* about off-screen items being unreachable. **Both** `LazyGrid.kt` and `LazyStaggeredGrid.kt` wire `Modifier.lazyLayoutBeyondBoundsModifier(...)` with a `beyondBoundsInfo` state (7 and 6 references respectively) [VERIFIED source grep of both files]. Off-screen items *are* focus-search candidates in both. The staggered grid's problem is purely the geometric heuristic.

### 5.2 Best practice

1. **Fixed card geometry — uniform width *and* height.** This is the whole fix: it puts every focus rect on a regular lattice, which makes `weightedDistance` reciprocal and predictable. Use `LazyVerticalGrid` with `GridCells.Fixed(n)`, not `LazyVerticalStaggeredGrid`.
2. **Absorb aspect ratio inside the card**, not in the layout: `ContentScale.Crop` for photos (fills the fixed box, crops overflow), plus an inner `Modifier.aspectRatio(...)` letterbox for content you refuse to crop. Per-item `ContentScale` chosen from the media's `original_info.width/height` — which X gives you, and which gallery-dl also reads (`file["width"] = media["original_info"].get("width", 0)`) [VERIFIED gallery-dl source].
3. **Focus restoration** — `Modifier.focusRestorer(fallback: FocusRequester = FocusRequester.Default)` is **stable, no annotation**. The overload most blog posts show, `focusRestorer(onRestoreFailed: (() -> FocusRequester)?)`, is **`@Deprecated @ExperimentalComposeUiApi`** — don't copy those samples [VERIFIED `compose/ui/ui/api/current.txt` L1053-1056]. `FocusRequester.saveFocusedChild()` / `restoreFocusedChild()` are stable public methods (L1000-1001, L1049-1050). `Modifier.focusGroup()` is `@Stable` [VERIFIED foundation api L222].
   Pin **Compose ≥ 1.11.0**: 1.11.0-beta02 fixed *"Focus will be restored correctly inside lazy containers where focus nodes and groups are being reused"* (b/481564275), and 1.12.0-alpha03 fixed *"focusRestorer not properly restoring focus when multiple save calls occur for the same layout"* (b/505371994) [VERIFIED compose-ui release notes]. Exact release in which the stable `focusRestorer` overload graduated: [UNKNOWN — the androidx GitHub mirror only carries `androidx-main`, so I could only confirm stability on the 1.12 line; verify against your pinned Compose by checking whether the call compiles without `@OptIn`].
4. **Scroll state**: because your player is an **overlay composable, not a nav destination**, the grid is never disposed on Back — plain `rememberLazyGridState()` already survives it. `rememberSaveable` only buys you process-death/config-change survival. Combine `focusRestorer()` on the grid with `focusGroup()` on each row-ish container.
5. **Overscan / safe area** [VERIFIED developer.android.com/training/tv/start/layouts]: *"Adding a 5% margin of 48 dp on the left and right edges and 27 dp on the top and bottom edges to a layout helps ensure that screen elements in the layout are within the overscan-safe area."* → `PaddingValues(horizontal = 48.dp, vertical = 27.dp)` as `contentPadding`. Requirement **TV-OV**: *"The app does not display any text or functionality that is partially cut off by the edges of the screen."* Densities: 720p = `tvdpi`, 1080p = `xhdpi`, 4K = `xxxhdpi`.
   Note the interaction with focus scale: tv-material's `scale` on focus grows the card **outside** its layout bounds. Cards in the first/last row or column need enough `contentPadding` that the grown card isn't clipped — 48/27 dp usually covers it, but verify at your chosen scale factor. [LIKELY]

---

## 6. TV-native integrations — recommendations for a private/NSFW app

### 6.1 `DreamService` (register as the TV screensaver) — **recommendation: DO NOT**

Contract [VERIFIED AOSP `core/java/android/service/dreams/DreamService.java`]:
```xml
<service android:name=".MyDream" android:exported="true"
         android:icon="@drawable/my_icon" android:label="@string/my_dream_label"
         android:permission="android.permission.BIND_DREAM_SERVICE">
  <intent-filter>
    <action android:name="android.service.dreams.DreamService" />
    <category android:name="android.intent.category.DEFAULT" />
  </intent-filter>
  <!-- optional -->
  <meta-data android:name="android.service.dream" android:resource="@xml/my_dream" />
</service>
```
`res/xml/my_dream.xml`: `<dream android:settingsActivity="com.example.app/.MyDreamSettingsActivity" />`.
Constants: `SERVICE_INTERFACE = "android.service.dreams.DreamService"`, `DREAM_META_DATA = "android.service.dream"`. The `BIND_DREAM_SERVICE` permission is required *"When targeting api level 21 and above."*
Lifecycle: `onAttachedToWindow` → `onDreamingStarted` → `onDreamingStopped` → `onDetachedFromWindow`; plus `setInteractive(boolean)`, `setFullscreen(boolean)`, `setScreenBright(boolean)`, `finish()`. Canonical sample sets `setInteractive(false)` and `setFullscreen(true)` in `onAttachedToWindow`.

**Why not**: a dream is registered **system-wide by label and icon** in the screensaver picker (discoverable by anyone who opens TV Settings), and once selected it **auto-starts after idle with no auth gate** — NSFW content on the living-room screen, unattended, triggered by the OS rather than by the user. That is the exact opposite of what a private app wants. If you still want it: gate behind a PIN, default the dream to neutral content, and give it a generic `android:label`.
**[UNKNOWN — needs device check]** Whether current Google TV builds still surface a third-party screensaver picker at all; Ambient Mode has displaced it in some builds, so the feature may not even be reachable on the target box.

### 6.2 Watch Next / home-screen channels (TvProvider) — **recommendation: DO NOT**

API [VERIFIED developer.android.com/training/tv/discovery/watch-next-add-programs]:
```kotlin
val builder = WatchNextProgram.Builder()
builder.setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
        .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        .setLastEngagementTimeUtcMillis(time)
        .setTitle("Title").setDescription("Program description")
        .setPosterArtUri(uri).setIntentUri(uri)
        .setInternalProviderId(appProgramId)
val watchNextProgramUri = context.contentResolver
        .insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, builder.build().toContentValues())
```
`WATCH_NEXT_TYPE_CONTINUE | _NEXT | _NEW | _WATCHLIST`.

Three disqualifying facts, all verified on that page:
1. *"Publishing to Watch Next on Google TV requires prior approval by Google through a certification process."* — a non-starter for a private app.
2. *"Your app cannot move, remove, or hide the Watch Next channel's row."* — you'd be publishing **titles and poster art onto the system home screen, permanently, outside your control.**
3. It fires `TvContractCompat.ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT` broadcasts, i.e. more surface area leaking item identity.

Requirement **TV-PN** would also apply. Skip it entirely — and note that *not calling these APIs* is all it takes to stay off the home screen. Also note `WRITE_EPG_DATA` / `READ_EPG_DATA` permissions are conventionally needed for TvProvider writes: [UNKNOWN — not quoted on the page I fetched; confirm against `androidx.tvprovider` docs before relying on it].

### 6.3 Keeping content out of the recents thumbnail — **use `setRecentsScreenshotEnabled(false)`, not `FLAG_SECURE`**

`Activity.setRecentsScreenshotEnabled(boolean)` javadoc, verbatim [VERIFIED AOSP `core/java/android/app/Activity.java` L9656-9670]:
> *"Also note that in comparison to `android.view.WindowManager.LayoutParams#FLAG_SECURE`, this only affects the behavior when the activity's screenshot would be used as a representation when the activity is not in a started state, i.e. in Overview. The system may still take screenshots of the activity in other contexts; for example, when the user takes a screenshot of the entire screen, or when the active `VoiceInteractionService` requests a screenshot."*
> *"Note that the system may use the window background of the theme instead to represent the window when it is not running."*

This is **exactly** what you asked for: kills the Overview thumbnail, leaves `adb shell screencap` and manual screenshots working for debugging. Set a solid theme window background so the placeholder is opaque. API level: [LIKELY 33 / Android 13 — verify; guard with a version check].

`FLAG_SECURE` javadoc, verbatim [VERIFIED AOSP `core/java/android/view/WindowManager.java` L2857-2868]:
> *"Window flag: treat the content of the window as secure, preventing it from appearing in screenshots or from being viewed on non-secure displays."*

- **Does it break video playback?** No documented breakage found — the flag marks the *window/surface* as secure; it is not a codec or DRM constraint, and the device's own built-in panel is a secure display. [LIKELY safe] [UNKNOWN — needs live test: set the flag and play one image + one video on the actual box, check for black surface, especially with `SURFACE_TYPE_TEXTURE_VIEW`, which historically is the fragile path for secure surfaces.]
- **Does it break screenshots-for-debug?** **Yes, by design** — that's the tradeoff vs `setRecentsScreenshotEnabled`. It also blackens screen recording and any mirroring/Cast output.
- Recommendation: `setRecentsScreenshotEnabled(false)` unconditionally; `FLAG_SECURE` behind a user setting (default on for a release build, off in `debug`), so you keep `screenrecord` for bug reports.

`android:excludeFromRecents="true"` is the blunter option — the task never appears in Overview at all, but you also lose resume-from-recents, and the app becomes harder to get back to. Prefer `setRecentsScreenshotEnabled(false)`.
Staying out of the launcher altogether means omitting `android.intent.category.LEANBACK_LAUNCHER` — but then there is no way to launch the app from the TV UI (you'd need an adb/deep-link/companion launcher). Note **TV-LB** requires a 320×180 banner + ≥160×160 xhdpi icon and **TV-BN** requires the banner to contain the app name, if you *do* want a launcher entry [VERIFIED tv-app-quality]. Choose a bland name/banner.

---

## 7. Compose for TV status in 2026 — you can delete most of your `@OptIn`

[VERIFIED `tv/tv-material/api/current.txt` + Google Maven]

- **`androidx.tv:tv-material` is 1.1.0 STABLE.** `ExperimentalTvMaterial3Api` appears 33× in the API file, and the annotated set is only **two families**:
  - **Chips**: `AssistChip`, `FilterChip`, `InputChip`, `SuggestionChip` + `AssistChipDefaults`, `FilterChipDefaults`, `InputChipDefaults`, `SuggestionChipDefaults`, `Clickable/SelectableChipShape|Colors|Scale|Glow|Border`
  - **Carousel**: `Carousel`, `CarouselState`, `CarouselDefaults`
- **Everything you need is stable, un-annotated**: `Surface` (all 3 overloads incl. selectable), `Card` / `ClassicCard` / `CompactCard`, `Button` / `OutlinedButton` / `IconButton`, `ListItem`, `Text`, `MaterialTheme`, `ColorScheme`, `Border`, `Glow`, `NavigationDrawer`, `TabRow`, `Checkbox`/`Switch`/`RadioButton`. **Action: strip `@OptIn(ExperimentalTvMaterial3Api::class)` from everything except Carousel/Chip usage.** If you use `Carousel` for a hero row, that one `@OptIn` stays.
- **`androidx.tv:tv-foundation` is 1.0.0, but effectively hollowed out.** Its lazy layouts were deprecated in `1.0.0-alpha11`: *"Tv Lazy Layouts have been deprecated from tv-foundation library"* (issuetracker 348896032), and cleaned out in `1.0.0-alpha12`. `TvLazyRow`, `TvLazyColumn`, `TvLazyVerticalGrid`, `TvLazyHorizontalGrid`, `ImmersiveList` are **gone** — use `androidx.compose.foundation` `LazyRow`/`LazyColumn`/`LazyVerticalGrid`. **Check whether you need the tv-foundation dependency at all.** [VERIFIED developer.android.com/jetpack/androidx/releases/tv]
- **Not deprecated in favour of core material3.** There is no release note announcing that; TV components remain the recommendation for their focus/`Border`/`Glow`/`scale` semantics. But the direction of travel is real — lazy layouts already migrated to core foundation, and core Compose absorbed the TV focus work (`focusRestorer`, `focusGroup`, focus-beyond-bounds all live in core, not tv-*). Expect more consolidation; keep TV-specific code thin. [LIKELY — inference from the tv-foundation deprecation + where the focus APIs actually live]
- **media3-ui-compose is where your risk is, not tv-material.** `ContentFrame`, `PlayerSurface`, `PresentationState`, `rememberPresentationState`, and every `*ButtonState` are all `@UnstableApi`, and `videoSizeDp`→`videoAspectRatio` is renaming under you between 1.10.1 and main. Wrap them in one file of your own so the churn has a single blast radius.

---

## 8. Open unknowns, with the experiment for each

| # | Unknown | Experiment |
|---|---|---|
| 1 | Does the image→video boundary actually flash on the target box? | Build the minimal hybrid: one 1080p JPEG then one X MP4. Record at 60fps with `adb shell screenrecord --bugreport` and step frames looking for black. Repeat with `keepContentOnReset=true` and with a custom `shutter`. |
| 2 | Can you crossfade at all with `SURFACE_TYPE_SURFACE_VIEW`? | Animate an alpha over the video layer; if it doesn't dissolve, retest with `SURFACE_TYPE_TEXTURE_VIEW` and measure dropped frames via `Player.AnalyticsListener.onDroppedVideoFrames`. |
| 3 | Concurrent hardware decoder instances | Enumerate `MediaCodecList` → `CodecCapabilities.getMaxSupportedInstances()` for avc/hevc on the box; then try 2 live `ExoPlayer`s and watch for `MediaCodec` init failure. |
| 4 | Per-app heap ceiling | `ActivityManager.getMemoryClass()` / `getLargeMemoryClass()`; then run the channel 30 min and watch `Debug.getMemoryInfo` / `adb shell dumpsys meminfo`. |
| 5 | Does `FLAG_KEEP_SCREEN_ON` on a Compose `View` actually defeat Ambient Mode? | Start the channel, don't touch the remote for 12+ minutes (>10 min threshold), confirm no screensaver. Then repeat with the channel paused and confirm Ambient Mode *does* engage. |
| 6 | Real key delivery from the actual remote | Debug overlay logging `keyCode`/`action`/`repeatCount`/`eventTime-downTime`; press every button, including MENU and any media keys. |
| 7 | Does `FLAG_SECURE` black out video on this SoC? | Toggle the flag at runtime and play one image + one video; test both surface types. |
| 8 | `setRecentsScreenshotEnabled` API level, and whether Google TV's launcher honours it | Check the SDK stub for `@since`; visually confirm the Overview card shows the theme background not the content. |
| 9 | Coil preload cache-key parity | Log `ImageResult.memoryCacheKey` for the preload and for the on-screen `AsyncImage`; assert equal. |
| 10 | Whether Google TV still exposes a 3rd-party screensaver picker | Open TV Settings on the box and look. (Moot if you follow the recommendation to skip DreamService.) |
| 11 | Exact Compose release where stable `focusRestorer(fallback)` graduated | Try compiling without `@OptIn` against the pinned Compose version. |
