# Feature 03 — Map Canvas Rendering & Cartographic Style

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324.
**No application source, test, Gradle, schema, or build artifact was modified. The D7 investigation and its instrumentation were left untouched.**

---

## 1. Summary

The reference runs **two renderers** — a legacy Java+GLES "Phoenix" path and a native vector "GeoXP/MapCore" path — with a *style-table* system that interpolates widths and colours **per zoom level**. RideTogether renders OSM raster tiles through Compose with a day/night colour matrix; its tile **fallback chain** is the one place where it already matches the reference's approach.

The transferable ideas are: **ancestor-tile fallback with a minimum zoom**, **zoom-interpolated widths**, **an explicit screen-space vs. world-space encoding decision**, and **a layer registry**. The non-transferable parts are the native renderer, the label placement/collision engine, and the style service.

---

## 2. Reference evidence

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F3-A1 | The reference has **two renderers**; the vector one is native | APK-confirmed | legacy "Phoenix" renderer in `defpackage.bkdb` & co. (Java + GLES20); vector path `com.google.android.libraries.geo.mapcore.geoxp.jni.NativeMapController` (`:21,52-162`) — `nativeSetCameraView`, `nativeSetCameraAnimation`, `nativeRequestFrame` (`:122`), `nativeSetPaintParameters` (`:148`), `nativeAddOverlay`/`nativeReplaceOverlay` (`:60-120`), `nativeHitTestFeaturesAsync` (`:94`), `nativeGetProjectionSnapshot` (`:90`) |
| F3-A2 | The map **session lifecycle** is a native create/delete pair (factory → controller → view) | native boundary | `NativeMapFactory.java:105-109` (`nativeCreateMapFactory`, `nativeCreateMapControllerOwnedByView`, `nativeDeleteMapFactory`); config provider `MapCoreConfigProvider.java:67,70-95` (10-day config TTL at `:50`) |
| F3-A3 | **Tile fetching is native-driven**: native asks Java for a tile by URL/options, Java performs the HTTP request and returns bytes | APK-confirmed | `com/google/android/libraries/geo/mapcore/geoxp/jni/fetcher/NativeNetworkTileFetchHandler.java:60-120` (`:101-105` the HTTP call) |
| F3-A4 | **Ancestor-tile fallback is an explicit loop**: while `zoom ≥ minZoomForAncestorTileFetch`, try the ancestor; if it is `NOT_EXIST`, climb one more level | APK-confirmed | `defpackage/bkni.java:301-325` (`k(...)`); request descriptor field `minZoomForAncestorTileFetch` in `defpackage/bknd.java:16-26`; ancestor zoom selection by binary search over a per-style available-zoom table `defpackage/bjwg.java:24-39`; child enumeration `bjwg.java:42-63`; bulk prefetch `defpackage/bket.java:30-115` (zoom-17 threshold at `:42`) |
| F3-A5 | Tile identity uses a **fixed-point 2³⁰ Mercator** world coordinate | APK-confirmed | `defpackage/bkrh.java:20-29` — `e = x·(2³⁰>>z) − 2²⁹`, `f = −((2³⁰>>z)·(y+1)) − 2²⁹`; lat/lng→tile at `:35-60` |
| F3-A6 | The **GL/in-memory tile cache** is LRU with byte accounting and Trace counters | APK-confirmed | `defpackage/bkna.java:31` ("GLTileCacheManager"), `:121-137`, `:176-248`, `:396-425` (`Trace.setCounter("GLTileCacheManager - glDataSize (B)")` at `:69`) |
| F3-A7 | The **disk tile cache** stores tiles, tile metadata and **empty-tile records** (the storage-level "missing tile" marker), with expiry, LRU trim and style-table priority boost | native boundary | `NativeSqliteDiskCacheImpl.java:41-89` — `nativeSqliteDiskCacheGetTile`, `…InsertOrUpdateTile`, `…InsertOrUpdateEmptyTile`, `…GetTileMetadata`, `…DeleteExpired`, `…TrimToSize`, `…SetStyleTablePriorityBoostMillis` |
| F3-A8 | An **external read-only** tile cache is supported | APK-confirmed | `defpackage/bkwq.java:30-40` (log literal `"Fetching from ExternalReadOnlyTileCache failed for coords %s : %s"`) |
| F3-A9 | **Layers are an explicit, mutually exclusive set** | APK-confirmed | `defpackage/aisp.java:35-48` (`LayersControllerImpl`, exclusion matrix `:37-45`); layer enum `defpackage/chqc.java:8-60` (60+ entries: `GMM_VECTOR_BASE`, `GMM_VECTOR_TRAFFIC_V2`, `GMM_LABELS_ONLY`, `GMM_TRANSIT`, `GMM_SATELLITE`, `GMM_TERRAIN`, `GMM_BUILDING_3D`, `GMM_SEARCH_RESULTS`, …); map-type enum `bjgm` |
| F3-A10 | **Styles have explicit day/night twins** and a draw-mode matrix | APK-confirmed | `defpackage/chqr.java:4-45+` (`ROADMAP`/`ROADMAP_DARK`, `NAVIGATION`/`NAVIGATION_LOW_LIGHT`, `NAVIGATION_AMBIENT`/`…_DARK`, `NAVIGATION_TUNNEL`/`…_LOW_LIGHT`); draw-mode enum `defpackage/bktm.java:54-120` (`NO_MAP, ROADMAP, NAVIGATION, NAVIGATION_EGMM, NAVIGATION_AMBIENT, NAVIGATION_AMBIENT_TUNNEL, NAVIGATION_LOW_LIGHT, …`); `MapModeControllerImpl` `defpackage/bjkw.java:50-102` |
| F3-A11 | The **style table is per-zoom and interpolated**: 23 zoom levels (0–22), interpolating floats, ARGB colours and stroke arrays between the two bracketing zooms | APK-confirmed | `defpackage/bkqx.java:38-40` (zoom range), `:51-55` (float interpolation with a `2^z` weighting), `:60-70` (ARGB interpolation), `:72-104` (stroke-array interpolation), `:148-373` (lookup) |
| F3-A12 | Stroke attributes that differ across bracketing zooms are **logged as a mismatch** rather than silently chosen | APK-confirmed | `defpackage/bkqx.java:260-271` — `"lineStrokeRenderingAttributes mismatch for zoom: %d, and zoom: %d"`, `"areaStrokeRenderingAttributes mismatch for zoom: %d, and zoom: %d"` |
| F3-A13 | **Zoom-dependent width scaling** is explicit: `width_px = baseWidth · 2^(−(zoom − zoomRef)) · 1.25`, with a 0.25-zoom LOD quantum | APK-confirmed | `defpackage/bkob.java:277-303` (`:282` factor 1.25, `:285-296` LOD buckets, `:302` quantum) |
| F3-A14 | The renderer distinguishes **screen-space vs. world-space value encodings** | APK-confirmed | `defpackage/chov.java:5-15` — `SCREEN_SPACE_FLOAT(5)`, `WORLD_ENCODING_LAT_LNG_E7(3)`, `…_DOUBLE(4)`, `…_DRACO(7)`, terrain/surface variants (8–11); decoders registered in `defpackage/bkdg.java:79` |
| F3-A15 | **Label placement, text measurement and collision are native** | native boundary | `com/google/android/libraries/geo/mapcore/internal/label/jni/SharedLabelerJni.java` — `nativeAddLabelCandidates` (`:321` region), `nativeMeasureText(long,String,float,int,int,int,boolean)` (`:321`), `nativeUpdatePlacedLabels` (`:434`), `nativeSetStyle`, `nativeAddCalloutCandidate`, `nativeAddLabelingHint`, `nativeReportIconLayerSize`; label rasterisation via GLSL programs `LabelRenderer$LabelShaderProgram` / `LayeredLabelRenderer$LayeredLabelShaderProgram` (`:18-29`); config `NativeMapController.nativeSetLabelRestrictions` (`:138`), `nativeSetSelectedLabel` (`:152`) |
| F3-A16 | **Text halo is native** — no "halo" identifier exists in the map module (only in Material components) | native boundary | `grep -rni "halo" --include=*.java` over the map module → 0 hits; the halo is inside the native labeler |
| F3-A17 | The **frame-time debug overlay** is a GL shader program | APK-confirmed | `com/google/android/libraries/geo/mapcore/renderer/FrameTimeOverlay$FrameTimeOverlayShaderProgram.java:11-52` |
| F3-A18 | Resource-level day/night variants exist in the APK **in addition** to the map's own style system | APK-confirmed | `/tmp/opencode/gmaps-res/res/values-night*`, `color-night*`, `drawable-night*` |
| F3-A19 | The reference has **no grey "placeholder tile"** concept: a missing tile is served by ancestor fallback or an empty-tile record | APK-confirmed (absence) | `bkni.k` fallback loop; `nativeSqliteDiskCacheInsertOrUpdateEmptyTile`; no placeholder identifier in the map module |
| F3-A20 | Measured reference cartography (Runtime-observed, this program) | Runtime-observed | `/tmp/opencode/aud_gm_1.png` (z≈14), `aud_gm_2_z17.png` (z≈17), `aud_gm_3_z11.png` (z≈11): land `#f5f3f3`/`#f8f7f7`, urban building `#e8e9ed`, beige building `#e9dfc9` with dark edge `#9a9791`, water `#90daee`, park `#c8e1d8`/tree `#19865f`, major road `#d3d7db`–`#aeb9cb`, minor road fill `#cfd9e3` ≈21 px (7 dp @3×) with casing `#b7bdc7` ≈3 px (1 dp), pedestrian `#fdf9ef`; labels halo'd, bilingual, POI labels coloured per category |

