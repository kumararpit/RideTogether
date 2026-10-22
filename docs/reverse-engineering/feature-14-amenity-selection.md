# Feature 14 — Map rendering, route UI and amenity selection

**Subject:** Google Maps `26.42.01.997557324`
(`com.google.android.apps.maps`, SHA-256 `6218d72f…`), on the rider's device
(RMX3360, 1080×2400, 3.0× density).
**Date:** 2026-10-22. **Method:** runtime observation of the reference app on
the device + static resource inspection (`res/values/strings.xml`,
`res/values/public.xml`) + the RideTogether source.
**Status:** investigated **and implemented** (see §5).

---

## 1. How the reference renders the map and the route

### 1.1 The map surface

| Element | Behaviour | Evidence |
|---|---|---|
| Base map | A raster carto style with a **declutter ladder**: at z≈14–17 the POIs are individual category markers with labels; at z≈11 the POIs are **removed entirely** — the map is not a field of pins, it is a map with a few markers that earn their place | `gm_02_map.png` (z≈14, POI-dense), `gm_08_zoom.png` (z≈14, Koregaon Park), `gm_09_cluster.png` (z≈11, no POI at all) |
| POI markers | **Category-coloured round pins with a white glyph** (orange restaurant with a fork-and-knife, magenta hotel with a bed, blue bus stop) and a **name label beside them** in the same category colour | `gm_02_map.png`, `gm_08_zoom.png` |
| Selection | A tapped place opens a **place sheet**; the place stays visible on the map and is shown with a **photo preview card** in the corner | `gm_04_poi.png` |
| Route line | A wide dark-blue line with a lighter core, drawn **under** the pins and **over** the base map, with a **duration pill on the line** ("8 min") | `gm_05_directions.png` |
| Route endpoints | A **red destination pin** and the **blue/white current-location dot** | `gm_05_directions.png` |

### 1.2 The route UI

| Element | Behaviour | Evidence |
|---|---|---|
| Route preview | A bottom sheet with the **travel-mode chips** — Drive 8 min / **Motorcycle 7 min** / Transit 11 min / Walk 17 min — i.e. the reference has a **two-wheeler profile** | `gm_05_directions.png` |
| Route summary | "**8 min (2.5 km)**" + a qualifier ("Fastest route, despite the usual traffic") + a difficulty chip ("Easy") | `gm_05_directions.png` |
| Route actions | **Start**, **Add stops**, **Share** | `gm_05_directions.png` |
| Turn card | A compact top card with a **duration pill**, an **instruction**, a **"Then:" secondary line** and a street-name label on the map | `gm_05_directions.png` (callout) and the master report §11 (turn card) |

### 1.3 The amenity (place) selection — the part this feature implements

The reference's selection flow is a **state machine** over the place sheet:

| Step | What the rider sees | Evidence |
|---|---|---|
| 1. Tap a place on the map | The **place sheet**: name (large, multi-line), rating + review count, category + attributes + price, open status, a photo card, and an action row — **Directions** (primary), **Start**, **Ask**, **Save** | `gm_04_poi.png` |
| 2. Press **Directions** | The place becomes the **destination of a route**; the route preview opens with the place's red pin on the map | `gm_05_directions.png` |
| 3. Press **Add stops** | The sheet "**Add stops to your route**" — "Find coffee, parks & more", a "**Search along route**" field, **category chips** (Petrol pumps, Restaurants, Coffee shops, Grocery stores) and a list of candidates, each card carrying a **detour badge ("+2 min", "+1 min")** | `gm_06_addstops.png` |
| 4. Same sheet, on the map | The **map markers themselves carry the detour** — "Desi Dhaba **+1 min** · Punjabi", "Hotel New Ruchira **+1 min** · Vegetarian" | `gm_06_addstops.png` |
| 5. Tap a candidate | The **place sheet in add-stop mode**: the same card, but the primary action is now literally "**Add stop**" (with a waypoint-plus icon) instead of "Directions" | `gm_07_added.png` |

Three behaviours matter and are not obvious:

1. **The primary action of a place sheet is contextual.** "Directions" for a
   place that is not on a route; "Add stop" for a place that is a candidate
   stop of the route that is on screen. The same sheet, a different verb.
2. **A stop has a price, and the price is shown before the stop is taken.**
   The detour ("+1 min") is attached to the candidate *and* to its marker on
   the map, so the rider sees the cost of a stop before committing to it.
3. **Selecting a place is not the same as acting on it.** Tapping a place
   opens a sheet; the ride is only changed when the rider presses the action.

Static confirmation: the reference ships the string `ADD_STOP` = "Add stop"
(`res/values/strings.xml`), plus the waypoint-editing ids
(`edit_waypoints_done_button`, `a11y_action_drag_waypoint_up` /
`a11y_action_drag_waypoint_down`) — the waypoint list of a route can be
reordered by dragging.

---

## 2. The RideTogether gap (before this change)

