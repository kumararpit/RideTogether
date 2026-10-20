# Feature 09 — Group Ride & Multi-Rider Map Experience

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324 — **no group-riding feature exists in the reference product**, so no reference behaviour is inferred here. Only *presentation patterns* that are observable in the reference (marker differentiation, selection emphasis, overview framing) are used as generic navigation-UX references, explicitly not as "Google group-riding capabilities".
**No application source, test, Gradle, schema, or build artifact was modified.**

---

## 1. Scope note (important)

The mission requires: *"Do not infer proprietary Google Maps group-riding capabilities from unrelated standard navigation features."* The reference APK contains **no** multi-rider group feature. Accordingly:

- No "Google Maps group mode" is claimed, referenced, or targeted.
- This document audits **RideTogether's own** group-ride map behaviour and identifies gaps, using only *generic* navigation-UX patterns (marker hierarchy, selection emphasis, overview framing) that are independently justifiable.

---

## 2. Reference-side presentation patterns (generic, not group features)

| ID | Pattern | Class | Evidence |
|---|---|---|---|
| F9-A1 | The active/selected location marker is visually distinct from background markers (blue dot + halo vs. plain POI pins) | Runtime-observed | `/tmp/opencode/aud_gm_7_routeprev.png`, `aud_gm_2_z17.png` |
| F9-A2 | Map controls stack in a single right-hand column with a consistent tap-target size (≈48 dp circles) | Runtime-observed | `aud_gm_10_nav.png` (compass, search, recenter, report buttons, ≈48 dp) |
| F9-A3 | Overview framing keeps the whole route visible with a primary route and lighter alternatives | Runtime-observed | `aud_gm_7_routeprev.png` |

---

## 3. RideTogether group-ride implementation (worktree) — source-verified

| Concern | Implementation | Location |
|---|---|---|
| Member model | `RiderMember(id, name, motorcycleModel, avatarColorHex, role, status, movementState, emergencyState, connectionStatus, location, speedKmh, headingDeg?, accuracyMeters, sequenceNumber, lastUpdatedMs, serverTimestampMs, …)` | `data/model/RideModels.kt:48-70` |
| Qualified heading in the member model | `headingDeg: Float?` — documented as *"Null must never be rendered as 'north' (0°)"* | `RideModels.kt:60-66` |
| Marker differentiation | fill colour by role/status (current user blue, leader crimson, stopped amber, offline slate, else avatar colour); white casing; dashed casing when disconnected/offline | `ui/components/RideMapCanvas.kt` rider block |
| Selection emphasis | extra white focus ring (`markerRadius + 8 dp`, 3.5 dp stroke) + the selected rider's name pill | same block |
| Leader identification | crown glyph above the marker + crimson fill | same block |
| Emergency visibility | pulsing radar ring (`infiniteRepeatable` 1200 ms) + red halo, driven by `MemberStatus.EMERGENCY` | same block |
| Group overview | "Fit Group" control → `cameraController.showOverview(allPoints, widthPx, heightPx, 100f)` over members + route | `RideMapCanvas.kt` fit-group control |
| Sync ownership | Firestore member documents; per-device SOS dismissal; server timestamps + sequence numbers for ordering | `data/firebase/RideRepository.kt` |
| SOS lifecycle | raise → acknowledge → resolve, with per-device dismissal and stale-snapshot protection | `RideRepository` (SOS section) |

---

## 4. Confirmed gaps (RideTogether-source-confirmed unless noted)

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F9-D1 | P1 | **Overlapping markers are not decluttered**: with N members on one straight road the discs overlap; the draw order is the member list order, so the *current user* can be hidden under another rider's disc and the tap target of a covered rider is unreachable. There is no clustering, no stacking badge, and no "selected rider raised to front" rule. | `RideMapCanvas.kt` rider block — no z-ordering/clustering logic; hit-test picks the **first** match in list order |
| F9-D2 | P1 | **Tap-target ambiguity**: the rider hit test breaks on the first member within the radius ⇒ with overlapping riders, the wrong rider can be selected; the selected rider is not prioritised. | same block (`for (member in members) { … break }`) |
| F9-D3 | P2 | **Stale/offline riders look nearly identical to live ones** beyond the dashed casing: no age indicator, no "last seen 5 min ago" text; a rider that dropped out 10 minutes ago still occupies a marker slot. | same block; `connectionStatus`/`lastUpdatedMs` are not surfaced in the marker |
| F9-D4 | P2 | **No relative-position information** between riders (distance to leader, bearing to leader) — the core question of group riding is not answerable from the map. | no such field in `RiderMember`; not rendered |
| F9-D5 | P2 | The emergency radar pulse runs on a global `rememberInfiniteTransition` for the whole map, so an emergency marker keeps animating even when the emergency is resolved but the member object is stale. | `RideMapCanvas.kt` radar transition block + `radarAlpha`/`radarRadiusFactor` applied per emergency rider |
| F9-D6 | P3 | Fit-Group overview does not reserve space for the control column / bottom HUD, so the fitted bounds can place riders under the FABs or the telemetry card. | `showOverview(allPoints, widthPx, heightPx, 100f)` — uniform 100 px padding, no inset awareness |
| F9-D7 | P3 | Member name pills are drawn for every rider (text measured per rider per frame) and can overlap each other. | rider block |

---

## 5. Proposed original implementation (not implemented)

- **S9-1 (P1) — Deterministic marker stacking.** When rider discs overlap, stack them with a small radial offset and a count badge, or select the nearest-to-camera. *Original algorithm:* cluster by screen-space proximity, render the cluster badge, expand on tap. Deterministic ordering (stable by member id) so it never flickers.
- **S9-2 (P1) — Selection priority in hit testing.** Test the *selected* rider first, then the nearest, then the rest; a covered rider stays reachable.
- **S9-3 (P2) — Freshness disclosure.** Show "last seen" age for riders whose `lastUpdatedMs` is older than a threshold; use a distinct, muted style for offline riders (beyond the dashed casing).
- **S9-4 (P2) — Relative position for the selected rider** (distance + relative bearing to the leader) as an optional HUD line.
- **S9-5 (P3) — Inset-aware overview framing** so fitted bounds avoid the control column and bottom card.

---

## 6. Tests to add / keep green

- NEW `RiderMarkerClusterTest` — pure geometry: overlapping discs within a threshold form one cluster; cluster membership is order-independent; the selected rider is always individually addressable.
- NEW `RiderHitTestPriorityTest` — selection priority order; a covered rider is reachable; the previous "first match wins" behaviour is intentionally replaced.
- NEW `RiderFreshnessTest` — age buckets → marker state mapping.
- NEW `RiderLabelCacheTest` — label layout reuse.
- Keep green: `Task17RiderMarkerSmoothingTest`, `NavigationTelemetryRegressionTest`, `NavEngineAndSosTest`, `SosLifecycleAndDeliveryTest`, `SosAckDeliveryAndDismissalTest`, `RideCompletionAndChatSyncTest`.

**Invariants affected:** #5 (Firestore data ownership, rider sync policy, SOS lifecycle) — the proposed changes are **render-layer only** and must not alter ownership, ordering rules, or the SOS state machine.
