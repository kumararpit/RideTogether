# Implementation Backlog — Google Maps → RideTogether

**Status:** B-01, B-02, B-04 and B-09 are **IMPLEMENTED and verified** (implementation record at the end of this document). Everything else remains a specification awaiting approval.
**Target:** enhancement worktree `/home/arpit/Projects/RideTogether-google-maps-enhancements` (branch `work/google-maps-feature-enhancements` @ `ef60bff`).
**Evidence IDs** refer to `google-maps-evidence-ledger.md`; feature IDs to the `feature-NN-*.md` specifications.
**Invariant guard:** the nine invariants of the mission (camera/gesture, qualified bearing, bounded render-only dead reckoning, multi-waypoint ordering + leg boundaries, Firestore plan versioning/ownership, navigation correctness, no silent place substitution, independent stack, no destructive git) are restated per task below.

> **Note on the worktree divergence:** the multi-stop feature (Feature 07) exists only as *uncommitted* work in the main repository. Any task touching stops/route plans must be reconciled with that work first; the main repository must not be modified by the audit or by an implementation pass that has not been approved to do so.

---

## P0 — Navigation and safety correctness

### B-01 · Pair the banner instruction with the distance it describes
| | |
|---|---|
| **User-visible outcome** | The maneuver banner, its icon, and the spoken prompt all name the **same upcoming maneuver** as the distance being counted down. |
| **Defect** | F1-D1 — the banner shows `steps[current]` while the distance targets `steps[current+1]` (evidence E-F1-11, E-F1-12; device capture `1a_state.png` showing "82 m / Head left / Then: Turn right" against live OSRM `depart/left @ 0 m`, `turn/right @ 80.2 m`). |
| **Evidence** | E-F1-11, E-F1-12, F1-A13 (reference maneuver taxonomy), F1-A15 (reference step index) |
| **Files likely to change** | `app/src/main/java/com/example/data/location/NavEngine.kt`; `app/src/main/java/com/example/ui/components/NavigationHud.kt`; `app/src/main/java/com/example/ui/screens/LiveRideScreen.kt`; `app/src/main/java/com/example/ui/viewmodel/RideViewModel.kt` |
| **Original implementation** | Introduce an explicit *upcoming maneuver* index in `NavigationProgress` (the first step whose maneuver vertex has not been passed). Drive the primary text, the icon, and the voice from it. Keep `currentManeuverIndex` semantics for the multi-leg consumers, or re-map them explicitly and update every consumer. |
| **Tests to add** | `ManeuverPairingTest` (fails on current code by design); `ManeuverPairingTest.voiceMatchesBanner`; `HudSnapshotConsistencyTest`; extend `NavigationProgressAndEtaTest.test 7` with monotonicity including the straight-line fallback branch |
| **Device acceptance** | AC-1 (banner shows the upcoming maneuver with the matching distance on the 3-leg Kamshet→Talegaon→Lonavala route), AC-2 (spoken prompt names the same maneuver), AC-5 (legacy ride unaffected) |
| **Invariants affected** | #6 navigation correctness (this *is* a correctness fix); #1 camera untouched; #7 no place substitution |
| **Dependencies / risk** | Medium — the index is consumed by the HUD, the "Then:" line, the voice and (in main) the leg logic. Mitigation: new field, no change to existing semantics. |
| **Complexity** | M |

### B-02 · Reset the voice announcement history on every route change
| | |
|---|---|
| **User-visible outcome** | After a reroute, the first maneuver of the new route is **spoken**. |
| **Defect** | F1-D3 — `resetAnnounceHistory()` has no call site, so `speakManeuver` suppresses every new-route maneuver at or below the previous route's maximum index (E-F1-14). |
| **Files** | `app/src/main/java/com/example/ui/viewmodel/RideViewModel.kt` (all `navEngine.setRoute(...)` call sites); `app/src/main/java/com/example/data/location/NavAudioGuidance.kt` |
| **Original implementation** | Call `navAudioGuidance.resetAnnounceHistory()` at each route installation; preferably key suppression on `(routeVersion, maneuverIndex)` so a new route can never inherit an old suppression. |
| **Tests** | `AnnouncementResetOnRerouteTest` (first maneuver of the new route is announced; a lower index is not suppressed) |
| **Device acceptance** | AC-3 (reroute via ↻ ⇒ the first new maneuver is announced) |
| **Invariants** | #6; no camera/bearing/DR/leg/Firestore change |
| **Risk / complexity** | Low / S |

### B-03 · Persist or refetch route geometry across process death
| | |
|---|---|
| **User-visible outcome** | Reopening the app during a live ride restores the **route line and a real maneuver banner**, not "Follow Route / Remaining 0 m". |
| **Defect** | F12-D1 / F7-D2 — `updateRoute` writes only distances to Firestore; `routePoints`/`navigationSteps` are memory-only (E-F7-05, device-confirmed). |
| **Files** | `app/src/main/java/com/example/data/firebase/RideRepository.kt`; `app/src/main/java/com/example/ui/viewmodel/RideViewModel.kt` (restore listener) |
| **Original implementation** | Either (a) persist a bounded, versioned route geometry (e.g. ≤2,000 points + the plan version) or (b) make the restore path deterministically refetch the route from the stored plan. Option (b) is simpler and keeps Firestore small; option (a) is faster offline. |
| **Tests** | `RouteRestorationTest`; keep `RideViewModelRerouteAndDistanceTest`, `NavigationTelemetryRegressionTest` green |
| **Device acceptance** | Force-stop the app mid-ride, relaunch ⇒ the route and banner are restored (no reroute needed) |
| **Invariants** | #5 (route-plan data must stay separated from rider telemetry); #4 (leg boundaries preserved in the persisted/re-fetched geometry) |
| **Risk / complexity** | Medium / M |