---

## 3. RideTogether (worktree) — verified rendering implementation

| Concern | Implementation | Location | Assessment |
|---|---|---|---|
| Tiles | OSM standard raster via Coil `AsyncImage`, 256 dp slots, ceil/round layout to avoid seams | `RideMapCanvas.kt` tile grid | sound |
| **Ancestor fallback** | cached ancestors Δz 4..2 + always-requested parent Δz 1 + exact tile | `RideMapCanvas.kt` tile block | **matches the reference's approach** (F3-A4) — good |
| Tile range | hypot radius + 1 tile margin | `data/map/MapProvider.kt:197-234` | sound |
| Projection | continuous-zoom Web Mercator, 256 dp tile size | `MapProvider.kt` `TileMath` | sound; RT uses 2^z float tiles vs the reference's 2^30 fixed point (equivalent) |
| Day/night | a single draw-time `ColorMatrix` over the same raster; placeholder colour follows the lighting | `RideMapCanvas.kt` night matrix block | simple, consistent — but the reference has **style pairs and draw modes**, not a matrix |
| Layers | implicit Compose z-order (tiles → route → overlay → FABs) | `RideMapCanvas.kt` | no explicit registry (F11-D1) |
| Labels | `AmenityLabelPlanner` — zoom gates, hysteresis, priority, collision, icon avoidance | `ui/components/AmenityLabelPlanner.kt` | **RT-original and good**; the reference's is native (F3-A15) |
| Width scaling | **none** — RT's overlay strokes are constant dp | `RideMapCanvas.kt` | F3-D3 (route band) |
| Rendering invalidation | per-frame composition (camera state is composition state) | `RideMapCanvas.kt:256-311` | F3-D1 |
| Diagnostics | TEMPORARY RT-D7 logging still in the composition phase (must remain until D7 closes) | `RideMapCanvas.kt` D7 block | F3-D2 (see Feature 12) |
| Frame statistics | none in-app | — | F3-D3b |

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F3-D1 | P1 | **Per-frame recomposition of the tile layer** (up to 5 `AsyncImage`s per visible slot re-settled each frame) — the dominant frame cost after the diagnostics | worktree tile grid; F2-A21/A22 (the reference regulates frames and collects statistics) |
| F3-D2 | P1 | **Composition-phase diagnostics** (string building + 5 Coil cache probes per tile + 2 `Log.d` per frame) | worktree D7 block; measured: 186 log lines / 20 s, 9.78 % janky (Feature 12, E-F12-02) |
| F3-D3 | P2 | **No zoom-interpolated widths** for any overlay stroke (route band, marker sizes are zoom-stepped, strokes are not) | worktree; F3-A11/A13 |
| F3-D4 | P2 | **No explicit layer registry** — overlay order is implicit | worktree; F3-A9, F11-D1 |
| F3-D5 | P2 | **No in-app frame statistics / frame-time overlay** | worktree; F3-A17, F2-A22 |
| F3-D6 | P2 | **Day/night is a colour matrix**, not a style pair — the raster's own palette is not honoured (e.g. water becomes grey-blue, park loses hue) | worktree night matrix; F3-A10 |
| F3-D7 | P3 | No **empty-tile/missing-tile record** concept: RT paints a flat placeholder colour for a missing tile, which the reference avoids by always falling back to an ancestor | worktree placeholder; F3-A19 |
| F3-D8 | P3 | No **style-table mismatch diagnostics** (the reference logs when a stroke attribute differs across zoom levels) | F3-A12 |
| F3-D9 | P3 | The **cartographic palette** is OSM standard; the reference's measured palette (Feature 03 §2 F3-A20) is not reproducible without a different tile source or an RT-original style | F3-A20 — RT's tiles are a licensing-safe OSM standard raster; the palette is therefore **fixed by the provider**, and RT's only lever is the day/night matrix |

