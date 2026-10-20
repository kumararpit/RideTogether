# Evidence Ledger — Google Maps 26.42.01 → RideTogether

**Workstream:** `work/google-maps-feature-enhancements` · **Worktree:** `/home/arpit/Projects/RideTogether-google-maps-enhancements` @ `ef60bff`
**Reference APK:** `/home/arpit/Downloads/googlemap.apk` (304,176,297 B, sha256 `6218d72f48b184eb71f68879bce48cb9ebb2b510be7f7cff88217ea3fd592bb2`), `com.google.android.apps.maps` 26.42.01.997557324 (versionCode 1068832648).
**Corpus:** `/tmp/opencode/gmaps-src` (68,503 .java) · **Resources:** `/tmp/opencode/gmaps-res` · **Native symbols:** `/tmp/opencode/jni_symbols.txt` · **Device:** fbe12520 (RMX3360, 1080×2400 @3.0).
**No application source, test, Gradle, schema, or build artifact was modified by this audit.** The main repository (`/home/arpit/Projects/RideTogether`, branch `main`, 105 uncommitted paths) was **read only**.

**Classifications:** APK-confirmed · Runtime-observed · RideTogether-source-confirmed · Test-confirmed · Decompiled-inference · Behavioral hypothesis · Implementable independently · Proprietary/unavailable · Unverified.

---

## A. Environment & baseline

| ID | Claim | Class | Evidence | Verification | Confidence |
|---|---|---|---|---|---|
| E-A1 | Worktree is clean at `ef60bff`; main repo holds 105 uncommitted paths | APK-confirmed (repo state) | `git -C <worktree> status --short` → 0; `git -C <main> status --short \| wc -l` → 105 | re-runnable | confirmed |
| E-A2 | The **worktree lacks the multi-stop feature** present (uncommitted) in main | RideTogether-source-confirmed | worktree: no `RouteStop` in `data/model/RideModels.kt`; main: `RideModels.kt:145,156`, `RouteStopCodec.kt`, `data/map/RouteProvider.kt` | `grep -rl RouteStop app/src/main/java/com/example/` | confirmed |
| E-A3 | Worktree/main line-count deltas: `NavEngine` 319/385, `RideMapCanvas` 1382/1572, `RideViewModel` 772/971, `NavigationHud` 506/506, `MapCameraController` 236/236 | RideTogether-source-confirmed | `wc -l` in both trees | confirmed |
| E-A4 | Dead-reckoning contract is **identical** in both trees (3.0 s / 5.4 km/h / 20 m / 50 m teleport) | RideTogether-source-confirmed | `RiderMarkerAnimator.kt:175,178,181,188` (both) | confirmed |
| E-A5 | The JADX corpus `resources/` was **empty**; resources were recovered this session with standalone `apktool 3.0.3 d -s` | APK-confirmed | `/tmp/opencode/gmaps-res/res/**` (values, layout*, drawable*, mipmap*) | re-runnable | confirmed |
| E-A6 | `jadx` CLI is **not installed**; REA apktool/JEB providers unavailable | Tool failure | `which jadx` → empty; per mission statement | — | confirmed |
| E-A7 | Native binaries present but **not disassembled**: `libgmm-jni.so`, `libgoogle3.so`, `libbase_native.so`, … | Proprietary/unavailable | `/tmp/opencode/gmaps-res/lib/arm64-v8a/` | — | confirmed |

---

## B. Feature 01 — turn-by-turn guidance (spec: `feature-01-navigation-guidance.md`)