---

## P1 — Core navigation experience

### B-04 · Guard maneuver advancement (one per fix, accuracy-gated, projected)
F1-D2 (E-F1-13) — the `while` loop can skip several maneuvers in one fix and uses a straight-line 25 m test without an accuracy gate.
**Files:** `NavEngine.kt`. **Original implementation:** cap at one advance per fix; require an accuracy-reliable fix; advance on a *projected* pass, with a bounded force-advance so progression cannot stall. **Tests:** `ManeuverAdvanceCapTest`, `ManeuverAdvanceNoStallTest`. **Device acceptance:** no skipped or double-announced maneuvers across a 3-leg route. **Invariants:** #6, #1 (unchanged). **Risk:** Medium (a too-strict rule stalls progress). **Complexity:** M. **Approval:** required — touches navigation progression.

### B-05 · Locality-aware stop confirmation + advisory distance warning
F6-D1/F6-D2 (E-F6-04, device capture `d8`: "Pavana" → Italy ⇒ 18,066 km estimate).
**Files:** `ui/screens/CreateRideScreen.kt` (stop row + suggestion row), `data/place/SelectedPlace.kt` (expose locality), `data/place/PhotonPlaceParser.kt` (already parses it).
**Original implementation:** show the confirmed stop's locality (city/district/state); when a selection is implausibly distant from the current location, show a **warning** with Confirm/Change. **Never** alter coordinates; **never** block a legitimate long-distance trip.
**Tests:** `StopLocalityDisplayTest`, `DistantSelectionWarningTest`. **Device acceptance:** selecting a distant same-name place shows the locality and a warning; the coordinates are unchanged. **Invariants:** #7 (strengthened). **Complexity:** S/M.

### B-06 · Chrome-aware overview framing + look-ahead
F2-D4 (E-F2 evidence in `feature-02`) — `showOverview` uses a uniform 100 px padding and ignores the control column and bottom card.
**Files:** `data/map/MapCameraController.kt`, `ui/components/RideMapCanvas.kt`.
**Original implementation:** per-side padding (top inset, bottom card, control column) and a look-ahead factor; reuse the reference's *formula shape* (fit-bounds zoom) with RT-original constants.
**Tests:** `OverviewFramingTest`. **Device acceptance:** "Fit Group" shows every rider and the route clear of the FABs. **Invariants:** #1 (framing only, no gesture/camera semantic change). **Complexity:** S.

### B-07 · Density-correct touch targets (48 dp floor)
F11-D3 / F5 (rider tap radius is a raw 70 px) — apply the 48 dp convention to every map control and the rider hit target, with enlarged hit rects for small icon buttons.
**Files:** `ui/components/RideMapCanvas.kt` (hit tests), `ui/components/NavigationHud.kt` (control sizes).
**Tests:** `TouchTargetTest`. **Device acceptance:** tapping a rider selects it reliably on a 3× device and on a lower-density device. **Invariants:** #1 (hit-testing only). **Complexity:** S.

### B-08 · Remove the composition-phase diagnostics — **blocked by D7**
F12-D2 (E-F12-01/02: 186 log lines / 20 s; 9.78 % janky, p95 65 ms).
**Blocked:** the mission forbids removing the D7 instrumentation while D7 is open. **Sequence:** (1) close D7 with a failing coordinate under the standing probe protocol; (2) then remove the diagnostics and re-measure before/after with the same harness. **Files:** `ui/components/RideMapCanvas.kt`. **Tests:** the full offline suite. **Device acceptance:** zero `RT-D7` lines in logcat during a 60 s navigation session; `dumpsys gfxinfo` before/after attached to the report. **Invariants:** #1 (must not change what is drawn). **Complexity:** S (once unblocked).

### B-09 · Make the offline suite hermetic
F12-D3 (E-F12-03) — `ExampleRobolectricTest."test ride repository flow"` performs a **live Firebase** write inside a Robolectric `runBlocking` and hangs the whole offline suite when the network is down.
**Files:** `app/src/test/java/com/example/ExampleRobolectricTest.kt`.
**Original implementation:** replace the live repository with a fake/emulator-independent double, or mark the class as an integration test excluded from the offline task. **Tests:** n/a (test hygiene). **Acceptance:** `./gradlew :app:testDebugUnitTest --offline` completes with the network down. **Complexity:** S.

### B-10 · Persisted route-plan adoption for members (multi-device verification)
Open item from the program's "moving-device / multi-device verification" list. **Depends on:** the multi-stop feature being present in the target worktree (Feature 07 divergence). **Acceptance:** a second device joins by code, adopts the leader's ordered plan, and the stop order matches. **Complexity:** L. **Blocked:** needs a second device and the worktree reconciliation.

---

## P2 — Visual quality and interaction polish

