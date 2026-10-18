# Map Day/Night Styling (Task 18, revised: light/dark only)

> Revision: the interim dusk tier was removed per product feedback. The map only
> switches between **light (Day)** and **dark (Night)**; there is no grey/dusk state.

## What the map actually renders

RideTogether renders **standard OSM raster carto tiles** (`a/b/c.tile.openstreetmap.org`)
through its own Compose canvas. There is no separate night tile source, and no tile
provider change was made (no API keys, no paid services).

- **DAY** — the native OSM carto imagery, unfiltered. Standard carto is already a
  bright daytime style, so no extra tint is applied.
- **NIGHT** — a draw-time color matrix transformation (the pre-existing "dark
  tactical" matrix: `R' = 215 − 0.80·R` etc.).

This is a documented **renderer-level appearance approximation**, not a true dark
cartographic tile set. The transformation is applied at draw time (`AsyncImage`
`colorFilter`), so Coil caches only raw bitmaps — no cross-style cache contamination,
no cache clearing on theme changes.

## Root cause of the previously inconsistent dark/light tiles

1. Tile slot placeholders (`Box` behind every tile) were **always `SlateDark800`**
   regardless of style: in the light style, loading/failed tiles appeared as dark
   squares among light tiles. Placeholders now follow the resolved lighting
   (`MapAppearancePalette.tilePlaceholder`).
2. The style toggle was **ephemeral per-canvas state** (`remember { true }`): it reset
   on re-entry and was not shared across screens, producing inconsistent appearance.
   The single owner is now `MapThemeController` in `RideViewModel`.

## Daylight calculation

`data/map/MapDayNightTheme.kt` — pure, Android-free, testable:

- NOAA low-precision solar position with the Bennett refraction correction,
  computed from epoch millis (no `java.time`; minSdk 24 has no desugaring).
- Classification threshold (light/dark only): **DAY ≥ 0°** apparent elevation,
  **NIGHT < 0°** — the map is dark on the pre-dawn/post-sunset side of the horizon;
  there is no twilight band. Validated against Pune 2024-10-15 reference times
  (sunrise ≈ 06:31 IST, sunset ≈ 18:14 IST) within ±35 min tolerance, and against
  polar cases (Tromsø midnight sun, Longyearbyen midwinter).
- No horizon crossing within a 24 h scan ⇒ `POLAR_DAY` / `POLAR_NIGHT` (a **daily
  recheck** anchored to the next local midnight is scheduled instead).
- Invalid coordinates ⇒ `INDETERMINATE` ⇒ documented fallback.

## Location priority and timezone handling (AUTO mode)

1. Valid current rider fix (bounds, accuracy ≤ 500 m, age ≤ 10 min, not `(0,0)`),
   fed from the ViewModel members flow.
2. Map camera center — reported by the canvas on **material change only** (≥ 0.1°,
   ≈ 11 km; snapshotFlow bucketing prevents per-frame churn).
3. Route origin, else destination.
4. Local-clock fallback using the **device timezone** (DAY 07:00–18:59 local) when
   no geography is available.

For a geographic source, the civil time zone is approximated as the
**longitude band** `round(lng / 15°)` — solar time rather than political borders
(no tz-database boundary data is bundled, and DST is not modeled; documented
approximation). The day/night edge is therefore evaluated on the solar day at the
map location, and the local-date fingerprint (epoch day at that longitude band)
makes date rollover trigger a re-evaluation even for polar windows.

Material-change dedupe means theme **cannot flip on every GPS update**; camera
context changes below 0.1° are ignored.

## Transitions and lifecycle

A single `MapThemeController` (`RideViewModel`-scoped) recomputes when the mode,
material context, lifecycle resume, local date, or the **next sunrise/sunset**
changes. Exactly one delayed coroutine is scheduled for the next horizon crossing;
nothing polls, no background service, no dedicated GPS listeners, no per-frame
computation, and no recompute during drawing.

## Manual override

The style button on the live-ride map cycles **AUTO → DAY → NIGHT** (indicated by
icon tint: AUTO = cyan, DAY = amber, NIGHT = white). There is no pre-existing
persisted map-appearance preference, so AUTO is the default and overrides are
session-scoped. Lobby/summary previews keep the legacy dark-default appearance and
their local dark/light toggle.
## Amenity hierarchy and labels

- Marker contrast: dark outer rim (`#101828` at ~40%) + bright inner stroke, larger
  glyph (`1.25 × pin radius`), existing teardrop geometry preserved (hit-testing
  centers unchanged).
- Labels (`AmenityLabelPlanner`, pure map-free logic): zoom-gated
  (selected ≥ 12.2, fuel/repair ≥ 14.2, all ≥ 15.6, 0.25 zoom hysteresis),
  priority order (selected → category importance → stable list order), fixed-budget
  screen-space collision suppression (≤ 124×22 px boxes, max 16 labels/frame),
  ellipsized single-line text on theme-aware pills (contrast ≈ 11:1 day, ≈ 12.6:1
  night per documented fg/bg pairs). Geographic coordinates are never modified;
  labels draw before rider markers so they can never obscure the current rider or
  navigation indicators.
- Only named amenities get labels; icons always render (subject to zoom tiers).

## Verification

- `:app:testDebugUnitTest --rerun` → **BUILD SUCCESSFUL, 219 tests, 0 failures**
  (190 pre-existing + 29 new: `DaylightCalculatorTest`, `MapThemeControllerTest`,
  `AmenityLabelPlannerTest`; light/dark-only thresholds asserted).
- `:app:assembleDebug` → BUILD SUCCESSFUL; `:app:lintDebug` → BUILD SUCCESSFUL
  (0 errors).
- Visual/physical-device behavior (theme switching on hardware, tile appearance at
  all zoom levels, dense clusters on a real screen) **was not verified on a device**
  in this task and remains user-acceptance work.