---

## 5. Proposed original implementation (not implemented)

- **S3-1 (P1)** — Move the tile-grid computation out of the composition phase into a derived/draw-phase state so the camera feed invalidates only the draw phase. (Same change as Feature 12's S12-2 but scoped to the tile layer.)
- **S3-2 (P1)** — Remove the D7 diagnostics **only after D7 is closed with a failing coordinate** (the mission forbids removing them earlier).
- **S3-3 (P2)** — Introduce an RT-original **style-token layer** (a small table of stroke widths, colours and text roles per zoom band) so widths can interpolate with zoom instead of being fixed constants. RT-original values, no reference palette.
- **S3-4 (P2)** — Add an explicit **layer registry** shared with Feature 11's overlay contract.
- **S3-5 (P2)** — In-app **frame statistics** behind a debug flag (frame count, over-budget count, p95) — the RT-original analogue of F3-A17/F2-A22.
- **S3-6 (P3)** — Track "missing tile" explicitly so a gap is not painted as a flat colour but as the best available ancestor (RT already fetches ancestors; the change is to *not* paint a placeholder over a slot that has an ancestor).
- **S3-7 (P3)** — Day/night: consider a second RT-original raster treatment instead of a single matrix (e.g. a desaturation + hue-preserving curve) — subject to a legibility measurement (Feature 08, RT-D8 note).

---

## 6. Tests to add / keep green

- Keep green: `MapTileRenderingAndRotationTest` (230 lines — tile coverage under rotation), `MapCameraControllerTest`, `AmenityLabelPlannerTest` (303), `AmenityRenderDensityTest`, `Task15ArrivalLatchTileFallbackAndSummaryTest`.
- NEW `TileFallbackChainTest` — the ancestor chain is ordered coarsest-first, is bounded by a minimum zoom, and every requested slot has a resolvable fallback (no unpainted gap).
- NEW `StyleTokenInterpolationTest` — widths interpolate monotonically between zoom bands and are clamped (pure math; no reference values copied).
- NEW `FrameStatsTest` — the statistics collector buckets frames and reports over-budget counts (pure).
- **Preserve the D7 probe protocol** (worktree report §5): the instrumented logging and the capture script stay untouched until D7 closes.

**Invariants affected:** none of #1–#8 are touched by S3-3…S3-7 (they are rendering-only); S3-1/S3-2 change *when* work happens, not what is drawn — both must be validated against the D7 reproduction protocol.
