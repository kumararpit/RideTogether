# Feature 04 — Route Line, Geometry & Waypoint Rendering

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324.
**No application source, test, Gradle, schema, or build artifact was modified by this audit.** (A renderer rework exists as *uncommitted* work in the main repository — it is described here as the current state of that branch and is **not** part of the worktree.)

---

## 1. Summary

The reference renders a route as a **multi-band stroke** whose total width scales with context, with **alternatives** behind it and **anchored information pills**. RideTogether's worktree renders a **fixed 4-band stroke of constant width** with **per-frame geometry allocation** and no alternatives.

A three-band, zoom-modulated, cached-geometry rework has already been implemented and validated (in the main repo, uncommitted) — it is summarised in §5 as the model implementation for this specification.

---

## 2. Reference evidence

| ID | Claim | Class | Evidence | Limitations |
|---|---|---|---|---|
| F4-A1 | The primary route is a **three-band stroke**: dark casing → saturated core → light inner streak, total ≈ **24 dp at overview zoom** | Runtime-observed | `/tmp/opencode/aud_gm_7_routeprev.png`; pixel cross-sections (PIL 12.3, 1080×2400 @3×): casing `#27059b`/`#23038e` (~8–9 px), core `#0420d2`/`#450efb`, inner streak `#dbd1fe`–`#c1affe`; total ≈72 px ≈24 dp | Colours/values are Google's artwork — **not** to be copied |
| F4-A2 | In **nav mode** the route is **narrower** (≈**17 dp** core `#4710fb` + `#0e0158` casing ≈8 px) ⇒ width is context-dependent, not constant | Runtime-observed | `/tmp/opencode/aud_gm_10_nav.png`; col-530 cross-section: `0d0151 0d0155 4610f8 4710fb … 4710fa 4610fa 3f0ee4 27089f 110261 0e0158…` | |
| F4-A3 | **Alternative routes** are drawn behind the primary at ~⅓ weight in light periwinkle (`#c3d9fe`) | Runtime-observed | `aud_gm_7_routeprev.png`; row-1000 cross-section: `c6daff c9ddff … c3d6fe` | |
| F4-A4 | **Information pills are anchored to the line** with a leader tail: a dark-blue pill on the primary route ("56 min ₹240.00"), white pills on the alternatives ("1 hr 6 min ₹285.00", "1 hr 9 min ₹240.00") | Runtime-observed | `aud_gm_7_routeprev.png` | |
| F4-A5 | The **destination marker** is a red pin (`#b83235`/`#bb4349`) with a light halo, and the **location dot** (blue + ring) is drawn **above** the route stroke | Runtime-observed | `aud_gm_7_routeprev.png`, `aud_gm_10_nav.png` | |
| F4-A6 | The route is drawn **over the road network** but **under** map labels and the location marker | Runtime-observed | same captures | |
| F4-A7 | Route geometry is **re-projected every frame** by native code, keyed to camera-follow state, safe-area insets and a multi-surface model, with a **self-healing retry** when a projection fails | APK-confirmed | `sources/com/google/android/apps/gmm/kits/directions/map/renderer/viewportintegrity/PolylineViewportIntegrityBridge.java` (517 lines, read in full): `nativeOnPostFrameRenderCompleted(handle, surface, cghp)` → per-frame `double[]` + surface summaries; `nativeUpdateCameraFollowMode`; safe-area rect; `nativeOnClientPolylineConstructionOrProjectionFailed(handle, surface, 3)` + re-commit | JNI surface; the projection maths is **unavailable** |
| F4-A8 | Polyline rendering is native (point-sprite/line shader programs) | Proprietary/unavailable | `jni_symbols.txt` (21 `polyline` symbols, 97 `render`); `RoadStrokePointSpriteShaderState`, `ClientLineLiteShaderState` | |
| F4-A9 | Lane-aware polyline interpolation exists (lane guidance geometry) | APK-confirmed (native boundary) | `jni_symbols.txt`: `Java_com_google_android_apps_gmm_directions_laneaware_NativeLaneAwarePolylineInterpolator_nativeInitClass` | Not implementable without a lane model; RT has no lane data source |
| F4-A10 | Alternative-route selection and route-request semantics are service-side | Proprietary/unavailable | no independent multi-route service is available to RT | OSRM returns **one** route per request; alternatives would need a second request |