| ID | Claim | Class | Evidence | Reproduction / verification | Confidence |
|---|---|---|---|---|---|
| E-F1-01 | The reference's navigation engine is **native**: `nativeOnLocationChanged` returns a serialized guidance state, `nativeBuildTripGuidanceState` returns the `cgce` protobuf | APK-confirmed | `sources/com/google/android/libraries/geo/navcore/guider/jni/RouteGuiderJni.java` | `sed -n 1,120p` on that file | confirmed (method bodies are JNI stubs) |
| E-F1-02 | The reference renders the next-turn **distance** with two typographic tiers (number large, unit small) and re-renders only on content change | APK-confirmed | `.../navcore/turncard/views/TurnCardStepDistanceTextView.java` — `AbsoluteSizeSpan(bmcmVar.a)` + `StyleSpan(bmcmVar.b)` for the value; `bmcmVar.f/g` for the unit; `setVisibility(8)` when empty | read file | confirmed |
| E-F1-03 | The reference's distance model is an immutable 4-field value with real `equals` | APK-confirmed | `sources/defpackage/blmu.java` | read file | confirmed |
| E-F1-04 | The reference inlines a **30 dp** maneuver drawable with the next-step instruction | APK-confirmed | `TurnCardNextStepInstructionView.java` (`this.b=30; this.c=30;`) | read file | confirmed |
| E-F1-05 | The reference ships a family of turn-card layouts (solo / with-next / full-width-next / single- / multi-line / lane guidance / long step) | APK-confirmed | `res/layout*/step_container*.xml`, `step_instruction_content_*.xml`, `adaptive_turn_card_current_step_layout.xml` | `ls /tmp/opencode/gmaps-res/res/layout*` | confirmed (the **selection rule** is native → unavailable) |
| E-F1-06 | Banner view↔resource mapping: `distance_text`, `maneuver_image`, `bottom_cue_text`, `distance_cue_separator_text`, `step_cue_container` | APK-confirmed | `sources/defpackage/biwi.java` + `res/values/public.xml` id resolution | script in §2 of the spec | confirmed |
| E-F1-07 | The secondary instruction string is `"Then {0}"` / `"Then %1$s"` | APK-confirmed | `res/values/strings.xml` `TURN_CARD_THEN`, `TURN_CARD_THEN_INSTRUCTION` | grep | confirmed |
| E-F1-08 | Reference instruction text generation and lane guidance are native | Proprietary/unavailable | `NativeTripTranslatorJni.java`; `jni_symbols.txt` `…NativeLaneAwarePolylineInterpolator_nativeInitClass` | grep | confirmed as a boundary |
| E-F1-09 | The reference's **voice/announcement layer is absent from the Java corpus** | Proprietary/unavailable | corpus-wide grep of `sources/com/google/android/apps/gmm/**` for `TextToSpeech|synthesizeToFile|QUEUE_ADD|announce*` → 0 hits; `jni_symbols.txt` has no voice/speech/tts group | re-runnable | confirmed as unavailable |
| E-F1-10 | Reference nav banner (runtime): dark-teal `#015f61` card; "towards **Hanuman Temple** Rd" (name band ≈59 px cap-height ≈19.7 dp; "Rd" ≈42 px ≈14 dp); attached "Then ↱" card; blue next-street pill on the map; trip bar "56 min · 52 km" | Runtime-observed | `/tmp/opencode/aud_gm_10_nav.png` (fbe12520, 2026-10-19 15:29) + PIL band measurement | re-derivable from the PNG | confirmed for the captured state; **near-turn state not captured** |
| E-F1-11 | **RT defect D1**: the banner/voice pair the **previous** maneuver's instruction+icon with the **next** maneuver's distance | RideTogether-source-confirmed + Runtime-observed | worktree `NavEngine.kt:176-187` (distance → `stepVertexIndices[current+1]`) vs `:276-280` (`getCurrentStep` → `steps[current]`); `NavigationHud.kt:196-219`; `RideViewModel.kt:432-441`; device banner `82 m / Head left / Then: Turn right` (`/tmp/opencode/1a_state.png`, crop `1a_banner2.png`) | code read + device capture | **confirmed** |
| E-F1-12 | Ground truth for E-F1-11: live OSRM puts `depart/left` at **0.0 m** and `turn/right` at **80.2 m** from the device origin | APK-confirmed (service) | `/tmp/opencode/osrm_mw.json` — `GET /route/v1/driving/73.7332,18.6042;73.4056,18.7508;73.6738,18.6573;73.4017,18.7549?overview=full&geometries=geojson&steps=true` → `code: Ok`, 3 legs, 2136 points | re-runnable curl | confirmed |
| E-F1-13 | **RT defect D2**: maneuver advancement is an unguarded `while` loop using a straight-line ≤25 m test and is not accuracy-gated | RideTogether-source-confirmed | worktree `NavEngine.kt:162-173`; contrast the leg-advance discipline in the main-repo `NavEngine.kt:191-204` + `NavEngineMultiLegTest` | code read | confirmed (not runtime-reproduced) |
| E-F1-14 | **RT defect D3**: after a reroute the voice is silent for the new route's first maneuvers (`resetAnnounceHistory()` has no call site) | RideTogether-source-confirmed | worktree `NavAudioGuidance.kt:119` (suppression), `:158` (`resetAnnounceHistory`, no caller), `RideViewModel.kt:595` (`navEngine.setRoute` on reroute) | `grep -rn resetAnnounceHistory app/src/main` | confirmed |
| E-F1-15 | **RT defect D4**: `NavEngine._lastAnnouncedManeuverIndex` / `markManeuverAnnounced` are never called by the app | RideTogether-source-confirmed | worktree `NavEngine.kt:43-44,288-292`; only `NavEngineAndSosTest` references them | grep | confirmed |
| E-F1-16 | **RT defect D5**: the HUD mixes progress snapshots (collected `navProgress` vs live `_progress.value` reads) | RideTogether-source-confirmed | worktree `LiveRideScreen.kt:86` vs `:89-90` | code read | confirmed (race window is small) |
| E-F1-17 | **RT gap D6**: each maneuver is spoken at most once; no re-announcement ladder | RideTogether-source-confirmed | worktree `NavAudioGuidance.kt:117-139` | code read | confirmed (comparison gap, reference unavailable) |

---

## C. Feature 04 — route rendering (spec: `feature-04-route-rendering.md`)

| ID | Claim | Class | Evidence | Reproduction | Confidence |
|---|---|---|---|---|---|
| E-F4-01 | Reference primary route = 3-band stroke ≈24 dp (casing `#27059b`, core `#0420d2`/`#450efb`, inner streak `#c1affe`) at overview zoom | Runtime-observed | `/tmp/opencode/aud_gm_7_routeprev.png`; PIL cross-sections (row 600, col 130) | re-derivable | confirmed |
| E-F4-02 | Reference nav-mode route ≈17 dp core `#4710fb` + `#0e0158` casing ⇒ width is context-dependent | Runtime-observed | `/tmp/opencode/aud_gm_10_nav.png`; col-530 strip `0d0151 4610f8 4710fb … 27089f 110261 0e0158` | re-derivable | confirmed |
| E-F4-03 | Reference alternatives ≈⅓ weight `#c3d9fe`; ETA pills anchored to the line with leader tails | Runtime-observed | `aud_gm_7_routeprev.png`; row-1000 strip | re-derivable | confirmed |
| E-F4-04 | Reference re-projects route geometry **per frame** natively with camera-follow + safe-area state, and self-heals a failed projection | APK-confirmed | `sources/com/google/android/apps/gmm/kits/directions/map/renderer/viewportintegrity/PolylineViewportIntegrityBridge.java` (517 lines) | read file | confirmed (native boundary) |
| E-F4-05 | **RT defect D1/D2** (worktree): per-frame `List<Offset>` of every route point + `Path`, no simplification, no viewport decimation | RideTogether-source-confirmed | worktree `RideMapCanvas.kt:653-658`; live route size 2136 points (`osrm_mw.json`) | code read | confirmed |
| E-F4-06 | **RT defect D3** (worktree): fixed ~32.5 dp 4-band stroke at every zoom | RideTogether-source-confirmed | worktree `RideMapCanvas.kt:662-688` (18/12/7.5/2.5 dp) vs E-F4-01/02 | code read | confirmed |
| E-F4-07 | **RT gap D5** (worktree): no alternatives, no anchored pills, no progress emphasis, no reroute transition | RideTogether-source-confirmed | worktree route block | code read | confirmed |
| E-F4-08 | A cached/simplified three-band renderer **already exists and is validated** in the main repo (uncommitted) against live 3-leg data: 213/619/1389 vertices kept at z11/z14/z17, **0 leg-spanning segments**, max deviation 1.13–1.20 px | Test-confirmed (main repo WIP) | `ui/map/RouteRenderMath.kt`, `ui/map/RouteGeometryCache.kt`; validation script output (this session) | re-runnable | confirmed (not in the worktree) |

