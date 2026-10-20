# Feature 11 — Navigation Overlays, Controls & Information Hierarchy

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324.
**No application source, test, Gradle, schema, or build artifact was modified.**

---

## 1. Summary

The reference's overlay system has three properties RideTogether lacks entirely:

1. **A declared overlay z-order** (25 named layers) rather than an implicit Compose draw order.
2. **UI-chrome → renderer coupling**: the banner's height, the bottom-sheet exposure and an explicit "nav UI is occluding the map" flag are pushed into the native renderer each frame (`turnCardBodyHeightPx`, `bottomSheetExposurePx`, `isNavigationOverlayOccludingMap`), so the route line and camera respond to the chrome.
3. **A systematic 48 dp touch-target convention**, with enlarged `TouchDelegate` hit rects (76–96 dp) for small icons and *invisible* touch-target spacer views for the zoom buttons.

RideTogether's overlay stack is Compose-native and already usable; the gap is **discipline and semantics** (explicit occlusion contract, a11y labels, target sizing), not architecture.

---

## 2. Reference evidence

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F11-A1 | The top banner is a **container with three optional children** — `current_step_container` (2131427989), `status_container` (2131430113), `trip_pager_container` (2131430610) | APK-confirmed | `com/google/android/libraries/geo/navcore/turncard/views/TurnCardContainerLayout.java:40-62` |
| F11-A2 | **Banner state precedence is explicit**: if `status_container` is visible its height wins, else `trip_pager_container.getApparentHeight()`, else `current_step_container.getApparentHeight()`, else 0; the result is published via `bmczVar.y(apparentHeight)` | Decompiled-inference (high) | `TurnCardContainerLayout.java:64-84` |
| F11-A3 | The banner's **apparent height is fed to the map renderer** (polyline occlusion) | Decompiled-inference (high) | `TurnCardContainerLayout.onLayout` → `bmczVar.y(...)`; and the renderer's viewport updater takes `turnCardBodyHeightPx` / `bottomSheetExposurePx` / `isBottomSheetExpanded` / `isNavigationOverlayOccludingMap` (Kotlin `@Metadata` of `PolylineViewportIntegrityBridge`, line 41) |
| F11-A4 | The renderer keeps a **declared overlay z-order** with 25 named layers | APK-confirmed | `defpackage/blga.java:36-88,95-108` — `OVERLAY_LABELS(0)`, `NAVIGATION_ADS(3)`, `TRAFFIC_INCIDENTS(5)`, `SEARCH_RESULT_MEASLES(6)`, `SEARCH_RESULT_ICONS(7)`, `CALLOUT_LABEL(13)`, `BLUE_DOT_CALLOUT(16)`, `VISUAL_EXPLORE_CLUSTER(17)`, **`FRIEND_ACCURACY_CIRCLE(18)`, `FRIEND(19)`, `FRIEND_CLUSTER(20)`**, `PLACEMARK_LABELS(22)`, `PLACEMARK(23)`, `CALLOUT_LABEL_OVER_PLACEMARKS(24)` |
| F11-A5 | The map renderer applies a **triple z-order (style lock)** when a style is applied | Decompiled-inference | `defpackage/bkaf.java:131-198` (trace `"setZOrderTriple-styleLock"`, `:143`) |
| F11-A6 | UI occlusion is modelled as a **safe-area rect** per surface, plus a surface-type enum (phone/AA/CarPlay/embedded) and a surface connection state | APK-confirmed | `PolylineViewportIntegrityBridge.l(cghp,double,double,double,double)` → `ahei` = `"SafeAreaBounds(minX,maxY…)"` (`defpackage/ahei.java:37`); `defpackage/cghp.java:5-15` surface enum; `nativeUpdateSurfaceConnectionState` / `nativeUpdateSurfaceStepListState` / `nativeUpdateNavUiHoldState` |
| F11-A7 | The native side reports **why a frame is not drawn** | APK-confirmed | `defpackage/ahej.java:39-91` — `CACHING_OK`, `INVALID_SAFE_AREA`, `UNKNOWN_SURFACE`, `PAUSED_BACKGROUND`, `WARMUP_AFTER_RESUME`, `HOLD_STEP_LIST`, `SURFACE_DISCONNECTED`, `HOLD_NAV_UI`, `NOT_FOLLOWING`, `DISABLED`, `CLOSED`, `NULL_NATIVE_SNAPSHOT` |
| F11-A8 | A **MapButtonsController** hosts zoom, compass, mute, report-incident, speed-limit and one-bar controls | Decompiled-inference (high) | `defpackage/rfm.java:63-137` (trace `"MapButtonsController"`, `:75`) with ids `zoom_widget_view` (2131430794), `map_buttons_view_compass_container` (2131428837), `map_buttons_view_mute_container` (2131428843), `map_buttons_view_report_incident_button` (2131428844), `map_buttons_view_speed_limit` (2131428846) |
| F11-A9 | Touch targets follow a **48 dp floor**; small icon buttons get **enlarged `TouchDelegate` hit rects** (96 dp / 76–96 dp) and *invisible* touch-target spacer views | Decompiled-inference (high) | `defpackage/rfm.java:170` `setTouchDelegate(new TouchDelegate(new Rect(0,0, uxv.bN.pB(ctx), uxv.bO.pB(ctx)), view2))`; `defpackage/uxv.java:364-365` (`bN = 96dp`, `bO = 76–96dp`); `defpackage/rhb.java:10-27` (zoom in/out + `map_buttons_view_zoom_in_touch_target` 2131428848 / `..._zoom_out_touch_target` 2131428850, padded 7 dp) |
| F11-A10 | Resource convention: `directions_min_touch_target_size`, `google_min_touch_target_size`, `min_touch_size`, `mtrl_fab_min_touch_target`, `mtrl_min_touch_target_size` = **48.0 dp**; `button_size` / `compass_button_size` = **40.0 dp** | APK-confirmed | `res/values/dimens.xml:200,470,1099,119,1197,109` |
| F11-A11 | The compass exposes **accessibility** state: plural bearing description + `FOLLOW` / `NORTH_UP` action labels | APK-confirmed | `com/google/android/libraries/geo/mapcore/internal/ui/CompassButtonView.java:279-285,300-306` — `setContentDescription(resources.getQuantityString(2131886150 /*COMPASS_ACCESSIBILITY_BEARING*/, i, i))`; `AccessibilityNodeInfo.setClassName(Button.class)`; action labels 2132019220 / 2132019221 |
| F11-A12 | The mute control is a **3-state audio-level selector** with dedicated a11y strings | APK-confirmed | `res/values/strings.xml:4330-4343` — `MUTE_BUTTON_CONTENT_DESCRIPTION`="Mute", `MUTE_BUTTON_MINIMAL_SELECTED_CONTENT_DESCRIPTION`="Audio level selector: only speak alerts", `MUTE_BUTTON_MUTE_SELECTED_CONTENT_DESCRIPTION`="Audio level selector: muted", `MUTE_BUTTON_UNMUTED_SELECTED_CONTENT_DESCRIPTION`="Audio level selector: unmuted" |
| F11-A13 | Alternative routes are **announced** to screen readers ("1 of %s routes available") | APK-confirmed | `defpackage/wkt.java:12-24` (string 2132022816) |
| F11-A14 | The reference exposes a **voice-command surface** including turn queries | APK-confirmed | `defpackage/qvv.java:10` — `mute`, `unmute`, `show_traffic`, `hide_traffic`, `show_alternates`, `route_overview`, `exit_navigation`, **`query_next_turn`, `distance_to_next_turn`, `time_to_next_turn`, `distance_to_destination`, `time_to_destination`, `eta`**, `my_location`, `query_current_road`, `query_destination` |
| F11-A15 | The bottom navigation container is a `nav_container` (2131429022) owned by `MainLayout` | Decompiled-inference | `com/google/android/apps/gmm/base/layout/MainLayout.java:387,2871` |
| F11-A16 | Route/ETA panels are modelled as display objects with explicit travelled/remaining and delay fields | APK-confirmed | `defpackage/xrl.java:52-53` `"Progress(travelMode=…, etaText=…, durationText=…, distanceText=…, destination=…, progressTraveled=…, distanceToDestination=…, trafficData=…)"`; `defpackage/sim.java:39-40` `"EtaInfo(eta=…, etaWithoutTimezone=…, travelTime=…, travelTimeShort=…, travelTimeDelayCategory=…, isOffline=…)"`; arrival `"Arriving(durationText=…, distanceText=…, destination=…, sideOfRoad=…, …)"` (`xrk.java:51`, `aakf.java:51`) |

