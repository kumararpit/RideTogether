# Google Maps Reverse-Engineering & RideTogether Feature-Enhancement Report

Workstream: `work/google-maps-feature-enhancements`
Worktree: `/home/arpit/Projects/RideTogether-google-maps-enhancements`
Status: **in progress** — this report consolidates all program evidence to date and is updated as each task completes.

---

## 1. Subject APK — verified metadata

| Field | Value | Evidence |
|---|---|---|
| File | `/home/arpit/Downloads/googlemap.apk` | verified readable |
| Size | 304.2 MB (304,176,297 bytes) | `ls` |
| SHA-256 | `6218d72f48b184eb71f68879bce48cb9ebb2b510be7f7cff88217ea3fd592bb2` | `sha256sum` |
| Package | `com.google.android.apps.maps` | `pyaxmlparser` AXML parse |
| versionName | `26.42.01.997557324` | same |
| versionCode | `1068832648` | same |

Toolchain available in this environment:

- `apktool 3.0.3` (`/home/arpit/.local/bin/apktool`) — resources/manifest/smali.
- `adb` — runtime probes on physical device `fbe12520` (RMX3360, 1080×2400, density 3.0).
- `pyaxmlparser` (pip, installed this session) — AXML metadata.
- `python3` + OpenCV 5.0.0 + numpy — screenshot forensics and tile-image calibration.
- REA reverse-engineering MCP server — binary/native/browser inspection.

Tool limitations (recorded for evidence hygiene):

- `jadx` binary missing (only stale config dirs at `~/.config/jadx`, `~/.cache/jadx`). No `aapt`/`aapt2`/`apkanalyzer`/`dex2jar`/`baksmali`.
- REA `inspect_adb_device` output unreadable in this environment — use `adb getprop` fallback; do not retry.
- OpenCV `matchTemplate` on flat night-tile textures produces degenerate score-1.0 matches; template std must be filtered (<10 rejected).
- No root on device: two-finger gestures unscriptable, no `screenrecord`, screencap ceiling ≈ 2.2 fps.
- Copying Google proprietary code/assets is prohibited; all RideTogether changes are original implementations on the OSM-based stack (Photon/OSM/whatever the independent providers are).

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
| P1 | D7 unpainted band root cause + minimal fix if proven | source audit done; reproduction pending |
| P2 | Remove temporary RT-D7 logging after P1 resolves | pending |
| P3 | Per subsystem spot-checks vs Google Maps behavior (search ranking, label collision, traffic overlay equivalents) — the mission-level sweep beyond camera/nav | partially covered by prior tasks (Photon search, label planner, theme, marker smoothing); needs a fresh pass against this framework |
| P4 | Tile-scale calibration forensics (OpenCV matchTemplate vs `/tmp/opencode/tref/` OSM z17/z16 references) | paused; unreliable on flat night templates — requires template-std filtering or distinctive-feature approach |

## 8. Repo / worktree state (this session)

- Main repo HEAD restored after a filesystem corruption event: `b7406b5875b165c5c944359c4c7d256aebfabe8a` (21 zero-byte loose objects deleted; objects re-fetched from origin; fsck clean; backup of pre-repair `.git` at `/tmp/opencode/dotgit_backup_20261019_004455.tar.gz`).
- Main repo had 80 modified + meaningful-untracked source files from the ongoing program; snapshot-carried into the worktree as commit `f103759` on branch `work/google-maps-feature-enhancements` (baseline noted in commit message). Main repository working tree untouched since (no branch switch).
- Worktree `/home/arpit/Projects/RideTogether-google-maps-enhancements`: clean at commit time; report created.

## 9. Limitations & open questions

- No jadx/apkanalyzer/aapt in this environment; static APK work relies on apktool + pyaxmlparser (sufficient for manifest/resources; classes require REA-based inspection rather than full decompilation).
- Google Maps' *internal* implementation details that lack runtime evidence must remain "inferred"; only artifact-verifiable claims may be labeled confirmed.
- D7 reproduction depends on a flaky screen-sleep recovery loop and an intermittent failure trigger; probe may need several attempts.
- In-motion scenarios (DR 7–9, course-over-ground bearing, tunnel GPS-loss) remain blocked until a real ride.

## 10. Recommended next investigation

1. Execute the standing D7 probe (Section 5) — the only pending item that changes code.
2. After D7 resolution: remove RT-D7 logging, run `./gradlew :app:testDebugUnitTest`, device revalidation sweep (FOLLOWING @17.0/16.5/17.5, free-pan int zoom, in-loop recenter transitions, pan/recenter stress) and close task 3 in the Phase-4 report.
3. Then continue the mission-level sweep (Section 7 P3) one subsystem at a time.
