# Feature 10 — Traffic, Road Conditions & Route Alternatives

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324.
**No application source, test, Gradle, schema, or build artifact was modified. No proprietary endpoint was called; proprietary service addresses are documented as boundaries only.**

---

## 1. Verdict

**Traffic is a proprietary-data feature; route alternatives are a proprietary-service feature. Neither is reproducible with RideTogether's current stack.** What *is* reproducible, and what the reference demonstrates architecturally, is the **presentation layer**: a traffic-styled route line, a delay badge, an alternative-route callout, and an incident taxonomy — all of which can be driven by an independent congestion source.

This document separates the five evidence classes the mission requires.

---

## 2. (i) Directly observed UI affordances (APK-confirmed strings/views the user can see)

| Affordance | Evidence |
|---|---|
| Traffic map layer in the layers menu | id `layers_menu_traffic_layer` (2131428679) → `res/values/ids.xml`; bound in `defpackage/aitg.java:49`; layer kind `aitf.TRAFFIC` (`defpackage/aitf.java:5-16`, with the "selectable/offline-capable" flag) |
| "All traffic" | `strings.xml:503` `ALL_TRAFFIC` |
| Delay badges **Heavy / Normal / Light traffic** | `HEAVY_TRAFFIC` 2132020774, `NORMAL_TRAFFIC` 2132022720, `LIGHT_TRAFFIC` 2132021340; produced by `defpackage/vrc.java:1130-1142` (maps `citv` → label) |
| ETA with traffic: "%1$s in current traffic" | `strings.xml:2572` `ETA_IN_CURRENT_TRAFFIC` (2132020281) |
| Alternative-route comparison: **"Less traffic" / "More traffic"** | `strings.xml:505-506` `ALTERNATIVE_CALLOUT_LESS_TRAFFIC_LABEL` / `..._MORE_TRAFFIC_LABEL`; produced by `defpackage/yav.java:229-265` from `cipt` ordinals (`TYPE_TRAFFIC_CONGESTION_LESS(4)`, `_EVEN_LESS(7)`, `_MORE(3)`, `_EVEN_MORE(8)`, `_SIMILAR(6)`) |
| "Expect delays" | `strings.xml:2883` `GENERIC_DELAY_SUBTITLE` |
| Traffic **incident cards** (crash, speed camera, road closure, …) | `defpackage/sbk.java:190,196,355,366,372,399,405` broadcast actions `TRAFFIC_REPORT`, `ROAD_CLOSURE`, `MODERATABLE_TRAFFIC_INCIDENT`, `SUSPECTED_ROAD_CLOSURE_REROUTE`, `TRAFFIC_INCIDENT`, `REPORTED_ROAD_CLOSED`, `SPOKEN_TRAFFIC_INCIDENT`; card art paths under `//maps.gstatic.com/mapfiles/traffic/svg/…` (`sbk.java:30,254`) — **artwork is proprietary and must not be copied** |
| "Report incident" control | id `nav_report_incident_fab_button` (2131429031) |
| Nearby-Traffic home-screen widget | `com/google/android/apps/gmm/widget/traffic/TrafficWidgetProvider.java:66-92`; strings `traffic_widget_label`="Nearby Traffic", `traffic_widget_description`="See the current traffic near you" |
| Traffic-trend a11y narration | `strings.xml:225-229` `ACCESSIBILITY_TRAFFIC_TREND_BAR_CHART*` ("Traffic predictions. This trip takes %1$s now, with %2$s. %3$s"; "Heavy/Light/Usual traffic") |
| Voice commands for traffic | `defpackage/qvv.java:10` — `show_traffic`, `hide_traffic`, `traffic_report` |

---

## 3. (ii) Decompiled implementation evidence (present in the Java/Kotlin layer)

