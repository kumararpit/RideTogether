# Google Maps Reverse-Engineering & RideTogether Feature-Enhancement Report

Workstream: `work/google-maps-feature-enhancements`
Worktree: `/home/arpit/Projects/RideTogether-google-maps-enhancements`
Status: **in progress** — this report consolidates all program evidence to date and is updated as each task completes.

> **2026-10-20 master-mission addendum (Sections 11–15).** A twelve-category reverse-engineering sweep was executed against Google Maps 26.42.01.997557324 and the RideTogether worktree, producing per-feature specifications (`feature-01…feature-12-*.md`), a consolidated **evidence ledger** (`google-maps-evidence-ledger.md`) and a prioritized **implementation backlog** (`google-maps-implementation-backlog.md`). Sections 1–10 below are the historical record and are **unchanged**; the mission results are appended in §11–§15. The audit was **read-only** — no application source, test, Gradle, schema, or build artifact was modified, and the main repository was read but not touched.

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

**Probe record — 2026-10-19 session (instrumented build, pid 16392).**

- Pass 1 (`/tmp/opencode/gma_d7c_00..19.png`, log `/tmp/opencode/d7_log_c.txt`): short-swipe follow-exit → ~15 s FREE → capture loop with recenter tap inside (between frames 06/07). Result: **no band**; max exact-SlateDark900 row fraction 0.02. Log confirms clean anchor flip 1104.0 → 1330.8 with contiguous rowsY at every logged composition; cXY fractional-drift values confirm per-frame recomposition was active through the transition.
- Pass 2 (`/tmp/opencode/d7stress_1..6_{a..d}.png`, log `/tmp/opencode/d7_log_stress.txt`): 6× pan→recenter stress (24 captures). Result: **no band**; max fraction 0.022; 121 transition-log entries, all contiguous.
- Interpretation: the reproduction trigger remains session-state-conditioned (historical captures occurred in an aged session with tile-load stalls). Two consecutive clean stress passes on the exact trigger class (pan→recenter) now stand. Per the no-fix-without-failing-coordinate rule: **no code change; D7 stays OPEN**. The instrumented RT-D7 logging remains in place intentionally while D7 is open (removal is backlog item P2).

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

---

# 11. Master-mission results (2026-10-20, read-only sweep)

## 11.1 Scope executed

All twelve feature categories were investigated against the decompiled corpus (`/tmp/opencode/gmaps-src`, 68,503 files), the apktool-decoded resources (`/tmp/opencode/gmaps-res`), the native symbol inventory (`/tmp/opencode/jni_symbols.txt`, 442 exported JNI symbols), the physical device (fbe12520), and the RideTogether worktree source. Three parallel corpus-mining passes covered search/POI, camera/rendering, and route/traffic/overlays; the remaining categories were traced directly against RideTogether source and device captures.

