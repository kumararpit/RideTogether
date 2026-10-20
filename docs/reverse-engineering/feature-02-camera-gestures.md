# Feature 02 — Navigation Camera & Gestures

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324.
**No application source, test, Gradle, schema, or build artifact was modified.**

---

## 1. Summary

RideTogether's camera model is **2D** (center / zoom / heading) and deliberately simple; the reference is a full 3D camera (tilt, vertical FOV, look-ahead, target altitude). That difference is **not** a defect to close — the mission forbids trading navigation correctness for visual similarity, and RT's protected invariants (D5 pivot fix, D2/D3 gesture fixes) are already closed and device-verified.

What is worth adopting (RT-original, no copying):
1. **Explicit gesture arbitration with thresholds** — the reference separates pinch from rotation (10° finger-angle) and tilt from pan/zoom (45°), so a two-finger gesture resolves to *one* intent. RT currently has a single `detectTransformGestures` with a log2 threshold.
2. **An explicit camera-mode vocabulary** — the reference has 10 named nav camera modes (`FOLLOW`, `INSPECT_STEP`, `INSPECT_ROUTE`, `OVERVIEW_MODE`, `FREE_MOVEMENT`, `ARRIVAL`, …). RT has 3 (FOLLOWING / FREE_EXPLORE / OVERVIEW) — the gap is that RT's *in-ride step inspection* and *arrival* states are not distinct modes.
3. **A declared frame-rate budget and frame statistics** — the reference regulates frames through a `Choreographer.FrameCallback` with a target rate (default 30) and collects frame stats; RT has no frame budget and no frame-time instrumentation in the app.
4. **Fit-bounds with padding + look-ahead** — RT's `showOverview` uses a uniform 100 px padding and ignores the control column.

---

