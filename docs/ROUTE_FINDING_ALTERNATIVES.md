# Route alternatives + per-stop itinerary

**Workstream:** `feature/route-finding-alternatives` (branched from `db5d7ce`)
**Feature:** the group picks one of several candidate routes for an ordered
stop plan, and every screen shows the plan leg by leg.

---

## 1. What the reference implementation does

Evidence from Google Maps 26.42.01.997557324 (`googlemap.apk`,
SHA-256 `6218d72f48b184eb71f68879bce48cb9ebb2b510be7f7cff88217ea3fd592bb2`),
collected with REA (JADX headless) and by mining the shipped DEX/resources.
Full evidence ledger: `docs/reverse-engineering/google-maps-feature-enhancements.md`.

| Layer | Reference behaviour | Evidence |
|---|---|---|
| Request | Route requests are **Compute Routes** requests, serialized to Google's internal **"Tactile" Directions** proto by native code | `DirectionsRequestTranslation.nativeTactileRequestJniBytesFromComputeRoutesRequest(byte[],byte[]):byte[]`; `DirectionsResponseTranslation.nativeComputeRoutesResponseJniBytesFromTactileResponse(...)`; each has exactly one Java caller (`defpackage.bmxc.a`, `defpackage.bmxe.apply`) |
| Model | `RouteLeg` → `RouteStep`, geometry as **encoded polyline**, with `waypointIndex` / `stepIndex` / `polylineIndex` | DEX tokens |
| Waypoints | First-class client-side model with its own store and lifecycle: `WaypointsStore`, `WaypointUpdate`, `waypointInfoList`, `refineWaypoints`, `addWaypointToRoute`, `removeWaypointFromRoute`, `removeNextWaypointFromRoute`, `WaypointsHaveChangedInNav`, `DirectionsSwapWaypointsClicked` | DEX tokens |
| Alternatives | Managed by the **guidance engine**, not by a visible request flag: `ActiveRouteMonitorJni.nativeOnAlternateSelected`, `nativeOnDroveOntoAlternate`; UI callouts `ALTERNATIVE_CALLOUT_LESS_TRAFFIC_LABEL` / `…MORE_TRAFFIC_LABEL`, `"Alternative Route, %1$s"`, `"Best route"` | REA class inspection + `resources.arsc` |
| Guidance | Route bytes → native guider; each location fix produces guidance state: `RouteGuiderJni.nativeCreateGuider(byte[],byte[],byte[],long)`, `nativeOnLocationChanged(long[],int,byte[],boolean):byte[]`, `nativeGetRemainingMetersToNextDestination(long)` | REA class inspection |
| Public model | Closed 48-value maneuver vocabulary (`Maneuver.TYPE_*`), `Step` = maneuver + cue + road + lanes, `RoutingInfo` = **current + next step**, `TravelEstimate` = remaining distance/time with **decoupled colours** | `androidx.car.app.navigation.model.*` |

**Not recoverable:** the routing algorithm itself, the Tactile wire schema,
maneuver selection, traffic-aware ETA and the two-wheeler profile are native or
server-side. No claim is made about them.

**Negative finding:** this build contains **no waypoint-optimization parameter**
(`optimizeWaypoints`, `routeAvoidance`, `routingPreference` → 0 hits across all
ten DEX files). The reference does not reorder the rider's stops.

---

## 2. What this feature implements (and what it does not)

| Reference capability | Status here | Note |
|---|---|---|
| Several candidate routes for one ordered plan | **implemented** | `alternatives=true` on the routing engine request |
| A "best route" preset | **implemented** | the fastest candidate is labelled `BEST ROUTE` |
| Leader-owned choice shared with the group | **implemented** | selection + candidate list travel inside the route plan, guarded by the plan version |
| Per-leg itinerary (leg distance, duration, maneuvers) | **implemented** | new `RouteItineraryCard` |
| Encoded-polyline geometry | **implemented** | `PolylineCodec` (the same encoding the reference carries) |
| Exact leg boundaries | **implemented** | cumulative-leg-distance mapping (`LegBoundaryMath`) |
| Traffic-aware alternatives / ETA | **not implemented** | proprietary service |
| Two-wheeler profile, avoid tolls/highways | **not implemented** | the public routing engine rejects `exclude=` (`InvalidValue`) and ships only the `driving` profile; needs a self-hosted engine |
| Waypoint editing during navigation | **not implemented** | next slice (add / remove / swap stops mid-ride) |
| Closed maneuver vocabulary (48 types) | **not implemented** | next slice; today the app keeps the engine's own step strings |

---

## 3. Design

### 3.1 Candidates

`OsrmRoutingService.fetchRouteOptions(...)` asks the engine for alternatives and
returns every route in the response. Each candidate is a **complete** route:
its own geometry, legs and steps. The engine's own ranking stays first
(candidate 0 = primary).

`RouteProvider` gained a default `fetchRouteOptions` that returns exactly one
candidate, so every existing provider (and every test fake) stays compatible.

### 3.2 Exact leg boundaries

The leg boundary used to be found by a nearest-point search over the merged
overview polyline. That is ambiguous whenever a waypoint sits near a hairpin or
a duplicated service road: the nearest vertex may belong to a different pass of
the road, which moves the arrival test off the stop.

