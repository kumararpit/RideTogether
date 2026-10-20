# Feature 08 — Rider Position, Orientation & Marker Animation

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324 (corpus; native symbols).
**No application source, test, Gradle, schema, or build artifact was modified.**

---

## 1. Summary

RideTogether's marker pipeline is **well engineered and already at reference-grade on the safety-critical parts**: exponential-damping smoothing with a frame-rate-independent time base, a *qualified* bearing (never fabricated), and a **bounded, render-only** dead-reckoning predictor. The worktree and the main repo carry **identical** DR constants (verified by diff), so the protected contract holds in both.

The gaps are in **presentation and disclosure**, not in the safety model: no accuracy ring, no "last fix age" cue, no rider→rider distance, and a marker layer that still allocates per frame.

---

## 2. Google Maps APK evidence

| ID | Claim | Class | Evidence | Notes |
|---|---|---|---|---|
| F8-A1 | The reference ships a **car sprite** for the active-navigation vehicle, not a generic disc | Runtime-observed | `/tmp/opencode/aud_gm_10_nav.png` — 3D blue car sprite with white border, heading-up, at the follow anchor; distinct from the flat blue "location dot" used outside navigation (`aud_gm_7_routeprev.png`) | Proprietary artwork — **must not be copied** |
| F8-A2 | The reference differentiates the *current position* marker from *other* markers: the standalone map shows a blue dot + halo ring; nav mode shows the vehicle sprite | Runtime-observed | `aud_gm_7_routeprev.png` (dot + ring above the route stroke) vs `aud_gm_10_nav.png` (sprite) | |
| F8-A3 | The reference renders a **heading-up** map in nav mode (map rotates with the vehicle) | Runtime-observed | `aud_gm_10_nav.png` — street labels and the route are rotated relative to the screen | Rotation of *labels* is native ⇒ **unavailable** as an implementation |
| F8-A4 | Rider/vehicle motion, pose selection (3D model, heading) and "GPS lost" chevron states are native | APK-confirmed (native boundary) | `res/drawable-nodpi/bearingless_navigation_chevron.png` (+ `_night`, `_map_colors2`, `_min_mode`, `_gpslost` variants, all 256×256); `jni_symbols.txt` groups containing `render`/`camera` | Asset **content** is proprietary — not reproduced |
| F8-A5 | The reference exposes an explicit "GPS lost / bearingless" presentation state | APK-confirmed (asset + resource) | `bearingless_gpslost_navigation_chevron.png` present in `res/drawable-nodpi` | RT has no equivalent state ⇒ gap F8-D4 |
| F8-A6 | Marker movement/pose interpolation is native | Proprietary/unavailable | `NativeLaneAwarePolylineInterpolator`, `RoadStrokePointSpriteShaderState` (symbol inventory) | |

---

## 3. RideTogether (worktree) — verified implementation

| Element | Implementation | Location |
|---|---|---|
| Position smoothing | 1st-order exponential damping, **frame-rate independent** (`alpha = 1 − e^(−dt/τ)`) | `data/map/RiderMarkerAnimator.kt:14-42` (`POSITION_TAU_SECONDS = 0.15f`, `BEARING_TAU_SECONDS = 0.10f`) |
| Bearing smoothing | shortest-arc step, wrapped to 0..360 | `RiderMarkerAnimator.kt:71-78` |
| **Dead reckoning (bounded, render-only)** | `DEAD_RECKONING_MAX_DURATION_SEC = 3.0` (L175), `DEAD_RECKONING_MIN_SPEED_KMH = 5.4` (L178), `DEAD_RECKONING_MAX_ACCURACY_METERS = 20.0f` (L181), `DEAD_RECKONING_TELEPORT_METERS = 50.0` (L188); prediction **holds** after expiry (`ageSec.coerceAtMost(3.0)`, L312); prediction cancelled by a >50 m jump (L232) | `RiderMarkerAnimator.kt` |
| DR isolation | the animator is consumed **only** by the map overlay; camera, NavEngine and telemetry read measured fixes | `ui/components/RideMapCanvas.kt:152-176` ("Consumed by the rideMapCanvas overlay only — never by camera logic or navigation") |
| Qualified bearing | derived course only when displacement ≥ 3 m and interval ≥ 500 ms; otherwise `null` (never fabricated) | `data/location/CourseOverGround.kt:21-24, 54-66` |
| Speed gate on heading | heading updates only at ≥ 5.4 km/h, otherwise the previous heading is preserved | `data/map/MapCameraController.kt` `processLocation` (worktree) |
| Geographic discontinuity | antimeridian-safe longitude wrap; >150 m teleport snap | `RiderMarkerAnimator.kt:338-351` |
| Accuracy gate on targets | fixes with `accuracyMeters > 30` are ignored for the target | `RideMapCanvas.kt:162-176` |
| Marker rendering | coloured disc (role/status palette), white casing (dashed when offline), directional arrow rotated by `bearing − heading`, "You" name pill, leader crown, emergency radar pulse | `RideMapCanvas.kt` rider block (worktree) |