### B-11 · Cached route geometry + three-band, zoom-modulated route styling
F4-D1/D2/D3 (E-F4-05/06) — per-frame `List<Offset>` + `Path` of every route point; a fixed ~32.5 dp 4-band stroke.
**Status:** a validated implementation already exists **uncommitted in the main repository** (`ui/map/RouteRenderMath.kt`, `ui/map/RouteGeometryCache.kt`, `RouteRenderMathTest`, `RouteGeometryCacheTest`) — validated against the live 3-leg route: 213/619/1389 vertices kept at z11/z14/z17, **0 leg-spanning segments**, max deviation 1.13–1.20 px. **Action:** port it to the worktree, with the leg-boundary pinning preserved.
**Files:** `ui/components/RideMapCanvas.kt`, new `ui/map/*`. **Tests:** port `RouteRenderMathTest`, `RouteGeometryCacheTest`; add `RouteLegBoundaryTest`, `RouteGeometryTransformTest`. **Acceptance:** frame-time improvement measured on device; screenshot trio matches the measured band relationships. **Invariants:** #4 (leg boundaries), #6. **Complexity:** M.

### B-12 · Halo typography and distance number/unit tiering
F1-A3 (reference two-tier distance) — RT's distance is a single-style string.
**Files:** new `ui/map/MapLabelTheme.kt` (a validated version exists uncommitted in main), `ui/components/NavigationHud.kt`. **Tests:** `MapLabelThemeTest` (exists uncommitted in main). **Invariants:** none (visual). **Complexity:** S.

### B-13 · Dynamic scale bar
The static "500 m" bar is wrong at every zoom except one. **Files:** `ui/components/RideMapCanvas.kt` (+ `ui/map/ScaleBarMath.kt`, validated uncommitted in main). **Tests:** `ScaleBarMathTest` (exists uncommitted in main). **Invariants:** none. **Complexity:** S.

### B-14 · Amenity relevance priority, palette, clustering and selection semantics
F5-D1..D4 — category-only priority, a palette that fights the OSM raster, no clustering, weak selection.
**Files:** `ui/components/AmenityLabelPlanner.kt`, `ui/components/AmenityIconConfig.kt`, `ui/components/RideMapCanvas.kt`. **Tests:** `AmenityRelevancePriorityTest`, `AmenityPaletteContrastTest`, `AmenityClusterTest`, `AmenitySelectionTest`. **Invariants:** none (rendering only; the cache/data contract is untouched). **Complexity:** M.

### B-15 · Rider-marker accuracy/freshness disclosure and a "direction unknown" state
F8-D1/D4 — no accuracy ring; a missing bearing renders as north. **Files:** `ui/components/RideMapCanvas.kt`, `data/map/RiderMarkerAnimator.kt` (expose a *predicted* flag, S8-4). **Tests:** `MarkerAccuracyPresentationTest`, `RiderLabelCacheTest`; **keep** `Task17RiderMarkerSmoothingTest` green. **Invariants:** #2, #3 — the DR constants and its render-only isolation must not change, and the accuracy ring must never be fed by the DR prediction. **Complexity:** M.

### B-16 · Rider marker stacking, selection priority and freshness
F9-D1..D4 — overlapping riders, first-match hit testing, no last-seen age, no relative position.
**Files:** `ui/components/RideMapCanvas.kt`, `data/model/RideModels.kt` (additive fields only). **Tests:** `RiderMarkerClusterTest`, `RiderHitTestPriorityTest`, `RiderFreshnessTest`. **Invariants:** #5 (no change to ownership/ordering/SOS). **Complexity:** M.

### B-17 · Overlay contract + chrome→renderer coupling + accessibility pass
F11-D1/D2/D4 — no declared overlay order, no chrome geometry published to the renderer, a11y gaps.
**Files:** `ui/components/RideMapCanvas.kt`, `ui/components/NavigationHud.kt`, new `ui/map/MapOverlayLayer.kt`. **Tests:** `MapOverlayOrderTest`, `ChromeOcclusionTest`, `BannerAccessibilityTest`. **Invariants:** #1 (the camera must not move; only route placement responds). **Complexity:** M.

### B-18 · Frame budget and in-app frame statistics
F2-D3 / F3-D5 — no target rate, no statistics, no frame-time overlay.
**Files:** `ui/components/RideMapCanvas.kt` (frame loop), new `ui/map/FrameStats.kt`. **Tests:** `FrameBudgetTest`. **Acceptance:** jank counters visible in a debug overlay; frame time captured before/after B-08. **Invariants:** none. **Complexity:** M.

### B-19 · Maneuver taxonomy upgrade (icons)
F1-A13 — the reference has 24 maneuver types; RT maps icons by `String.contains`, so `merge`/`fork`/`roundabout`/`ramp` cases fall through to a generic arrow.
**Files:** `ui/components/NavigationHud.kt` (`getManeuverIcon`), `data/model/RideModels.kt` (additive). **Tests:** `ManeuverIconMappingTest` (one case per type). **Invariants:** none. **Complexity:** S/M.

---

## P3 — Optional or data-dependent

| ID | Item | Dependency / note | Complexity |
|---|---|---|---|
| B-20 | Traffic presentation layer (delay bucket, traffic-styled route, "less/more traffic" callout) | Needs an independent congestion source; **do not** present stale OSM data as live traffic. The reference's data is a proprietary service (`GetTrafficConditions`) — **not** to be called. | L |
| B-21 | Alternative routes | OSRM returns one route per request; a second request + comparison model is unproven. Cost/latency must be measured first. | L |
| B-22 | 3-state audio level (alerts-only / muted / unmuted) | Mirrors the reference's a11y strings; small, but needs a product decision on the semantics. | S |
| B-23 | Voice-command surface ("distance to next turn") | Large surface, small user base; depends on B-01. | L |
| B-24 | Gesture intent separation (pinch vs. twist) | Touches protected gesture arbitration; separate approval; must not introduce tilt. | M |
| B-25 | Camera modes `INSPECT_STEP` / `ARRIVAL` | Requires B-01 to make step inspection meaningful. | M |
| B-26 | Lane guidance | Needs a lane model; the reference's is native (`NativeLaneAwarePolylineInterpolator`). **Not** implementable with the current data. | XL |
| B-27 | Incident cards / closure banners | Needs an incident data source; the reference's card artwork is proprietary and must not be copied. | L |