---

## D. Feature 07 — multi-stop routing (spec: `feature-07-multi-stop-routing.md`)

| ID | Claim | Class | Evidence | Reproduction | Confidence |
|---|---|---|---|---|---|
| E-F7-01 | The worktree has **no** multi-stop feature (no `RouteStop`/`RouteStopRole`/`RouteStopCodec`, no leg advancement, two-argument `OsrmRoutingService.fetchRoute`) | RideTogether-source-confirmed | worktree `data/model/RideModels.kt` (absent), `data/location/OsrmRoutingService.kt` | grep | confirmed |
| E-F7-02 | OSRM supports ordered multi-waypoint requests and returns `legs == waypoints − 1` | APK-confirmed (service) | `osrm_mw.json`: 4 waypoints → `code: Ok`, 3 legs, waypoint vertices at indices 0/774/1456/2135 | re-runnable curl | confirmed |
| E-F7-03 | OSRM `steps[0]` is a **`depart`** maneuver at the origin — the first "turn" is `steps[1]` | APK-confirmed (service) | `osrm_mw.json` step dump | re-derivable | confirmed |
| E-F7-04 | Google's stop management / reordering / route recomputation is native (guider re-creation) | Proprietary/unavailable | `RouteGuiderJni.nativeCreateGuider`; `jni_symbols.txt` | — | confirmed as unavailable |
| E-F7-05 | **RT defect**: route geometry is never written to Firestore ⇒ a killed process restores with no route (banner "Follow Route", Remaining 0 m, no line) | RideTogether-source-confirmed + Runtime-observed | main `RideRepository.kt:1067-1085`; device captures `av2`–`av11`, `1a_state.png` | re-derivable | **confirmed** |
| E-F7-06 | **RT defect**: the intermediate-stop editor (main-repo WIP) shows the **name only**, so a distant Photon match is invisible — observed "Pavana" → Italy ⇒ 18,066 km estimate | Runtime-observed | device capture `d8`; Photon probe (`Pavana` → Italia 44.12/11.00, España, Nederland, Honduras) | re-derivable | confirmed |
| E-F7-07 | Destination suggestions in the worktree **do** show the locality (`place.address`, `CreateRideScreen.kt:839-870`) | RideTogether-source-confirmed | worktree `ui/screens/CreateRideScreen.kt:868-870` | code read | confirmed |
| E-F7-08 | The worktree's place-search selection integrity is sound: editing the text invalidates the selection; coordinates are never fabricated; bias requires a fresh/accurate fix and is never (0,0) | RideTogether-source-confirmed | `data/place/PlaceSearchEngine.kt:171-175`; `data/place/PlaceSearchRanking.kt:22-45`; `data/place/SelectedPlace.kt` | code read | confirmed |

---

## E. Feature 08 — rider markers (spec: `feature-08-rider-markers.md`)

| ID | Claim | Class | Evidence | Reproduction | Confidence |
|---|---|---|---|---|---|
| E-F8-01 | The reference distinguishes the *current position* (blue dot + halo) from the nav vehicle (3D sprite) | Runtime-observed | `aud_gm_7_routeprev.png` vs `aud_gm_10_nav.png` | re-derivable | confirmed |
| E-F8-02 | The reference ships explicit **GPS-lost / bearingless** chevron states (256×256, day/night/map-colors/min-mode variants) | APK-confirmed | `res/drawable-nodpi/bearingless_*_navigation_chevron*.png` (6 files) | `ls` | confirmed (content proprietary — not copied) |
| E-F8-03 | RT's qualified bearing is never fabricated: derived course requires ≥3 m displacement and ≥500 ms interval, else `null` | RideTogether-source-confirmed | worktree `data/location/CourseOverGround.kt:21-24,54-66` | code read | confirmed |
| E-F8-04 | RT's dead reckoning is bounded (3.0 s / 5.4 km/h / 20 m), **holds** after expiry, teleports at >50 m, and is consumed only by the map overlay | RideTogether-source-confirmed | worktree `RiderMarkerAnimator.kt:175-188,232,245-246,312`; `RideMapCanvas.kt:152-176` comment | code read | confirmed |
| E-F8-05 | **RT gap**: no accuracy/freshness disclosure on the rider marker; no "direction unknown" state (a missing bearing silently renders as north) | RideTogether-source-confirmed | worktree rider draw block (arrow source `?: 0f`) | code read | confirmed |
| E-F8-06 | **RT gap**: rider label text is measured per rider per frame; arrow `Path` allocated per rider per frame | RideTogether-source-confirmed | worktree rider block (`textMeasurer.measure(firstName, tagStyle)`) | code read | confirmed |

---

## F. Feature 09 — group ride (spec: `feature-09-group-ride-map.md`)