| Component | Evidence |
|---|---|
| Traffic payload model | `defpackage/citz.java:7-19` — `c` = list of `cisz` events (offset seconds + type), `d` = timestamp, `e` = bool, `f/g/h/i/k` = lists of `cirw`, `j` = `cium`, `l` = `city` |
| Traffic **stretch** model (type + start/end index range along the route) | `defpackage/ryi.java:33-35` — `"BaseTrafficStretch(type=…, start=…, end=…)"` |
| Traffic **route-line style** enum | `defpackage/citw.java:5-8` — `UNKNOWN_STYLE(0)`, `SLOWER_TRAFFIC(1)`, `TRAFFIC_JAM(2)`; name mapping `defpackage/bmee.java:29-44` |
| Route-segment style taxonomy (incl. incident-driven styles) | `defpackage/ahdy.java:36-90` — `HEAVY_TRAFFIC(7)`, `JAMCIDENT_ROAD_STRETCH_SLOWER_TRAFFIC(19)`, `JAMCIDENT_ROAD_STRETCH_TRAFFIC_JAM(20)`, `JAMCIDENT_VISUALIZATION_JAM_STRETCH(21)`, `HOV(18)`, `FLYOVER(15)`, `UNPAVED_ROAD(16)`, `ICY_ROAD(17)`, `LOW_DATA_QUALITY_ZONE(13)`, `*_RESTRICTION` |
| Delay bucketing | `defpackage/citv.java:5-9` — `DELAY_NODATA(0)`, `DELAY_HEAVY(1)`, `DELAY_MEDIUM(2)`, `DELAY_LIGHT(3)`; stored on the route (`yct.S`) |
| Alternative-vs-traffic comparison model | `defpackage/cipt.java:5-14` (traffic-congestion comparison, tolls, fuel consumption) |
| Incident taxonomy (30+ types) | `defpackage/ciqp.java:5-44` — `INCIDENT_ROAD_CLOSED(1)`, `INCIDENT_CRASH(2)`, `INCIDENT_CONSTRUCTION(3)`, `INCIDENT_JAM(4)`, `INCIDENT_SPEED_TRAP(5)`, `INCIDENT_SPEED_CAMERA(6)`, `INCIDENT_LANE_CLOSURE(10)`, `INCIDENT_STALLED_VEHICLE(11)`, `INCIDENT_HEAVY_RAIN(36)`, `INCIDENT_FOG(24)`, `INCIDENT_SNOW(23)`, `INCIDENT_FLOOD(25)`, `INCIDENT_LANDSLIDE(32)`, `INCIDENT_THUNDERSTORM(35)`, `INCIDENT_CYCLONE(33)`, `INCIDENT_EARTHQUAKE(34)`, `INCIDENT_SUSPECTED_CLOSURE(9)`, `INCIDENT_OTHER(127)` |
| Traffic → native guidance engine | `RouteGuiderJni.d(citz,int,int)` → `nativeUpdateTrafficData(j, citz.toByteArray(), i, i2, 0)`; `null` ⇒ `nativeClearTrafficData` (`.../navcore/guider/jni/RouteGuiderJni.java:37,76-84`) |
| Traffic → camera | `CameraControllerJni.java:476-477,738-762` rebuilds the traffic payload (`cgcf.k`) — traffic influences camera behaviour too |
| Traffic conditions fetched by a Java worker | `defpackage/aagp.java:55-140` — `EnRouteNotificationWorker`; log literal `"GetTrafficConditions rpc request skipped as last location is stale by %d seconds. SKIPPING."` (`:123`); staleness window 2 min (`:31`), request window 5 min (`:30`); failure literal `defpackage/aago.java:34-35` |
| Traffic tiles are fetched natively from a Java-supplied store | `NativeOnlineTileFetcherTileStoreWrapperImpl.java:26-48` |
| Reroute **causes** | `defpackage/cjqs.java:5-9` — `REROUTE_TYPE_BETTER_ETA(1)`, `REROUTE_TYPE_AVOIDS_CLOSURE(2)`, `REROUTE_TYPE_AVOIDS_VEHICLE_RESTRICTION(3)`, `REROUTE_TYPE_AVOIDS_DYNAMIC_CLOSURE(4)` |
| Reroute **request modes** | `defpackage/cine.java:5-13` — `REROUTE_FROM_NEW_LOCATION(2)`, `REROUTE_AND_ALTERNATES_FROM_NEW_LOCATION(5)`, `TRIP_UPDATE_AND_ALTERNATES(7)`, `OFFLINE_TRIP_UPDATE_AND_ALTERNATES(8)` |
| Navigation event taxonomy | `defpackage/bxwv.java:5-11` — `MANEUVER_TYPE_UNKNOWN`, `COMPLIED`, `REROUTE`, `ALTERNATE_ACCEPTED`, `ALTERNATE_SELECTED`, `ALTERNATE_DRIVEN_INTO` |
| "Route changed" event grouping | `defpackage/bcun.java:35`, `bcuo.java:9` — `REROUTE || ALTERNATE_SELECTED || ALTERNATE_DRIVEN_INTO` are one event class |
| Live Trips (continuous, destination-less guidance) | `LiveTripsGuiderJni.java:36-113` (all-native), `LiveTripsService.java:52-97` (foreground service, status blob with `status/eta/duration/distance/progress`) |

---

## 4. (iii) Publicly documented concepts present in the corpus

- `strings.xml:2872` `GEMINI_NAV_ALERT_ENTRY_POINT_PROMPT` — "…Make sure to tell them which route is faster and compare the available routes." (an LLM prompt describing alternative comparison).
- `strings.xml:2849` `FUEL_CONSUMPTION_BASED_ON_HILLS_TRAFFIC_SPEEDS` — fuel-efficiency-aware routing concept.
- `strings.xml:2841` `FREE_NAV_DRIVING_DETECTED_NOTIFICATION_MESSAGE` — "Tap to get traffic updates".

---

## 5. (iv) Features requiring proprietary live data — **DO NOT CALL**