| # | Feature | Spec | Status | Headline result |
|---|---|---|---|---|
| 1 | Turn-by-turn guidance & next-turn banner | `feature-01-navigation-guidance.md` | investigated | **P0 defect**: the banner/voice pair the *previous* maneuver's instruction with the *next* maneuver's distance (device-confirmed). Reference engine is native → unavailable |
| 2 | Navigation camera & gestures | `feature-02-camera-gestures.md` | investigated | Reference has 8-property 3D camera, 10 nav modes, explicit gesture arbitration (10° pinch/rotation, 45° tilt) and a 30 fps frame regulator. RT's 2D model is a deliberate choice; gaps: arbitration thresholds, frame budget, step/arrival modes |
| 3 | Map canvas rendering & cartography | `feature-03-map-rendering.md` | investigated | Reference: two renderers, ancestor-tile fallback, per-zoom **interpolated style tables**, zoom-scaled stroke widths, native labeler. RT matches on ancestor fallback; gaps: per-frame recomposition, no zoom-interpolated widths, no layer registry |
| 4 | Route line, geometry & waypoints | `feature-04-route-rendering.md` | investigated | Reference: 3-band stroke ≈24 dp (overview) / ≈17 dp (nav), alternatives, anchored pills, viewport-clipped geometry, chevrons driven by *remaining* distance. RT: fixed ≈32.5 dp, per-frame full-polyline rebuild |
| 5 | Amenities, POIs & map markers | `feature-05-amenities-poi.md` | investigated | RT pipeline is **complete and device-validated** (cache-first, versioned keys, dedup, resilience). Gaps: relevance-weighted priority, palette vs. the OSM raster, clustering, selection semantics |
| 6 | Place search & destination selection | `feature-06-place-search.md` | investigated | Reference: gRPC-Lite, **server-side ranking**, ≤50 m dedup, "re-query rather than guess" coordinates, disambiguation by displayed locality. RT's client ranking is sound; the **distant-place risk is confirmed** (name-only stop rows) |
| 7 | Route planning & multi-stop management | `feature-07-multi-stop-routing.md` | investigated | **The worktree lacks the multi-stop feature** (it exists uncommitted in the main repo). OSRM contract verified live (`legs == waypoints − 1`, per-leg steps, `depart` at 0 m) |
| 8 | Rider position, orientation & marker animation | `feature-08-rider-markers.md` | investigated | RT's qualified bearing + bounded render-only DR are **at reference grade and identical in both trees**. Gaps: accuracy/freshness disclosure, "direction unknown" state, per-frame allocations |
| 9 | Group ride & multi-rider map | `feature-09-group-ride-map.md` | investigated | The reference has **no** group feature (nothing inferred). RT gaps: no marker stacking/clustering, first-match hit testing, no last-seen age, no relative position |
| 10 | Traffic, road conditions & alternatives | `feature-10-traffic-data.md` | investigated (partly blocked) | Traffic/alternatives are **proprietary-service features** (boundaries documented, never called). The *presentation* is generic and separable; RT has no traffic feature |
| 11 | Navigation overlays, controls & hierarchy | `feature-11-navigation-overlays.md` | investigated | Reference has a **declared 25-layer overlay z-order**, publishes banner height/sheet exposure to the renderer, a 48 dp target floor, and a11y semantics. RT: no overlay contract, no chrome coupling, px-based hit target |
| 12 | Lifecycle, resilience & performance | `feature-12-resilience-performance.md` | investigated | RT's degradation strategy is sound; gaps: route geometry not persisted (device-confirmed), per-frame diagnostics, a **live-Firebase test that hangs the offline suite** |

## 11.2 Confirmed defects (ranked)

| ID | Sev | Feature | Defect | Evidence |
|---|---|---|---|---|
| F1-D1 | **P0** | 01 | Banner + voice pair the previous maneuver's instruction/icon with the next maneuver's distance | E-F1-11/E-F1-12; device `1a_state.png` + live OSRM |
| F1-D3 | P1 | 01 | After a reroute the voice is silent for the new route's first maneuvers | E-F1-14 |
| F12-D1 / F7-D2 | P1 | 12 / 07 | Route geometry is never persisted ⇒ a killed process restores with no route | E-F7-05 (device-confirmed) |
| F6-D1 | P1 | 06 | A confirmed stop can hide a geographically distant place (name-only row) | E-F6-04/E-F6-08; device `d8` |
| F1-D2 | P1 | 01 | Unbounded multi-maneuver advance; straight-line 25 m test; no accuracy gate | E-F1-13 |
| F12-D2 | P1 | 12 | Composition-phase diagnostics (186 log lines / 20 s; 9.78 % janky) — **removal blocked by D7** | E-F12-01/02 |
| F12-D3 | P2 | 12 | The offline suite is not hermetic (live Firebase inside Robolectric `runBlocking`) | E-F12-03 |
| F3-D1 | P1 | 03 | Per-frame recomposition of the tile layer | E-F3-11 |
| F4-D1/D2 | P1 | 04 | Per-frame full-polyline rebuild, no simplification | E-F4-05 |
| F9-D1/D2 | P1 | 09 | No rider-marker stacking; first-match hit testing selects the wrong rider | E-F9-02/03 |
| F11-D2/F11-D3 | P1 | 11 | No chrome→renderer coupling; px-based rider hit target | E-F11-06 |
| F5-D1/D3 | P1/P2 | 05 | Category-only amenity priority; no clustering | E-F5-06 |
| F2-D1/D3 | P2 | 02 | No gesture arbitration thresholds; no frame budget | E-F2-11/12 |
| F8-D1/D4 | P2 | 08 | No accuracy/freshness disclosure; missing bearing renders as north | E-F8-05 |