### Additional renderer-handoff evidence (corpus mining)

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F4-A11 | Route segments are grouped **by style** and emitted **one polyline per style group** — the reference's route line is a *set* of styled segments, not one path | Decompiled-inference (high) | `defpackage/ahbm.java:334` calls `agdd.W(bjqaVar2, new ctyz(...), z2, bkzbVarD, chmfVar, z3)`; segments grouped via `ahbl`; polyline factory contract `defpackage/bjqa.java:7-21` (`b/c/e/d/f/g` → protobuf `cmqz`) |
| F4-A12 | The renderer receives **viewport-clipped** geometry, not the whole polyline | APK-confirmed | `PolylineViewportIntegrityBridge.e(cghp, double[], double, double[], double[])` → `ahej` containing `aheh` = `"ClippedPolylineGeometry(visibleSegmentCount=…, is3dPolyline=…, visibleSegmentsXy=…)"` (`defpackage/aheh.java:35`) and `ahei` = `"SafeAreaBounds(minX,maxX,minY,maxY)"` |
| F4-A13 | The renderer is fed **cumulative distances** and a **chevron remaining-distance** channel ⇒ animated direction chevrons are driven by *remaining* distance, not by vertex index | APK-confirmed (Kotlin `@Metadata`) | `PolylineViewportIntegrityBridge` metadata (line 41) names the geometry inputs verbatim: `projectedScreenCoordsXy`, `chevronRemainingDistanceMeters`, `cumulativeDistancesMeters`, `vertexAltitudesMeters` |
| F4-A14 | The reference models **per-leg progress** as a step index + a fraction travelled within the leg (1.0 on the final leg) | Decompiled-inference (high) | `defpackage/bmvq.java:137,217-250` — `chgw.b` = step index, `chgw.c` = leg fraction; `iMax = Math.max(0, bjhxVar.g() - 1)` |
| F4-A15 | "Alternatives kept, one selected" wire format: `chfj` = `c` (selected route id) + `d` (repeated alternative route ids); the translator emits the primary with `z=true` and each alternative with `z=false` | APK-confirmed (proto shape) | `defpackage/chfj.java:7-9`; `defpackage/bmvq.java:295-361` |

---

## 3. RideTogether (worktree) — verified route rendering

`ui/components/RideMapCanvas.kt:653-700` (worktree):
- `route.map { latLngToUnrotatedScreen(it) }` — a **new list of every point per frame**, then a `Path` built with `lineTo` per point (no simplification, no viewport decimation).
- Four stacked strokes, **constant width** at every zoom: shadow 18 dp `#AA0B0F19` → casing 12 dp `#E65100` → core 7.5 dp `AmberPrimary` → centre guide 2.5 dp `#FFF9C4` ⇒ **~32.5 dp painted width**.
- Direction dots every ≈N vertices (vertex-count cadence, not screen distance) with white + `#E65100` discs.
- Start marker: 3-circle green stack; destination: 3-circle red stack (no pin glyph, no anchoring to the line).
- No alternatives, no anchored pills, no route-progress emphasis, no reroute cross-fade.

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F4-D1 | P1 | **Per-frame geometry allocation** (full point list + `Path`) — cost grows with route length; a 136 km / 2136-point route is re-projected every frame | worktree `RideMapCanvas.kt:653-658`; live OSRM geometry size measured (`/tmp/opencode/osrm_mw.json`: 2136 points for the 3-leg route) |
| F4-D2 | P1 | **No simplification** ⇒ every vertex is drawn and every vertex is transformed per frame | same |
| F4-D3 | P2 | **Route width is constant** (~32.5 dp) at every zoom, ~1.35× the measured reference relationship (24 dp overview / 17 dp nav) and **not** context-modulated | F4-A1/F4-A2 vs worktree constants |
| F4-D4 | P2 | **Direction-dot cadence is vertex-based**, so dots bunch up or vanish with zoom | worktree route block (`routePoints.size / 10`) |
| F4-D5 | P2 | **No alternative routes** and **no anchored route information pills** (ETA/distance at the destination) | worktree; F4-A3/F4-A4 |
| F4-D6 | P2 | **No route-progress emphasis** (travelled vs remaining are visually identical) | worktree |
| F4-D7 | P3 | Destination/start markers are abstract circle stacks, not anchored destination pins | worktree; F4-A5 |
| F4-D8 | P3 | No reroute transition continuity: the new route simply replaces the old path (no cross-fade, no "old route ghost") | worktree |