## 2. Reference evidence

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F2-A1 | The camera is a **value object with 8 animatable properties**: target point, zoom, tilt, bearing, look-ahead, target altitude, target-altitude strength, **vertical FOV** | APK-confirmed | `defpackage/bjvk.java:54-75` (ctor) and `:374-385` (`toString()` emits `target, zoom, tilt, bearing, lookAhead, targetAltitude, targetAltitudeStrength, verticalFov`); property enum `defpackage/bjvi.java:257-324` — `TARGET_POINT, ZOOM, TILT, BEARING, LOOK_AHEAD, TARGET_ALTITUDE, TARGET_ALTITUDE_STRENGTH, VERTICAL_FOV` |
| F2-A2 | Camera bounds: **zoom clamped to [2, 21]**, **tilt to [0°, 90°]**, bearing normalised mod 360, **default vertical FOV 30°** | APK-confirmed | `defpackage/bjvk.java:57-61,68,70`; builder `defpackage/bjpo.java:27-46` (`:34` bearing %360, `:35` tilt clamp, `:36` zoom clamp, `:38` FOV default 30) |
| F2-A3 | Rendering uses a **4×4 projection matrix**: world scale `2^(30−zoom)`, FOV via `tan(fov/2)`, bearing rotation, tilt rotation, near/far planes, frustum corners | APK-confirmed | `defpackage/blee.java:53-163` (`:79` scale, `:59-61` FOV, `:83-86` bearing, `:67-70` tilt, `:114-123` planes, `:24-42` corners); second, independent builder for the vector path `defpackage/bkzt.java:119-244` (`2^(30-zoom)` at `:145`, perspective matrix at `:203`, `Matrix.invertM` at `:227`) |
| F2-A4 | Spherical **Mercator** with WGS84 constants | APK-confirmed | `defpackage/bkzy.java:13-35` — `atan(exp(±2π·y/2³⁰)) − π/4` (`:32`), `6378137` / `6356752.31424518` (`:22-23`) |
| F2-A5 | Screen↔world projection exists in **both** directions and is used for the **zoom anchor** | APK-confirmed | `defpackage/bjuj.java:168-174` (`k()` = unproject, used for the zoom anchor), `:277-289+` (`s()` = project) |
| F2-A6 | The reference implements the **"keep the map point under the gesture centroid fixed"** math on the Java side | Decompiled-inference | `defpackage/bjuj.java:256-275` — `q()` computes the world point under a screen anchor (bearing + FOV aware); `r()` computes the world-space offset for a rotate/tilt pivot (`atan2`, `sqrt`, `cos(tilt+90°)`) |
| F2-A7 | Gesture axes are an explicit enum: **PAN, ZOOM, ROTATE, TILT** | APK-confirmed | `defpackage/bjzf.java:14-24` |
| F2-A8 | **Gesture arbitration** returns a set of simultaneously enabled axes: tilt wins when two recognisers fire; pan when not multi-touch; zoom when pinch; rotate when twist | APK-confirmed | `defpackage/bjzg.java:132-145` (`h()`) — `if (this.j.b \|\| this.k.b) → TILT; else if (!this.O) → PAN; if (this.i.b) → ZOOM; if (this.l.b && !this.O) → ROTATE` |
| F2-A9 | **Pinch vs. rotation**: a pinch is only recognised while the angle between the two fingers stays within **10°** and their separation changes by ≥10–20 % of screen density | APK-confirmed | `defpackage/bjzl.java:33-55` (`:46` `e = 0.17453292f` = 10°, `:52` separation threshold) |
| F2-A10 | **Tilt vs. pan/zoom** arbitration: Σ\|Δx\| of both pointers vs Σ\|Δy\|; horizontal ≤ vertical ⇒ vertical (tilt) gesture; 45° angular threshold, 0.125 slop | APK-confirmed | `defpackage/bjzi.java:24-69` (`:54-63`; `:17-19` constants) |
| F2-A11 | Rotation recogniser thresholds **5° / 10°** | APK-confirmed | `defpackage/bjzk.java:6-7,38-70` |
| F2-A12 | Gesture deltas are computed as: pinch scale = `hypot(new)/hypot(old)`; rotation in degrees with logistic damping by the zoom-vs-translation ratio; tilt = `0.25 × ΔmidpointY`; **centroid** of the two pointers tracked between events | APK-confirmed | `defpackage/bjzg.java:214-257` (centroid `:241-243`), `:284-309` (rotation, `:302` degrees, `:130-137` damping), `:311-355` (pinch `:334,343,352`), `:357-366` (tilt `:362`) |
| F2-A13 | The gesture layer packs a **4-component delta {panX, panY, zoom, tilt}** and zeroes the disabled axes | APK-confirmed | `defpackage/bjjt.java:320-356` (`:322-323`, `:334-353`, `:354`); feature flags `defpackage/bjzn.java:5-16` (11 on/off flags) |
| F2-A14 | The **zoom anchor** is passed to the camera with the zoom delta | APK-confirmed | `defpackage/bjjt.java:52-114` (`:64-65` anchor screen coords, `:75` `fLog = log(scale)/ln2`, `:106-108` `PINCH_OPEN`/`PINCH_CLOSED`); sink `defpackage/bjzb.java:24` `r(zoomDelta, logZoom, anchorX, anchorY, isPan)`; legacy impl `defpackage/bkml.java:119-125` unprojects the anchor to world coords (`:37-41`) |
| F2-A15 | **Fit-bounds** camera: zoom = `30 − log2(spanX / widthPx × (fov × 256))`; bounds centre in world fixed point with antimeridian wrap; look-ahead offset; 4 paddings + tilt + bearing inputs | APK-confirmed | `defpackage/bzwv.java:1369-1420` (`:1396` zoom, `:1401-1406` centre, `:1412-1418` look-ahead); `defpackage/bjhj.java:18-26` (the zoom formula) |
| F2-A16 | Fit-bounds has an **animated** variant driven by a duration + interpolator | APK-confirmed | `defpackage/bzwv.java:1427-1478` (`:1477` `bjtoVar.d(camera, duration, interpolator)`) |
| F2-A17 | The reference has **20 camera-update flavours** (bounds→zoom+centre, centre-on-LatLng-with-padding, centre+zoom+tilt+bearing, position+zoom+look-ahead, …) | Decompiled-inference (existence confirmed) | `defpackage/bjtp.java` (abstract) + `bjtr/bjtv/bjtw/bjtx/…` (concrete), e.g. `bjtr.java:25-45` |
| F2-A18 | Nav camera **mode vocabulary**: `UNKNOWN_MODE, FOLLOW, INSPECT_STEP, INSPECT_ROUTE, FRAMING_PROMPT_CONTENT, SEARCH_RESULTS, OVERRIDE_MODE, OVERVIEW_MODE, FREE_MOVEMENT, ARRIVAL`; source modes `FOLLOWING, FREE_MOVEMENT, OVERVIEW, INSPECT_STEP, INSPECT_RESULTS_ON_MAP_WITH_LOCATION, …` | APK-confirmed | `defpackage/bybt.java:5-15`; `defpackage/bmfc.java:21-45`; mapping `defpackage/bmei.java:29-53` |
| F2-A19 | Compass/camera-type vocabulary: `HEADING_UP(2), NORTH_UP(3), OVERVIEW(4)`; camera type `CAMERA_3D, CAMERA_2D_NORTH_UP, CAMERA_2D_HEADING_UP`; density "moment" `FAR_VIEW, NORMAL, APPROACH, LIMITED_CONTROLLED_ACCESS_*, DENSE_*, SPARSE_*, TUNNEL` | APK-confirmed | `defpackage/bmfx.java:3-7`; `defpackage/bybr.java:5-9`; `defpackage/bybu.java:5-17` |
| F2-A20 | The nav camera is **native**; Java only marshals state and overrides | native boundary | `com/google/android/libraries/geo/navcore/ui/camera/jni/CameraControllerJni.java:435-449,812-834` — `nativeUpdateNavigationCameraState`, `nativeUpdateNavGuidanceStateAndLocation`, `nativeGetCameraAnimatedPosition`, `nativeGetGoalCameraPosition`, `nativeGetCameraAnimationState`, `nativeUpdateChevronPrediction`, `nativeSetCameraSandboxOverrides`; Java wrapper `defpackage/bmev.java` (`SharedCameraControllerImpl`), which forces a 0.2 s animation on demand (`:497`) |
| F2-A21 | **Frame-rate regulation**: a `Choreographer.FrameCallback` sets the target rate and frame time `1000/rate`; the app default target is **30 fps**, and the effective rate is `min(target, device max)`; there is an **adaptive** frame rate with 2D/3D minimums | APK-confirmed | `defpackage/bjtf.java:13,139-146` (`:51` default 30), `defpackage/rck.java:91,171-174,641-646`; `defpackage/bkfn.java:1121-1125` (`MinAdaptiveFrameRate (2D)`, `MinAdaptiveFrameRate3D`); write-through `defpackage/blac.java:72-82` |
| F2-A22 | The reference collects **frame statistics** (total frames, frames over budget, per-bucket) and exposes an **FpsProfiler** with a 100-slot histogram | APK-confirmed | `defpackage/bjyn.java:10-60`; dump format `defpackage/bkfn.java:1180-1191` (`FrameStats-<bucket>: totalFrameCount=… totalFramesOverBudget=…`); `defpackage/bcvk.java:9-27` |
| F2-A23 | Rendering can be **paused/resumed** and a frame can be requested explicitly; `requestFrame` is unsupported in the legacy renderer | APK-confirmed | `defpackage/bkfn.java:611-637` (`setPauseMapRendering`; `:502` throws for legacy), `defpackage/rck.java:299-301` ("unpause map rendering"), `NativeMapController.nativeRequestFrame` (`:122`) |
| F2-A24 | A **frame-time debug overlay** exists as a GL shader program | APK-confirmed | `com/google/android/libraries/geo/mapcore/renderer/FrameTimeOverlay$FrameTimeOverlayShaderProgram.java:11-52` |

