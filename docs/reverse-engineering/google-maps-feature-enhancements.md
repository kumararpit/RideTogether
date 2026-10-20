# Google Maps Reverse-Engineering & RideTogether Feature-Enhancement Report

Workstream: `work/google-maps-feature-enhancements`
Worktree: `/home/arpit/Projects/RideTogether-google-maps-enhancements` — **superseded by direct main-repo work per task directive (no worktrees)**: this report lives at `docs/reverse-engineering/google-maps-feature-enhancements.md` in the main repo and program changes land in `/home/arpit/Projects/RideTogether`. Worktree branch retained as historical archive only.
Status: **in progress** — this report consolidates all program evidence to date and is updated as each task completes.

---

## 1. Subject APK — verified metadata

| Field | Value | Evidence |
|---|---|---|
| File | `/home/arpit/Downloads/googlemap.apk` | verified readable |
| Size | 304.2 MB (304,176,297 bytes) | `ls` |
| SHA-256 | `6218d72f48b184eb71f68879bce48cb9ebb2b510be7f7cff88217ea3fd592bb2` | `sha256sum` |
| Package | `com.google.android.apps.maps` | `pyaxmlparser` AXML parse — **cross-verified** `aapt2 dump badging` (build-tools 36.0.0) |
| versionName | `26.42.01.997557324` | same |
| versionCode | `1068832648` | same |
| minSdk / targetSdk | 29 / 37 | `aapt2 dump badging` |

Toolchain available in this environment (re-verified 2026-10-19):

- `apktool 3.0.3` (`/home/arpit/.local/bin/apktool`) — resources decoded successfully to `/tmp/opencode/gma-decoded-apktool-20261019/` (222 res dirs, 8576 strings, 1596 drawables).
- **`aapt` / `aapt2` / `dexdump` are PRESENT** at `/home/arpit/Android/Sdk/build-tools/36.0.0/` — *corrects the earlier "missing" claim*; `aapt2 dump badging` used for verified metadata above.
- `jadx 1.5.2` — installed this session (official GitHub release zip → `/tmp/opencode/jadx-tool/`); runs (44,647 classes registered) **but the host has 7 GB RAM with ~1 GB free and jadx stalls at class 48 (`-j1`, `-Xmx3g`) — full-code decompilation blocked by host memory**; targeted `dexdump`/apktool-smali queries are the feasible code path.
- `adb` — runtime probes on physical device `fbe12520` (RMX3360, 1080×2400, density 3.0).
- `pyaxmlparser` (pip, installed this session) — AXML metadata.
- `python3` + OpenCV 5.0.0 + numpy — screenshot forensics and tile-image calibration.
- REA reverse-engineering MCP server — binary/native/browser inspection.

Tool limitations (recorded for evidence hygiene):

- REA `inspect_adb_device` output unreadable in this environment — use `adb getprop` fallback; do not retry.
- OpenCV `matchTemplate` on flat night-tile textures produces degenerate score-1.0 matches; template std must be filtered (<10 rejected).
- No root on device: two-finger gestures unscriptable, no `screenrecord` (re-confirmed: binary absent on this image), screencap ceiling ≈ 2.2 fps.
- Host RAM (7 GB, ~1 GB free) blocks full jadx code decompilation of the 44.6k-class Maps APK; memory-safe targeted queries only.
- Copying Google proprietary code/assets is prohibited; all RideTogether changes are original implementations on the OSM-based stack (Photon/OSM/independent providers).

All analysis artifacts live under `/tmp/opencode/` (screencaps `d5_*`, `rt_d5_*`, `e3`, `s1..s3`, `zo1..3`, crops, logs) and are reused across tasks; they are not duplicated into the repo.

## 2. Program structure — phases executed

| Phase | Scope | Status |
|---|---|---|
| 1–5 | Camera/navigation-stack audit vs Google Maps under Android (BM/app semantics via device runtime + our code) | complete |
| D-series defect ledger | D1..D7 defects tracked with fixes/regressions | D5 **closed & device-verified**; D7 **open** (this is the active workstream) |
| Task 3 (D7) | Horizontal unpainted-band root cause | source audit complete (Section 5 of this report); reproduction pending |
| Place search | Photon-based place search (replacing Google Places) | implemented in prior tasks; `PhotonPlaceSearchTest`, `PlaceSearchEngineTest` in tree |
| Day/night theme | Map theme controller + appearance palette | implemented; `MapThemeControllerTest`, `DaylightCalculatorTest` in tree |
| Marker smoothing | `RiderMarkerAnimator`, bearing pipeline | implemented; `Task17RiderMarkerSmoothingTest` in tree |
| Label placement | `AmenityLabelPlanner` collision avoidance | implemented; `AmenityLabelPlannerTest` in tree |
| Telemetry | ActiveRide telemetry + SOS reliability + quick messages | implemented; tests in tree |

Baseline snapshot: worktree branch starts from main `b7406b5` + a snapshot commit `f103759` of the program's uncommitted work (80 modified/30 untracked source files) so that the task diff stays reviewable.

## 3. Evidence ledger (core entries)

> Confidence labels: **confirmed** = verified this session against code/artifacts; **program-recorded** = established in earlier phases with artifacts preserved in `/tmp/opencode/`; **inferred** = strong but not proven.