---

## Explicitly **not** recommended (and why)

- **Replicating the native renderer, labeler, or guidance engine** — unavailable and unnecessary; the RT equivalents must be original.
- **Copying Google assets** (icons, tiles, fonts, chevrons, POI categories, card artwork) — prohibited.
- **Using Google's services** (Places, Directions, Maps SDK, traffic RPCs, nav tiles) — prohibited by the mission and by the independent-stack invariant.
- **Adding tilt/3D camera** — not needed for a 2D motorcycle-touring map, and it would put the D5 pivot invariant at risk.
- **Traffic from OSM `maxspeed`/`type`** — not live traffic; presenting it as such would mislead.

---

## Sequencing recommendation

1. **B-02** (S, unblocks voice) → **B-01** (M, the headline defect) → **B-04** (M, guards the same area).
2. **B-09** (S, unblocks reliable verification) → **B-08** (S, *after* D7 closes) → **B-18** (M, measures the result).
3. **B-05** (S/M, the named open risk) → **B-03** (M, restoration) → **B-07** (S).
4. **B-11…B-19** as visual polish, in that order.
5. **B-20…B-27** only with data/architecture decisions.

Every task above is **blocked from implementation** until the user explicitly approves it; the audit itself has not written any of them.

---

# Implementation record (approved 2026-10-20, applied in the enhancement worktree)

Approval covered the first four items of the sequencing recommendation. Implemented in
`/home/arpit/Projects/RideTogether-google-maps-enhancements`; the **main repository was not
modified by the audit** (its own separately-approved Phase B renderer work was corrected
separately — see the note at the end).

## B-01 · Pair the banner instruction with the distance it describes — **DONE**

**Change.** `NavigationProgress` gained `upcomingManeuverIndex`: the *first step whose maneuver
vertex the rider's projected position has not yet passed* (`NavEngine.computeUpcomingManeuverIndex`).
The distance target now points at **that** step's vertex, and `getCurrentStep()` / `getNextStep()`
return the upcoming step and the one after it. The previous `currentManeuverIndex` keeps its
meaning (last maneuver *performed*) and is now pure bookkeeping. The banner text, the maneuver
icon and the spoken prompt therefore all describe the maneuver the distance counts down to.

**Verification.**
- `ManeuverPairingTest` (9 new tests) — the pairing invariant, the distance-to-shown-step
  equality (road distance within 60 m), the monotonic countdown, the post-turn step, the
  one-advance-per-fix cap, the accuracy gate, the no-steps fallback, and the announcement
  history contract.
- **Runtime (device fbe12520, worktree build md5 `5a93f6367811683ff53963c05b0f080a`,
  installed 13:54)**: after a reroute rebuilt the route, the banner reads
  **"46 m" / "Turn left" / "Then: Turn right"** with a **left-turn icon matching the text**
  (`/tmp/opencode/1a_worktree_banner2.png`). The pre-fix capture on the same device
  (`/tmp/opencode/1a_state.png`) showed the incoherent pairing: **"82 m" / "Head left" /
  "Then: Turn right"**, where the 82 m belonged to the *secondary* line's maneuver and the
  icon/text described the already-performed departure.
  *Limitation, stated honestly:* the rider's live position is masked in `dumpsys location`
  and the live fix accuracy is ≈93 m, so the **absolute** identity of the maneuver
  ("Turn left" at 46 m) could not be re-derived independently from the service for that exact
  point. What is proven is (a) the numeric pairing invariant by unit test, and (b) the
  banner's internal consistency on the device (icon = instruction = the distance's target),
  which was previously violated.

## B-02 · Reset the voice announcement history on route change — **DONE**

**Change.** `navAudioGuidance.resetAnnounceHistory()` is now called at every route
installation (initial route, route fetch and reroute). The suppression state moved into a
pure, Android-free `AnnouncementHistory` class (`shouldAnnounce` / `markSpoken` / `reset`), so
the contract is unit-testable and has a single source of truth.

**Verification.** `ManeuverPairingTest.announcement history is reset when a new route is
installed` (pure, no TTS needed) + the ViewModel call sites (3).

## B-04 · Guard maneuver advancement — **DONE**

**Change.** The unbounded `while` loop became a **single, accuracy-gated advance per fix**:
at most one maneuver per location fix, and only an accuracy-reliable fix (≤30 m) may advance
the performed-maneuver counter. Because the *guidance* is now derived from the projection
(B-01), a conservative advance can no longer hide a turn.
**Verification.** `ManeuverPairingTest.a single fix never advances more than one maneuver` and
`…an inaccurate fix never advances the performed maneuver counter` + the unchanged
`NavigationProgressAndEtaTest` / `NavEngineAndSosTest` suites.

## B-09 · Make the offline suite hermetic — **DONE**