---

## 3. RideTogether (worktree) — current overlay stack

| Concern | Implementation | Location | Assessment |
|---|---|---|---|
| Top maneuver banner | `TurnByTurnNavHeader` — 52 dp icon tile, 26 sp Black distance, 14 sp instruction, 11 sp "Then:" | `ui/components/NavigationHud.kt:88-279` | solid; but **no distance/unit tier split** (Feature 01) |
| Bottom trip HUD | `NavigationTripHud` — 3-column ETA/Remaining/Traveled grid, progress bar, route sub-row, Tracking/Free-Pan pill | `NavigationHud.kt:284-506` | solid |
| Map controls | Compose FAB column: compass toggle, recenter, fit-group, zoom in/out, style toggle, scale bar | `RideMapCanvas.kt` control column | **48 dp FABs** ✓; compass/labels have content descriptions ✓ |
| Overlay ordering | implicit Compose z-order (tiles → route → overlay canvas → FABs) | `RideMapCanvas.kt` | no declared contract |
| Chrome ↔ renderer coupling | **none** — the canvas knows `topInsetDp`/`bottomInsetDp` but does not publish its own chrome height | `RideMapCanvas.kt:112-113` | gap F11-D2 |
| Touch targets | FABs 48 dp ✓; rider tap radius was a **raw 70 px** constant (≈23 dp at 3× — shrinks on denser screens) | `RideMapCanvas.kt` hit-test | gap F11-D3 |
| Accessibility | content descriptions on the compass/recenter/fit/zoom/style buttons and on the amenity tiles | `RideMapCanvas.kt` | partial: **no** description for the maneuver banner, the distance, or the rider markers |
| Audio control | single binary mute toggle (`Icons.AutoMirrored.Filled.VolumeUp/Off`) | `NavigationHud.kt:240-253` | no 3-state audio level |
| Voice commands | **none** | — | gap F11-D4 (P3) |

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F11-D1 | P1 | **No declared overlay contract.** Overlays (banner, trip HUD, sheets, FABs) are ordered by Compose structure only; a new overlay (e.g. an alternative-route sheet) has no defined z-position, and the map cannot be told what occludes it. | worktree source review; reference F11-A4/A5 |
| F11-D2 | P1 | **No chrome → renderer coupling.** The reference publishes the banner height / sheet exposure / occlusion flag so the route line and camera can respond; RT's canvas receives only static insets, so a tall banner can cover the next maneuver. | worktree `RideMapCanvas.kt:112-113`; reference F11-A2/A3/A6 |
| F11-D3 | P1 | **Rider tap target is density-dependent** (raw 70 px) | worktree hit-test; reference F11-A9/A10 (48 dp floor) |
| F11-D4 | P2 | **Accessibility gaps**: the maneuver banner, its distance and the rider markers have no content descriptions; the audio control has no state description; alternative routes (if ever added) would need an announcement. | worktree source review; reference F11-A11/A12/A13 |
| F11-D5 | P2 | **Single-level audio control** (mute/unmute) vs the reference's 3-state level selector (alerts-only / muted / unmuted) | worktree `NavigationHud.kt:240-253`; reference F11-A12 |
| F11-D6 | P2 | **No occlusion feedback**: the reference reports *why* a frame is not drawn (`HOLD_NAV_UI`, `PAUSED_BACKGROUND`, `SURFACE_DISCONNECTED`, `INVALID_SAFE_AREA`); RT has no equivalent diagnostic, so "the map didn't update" is undiagnosable in the field. | reference F11-A7; worktree — none |
| F11-D7 | P3 | **No voice-command surface** (RT cannot answer "distance to next turn" hands-free) | reference F11-A14; worktree — none |
| F11-D8 | P3 | ETA panel semantics: the reference models `eta`/`etaWithoutTimezone`/`travelTime`/`travelTimeShort`/`isOffline` explicitly; RT's HUD shows duration + "ETA hh:mm" + remaining, without an offline indicator | reference F11-A16; worktree `NavigationHud.kt:320-360` |