## 11.3 What was **not** obtained (proprietary / unavailable)

- The reference's maneuver selection, distance-to-maneuver projection, transition timing, announcement thresholds and duplicate suppression are **native** (`RouteGuiderJni`, `CameraControllerJni`, `SharedLabelerJni`, `ActiveRouteMonitorJni`, `ArrivalManagerJni`, `LiveTripsGuiderJni`) or **server-side** (search ranking, traffic, alternatives, directions) ⇒ no claim is made about their algorithms.
- The **voice layer is absent from the Java corpus** entirely.
- Native binaries were **not disassembled**; the JNI export table only proves which classes are native-backed.
- Reference **near-turn** banner state, in-motion behaviours (dead reckoning, GPS loss, camera follow), and the traffic layer were **not runtime-verified** (stationary device, no mock-location facility, proprietary backend unavailable).
- The D7 unpainted band remains **open**; its instrumentation and reproduction protocol are untouched.

## 11.4 Artifacts produced by this sweep

| Artifact | Path (worktree) |
|---|---|
| Master report | `docs/reverse-engineering/google-maps-feature-enhancements.md` (this file) |
| Evidence ledger | `docs/reverse-engineering/google-maps-evidence-ledger.md` |
| Feature specs | `docs/reverse-engineering/feature-01-navigation-guidance.md` … `feature-12-resilience-performance.md` |
| Implementation backlog | `docs/reverse-engineering/google-maps-implementation-backlog.md` |
| Overnight checkpoint | `/tmp/opencode/google-maps-overnight-checkpoint.md` |

## 11.5 Working-tree state

`git status --short` in the worktree lists **only** the new `docs/reverse-engineering/` documents (the `docs/` tree was already untracked). **No application source, test, Gradle file, schema, or build artifact was created, modified, or deleted**; the main repository was read only.

# 12. Approval gate for the next phase

The backlog (`google-maps-implementation-backlog.md`) sequences 27 tasks. Recommended first steps, in order, each requiring explicit approval:

1. **B-02** (S) — reset the voice announcement history on route change.
2. **B-01** (M) — pair the banner instruction with the distance it describes (the P0 defect).
3. **B-04** (M) — guard maneuver advancement (one per fix, accuracy-gated, projected).
4. **B-09** (S) — make the offline suite hermetic (unblocks reliable verification).
5. **B-08** (S) — remove the composition-phase diagnostics — **only after D7 closes with a failing coordinate**.

B-01, B-04 and B-24 touch navigation progression and gesture arbitration and therefore require explicit approval per the mission's invariant list. P3 items (traffic, alternatives, lane guidance, voice commands) depend on data or architecture decisions and are not recommended now.

---

# 13. Phase B implementation (approved 2026-10-20) — B-01, B-02, B-04, B-09

Approved order: B-02 → B-01 → B-04 → B-09. All changes were applied in the **enhancement
worktree**; the main repository was **not** modified for these features.

## 13.1 What changed

| Task | Change | Files |
|---|---|---|
| **B-01** | `NavigationProgress.upcomingManeuverIndex` — the first step whose maneuver vertex the rider's projected position has not passed. The distance now targets that step's vertex, and `getCurrentStep()`/`getNextStep()` return the upcoming step and the one after it. `currentManeuverIndex` keeps its old meaning (last maneuver performed) and becomes bookkeeping only. The banner text, the maneuver icon and the spoken prompt therefore describe one and the same maneuver. | `data/location/NavEngine.kt` |
| **B-02** | `navAudioGuidance.resetAnnounceHistory()` is called at every route installation (initial route, route fetch, reroute). The suppression state moved into a pure, Android-free `AnnouncementHistory` class (`shouldAnnounce`/`markSpoken`/`reset`). | `data/location/NavAudioGuidance.kt`, `ui/viewmodel/RideViewModel.kt` |
| **B-04** | The unbounded maneuver-advance `while` loop became a **single, accuracy-gated advance per fix** (≤30 m). Because the guidance is projection-derived, a conservative advance can no longer hide a turn. | `data/location/NavEngine.kt` |
| **B-09** | The live-Firebase Robolectric test now runs the repository fully in-memory (`firestore = null` — the dependency is nullable and every access is null-safe). The SOS assertions became the documented null-backend contract; the SOS lifecycle stays covered offline by the dedicated suites. | `test/java/com/example/ExampleRobolectricTest.kt` |

