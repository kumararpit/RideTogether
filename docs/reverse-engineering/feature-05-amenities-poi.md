# Feature 05 — Amenities, POIs & Map Markers

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324 (as a *generic* POI-presentation reference only — Google's POI assets and categories are **not** to be copied).
**No application source, test, Gradle, schema, or build artifact was modified.**

---

## 1. Summary

RideTogether's amenity pipeline is **complete and device-validated** (Overpass → Room → cache-first → canvas markers → deterministic label planning). It is also the pipeline with the **hardest external dependency** in the app: the Overpass endpoint was degraded/unavailable for a large part of the last program window, which the cache-first design absorbed.

The gaps are in **marker semantics and density behaviour**: a category colour set that does not read well on the OSM raster, no distance-based prioritisation of navigation-relevant amenities, and no notion of "selected" beyond a ring.

---

## 2. Reference evidence (generic presentation patterns only)

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F5-A1 | POI markers in the reference are **category tiles with a coloured label**, not generic pins: e.g. a magenta rounded-square lodging badge, an orange restaurant pin with a fork-and-knife glyph | Runtime-observed | `/tmp/opencode/aud_gm_2_z17.png` (z≈17), `aud_gm_1.png` (z≈14) |
| F5-A2 | The reference renders **two-tier POI labels**: the name in the category colour plus an optional secondary line ("Top rated") in a smaller, greyer style | Runtime-observed | `aud_gm_2_z17.png` — "Seven heaven PG LLP" + "Top rated"; "Street 2 Furniture…" with a trailing subtitle |
| F5-A3 | The reference's **label collision and icon priority are native** (label placement, measurement, collision, icon-layer reservations) | native boundary | `SharedLabelerJni` natives `nativeAddLabelCandidates`, `nativeMeasureText`, `nativeUpdatePlacedLabels`, `nativeAddCalloutCandidate`, `nativeReportIconLayerSize` (Feature 03, F3-A15) |
| F5-A4 | The reference has a **declared overlay z-order** that places POI labels and markers explicitly relative to each other (e.g. `PLACEMARK_LABELS(22)`, `PLACEMARK(23)`, `CALLOUT_LABEL_OVER_PLACEMARKS(24)`, `FRIEND_ACCURACY_CIRCLE(18)`, `FRIEND_CLUSTER(20)`) | APK-confirmed | `defpackage/blga.java:36-88,95-108` |
| F5-A5 | The reference renders **clusters** for dense point sets (`VISUAL_EXPLORE_CLUSTER(17)`, `FRIEND_CLUSTER(20)`) | APK-confirmed | same enum; the clustering algorithm is native/unavailable |
| F5-A6 | The reference's map supports a **"search results" layer** distinct from base POIs (`GMM_SEARCH_RESULTS`, `SEARCH_RESULT_ICONS`, `SEARCH_RESULT_MEASLES`) | APK-confirmed | `defpackage/chqc.java:8-60`; `blga.java` overlay enum |
| F5-A7 | The reference **scales icon containers by zoom** and reserves icon-layer space for labels | Decompiled-inference | `SharedLabelerJni.nativeReportIconLayerSize` (icon layer size reported to the labeler); marker size resources `lighthouse_primary_far_marker_icon_size` 32 dp / `_far_marker_size` 15 dp (Feature 01 G-5) |
| F5-A8 | The reference maps **~100 place types to icon assets** with day/night/region/ad variants, plus a coarser **category → 10-group** taxonomy (OUTDOORS, FOOD, EMERGENCY, LODGING, GENERIC, SERVICES, RETAIL, ENTERTAINMENT, TRANSIT) mapped to a "vertical" | APK-confirmed | `defpackage/bxhz.java:20-121` (type→icon table), `:125-133` (icon URL template `icon/name=assets/icons/search-experiment/…&highlight=ffffff,<hex>,ffffff&scale=4`), `:135-146,178-186` (lookup by `SearchResult.TYPE_<TYPE>[.<region>][.Ad]`); `defpackage/mpa.java:11-21` (120-entry category map) → `defpackage/moz.java:20-41` (10 groups) → `defpackage/mpa.java:22-29` (vertical mapping) |
| F5-A9 | A **70+ type categorical-search enum** exists (restaurants, coffee, hotels, EV charging, pharmacies, …) | APK-confirmed | `defpackage/cbjt.java:6-77` |
| F5-A10 | **POI clustering / decluttering and label collision are entirely native**; the Java layer only *creates candidates* and *consumes the outcome* | native boundary | `SharedLabelerJni` — `nativeCreateLabeler(byte[])` (`:229`; config `chve` incl. `chve.x = 300` px icon-layer size), `nativeAddLabelCandidates(long,byte[],byte[])` (`:227`), `nativeRunTasks(long,boolean)` (`:791`), `nativeUpdatePlacedLabels(byte[])` (`:434`, Java-side callback), `nativeMeasureText(long,String,float,int,int,int,boolean)` (`:321`); Java-visible outcome enum `defpackage/bkin.java:17-32` — `ALL_VISIBLE, PRIMARY_SECONDARY_VISIBLE, PRIMARY_TERTIARY_VISIBLE, ONLY_PRIMARY_VISIBLE, REPRESSED, PLACED_INVISIBLE, TRUMPED_PROMOTED`; counters `numLabelsCreated` / `numLabelsPlaced` (`:604,637`); candidate factory `defpackage/bkij.java:30-83` (one body failed decompilation, 809 instructions) |
| F5-A11 | The reference's Java-drawn POI bubble (an **AR** place pin) shows a **title** (`?textAppearanceSubhead2`) and a **secondary line** (`?textAppearanceCaption`), 12 dp padding, 12 dp corner radius, 24 dp arrow base, 12 dp arrow, 200 dp texture width, 2 dp label stroke | APK-confirmed (resource values) | `res/layout/primary_poi_label_layout.xml`; `res/values/dimens.xml:519-525` (`label_arrow_base_width` 24 dp, `label_arrow_length` 12 dp, `label_corner_radius` 12 dp, `label_horizontal_padding` 12 dp, `label_stroke_width` 2 dp, `label_texture_width` 200 dp, `label_vertical_padding` 12 dp); `res/values/colors.xml:1264` `primary_background_color` = `google_blue600`; inflated by `defpackage/mkt.java:37,69-107` |
| F5-A12 | POI distances are formatted with an explicit **imperial/metric mode** and unit abbreviations | APK-confirmed | `defpackage/awrk.java:18-29,94-96,148-197`; strings `DA_DISTANCE_FORMAT_MODE` (2132019533), `DA_DISTANCE_FORMAT_FEET_ABBREVIATED` (2132019525), `DA_DISTANCE_FORMAT_METERS_ABBREVIATED` (2132019529) |

*No Google POI icon, category colour, or artwork is reproduced here; the RT-original equivalents are specified in §5.*

---

## 3. RideTogether (worktree) — verified pipeline

| Stage | Implementation | Location | Assessment |
|---|---|---|---|
| Query | Overpass QL per viewport, 13 categories | `data/amenities/OverpassApiClient.kt` / service | bounded, POST + form body, UA/Accept headers, 60 s timeout |
| Resilience | bounded retry + failure classification + offline probe | `data/amenities/OverpassResilience.kt` | sound |
| Scheduling | serial, conflated viewport worker | `data/amenities/AmenityViewportWorker.kt` | sound (no request storms) |
| Cache | Room; **cache-first** reads; versioned query keys; in-flight dedup; transactional batch writes | `data/amenities/AmenityRepository.kt`, `AmenityDao.kt` | **sound and device-validated** |
| Markers | teardrop pin, zoom-adaptive 20/24/28/32 dp, selected 38 dp, dual rim (dark + white), white category glyph, drop shadow | `RideMapCanvas.kt` amenity block | good; category palette is the weak point |
| Categories | 10 types with fixed colours + Material icons | `ui/components/AmenityIconConfig.kt` | F5-D2 |
| Labels | `AmenityLabelPlanner` — per-type zoom gates, one-frame hysteresis, priority (selected → category → previously visible → list order), collision boxes, **icon-avoidance** (a label may never cover another marker's painted footprint) | `ui/components/AmenityLabelPlanner.kt` | **strong, RT-original** — matches the reference's *intent* (icon priority over label coverage) while remaining fully testable |
| Density cap | `AmenityRenderDensityTest` pins the cap | `app/src/test/…/AmenityRenderDensityTest.kt` | sound |
| Device evidence | 406 seeded amenity rows + 41 `14_v2_*` cache marks injected; seeded cluster rendered **offline** (Overpass down) with anchored labels; live fetches later succeeded at **39–48 s** latency, bounded to 16 cells | main report §7b; captures `av5`–`av10` | confirmed |

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F5-D1 | P1 | **No distance/priority weighting for navigation-relevant amenities.** A fuel station 3 km away and a scenic viewpoint 3 km away are treated identically; the planner's priority is *category* rank only, not relevance-to-the-rider. | worktree `AmenityLabelPlanner.kt` priority order (selected → category rank → hysteresis → list order) |
| F5-D2 | P2 | **The category palette does not read on the OSM raster.** Colours `#1976D2` (fuel), `#E65100` (cafe), `#388E3C` (repair), `#7B1FA2` (rest stop), `#D81B60` (store), `#00796B` (hotel), `#5D4037` (restaurant), `#455A64` (toilet), `#512DA8` (viewpoint) — several are close in luminance/hue to the raster's land/water tones, and the dual dark+white rim is heavy at 20–24 dp. | worktree `AmenityIconConfig.kt:19-32`; measured raster palette (Feature 03 F3-A20) |
| F5-D3 | P2 | **No POI clustering** for dense viewports: the planner caps labels, but the *markers* are all drawn, so a dense area becomes a pin field (the reference clusters: F5-A5). | worktree amenity draw loop (no clustering) |
| F5-D4 | P2 | **No "selected" semantics beyond a ring**: selecting an amenity does not raise it, does not suppress the other markers, and does not change its hit priority. | worktree amenity draw block |
| F5-D5 | P2 | **No amenity distance shown to the rider** (neither in the label nor on selection) — the rider cannot judge relevance. | worktree |
| F5-D6 | P3 | **The rest-stop category uses a parking icon** (`Icons.Default.LocalParking`) while its display name is "Rest Stop" (motorcycle roadside) — semantic mismatch. | `AmenityIconConfig.kt:39` |
| F5-D7 | P3 | **The label width is estimated, not measured** (7 dp/char + 14 dp, clamped), so long names may reserve a box wider than the rendered pill. | worktree `RideMapCanvas.kt` estimatedFullWidthPx block |
| F5-D8 | P3 | Marker allocation per frame (a teardrop `Path` per pin per frame) | worktree amenity draw block |

---

## 5. Proposed original implementation (not implemented)

- **S5-1 (P1) — Relevance-aware amenity priority.** Extend the planner's priority with a *distance* term (distance from the rider, or from the route corridor) and an explicit "navigation-relevant" class (fuel, repair, rest stop) that outranks purely scenic POIs when close. RT-original model; no reference data.
- **S5-2 (P2) — An RT-original category palette** chosen against the OSM raster's measured palette (Feature 03 F3-A20): pick 10 hues with a minimum luminance separation from land/water/road tones, and verify each on a day and a night capture.
- **S5-3 (P2) — Marker clustering** for dense viewports: screen-space clustering with a count badge, deterministic (stable by OSM id) so pins do not flicker between frames. *Original algorithm* (the reference's is native).
- **S5-4 (P2) — Selection semantics**: raise the selected marker above the others, dim the rest slightly, and give the selected marker hit priority.
- **S5-5 (P2) — Show the distance** to the rider on the selected amenity (and optionally in its label when it is the only visible one).
- **S5-6 (P3)** — Fix the rest-stop icon; measure the label width instead of estimating it; cache marker paths.

---

## 6. Tests to add / keep green

- Keep green: `AmenityLabelPlannerTest` (303 lines), `AmenityRenderDensityTest`, `AmenityIconConfigTest`, `AmenityRepositoryCacheTest`, `AmenityRepositoryTest`, `AmenityViewportWorkerTest`, `OverpassResilienceTest`, `OverpassQueryBuilderTest`, `OverpassClientTimeoutTest`.
- NEW `AmenityRelevancePriorityTest` — a nearer fuel station outranks a farther viewpoint; a selected amenity always wins; the previous category-only ordering is preserved as the tie-break.
- NEW `AmenityPaletteContrastTest` — each category colour clears a minimum luminance/chroma separation against the measured raster palette (day and night).
- NEW `AmenityClusterTest` — clustering is deterministic and order-independent; the count badge is correct; no cluster flickers between frames (same input ⇒ same output).
- NEW `AmenitySelectionTest` — the selected marker is drawn last, and hit-testing resolves to it.

**Invariants affected:** none of #1–#8 (rendering only). The amenity **data** contract (cache-first, versioned keys, in-flight dedup, rate limits) must remain exactly as-is.