**Change.** `ExampleRobolectricTest."test ride repository flow"` no longer initialises a
**live Firebase** app. The repository's Firestore dependency is nullable and every access is
null-safe, so the test constructs `RideRepository(locationTracker, firestore = null, context)`
and exercises the repository fully in-memory. The SOS assertions were replaced by the
*documented null-backend contract* (the trigger must fail explicitly and leave no dangling
alert) — the SOS lifecycle itself remains covered offline by `SosLifecycleAndDeliveryTest` /
`SosAckDeliveryAndDismissalTest` with a fake backend.
**Verification.** The full worktree suite — **27 classes / 272 tests / 0 failures / 0 errors /
0 skipped** — now completes in **2 m 37 s** instead of hanging indefinitely (>30 min observed,
thread-dumped in the earlier session). The live-Firebase test now *runs* instead of hanging.

## Regression status

`./gradlew :app:testDebugUnitTest --offline --rerun-tasks` in the enhancement worktree:
**BUILD SUCCESSFUL — 272 tests, 0 failures.** `:app:assembleDebug` green
(apk md5 `5a93f6367811683ff53963c05b0f080a`), installed and verified on the device.

## Note on the main repository (separate, previously-approved work)

The earlier Phase B renderer work in the **main** repository was verified by its own test
suite, which **failed 3 of 360 tests** and exposed two **real defects** in that code — both
now fixed and covered by tests:
1. **Float precision loss in the route transform** — the tile-17 world vertices were stored in
   a `FloatArray`, whose ~0.5-unit resolution at those magnitudes is ~600 screen px at street
   zoom, and the transform multiplied before subtracting. The world geometry is now `DoubleArray`
   and the whole world→screen computation stays in `Double`; the transform API now takes the
   geographic centre (a tile index had been passed where a longitude was expected — a second,
   independent bug in the same call). Regression: `RouteRenderMathTest.transform keeps
   sub-pixel accuracy for a long route`.
2. **A 256× error in the scale-bar factor** — the slippy-map formula yields meters per *tile
   pixel*; the code divided by 256 again, so every scale-bar segment was off by 256×.
   Regression: the two `ScaleBarMathTest` budget tests.

## Still open (unchanged)

B-03 (route-geometry persistence), B-05..B-07, B-10..B-27 — and **B-08 remains blocked by D7**:
the composition-phase diagnostics must not be removed until D7 is closed with a failing
coordinate.

---

## Implementation record, batch 2 (approved 2026-10-20): B-03 and B-05

### B-03 · Persist/refetch the route geometry — **implemented; device proof BLOCKED (documented)**

**Change.**
- New pure codec `data/firebase/RouteGeometryCodec.kt` (`encode`/`decode`, a 2,000-vertex cap, `describe`).
- `RideRepository.updateRoute(...)` now also writes the polyline, the maneuver steps and the totals to a **dedicated document** `rides/{rideId}/route/current` — deliberately *not* into the ride document, which is re-read by every listener on every member/location update.
- `RideRepository.fetchRouteGeometry(rideId)` reads it back, tolerating malformed documents.
- `RideViewModel` restore path: when a ride is restored (process death) **without** geometry, it fetches the persisted geometry once per ride and re-arms the nav engine (`navEngine.setRoute`), guarded against overwriting a newer live route and against a stale in-flight fetch.

**Verification.**
- `RouteGeometryCodecTest` (6 tests): exact round-trip of 120 vertices + steps, the truncation cap and its `truncated`/`pointCount` flags, malformed documents decoding to `null`, invalid/out-of-range coordinates dropped, and the human-readable description.
- Full worktree suite: **29 classes / 285 tests / 0 failures / 0 errors / 0 skipped**; `:app:assembleDebug` → apk md5 `781f6c4985efbd2870c232154be8dfda`, installed on fbe12520.
- **Device end-to-end proof: NOT achieved — and the reason is environmental, not the implementation:**
  1. The app's Firestore backend is not observable from this device's network path. The ride created on the device at 14:57 (invite code **E8WNRX**) never appears in the configured project (`ridetogether-f558a`, read back through the Firestore REST API: 1,837 ride documents, newest `createTime` **2026-10-19T01:09**), and the app resolves the invite code through its own local fallback. There is therefore no server-side copy of the geometry document to inspect, and the restore path cannot be exercised end-to-end.
  2. The **worktree** additionally lacks the active-ride re-discovery path (the newer main-repository code has it): after a process death the worktree build cannot re-enter an active ride on its own — the ride must be re-entered by invite code, and the "Join Ride Lobby" control did not respond to synthetic taps in this session.

  What *is* proven: the codec round-trip (unit), the repository write/read contract (code-reviewed, null-safe, no Firestore types in the codec), and the restore wiring (code-reviewed, with the stale-fetch and live-route guards). The device acceptance criterion for B-03 therefore stays **OPEN** and must be re-run in an environment with a reachable backend (or after the worktree/main reconciliation that brings the re-discovery path across).

### B-05 · Locality-aware, advisory distance warning — **implemented**

**Change.**
- New pure `data/place/PlaceProximityGuard.kt`: 1,000 km threshold, `warningFor(reference, place)`, `warningText(distance)`; it returns a **message**, never a rejection, and never touches a coordinate.
- `CreateRideScreen`: when the selected destination is implausibly far from the start point, a warning row appears under the field with a dismissible **"Use anyway"** action. The warning is advisory; the ride can still be created with the selected place.