New test: `test/java/com/example/ManeuverPairingTest.kt` (9 tests).

## 13.2 Verification

- **Worktree suite:** `./gradlew :app:testDebugUnitTest --offline --rerun-tasks` → **BUILD SUCCESSFUL — 27 classes / 272 tests / 0 failures / 0 errors / 0 skipped**, and the run now takes **2 m 37 s** instead of hanging indefinitely (>30 min observed before, thread-dumped).
- **Build:** `:app:assembleDebug` → apk md5 `5a93f6367811683ff53963c05b0f080a`; installed on fbe12520 (`lastUpdateTime` 2026-10-20 13:54).
- **Device (AC-1/AC-2):** after a reroute rebuilt the route, the banner reads **"46 m" / "Turn left" / "Then: Turn right"** with a **matching left-turn icon** (`/tmp/opencode/1a_worktree_banner2.png`) — the icon, the instruction and the distance now describe the same maneuver. The pre-fix build on the same device showed the incoherent **"82 m" / "Head left" / "Then: Turn right"** (`/tmp/opencode/1a_state.png`), where the distance belonged to the *secondary* line's maneuver (confirmed against live OSRM: `depart/left @ 0.0 m`, `turn/right @ 80.2 m`).
- **Honest limitation:** the rider's live position is masked in `dumpsys location` and the live fix accuracy is ≈93 m, so the *absolute* identity of the on-device maneuver could not be re-derived independently from the service. Proven: the numeric pairing invariant (unit test) and the banner's internal consistency (icon = instruction = distance target), which the pre-fix build violated.

## 13.3 Defects closed / still open

| Finding | State |
|---|---|
| F1-D1 (P0) banner/voice pairing | **CLOSED** (implemented + unit-tested + device-verified) |
| F1-D3 (P1) voice silence after reroute | **CLOSED** (implemented + unit-tested) |
| F1-D2 (P1) unguarded multi-maneuver advance | **CLOSED** (implemented + unit-tested) |
| F12-D3 (P2) non-hermetic offline suite | **CLOSED** (implemented; the suite now completes in minutes) |
| F12-D1/F7-D2 (P1) route geometry not persisted | **OPEN** → B-03 |
| F6-D1 (P1) distant-place stop confirmation | **OPEN** → B-05 |
| F12-D2 (P1) composition-phase diagnostics | **OPEN and BLOCKED** → B-08 (must not be removed before D7 closes with a failing coordinate) |
| F1-D4/D5, F2-D1/D3, F3-D1, F4-D1/D2, F5-D1/D3, F8-D1/D4, F9-D1/D2, F11-D2/D3 | **OPEN** → backlog B-06, B-07, B-11 … B-19 |

## 13.4 Main repository (separate, previously-approved Phase B work)

Its own test suite failed 3 of 360 tests and exposed **two real defects** in that renderer code — both now fixed with regression tests: (1) the tile-17 world vertices were stored in a `FloatArray` (~0.5-unit resolution at those magnitudes ≈ 600 screen px) and the transform multiplied before subtracting — the geometry is now `DoubleArray` and the whole world→screen computation stays in `Double`; the transform API also had a second bug (a tile index was passed where a longitude was expected) and now takes the geographic centre; (2) the scale-bar factor was off by **256×** (the slippy-map formula already yields meters per tile pixel).

---

# 14. Phase B implementation, batch 2 (approved 2026-10-20) — B-03, B-05