**Unavailable (documented):** `bjtf.doFrame(long)` body (JADX "Method not decompiled") ⇒ the exact frame-budget algorithm; `bjjt.onDoubleTapEvent` body (double-tap zoom animation); `bmev.w()/f(cgrv)/e(bmfh)/f(cfcr,bmfc)` (nav camera-state plumbing); `bkgb.b(bjpc)` body (paint-params update). The reference ships **its own fork** of the Maps SDK (`ahtu`/`bjpc` façade) — the public SDK classes (`com.google.android.gms.maps.*`) are **not** in this APK.

---

## 3. RideTogether (worktree) — verified camera & gesture implementation

| Concern | Implementation | Location | Assessment |
|---|---|---|---|
| Modes | `FOLLOWING`, `FREE_EXPLORE`, `OVERVIEW` | `data/map/MapCameraController.kt:9-13` | sound but coarse vs the reference's 10 nav modes (F2-A18) |
| Zoom range | `coerceIn(3f, 18f)` | `MapCameraController.kt:188,202` | fine for a 2D map |
| Adaptive zoom | `calculateAdaptiveZoom`: 17.5 near a maneuver (≤150 m), 16.5 ≥30 km/h, 15.5 ≥70 km/h | `MapCameraController.kt:211-220` | RT-original and appropriate |
| Heading gate | speed ≥ 5.4 km/h, null heading preserves the previous value | `MapCameraController.kt:70-75` | **protected invariant #2 — do not change** |
| Pinch in FOLLOWING | zoom only, via `setZoom`; jitter gate 24 dp | `RideMapCanvas.kt:769-808` + `MapCameraController.kt:187-191` | **protected invariant #1** (closed, device-verified) |
| Centroid-anchored zoom (free) | `ZoomAnchorMath.anchoredCenter` — the point under the centroid stays under it | `data/map/ZoomAnchorMath.kt:37-64` | **protected invariant #2** (closed) |
| Pan → follow exit | 24 dp cumulative jitter gate; `onUserGesture()` switches mode | `RideMapCanvas.kt:809-833` | sound |
| FREE_EXPLORE protection | `processLocation` returns early unless FOLLOWING | `MapCameraController.kt:63` | **protected invariant #3** |
| Recenter | one controller write; D5 fix keeps the anchor pivot stable in a single frame | `MapCameraController.kt:134-148`; `RideMapCanvas.kt:377-394` | **closed, device-verified** |
| Follow anchor | 65 % of the usable height below the top inset | `RideMapCanvas.kt:367-371` | matches the reference's observed placement (marker at ≈62 % in `aud_gm_10_nav.png`) |
| Fit-group overview | `showOverview(points, w, h, 100f)` — uniform padding, no look-ahead | `MapCameraController.kt:150-185` | simpler than F2-A15 (no padding model, no look-ahead) |
| Frame pacing | one `withFrameNanos` loop driving exp-smoothed camera + marker animation | `RideMapCanvas.kt:256-311` | no **budget**; always animates while resumed |
| Frame statistics | none in-app; `dumpsys gfxinfo` used externally | — | gap F2-D3 |
| Tilt / 3D | not implemented (2D map) | — | out of scope by design |

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F2-D1 | P2 | **No gesture arbitration with thresholds.** A single `detectTransformGestures` conflates pinch/rotate/tilt; there is no separation of a two-finger twist from a pinch, and no tilt concept. | worktree `RideMapCanvas.kt:769` vs F2-A7..A13 |
| F2-D2 | P2 | **No explicit camera-mode vocabulary for step inspection or arrival.** "INSPECT_STEP" (the reference's step-preview state) and "ARRIVAL" have no RT equivalent; RT's arrival is only a flag on the progress, and the camera keeps following. | worktree `MapCameraController.kt:9-13`; F2-A18 |
| F2-D3 | P2 | **No frame budget and no in-app frame statistics.** The map animates at display rate regardless of load; a stalled frame is invisible to the app. | worktree `RideMapCanvas.kt:256-311`; F2-A21/A22/A24 |
| F2-D4 | P2 | **Overview framing ignores the chrome** (control column, bottom card) and has no look-ahead, so a fitted route can hide under the FABs and the rider can sit at the viewport edge. | worktree `MapCameraController.kt:150-185`; F2-A15 |
| F2-D5 | P3 | Camera mode transitions are not **animated** as an explicit concept — RT re-glides continuously toward the new target; the reference animates a camera *update* with a duration + interpolator. | worktree `RideMapCanvas.kt:263-276`; F2-A16/A17 |
| F2-D6 | P3 | No **pause/resume of rendering** concept: the frame loop keeps running while the app is resumed but the screen is not visible. | worktree `RideMapCanvas.kt:260-262` (only `isResumed` is checked) |
| F2-D7 | P3 | The reference's zoom range extends to 21 and its tilt/FOV model enables 3D; RT is capped at 18 in a 2D map. | worktree `MapCameraController.kt:188` |

---

## 5. Proposed original implementation (not implemented)

- **S2-1 (P2) — Add gesture intent separation (no tilt).** Classify a two-finger gesture as *pinch* or *twist* using the reference's *shape* (finger-pair angle vs. separation change) but with **RT-original thresholds and code**; keep single-finger pan. Do **not** introduce tilt.
*Invariant risk:* touches gesture arbitration (invariant #1/#2) ⇒ **requires explicit approval and dedicated tests**.
- **S2-2 (P2) — Extend the camera-mode vocabulary** with `INSPECT_STEP` and `ARRIVAL` as distinct modes (the reference's semantics are observable; the names are RT-original), so the camera can hold a specific framing at a maneuver and at arrival.
- **S2-3 (P2) — Frame budget + statistics.** Cap the map's animation loop to a target rate (e.g. 60 fps when the device supports it, 30 otherwise) and record frame-time statistics behind a debug flag; surface jank counters in the developer overlay.
- **S2-4 (P2) — Chrome-aware overview framing.** Extend `showOverview` to take per-side padding (top inset, bottom card, control column) and a look-ahead factor.
- **S2-5 (P3) — Render pause/resume** when the window is not visible.
- **S2-6 (P3) — Explicit camera transitions** (duration + interpolator) for recenter/overview instead of an unbounded glide.

---

## 6. Tests to add / keep green

- Keep green (protected): `MapCameraControllerTest` (548 lines), `MapTileRenderingAndRotationTest` (230), `Task17RiderMarkerSmoothingTest`, `NavigationTelemetryRegressionTest`.
- NEW `GestureIntentTest` — pure classification: a two-finger spread with a stable angle ⇒ PINCH; a twist with constant separation ⇒ TWIST; one finger ⇒ PAN; a micro-move below the slop ⇒ NONE.
- NEW `CameraModeTest` — mode transitions preserve the protected invariants: a pinch inside FOLLOWING never exits follow; a pan beyond the jitter gate exits follow exactly once; FREE_EXPLORE is never overwritten by the GPS feed.
- NEW `OverviewFramingTest` — fitted bounds respect the per-side padding (route not under the control column) and the look-ahead factor.
- NEW `FrameBudgetTest` — the animation loop honours the configured target rate and records frame statistics.

**Invariants affected:** #1 (camera/gesture behaviour) — S2-1 and S2-2 are the riskiest and need explicit approval; S2-3/S2-4 are additive. Qualified bearing, dead reckoning, multi-waypoint ordering, and Firestore must remain untouched.