| ID | Claim | Class | Evidence | Reproduction | Confidence |
|---|---|---|---|---|---|
| E-F9-01 | The reference has **no** group-riding feature; no such capability is inferred | Proprietary/unavailable (scope statement) | corpus + class inventory show no multi-rider navigation surface | grep | confirmed |
| E-F9-02 | **RT defect**: overlapping rider markers are not decluttered and the draw order follows the member list, so the current user can be hidden | RideTogether-source-confirmed | worktree rider block (no clustering / z-order) | code read | confirmed |
| E-F9-03 | **RT defect**: rider hit-testing takes the **first** member within the radius ⇒ the wrong rider can be selected when markers overlap | RideTogether-source-confirmed | worktree hit-test loop (`break` on first match) | code read | confirmed |
| E-F9-04 | **RT gap**: no last-seen age for stale riders; no relative position (distance/bearing) to the leader | RideTogether-source-confirmed | `RiderMember` fields; marker block | code read | confirmed |
| E-F9-05 | `headingDeg: Float?` semantics are documented as "Null must never be rendered as north" | RideTogether-source-confirmed | worktree `data/model/RideModels.kt:60-66` | code read | confirmed |

---

## G. Feature 12 — lifecycle & performance (spec: `feature-12-resilience-performance.md`)

| ID | Claim | Class | Evidence | Reproduction | Confidence |
|---|---|---|---|---|---|
| E-F12-01 | **RT defect**: the temporary D7 diagnostic block executes in the **composition phase** (string building + Coil memory-cache probes per visible tile + 2 `Log.d`) on every camera-driven recomposition | RideTogether-source-confirmed | worktree `RideMapCanvas.kt:514-527` (worktree report §4/§5) | code read | confirmed |
| E-F12-02 | Measured cost of E-F12-01 on device (old build `556a7b45…`): **186 RT-D7 log lines** in a 20 s interaction; 1114 frames, **9.78 % janky**, p50 16 ms / p90 30 ms / p95 65 ms / p99 109 ms | Runtime-observed | `/tmp/opencode/gfxinfo_before.txt`, `/tmp/opencode/logcat_before.txt`, harness `/tmp/opencode/measure_frames.sh` | re-runnable | confirmed |
| E-F12-03 | **Pre-existing suite blocker**: `ExampleRobolectricTest."test ride repository flow"` initialises **live Firebase** inside a Robolectric `runBlocking` ⇒ the whole `:app:testDebugUnitTest` task hangs when the network is unavailable (>30 min, 2.8 % CPU; thread dump: `FutureTask.get` → `Robolectric Sandbox.runOnMainThread`) | Test-confirmed | `app/src/test/java/com/example/ExampleRobolectricTest.kt:141-152` + Gradle worker thread dump | re-runnable | confirmed |
| E-F12-04 | RT's foreground location service, stale-fix rejection (>30 m), off-route latch, arrival latch, reroute throttling and stale-response guard are all present and sound | RideTogether-source-confirmed | `data/location/RideLocationService.kt:44-76`; `NavEngine.kt:95-128`; `RideViewModel.kt:528-548` | code read | confirmed |
| E-F12-05 | Amenity pipeline is cache-first with versioned keys, in-flight dedup and Overpass resilience; device-validated (406 rows/41 v2 marks seeded; live fetches 39–48 s) | Test-confirmed + Runtime-observed | main report §7b; device captures `av5`–`av10` | re-derivable | confirmed |
| E-F12-06 | The D7 unpainted-band defect remains **open**; two clean stress passes produced no band; diagnostics deliberately retained | Test-confirmed | worktree report §5 probe record (2026-10-19) | re-runnable | confirmed **open** |

---

## H. Feature 06 — place search (RT side; corpus side pending subagent)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-F6-01 | Ranking is **tier-first** (exact → prefix → contains → address-contains → provider relevance) with distance only as a tie-breaker inside a tier; stable sort preserves provider order | RideTogether-source-confirmed | worktree `data/place/PlaceSearchRanking.kt:65-107` | confirmed |
| E-F6-02 | Bias is a validated *hint*, never a restriction: fresh (≤30 s), accurate (≤50 m), in-bounds, never (0,0) | RideTogether-source-confirmed | `PlaceSearchRanking.kt:22-45` | confirmed |
| E-F6-03 | Photon parsing already extracts street/housenumber/postcode/district/city/county/state/country ⇒ the data for locality-aware disambiguation **exists** | RideTogether-source-confirmed | `data/place/PhotonPlaceParser.kt:21-35,67-77` | confirmed |
| E-F6-04 | The distant-match risk is real: Photon returns same-name places worldwide ("Pavana" → Italia/España/Nederland/Honduras) and the ranking tie-breaker only orders them | Runtime-observed | Photon probe output (this session) | confirmed |
| E-F6-05 | The reference's search is a **gRPC-Lite** service (`Search`, `Suggest`, `Geocode`, `GetExhaustiveSearch`, `GetLocationDetails`, `Reveal`, `ReverseGeocode`, `NearbySearch`) and **ranking is server-side**; the client preserves order and only merges (offline top-2 first), de-duplicates (feature id / fingerprint / **≤50 m**) and filters a local prefix cache | APK-confirmed + Decompiled-inference | `defpackage/ccxk.java:22`; `defpackage/ayym.java:54`; `defpackage/aywl.java:34-126`; `defpackage/azah.java:117-292`; merge `defpackage/axuc.java:485-598` + `defpackage/aytf.java:1765-1780`; dedup `defpackage/aytf.java:3289-3315`; prefix cache `defpackage/axry.java:38-202` | confirmed |
| E-F6-06 | The reference **never invents coordinates**: if a suggestion carries no location, the client re-queries by the suggestion's own text; the home/work centroid suggestion carries a feature id and **no lat/lng** | Decompiled-inference (high) | `defpackage/ajtf.java:33-126` (`:47`, `:85,96,112`); `defpackage/qss.java:41` | confirmed |
| E-F6-07 | Same-name disambiguation in the reference is achieved by **displaying** server-supplied address / secondary text / place type / distance class — there is no client-side disambiguation screen | Decompiled-inference (high) | `defpackage/bekm.java:279-389`; `defpackage/cqbi.java:6-8`; `res/values/strings.xml` `DISAMBIGUATION_*` belong to the report-a-problem flow | confirmed |
| E-F6-08 | RT shows the **locality on destination suggestions** but **not on confirmed intermediate stops** | RideTogether-source-confirmed + Runtime-observed | worktree `ui/screens/CreateRideScreen.kt:868-870`; main-repo stop row (read-only) + device capture `d8` | confirmed |