| Task | Change | Verification |
|---|---|---|
| **B-03** (route geometry persistence) | New pure codec `data/firebase/RouteGeometryCodec.kt`; `RideRepository.updateRoute` writes the polyline + steps + totals to a **dedicated** document `rides/{rideId}/route/current` (kept out of the ride document, which every listener re-reads); `fetchRouteGeometry` reads it back; the ViewModel restore path re-arms the nav engine from it, guarded against stale fetches and against overwriting a live route. | `RouteGeometryCodecTest` (6) green; suite **285 tests / 0 failures**; apk md5 `781f6c49…` installed. **Device end-to-end proof NOT achieved — environmental:** the device's Firestore writes are not observable from here (the ride created on-device at 14:57, code E8WNRX, never appears in the configured project `ridetogether-f558a` — 1,837 documents, newest `createTime` 2026-10-19T01:09 — the app resolves the code via its own local fallback), and the worktree lacks the newer main-repo active-ride re-discovery path, so a killed process cannot re-enter the ride. **B-03's device acceptance stays OPEN.** |
| **B-05** (advisory distance warning) | New pure `data/place/PlaceProximityGuard.kt` (1,000 km threshold; returns a message, never a rejection, never touches coordinates); `CreateRideScreen` shows a dismissible warning row with "Use anyway" under an implausibly distant destination. | `PlaceProximityGuardTest` (7) green. **Device:** a normal 50.2 km destination produced **no** warning ✓; the cross-country case is unit-verified (the exact "Pavana" → Italy case) but not forced on device, because the local ranking already surfaces the nearest match first — the warning is the backstop. |

**Suite (worktree):** 29 classes / **285 tests / 0 failures / 0 errors / 0 skipped**; `:app:assembleDebug` md5 `781f6c4985efbd2870c232154be8dfda`, installed on fbe12520.

**Device evidence for the guidance fixes (unchanged, re-confirmed on the live route):** the banner reads **"100 m" / "Turn right"** with a **matching right-turn icon** and "Then: Continue left onto Mar…" (`/tmp/opencode/b03_rerouted.png`) — the distance, the icon and the instruction describe one and the same maneuver, which the pre-fix build violated ("82 m / Head left / Then: Turn right").

---

# 15. Phase B implementation, batch 3 (approved 2026-10-20) — B-06, B-07, B-11, B-12, B-13

The renderer port (B-11/B-12/B-13) was taken from the main repository **after** its two test-proven fixes, so the worktree now carries the corrected `Double` world geometry, the corrected transform API (geographic centre) and the corrected scale-bar factor.

| Item | Outcome | Evidence |
|---|---|---|
| B-11 route rendering | 4-pass band (shadow → slate casing → amber core → pale streak), zoom-modulated width, screen-spaced direction dots, cached world geometry with per-tier simplification and leg-boundary pinning | `p3_live.png`, `p3_routeband.png`, `p3_fitgroup.png`; 4 ported test classes |
| B-12 halo typography | 8-direction offset halo on START/FINISH; layouts measured once | `p3_routeband.png` (halo visible) |
| B-13 dynamic scale bar | Caption follows the ground distance: **"10 m" → "50 m"** on zoom-out (was always "500 m") | `p3_scalebar.png`, `p3_scalebar2.png` |
| B-07 touch targets | Rider hit radius → density-correct 24 dp | code-reviewed |
| B-06 chrome-aware framing | Fit-group framing accounts for the banner and trip-HUD insets | `p3_fitgroup.png` |

**Suite (worktree): 33 classes / 315 tests / 0 failures / 0 errors / 0 skipped**; `:app:assembleDebug` md5 `7f8b7b12c5b25ce9d142d1911e06d291`, installed on fbe12520.

**New observation (logged, not fixed):** the white scale-bar caption loses its leading digit on the pale day map — a scrim/halo behind the caption is the fix (follow-up).

---

# 16. Phase B implementation, batch 4 (approved 2026-10-20) — B-15, B-16, B-17

| Item | Outcome | Evidence |
|---|---|---|
| B-15 rider accuracy / freshness / bearingless | Fix-quality ring (good fixes stay clean), muted dashed casing + "5 min ago" for stale riders, and **no fabricated north arrow** when no qualified bearing exists (a neutral "direction unknown" pip instead) | `RiderMarkerStyle` + 8 unit tests; invariants #2/#3 preserved (measured fix only, never the DR prediction) |
| B-16 rider stacking / selection priority | Deterministic, id-derived separation of overlapping markers; the **selected rider is hit-tested first** | `RiderMarkerStyleTest` (order-independence, determinism) |
| B-17 overlay contract + chrome coupling + a11y | Explicit 10-layer overlay registry; the **chrome is published to the renderer** and labels under the banner/HUD/controls are suppressed; the map surface gained an accessibility description | `MapOverlayContractTest` (5) |