---

## 5. Model implementation already validated (main repo, uncommitted — **not** in the worktree)

| Aspect | Implementation | Validation |
|---|---|---|
| Cached geometry | route projected **once** into Web-Mercator tile-17 world space; per-frame work is one affine transform + 3 stroke passes | `ui/map/RouteRenderMath.kt`, `ui/map/RouteGeometryCache.kt` (main repo) |
| Simplification | Douglas–Peucker per integer zoom tier, 1.2 px screen tolerance | validated against the **live** 2136-point 3-leg route: z11 → 213 pts (10 %), z14 → 619 (29 %), z17 → 1389 (65 %) |
| **Leg-boundary pinning** | the polyline is split at every leg-boundary vertex, so **no retained segment can span a waypoint** | measured on live data: **0 spanning segments** at every tier; max deviation from the original road geometry **1.13–1.20 px** (within tolerance) |
| Original three-band styling | soft shadow → brand-slate casing `#0C1017` → amber core `#FF9800` → pale-amber streak `#FFE0A3`; core width 18 → 17 dp by zoom | RT-original palette (no reference artwork) |
| Screen-distance dot cadence | `directionDotSpacingPx` clamped to 56–320 px | new unit tests |
| Tests | `RouteRenderMathTest` (10), `RouteGeometryCacheTest` (8) | added in the main repo; **must be ported to the worktree** |

---

## 6. Proposed original implementation for the worktree

- **S4-1 (P1)** — Port the cached world-space geometry + tier simplification **with leg-boundary pinning** (Feature 07's R7-1 invariant) into the worktree.
- **S4-2 (P2)** — Adopt the three-band, zoom-modulated RT-original palette (S4-1's band set) instead of the fixed 4-band stroke.
- **S4-3 (P2)** — Screen-distance dot cadence.
- **S4-4 (P2)** — Original destination pin geometry (casing ring + interior glyph) with the line anchored to it; optional ETA pill at the destination in overview zoom.
- **S4-5 (P3)** — Alternative routes: OSRM offers one route per request; an alternatives feature would need a second request + a comparison model ⇒ **defer** (data/cost dependent, P3 by the mission's framework).
- **S4-6 (P3)** — Reroute ghost/cross-fade (RT-original, cosmetic).

---

## 7. Tests to add / keep green

- Port `RouteRenderMathTest` + `RouteGeometryCacheTest` from the main repo.
- NEW `RouteLegBoundaryTest` — a simplified polyline never spans a leg boundary (synthetic 3-leg route with a sharp corner at each boundary) — this is the invariant that protects Feature 07.
- NEW `RouteGeometryTransformTest` — the cached transform must equal the canvas projection `x = anchorX + (tileX(point) − tileX(center)) · tileSizePx` (coordinate-space compatibility with markers and hit-testing).
- NEW `RouteBandWidthTest` — width band monotonic and clamped across zooms.
- Keep green: `MapTileRenderingAndRotationTest`, `MultiWaypointOsrmParsingTest` (main repo), `NavEngineMultiLegTest` (main repo).

**Invariants affected:** #4 (ordered waypoints, continuous geometry, leg boundaries) and #6 (route progress correctness must not be traded for appearance). All proposed changes are **display-only**; route calculation semantics stay untouched.