---

## I. Feature 02 — camera & gestures (spec: `feature-02-camera-gestures.md`)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-F2-01 | The reference camera has 8 animatable properties incl. **tilt, vertical FOV (default 30°) and look-ahead**; zoom clamped [2,21], tilt [0,90] | APK-confirmed | `defpackage/bjvk.java:54-75,374-385`; `defpackage/bjpo.java:27-46`; `defpackage/bjvi.java:257-324` | confirmed |
| E-F2-02 | Rendering builds a 4×4 projection matrix (world scale `2^(30−zoom)`, FOV, bearing, tilt, near/far, frustum corners) | APK-confirmed | `defpackage/blee.java:53-163`; `defpackage/bkzt.java:119-244` | confirmed |
| E-F2-03 | The reference implements the **"keep the point under the gesture centroid"** math on the Java side | Decompiled-inference | `defpackage/bjuj.java:168-174,256-275` | confirmed |
| E-F2-04 | Gesture axes are an explicit enum (PAN, ZOOM, ROTATE, TILT) with an **arbitration rule** (tilt wins over pan; zoom and rotate are independent) | APK-confirmed | `defpackage/bjzf.java:14-24`; `defpackage/bjzg.java:132-145` | confirmed |
| E-F2-05 | **Pinch vs. rotation** is separated by a **10° finger-pair angle** plus a 10–20 % separation change; **tilt vs. pan/zoom** by a 45° parallel/perpendicular test | APK-confirmed | `defpackage/bjzl.java:33-55`; `defpackage/bjzi.java:24-69` | confirmed |
| E-F2-06 | The zoom **anchor** is carried with the zoom delta into the camera, and the anchor is unprojected to world coordinates | APK-confirmed | `defpackage/bjjt.java:52-114`; `defpackage/bjzb.java:24`; `defpackage/bkml.java:37-41,119-125` | confirmed |
| E-F2-07 | Fit-bounds zoom = `30 − log2(spanX / widthPx × (fov × 256))`, with antimeridian-safe centre and a look-ahead offset; an animated variant exists | APK-confirmed | `defpackage/bzwv.java:1369-1420,1427-1478`; `defpackage/bjhj.java:18-26` | confirmed |
| E-F2-08 | The reference has **10 named nav camera modes** and 4 compass modes; RT has 3 modes and no step-inspection/arrival mode | APK-confirmed + RideTogether-source-confirmed | `defpackage/bybt.java:5-15`; `defpackage/bmfc.java:21-45`; `defpackage/bmfx.java:3-7`; worktree `MapCameraController.kt:9-13` | confirmed |
| E-F2-09 | The reference regulates frames through a `Choreographer.FrameCallback` with a **default 30 fps** target, an adaptive floor (2D/3D), a frame-stats collector and an FpsProfiler histogram | APK-confirmed | `defpackage/bjtf.java:13,139-146`; `defpackage/rck.java:91,171-174`; `defpackage/bkfn.java:1121-1125,1180-1191`; `defpackage/bcvk.java:9-27` | confirmed (the `doFrame` body is a JADX failure ⇒ the budget algorithm is **unavailable**) |
| E-F2-10 | The nav camera algorithm is **native** (`CameraControllerJni`) | native boundary | `.../navcore/ui/camera/jni/CameraControllerJni.java:435-449,812-834` | confirmed as a boundary |
| E-F2-11 | **RT defect**: a single `detectTransformGestures` conflates pinch/rotate/tilt; no arbitration thresholds | RideTogether-source-confirmed | worktree `RideMapCanvas.kt:769` | confirmed |
| E-F2-12 | **RT defect**: no frame budget, no in-app frame statistics; no explicit "step inspection" or "arrival" camera mode | RideTogether-source-confirmed | worktree `RideMapCanvas.kt:256-311`; `MapCameraController.kt:9-13` | confirmed |

---

## J. Feature 03 — map rendering (spec: `feature-03-map-rendering.md`)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-F3-01 | The reference runs a **legacy Java+GLES renderer** and a **native vector renderer**; the session lifecycle is a native create/delete pair | APK-confirmed + native boundary | `defpackage/bkdb` & co.; `NativeMapController.java:21,52-162`; `NativeMapFactory.java:105-109` | confirmed |
| E-F3-02 | The reference's **ancestor-tile fallback is an explicit loop** with a minimum zoom, and its ancestor zoom is chosen by binary search over a per-style zoom table | APK-confirmed | `defpackage/bkni.java:301-325`; `defpackage/bknd.java:16-26`; `defpackage/bjwg.java:24-63`; `defpackage/bket.java:30-115` | confirmed |
| E-F3-03 | The reference's tile identity is a **fixed-point 2³⁰ Mercator**; the disk cache stores tiles, metadata and **empty-tile records** (the storage-level "missing tile" marker) | APK-confirmed + native boundary | `defpackage/bkrh.java:20-29`; `NativeSqliteDiskCacheImpl.java:41-89` | confirmed |
| E-F3-04 | The reference's **style table is per-zoom (0–22) and interpolated** (floats, ARGB colours, stroke arrays) and logs a mismatch when a stroke attribute differs across bracketing zooms | APK-confirmed | `defpackage/bkqx.java:38-40,51-104,148-373,260-271` | confirmed |
| E-F3-05 | Reference **stroke width scales with zoom**: `width_px = baseWidth · 2^(−(zoom − zoomRef)) · 1.25` with a 0.25-zoom LOD quantum | APK-confirmed | `defpackage/bkob.java:277-303` | confirmed |
| E-F3-06 | The reference distinguishes **screen-space vs. world-space value encodings** | APK-confirmed | `defpackage/chov.java:5-15`; `defpackage/bkdg.java:79` | confirmed |
| E-F3-07 | **Label placement, measurement, collision and clustering are native**; Java creates candidates and consumes the outcome (`bkin` visibility states, `numLabelsCreated`/`numLabelsPlaced`) | native boundary | `SharedLabelerJni.java:227-229,321,434,604,637,745-791`; `defpackage/bkin.java:17-32`; `defpackage/bkij.java:30-83` | confirmed |
| E-F3-08 | The reference has **no grey placeholder tile**: a missing tile is served by ancestor fallback or an empty-tile record | APK-confirmed (absence) | `bkni.k`; `nativeSqliteDiskCacheInsertOrUpdateEmptyTile` | confirmed |
| E-F3-09 | The reference has a **frame-time debug overlay** (a GL shader program) and a day/night **draw-mode matrix** with explicit style twins | APK-confirmed | `FrameTimeOverlay$FrameTimeOverlayShaderProgram.java:11-52`; `defpackage/chqr.java:4-45+`; `defpackage/bktm.java:54-120`; `defpackage/bjkw.java:50-102` | confirmed |
| E-F3-10 | **RT strength**: the ancestor-tile fallback chain (Δz 4..2 + parent + exact) matches the reference's approach | RideTogether-source-confirmed | worktree `RideMapCanvas.kt` tile block | confirmed |
| E-F3-11 | **RT defect**: per-frame recomposition of the tile layer (up to 5 `AsyncImage`s per slot) and composition-phase diagnostics | RideTogether-source-confirmed | worktree tile grid + D7 block; E-F12-02 | confirmed |