**Suite (worktree): 35 classes / 328 tests / 0 failures / 0 errors / 0 skipped**; apk md5 `69ae8277634aea2b13d6237e8b104683`, installed on fbe12520 (17:29).

**Device verification is partial and stated as such:** the build installs, launches and renders correctly (`b4_live.png`, `b4_overview.png`), but four behaviours could not be demonstrated with a single parked rider on a fresh fix — the chrome-aware label suppression, the rider stacking/selection priority, the stale "last seen" label and the bearingless pip. Those are code-verified and unit-tested, not claimed as device-observed.

## 14. Reconciliation with the ride-platform branch

The navigation/rendering work and the ride-platform work (multi-stop planning, amenity
pipeline, place search, telemetry) developed in separate trees and were reconciled on a
single integration branch.

**Method.** The platform tree's uncommitted work was committed first (`5202faf`), the
navigation/rendering work second (`0f1bce7`), and the six files that both sides had
touched were merged three-way against `f103759` — the snapshot that both trees branched
from — instead of against `b7406b5`, which reduced a ~50-file divergence to 6 files.
No history was rewritten and nothing was squashed.

**Per-file outcome.**

| File | Resolution |
| --- | --- |
| `RideRepository.kt`, `CreateRideScreen.kt`, `RideViewModel.kt` | clean three-way merge (0 hunks); route-geometry persistence added, SOS paths untouched |
| `NavEngine.kt` | union: `getNextStop()`/`currentLegIndex`/`legCount` (multi-stop) **and** `getCurrentStep()`/`getNextStep()`/`upcomingManeuverIndex` (maneuver-accurate guidance) |
| `ExampleRobolectricTest.kt` | the newer batched DAO API plus the hermetic in-memory repository |
| `RideMapCanvas.kt` | the navigation/rendering implementation (frame budget, palette/clustering, rider states, overlay contract, halo labels, dynamic scale bar) plus the platform's icon-footprint label avoidance |

`MapCameraController.kt` (chrome-aware framing) and `NavAudioGuidance.kt` (announcement
history) came from the navigation/rendering tree; the platform tree still held their
pre-batch versions.

**Verification.** The suite was run per class with a hard timeout, because five
pre-existing test classes (`NavEngineAndSosTest`, `RiderStatusAndSyncAuditTest`,
`SosAckDeliveryAndDismissalTest`, `SosLifecycleAndDeliveryTest`,
`RideCompletionAndChatSyncTest`) construct a real `RideRepository` and call live
Firestore; on a network that accepts the gRPC connection but does not answer, they block
inside `NioSocketImpl.read` and never return. Those files are byte-identical to the
platform commit and no SOS code was changed by the reconciliation.

- `43 classes / 380 tests / 0 failures / 0 errors / 0 skipped`
- `:app:assembleDebug` succeeds; apk md5 `464913a5f40bdfd96556c2f61318ae0e`
- the five live-network classes above remain excluded from the offline gate and are
  recorded as an environmental limitation, not a pass

**Diagnostics.** The `RT-D7` instrumentation stays in place; the open coordinate item is
still open.

---

# 17. Phase B implementation, batch 6 (approved 2026-10-21) — B-19 + B-28 (lane guidance and richer turn instructions)

**Full evidence and design:** `docs/reverse-engineering/feature-13-lane-guidance.md`.

## 17.1 What the evidence showed (reference side)

The reference draws a **lane strip** in the turn card: the lanes of the
carriageway the rider is on, with the route's lanes highlighted. It is a
custom `View`
(`com.google.android.libraries.geo.navcore.turncard.views.TurnCardLaneGuidanceListView`,
evidence `ev_ff392141989a8d0171e63bd8e9237df1c9e1412ac3ad1bc7921238f8ca8697c3`)
whose decompiled `onDraw` proves three behaviours:

* **two passes, recommended last** — usable lanes are drawn after the dimmed
  ones with a second `ColorFilter`, so a highlight can never be overdrawn;