| ID | Subsystem | Claim | Evidence | Confidence | RideTogether equivalent | Improvement / status |
|---|---|---|---|---|---|---|
| E-L1 | Device env | Rider device is RMX3360 1080x2400 density 3.0 (baseTileSizePx = 768, S@z+0.5 = 1086.11) | `adb getprop`/`wm` probes (program-recorded) | confirmed | `RideMapCanvas.kt` density math | n/a |
| E-L2 | Camera anchor | FOLLOWING anchor = 116dp topInset + 65% usable height ⇒ 1330.8 px; FREE = 1104 px; `LiveRideScreen.kt` L142–143 | program-recorded + code (`RideMapCanvas.kt` L364–368) | confirmed | anchor model | n/a (D5-closed invariant) |
| E-L3 | Recenter semantics | Google Maps recenter = instant anchor flip with same-frame center compensation, no second-step zoom pop | phase runtime probes | program-recorded | implemented as D5 fix (pivot rebase in one composition frame, `RideMapCanvas.kt` L373–391) | closed & device-verified (`rt_d5_*`, `d5b_*`, `d5c_*` captures) |
| E-L4 | Pinch in FOLLOWING | Google Maps: pinch inside follow changes zoom only; camera stays locked | phase runtime probes (inferred: gesture visual behavior) | program-recorded (inferred for GM internals, confirmed for our behavior) | implemented as D2 fix (zoom-only coercion in FOLLOWING) | closed |
| E-L5 | Centroid zoom | Free-pan pinch pivots on gesture centroid | phase runtime probes | program-recorded | implemented as D3 fix (`ZoomAnchorMath.anchoredCenter`) | closed |
| E-L6 | Night rendering | Google Maps night mode = GPU color matrix over light carto (equivalent behavior) | pixel forensics (night matrix R'=-0.8R+215, G'=-0.75G+220, B'=-0.7B+230; G-min 28.75 ⇒ (12,16,23) cannot be tile content) | confirmed (our implementation) / inferred (GM internals) | `RideMapCanvas.kt` L408–415 night matrix | n/a |
| E-L7 | Band defect (D7) | Intermittent full-width exact-SlateDark900 (12,16,23) strips inside map area; hard 1-px edges; stable across ≥3 frames; FABs/chips draw above | `/tmp/opencode/d5_now2.png` (rows 636–781), `e3`/`s1` (415–562 + 1643–1691), `rt_d5_12`/`d5_now` (716–720, 5 px) — re-verified this session with corrected channel order | confirmed (pixels) / unknown (mechanism) | task 3 active | source audit complete; reproduction pending; **no code change yet** |
| E-L8 | D7 trigger | Band first appears at recenter transition (device captures: post-recenter frames 12–14 show 5-px hairline at 716–720 at rider-disc-relative −619.6, FAB/gesture transition) | `rt_d5_12..14` | program-recorded | task 3 | investigation in progress |
| E-L9 | D7 static contract | A consistently composed frame cannot contain the observed holes: (a) every composed slot paints placeholder SlateDark800 unconditionally ⇒ SlateDark900 can never show through an existing slot; (b) row adjacency is integer-contiguous by construction (`top=round(y)`, `bottom=round(y+S)`); (c) full-window redraw per frame excludes stale-layer reuse; (d) no clipping in the chain asymptotic to a strip | `RideMapCanvas.kt` L538–644 audit this session | confirmed (code) | task 3 | constrains root cause to "uncovered region" at a specific failing instant |
| E-L10 | OSM tile math | `calculateVisibleTileRange` uses hypot radius + margin 1, contiguous range | `MapProvider.kt` L197–229 | confirmed | `MapProvider.kt` | n/a |
| E-L11 | Matching caveat | ORB/RANSAC cross-frame image identity matching is unreliable on night-tile textures (repeated-pattern false identity consensus on d5_now2↔e3) | forensic consensus | program-recorded | — | never use for evidence |

## 4. RideTogether module map (verified in worktree)

- `app/src/main/java/com/example/ui/components/RideMapCanvas.kt` — map surface: camera transform container (L483–493), tile grid (L495–512), slot bodies (L554–644), route/rider canvas (L651+), HUD/FABs (L1229+). Contains temporary RT-D7 diagnostic logging at L514–527 (**removal scheduled**).
- `app/src/main/java/com/example/data/map/MapProvider.kt` — `TileMath` (L127+), `calculateVisibleTileRange` (L197), tile URL builders, ancestor fallback chain.
- `app/src/main/java/com/example/data/map/MapCameraController.kt` — FOLLOWING/FREE_EXPLORE/OVERVIEW modes; recenter/pinch/pan entry points.
- `app/src/main/java/com/example/data/map/ZoomAnchorMath.kt`, `RiderMarkerAnimator.kt`, `CourseOverGround.kt` — D3 zoom pivot, marker glide, bearing qualification.
- `app/src/main/java/com/example/data/map/MapThemeController.kt` + `MapDayNightTheme.kt` + day/night palette — appearance pipeline.
- `app/src/main/java/com/example/data/place/*` — Photon place search stack.
- `app/src/main/java/com/example/data/location/NavEngine.kt`, `LocationTracker.kt`, `NavAudioGuidance.kt` — navigation engine.
- `app/src/main/java/com/example/data/firebase/*` — live ride sharing; not part of map enhancements.
- Amenity pipeline — Room entities/DAO + `AmenityLabelPlanner` label collision avoidance.

## 5. D7 (active workstream) — Task 3B source audit summary

 Delivered this session (full detail in conversation record; conclusions preserved here because they gate all further D7 work):

**Verified draw path.** `BoxWithConstraints(fillMaxSize().background(SlateDark900))` (L352–356) → rotated container `graphicsLayer` (rotationZ=−displayHeading, transformOrigin=anchor-fraction; **no clip, no alpha, no offscreen effects**; L483–493) → tile-grid Box (L537) → per-slot `Box(offset/size/clip/background(SlateDark800))` with cached-ancestor AsyncImages (Δz 4..2), parent (Δz 1) AsyncImage, exact AsyncImage (L554–644, `crossfade(false)`, night color filter) → route/rider Canvas (L651+) → HUD/FABs outside the rotated container. Compose BOM 2024.09.00; second `graphicsLayer` (L1247) is the compass icon only — irrelevant.

**Hypothesis verdicts.**
- H1 (rotated-layer coverage): falsified — heading ≈0 in band captures; container unclipped; hypot radius + margin always exceeds viewport.
- H2 (stale/offscreen invalidation): falsified — full-window redraw per frame; stale content cannot survive one frame, while bands persist across ≥3 captures (~1.3 s) and across session segments.
- H3 (tile loading/fallback): narrowed — a missing tile yields SlateDark800 placeholder, never the exact band color; the surviving subset is "region covered by no slot node in the failing frame" (the only composition-level way to paint nothing).
- H4 (parent clipping/layout boundary): falsified — no clip in the chain matches a full-width strip.

**Two remaining candidates the static audit cannot separate:**
- HA — transient composition state at a recenter/anchor-flip frame in which slot coverage genuinely skipped a slab (real app defect; gated on catching failing-instant `tileRange` + slot rects).
- HB — a non-slot painter of exact SlateDark900 below the FAB/z-order of the map (sheets like QuickMessage/RiderBottomSheet set `containerColor = SlateDark900`, but they render above the in-canvas FABs, which are visible over the bands — weakly disfavored, half-checked).

**Discriminating experiment (approved and standing).** Reproduce the band with the instrumented build and pull `logcat -s RT-D7` at the failing instant: if band edges lie between adjacent logged row boundaries while the log shows a contiguous grid covering only a *whole row*-height slab, HA is proven; if the band interior to a logged contiguous row, composition is excluded ⇒ HB probes (window hierarchy dump, day↔night toggle during failing state) take over. If unproducible, D7 stays OPEN with no code change (no fix without a failing coordinate).

**Process note.** The two 540-px "wide" band crops (`d5_now_wide.png`, `rt9_wide.png`) contain zero exact-SlateDark900 pixels and are stricken from the defect-matrix evidence set; full-screen captures are primary. Measurement method: per-row fraction of pixels exactly RGB(12,16,23).

**Probe record — 2026-10-19 session (instrumented build, pid 16392).**

- Pass 1 (`/tmp/opencode/gma_d7c_00..19.png`, log `/tmp/opencode/d7_log_c.txt`): short-swipe follow-exit → ~15 s FREE → capture loop with recenter tap inside (between frames 06/07). Result: **no band**; max exact-SlateDark900 row fraction 0.02. Log confirms clean anchor flip 1104.0 → 1330.8 with contiguous rowsY at every logged composition; cXY fractional-drift values confirm per-frame recomposition was active through the transition.
- Pass 2 (`/tmp/opencode/d7stress_1..6_{a..d}.png`, log `/tmp/opencode/d7_log_stress.txt`): 6× pan→recenter stress (24 captures). Result: **no band**; max fraction 0.022; 121 transition-log entries, all contiguous.
- Interpretation: the reproduction trigger remains session-state-conditioned (historical captures occurred in an aged session with tile-load stalls). Two consecutive clean stress passes on the exact trigger class (pan→recenter) now stand. Per the no-fix-without-failing-coordinate rule: **no code change; D7 stays OPEN**. The instrumented RT-D7 logging remains in place intentionally while D7 is open (removal is backlog item P2).

**Reproduction achieved — 2026-10-19 late session (documented defect fingerprint reproduced twice on the instrumented build).**

- Run `zo2t_*` (aged session, pid 16392): free-pan + zoom-out glide produced full-width exact-SlateDark900 slabs (rows 452..658 ≈207 px; 703..1065 ≈362 px; 452..541). RT-D7 logs captured across the failing window; captures time-paired via file mtimes. Two clean frames interleaved between slab states.
- Run `agA_*`/`agB_*` (aged session pid 11744, enriched RT-D7 build): **trigger class A** (zoom-out glide in FREE) reproduced on 13 of 14 frames (slabs from 6-px hairlines to 1100+ px, positions changing per frame); **trigger class B** (recenter-tap loop, static FOLLOWING @17.5) reproduced the historical 5–6-px hairline signature (rows **725..730**, matching the old `rt_d5_12..14` 716..720) **stable across all 16 captures**.
- New facts established from the failing instants (each labeled):
  - **Confirmed:** slab color is *exactly* the app background `SlateDark900` (12,16,23) — not achievable through the tile night-color matrix (mathematically out of input range) and not equal to any slot placeholder/route-stroke blend; an opaque black diagonal drawn earlier (t2_now) is the same artifact family, confirming (12,16,23) appears in *failed-slot* states, not tile content.
  - **Confirmed:** slabs are **screen-anchored** — the 452..658 slab persisted *identically* across a tileZoom 17→16 flip while S jumped 885→1436 px per tile (map geometry leapt; slab did not move). Map-anchored slot edges cannot produce that.
  - **Confirmed:** at the failing instant the RT-D7 `rect` line reports **every exact/parent/ancestor bitmap present in the Coil memory cache** (all `P`), and the slab lies **interior** to a fully-covered composed slot row — so this is *not* a tile-loading gap and *not* slot-boundary rounding; whole slot Boxes painted *nothing* (not even their SlateDark800 background) in those pixel rows.
  - **Confirmed:** the slab persists while the screen state is static (agB: identical rows across ~5 s and recenter taps) yet re-positions/resizes between draw epochs during active glides (agA) — consistent with draw-phase anomaly, not composition math.
  - **Inferred:** candidates now narrow to (i) a Compose/HWUI draw-phase failure of specific slot subtrees inside the rotated `graphicsLayer`, or (ii) a screen-anchored full-bleed painter of exact SlateDark900 that toggles per draw epoch. HB sheets remain weakly disfavored (they occlude FABs; FABs draw over the slabs).
- **Magenta discrimination probe (D7-B):** placeholder temporarily recolored magenta (rebuild+install), two aged probe rounds run. Mechanism verified (a freshly relaunched map paints whole-screen magenta placeholders while tiles load), but **neither round reproduced the defect**, leaving the "hole vs overlay" branch undecided. Placeholder color restored to `SlateDark800` and reinstalled (final build re-verified rendering normally on device); RT-D7 logging retained while D7 is open.
- **Honest repro-rate record:** 2 hits / 4 misses today. Conditioning: sessions with ≥40 min of live ride time + heavy zoom/wifi churn reproduce; 15–27-min sessions did not (4 misses incl. both magenta rounds). No code change; D7 stays OPEN.
- New artifact inventory (all under `/tmp/opencode/`): `zo2t_*.png` + `d7_log_zo2t.txt`, `g2/g3/g4/g5/g6_*` + logs, `agA_*/agB_*` + `agA_log.txt/agB_log.txt`, `magA*/magB*` + logs, crops `crop_*`; enlarged temporary diagnostics in `RideMapCanvas.kt` (~L514–565: per-composition `range` + `rect`/cache lines, tagged TEMPORARY).

## 6. Device-protocol continuity (for the executors of future tasks)

- Device `adb -s fbe12520`; app `com.aistudio.ridetogether.vibe/com.example.MainActivity`; ride P3W5YP (Pune→Lonavala), rider ≈ 18.5968N 73.73E (`c17 = (92381.44, 58640.15)`); parked 0 km/h, hAcc 3 m.
- Screen coords (px): Zoom In (924,936); Zoom Out (924,1104); Re-center (924,594); Fit Group (924,768); lobby "START RIDE" ≈ (836,2184).
- Keep device awake: `svc power stayon usb`. Recovery from sleep/NotificationShade-lock: `input keyevent 4` ×2 + `keyevent 3` + relaunch; `cmd statusbar collapse` when needed; `wm dismiss-keyguard` unreliable.
- ~2.2 fps screencap ceiling; recenter tap must fire INSIDE the screencap loop.
- Exiting FOLLOWING needs a SHORT horizontal swipe (long/diagonal swipe opens Ride Controls sheet).
- Installs/corpus confined to `/tmp/opencode`.

## 7. Improvement backlog (live)

| P | Item | Status |
|---|---|---|
| P1 | D7 unpainted band root cause + minimal fix if proven | **reproduced twice w/ failing-instant logs; load-gap & slot-boundary causes eliminated; branch decision pending magenta-probe hit (2/4 repro rate)** |
| P2 | Remove temporary RT-D7 logging (incl. the added `rect`/cache lines) after P1 resolves | pending |
| P3 | Per subsystem spot-checks vs Google Maps behavior (search ranking, label collision, traffic overlay equivalents) — the mission-level sweep beyond camera/nav | partially covered by prior tasks (Photon search, label planner, theme, marker smoothing); **this session: amenity label planner upgraded** (per-name collision boxes + within-rank stability, 12/12 unit tests incl. fail-before/pass-after regressions; device re-validation of labels pending amenity data at current parked location) |
| P4 | Tile-scale calibration forensics (OpenCV matchTemplate vs `/tmp/opencode/tref/` OSM z17/z16 references) | paused; unreliable on flat night templates — requires template-std filtering or distinctive-feature approach |
| P5 | **Icon–label collision avoidance** (this benchmarking pass): planner now reserves against every rendered pin's painted footprint (`IconRect`) | **implemented + 21/21 planner tests**; on-device visual check pending amenity data — Overpass proven structurally blocked pre-fix (§7b A1: 414 on ≥4-cell GETs), pipeline now fixed; device + endpoint both pending |
| P6 | **Search quality pass** (this benchmarking pass): word-initial prefix ladder + identical-name near-duplicate collapse in `PlaceSearchRanking`; CreateRideScreen search-results auto-reveal fix | **implemented + live-provider-evidenced; 22/22 search/engine tests; full suite green; device-verified reveal fix** |
| P7 | Live RouteSearchService.kt deletion (pre-existing untracked `D` in git status) predates this program's changes — verify it is intentional work of another task, not accidental | unverified — flagged for the owning session |
| P8 | **Overpass pipeline optimization** (§7b): POST transport, consolidated QL, cache-first read, bounded retry w/ classification, versioned keys, batched DB ops | **implemented + 309/0 tests, build green, report updated**; device install + live `AmenityPerf` before/after metrics pending endpoint recovery + device reconnection |
| P9 | Overpass **mirror failover** (no hardcoding; health-checked list) | not implemented — kumi.systems + private.coffee HTTP 500 on 2026‑10‑19; no second healthy global mirror to validate against |
| P10 | Physical validation of POI icon–label avoidance + `AmenityPerf` latency/parse metrics on device | blocked on P8 device install AND overpass-api.de recovery (04:43–05:06+ outage documented) |

### 7a. POI/search benchmark findings (2026-10-19, late pass)

**Google Maps behaviors referenced (from Maps product knowledge + APK resource vocabulary; not from proprietary code):** icon pins always stay fully readable, labels never cover neighboring POI markers, suggestions appear immediately under the query field, identical POI entries collapse to fewer rows, prefix queries match at word starts.

1. **Icon–label collision (Task 1).** RideTogether drew amenity name pills *after* and *over* all pins; the planner's label-box collisions never considered marker geometry. Gaps eliminated by passing each pin's **painted footprint** (derived from the exact draw-code values: head-circle radius `pinRadius`, dual outline half-stroke 2.3 dp or selected halo 8 dp, tip, drop shadow radius `0.45×pinRadius` at `+1 dp`) into `AmenityLabelPlanner.plan(..., iconRects = ...)`. Rules: a label overlapping **another POI's** footprint is suppressed (icons win; own pin exempt — pills render below their own tip); suppression, not repositioning, keeps coordinates immutable; priority/stability semantics unchanged. Regression tests cover short/long names, adjacent+dense clusters where every label would cover some pin (both suppressed), unnamed-POI icons, clear-of-band icons, zoom gating and preserved displacement/stability.
2. **Word-initial prefix ladder (Task 2).** Live Photon checks (`q=lonavala`, `q=lon`, `q=wagholi`, `q=hotel/cafe/fuel lonavala`, reordered multi-token queries, case/whitespace variants) showed the **provider** already handles category words, token order, casing and whitespace; RideTogether's local ladder (then: exact / whole-name prefix / contains / address / provider) missed **word-initial prefixes** ("East London" for "lon" ranked as mere containment) and mixed them with word-exact matches ("Greater Lavasa Road" for "lavasa"). Final ladder: 0 exact · 1 name-starts-with · 2 **non-leading token starts with query** ("East London") · 3 mid-word contains · 4 address-only · 5 provider relevance. Ordering of the original test permutations is preserved (`relevance tiers are ordered…`).
3. **Identical-name near-duplicate collapse (Task 2).** Live `q="lonavala"` returns **five identical-name features** (station/platform/two stops/locality); the old dedup (ids + name plus exact 5-dp coords ≈ 1.1 m) let all five render as separate identical rows. New rule: same normalized name **and** pairwise distance < `NEAR_DUPLICATE_COLLAPSE_KM` = **0.3 km** → keep the first (provider-relevance leader). The radius is derived from the live measurements (transit cluster spread 0.024–0.267 km; distinct locality ~0.7 km away stays separate). *Data-coverage note kept honest: `q="fuel lonavala"` returns only a 344-km pagoda from Photon — that is provider dataset coverage, not a ranking bug; ranking cannot recover absent places (the app's Overpass amenity layer covers rider-relevant POIs instead).*
4. **CreateRideScreen results reveal (Task 2, measured device defect).** With the keyboard open, freshly returned suggestion rows composed and laid out (layout probe: results at device y≈1233, x 120–960 → 4-row list + red 4-dp probe box painted **nothing** and pushed no layout) because the **fixed bottom section** (route-preview card + Create button + bottom bar) draws over the tail of the scrollable column — every result state (network results, offscreen recents, no-results, failure rows) was invisible below the fold. Minimal fix: hoisted a single `screenScrollState` and a `LaunchedEffect` that auto-reveals the results area when either field's state settles into Results/NoResults/Failure (`animateScrollTo(maxValue)`), restoring the top when both fields return to blank idle. Device-verified: results now appear immediately under the query field with the keyboard up (`val_reveal1.png`); no search-state machinery touched.
5. **Unchanged by design:** recents plumbing, selection-integrity, bias validation, Photon endpoint/limits, URL building, stable ids — all previously verified with tests, still green.

**Artifacts of this pass:** device screenshots `val_map_z16.png` (healthy night map after upgrade), `val_search_r7.png` (pre-fix: list composed, invisible), `val_reveal1.png` (post-fix: visible suggestions), pixel-analysis + layout-probe notes; live provider query records in the conversation log.

### 7b. Overpass amenity-pipeline audit + optimization (2026-10-19, overnight pass)

**Mission:** audit and fix the Overpass POI-loading pipeline (request scheduling, query shape, cache correctness, resilience, renderer coupling) with measured evidence; no performance claims without measurements.

**Phase-1 audit result — six concrete defects, all evidenced live:**

| # | Defect | Evidence |
|---|--------|----------|
| A1 | **Multi-cell GET requests were structurally oversized**: the QL rode in the request URL; a 4-cell batch produced a **9036 B URL**, past the public endpoint's ~8 kB request-header cap → **HTTP 414 "Request-URI Too Large"**. Every dense-viewport fetch has been doomed since the feature's inception; ≤2-cell requests got through. | 4-cell QL/URL size replication probe (`/tmp/opencode/overpass_size_probe.py`); device logcat `retrofit2.HttpException: HTTP 414` ×2 at 04:40 |
| A2 | **HTTP 406 on a 2-cell GET** (OkHttp sends no `User-Agent`; curl's default UA succeeded from the same host/network on the same path) | device logcat `HTTP 406` 04:40:32; host curl A/B |
| A3 | Default **h2/OkHttp** turned Overpass load-shedding into `StreamResetException: stream was reset: CANCEL`; the identical request over **HTTP/1.1** surfaced real server responses instead | logcat diff across h2/h1 runs (04:04–04:41) |
| A4 | Read timeout **15 s < real Overpass latency 22–40 s** (device POST measured **HTTP 200 in 33.5 s**) — valid queries failed as timeouts | host+device probes; device curl 04:33 |
| A5 | **Fetch-before-read**: cached POIs were served only AFTER the network completed; an uncached viewport showed nothing for 20–40 s and failures showed nothing at all | `getAmenitiesInViewport` structure |
| A6 | Per-cell Room round-trips: up to **16 sequential `SELECT timestampMs`** + one insert per cache-mark row (each its own transaction) | DAO/repo audit |
| A7 | Cache keys **not query-versioned** (`14_x_y`) — a future QL change could serve stale-semantics rows for 7 days | key format |

**Fixes implemented (unchanged 3rd-party semantics; OSM/Overpass only, no Google services):**
1. `OverpassApiService`: GET → **`@POST("api/interpreter")` + `@FormUrlEncoded` `data` field** — canonical Overpass transport; kills A1 (and halves wire overhead for long queries).
2. `OverpassApiClient`: UA/Accept header interceptor (`RideTogether/1.0 (OpenStreetMap amenity loader)`) → fixes A2; **HTTP/1.1** → fixes A3; read timeout **60 s** → fixes A4.
3. `OverpassQueryBuilder` (new, pure, test-locked): 13 categories retained **exactly** (amenity: fuel/cafe/motorcycle_repair/restaurant/fast_food/toilets; shop: motorcycle_repair/convenience/supermarket; tourism: hotel/motel/viewpoint; highway=rest_area exact-match), consolidated into regex-union statements — **26 → 8 statements/cell**, 4-cell QL 6265 B → <4000 B (asserted), node+way symmetric coverage locked by tests.
4. `OverpassResilience` (new): failure classification with **bounded single retry + ±25 % jitter**; transient = IOException ∪ {408, 429, 500, 502, 503, 504}; **permanent = other 4xx** (400/406/410/414/418/501) fail fast without burning public-API quota; `CancellationException` never swallowed; **offline probe** (ConnectivityManager-backed, wired from the ViewModel) prevents doomed retries in Airplane Mode.
5. `AmenityRepository` rewritten: `getAmenitiesCached()` (DB-only, instant display) + `refreshAmenities()` (stale-cell refresh then re-read) → fixes A5; **one batched `SELECT … IN`** for timestamps and **one `Room.withTransaction`** for data+marks → fixes A6; **versioned keys `14_v2_x_y`** + one-time `instr(cellKey,'_v')=0` cleanup of legacy marks → fixes A7; `MAX_CELLS_PER_FETCH=16` retained; `overpassApi` now injectable for tests.
6. `RideViewModel`: debounce (400 ms) path now does the **instant cache-first publish**, then submits the refresh to the retained serial conflated `AmenityViewportWorker` (in-flight fetches uncancellable by camera motion; newest pending viewport wins — retains the StreamReset fix from the earlier pass). Post-refresh, the worker publishes the **current** viewport (not its own possibly-superseded one) so a pan during the 20–40 s network wait never regresses the shown POI set.
7. `LiveRideScreen`: amenity category filter hoisted into `remember(amenities, visibleCategories)` — stops per-GPS-tick re-filtering/reallocation and `pointerInput(amenities)` restart churn (Phase-6 renderer-coupling fix).
8. Instrumentation (`AmenityPerf` log tag): per-refresh cells/stale, per-fetch QL bytes, fetch OK elements/fetchMs/parseMs, fetch-failed transient flag — request counts and latency parse time now measurable in logcat for before/after comparisons.

**Cache-correctness semantics (Phase 4, test-locked):** successful-empty responses mark cells fresh (a genuinely sparse cell is a real OSM fact; 7-day mark avoids hammering) — a **failed response never writes marks or rows**, so transient outages cannot poison the cache; pre-existing rows always survive failures (Robolectric-verified for IOException *and* 4xx classes).

**Tests:** new suites `OverpassQueryBuilderTest` (6), `OverpassResilienceTest` (6), `AmenityRepositoryCacheTest` (8, in-memory Room), `AmenityRenderDensityTest` (2, 30-marker planner determinism/density cap), plus retained `OverpassClientTimeoutTest` (3) and `AmenityViewportWorkerTest` (3). Superseded `OverpassRetry`/`OverpassRetryTest` removed after `OverpassResilience` replaced it. Full suite **309 tests / 0 failures** (`:app:testDebugUnitTest` BUILD SUCCESSFUL after this pass; baseline 281 pre-pass, 291 at the earlier amenity-robustness sub-pass). `:app:assembleDebug` BUILD SUCCESSFUL (APK md5 `98c1a891cb60aa489c6031df002d51a4`).

**Device status (honest):** the final POST+UA+cache-first build could **not** be installed — the device dropped off ADB right before install (retry pending). Live POI validation additionally remains gated by the **documented overpass-api.de outage window**: host health poll shows continuous unavailability 04:43–05:06+ (fast-refusal and 15 s connect-timeout `000`s with two 502s; `/tmp/opencode/op_health.log`). The device-side proof that app-destined queries *can* succeed stays the 04:33 device POST (HTTP 200, 33.5 s, 30 KB via the healthy A record 65.109.112.52 while 162.55.144.139 refused TCP/443 — dual-A-record DNS ordering remains a live hydration hazard, mitigated by OkHttp route retry + `OverpassResilience`).

**Backlog added:** Overpass **mirror failover** (kumi.systems and private.coffee both returned HTTP 500 on this date; osm.jp untested; osm.ch is Switzerland-only — no second healthy global mirror available today to validate against); stale-refresh only triggers on viewport entry (no timers, by design).

**Offline verification status (2026-10-19, follow-up — no code changes made since acceptance):**
- Full offline regression re-run confirmed with `--rerun-tasks` (`:app:testDebugUnitTest` BUILD SUCCESSFUL): **309 tests / 0 failures across 32 suites**. Mapped to the acceptance items: cache-first = `AmenityRepositoryCacheTest` (8, incl. cached-POIs-with-zero-network-queries, failure-preserves-cache for IOException and 4xx, empty-success marks, versioned keys, pre-v2 cleanup, idempotent upsert) + `AmenityRepositoryTest` (3); dense-viewport renderer = `AmenityRenderDensityTest` (2, 30-marker determinism/density cap) + `AmenityLabelPlannerTest` (21) + `AmenityIconConfigTest` (4); offline/resilience = `OverpassResilienceTest` (6, incl. offline-never-retries + cancellation) + `OverpassClientTimeoutTest` (3) + `AmenityViewportWorkerTest` (3); navigation regression = `NavEngineAndSosTest` (6) + `NavigationProgressAndEtaTest` (17) + `NavigationTelemetryRegressionTest` (27) + `RideViewModelRerouteAndDistanceTest` (7) + `Task17RiderMarkerSmoothingTest` (31) + `MapCameraControllerTest` (20) + `LocationTimeoutAndManualOriginTest` (4).
- **Device offline-validation assets prepared** (host-side tooling, no app-code changes): `/tmp/opencode/seed_amenities.py` builds `seed_cache.sql` from the **real live Overpass probe capture** (`poi_marunji.json`: 392 real OSM elements, 371 named, lat 18.5802–18.6199 / lon 73.7001–73.7799) using the exact app-side category mapping; validated end-to-end against a DB copy (integrity_check clean; mapping yields RESTAURANT 236 / CAFE 56 / HOTEL 34 / FUEL 22 / CONVENIENCE_STORE 21 / REPAIR 15 / TOILET 8; 14 amenities within ~600 m of the parked pin; 25 fresh `14_v2_*` cache marks spanning a ±2-cell window around it, cell 11547/7330). `/tmp/opencode/inject_seed.sh` scripts the on-device procedure (force-stop → run-as pull → WAL checkpoint + integrity check → seed apply → in-app rollback copy → run-as push back → verify). Running this the moment the device reconnects validates the **cache-first display path and label-planner rendering on the physical device with realistic dense data entirely offline** — no live Overpass needed and no hammering of the down endpoint.
- Still blocked, honestly: (a) install + MD5/py-verify of build `98c1a891…` — superseded: `556a7b45…` (which contains the whole §7b pipeline) **was installed 12:33**; (b) live `AmenityPerf` request-latency/HTTP-status/cache-hit/parse-DB timings — **now partially unblocked, see below**; (c) endpoint state monitored with **spaced probes** (one small `out` query per 15 min, `op_health2.log`) rather than a hammering loop. The window 04:43–06:00+ showed continuous `000`/502 responses.

**Device validation of §7b (2026-10-19 12:43–13:01, apk `556a7b45…`):**
- **Offline cache-first display: PASS.** The prepared seed was injected via `inject_seed.sh` (with two script fixes applied manually: the in-app backup `run-as cp` quoting, and single-transport `-s fbe12520` targeting; device DB verified: 406 amenity rows / 41 fresh `14_v2_*` marks). With overpass-api.de still refusing the host probe, the ride screen rendered the full seeded cluster — fuel/café/restaurant/hotel markers with anchored labels ("Hindustan Petroleum", "Rajmundra Petroleum") at 500 m scale (`av5`–`av10`), proving the **cache-first read path + label planner on-device with realistic dense data, offline from Overpass**.
- **Live Overpass recovery observed: PASS (degraded latency).** At 12:56–13:01 the device completed real live fetches: `AmenityPerf: fetch ok: cells=16 elements=3 fetchMs=44214`, then `elements=13 fetchMs=39461` and `elements=51 fetchMs=48517` — bounded (boundedTo=16) and cache-merged (`refresh done: cells=2793 stale=2697 (boundedTo=16)`); the host-side probe still timed out (`000` at 30 s) in the same window, so recovery is network-path dependent (consistent with the dual-A-record hazard in the audit). `AmenityPerf` end-to-end timing now has live device measurements.
- Honest remainders: label alignment was validated visually at the tested zooms only; no airplane-mode sweep after seeding (the cache-first display was instead proven with the endpoint effectively unreachable); Overpass mirror failover remains unimplemented backlog.

### 7c. Multi-waypoint intermediate stops & multi-leg navigation (2026-10-19, morning pass)

New user-task implementation (original code, Google Maps as behavioral reference only):

**Phase-A audit findings (pre-implementation):** origin/destination live as flat `Ride` fields fed by confirmed `SelectedPlace` coordinates (never unconfirmed suggestions); OSRM request assembled in `OsrmRoutingService` (`/route/v1/driving/lon,lat;lon,lat?overview=full&geometries=geojson&steps=true`, HTTPS→HTTP fallback); the parser **discarded all legs except `legs[0]`**; `NavEngine` works on the single merged polyline with step→vertex mapping, monotonic forward-window segment search, >120 m off-route and a latched 35 m arrival; **leader writes only distances to the ride doc — members re-fetch their own route client-side**; protective suites: `RideViewModelRerouteAndDistanceTest`, `NavigationProgressAndEtaTest`, `NavigationTelemetryRegressionTest`, `Task17RiderMarkerSmoothingTest`, `NavEngineAndSosTest`.

**Files changed & why**
- `data/model/RideModels.kt` — `RouteStopRole`/`RouteStop` (stable id survives reorder/edit/Firestore round-trip; origin & destination tracked as explicit roles so they never appear duplicated inside the intermediate collection) and `RouteLeg` (per-leg data located in the merged overview polyline via `startVertexIndex`; the map keeps drawing one continuous road-following line — no synthetic leg-endpoint connectors); `RouteResult` gains `waypoints/stops/legs/planVersion` (all defaulted → legacy call sites compile unchanged); `Ride` gains `routeStops/routePlanVersion` (defaulted → legacy rides identical).
- `data/location/OsrmRoutingService.kt` — multi-waypoint requests serialize `origin;s1;…;dest` in exact user order, strictly `lon,lat`; **strict leg contract: response rejected unless `legs.size == waypoints.size - 1`**; per-leg steps flattened in order with named intermediate arrivals ("Arrive at stop: X"); `RouteRequestException` carrying `osrmCode` (NoRoute/NoSegment get friendly messages; malformed JSON/HTTP errors/missing routes rejected); request-side validation (finite/in-range coords, duplicate-consecutive waypoints <1 m rejected, ≤25 waypoints); the marked straight-line fallback is kept *only* for the zero-intermediates legacy path — intermediate-stop requests never silently degrade to a straight line. Testable seams: internal `buildCoordinatePath` + `validateWaypoints`.
- `data/location/RouteProvider.kt` (new) — route-calculation seam so `RideViewModel` is deterministically testable without live OSRM; `OsrmRoutingService` implements it.
- `data/location/NavEngine.kt` — `NavigationProgress.currentLegIndex/legCount`; leg advancement is **at most one leg per fix** (a single stale/jumpy fix can never skip closely spaced stops), accuracy-gated (≤30 m) and off-route-gated, with a crossing test (closer-to-next-vertex than-to-stop-vertex) so sparse vertex spacing cannot prematurely complete a leg; remaining distance/ETA need no transition handling (merged polyline already counts through future legs); `getNextStop()` exposes the next required stop in order; `reset()` clears counters.
- `ui/viewmodel/RideViewModel.kt` — `createRide(...)` gains an ordered intermediate-stops plan (invalid stops dropped by `isValidCoordinate()`; plan applied atomically with the ride); explicit `_routePlanError` no-route/routing-error StateFlow (last valid route and nav state are never cleared on planning failure); `fetchRouteForRide` validates response legs against the plan before applying; `rerouteFromCurrentLocation` preserves the remaining waypoint sequence: remaining = plan after the authoritative `currentLegIndex`, destination kept last, order unchanged; `joinRideWithCode` adopts the leader's shared plan verbatim (missing field = legacy single-destination); `CancellationException` is rethrown in planning paths (stale-request cancellation stays structured); **new `RouteProvider` injection** via a secondary `(Application)` constructor preserved for `AndroidViewModelFactory` (an initial single-constructor change broke `MainActivity` model instantiation — caught by the `ExampleRobolectricTest` activity-launch regression and fixed before any re-run claim).
- **`startRide()` re-apply (documented necessary fix):** ride start previously `navEngine.reset()`ed and never re-applied the route, leaving segment/leg progression dead for the whole ride; it now re-applies the **live validated route** (not derived/stale ride data) after the reset — preserving the reset's purpose (clearing arrival latches/leg counters) while making leg progression and reroute stop-preservation actually work in-app.
- `data/firebase/RideRepository.kt` — `createRide` persists the leader's ordered plan (`routeStops` + `routePlanVersion = 1`) with the ride document atomically; snapshot listeners parse `routeStops` and adopt only **strictly newer** plan versions (`RouteStopCodec.mergeRoutePlan` — stale snapshots never overwrite); `updateRoute` keeps writing only distances (copy semantics preserve the plan), so member devices cannot overwrite the leader's plan through ordinary flows; rider-location updates never touch the plan fields.
- `data/model/RouteStopCodec.kt` (new) — versioned backward-compatible Firestore schema: ordered array of `{id, name, address?, role, lat, lng, order}` + Long version; legacy rides without the field parse to an empty plan; malformed entries are skipped, unknown roles fall back to INTERMEDIATE.
- `ui/screens/CreateRideScreen.kt` — Maps-style intermediate-stops editor: add stop, per-row remove, up/down reorder (deliberate smallest stable interaction; drag-and-drop omitted — no reorderable-list dependency exists in the project and no new dependencies are warranted), one `PlaceSearchEngine` per row (same pattern as origin/destination: only confirmed selections ever route), confirm-all-for-create validity, live "Route: Start → 1. … → 2. … → Destination" sequence line, cap 8 stops; route estimate recalculates including stops and is order-sensitive.
- `MainActivity.kt` — one lambda adaptation (new `intermediateStops` parameter).
- Tests (new, 34): `MultiWaypointOsrmParsingTest` (13 — legs preserved, totals consistency, per-leg step association, leg-mismatch/NoRoute/NoSegment/malformed/empty responses, lng,lat wire order, invalid/duplicate waypoints), `RouteStopCodecTest` (6 — stable round-trip order, legacy empty, malformed entries, stale/newer/equal version merge guards), `NavEngineMultiLegTest` (8 — initial leg, advancement at stops, distance/ETA coherence across transitions, single-advance-per-fix for closely spaced stops, stale-fix and off-route non-advancement, no-stop regression, `getNextStop`), `RideViewModelMultiWaypointTest` (7 — legacy no-stop behavior, 1–2 stops ordered serialization, invalid coordinates rejected, reorder changes request order, reroute preserves remaining sequence through the original destination, failed reroute retains route+nav state, stale gated response cannot overwrite a newer route).

**Verification (honest):** full offline suite with `--rerun-tasks`: **343 tests / 0 failures / 36 suites** (previously accepted 309/0/32 + 34 new). `:app:assembleDebug` green — apk md5 `556a7b4590affa971e6bd35d2a951752` (supersedes `98c1a891…`, which was never installed — device still off ADB at the time of the build).

**Device + live validation (2026-10-19 afternoon, device fbe12520 / RMX3360, apk `556a7b45…` installed 12:33, `lastUpdateTime` verified):**

| Check | Result | Runtime evidence (screenshots in `/tmp/opencode/`) |
|---|---|---|
| Stop-editor UI on device | **PASS** | Stop editor renders with reorder arrows, ✕ remove, confirm-all gating (Create dimmed while a stop is unconfirmed: `mw8`, `b5`); `b16`/`b20`/`d7` show confirm flow; `e1` reorder flip ("Start → 1. Pavana → 2. Kamshet → Destination") with confirmations preserved; `e2` remove → "1 added" |
| Estimate responds to plan changes | **PASS** | 0 stops → 50.2/50.3 km · 41 min (`b14`,`d5`); same stops in-order → 100.2 km · 85 min (`b23`); after removing a stop → 50.3 km · 44 min (`e2`). An absurd 18066 km estimate (`d8`) traced to a **distant Photon "Pavana" match (Italy, 44.12/11.00) selected by an unverified row tap** — app routed faithfully to the selected place; input artifact, not a bug. UX note: confirmed stop rows display name only, so a distant wrong match is only visible through the estimate |
| Live OSRM multi-waypoint fetch (device→service) | **PASS** | Create-screen estimate round-trips real OSRM with intermediates (`mw14` 90.6 km · 1h22 for GPS→Kamshet→Talegaon→Lonavala); lobby + live ride render the multi-waypoint road route with 4 waypoint markers (`mw15`,`mw16`) |
| Strict leg contract (legs == waypoints − 1) | **PASS (host reconstruction)** | `GET /route/v1/driving/73.7332,18.6042;73.4056,18.7508;73.6738,18.6573;73.4017,18.7549` → `code: Ok`, **legs: 3** (50.5 km / 42.5 km / 43.6 km), total 136.6 km — wire order and leg count confirmed against the live service; in-app strict validation is unit-covered |
| One continuous route line / no straight-line fallback | **PASS** | `mw16` (fresh start) and `c2` (2-stop ride WSTGJW): single continuous road-following polyline through stop order; no synthetic connectors, no fallback geometry |
| Maneuvers bound to correct legs | **PARTIAL** | Device shows leg-0 steps with "Then:" chaining (`mw16`,`c2`); per-leg association for later legs covered by unit tests (`MultiWaypointOsrmParsingTest`), not device-drivable without motion |
| Leg advancement at a stop | **NOT RUNTIME-VERIFIED** | Requires physical motion past a stop; device stationary. Unit-covered by `NavEngineMultiLegTest` (single-advance-per-fix, closely-spaced stops) |
| Leader-side reroute preserving remaining stops | **PASS (stationary)** | Ride WSTGJW (Kamshet+Pavana, 100.2 km plan): manual ↻ reroute completed, plan meta unchanged (100.2 km · 85 min), Remaining recomputed to ~full-plan distance — i.e. no stop dropped (`c3`→`c4`); only completed-legs=0 variant exercised |
| Firestore plan adoption after process death | **PASS (indirect)** | Ride 2TNHUJ (90.6 km Kamshet+Talegaon plan): force-stop → relaunch → the plan was re-adopted from the ride document and a recovery reroute rebuilt a multi-stop route (Remaining 122.2 km via remaining stops, `av11`) — `routeStops` restoration + remaining-stop-preserving reroute worked end-to-end |
| Legacy rides (no `routeStops`) | **PASS** | Pre-feature ride P3W5YP joined by code (`f6` lobby: single-destination 50.4 km · 41 min, no stop editor artifacts) and navigated (`f7` live: turn guidance, Remaining 50.4, Tracking) with zero behavioral change |
| Member-device plan sync | **NOT RUNTIME-VERIFIED** | Requires a second device; `RouteStopCodec` merge guard + member adoption are unit-covered (`RouteStopCodecTest`, `RideViewModelMultiWaypointTest`) |

**New device-discovered pre-existing gap (follow-up, not this feature's regression):** `RideRepository.updateRoute` writes **only** `initialPlannedDistanceMeters`/`totalDistanceMeters`/`totalDurationSeconds` to Firestore — `routePoints`/`navigationSteps` live in memory only. After a process kill, the restore listener finds `ride.routePoints` empty → `routeEligible` fails → NavEngine stays empty (Remaining 0 m, no route line, `av2`–`av10`) **until** the adaptive off-route reroute rebuilds a route (which is what recovered ride 2TNHUJ). This is the same "leader writes only distances" Phase-A behavior, now device-confirmed; a proper fix (persist geometry or refetch on restore) is a separate scoped change.

**Scope notes:** in-ride stop editing deliberately not shipped (the task text permits keeping scope to pre-ride planning; leader/member ordering authority rides on the same plan fields and needs its own sync slice); no Google APIs, no new dependencies, no dead-reckoning/bearing/camera/dedup changes; `PlaceSearchService.kt` pre-existing deletion and every other working-tree file untouched.

**Companion audits (same directory, all read-only unless stated):**
- `gmaps-rendering-visual-fidelity-audit.md` — map renderer / visual language audit (Phase A) + **Phase B implementation log** (P1 diagnostic removal, P2 route rendering rework, P3 halo labels, P4 dynamic scale bar, P5 hit targets).
- `nav-banner-maneuver-distance-audit.md` — **Feature 1A** audit of the next-turn banner and maneuver distance. Headline finding: the banner and the voice pair the **previously performed** maneuver's instruction/icon with the **next** maneuver's distance (off-by-one), device-confirmed (`82 m` / `Head left` / `Then: Turn right` against live OSRM `depart/left @ 0 m`, `turn/right @ 80.2 m`); plus a reroute-related voice-silence defect and unguarded multi-maneuver advancement. Implementation is **not** started — it awaits explicit approval.

## 8. Repo / worktree state (this session)

- Main repo HEAD restored after a filesystem corruption event: `b7406b5875b165c5c944359c4c7d256aebfabe8a` (21 zero-byte loose objects deleted; objects re-fetched from origin; fsck clean; backup of pre-repair `.git` at `/tmp/opencode/dotgit_backup_20261019_004455.tar.gz`).
- Main repo had 80 modified + meaningful-untracked source files from the ongoing program; snapshot-carried into the worktree as commit `f103759` on branch `work/google-maps-feature-enhancements` (baseline noted in commit message). Main repository working tree untouched since (no branch switch). Snapshot commit `ef60bff` (report) lives in the worktree branch as archive.
- **Directives now ban worktrees**: main repo is authoritative. This report exists in the main repo at `docs/reverse-engineering/google-maps-feature-enhancements.md` (ported from the worktree copy and extended in-place).
- 2026-10-19 late-session main-repo changes (uncommitted, per program convention): enriched temporary D7 diagnostics in `RideMapCanvas.kt`; `AmenityLabelPlanner.kt` collision/stability upgrade + `RideMapCanvas.kt` candidate-width estimate call site; new regression tests in `AmenityLabelPlannerTest.kt`; `MapAppearancePalette.kt` untouched net (magenta diagnostic added then fully reverted). Full unit suite green (`:app:testDebugUnitTest` BUILD SUCCESSFUL).
- 2026-10-19 POI/search benchmarking pass (later the same night, uncommitted): `AmenityLabelPlanner.kt` icon-footprint reservation (`IconRect` + `plan(..., iconRects=...)`); `RideMapCanvas.kt` pin-footprint recording + planner call-site; `PlaceSearchRanking.kt` word-initial prefix ladder + `NEAR_DUPLICATE_COLLAPSE_KM` = 0.3 identical-name collapse; `CreateRideScreen.kt` shared `screenScrollState` + search-results auto-reveal effect; `PhotonPlaceSearchTest.kt` +4 tests (22 total), `AmenityLabelPlannerTest.kt` +9 tests (21 total). Full suite **281 tests / 0 failures**; debug build installed; search reveal + near-dedup verified live on device (`val_reveal1.png`).
- 2026-10-19 Overpass pipeline pass (overnight, uncommitted, §7b): `OverpassApiService` POST + form-body; `OverpassApiClient` 60 s read timeout + HTTP/1.1 + UA/Accept interceptor; new `OverpassQueryBuilder` (13-category coverage locked), `OverpassResilience` (bounded retry + classification + offline probe), `AmenityViewportWorker` (serial conflated); `AmenityDao`/`AmenityRepository` batched/transactional/versioned rewrite + cache-first read; `RideViewModel` cache-first debounce + worker wiring + ConnectivityManager probe; `LiveRideScreen` remembered amenity filter. New tests: QueryBuilder 6, Resilience 6, RepositoryCache 8, RenderDensity 2, ClientTimeout 3, ViewportWorker 3; `OverpassRetry` superseded and removed. Full suite **309 tests / 0 failures**; `:app:assembleDebug` green (apk md5 `98c1a891…`). Device install of this build **pending** (device off ADB); live amenity validation additionally gated by the overpass-api.de outage window (§7b).
- Worktree `/home/arpit/Projects/RideTogether-google-maps-enhancements`: retained as untouched archive (branch `work/google-maps-feature-enhancements`, HEAD `ef60bff`); no longer used per directive.
- 2026-10-19 multi-waypoint pass (uncommitted, §7c): new stable-id stop model + OSRM multi-leg parsing (`legs == waypoints − 1` contract), leader-persisted ordered plan (`routeStops` + `routePlanVersion` with stale-snapshot merge guard), leg-aware `NavEngine` progression, remaining-stop-preserving rerouting, stop-editor UI, `RouteProvider` test seam, startRide re-apply fix. New tests: 13 + 6 + 8 + 7 = 34; full suite **343 tests / 0 failures / 36 suites** with `--rerun-tasks`; apk md5 `556a7b4590affa971e6bd35d2a951752`. Pre-existing uncommitted state (incl. the `PlaceSearchService.kt` deletion) preserved.
- 2026-10-19 device-validation pass (afternoon, uncommitted, §7c table): apk `556a7b45…` installed on fbe12520 (12:33, `lastUpdateTime` verified); stop-editor UI, live OSRM multi-waypoint fetch/estimate, leg contract (host reconstruction), continuous route line, stationary leader reroute, post-kill plan re-adoption, and legacy-ride (P3W5YP) behavior all verified with screenshots in `/tmp/opencode/`. Two honest gaps remain: leg advancement at a stop and member-device sync (no motion / no second device). Device-confirmed pre-existing restore gap documented (route geometry not persisted). Test rides created during validation remain live in Firestore: 2TNHUJ (Kamshet+Talegaon), WSTGJW (Kamshet+Pavana); P3W5YP left in the live state on the device.

## 9. Limitations & open questions

- No full `jadx` code decompilation in this environment (host RAM-limited; jadx 1.5.2 present and works on smaller targets, stalls mid-run on this 291 MB/44.6k-class APK); static APK code work relies on apktool resources + targeted `dexdump` queries.
- Google Maps' *internal* implementation details that lack runtime evidence must remain "inferred"; only artifact-verifiable claims may be labeled confirmed.
- D7 reproduction depends on aged-session conditioning (2 hits / 4 misses across today's probe variants); probes must budget ~40 min of live session time per attempt.
- In-motion scenarios (DR 7–9, course-over-ground bearing, tunnel GPS-loss) remain blocked until a real ride.

## 10. Recommended next investigation

1. Execute the standing D7 probe (Section 5) — the only pending item that changes code.
2. After D7 resolution: remove RT-D7 logging, run `./gradlew :app:testDebugUnitTest`, device revalidation sweep (FOLLOWING @17.0/16.5/17.5, free-pan int zoom, in-loop recenter transitions, pan/recenter stress) and close task 3 in the Phase-4 report.
3. Then continue the mission-level sweep (Section 7 P3) one subsystem at a time.