| Feature | Proprietary boundary (documented, never invoked) |
|---|---|
| Live traffic conditions | `google.internal.mothership.maps.mobilemaps.inferreddirections.v1.MobileMapsInferredDirectionsService/GetTrafficConditions` — `defpackage/aywn.java:30` (request `cdze`, response `cdzl`) |
| Route computation **and alternatives** | `google.internal.mothership.maps.mobilemaps.directions.v1.MobileMapsDirectionsService/GetDirections` — `defpackage/ayvx.java:30` (request `cpyw`, response `cpyx`) |
| Guidance/traffic **tiles** | `google.internal.geo.navtiles.v1.GeoNavTilesService/GetTiles` + `/GetConfig`; native `SnaptileStore$Provider` |
| Traffic-aware ETA | `citz` → `nativeUpdateTrafficData`; per-step durations via `defpackage/ahuc.java:17-32` |
| "Better route" prompt | `REROUTE_TYPE_BETTER_ETA` needs multi-route ETAs from the directions backend |
| Legacy endpoints | `https://mobilemaps.googleapis.com/glm/mmap`, `https://clients4.google.com/glm/mmap` — `defpackage/aysi.java:11-36` |

**These are recorded as architectural boundaries only. No endpoint was called, no scraping was performed, and no stale OSM data is presented as live traffic.**

---

## 6. (v) Implementable independently with open data

| Capability | Why it is independent | RT feasibility |
|---|---|---|
| **Traffic-styled route line** | `citw` / `ahdy` are plain style enums; any congestion source can drive them | P3 — needs a congestion source (e.g. an open feed); the *renderer* side is a small addition |
| **Delay badge** (none/light/normal/heavy) | `citv` + three label strings are generic | P3 — same dependency |
| **Alternative routes + "Less/More traffic" callout** | needs ≥2 routes with comparable ETAs/congestion | P3 — OSRM returns **one** route per request; a second request is possible but comparison/cost is unproven |
| **Incident markers / closure banners** | `ciqp` is a plain int enum; the *presentation* is generic | P3 — source-dependent; do not ship without a data source |
| **Traffic layer toggle** | `aitf.TRAFFIC` → style id → renderer slot is generic plumbing | P3 |
| **Route-line traffic colouring from live probes** | the reference's own traffic data comes from tiles; an RT implementation could use OSM `maxspeed`/`type` heuristics — but that is **not live traffic** and must be labelled as such | Not recommended without an honest label |

**Offline precedent (important):** the APK itself contains a full **offline routing stack** (`OfflineRoutingControllerImpl`, `OfflineReroutingControllerImpl`, `SnaptileTileSourceFactory`, `RoutingCompositeTileTileSourceFactory` — JNI symbols) proving the rendering layer is separable from an online backend. RT's OSRM/OSM stack is the equivalent of that separation.

---

## 7. RideTogether status (worktree)

- **No traffic feature exists** in the worktree (no traffic model, no traffic layer, no incident taxonomy).
- Route requests go to **OSRM** (`data/location/OsrmRoutingService.kt`), which returns a single route with optional alternatives via a separate `alternatives=true` parameter — **not currently used**; the service exposes `fetchRoute(start, destination)` only.
- Reroute exists and is sound (adaptive off-route → `rerouteFromCurrentLocation`, cooldown, single-flight) — see Feature 12.
- OSM tiles carry `maxspeed`/`type` attributes that are **not** currently used for styling.

---

## 8. Prioritised recommendations (specification only)

| Priority | Item | Rationale |
|---|---|---|
| **P3** | Keep traffic out of scope for now | No independent, licensed, live congestion source is available in this stack; presenting stale OSM data as traffic would be misleading |
| **P3** | If a source is adopted later: implement the **presentation contract first** (delay enum, style enum, callout model) so the data source is swappable — mirroring the reference's separation of `citv`/`citw`/`cipt` from the transport | Keeps the renderer independent of any single provider |
| **P3** | Alternative routes: prototype an OSRM `alternatives=true` request and measure request cost/latency before designing any UI | Cost/feasibility unknown; must not be assumed |
| **P1** (adjacent, already covered) | Reroute **cause** reporting — RT currently reroutes on off-route only; the reference distinguishes closure/restriction/better-ETA causes | RT can at least surface *why* it rerouted (off-route) — low value alone |
| **P2** | Do **not** copy `ciqp`/`citw`/`ahdy` enum values or Google's card art; if an incident feature is ever built, use RT-original categories and artwork | Proprietary restriction |

---

## 9. Unverified / not found

- Actual traffic data values, tile bytes, incident records (no samples in the corpus).
- Server-side behaviour: which routes the backend returns, how many alternatives, the traffic prediction model, ETA-with-traffic computation, "better route" trigger heuristics.
- The `LiveTrips` request/response schema (`cgec`/`cgfd`) and its server counterpart.
- Which `ahdy`/`chmf`/`chix` style value maps to which drawable/brush (resolved at runtime by the style service).
- Runtime observation of the traffic layer: the reference's traffic layer requires the proprietary backend and the device session was not configured for it ⇒ **not runtime-verified**.
