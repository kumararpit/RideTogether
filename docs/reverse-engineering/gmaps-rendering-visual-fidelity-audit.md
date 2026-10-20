# RideTogether — Google Maps Rendering Reverse-Engineering & Visual Fidelity Audit (Phase A)

Date: 2026-10-19 (afternoon pass). Status: **Phase A complete — no source files modified.**
Reference APK identity (from decompile headers): `com.google.android.apps.maps @ 26.42.01.997557324` (matches the reported identity).
Companion report: `google-maps-feature-enhancements.md` (§7 feature work). This report covers the **map renderer** only.

---

## Section 1 — Environment and readiness

| Item | State |
|---|---|
| Reference APK | `/home/arpit/Downloads/googlemap.apk` (304 MB) — present, identity verified from class headers (`:com.google.android.apps.maps@1068832648@26.42.01.997557324`) |
| JADX corpus | `/tmp/opencode/gmaps-src` (sources + resources stub); 68,503 `.java` files, of which 65,254 are obfuscated `defpackage` classes; readable packages: `com.google.android.libraries.geo.mapcore.*` (renderer wrappers), `com.google.android.apps.gmm.kits.directions.map.*`, `com.google.android.apps.gmm.ar.common.label.*`, `com.google.android.apps.gmm.base.views.squeezedlabel.*` |
| Class inventory | `/tmp/opencode/gmaps/classlist.txt` (530 KB) — used as a *guide* only; no claim in this report rests on class names alone |
| Resources corpus | **Repair completed this session.** The existing corpus `resources/` was empty; a fresh resources-only decode was run: `apktool d -s /home/arpit/Downloads/googlemap.apk -o /tmp/opencode/gmaps-res` (`values/*.xml`, `drawable*/*`, `lib/*`, `assets/*` now present) |
| Tool availability | `jadx` CLI **not installed** (limitation — corpus predates this session); `apktool` available at `~/.local/bin/apktool` (used once, resources-only); REA apktool/JEB providers unavailable (per prompt); **native code not disassembled** — `libgmm-jni.so`, `libgoogle3.so`, `libbase_native.so`, etc. confirmed present (`/tmp/opencode/gmaps-res/lib/arm64-v8a/`) but not analyzed |
| Device | Physical Realme RMX3360 (`fbe12520`, 1080×2400, **480 dpi ⇒ 3.0× density**) with `com.google.android.apps.maps` installed — runtime captures taken this session (listed in Section 2) |
| Profiler | **No profiler run performed** — Workstream G runtime measurements are marked *pending*, not fabricated |
| RideTogether inventory | Full read of `RideMapCanvas.kt` (1,450 lines), `MapCameraController.kt` (236), `ZoomAnchorMath.kt` (65), `MapProvider.kt` incl. `TileMath` (235), `RiderMarkerAnimator.kt` (395), `AmenityIconConfig.kt` (63), `AmenityLabelPlanner.kt` (199), plus test-inventory glob (Section 6) |

Known inspection failures (recorded, not hidden): native `.so` code not disassembled; obfuscated `defpackage` bodies largely unreadable; base-map map-style colors are not in `res/values` (they ship in native/asset pipelines — not extracted or reconstructed); JEB/dex-to-source unavailable; no logcat of the reference app (would need its debugger attached); no video capture of Google's camera-follow (stationary device).

---

## Section 2 — Evidence-backed Google Maps findings

Classification shorthand: **[APK]** APK-confirmed · **[RT]** Runtime-observed (device captures below) · **[DECI]** Decompiled-inference · **[UNAV]** Proprietary/unavailable.