| ID | Gap | Where |
|---|---|---|
| F14-D1 | **A tap resolves to the wrong place.** The hit test walked the amenity list in *data* order, so a marker drawn under another could steal the tap | `RideMapCanvas.kt` hit-test loop |
| F14-D2 | **A cluster cannot be tapped at all.** Only the drawn representatives were in the hit-test table, so the cluster badge was dead space | `RideMapCanvas.kt` hit-test loop |
| F14-D3 | **No cluster expansion.** Tapping a cluster could never reveal the places inside it | `AmenityClustering` |
| F14-D4 | **A selection does not read.** The other markers stayed fully opaque, so the selected pin did not stand out | `RideMapCanvas.kt` amenity draw |
| F14-D5 | **A selected place could not become a stop of the ride.** The sheet offered "Navigate Here", which **handed the ride over to Google Maps** — a violation of the independent-stack invariant, and it abandoned the group, the plan and the telemetry | `AmenityDetailsSheet.kt` (`google.navigation:` intent) |
| F14-D6 | **No cost was ever shown.** Adding a place could silently make the ride longer, and the rider had no way to know | — |

---

## 3. What was implemented

### 3.1 A selection model that answers the tap (`ui/map/AmenitySelection.kt`)

Pure, testable, and derived from the reference behaviour:

* **Topmost wins.** The tap is resolved against the markers **as they are
  drawn**, from the top of the draw order downwards — the marker the rider
  sees is the one that answers (F14-D1).
* **A cluster expands, it does not select.** Tapping a cluster answers with
  the ids of its members (F14-D2, F14-D3).
* **A selection dims the rest** to 40 % so the selection reads at a glance
  while the surroundings stay visible (F14-D4).

### 3.2 Cluster expansion (`AmenityClustering`)

An expanded cluster stays open: its members are excluded from the grouping
and are drawn as single markers, so the cluster does not re-form on the next
frame while the rider looks at it.

### 3.3 A place becomes a stop of the ride (`AmenityStopPlan`, `RideViewModel`)

This is the core of the feature — the RideTogether equivalent of the
reference's "Add stop":

* the place is inserted into the plan as the **last intermediate stop**, just
  before the final destination (the detour of the last slot is the smallest
  one that does not reorder the stops the group already agreed on);
* the route is **re-planned from the rider's position** through the new stop;
* the **detour is derived from the two route results the app already has** —
  the ride that was left compared with the ride that is left now — so the
  cost of the stop is exact and costs no extra service call;
* the plan is published as **one versioned unit** (stops + candidate list +
  selection) through the existing leader-owned path, and the announcement
  suppression of the old route is cleared (a new route has new maneuvers).

The plan is leader-owned, exactly like every other plan change, so a member is
told who can change the route instead of silently failing.

### 3.4 The sheet (`AmenityDetailsSheet`)

The sheet now mirrors the reference's place sheet: the place, its category,
its **distance from the rider**, the primary action, and the explanation. The
primary action is **"Add as a stop"** — the in-app action — and the
Google Maps hand-off is **gone** (F14-D5). A secondary "Show on map" action
opens the place in whatever map app the rider has, without taking the ride
away from RideTogether.

---

## 4. What is **not** copied

* No Google place card, artwork, icon or wording — the sheet is
  RideTogether's own.
* No Google search, ranking, reviews, opening hours or "Ask" surface: those
  are server-side products, not behaviours.
* The two-wheeler routing profile the reference offers (`Motorcycle 7 min`)
  is a routing-engine capability RideTogether's engine does not provide.

---

## 5. Verification

| Check | Result |
|---|---|
| `AmenitySelectionTest` | 7 tests — topmost-wins, cluster expansion, empty tap, touch target, dimming, expanded members never re-merge |
| `AmenityStopPlanTest` | 6 tests — stop order, origin/destination invariants, no duplicates, detour arithmetic, detour wording |
| Full offline suite | **60 classes / 507 tests / 0 failures / 0 errors / 0 skipped** |
| On-device rendering (RMX3360) | `AmenitySelectionSheetDeviceRenderTest` — the sheet renders with the category badge, the name, the distance chip, the amber **"Add as a stop"** action and the explanation; **no Google Maps hand-off**. Capture: `/tmp/opencode/amenity_shots/amenity_sheet.png` |
| On-device interaction | `AmenityTapSelectionDeviceTest` — the map is rendered with a synthetic amenity set and the taps are performed on the device: a tap on a marker selects that place, a tap on a cluster expands it |
| Live ride, real data | **Blocked by the environment**: the Overpass endpoint is currently not answering (a 40 s POST to `overpass-api.de/api/interpreter` times out from the host as well), so the live map has no places to select. The seeded-cache path used in earlier sessions is not available in this window either. |

---

## 6. Open items

| Item | Note |
|---|---|
| Detour **before** committing | the reference shows the detour on the candidate cards and on the markers *before* the rider commits. RideTogether can only show it after the re-plan (the detour is the difference between two routes). Showing it beforehand would need a second route request per candidate. |
| Member suggestion | a member cannot change the plan; a "suggest this stop to the leader" action would need a message type that does not exist yet. |
| Relevance weighting | the detour now gives the rider a *cost* signal; a *relevance* signal (a fuel stop before a long leg) is still open (backlog S5-1). |
| Selection across layers | a selected place and a selected rider currently exclude each other; the reference keeps both selectable. |