---

## 5. Proposed original implementation (not implemented)

- **S11-1 (P1) — Declare an overlay contract in RT.** A small `MapOverlayLayer` enum (tiles, route, route-labels, amenities, riders, marker-labels, banner, trip-hud, controls, sheets) with an explicit draw order, replacing the implicit Compose ordering. RT-original names; no reference assets.
- **S11-2 (P1) — Publish chrome geometry to the renderer.** The banner reports its measured height to the canvas so the route's "next maneuver" can be kept clear of the chrome (the reference does exactly this).
- **S11-3 (P1) — Enforce the 48 dp touch-target floor**, including the rider hit target (density-correct dp) and enlarged hit rects for small icon buttons.
- **S11-4 (P2) — Accessibility pass**: content descriptions for the banner (maneuver + distance), the distance value, the selected rider, the audio state, and amenity markers; a11y announcements for state changes.
- **S11-5 (P2) — 3-state audio level** (alerts-only / muted / unmuted) as an RT-original control.
- **S11-6 (P2) — A "frame not drawn" diagnostic** (RT-original enum: resuming, chrome-occluded, surface-disconnected, tiles-caching) surfaced in a debug overlay, mirroring the *idea* (not the names) of F11-A7.
- **S11-7 (P3) — Voice-command surface** for the next-turn distance/ETA (RT-original command set) — P3 because it is a large surface with a small user base.

---

## 6. Tests to add / keep green

- NEW `MapOverlayOrderTest` — the declared order is total and stable; every overlay has a unique slot.
- NEW `TouchTargetTest` — all interactive map controls resolve to ≥48 dp; the rider hit radius is expressed in dp and scales with density.
- NEW `ChromeOcclusionTest` — given a banner height, the "keep clear" computation excludes the banner rect (pure geometry).
- NEW `BannerAccessibilityTest` — the banner exposes a single merged description ("Turn right, 250 metres") and the audio control exposes its state.
- Keep green: `RideLobbyScreenTest`, `MapTileRenderingAndRotationTest`, `Task15ArrivalLatchTileFallbackAndSummaryTest`, `NavigationProgressAndEtaTest`, `NavEngineAndSosTest`.

**Invariants affected:** #1 (camera/gesture behaviour — S11-2 must not move the camera, only inform the route placement) and #6 (navigation correctness). No change to camera, bearing, DR, legs, or Firestore.
