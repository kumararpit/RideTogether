# Feature 12 — Lifecycle, Resilience & Performance

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324 (architecture-level observations only; internals unavailable).
**No application source, test, Gradle, schema, or build artifact was modified.**

---

## 1. Summary

RideTogether's **degradation strategy is largely sound** (foreground location service, cache-first amenities, offline place recents, stale-response guards, arrival latching). The material gaps are: **route geometry is not persisted** (a killed process loses the route), the **map layer recomposes per frame** with per-frame diagnostics still in the tree, and the **offline suite is not hermetic** (a live-Firebase test hangs it).

---

## 2. Google Maps side (architecture-level, no internals claimed)

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F12-A1 | The reference keeps a **persistent navigation session** across app backgrounding (nav mode survived the app being backgrounded/reopened during this session's captures) | Runtime-observed | device fbe12520, Google Maps nav mode remained active across the capture sequence (`aud_gm_8_nav.png` → `aud_gm_10_nav.png`) |
| F12-A2 | The reference degrades gracefully when a map tile/POI source is slow — it shows *older* content and keeps the route rather than blanking | Runtime-observed | base map continued to render during live Overpass/OSRM degradation tests in the earlier program passes (main report §7b) |
| F12-A3 | Tile cache, session/transaction lifecycle and frame scheduling are native | Proprietary/unavailable | `jni_symbols.txt` groups `render` (97), `tile` (27); `mapcore` Java classes are thin JNI holders |

---

## 3. RideTogether (worktree) — verified resilience inventory

| Area | Implementation | Location | Assessment |
|---|---|---|---|
| Background location | foreground service with `FOREGROUND_SERVICE_TYPE_LOCATION` + persistent notification, `stopForeground(STOP_FOREGROUND_REMOVE)` on stop | `data/location/RideLocationService.kt:44-76` | sound |
| Location cadence | `LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, updateIntervalMs)`; a 1 s interval variant | `data/location/LocationTracker.kt:130, 224` | sound |
| Stale-fix rejection | fixes with `accuracyMeters > 30` never reach the marker animator | `RideMapCanvas.kt:162-176` | sound |
| Off-route handling | >120 m from polyline (accuracy-reliable only) → `isOffRoute`, no progress mutation | `NavEngine.kt:117-128` | sound |
| Arrival latch | session-latched arrival (≤35 m / ≤25 m remaining) that suppresses off-route recalculation loops afterwards | `NavEngine.kt:95-114` | sound |
| Reroute throttling | consecutive-off-route counter (≥2) + cooldown + single-flight `_isRerouting` | `RideViewModel.kt:528-548` | sound |
| Stale route response | `routeRequestId` guard rejects superseded OSRM responses | `RideViewModel.kt` (reroute + fetch) | sound |
| Fallback routes | fallback routes are rejected, not adopted (`result.isFallback` checks) | `RideViewModel.kt:758` | sound |
| Amenity offline | Room cache-first reads + versioned query keys + in-flight dedup + Overpass resilience/retry classification | `data/amenities/*`, main report §7b | sound (validated on device) |
| Place search offline | recent-place fallback when the query is shorter than the minimum length | `data/place/PlaceSearchEngine.kt:103-120` | sound |

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence | Notes |
|---|---|---|---|---|
| F12-D1 | **P1** | **Route geometry is not persisted.** `updateRoute` writes only `initialPlannedDistanceMeters` / `totalDistanceMeters` / `totalDurationSeconds` (plus steps in memory); `routePoints` never reach Firestore ⇒ after a process kill the restored ride has no route: banner shows "Follow Route / Heading toward destination", Remaining 0 m, no route line, until the adaptive off-route reroute rebuilds one. | main repo `data/firebase/RideRepository.kt:1067-1085` (read-only); device captures `av2`–`av11`, `1a_state.png` | **Device-confirmed**, not hypothetical |
| F12-D2 | **P1** | **The map layer recomposes per frame**, and the temporary D7 diagnostic block (string building + Coil cache probes per visible tile + two `Log.d` calls) executes **in the composition phase** on every camera-driven recomposition. | worktree `ui/components/RideMapCanvas.kt:514-527` (worktree report §4/§5 locates the block) | Measured on device (old build): **186 RT-D7 log lines** during a 20 s interaction; 1114 frames, 9.78 % janky, p95 65 ms (`/tmp/opencode/gfxinfo_before.txt`) |
| F12-D3 | P2 | **The offline test suite is not hermetic**: `ExampleRobolectricTest."test ride repository flow"` initialises **live Firebase** inside a Robolectric `runBlocking` ⇒ with the network unavailable the coroutine never resumes and the whole `:app:testDebugUnitTest` task hangs (observed >30 min at 2.8 % CPU; thread dump shows `FutureTask.get` → `Robolectric Sandbox.runOnMainThread`). | `app/src/test/java/com/example/ExampleRobolectricTest.kt:141-152`; thread dump of the Gradle test worker | Pre-existing; **not** caused by any feature work |
| F12-D4 | P2 | **Per-frame allocations in the draw path**: the route block allocates a `List<Offset>` of every route point plus a `Path` per frame; the rider block allocates an arrow `Path` per rider per frame and re-measures each rider's name text per frame. | worktree `RideMapCanvas.kt:653-658` (route) and the rider block | |
| F12-D5 | P2 | **The D7 unpainted-band defect remains open** with its diagnostics deliberately retained; the band is intermittent and unreproduced in two clean stress passes. | worktree report §5 (probe record 2026-10-19) | Must stay open; **must not** be closed or "fixed" without a failing coordinate |
| F12-D6 | P3 | No explicit "network lost" banner in the live screen; the user sees stale data without being told (amenity cache and recents silently serve). | source review | |
| F12-D7 | P3 | The rider name-pill and marker shadow allocations scale with member count; no cap on member count drawn. | rider block | |

---

## 5. Proposed original implementation (not implemented)

- **S12-1 (P1) — Persist or refetch route geometry.** Either write `routePoints` (bounded, e.g. ≤ 2,000 points) + a geometry version to Firestore, or make the restore path refetch the route deterministically from the stored plan. Closes F12-D1 and Feature-07's F7-D2. *Invariant note:* route-plan data must stay separated from rider telemetry (invariant #5).
- **S12-2 (P1) — Remove the composition-phase diagnostics** (after D7 is resolved) and move the tile-grid computation out of composition into a derived state. Closes F12-D2 / backlog P2 of the worktree report. **Not before D7 closes** — the diagnostics are the standing reproduction instrument.
- **S12-3 (P2) — Make the offline suite hermetic.** Replace the live-Firebase test with a fake repository / emulator-independent abstraction; or mark it as an integration test excluded from the offline task. Closes F12-D3.
- **S12-4 (P2) — Cache route geometry and rider label layouts** so a frame allocates no per-point/per-rider objects. Closes F12-D4.
- **S12-5 (P2) — "Offline / stale data" disclosure** in the live screen when the last successful network call is older than a threshold (amenities, route).
- **S12-6 (P3) — Frame-time instrumentation** (opt-in, debug-only) so jank regressions are measurable in CI-like conditions.

---

## 6. Tests to add / keep green

- NEW `RouteRestorationTest` — after a simulated process restart the ride restores with a usable route and the banner shows a real maneuver (closes F12-D1).
- NEW `OfflineSuiteHermeticityTest` — a guard that fails if any unit test performs a network call (e.g. a JVM-level socket-block in the test task).
- NEW `DrawPathAllocationTest` — pure check that the route geometry cache returns the same vertex set for the same (route, tier) and that no per-frame list is rebuilt.
- Keep green: the whole offline suite (with F12-D3's class excluded or fixed), `MapTileRenderingAndRotationTest`, `Task15ArrivalLatchTileFallbackAndSummaryTest`, `NavigationTelemetryRegressionTest`, `OverpassResilienceTest`, `AmenityRepositoryCacheTest`.

**Invariants affected:** #5 (Firestore separation of route plan vs telemetry) and #6 (navigation correctness must not be traded for cosmetic/perf work). The D7 instrumented logging must remain until D7 is closed with evidence.