---

## K. Feature 05 — amenities (spec: `feature-05-amenities-poi.md`)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-F5-01 | The reference maps ~100 place types to icons and a 120-entry category taxonomy to **10 groups** | APK-confirmed | `defpackage/bxhz.java:20-121`; `defpackage/mpa.java:11-21`; `defpackage/moz.java:20-41` | confirmed |
| E-F5-02 | Reference POI **clustering/declutter is native** | native boundary | `SharedLabelerJni` (E-F3-07) | confirmed as a boundary |
| E-F5-03 | Reference POI label bubble: title + secondary line, 12 dp padding, 12 dp radius, 24/12 dp arrow, 2 dp stroke | APK-confirmed (resources) | `res/layout/primary_poi_label_layout.xml`; `res/values/dimens.xml:519-525` | confirmed |
| E-F5-04 | RT's amenity pipeline is **cache-first with versioned keys, in-flight dedup and a bounded worker**, and was device-validated (406 rows / 41 v2 marks seeded; live fetch 39–48 s, bounded to 16 cells) | Test-confirmed + Runtime-observed | main report §7b; `av5`–`av10` | confirmed |
| E-F5-05 | RT's `AmenityLabelPlanner` implements the reference's *intent* (icon priority over label coverage) while remaining deterministic and testable | RideTogether-source-confirmed | worktree `AmenityLabelPlanner.kt:103-195` | confirmed |
| E-F5-06 | **RT defect**: priority is category-only (no distance/relevance term); no clustering; selection is a ring only; the palette collides with the OSM raster | RideTogether-source-confirmed | worktree planner + `AmenityIconConfig.kt:19-32` | confirmed |

---

## L. Feature 10 — traffic (spec: `feature-10-traffic-data.md`)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-F10-01 | Live traffic, routes, alternatives and traffic tiles all come from **proprietary services** (`GetTrafficConditions`, `GetDirections`, `GeoNavTilesService/GetTiles`) | Proprietary/unavailable (service) | `defpackage/aywn.java:30`; `defpackage/ayvx.java:30`; `GeoNavTilesService` grep; legacy `defpackage/aysi.java:11-36` | confirmed — **never called** |
| E-F10-02 | The **presentation** of traffic is generic and separable: delay buckets (`citv`), traffic stretch model (`ryi`), route-line traffic styles (`citw`, `ahdy`), alternative-vs-traffic comparison (`cipt`), incident taxonomy (`ciqp`, 30+ types) | APK-confirmed | see the spec's evidence table | confirmed |
| E-F10-03 | Traffic is pushed into the native engine and the camera (`nativeUpdateTrafficData`) | native boundary | `RouteGuiderJni.java:76-84`; `CameraControllerJni.java:738-762` | confirmed |
| E-F10-04 | The reference distinguishes reroute **causes** (`BETTER_ETA`, `AVOIDS_CLOSURE`, `AVOIDS_VEHICLE_RESTRICTION`, `AVOIDS_DYNAMIC_CLOSURE`) and **request modes** (`REROUTE_FROM_NEW_LOCATION`, `REROUTE_AND_ALTERNATES_FROM_NEW_LOCATION`, …) and nav events (`ALTERNATE_ACCEPTED/SELECTED/DRIVEN_INTO`) | APK-confirmed | `defpackage/cjqs.java:5-9`; `defpackage/cine.java:5-13`; `defpackage/bxwv.java:5-11` | confirmed |
| E-F10-05 | RT has **no** traffic feature; the reference's own offline-routing stack proves the rendering layer is separable from an online backend | RideTogether-source-confirmed + APK-confirmed | worktree (no traffic model); `jni_symbols.txt` offline-routing symbols | confirmed |
| E-F10-06 | RT's traffic-adjacent data (OSM `maxspeed`/`type`) is **not live traffic** and must never be presented as such | policy | mission constraint | n/a |

---