`LegBoundaryMath` derives the boundary from the **cumulative leg distance** the
engine reports, then snaps to the vertex closest to the waypoint inside a tight
window around that distance. The nearest-point search remains as a fallback for
responses whose leg distances do not reconcile with the geometry.

### 3.3 The plan is one unit

The ordered stops, the candidate list and the leader's selected candidate are
published together on the ride document and travel together through the
version guard (`RoutePlanCodec.merge`): a stale snapshot can never pair new
stops with an old selection.

```
routeStops        : ordered [{ id, name, address?, role, lat, lng, order }]
routePlanVersion  : Long   (monotonic; incremented on every plan change)
selectedRouteIndex : Int    (the leader's chosen candidate)
routeOptions      : [{ index, distanceMeters, durationSeconds }]
```

The plan version is only bumped when the plan actually changes, so repeated
fetches of the same plan do not churn the shared document.

### 3.4 Authority

Only the ride leader may change the plan. The selection is enforced in the app
(the ride's leader member entry is the authority) **and** in the Firestore
rules: the four plan fields are leader-only, enforced with
`request.resource.data.diff(resource.data).affectedKeys()` so that a member's
distance telemetry on a document that already carries the plan stays allowed.

Members adopt the leader's selection when they fetch the route for the shared
plan, so every rider follows the same road.

---

## 4. Files

| File | Change |
|---|---|
| `data/location/PolylineCodec.kt` | **new** — encoded polyline decoder/encoder |
| `data/location/LegBoundaryMath.kt` | **new** — exact leg-boundary geometry |
| `data/location/OsrmRoutingService.kt` | alternatives request, multi-route parsing, polyline geometry, cumulative leg boundaries |
| `data/location/RouteProvider.kt` | `fetchRouteOptions` with a single-candidate default |
| `data/model/RideModels.kt` | `RouteOption`, `RouteOptionSummary`, `Ride.selectedRouteIndex`, `Ride.routeOptions` |
| `data/model/RoutePlanCodec.kt` | **new** — plan serialization + version guard carrying the selection |
| `data/model/RouteItinerary.kt` | **new** — pure itinerary derivation (one leg per stop pair) |
| `data/firebase/RideRepository.kt` | publish/read the plan fields; snapshot merge carries the selection |
| `data/firebase/SosDeliveryBackend.kt` | **new** — offline seam for the SOS write path (see §7) |
| `ui/viewmodel/RideViewModel.kt` | candidate state, leader-only selection, plan publication, remaining-leg distance |
| `ui/components/RouteOptionSelector.kt` | **new** — candidate list with the "best route" preset |
| `ui/components/RouteItineraryCard.kt` | **new** — stop-by-stop itinerary |
| `ui/screens/CreateRideScreen.kt` | preview the candidates, pick one, publish it with the ride |
| `ui/screens/RideLobbyScreen.kt` | show the candidate list (leader-selectable) and the itinerary |
| `ui/screens/LiveRideScreen.kt` | next-stop strip (which stop, how far, which leg) |
| `firestore.rules` | plan fields are leader-only |
| `scripts/firestore-rules-test.js` | rules coverage for the plan fields |

---

## 5. Tests

| Suite | Covers |
|---|---|
| `PolylineCodecTest` | documented reference vectors, round trip, corrupted payload degrades instead of throwing |
| `LegBoundaryIndexTest` | cumulative boundaries, monotonicity, the hairpin case, inconsistent input |
| `OsrmAlternativesParsingTest` | every candidate parsed independently, strict leg contract per candidate, encoded-polyline geometry, single-route response, `NoRoute` handling |
| `RoutePlanMergeTest` | newer plan adopted in full, older rejected, equal version keeps local state, candidate summaries round trip |
| `RouteCandidateSelectionTest` | candidate list published with the plan, selection swaps route + navigation state, out-of-range selection clamped |
| `scripts/firestore-rules-test.js` | member cannot change the plan, leader can, telemetry still allowed (needs the local emulator) |

---

## 6. Honest limits

- The engine's alternatives are **static** (no traffic) and are often absent:
  a long rural pair returned a single candidate while a dense city pair
  returned two. The feature is therefore never required to create a ride.
- Avoid-tolls/highways and the two-wheeler profile need a self-hosted routing
  engine; the public one rejects `exclude=`.
- The candidate list is a summary in the shared document; the geometry stays
  per-device (each member fetches the route for the selected candidate).

---

## 7. Side fix: the offline suite was not hermetic

While verifying this feature the full suite turned out to **hang** — not
because of this feature, but because the SOS suites drove the real Firestore
client inside Robolectric. The write never completed in a sandbox, so
`runBlocking` waited forever and the suite never finished (it was already
recorded as defect F12-D3 in the program's audit, and the worktree had fixed
only one of the affected files).

Fix: a narrow **delivery seam** (`SosDeliveryBackend`). Production keeps the
Firestore write path unchanged — the seam is null in production. The offline
suites inject an in-memory backend, so the SOS lifecycle (trigger, idempotency,
acknowledgment, resolution, rollback on a failed delivery) is exercised
completely offline, with the same "the call either completes or throws"
contract the Firestore path has.

Two contracts are preserved deliberately and are covered by tests:

- with **no** backend at all, `triggerSos` fails explicitly and leaves no local
  alert, and `resolveSosInternal` fails without clearing the local state;
- with a backend that throws, the trigger rolls the local alert back and
  restores the rider status.

