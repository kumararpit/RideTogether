# Feature 07 — Route Planning & Multi-Stop Management

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324 (corpus + `/tmp/opencode/gmaps-res`).
**No application source, test, Gradle, schema, or build artifact was modified.**

---

## 1. Baseline finding — the worktree does **not** contain the multi-stop feature

| Artifact | Worktree (audit target) | Main repo (uncommitted WIP) |
|---|---|---|
| `RouteStop` / `RouteStopRole` model | **absent** | `app/src/main/java/com/example/data/model/RideModels.kt:145,156` |
| `RouteStopCodec` (merge guard) | **absent** | `app/src/main/java/com/example/data/model/RouteStopCodec.kt` |
| `RouteProvider` (routing seam) | **absent** | `app/src/main/java/com/example/data/data/map/RouteProvider.kt` → `data/map/RouteProvider.kt` |
| `NavEngine` multi-leg advancement | **absent** (319-line NavEngine) | present (385-line NavEngine, leg advancement + `currentLegIndex`/`legCount`) |
| `OsrmRoutingService` multi-waypoint | **absent** | present |
| `CreateRideScreen` stop editor | **absent** | present (add / reorder / remove / confirm gating) |

⇒ **Feature 7 in the worktree is a *greenfield* specification**, and the main repo holds an **uncommitted** implementation that must be reconciled (and not disturbed) before any implementation pass. This divergence is recorded in the master report and the checkpoint; the mission forbids touching the main repository, so nothing was changed there.

---

## 2. Google Maps APK evidence (add / remove / reorder stops, ordered summaries)

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F7-A1 | The reference exposes a "next step" instruction and a "then" instruction as **separate banner slots** (implying an ordered upcoming-step list) | APK-confirmed | `TurnCardNextStepInstructionView` / `TurnCardStepInstructionContainerLayout` / `step_container_next_step_full_width.xml` (F1-A5/A7) |
| F7-A2 | Turn-card layout variants exist for "solo step" vs "with next step" vs "full-width next step" ⇒ the reference switches card composition as the stop/step situation changes | APK-confirmed | `step_container_solo.xml`, `step_container.xml`, `step_container_next_step_full_width.xml` (F1-A7) |
| F7-A3 | A lane-guidance surface exists for multi-instruction steps | APK-confirmed | `TurnCardLaneGuidanceListView`, `step_instruction_lane_guidance_bottom.xml` (F1-A7) |
| F7-A4 | Waypoint/stop insertion, reordering, and route re-computation are implemented natively (guider re-creation) | Proprietary/unavailable | `RouteGuiderJni.nativeCreateGuider(byte[],byte[],byte[],long)` (F1-A1); native symbols `…LiveTripsGuiderJni…`, `…NavigationOrchestratorJniBridgeImpl…` (`/tmp/opencode/jni_symbols.txt`) |
| F7-A5 | Route request/response with multiple waypoints is a service-side contract | Proprietary/unavailable | No independent multi-waypoint protocol is recoverable; the only *available* multi-waypoint routing in the RT stack is OSRM (`/route/v1/driving/lon,lat;lon,lat;…`), verified live this session: `code: Ok`, `legs = 3` for 4 waypoints (`/tmp/opencode/osrm_mw.json`) |

**Implication:** the *behavioural* contract of ordered stops and ordered legs is reproducible with OSRM; Google's own stop-management UX, ordering heuristics, and error copy are **not** recoverable and must not be imitated — RT-original UX is required.

---

## 3. What OSRM gives and what it does not (independent stack)

*Live verification (this session, `router.project-osrm.org`):*
- 4-waypoint request → `code: Ok`, **3 legs**, leg distances 50.5 / 42.5 / 43.6 km, 2136 geometry points.
- Waypoint vertices land at polyline indices 0 / 774 / 1456 / 2135 ⇒ a leg boundary is a **vertex index** in the merged polyline.
- `steps` are **per-leg**; the flattened list must be concatenated in leg order, and step distances accumulate continuously across legs.
- `steps[0]` is a **`depart`** maneuver at the origin (0.0 m) — not a turn. Any banner logic that treats step 0 as "the first maneuver to perform" is immediately off (see Feature 01, F1-D1).

**Not provided by OSRM:** intermediate-stop semantics for *rider arrival* (RT must define it), plan versioning, or group-synchronised plan ownership. Those are RT-original designs.

---

## 4. Requirements for the worktree (specification only)

**R7-1 — Ordered waypoint contract.** A plan is an ordered list of stops (origin → intermediates → destination). The rendered route must remain one continuous road-following polyline; no synthetic connector between legs.