## M. Feature 11 — overlays & controls (spec: `feature-11-navigation-overlays.md`)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-F11-01 | The reference banner is a container with three optional children and an explicit **state precedence** (status → trip pager → current step), and its **apparent height is published to the renderer** | APK-confirmed + Decompiled-inference (high) | `TurnCardContainerLayout.java:40-84`; `bmczVar.y(apparentHeight)` | confirmed |
| E-F11-02 | The renderer receives the UI-chrome state: `turnCardBodyHeightPx`, `bottomSheetExposurePx`, `isBottomSheetExpanded`, `isNavigationOverlayOccludingMap`, `fullWindowHeightPx`, `isSidePanel`, `isRtl` | APK-confirmed (Kotlin `@Metadata`) | `PolylineViewportIntegrityBridge` metadata (line 41) | confirmed |
| E-F11-03 | The reference has a **declared 25-layer overlay z-order** (incl. `TRAFFIC_INCIDENTS(5)`, `FRIEND(19)`, `FRIEND_CLUSTER(20)`, `FRIEND_ACCURACY_CIRCLE(18)`, `PLACEMARK(23)`) and a triple z-order style lock | APK-confirmed | `defpackage/blga.java:36-108`; `defpackage/bkaf.java:131-198` | confirmed |
| E-F11-04 | Touch targets follow a **48 dp floor**; small icon buttons get enlarged `TouchDelegate` rects (76–96 dp) and invisible touch-target spacer views | APK-confirmed | `defpackage/rfm.java:170`; `defpackage/uxv.java:364-365`; `defpackage/rhb.java:10-27`; `res/values/dimens.xml:200,470,1099,1197` | confirmed |
| E-F11-05 | The reference exposes a11y semantics for the compass, mute state and alternative counts, and a **voice-command vocabulary** including `distance_to_next_turn` / `eta` | APK-confirmed | `CompassButtonView.java:279-306`; `res/values/strings.xml:4330-4343`; `defpackage/wkt.java:12-24`; `defpackage/qvv.java:10` | confirmed |
| E-F11-06 | **RT defect**: no declared overlay contract; no chrome→renderer coupling; rider tap radius is a raw px constant; no a11y on the banner/rider markers; single-level audio control | RideTogether-source-confirmed | worktree source review | confirmed |

---

## N. Implementation evidence (Phase B, approved 2026-10-20)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-IMPL-01 | The banner now pairs the instruction, the icon and the distance with the **same upcoming maneuver** | Test-confirmed + Runtime-observed | `NavEngine.computeUpcomingManeuverIndex` + `NavigationProgress.upcomingManeuverIndex`; `ManeuverPairingTest` (9 tests, all green); device capture `/tmp/opencode/1a_worktree_banner2.png` — "46 m / Turn left / Then: Turn right" with a matching left-turn icon; pre-fix capture `/tmp/opencode/1a_state.png` — "82 m / Head left / Then: Turn right" (incoherent) | confirmed |
| E-IMPL-02 | The voice announces the **upcoming** maneuver and resets its suppression on every route change | Test-confirmed | `NavAudioGuidance.AnnouncementHistory` (pure) + `resetAnnounceHistory()` at the three route-installation sites in `RideViewModel`; `ManeuverPairingTest.announcement history is reset…` | confirmed |
| E-IMPL-03 | The performed-maneuver counter advances **at most one step per fix** and only on an accuracy-reliable fix | Test-confirmed | `NavEngine.kt` advancement block; `ManeuverPairingTest.a single fix never advances more than one maneuver` + `…an inaccurate fix never advances…` | confirmed |
| E-IMPL-04 | The offline suite is hermetic: the live-Firebase test now runs the repository in-memory | Test-confirmed | `ExampleRobolectricTest."test ride repository flow"` (firestore = null); full worktree suite **272 tests / 0 failures / 0 skipped in 2 m 37 s** (previously >30 min hang) | confirmed |
| E-IMPL-05 | The worktree build is green and installed | Test-confirmed + Runtime-observed | `:app:assembleDebug` → md5 `5a93f6367811683ff53963c05b0f080a`; installed on fbe12520 (`lastUpdateTime` 2026-10-20 13:54) | confirmed |
| E-IMPL-06 | The main repository's route transform lost **~600 px** of positional precision because the tile-17 world vertices were stored as `Float` | Test-confirmed | `RouteRenderMathTest.world to screen transform matches the canvas projection exactly` failed (expected 540.0, actual −1.7·10¹⁰); fixed by `DoubleArray` geometry + a `Double` world→screen computation; new regression `transform keeps sub-pixel accuracy for a long route` | confirmed |
| E-IMPL-07 | The main repository's route transform was also fed a **tile index where a longitude was expected** (second, independent bug in the same call) | Test-confirmed | same failing test; the API now takes the geographic centre (`centerLat`/`centerLng`) | confirmed |
| E-IMPL-08 | The main repository's scale bar was off by **256×** (the slippy-map formula already yields meters per tile pixel) | Test-confirmed | `ScaleBarMathTest.chosen segment fits the bar budget` + `…zooming in selects a smaller segment` failed; fixed by removing the extra `/256` | confirmed |
| E-IMPL-09 | The on-device maneuver's *absolute* identity could not be independently re-derived | Unverified (limitation) | `dumpsys location` masks coordinates (`18.60****,73.73****`) and the live fused fix accuracy is ≈93 m, so the service ground truth for the rider's exact point is unavailable | limitation, stated |

## O. Implementation evidence, batch 2 (Phase B, approved 2026-10-20)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-IMPL-10 | Route geometry is persisted in a dedicated `rides/{id}/route/current` document and restored after process death | Test-confirmed (mechanism) | `RouteGeometryCodec.kt` (pure encode/decode, 2,000-vertex cap), `RideRepository.updateRoute`/`fetchRouteGeometry`, `RideViewModel.restorePersistedRouteGeometry`; `RouteGeometryCodecTest` (6) green; suite 285/0 | confirmed for the mechanism |
| E-IMPL-11 | The B-03 **device** end-to-end proof could not be obtained | Unverified (environment) | The device's ride created at 14:57 (code E8WNRX) is absent from the configured Firestore project (REST read: 1,837 documents, newest `createTime` 2026-10-19T01:09); the app resolves the invite code via its local fallback; the worktree also lacks the newer main-repo active-ride re-discovery path | limitation, stated |
| E-IMPL-12 | An implausibly distant place selection is flagged with an advisory warning that never blocks or rewrites the selection | Test-confirmed + Runtime-observed | `PlaceProximityGuard` (1,000 km threshold), `PlaceProximityGuardTest` (7, incl. the exact "Pavana" → Italy case and the "long trip stays allowed" case); device: a 50.2 km destination produced no warning | confirmed |
| E-IMPL-13 | The guidance banner pairs the distance, the icon and the instruction with one maneuver on a live OSRM route | Runtime-observed | `/tmp/opencode/b03_rerouted.png` — "100 m / Turn right" with a right-turn icon, "Then: Continue left onto Mar…" | confirmed |