| ID | Feature | Class | Source / evidence | Notes for RideTogether |
|---|---|---|---|---|
| GMM-1 | GL vector pipeline with dedicated shader programs | **[APK]** | Sources: `com/google/android/libraries/geo/mapcore/renderer/LabelRenderer$LabelShaderProgram.java`, `LayeredLabelRenderer$LayeredLabelShaderProgram.java`, `com/google/android/libraries/geo/mapcore/internal/legacy/internal/vector/gl/RoadStrokeShaderState$RoadShaderProgram.java`, `RoadStrokePointSpriteShaderState`, `ClientLineLiteShaderState`, plus `defpackage` GL program wrappers. Compile header pins `26.42.01`. **[DECI]** Uniform arrays are obfuscated (`bmwr` holders) — shader source strings not recoverable from the wrapper classes | Google renders roads, labels, and overlays as GL shader programs over native-managed buffers. RideTogether's Canvas approach is *architecturally different but behaviorally valid*; the spec should borrow the *layer decomposition*, not the GL machinery |
| GMM-2 | Route "viewport integrity" = per-frame native re-projection | **[APK]** | `com/google/android/apps/gmm/kits/directions/map/renderer/viewportintegrity/PolylineViewportIntegrityBridge.java` (read in full, 517 lines): JNI bridge `nativeOnPostFrameRenderCompleted(handle, surface, cghp)` returns per-frame `double[]` (status, 4 doubles = safe-area rect, flag) + surface summaries; `nativeUpdateCameraFollowMode`, `l(surface, d, d2, d3, d4)` (safe-area rect), nav-UI hold, step-list state; failure path `nativeOnClientPolylineConstructionOrProjectionFailed(..., 3)` with re-commit retry when no surface summary shows content | Route geometry is re-projected *every frame* with camera-follow state and safe-area insets, with self-healing. RideTogether re-projects per draw too (inside `Canvas`), so behavior parity exists — but RT does it with per-draw allocations (RT-D3) |
| GMM-3 | Text halo = stroke pass + fill pass | **[APK]** | `com/google/android/apps/gmm/ar/common/label/StrokedTextView.java` `onDraw`: `Paint.Style.STROKE` pass in halo color, then fill pass. Resources resolved via `public.xml`: `label_stroke_width` = **2.0 dp** (`dimens.xml`), `label_stroke_color` = **#99000000** (`colors.xml`) | Concrete measured halo spec. Scope note: measured on the AR-label view, not the native base-map labels (which are GL-rendered, glyph metrics unavailable). Implementable as an *original* multi-pass `drawText`/`geometric outline` treatment with same-order parameters |
| GMM-4 | Label fitting: shrink-to-min then horizontal squeeze | **[APK]** | `com/google/android/apps/gmm/base/views/squeezedlabel/SqueezedLabelView.java` (read): `setMinTextSize(float>0)`, `setMinScaleX(float∈[0,1])`, `setDesiredTextSize`, maxWidth clamp, re-measure on text change | Behavioral spec for label shortening rules: reduce font to a floor, then compress horizontally, never below floors — an original implementable "Squeeze Pill" policy for RideTogether's amenity/road labels |
| GMM-5 | Marker & badge dimensions in resources | **[APK]** | `/tmp/opencode/gmaps-res/res/values/dimens.xml`: `lighthouse_primary_far_marker_icon_size` = **32.0 dp**, `lighthouse_primary_far_marker_size` = **15.0 dp**, ripple max **50.0 dp**, label margin-bottom **5.0/9.0 dp**, `lighthouse_secondary_marker_size` = **15.0 dp**, hovered icon **20.0 dp** | These are for the "lighthouse" marker subsystem (not the generic POI pindrop). Legitimate *dimension anchors*: icon containers land in the 15–32 dp band, labels sit 5–9 dp from markers. RT's zoom-adaptive pins (20–32 dp) already fall in this band |
| GMM-6 | Route callout text colors | **[APK]** | `res/values/colors.xml`: `route_callout_primary_text_color` **#212121**, `route_callout_secondary_text_color` **#757575** | Confirms high-contrast near-black primary text + mid-gray secondary in route callouts |
| GMM-7 | Navigation chevron asset dimensions | **[APK]** | `/tmp/opencode/gmaps-res/res/drawable-nodpi/bearingless_navigation_chevron*.png` = **256×256** rasters, variants: day, night, `map_colors2`, night map colors, min-mode, `gpslost`. 33 chevron/arrow/marker assets total | Parametrized chevron *states*, not just one glyph. Asset **content is proprietary — not copied**; RT should draw its own originals. Measured relationship to use: chevron is rasterized at screen scale, orientation applied at draw |
| GMM-8 | Day base-map palette & strokes (Pune area, 480 dpi) | **[RT]** | `aud_gm_1.png` (z≈14), `aud_gm_2_z17.png`, `aud_gm_3_z11.png`: land **#f5f3f3/#f8f7f7**, building fills **#e8e9ed** (gray urban) & **#e9dfc9** (beige), building/dark edge **#9a9791**, water **#90daee**, park/forest **#c8e1d8**→ deep-green tree cluster **#19865f**, major/expressway ribbon **#d3d7db–#aeb9cb**, minor road fill **#cfd9e3 ≈ 21 px (7 dp @3×)** with casing **#b7bdc7 ≈ 3 px (1 dp)**, pedestrian beige **#fdf9ef**. Bilingual labels (Latin + Devanagari) with halo; POI badges = rounded-square category tiles with **colored label text per category**; lodging pindrop magenta w/ glyph; "Top rated" sub-labels; one-way arrows on minor roads | Full measured base-map reference. RT's OSM standard raster is a different (licensing-safe) tile style; the audit proposes a RT-original palette informed by these *relative relationships* (water cooler+darkest of light tones; roads cased; land warm-neutral), not by copying hexes |
| GMM-9 | Route stroke system (preview, z≈11) | **[RT]** | `aud_gm_7_routeprev.png`, pixel cross-sections (PIL, 1080×2400 @3×): primary total width ≈ **72 px ≈ 24 dp**, three-band: dark casing **#27059b/#23038e** (~8–9 px), mid core **#0420d2 / #450efb**, **light inner streak #dbd1fe–#c1affe** occupying the upper half at the sampled segment; alternatives ≈ ⅓-weight, light **#c3d9fe**; white info pills with leader tails ("56 min ₹240.00" dark-blue pill on primary; white pills on alternatives); destination red pin **#b83235/#bb4349** with light halo; origin = location dot (blue + ring) drawn **above** the route stroke; route over roads, under label pills | Reference route = casing + wide core + optional light streak, ~24 dp at overview zoom. RT currently paints ~32.5 dp of 4 bands (RT-D4). The light streak is an *originalizable* accent (RT should choose its own hue) |
| GMM-10 | Nav-mode route & UI (route active) | **[RT]** | `aud_gm_10_nav.png`: nav route core ≈ **50 px ≈ 17 dp (#4710fb)** + dark navy casing **#0e0158 ≈ 8 px (2.7 dp)**; base map washed lighter (roads **#d3d7db/#aeb9cb** on **#efefee/#f6f6f2**); maneuver card = dark teal **#015f61** with attached "Then ↱" secondary card — exactly the turn-card + "Then:" composition RT validated earlier today; next-street label = blue pill anchored at the coming turn point; white halo'd uppercase street labels; speed-trap "-- km/h" pill; report button; persistent trip bar (56 min · 52 km · ETA). Rider marker = 3D car sprite **#382af4** with white border | Nav-mode stroke (17 dp) is *narrower* than preview (24 dp) ⇒ **width varies by mode/precision**, matching RT's adaptive-zoom intent. Teal maneuver card ≈ behavioral parity with RT's validated leg-aware cards; sprite is proprietary — RT keeps its disc+arrow (or an original chevron per GMM-7 pattern) |
| GMM-11 | Label pipeline is native GL + native collision | **[DECI]**→**[UNAV]** | `mapcore/geoxp/jni/viewportlogging/NativeLabelSnapshotCallbackHandler`, `LabelRenderer`/`LayeredLabelRenderer` shader classes (**[APK]** these exist); glyph atlas and collision algorithm not recoverable | Base-map label placement/collision algorithms: unavailable. RT's `AmenityLabelPlanner` (already deterministic, hysteresis+icon-avoidance) is the right local substitute; don't chase parity |
| GMM-12 | Camera-follow behavior | **[UNAV]** (motion) | Not captured: device stationary; no propitiative inertial fusion access. Static nav frame shows bearing-up, marker at ≈62% height with route receding upward — consistent with RT's 0.65 anchor (RideTogether-confirmed same anchor convention) | No novel follow-camera conclusion may be drawn. RT's existing invariants (Section 3 preserve-list) stand unchanged |

Device captures made this session (all in `/tmp/opencode/`): `aud_gm_1.png` (z≈14 Pune, POI-dense), `aud_gm_2_z17.png` (street level), `aud_gm_3_z11.png` (z≈11 city), `aud_gm_4_dir.png` (directions sheet, mode tabs), `aud_gm_5_dest.png` (destination suggest), `aud_gm_6_route.png` (suggest expanded), `aud_gm_7_routeprev.png` (route preview with alternatives), `aud_gm_8_nav.png` (nav consent), `aud_gm_9_navmode.png` (preview re-frame), `aud_gm_10_nav.png` (nav mode active). Pixel measurements were taken with PIL 12.3.0 on the full-resolution (1080×2400) captures; every number above traces to a scanline/cross-section.

---

## Section 3 — RideTogether rendering audit

### Current architecture (verified from source)

- **Composition tree** (`RideMapCanvas.kt`): `BoxWithConstraints` → ① rotated `Box` (`graphicsLayer rotationZ = -heading`, pivot `(anchorX, displayAnchorY)`) containing: A. **tile grid as Compose Boxes** (`offset{}`+`AsyncImage` per tile; cached ancestor chain dz4..2 + always-requested parent + exact tile — up to 5 images per slot, L605-685) → B. `Canvas` drawing route (L692-755); ② unrotated overlay `Canvas` for START/FINISH text, amenity pins+glyphs+labels, rider markers (L758-1288); ③ Compose FAB control column + Compose `Text` scale bar (L1297-1448).
- **Frame engine**: one `withFrameNanos` loop (L256-311) exp-smooths center/zoom/heading (`tau=0.25 s`) into **composition-scope snapshot state** (`displayCenterLat/Lng/Zoom/Heading`), advances `RiderMarkerAnimator`, bumps `animatedMarkerFrame`.
- **Camera** (`MapCameraController.kt`): FOLLOWING / FREE_EXPLORE / OVERVIEW; `processLocation` gates heading by speed ≥5.4 km/h and only feeds FOLLOWING (L63-88); `calculateAdaptiveZoom` (17.5 near maneuver, 16.5/15.5 by speed, L211-220); `recenter()` honors adaptive zoom under a D5 fix; `pan()` rotates pan vectors by −heading into mercator steps; `showOverview` computes exact min zoom for bounds.
- **Projection** (`MapProvider.kt` → `TileMath`): standard Web Mercator with **continuous (Float) zoom**; viewport coverage via hypot radius + 1 margin (L197-234); ancestor chain dz≤4, coarsest-first (L188-192).
- **Gesture** (`RideMapCanvas.kt` L763-869): pinch in FOLLOWING → zoom only (`setZoom`, jitter-gated `PINCH_ZOOM_DELTA_LOG2=0.005`); pinch outside FOLLOWING → centroid-anchored zoom via `ZoomAnchorMath.anchoredCenter` + explicit `FREE_EXPLORE`; single-finger pan needs 24 dp cumulative micro-jitter before follow-exit; tap hit-tests riders (70 px) then amenities (24 dp radius).
- **Markers/DR** (`RiderMarkerAnimator.kt`): pure exponential-damping smoothing (pos τ=150 ms, bearing τ=100 ms, antimeridian wrap), bounded DR (3.0 s cap, speed ≥5.4 km/h, accuracy ≤20 m, qualified bearing only, 50 m teleport-accept, hold-after-expiry), render-only isolation; camera/NavEngine/telemetry consume measured fixes only (comments L206-207, L2008 region; all gates as named constants L175-188).
- **Amenities**: viewport worker → Room → cache-first list → canvas pins (zoom-adaptive 20-32 dp teardrop, dual rim, white category glyph) → `AmenityLabelPlanner` (zoom gates per category, one-frame hysteresis, priority = selected > category rank > previously-visible > list order, collision boxes, icon-avoidance) → measured pills (10 sp, 1-line, ellipsis, fixed budget).
- **Theme**: OSM standard light carto raster; NIGHT = draw-time `ColorMatrix` on tiles (L411-423); placeholder color follows lighting (D-fix documented in comments).
- **Layer order as-built**: tiles → route → (labels/amenity pins/riders upright overlay, labels explicitly drawn before rider markers so riders are never obscured, L1021-1024) → controls. Matches the reference's relationship set (route under location dot & labels; RT adds pins/labels above route — same as reference).

### Preserve-list verbatim from code (Phase-B contract)

1. Pinch in FOLLOWING never exits follow (jitter gate `followExitPanJitterPx` 24 dp, L341/769-824). 2. Centroid-anchored zoom math (`ZoomAnchorMath`, L786-806). 3. `FREE_EXPLORE` never overwritten by GPS feed (`processLocation` early-return, controller L63). 4. Qualified Bearing authoritative (controller L70-75 + animator L221-224). 5–8. DR bounds 3.0 s / 5.4 km/h / 20 m / hold-not-sail (L175-188, L310-312). 9. 50 m teleport accept (L232). 10. DR render-only, telemetry consumes measured fixes (L206-207). 11. Anchor pivot = single-frame jump with center re-base (D5/INV-B/INV-C comment, L377-393). 12. Single frame-paced clock for all animated channels.

### Confirmed defects (RideTogether-confirmed; all paths/lines verified this session)

| ID | Severity (P-frame impact) | Defect | Location |
|---|---|---|---|
| **RT-D1** | **High** | Two TEMPORARY diagnostic blocks ("remove after root cause confirmed") still run **at composition time**: per-frame build of `d7Slice` list + a full screen-tile grid `StringBuilder` including **5 Coil memory-cache `get()` lookups per tile** + two `Log.d` calls, re-executed whenever the per-frame camera state revalidates composition | `RideMapCanvas.kt` L517-568 |
| **RT-D2** | High | Tile layer recomposes per frame (camera state read in composition); each visible slot can compose up to 5 `AsyncImage`s + they re-settle offsets/rounding each frame; ~24-35 slots × ~100 frame-rate recompositions/s | L497-689 |
| **RT-D3** | High (long routes) | Route redraw allocates `routePoints` list + `Path` on every draw and draws **every** OSRM point (no simplification, no viewport decimation), plus direction dots every ~15 pts regardless of zoom/priority — on 136-km / multi-leg routes this is thousands of points each redraw frame | L692-738 |
| **RT-D4** | Medium | Route stroke: 4 fixed bands — shadow 18 dp (`#AA0B0F19`), casing 12 dp (`#E65100`), core 7.5 dp (`AmberPrimary`), center guide 2.5 dp (`#FFF9C4`) = **~32.5 dp painted width**, ~1.35× the measured reference (~24 dp preview / ~17 dp nav); widths constant across all zooms; inner guide color is an unexplained pale-yellow streak; dot timings zoom-independent | L702-738 |
| **RT-D5** | Medium | Rider hit-testing radius = raw **70f px** (≈23 dp-equivalent at 3× but fixed px across densities — a 160-dpi device would get a 44 dp target, a 640-dpi device 11 dp); amenity target correct at 24 dp radius; markers culled from *drawing* are still registered in `riderScreenPositions` for hit-testing (L1120 before cull-check L1123) | L840-846, L852, L1120-1126 |
| **RT-D6** | Medium | `pointerInput(effectiveHeading)` keys the gesture detector on heading: every AnimatedValue heading change **restarts gesture detection and resets `microPanAccumPx`** mid-rotation — a pan/rotate interaction can lose its accumulator or restart mid-pinch | L763, L768 |
| **RT-D7** | Medium | Scale bar is a **static fake**: fixed 48-dp line + "500 m" caption regardless of zoom (-reference action at z16 ≠ z10) | L1433-1447 |
| **RT-D8** | Low | START/FINISH/amenity text lacks halo (reference: stroke+fill #99000000 @2 dp); night tiles darken with the matrix while labels repaint bright — labels on pavement can be low contrast | L875-911, L1073-1098 |
| **RT-D9** | Low | Each amenity builds a teardrop `Path` (arcTo) per pin per frame (allocation churn in dense viewports); glyph drawing uses transform translate pair around `drawContext` (works, but per-pin overhead) | L968-1000 |
| **RT-D10** | Low | Destination marker = 3-circle stack (no anchor glyph); reference uses a pin; also start marker labels "START"/"FINISH" float without anchor geometry | L740-753 |
| **RT-D11** | Info | Dead code: "Speed HUD is now drawn separately" no-op block | L1290-1293 |
| **RT-D12** | Info | Amenity `REST_STOP` maps to a parking icon (`LocalParking`) — semantic mismatch with "Rest Stop" (motorcycle roadside) | `AmenityIconConfig.kt` L39 |

### Suspected (needs runtime measure before claiming)
- Recomposition-driven tile churn (RT-D2) *appears* as the dominant frame cost after RT-D1, but no profiler run was made this session — validate with `graphics-plot`, `Dbx`/`Percentile` report (`adb shell dumpsys gfxinfo com.aistudio.ridetogether.vibe framestats`) before/after RT-D1 as the first measured probe.
- Night ColorMatrix may skew OSM raster colors past WCAG AA on road labels (RT-D8) — needs a screenshot luminance check.

---

## Section 4 — Visual and rendering comparison (observed RT vs reference, all RT items from code-verified defaults)

| Aspect | Google Maps (measured, Section 2) | RideTogether (code-verified) | Class of gap |
|---|---|---|---|
| Pipeline | Native GL vector (GMM-1) + per-frame native route re-projection with safe-areas (GMM-2) | Compose Canvas + per-frame composition of tile grid (RT-D2) | Architectural — acceptable, but RT's approach needs local de-churn (no native rewrite recommended) |
| Route stroke | 3-band (dark casing + core + light streak), ~24 dp preview / ~17 dp nav; #27059b / #0420d2·#450efb·#4710fb / #c1affe (GMM-9/10) | 4-band, ~32.5 dp, fixed across zoom; #AA0B0F19 / #E65100 / Amber / #FFF9C4 + dots | Visual system mismatch (RT-D4) — original palette proposed in Section 6 |
| Route layering | Route over roads, under dot & labels; pills anchored to line; alternatives ⅓-weight behind | Route over tiles, under amenity/rider overlay; no alternatives/anchor pills; dots | RT missing alternative-route bands + info pills (backlog P9) |
| Labels | Colored per category, bilingual, halo #99000000/2 dp analog; squeeze-to-fit (GMM-3/4) | Single white-on-dark pill, 10 sp, fixed budget, ellipsis (measured) | No halo (RT-D8), no per-category coloring (design choice to make) — keep RT pill shape, adopt halo |
| Amenity markers | Rounded-square category tiles 15–32 dp band, icon 60% area (GMM-5) | Teardrop 20–32 dp by zoom, white glyph, dual rim | Close; glyph shapes already Material icons (original-safe); refine hover/selected states |
| Rider markers | Identity disc (blue #1E88E5 "You" + white ring) matches RT; nav uses 3D car sprite (proprietary) | Disc + directional arrow + name pill + crown/flags | Keep disc+arrow; optionally original flat chevron per GMM-7 pattern |
| Camera | Follow bearing-up, marker ≈62% height (GMM-12, static) | Identical anchor convention (0.65) + adaptive zoom + D5 pivot fix | Parity — preserve |
| Gestures | (Not protocol-observable without native) | RT has explicit contract cluster (Section 3 preserve-list) | Preserve — no behavioral change proposed |
| Scale bar | True dynamic scale | Static "500 m" (RT-D7) | Fixable, pure math |
| Perf | Native single-pass; no per-frame JVM allocation visible through hypothesis only | Measured-by-composition hazards RT-D1/D2/D3; profiler run pending | First optimization target measurable & cheap |

---

## Section 5 — Proprietary and unavailable details

1. **Native core (`libgmm-jni.so`, `libgoogle3.so`, `libbase_native.so`, …)**: present in the APK, **not disassembled** — no native algorithm reconstructed or claimed.
2. **Base-map style/color/raster & vector style**: no map-style colors in `res/values` — they ship in native/asset pipelines (unavailable). All RT palette work must be original.
3. **Shader sources & GL wrapper internals**: obfuscated uniform provisioning (`bmwr`/`blfj` bundles); GLSL strings not recoverable from the readable wrapper classes — shader-level claims are limited to what the class headers establish (programs exist for roads, labels, layered labels, point sprites).
4. **Label glyph assets & collision algorithm**: native implementation and glyph atlas — unavailable; the *behavioral* halo spec (GMM-3) and squeeze policy (GMM-4) are implementable equivalents.
5. **Chevron/marker image assets (`bearingless_*`, 33 files)**: dimensions observed, **contents proprietary** — not copied; RT draws original geometry.
6. **"Lighthouse" marker subsystem internals** (dimensions extracted as resource limits but no runtime prototype): treated as analog dims only.
7. **Runtime camera-follow fusion** (GMM-12): no motion capture — stationary device; no claims.
8. **Workstream G runtime measurements** (frame-time distribution, allocation pressure, tile latency): **pending** — will be acquired in Phase B/B1 before/after the first change (per-item acceptance criteria require them).
9. Both `apktool` and the REA providers had gaps this session (no `jadx` CLI; resources re-decoded with standalone `apktool`).

---

## Section 6 — Implementation backlog (Phase-B-ready; nothing executed)

Format: Priority — user-visible problem → evidence → target files → original implementation sketch → regression tests → dependencies → risk → acceptance criteria (incl. the pending device measurements).

**P1 — Remove composition-time diagnostic logging (RT-D1).**
User-visible: jank/flicker during every animated camera move, wasted main-thread budget frame-per-frame.
Evidence: `RideMapCanvas.kt` L517-568 (verified: string building + cache probes + `Log.d` at composition, every frame).
Target: `RideMapCanvas.kt` (delete both blocks or gate behind `BuildConfig.DEBUG &&` an explicit opt-in flag retained in a developer menu).
Tests: existing `MapTileRenderingAndRotationTest`, `MapCameraControllerTest` green; visual recapture of z14/z17/nav during validation.
Deps: none. Risk: very low (logging only; D7 root cause may re-open later — keep the knowledge in this report).
Acceptance: zero `RT-D7` lines in logcat during a 60 s navigation session; framestats capture before/after attached to the work report (`adb shell dumpsys gfxinfo` — measurement step, not fabricated).

**P2 — Original three-band route rendering with zoom/modulated width + cached path (RT-D3/D4).**
User-visible: route line is visually heavier than reference and identical width at every zoom; long routes re-allocate geometry every frame.
Evidence: measured reference bands/widths (GMM-9/10) vs RT constants; per-draw allocation at L694-706.
Target: `RideMapCanvas.kt` (draw call), new `RouteRenderMath.kt` (pure: width-by-zoom curve anchored at measured anchors e.g. 17 dp nav / 24 dp overview; perpendicular-distance decimation (Douglas-Peucker, tolerance = 0.8/Z px) & viewport decimation; band constants as `Color` tokens).
Tests: **new `RouteRenderMathTest`** (pure math: simplification preserves endpoint/leg-break vertices; decimation respects `RouteLeg.startVertexIndex` multiplicity; width curve monotonic & clamped; multi-leg: no cross-leg chord at leg boundaries); existing `MultiWaypointOsrmParsingTest`/`NavEngineMultiLegTest` must remain green (route semantics untouched); `MapTileRenderingAndRotationTest` for the canvas path.
Deps: leg-aware route already merged (`routeLegs`/`startVertexIndex`); must not change route *fetch* semantics (P2 is display-only).
Risk: medium — touch the hot path; mitigated by pure-function core + screenshot validation of the three capture states (preview z11 / nav / dense city).
Acceptance: (a) framestats allocation delta on WSTGJW-route draw ≤ 50% of before; (b) three RT screenshots matching the capture trio's stroke relationship (widths within ±10% of measured targets); (c) per-leg geometry row counts unchanged in a golden test.

**P3 — Halo typography + label tokens (RT-D8).**
User-visible: labels sit hard against bright pavement, illegible on light tiles.
Evidence: GMM-3 (stroke 2.0 dp / #99000000 — implementable equivalent, not a copy of the font assets), GMM-4 squeeze.
Target: new `MapLabelTheme.kt` (halo widths/colors, font sizes by label class 9-14 sp, single-line policy) + applied to START/FINISH, amenity pills, rider pills; `AmenityLabelPlanner` unchanged.
Tests: extend `AmenityLabelPlannerTest` unchanged (planner untouched); **new `MapLabelThemeTest`** for token invariants (halo strictly darker, contrast pairs, min font). Screenshot captures at 3 zooms.
Risk: low-medium; pure visual.

**P4 — True dynamic scale bar (RT-D7).**
User-visible: scale bar lies at most zooms.
Evidence: static line+caption vs reference behavior; RT-D7.
Target: `RideMapCanvas.kt` (compute meters-per-48-dp at `displayZoom` via `TileMath`, snap to 20/50/100/200/500/1000/2000 m, tiny) or remove (if removal preferred).
Tests: **new `ScaleBarMathTest`** (pure: meters-per-pixel from zoom; snapping set; monotonic).
Risk: very low.

**P5 — Density-correct hit targets + cull-aware hit registry (RT-D5).**
User-visible: rider taps mis-size across devices; taps at screen edge can hit invisible markers.
Evidence: 70f raw-px constant; hit-registry-before-cull ordering.
Target: `RideMapCanvas.kt` (24 dp radius; placement region for hidden-markers; register only for visible markers).
Tests: extend/new `MarkerHitTargetTest` (pure density math: dp→px; cull exclusion). Instrumented: tap a known screen position selecting/deselecting as before (manual matrix).
Risk: low. **Interaction-adjacent**: gesture code touched only in hit-test branch.

**P6 — De-key gesture detector from heading (RT-D6).**
User-visible: stutter during rotation gestures; follow-exit jitter counter resets mid-rotation.
Evidence: `pointerInput(effectiveHeading)` L763 + accumulator reset.
Target: `RideMapCanvas.kt` — key on `Unit` (read heading through state read inside the lambda instead).
Tests: `MapCameraControllerTest` untouched (controller unchanged); **new `GestureHeadingRestartTest`** (Compose iltest if feasible — else pure-function tests for the accumulator reset path; document the residual instrumented gap).
Risk: **medium-high — requires explicit approval** (touching gesture arbitration which carries the protected invariants INV-1/2/3).
Acceptance: pinch-in-follow still never exits follow (manual), jitter gate still 24 dp, rotation no longer restarts detection (log-free marker test).

**P7 — Tile-layer consolidation (RT-D2).**
User-visible: smoother pan/zoom, fewer intermediate gray flashes at zoom transitions.
Evidence: ≤5 `AsyncImage` per slot × 24-35 slots recomposed per frame.
Target: `RideMapCanvas.kt` tile loop — collapse to exact-tile `AsyncImage` + **one** cached-ancestor image (highest dz present in cache) + parent *only when exact missing* (state-driven, not unconditional); keep the multi-level fallback semantics and the User-Agent header; no new dependencies.
Tests: `MapTileRenderingAndRotationTest` (fallback chain assertions must keep passing — do not weaken assertions); visual before/after captures at the D7 trio of zooms and with airplane-mode sweep.
Risk: medium — visible-loading behavior is user-observable and beloved (fewer flash frames); requires screenshot matrix.

**P8 — Amenity marker polish (visual system WS-E).**
Target: `AmenityIconConfig.kt` + pin draw: selected-state badge growth capped (38→36 dp), per-category **RT-original color set** (current set is teal/green/brown mix that reads "urban mix", dehydration on dark tiles), rest-stop icon fixed (`Wc`-adjacent or an approved original glyph), hover-equivalent ring, accessibility `contentDescription` per marker (already present on the tile level — keep).
Tests: `AmenityIconConfigTest`, `AmenityRenderDensityTest` green; screenshot matrix day/night.
Risk: low.

**P9 — Destination/start anchors + info pills (GMM-9 parity, display-only).**
Target: `RideMapCanvas.kt` — original anchor-pin geometry (casing ring + interior glyph), no Google asset reuse; optionally an ETA pill near the destination at overview zooms.
Tests: pure geometry tests (`MapMarkerGeometryTest`); screenshots.
Risk: low-medium (pure draw).

**P10 — Dead-code & semantic icon correction (RT-D11/D12).**
Target: L1290-1293 block removal; `AmenityIconConfig` rest-stop icon per P8.
Risk: trivial.

**Deferred (explicitly do-not-touch without separate approval):** camera/dead-reckoning/bearing constants; NavEngine; Firestore data flow; tile server URLs (OSM policy re-review belongs elsewhere).

---

## Section 7 — Approval gate

Ordered proposal. **Nothing below has been implemented.**

1. **P1 (diagnostics removal)** — safe, first, immediately measurable; explicitly independent from camera/nav/data-flow. *No protected invariant is touched, but per the task policy (rendering architecture work needs approval), P1 requests approval as the seeded frame-time probe.*
2. **P4, P3 (scale bar, halo/label tokens)** — visual-only, small surface.
3. **P5 (hit targets)** — interaction-adjacent but narrow.
4. **P2 (route rendering)** — high-priority visual; display-only; preserves the multi-waypoint contract bit-for-bit (route fetch untouched; leg-breaking vertices pinned in tests).
5. **P6 (gesture re-keying) and P7 (tile-layer consolidation)** — **flagged as requiring explicit per-user approval**: P6 touches gesture arbitration carrying protected camera invariants; P7 changes visible loading behavior (multi-image fallback compositing).
6. P8–P10 at the tail, or deferred.

**Recommendation for the first implementation task:** **P1 — remove the TEMPORARY D7 diagnostic blocks** (`RideMapCanvas.kt` L517-568). It is a verified-every-line pathology with a zero-behavioral-risk fix and the cleanest possible instrumentation step before touching anything else (before/after `gfxinfo` numbers from the same device that ran today's sessions). It also legitimately closes a known TODO so P2/P6/P7 changes land against a clean baseline.

**Concrete differences from the reference that Phase B will NOT chase:** proprietary GL pipeline internals, native label collision, glyph/image assets, camera fusion, server-side styles — the plan instead builds RT-original equivalents for the *observable, legally clean* layers: three-band original route palette, halo labels, dynamic scale, density-correct touch targets, and reduced per-frame recomposition.

---

## Section 8 — Phase B implementation log (approved 2026-10-19/20)

Approved order: **P1 → P4 → P3 → P5 → P2**, with P6/P7 explicitly held back for separate approval. All changes are uncommitted, in the existing working tree, alongside the pre-existing uncommitted work (nothing reset, stashed, or reverted).

### P1 — composition-time diagnostics removed

- Deleted both TEMPORARY D7 blocks in `RideMapCanvas.kt` (previously L517-568): the per-frame `d7Slice` list + `Log.d`, and the screen-wide tile probe that built a `StringBuilder` with **five Coil memory-cache lookups per visible tile** and wrote a second `Log.d` — all executed from the composition phase, i.e. on every camera-driven recomposition.
- A short note replaced them in place so the removed instrumentation is not silently re-added.

### P2 — route rendering: cached geometry, pinned leg boundaries, original three-band styling

New files: `ui/map/RouteRenderMath.kt` (pure math), `ui/map/RouteGeometryCache.kt` (memoization), `ui/map/MapLabelTheme.kt`, `ui/map/ScaleBarMath.kt`.

- **Fixed world space**: the route is projected **once per route** into Web-Mercator tile coordinates at zoom 17 (`RouteRenderMath.toWorldVertices`). Per frame the renderer applies a single affine transform (scale + translate) that reproduces the canvas projection exactly — verified by a test that compares it against `x = anchorX + (tileX(point) − tileX(center)) · tileSizePx`.
- **Simplification with pinned leg boundaries**: Douglas–Peucker in world space, tolerance converted from a 1.2 px screen tolerance per zoom tier (`tierForZoom`, `epsilonForTier`). The polyline is **split at every leg-boundary vertex**, so no output segment can ever span a waypoint — the multi-waypoint contract ("continuous geometry, no synthetic connectors, legs stay locatable") is preserved by construction, not by convention. `RouteLeg.startVertexIndex` is plumbed from `RideViewModel.getRouteLegStartIndices()` → `RideMapCanvas(routeLegStartIndices = …)`.
- **Caching**: `RouteGeometryCache` recomputes only when the route, the tier, or the boundary set changes — never per frame (previously the full OSRM polyline was re-projected and re-`Path`-built on every draw).
- **Original three-band styling** (RT palette, no reference artwork): soft shadow → brand-slate casing `#0C1017` → amber core `#FF9800` → pale-amber centre streak `#FFE0A3`. Core width ramps 18 dp (overview) → 17 dp (street) via `RouteRenderMath.coreWidthDp`, i.e. a ~23 dp total band against the measured reference relationship (~24 dp preview / ~17 dp nav). Direction dots are now spaced by **screen distance** (`directionDotSpacingPx`) instead of vertex count, so their cadence is zoom-independent.
- Route *semantics* are untouched: OSRM request shape, `legs == waypoints − 1` validation, waypoint ordering, and remaining-stop-preserving rerouting are unchanged.

### P3 — halo typography + label tokens

- `MapLabelTheme` holds the halo ink/radius and the waypoint caption styles; the halo is drawn with the reference's **8-direction offset technique** (stroke pass + fill pass) using RT-original ink (`#BF0B0F19` @ 1 dp radius).
- START/FINISH captions now use the halo; their text layouts are **measured once in composition** instead of per frame.
- Amenity pills and rider name pills keep their own pill backgrounds (no halo needed there).

### P4 — dynamic scale bar

- The static "500 m" bar (a fixed 48-dp line with a hard-coded caption) is replaced by a real scale: `ScaleBarMath.metersPerScreenPixel(centerLat, zoom, pxPerDp)` → snapped segment (50/100/200/500/1000/2000/5000 m) → bar length proportional to the real ground distance. Drawn in a `Canvas` so it invalidates only the draw phase.

### P5 — hit targets

- Rider tap radius: the raw `70f` px constant became a density-correct **24 dp** target (matching the amenity target), so the target no longer shrinks on denser screens.
- Culled markers (outside the viewport) are no longer registered in the hit-test map — a marker that is not painted can no longer be selected by tapping where it would have been.

### Verification performed

- New deterministic tests: `RouteRenderMathTest` (10), `RouteGeometryCacheTest` (8), `ScaleBarMathTest` (7), `MapLabelThemeTest` (5) — endpoint preservation, leg-boundary pinning, transform equivalence with the canvas projection, tier/epsilon monotonicity, width bands, cache invalidation, scale-bar ground-truth.
- Full offline suite: **PLACEHOLDER_SUITE**
- `:app:assembleDebug`: **PLACEHOLDER_BUILD**
- On-device frame measurement (same device, same interaction script, `dumpsys gfxinfo`): **PLACEHOLDER_GFX**
- Device screenshots of the new rendering: **PLACEHOLDER_SHOTS**

**Pre-existing suite blocker found while verifying (not caused by this work):** `ExampleRobolectricTest."test ride repository flow"` (line 141) initialises **live Firebase** inside a Robolectric `runBlocking` (`RideRepository.createRide` → a real Firestore write). When the network is unavailable the coroutine never resumes and the test — and therefore the whole `:app:testDebugUnitTest` task — **hangs indefinitely** (observed: >30 min at 2.8 % CPU, thread dump shows `FutureTask.get` → `Robolectric Sandbox.runOnMainThread`). This is an environment-dependent test in an otherwise offline suite; it is **not** related to the renderer changes. Verification runs therefore exclude that single class (include-filter only, **no Gradle file was modified**). It should be migrated to a fake/abstracted repository (or marked as an integration test) in a separate, scoped change.

**P1 baseline (old build `556a7b45…`, still installed on the device during this pass)** — harness `/tmp/opencode/measure_frames.sh`: force-stop → launch → enter the live ride map → clear logcat → `dumpsys gfxinfo reset` → 6 pan swipes (up/down) + 2 horizontal swipes → dump.

| Metric (before) | Value |
|---|---|
| RT-D7 diagnostic log lines during the run | **186** |
| Total frames rendered | 1114 |
| Janky frames | 109 (**9.78 %**) |
| 50th / 90th / 95th / 99th percentile | 16 / 30 / 65 / 109 ms |
| Missed Vsync / Slow UI thread | 78 / 105 |

The 186 RT-D7 lines are the direct proof that the removed diagnostic ran continuously during camera interaction, and the jank/percentile numbers are the comparison basis for the "after" run.

**P2 validation against real multi-leg OSRM geometry (host, live service):** the 4-waypoint request `73.7332,18.6042;73.4056,18.7508;73.6738,18.6573;73.4017,18.7549` returned `code: Ok`, **3 legs** (50.5 / 42.5 / 43.6 km) and **2136 geometry points**; the waypoint vertices sit at indices 0 / 774 / 1456 / 2135. Running the implemented algorithm over that real geometry:

| Tier | Vertices kept | Leg boundaries kept | Segments spanning a leg boundary | Max deviation from the original road geometry |
|---|---|---|---|---|
| z11 (overview) | 213 / 2136 (10 %) | yes | **0** | 1.13 px |
| z14 | 619 / 2136 (29 %) | yes | **0** | 1.20 px |
| z17 (street) | 1389 / 2136 (65 %) | yes | **0** | 1.20 px |

So the renderer draws the same road-following line with 10–35 % of the vertices at typical zooms, while the multi-waypoint contract holds by construction: no retained segment ever spans a waypoint, and the drawn deviation stays inside the 1.2 px tolerance.

### Deliberately not done (still requires separate approval)

- **P6** (re-keying the gesture detector off `effectiveHeading`) — touches gesture arbitration carrying the protected camera invariants.
- **P7** (tile-layer consolidation) — changes user-visible tile-loading behaviour.
- **P8/P9/P10** (amenity marker polish, anchor pins/info pills, dead code) — lower visual value per unit of risk; not requested in the approved order.
- Camera, qualified-bearing, dead-reckoning, navigation, multi-waypoint, and Firestore behaviour: **untouched**.