* **the strip shrinks to fit** and is centred — the glyph size is
  `max(min, min(max, (width − 60dp) / laneCount))`;
* **the left/right arrows are one glyph mirrored** (`canvas.scale(-1, 1)`),
  so the reference needs one base drawing per shape family.

The reference's own public model (`androidx.car.app.navigation.model`) is a
**48-type maneuver vocabulary** with a roundabout exit number and exit angle
(`ev_0dd6c033089f6aa0a9a104f3b46c65e22f0e111d9a82945acbbbb0353ae87bc7`), and
a lane model of **10 arrow shapes with a recommended flag**
(`ev_b20f9a73ebf7b5f2cabe80edb44d348dc237bc44524613f1620633d251874067`).
The lane-aware route line itself is **native**
(`NativeLaneAwarePolylineInterpolator.nativeInterpolateBeziersJni`) and was
not reproduced.

Two negative findings that shaped the implementation: the reference has **no
lane announcements** (the lane strip is visual only), and its own help text
states that offline directions have **no lane guidance** — lane data is a
property of the *route response*, not of the map.

## 17.2 What the routing engine actually provides (probe)

A live probe of the OSRM endpoint the app uses returned:

* `maneuver.type` / `maneuver.modifier` / `maneuver.exit` /
  `bearing_before` / `bearing_after` on every step;
* `intersections[].lanes[] = { "valid": bool, "indications": [ … ] }` — but
  **only on roads mapped with `turn:lanes`**. The app's own test route
  (Pune→Lonavala) returns **no** lane data at all; a European route returns
  2–4 lane sets.

Empirically verified over four routes: a manoeuvre's lanes are the ones
attached to the **manoeuvre's own node** (`maneuver.location ==
intersections[0].location`); the lanes of the later intersections of the same
step belong to the *next* manoeuvre.

## 17.3 What was implemented

| Item | Change |
|---|---|
| **B-19** maneuver taxonomy | New `data/model/ManeuverType.kt` — a RideTogether-original, closed 24-value vocabulary mapped from the engine's `(type, modifier)` pair. It separates the cases the raw pair cannot: `turn/slight left` (bear left) vs `continue/slight left` (keep left), fork vs merge vs ramp, and it adds roundabout exits, exit numbers, U-turns, end-of-road and ferries. The icon mapping now reads the vocabulary instead of `String.contains`; the instruction text is built from it (`"Take the 3rd exit onto NH-48"`, `"Keep right at the fork onto B"`). |
| **B-28** lane guidance | New `data/model/LaneGuidance.kt` + `data/location/OsrmStepParser.kt` + `ui/map/LaneArrowGeometry.kt` + `ui/components/LaneGuidanceStrip.kt`. The strip is drawn from **RideTogether-original** arrow geometry (left/right pairs are exact mirrors), shrinks to fit, is centred, and draws the usable lanes in white over the dimmed ones. |
| The no-invention rule | Lane guidance is attached to a manoeuvre **only** when the engine supplied lane data for that manoeuvre's own node, and only when the data can carry the manoeuvre. No data ⇒ no strip; contradictory data ⇒ dropped. The engine's `valid` flag is copied verbatim — RideTogether never decides which lane is usable. |
| Persistence | `RouteGeometryCodec` round-trips the maneuver, exit number, turn angle and the lane guidance (additive; old documents decode to the defaults). |

**B-19 is closed** (the taxonomy backlog item) and **B-28 is new** (lane
guidance was previously listed as "not implementable with the current data" —
see §13, B-26; that judgement was **wrong**: the data exists in the route
response, it is simply absent on roads without `turn:lanes` mapping).

## 17.4 Verification

`ManeuverVocabularyTest` (11), `LaneGuidanceParsingTest` (11) and
`LaneArrowGeometryTest` (5) are new; the fixtures are trimmed copies of real
engine responses. Full offline suite: **57 classes / 485 tests / 0 failures / 0 errors / 0 skipped**.

**Not device-verified**: the device was not connected during this pass, so the
strip has not been observed on the rider's screen. The geometry, colour
split, fit-to-width behaviour and the parser are unit-tested; the on-device
appearance is not claimed.