## P. Implementation evidence, batch 3 (Phase B, approved 2026-10-20)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-IMPL-14 | The route is drawn as a four-pass band with a zoom-modulated width, screen-spaced direction dots and cached (simplified) world geometry | Test-confirmed + Runtime-observed | `RouteRenderMath`/`RouteGeometryCache` (ported with the `Double` fixes), `RideMapCanvas` route block; 30 ported tests; device `/tmp/opencode/p3_routeband.png` (slate casing + amber core + pale streak), `p3_fitgroup.png` | confirmed |
| E-IMPL-15 | The scale bar is truthful and zoom-dependent | Test-confirmed + Runtime-observed | `ScaleBarMath` (slippy formula in meters per dp, snapped 10 m…5 km); device caption **"10 m" → "50 m"** on zoom-out (`p3_scalebar.png`, `p3_scalebar2.png`); the old build always read "500 m" | confirmed |
| E-IMPL-16 | Map captions carry an RT-original halo, measured once | Test-confirmed + Runtime-observed | `MapLabelTheme` + 8-direction offset paint; device `p3_routeband.png` (halo on "START") | confirmed |
| E-IMPL-17 | The rider hit target is density-correct (24 dp) | RideTogether-source-confirmed | `RideMapCanvas` hit test (was a raw 70f px constant) | confirmed (code) |
| E-IMPL-18 | Overview framing is chrome-aware | RideTogether-source-confirmed + Runtime-observed | `MapCameraController.showOverview(topInsetPx, bottomInsetPx)` + fit-group call site; device `p3_fitgroup.png` | confirmed |
| E-IMPL-19 | The scale-bar caption can lose its leading digit on the pale day map (white at 80 % alpha) | Runtime-observed | `p3_scalebar.png` — the "1" of "10 m" is invisible against a light building | confirmed (logged, not fixed) |

## Q. Implementation evidence, batch 4 (Phase B, approved 2026-10-20)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-IMPL-20 | Rider markers expose fix quality, freshness and a "direction unknown" state | Test-confirmed | `RiderMarkerStyle` (accuracy buckets, freshness buckets, ring alpha, last-seen label, `canDrawDirectionArrow`); `RiderMarkerStyleTest` (8) green | confirmed (unit) |
| E-IMPL-21 | Overlapping riders are separated deterministically and the selected rider is hit-tested first | Test-confirmed | `RiderMarkerStyle.clusterRiders`/`stackingOffset`; `RideMapCanvas` hit test reorders by selection; `RiderMarkerStyleTest` (order-independence, determinism) | confirmed (unit) |
| E-IMPL-22 | The overlay order is explicit and the map chrome is published to the renderer (labels under the chrome are suppressed) | Test-confirmed | `MapOverlayContract` + `MapChromeInsets`; `RideMapCanvas` label suppression; `MapOverlayContractTest` (5) green | confirmed (unit) |
| E-IMPL-23 | The map surface carries an accessibility description | RideTogether-source-confirmed | `RideMapCanvas` canvas `semantics { contentDescription = … }` | confirmed (code) |
| E-IMPL-24 | Batch 4 device verification | Unverified (device offline) | fbe12520 dropped off ADB (USB and wireless) after the build; the batch is unit-verified only | limitation, stated |
| E-IMPL-24 | Batch 4 device verification | Runtime-observed (partial) + Unverified (rest) | device fbe12520, apk `69ae8277634aea2b13d6237e8b104683` installed 17:29; the build installs, launches and renders (three-band route, direction dots, rider marker, chrome-aware fit-group framing) — `/tmp/opencode/b4_live.png`, `b4_overview.png`. **Not demonstrable on this device:** chrome-aware label suppression (the planner's own zoom gate already hides labels at that zoom), rider stacking/selection priority (a single rider), the stale "last seen" label and the bearingless pip (a fresh fix with a bearing), the fix-quality ring on a second rider | partial — stated |

## R. Implementation evidence, batch 5 (Phase B, approved 2026-10-20)

| ID | Claim | Class | Evidence | Confidence |
|---|---|---|---|---|
| E-IMPL-25 | The map renders at a declared target rate and publishes frame statistics | Test-confirmed | `FrameBudget` (rate limiting, over-budget counting, p95, on-budget ratio) + the camera state is published only on a budgeted frame while the animation stays time-based; statistics logged every 5 s; `FrameBudgetTest` (7) green | confirmed (unit) |
| E-IMPL-26 | Every amenity category colour is separable from the map in **both** themes (day land #F8F7F7, night land #13263C) | Test-confirmed | `AmenityPalette` (10 categories × 2 themes) + `AmenityPaletteTest` (4); measured contrasts 3.5–10.6 (day) and 4.3–8.9 (night) against a 2.2 bar | confirmed |
| E-IMPL-27 | Dense amenities are grouped deterministically and the selected one stays on top and tappable | Test-confirmed | `AmenityClustering` (union-find merging towards the smallest OSM id) + `AmenityClusteringTest` (5) incl. order independence | confirmed (unit) |
| E-IMPL-28 | Batch 5 device verification | Unverified (device) | fbe12520's display entered sleep and could not be woken (`mWakefulness=Asleep`, empty screencap), and the on-device OSRM estimate stopped completing (host OSRM healthy: 200 in 0.57 s), blocking the flow that reaches the map | limitation, stated |