**Verification.**
- `PlaceProximityGuardTest` (7 tests): the exact cross-country case observed on device ("Pavana" → Italy) is flagged with its distance; a 50 km destination is never flagged; a legitimate 1,600 km trip is flagged but **not** blocked and its coordinates are provably unchanged; the 1,000 km threshold; missing reference/place; the distance measurement; and the message formatting.
- **Device:** selecting "Lonavala" (50.2 km) produced **no** warning — correct behaviour for a plausible destination. The distant-match case is unit-verified only: the local ranking already surfaces the nearest same-name place first, so the wrong-place case is hard to force by tapping, which is the mitigation working with the warning as the backstop.

### B-05/B-03 — what was *not* done
The intermediate-stop editor (main-repository WIP) still shows the **name only** after confirmation; the locality line and the warning must be applied there as well when that feature is ported to the worktree (Feature 07 reconciliation).

---

## Implementation record, batch 3 (approved 2026-10-20): B-06, B-07, B-11, B-12, B-13

All in the enhancement worktree. The renderer port (B-11/B-12/B-13) was taken from the main repository's Phase B work **after** its two test-proven bug fixes (the `Float`→`Double` world geometry and the corrected transform API), so the ported code carries the fixes rather than the defects.

| Task | Change | Verification |
|---|---|---|
| **B-11** route rendering | Ported `ui/map/RouteRenderMath.kt` (Double world geometry, tiered Douglas–Peucker, leg-boundary pinning) and `ui/map/RouteGeometryCache.kt`; the canvas route block now draws **four passes** — soft shadow → brand-slate casing `#0C1017` → amber core `#FF9800` → pale-amber centre streak `#FFE0A3` — with a **zoom-modulated** core width (18 → 17 dp) and direction dots spaced by **screen** distance. `routeLegStartIndices` was added to `RideMapCanvas` (empty in the worktree until Feature 07 is ported) so the boundary pinning is in place from the start. | 4 ported test classes (30 tests) + full suite **33 classes / 315 tests / 0 failures**; apk md5 `7f8b7b12…` installed. **Device:** the live map shows the three-band stroke with the pale centre streak and the screen-spaced dots (`/tmp/opencode/p3_live.png`, close-up `/tmp/opencode/p3_routeband.png`), and the overview framing renders the same bands (`/tmp/opencode/p3_fitgroup.png`) |
| **B-12** halo typography | `ui/map/MapLabelTheme.kt` ported; START/FINISH captions now use the 8-direction offset halo (RT ink `#BF0B0F19`, 1 dp radius) and their text layouts are **measured once in composition** instead of per frame. | `MapLabelThemeTest` (5) green; **device:** the "START" caption shows its halo over the pale map (`/tmp/opencode/p3_routeband.png`) |
| **B-13** dynamic scale bar | `ui/map/ScaleBarMath.kt` ported (meters-per-dp from the slippy formula, snapped segments 10 m … 5 km); the bar is drawn in a `Canvas` (draw-phase only) and its caption/length now follow the real ground distance. The old static "500 m" is gone. | `ScaleBarMathTest` (7) green; **device:** the caption changed **"10 m" → "50 m"** when zooming out (`/tmp/opencode/p3_scalebar.png`, `p3_scalebar2.png`) — previously it always read "500 m" |
| **B-07** touch targets | The rider hit radius changed from a raw `70f` px constant to a density-correct **24 dp** target (matching the amenity target and the reference's 48 dp floor). | code-reviewed; the constant is now density-derived |
| **B-06** chrome-aware framing | `MapCameraController.showOverview` takes `topInsetPx`/`bottomInsetPx`; the fit-group control passes the banner and trip-HUD insets, so a fitted route is no longer placed under the chrome. | code-reviewed + device: the fit-group overview renders the route clear of the trip HUD (`/tmp/opencode/p3_fitgroup.png`) |

**Legibility note (new observation, not fixed):** the scale-bar caption is white at 80 % alpha, so on the **day** map a leading digit can be hard to read against pale buildings (the "1" of "10 m" disappears). A halo or a scrim behind the caption would fix it — filed as a follow-up, not changed here.

**Not done in this batch:** B-08 (blocked by D7), B-14 (amenity palette/clustering/selection), B-15 (rider accuracy/freshness), B-16 (rider stacking/hit priority), B-17 (overlay contract + a11y), B-18 (frame budget), B-19 (maneuver taxonomy) — each still needs its own approval.

---

## Implementation record, batch 4 (approved 2026-10-20): B-15, B-16, B-17

All in the enhancement worktree. Two new pure cores carry the decisions so they are testable without a device:
`ui/map/MapOverlayContract.kt` (overlay order + chrome model) and `ui/map/RiderMarkerStyle.kt` (marker presentation rules).

| Task | Change | Verification |
|---|---|---|
| **B-15** rider accuracy / freshness / bearingless | New `RiderMarkerStyle`: accuracy buckets (GOOD ≤10 m, FAIR ≤30, POOR ≤100, VERY_POOR) drive a **fix-quality ring** (a good fix stays clean); freshness buckets (LIVE <15 s, AGED <60 s, OLD <5 min, STALE ≥5 min) drive a **muted dashed casing** and a **"5 min ago"** label; a rider with **no qualified bearing no longer gets a fabricated north arrow** — a neutral "direction unknown" pip is drawn instead. | `RiderMarkerStyleTest` (8) green. The bearing rule preserves invariant #2 (a null bearing is never turned into a direction) and the rules read the **measured** fix only, never the dead-reckoning prediction (invariant #3) |
| **B-16** rider stacking / selection priority / freshness | Overlapping riders are grouped by screen proximity and separated by a **deterministic, id-derived offset** (stable across frames, independent of the member list order); the **selected rider is hit-tested first**, so a selected rider covered by another rider can still be tapped; stale riders show their age. | `RiderMarkerStyleTest` (8) green, incl. "clustering does not depend on the input order" |
| **B-17** overlay contract + chrome coupling + a11y | New `MapOverlayContract`: an explicit, ordered overlay registry (`TILES → ROUTE → RIDERS → AMENITIES → AMENITY_LABELS → WAYPOINT_LABELS → MANEUVER_BANNER → TRIP_HUD → MAP_CONTROLS → SHEETS`) — the RideTogether-original equivalent of the reference's 25-layer registry. The **chrome is published to the renderer** (`MapChromeInsets` derived from the chrome layers) and a map label that would sit under the banner/HUD/controls is **suppressed** — the same idea as the reference pushing `turnCardBodyHeightPx` / `bottomSheetExposurePx` into its renderer every frame. The map surface gained an **accessibility description** (the canvas-drawn markers are invisible to screen readers; the turn instructions live in real `Text` composables and are already readable). | `MapOverlayContractTest` (5) green: the order is total and stable, map content is below the chrome, the chrome layers are exactly banner/HUD/controls, and the inset model detects labels hidden under each chrome band |

**Suite (worktree): 35 classes / 328 tests / 0 failures / 0 errors / 0 skipped**; `:app:assembleDebug` → apk md5 `69ae8277634aea2b13d6237e8b104683`.

**Device verification of batch 4 (device fbe12520, apk `69ae8277634aea2b13d6237e8b104683`, installed 17:29):** the build installs, launches and renders correctly — the live map shows the new three-band route with its screen-spaced direction dots, the rider marker renders with its fix-quality ring, and the fit-group overview frames the route clear of the trip HUD (`/tmp/opencode/b4_live.png`, `/tmp/opencode/b4_overview.png`).

What the device could **not** demonstrate, and why:
- **Chrome-aware label suppression** — at the overview zoom the amenity *planner's own* zoom gate already hides every label, so the chrome suppression cannot be separated visually there; it is **unit-verified** only (`MapOverlayContractTest`).
- **Rider stacking / selection priority** — the ride has a **single rider**, so no two markers overlap and the selected-rider hit priority has nothing to disambiguate; unit-verified only.
- **The stale "last seen" label and the bearingless pip** — the parked device produces a fresh fix *with* a bearing, so neither state can be forced without a second, stale member or a bearing-less fix.
- **Fix-quality ring on another rider** — the only rider is the current user, who already carries the pre-existing accuracy pulse, so the new ring is not visually separable for that marker.

No claim is made that these four behaviours were observed on the device; they are code-verified and unit-tested, and the device confirmed only that the build runs and renders.

---

## Implementation record, batch 5 (approved 2026-10-20): B-18, B-14

All in the enhancement worktree. Two new pure cores: `ui/map/FrameBudget.kt` (render rate + statistics) and `ui/map/AmenityPalette.kt` + `ui/map/AmenityClustering.kt` (marker colour system + grouping).

| Task | Change | Verification |
|---|---|---|
| **B-18** frame budget + statistics | New `FrameBudget`: a declared target rate (60 fps) with `shouldRender(now)`, frame-duration accounting, `framesOverBudget`, a p95 and an `onBudgetRatio()`. The map loop now **publishes its camera state only when the budget allows a draw** — the animation stays time-based (the damping always uses the real elapsed time), so a skipped frame only samples the screen less often; it never slows the motion. The statistics are published every 5 s to logcat (`RideMapCanvas: FrameStats: …`) so they are observable in the field rather than collected and never read. | `FrameBudgetTest` (7) green: the first frame always renders, the rate is limited to the target, 30 fps renders half as often as 60 fps, over-budget frames are counted, the summary is readable, the on-budget ratio is reported, reset clears the counters |
| **B-14** amenity palette / clustering / selection | New `AmenityPalette`: ten RT-original categories, each with a **day and a night variant** chosen against the *measured* raster tones (day land `#F8F7F7`, night land `#13263C` after the night matrix) — every category clears a 2.2 contrast ratio on both backgrounds (verified: 3.5–10.6 day, 4.3–8.9 night). This is the RT-original equivalent of the reference's day/night **style twins**. New `AmenityClustering`: dense amenities are grouped in screen space and drawn as **one marker with a count badge**; the grouping is deterministic and order independent (union-find that always merges towards the smaller OSM id), so pins never flicker or reshuffle. The **selected** amenity is always drawn on its own, on top, and is **hit-tested first**. | `AmenityPaletteTest` (4) + `AmenityClusteringTest` (5) green: every category separable in both themes, the themes differ per category, WCAG-style contrast behaviour, nearby/distant grouping, smallest-id representative, order independence, stability across runs |

**Suite (worktree): 38 classes / 344 tests / 0 failures / 0 errors / 0 skipped** (before the frame-stats log consumer, which added no new test); apk md5 `d6889bf18d481cf92667df35b91a810e` built and installed on fbe12520.

**Device verification of batch 5: PARTIAL — blocked by the device, not by the code.**
- The build **installs and launches**; the app process stays healthy (no crash in logcat) and the earlier batch-3 rendering was confirmed with the same code path.
- The on-map capture of the clustered/palette amenity markers and the frame-statistics log could **not** be completed, for two environmental reasons:
  1. the device's **display went to sleep** and could not be woken programmatically (`mWakefulness=Asleep`; `screencap` returns an empty file even after `keyevent 26` + `wm dismiss-keyguard`);
  2. the **on-device OSRM estimate request stopped completing** (the create-ride flow stayed on "Calculating…" for minutes while the **host's** OSRM answered `http=200` in 0.57 s) — so the flow that reaches the live map was blocked.
- Consequently the amenity clustering, the palette, the count badge and the frame-statistics line are **unit-verified only** and are **not** claimed as device-observed.

---

## Implementation record, batch 6 (approved 2026-10-21): B-19 and B-28

Evidence and design: `docs/reverse-engineering/feature-13-lane-guidance.md`.

### B-19 · Maneuver taxonomy upgrade — **DONE (closed)**

**Change.** New `data/model/ManeuverType.kt`: a RideTogether-original, closed
24-value vocabulary (`DEPART`, `ARRIVE`, `TURN_{LEFT,SLIGHT_LEFT,SHARP_LEFT,…}`,
`KEEP_{LEFT,RIGHT}`, `CONTINUE`, `NEW_NAME`, `ON_RAMP`, `OFF_RAMP`,
`FORK_{LEFT,RIGHT}`, `MERGE_{LEFT,RIGHT}`, `ROUNDABOUT_{ENTER,EXIT}`,
`END_OF_ROAD`, `U_TURN`, `FERRY`, `UNKNOWN`) mapped from the engine's
`(type, modifier)` pair, plus the instruction builder and the icon mapping.
The vocabulary is deliberately smaller than the reference's 48 types: the
extra reference types are combinations the engine never produces, and the
split that matters is the one the raw pair cannot express — `turn/slight
left` (a bear left) versus `continue/slight left` (a keep left).

**Verification.** `ManeuverVocabularyTest` (11 tests).

### B-28 · Lane guidance — **DONE**

> The backlog previously listed lane guidance as **not implementable with the
> current data** (B-26). That judgement was **wrong**: the lane data *is* in
> the route response. It is simply **absent on roads that are not mapped with
> `turn:lanes`**, which is why the implementation must be honest about
> absence instead of being impossible.

**Change.**
- `data/model/LaneGuidance.kt` — the lane model: one lane = a set of arrows +
  the engine's `valid` flag; a guidance = the lanes of one manoeuvre.
- `data/location/OsrmStepParser.kt` — parses the manoeuvre, the exit number,
  the signed turn angle and the lane guidance out of one engine step. The
  lanes are looked up **by manoeuvre location**, so the lanes of the next
  junction can never be shown with this manoeuvre.
- `ui/map/LaneArrowGeometry.kt` — RideTogether-original arrow geometry;
  left/right pairs are exact mirrors of one another.
- `ui/components/LaneGuidanceStrip.kt` — the strip: fits the available width,
  centred, usable lanes in white over dimmed ones, accessibility description.
- `NavigationHud.kt` — the strip is rendered at the top of the turn card's
  instruction column (the reference's `step_instruction_lane_guidance_top`
  placement).
- `RouteGeometryCodec.kt` — the maneuver, exit number, turn angle and lane
  guidance are persisted (additive; old documents decode to the defaults).

**The no-invention rules** (unit-tested):
1. no engine lane data for the manoeuvre ⇒ no guidance and nothing drawn;
2. the lanes of a later intersection are never shown with this manoeuvre;
3. lane data that cannot carry the manoeuvre is dropped instead of displayed;
4. the engine's `valid` flag is copied verbatim — RideTogether never decides
   which lane is usable;
5. unknown lane indications are dropped, and `"none"` is no marking, not an
   arrow.

**Verification.** `LaneGuidanceParsingTest` (12 tests, fixtures are trimmed
copies of real engine responses), `LaneArrowGeometryTest` (7 tests) and
`OsrmLaneGuidanceIntegrationTest` (5 tests, the whole service path over a
verbatim live engine capture). Full offline suite: 58 classes / 493 tests /
0 failures / 0 errors / 0 skipped.

**On-device (RMX3360):** the instrumented `LaneGuidanceDeviceRenderTest`
renders the real turn card and the real strip on the device (3 tests, green,
captures pulled and inspected): the route's lane is bright, the others dimmed,
a two-movement lane is one arrow with a branch, the strip sits at the top of
the instruction column, and a step without lane data leaves the card
unchanged.

**Live app run:** the live ride screen re-armed a live OSRM route
(Pune → Lonavala) and the turn card showed **"82 m / Turn right"** with the
vocabulary icon and **"Then: Turn at the end of the road…"** — the new
`END_OF_ROAD` instruction, announced by the voice as well. **No lane strip
was shown, correctly**: the engine has no lane data for that road (13 probed
Indian routes → **0** lane-bearing intersections; lane data exists only where
OpenStreetMap is mapped with `turn:lanes`).

**Not verified:** a *live in-app* lane strip — the rider is physically in
Pune (no lane data) and the device blocks mock locations from the shell.

### Still open

B-08 (blocked by D7), B-10, B-20 … B-27, plus the new items:
- **B-29** — use the parsed signed turn angle to refine the icon when the
  engine's modifier is coarse;
- **B-30** — show the lane strip in the stop-by-stop itinerary card as well as
  in the live turn card.