**Worktree ≡ main for this feature** (DR constants diff-verified identical).

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F8-D1 | P2 | **No accuracy / last-fix-age disclosure** on the rider marker. A 30 m-accuracy fix and a 3 m fix look identical; a stale fix is only visible as a redraw of the same disc. The reference at least distinguishes the *current position* dot from the nav sprite and exposes a "GPS lost" state. | `RideMapCanvas.kt` rider draw block — no accuracy ring or age label |
| F8-D2 | P2 | **No rider→rider distance or bearing** between group members, so a rider's position relative to the leader is not readable from the map (relevant to group riding — Feature 09) | same block; `RiderMember` carries no relative-distance field |
| F8-D3 | P2 | Marker layer allocates per rider per frame (arrow `Path` + shadow circles) and the rider "name pill" is re-measured with `textMeasurer.measure` **per rider per frame** | `RideMapCanvas.kt` rider block (`textMeasurer.measure(firstName, tagStyle)`) |
| F8-D4 | P2 | **No "GPS lost / bearingless" state**: when the bearing is unavailable the arrow silently points up (`arrowSourceBearingDeg = … ?: 0f`), which reads as "heading north" rather than "direction unknown" | `RideMapCanvas.kt` arrow source selection |
| F8-D5 | P3 | Dead-reckoning prediction is **not visually distinguished** from a measured position; a rider could read a 3 s extrapolation as a real fix | animator has no "predicted" flag exposed to the renderer |
| F8-D6 | P3 | `StopDetector` hysteresis (5 / 8 km/h, 120 s) is a *status* model, not a movement model; there is no "moving but stopped at a light" distinction in the marker | `data/location/StopDetector.kt:6-8` |

---

## 5. Proposed original implementation (not implemented)

- **S8-1 (P2) — Accuracy + freshness ring on the rider marker.** An RT-original concentric ring whose opacity/thickness encodes the fix accuracy bucket, plus a stale-fix indicator (e.g. ring dashed after >15 s without a new fix). *Must not* consume the dead-reckoning prediction (invariant #3).
- **S8-2 (P2) — Bearingless state.** When no qualified bearing exists, draw a neutral "direction unknown" glyph instead of an up-arrow (mirrors the *existence* of the reference's `gpslost` state, with original artwork).
- **S8-3 (P2) — Cache the rider label layouts** so text is measured once per rider per name change, not per frame.
- **S8-4 (P3) — Expose a "predicted" flag** from the animator so the renderer can distinguish an extrapolated position (render-only; telemetry unaffected).
- **S8-5 (P3) — Relative rider information** (distance to leader) as an opt-in label on the selected rider only.

---

## 6. Tests to add / keep green

- `Task17RiderMarkerSmoothingTest` — keep green (position/bearing time constants, frame-rate independence).
- NEW `DeadReckoningContractTest` — DR never exceeds 3.0 s, requires speed ≥ 5.4 km/h and accuracy ≤ 20 m, holds after expiry, teleports at >50 m, and **never** appears in progress/telemetry/heading outputs.
- NEW `QualifiedBearingTest` — no bearing fabricated from a <3 m / <500 ms pair; speed gate; null preserved.
- NEW `MarkerAccuracyPresentationTest` — accuracy bucket → ring state mapping (pure function).
- NEW `RiderLabelCacheTest` — layout reuse keyed on name+style.

**Invariants affected:** #2 (qualified bearing), #3 (dead reckoning bounds and render-only isolation). Proposed changes are **presentation-only** and must not alter the DR constants or feed predictions into navigation, camera heading, or telemetry.