**R7-2 — Strict leg validation.** `legs.size` must equal `waypoints.size − 1`; a mismatch is an **explicit error**, never a silent fallback to a straight line.

**R7-3 — Plan identity and versioning.** The leader owns the plan; the plan carries a monotonic version. A member's stale snapshot must never overwrite a newer plan (merge guard keyed on the plan version, not on arrival order).

**R7-4 — Reroute preserves unvisited stops.** A reroute from the current location keeps the *remaining* stop sequence; the request is built as `current location → remaining stops → destination`, and the resulting leg count must match `remainingStops + 1`.

**R7-5 — Progression across legs.** At most **one** leg may complete per location fix, and completion requires an accuracy-reliable fix and a crossing/arrival test that cannot be satisfied by sparse vertex spacing. (Implemented in the main-repo WIP; must be ported to the worktree with its tests.)

**R7-6 — Selection integrity.** A stop is chosen from an explicit suggestion; editing the text invalidates the selection; coordinates are never guessed. (Already present in the worktree: `PlaceSearchEngine.retainedSelectionFor` requires an exact name match — `data/place/PlaceSearchEngine.kt:171-175`.)

**R7-7 — Locality-aware stop confirmation (NEW — see Feature 06).** A confirmed stop row must display its locality/address, and a stop implausibly far from the current route must raise a **warning** that the user can acknowledge — without silently changing coordinates and without prohibiting legitimate long-distance stops.

**R7-8 — Error surfacing.** Route failures (no route, leg mismatch, provider error) must be surfaced in the UI with a user-readable message; the current plan is retained.

**R7-9 — Persistence & recovery.** The plan must survive process death (see Feature 12 gap F12-D1) and be re-adopted by members.

---

## 5. Confirmed defects / gaps in the worktree

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F7-D1 | P1 (capability) | **Multi-stop planning is absent in the worktree** — only origin→destination routing exists (`OsrmRoutingService.fetchRoute(start, destination)`) | `data/location/OsrmRoutingService.kt` (170 lines, two-arg call sites) |
| F7-D2 | P1 | **Route geometry is not persisted to Firestore** — `updateRoute` writes only distances; `routePoints`/`navigationSteps` stay in memory ⇒ after process death the restored ride has no route (observed on device: "Follow Route / Heading toward destination", Remaining 0 m, no route line) | main repo `data/firebase/RideRepository.kt:1067-1085` (read-only inspection); device captures `av2`–`av11`, `1a_state.png` |
| F7-D3 | P1 | **Stop rows show the name only** in the main-repo stop editor ⇒ a distant Photon match is invisible until the estimate explodes (observed: "Pavana" → Italy ⇒ 18,066 km estimate, device `d8` capture) | main repo `CreateRideScreen` stop row (read-only inspection) + device capture `d8` |
| F7-D4 | P2 | No distance sanity check between consecutive stops or between a stop and the route | source review of the stop editor (read-only) |

---

## 6. Proposed original implementation (not implemented)

Port the main-repo WIP (`RouteStop`, `RouteStopRole`, `RouteStopCodec.mergeRoutePlan`, multi-waypoint `OsrmRoutingService`, leg advancement in `NavEngine`, stop editor in `CreateRideScreen`) into the worktree **as a reviewed change set**, and add:
- **S7-1** locality line + distance warning on confirmed stop rows (R7-7);
- **S7-2** persistence of route geometry (or a refetch-on-restore) to close F7-D2;
- **S7-3** explicit leg-mismatch error surfacing at the UI level.

---

## 7. Tests to add / keep green

- `MultiWaypointOsrmParsingTest` — ordered waypoints, `legs == waypoints − 1`, per-leg step flattening, leg-boundary vertex indices (main repo; port to worktree).
- `RouteStopCodecTest` — merge guard rejects stale plans, preserves stop order, handles empty/absent plans.
- `RideViewModelMultiWaypointTest` — create/join with stops; reroute preserves remaining stops; leg-count mismatch rejected.
- `NavEngineMultiLegTest` — one leg per fix; closely spaced stops never skipped; stale fixes never advance; off-route never advances.
- NEW `StopLocalityAwarenessTest` — a stop >X km from the current route raises a warning; coordinates are never silently altered; legitimate long-distance stops remain allowed.
- NEW `RouteGeometryRestoreTest` — after a process restart the ride restores with a usable route (closes F7-D2).

**Invariants affected:** #4 (multi-waypoint ordering, leg boundaries, remaining-stop behaviour) and #5 (plan versioning, leader/member ownership). All proposed changes must keep #7 (no silent coordinate replacement).
