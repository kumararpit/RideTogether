# Feature 01 — Turn-by-Turn Guidance, Next-Turn Banner & Maneuver Distance

**Audit type:** read-only. **Target:** `/home/arpit/Projects/RideTogether-google-maps-enhancements` (branch `work/google-maps-feature-enhancements`, HEAD `ef60bff`).
**Reference:** Google Maps `26.42.01.997557324` — corpus `/tmp/opencode/gmaps-src`, resources `/tmp/opencode/gmaps-res`, native symbols `/tmp/opencode/jni_symbols.txt`.
**No application source, test, Gradle, schema, or build artifact was modified by this feature audit.**

---

## 1. Summary

| | Google Maps 26.42 | RideTogether (worktree HEAD) | Verdict |
|---|---|---|---|
| Maneuver selection | **native** (`nativeOnLocationChanged` → protobuf guidance state) | Java/Kotlin `NavEngine` | Different architecture; RT's is testable |
| Banner primary line | road/maneuver context being travelled towards | the **last performed** maneuver | **F1-D1** |
| Banner distance | not in the banner in the captured state | distance to the **next** maneuver, paired with the **previous** maneuver's text | **F1-D1 (off-by-one)** |
| Secondary line | "Then …" | "Then: …" = the maneuver the distance actually refers to | **F1-D1** |
| Voice | native, not inspectable | index-based one-shot suppression, "In N meters, …" | **F1-D3** on reroute |
| Turn advancement | native | unguarded `while` loop, straight-line 25 m test, not accuracy-gated | **F1-D2** |

**Three confirmed defects, one of them safety-relevant (P0).**

---

## 2. Google Maps APK evidence

| ID | Claim | Class | Evidence (file · class · method) | Notes / limitations |
|---|---|---|---|---|
| F1-A1 | The navigation engine is native; the Java layer only consumes its output | APK-confirmed | `sources/com/google/android/libraries/geo/navcore/guider/jni/RouteGuiderJni.java` — `nativeCreateGuider(byte[],byte[],byte[],long)`, `nativeOnLocationChanged(long[] jArr,int i,byte[] bArr,boolean z) → byte[]`, `nativeBuildTripGuidanceState(long) → cgce`, `nativeGetSecondsToNextDestination`, `nativeGetRemainingMetersToNextDestination`, `nativeUpdateTrafficData`, `nativeDeleteGuider` | Constructor is a JADX failure ("Method not decompiled … instructions count: 1676") — recorded. **The maneuver-selection and distance algorithms are not recoverable.** |
| F1-A2 | Guidance state is a protobuf with list fields for steps | APK-confirmed | `sources/defpackage/cgce.java` (extends `cmrf`) — ints `b,c,d,e,f`, bools `g,h,i,s`, ints `j,k,n,o`, doubles `l,m`, lists `p,q` (`cgbq`), `r` (`cgcf`), `t` (`cgbz`); `newMessageInfo` descriptor string encodes field numbers/types | Field **names** obfuscated ⇒ structure only, not semantics |
| F1-A3 | The next-turn distance is rendered with **two typographic tiers** (number large, unit small) and re-renders only on content change | APK-confirmed | `sources/com/google/android/libraries/geo/navcore/turncard/views/TurnCardStepDistanceTextView.java` — model `blmu`; `spannableString.setSpan(new AbsoluteSizeSpan(bmcmVar.a), 0, length, 0)` + `StyleSpan(bmcmVar.b)` for the number, `AbsoluteSizeSpan(bmcmVar.f)` + `StyleSpan(bmcmVar.g)` for the unit; `setVisibility(8)` when empty; re-render guard `!blmuVar.equals(this.a) \|\| this.e` | Sizes come from a themable bundle (`bmck.a().a().k`) |
| F1-A4 | The distance model is an immutable value with real content equality | APK-confirmed | `sources/defpackage/blmu.java` — `(String value, String unit, String, int)`, `equals`/`hashCode` over all four fields | |
| F1-A5 | The next-step line is a **maneuver drawable (30 dp) inlined with the instruction text** | APK-confirmed | `sources/com/google/android/libraries/geo/navcore/turncard/views/TurnCardNextStepInstructionView.java` — `this.b = 30; this.c = 30;`, `biwj.c(resources, blne, …)`, `aiop.c(drawable, w, h, " ")` | |
| F1-A6 | The "cue" (street-name) line is built from a model list with inline drawables scaled 1.2× and duplicate cue names collapsed | APK-confirmed | `sources/com/google/android/libraries/geo/navcore/turncard/views/TurnCardStepCueTextView.java` — `bncs(drawable, 1.2f)`, `HashSet`/`HashMap` dedup | |
| F1-A7 | The turn card is a **family of layouts** (solo step / with-next-step / full-width next step / single- & multi-line / lane guidance / long step) | APK-confirmed | `res/layout*/`: `adaptive_turn_card_current_step_layout.xml`, `step_container.xml`, `step_container_solo.xml`, `step_container_next_step_full_width.xml`, `step_instruction_content_default_single_line.xml`, `step_instruction_content_default_multi_line.xml`, `step_instruction_content_small_layout_multi_line.xml`, `step_instruction_lane_guidance_bottom.xml`, `navigation_stepcueview_internal.xml`, `long_step_instruction_content.xml` | The **selection rule** between variants is native ⇒ unavailable |
| F1-A13 | The reference has an explicit **maneuver-type taxonomy** (24 kinds) | APK-confirmed | `defpackage/cbev.java:5-36` — `DEPART, NAME_CHANGE, STRAIGHT, LANE_CHANGE, TURN, COUNTED_TURN, UTURN, ON_RAMP, OFF_RAMP, FORK, MERGE, FERRY, FERRY_TRAIN, EASTER_EGG, ROUNDABOUT_ENTER, ROUNDABOUT_EXIT, ROUNDABOUT_ENTER_AND_EXIT, PEDESTRIAN_*, DESTINATION, ERROR` |
| F1-A14 | The reference distinguishes **"complied", "reroute", "alternate accepted/selected/driven-into"** as separate navigation events | APK-confirmed | `defpackage/bxwv.java:5-11` — `MANEUVER_TYPE_UNKNOWN, COMPLIED, REROUTE, ALTERNATE_ACCEPTED, ALTERNATE_SELECTED, ALTERNATE_DRIVEN_INTO`; grouped as one "route changed" class in `defpackage/bcun.java:35` / `bcuo.java:9` |
| F1-A15 | The reference's route model carries a **step index** and the route-set carries a **selectedIndex** (with a bounds check) | Decompiled-inference (high) | `defpackage/yct.java:22-88` (`.j` = legs/steps array + step index), `defpackage/ycw.java:15-44` (log literal `"selectedIndex %d not in loaded RouteList, which has size %d."`) |
| F1-A8 | View-to-resource mapping of the banner | APK-confirmed | `sources/defpackage/biwi.java` + `res/values/public.xml`: `distance_text` (2131428162) → `TurnCardStepDistanceTextView`; `maneuver_image` (2131428833) → `TurnCardStepManeuverImageView`; `bottom_cue_text` (2131427720) → `TurnCardStepCueTextView`; `distance_cue_separator_text` (2131428161); `step_cue_container` (2131430128); `step_instruction_container_outline` (2131430135) | |
| F1-A9 | The secondary instruction format is literally "Then …" | APK-confirmed | `res/values/strings.xml`: `TURN_CARD_THEN` = `"Then {0}"`, `TURN_CARD_THEN_INSTRUCTION` = `"Then %1$s"` | |
| F1-A10 | Instruction text generation and lane guidance are native | APK-confirmed (native boundary) | `sources/com/google/android/libraries/geo/shared/client/guidance/modelstranslator/jni/NativeTripTranslatorJni.java`; `sources/com/google/android/libraries/geo/guidance/jni/NativeHelper.java`; `jni_symbols.txt`: `Java_com_google_android_apps_gmm_directions_laneaware_NativeLaneAwarePolylineInterpolator_nativeInitClass` | **No algorithm may be inferred** |
| F1-A11 | The voice/announcement layer is **absent from the Java corpus** | Unavailable | corpus-wide search of `sources/com/google/android/apps/gmm/**` for `TextToSpeech`, `synthesizeToFile`, `QUEUE_ADD`, `announce*` → no matches; `jni_symbols.txt` has no `voice`/`speech`/`tts` group | The reference's announcement thresholds / duplicate suppression are **unavailable** |
| F1-A12 | Runtime banner composition | Runtime-observed | `/tmp/opencode/aud_gm_10_nav.png` — device fbe12520 (RMX3360, 1080×2400 @3×), Google Maps 26.42.01, 2026-10-19 15:29, nav mode (route "Your location → Lonavala", 56 min / 52 km) | Dark-teal card `#015f61`; primary "towards **Hanuman Temple** Rd" (name band ≈59 px cap-height ≈19.7 dp, "Rd" suffix ≈42 px ≈14 dp ⇒ two-tier hierarchy); attached secondary card "Then ↱"; blue next-street pill anchored at the maneuver point; trip bar "56 min · 52 km · 16:25". **The distance is not in the banner in this state.** The near-turn state was **not captured** (stationary device) ⇒ the reference's distance↔instruction pairing is **not directly observable** |

---

## 3. RideTogether (worktree) call path — source-verified

```
LocationTracker → LocationUpdate(latLng, speedKmh, headingDeg, accuracyMeters)
 └─ RideViewModel.updateNavigationProgress(locUpdate)                      RideViewModel.kt:428
     ├─ NavEngine.processLocation(locUpdate) → NavigationProgress          NavEngine.kt:81
     │   ├─ arrival latch (≤35 m to destination or ≤25 m remaining)        NavEngine.kt:95-114
     │   ├─ off-route: distanceToPolyline > 120 m (accuracy ≤30 m)         NavEngine.kt:117-128
     │   ├─ closestIdx: forward window [currentSeg, +60], max(currentSeg,…) NavEngine.kt:130-154
     │   ├─ maneuver advancement (while loop; straight-line ≤25 m)          NavEngine.kt:162-173
     │   ├─ distanceToNextManeuver = polyline(closestIdx → stepVertex[i+1]) NavEngine.kt:176-187
     │   └─ ETA: EMA speed (≥8 km/h) else route average                   NavEngine.kt:189-…
     ├─ VOICE speakManeuver(currentStep.instruction, navProg.currentManeuverIndex,
     │                      navProg.distanceToNextManeuverMeters)          RideViewModel.kt:432-441
     │        └─ suppressed when maneuverIndex ≤ lastSpokenManeuverIndex   NavAudioGuidance.kt:117-119
     └─ adaptive off-route → rerouteFromCurrentLocation() → navEngine.setRoute()  RideViewModel.kt:595

Rendering: LiveRideScreen.kt:86-90 (navProgress collected; currentStep/nextStep read LIVE)
 └─ TurnByTurnNavHeader(...)                                              LiveRideScreen.kt:178-184
      └─ NavigationHud.kt: distance 26 sp Black (:186-199) · instruction 14 sp (:203-210)
         · "Then: …" 11 sp (:211-219) · icon via getManeuverIcon (:68-83, :151-160)
```

**Maneuver ↔ geometry association:** `computeStepVertexIndices` (NavEngine.kt:245) matches each OSRM step to a route vertex by **cumulative step distance vs cumulative polyline distance** (nearest vertex), step 0 pinned to vertex 0. It does **not** use OSRM's per-step geometry ⇒ on self-overlapping or duplicated geometry the association can be off by a vertex.

**Distance-to-maneuver source:** projected route position (`closestIdx`) then polyline length — the correct family of computation. Only its *target* is wrong (F1-D1).

---

## 4. Confirmed defects

### F1-D1 — Banner and voice pair the previous maneuver's text/icon with the next maneuver's distance (P0, safety-relevant)

*Evidence (RideTogether-source-confirmed):* `NavEngine.kt:176-180` targets `stepVertexIndices[currentManeuverIdx + 1]`; `NavEngine.kt:276-280` (`getCurrentStep`) returns `steps[currentManeuverIndex]`; `NavigationHud.kt:196-219` renders that text next to the distance; `RideViewModel.kt:432-441` speaks the same text with the same distance.
*Runtime corroboration:* device fbe12520, installed build md5 `556a7b4590affa971e6bd35d2a951752` (verified on-device), 2026-10-20 04:07 → banner **`82 m` / `Head left` / `Then: Turn right`** (`/tmp/opencode/1a_state.png`, crop `/tmp/opencode/1a_banner2.png`).
*Ground truth:* live OSRM for the same origin (`GET /route/v1/driving/73.7332,18.6042;73.4056,18.7508;73.6738,18.6573;73.4017,18.7549?overview=full&geometries=geojson&steps=true` → `code: Ok`, 3 legs, 2136 points, saved `/tmp/opencode/osrm_mw.json`): `step[0] = depart/left @ 0.0 m`, `step[1] = turn/right @ 80.2 m`.
⇒ The rider is 82 m from a **right** turn; the banner's primary line and its left-turn icon say **"Head left"**; the correct instruction is demoted to the grey 11 sp "Then:" line. The voice says "In 82 meters, Head left".
*Scope:* the defect is **internal** to RideTogether (instruction and distance disagree) and is verifiable without the reference; the reference is only used to show the intended relationship (primary = upcoming context, "Then" = the one after).
*Also present in the worktree* (NavEngine.kt:176-187 identical to main) ⇒ the fix must be applied to the worktree.

### F1-D2 — Unbounded maneuver advancement; straight-line threshold; no accuracy gate (P1)

*Evidence:* `NavEngine.kt:162-173` — a `while` loop advances the index with no per-fix cap; the secondary test `userLoc.distanceTo(points[nextManeuverVertex]) <= 25.0` is **straight-line**, and the block is **not** gated on `isAccuracyReliable`.
*Contrast (the intended discipline, present for legs in the main-repo implementation but absent in the worktree):* `NavEngineMultiLegTest` — "closely spaced stops are never skipped by a single fix", "stale or inaccurate fixes cannot falsely advance stops".
*Impact:* a jumpy/stale fix that projects far ahead can skip several maneuvers (their instructions are never shown or spoken); a hairpin or parallel carriageway within 25 m can advance a maneuver early.
*Not runtime-reproduced* (stationary device) — code-level confirmation only.

### F1-D3 — After a reroute the voice is silent for the first maneuvers of the new route (P1)

*Evidence:* `NavAudioGuidance.kt:119` refuses any `maneuverIndex <= lastSpokenManeuverIndex`; `NavAudioGuidance.kt:158` `resetAnnounceHistory()` has **no call site** in the worktree (grep: definition only); `RideViewModel.kt:595` (`rerouteFromCurrentLocation`) installs the new route via `navEngine.setRoute(result)`, which resets only the engine-side counter (`NavEngine.kt:51`).
⇒ A new route's maneuver indices restart at 0, so every maneuver at or below the previous route's maximum is silently skipped.

### F1-D4 — Dead announcement state (P2)

`NavEngine.kt:43-44, 288-292` (`_lastAnnouncedManeuverIndex`, `markManeuverAnnounced`) are never invoked by the app (only by `NavEngineAndSosTest`) ⇒ the engine-level duplicate suppression the class implies does not exist; suppression lives solely in the audio layer (F1-D3).

### F1-D5 — HUD mixes progress snapshots (P2)

`LiveRideScreen.kt:86` collects `navProgress`; `:89-90` re-read `navEngine.progress.value` live for the step texts. A progress emission landing between collection and composition can pair a distance from state *N* with an instruction from state *N+1*. The **voice** path is internally consistent (both values come from the same `processLocation` result).

### F1-D6 — No re-announcement ladder (P3, design gap)

`NavAudioGuidance.kt:117-139` speaks each maneuver at most once, ever. The reference's ladder is not inspectable (F1-A11) ⇒ this is a comparison gap, not a proven deviation.

### F1-D7 — Step↔vertex association by cumulative-distance matching (P2, hypothesis-grade)

`NavEngine.kt:245` matches steps to vertices by cumulative distance. On merged multi-leg geometry with self-overlap, this can mis-associate a maneuver by a vertex. Not reproduced ⇒ hypothesis.

---

## 5. Reference behaviour not obtainable / not captured

- Native maneuver selection, distance projection, and transition timing (F1-A1) — **unavailable**.
- Reference announcement thresholds, re-announcement, and duplicate suppression (F1-A11) — **unavailable**.
- Reference **near-turn** banner state (distance beside the maneuver) — **not captured** (stationary device, no mock-location facility).
- RT **voice audio** on device — not captured (no audio path exercised; stationary ⇒ only the first prompt could fire). The spoken **text** is source-verified.

---

## 6. Proposed original implementation (specification only — not implemented)

**S1 (P0) — One "upcoming maneuver" concept.**
Introduce an explicit `upcomingManeuverIndex` in `NavigationProgress`, defined as *the first step whose maneuver vertex has not yet been passed*. Drive the banner's primary line, its icon, the voice text, and `distanceToNextManeuverMeters` from that single index. Keep `currentManeuverIndex` semantics untouched (it is consumed by the multi-leg work in the main repo) or re-map it explicitly, checking every consumer (`LiveRideScreen`, `NavigationHud`, `getNextStep`, `getNextStop`, `NavEngineMultiLegTest`).
*Original, implementable:* the reference's own model is native and unavailable, so this is RT-original: "the banner always describes the maneuver the distance counts down to".

**S2 (P0) — Reset voice suppression on route change.**
Call `navAudioGuidance.resetAnnounceHistory()` wherever a route is installed (`RideViewModel.kt:307`, `:562`, `:595`), or key the suppression on `(routeVersion, maneuverIndex)`.
*Invariant-safe:* no camera/bearing/DR/leg/Firestore change.

**S3 (P1) — Guarded maneuver advancement.**
Advance at most one maneuver per fix; require an accuracy-reliable fix (≤30 m); replace the straight-line 25 m test with a *projected* one (advance when the projected position passes the vertex, or when the along-route distance to it drops below a small threshold). Add a bounded "force-advance after N consecutive fixes at the same vertex" rule so progression cannot stall.

**S4 (P1) — Single snapshot for HUD and voice.**
Expose the step indices inside `NavigationProgress` and remove the live `_progress.value` reads at `LiveRideScreen.kt:89-90`.

**S5 (P2) — Distance presentation.**
Split number/unit with two typographic tiers (RT-original ink), skip re-layout when the formatted value is unchanged, and add a km/m transition rule (e.g. switch at 1000 m, keep the value monotone).

**S6 (P2) — Step↔vertex association.**
Prefer OSRM's own per-step geometry/waypoint indices when present; fall back to cumulative matching. Validate with a self-overlapping geometry fixture.

**S7 (P3) — Optional re-announcement ladder** (RT-original, product decision): announce once at approach, optionally remind at a second threshold, with per-maneuver dedup keyed on the new upcoming index.

---

## 7. Regression tests to add (exact)

1. `ManeuverPairingTest` — the step returned as *upcoming* must be the one whose along-route distance equals `progress.distanceToNextManeuverMeters` (±1 m), at the route start and just before a turn. **Fails on current code by design.**
2. `ManeuverPairingTest.voiceMatchesBanner` — the spoken instruction equals the banner's primary instruction for the same fix (fake audio sink).
3. `ManeuverAdvanceCapTest` — a fix projecting past several vertices advances ≤1; a stale fix (accuracy >30 m) advances 0; a parallel-road fixture does not advance early.
4. `ManeuverAdvanceNoStallTest` — repeated fixes at the same vertex eventually advance.
5. `AnnouncementResetOnRerouteTest` — after a reroute the first maneuver of the new route **is** announced; a lower index than the previous maximum is not suppressed.
6. `HudSnapshotConsistencyTest` — distance and instruction come from the same `NavigationProgress` instance.
7. `ManeuverDistanceMonotonicTest` — extend `NavigationProgressAndEtaTest.test 7` to assert non-increasing distance across a dense fix sequence, including the straight-line fallback branch.
8. `StepVertexAssociationTest` — self-overlapping geometry keeps a stable step↔vertex mapping.

**Suites that must stay green:** `NavEngineAndSosTest`, `NavigationProgressAndEtaTest`, `NavigationTelemetryRegressionTest`, `NavEngineMultiLegTest` (main repo), `MultiWaypointOsrmParsingTest` (main repo), `RideViewModelRerouteAndDistanceTest`.

## 8. Device acceptance criteria

- **AC-1** — On the verified 3-leg route (Kamshet → Talegaon → Lonavala, `code: Ok`, legs = 3), ~80 m before the first turn the banner shows the **upcoming** maneuver with the matching distance; the performed maneuver (or "Then:") is secondary.
- **AC-2** — The spoken prompt names the same maneuver as the banner's primary line.
- **AC-3** — After a manual reroute (↻) the first maneuver of the new route is announced (no silence).
- **AC-4** — Arriving at an intermediate stop advances the leg; the banner continues with the next leg's first maneuver; waypoint order unchanged.
- **AC-5** — Legacy ride (no `routeStops`, e.g. code `P3W5YP`) still launches and navigates with a correct pairing.
- **AC-6** — Camera, qualified bearing, dead reckoning and telemetry behaviour unchanged (no new bearing/DR logic).

---

## 9. Implementation record (approved 2026-10-20) — F1-D1, F1-D3 and F1-D2 **fixed**

Applied in the enhancement worktree (the main repository was not modified for this feature).

| Finding | Fix | Verification |
|---|---|---|
| **F1-D1** (P0) | `NavigationProgress.upcomingManeuverIndex` = the first step whose maneuver vertex the projected position has not passed (`NavEngine.computeUpcomingManeuverIndex`). The distance targets that step's vertex; `getCurrentStep()`/`getNextStep()` return the upcoming step and the one after it. `currentManeuverIndex` keeps its meaning (last maneuver performed) and is now bookkeeping only. | `ManeuverPairingTest` (9 tests). **Device** (fbe12520, worktree build md5 `5a93f636…`): the banner now reads **"46 m" / "Turn left" / "Then: Turn right"** with a matching left-turn icon (`/tmp/opencode/1a_worktree_banner2.png`), versus the incoherent pre-fix **"82 m" / "Head left" / "Then: Turn right"** (`/tmp/opencode/1a_state.png`) |
| **F1-D3** (P1) | `navAudioGuidance.resetAnnounceHistory()` is called at every route installation (initial route, route fetch, reroute). The suppression state moved into a pure, Android-free `AnnouncementHistory` class. | `ManeuverPairingTest.announcement history is reset when a new route is installed` + the three ViewModel call sites |
| **F1-D2** (P1) | The unbounded `while` loop became **one accuracy-gated advance per fix** (≤30 m accuracy). Because the guidance is now projection-derived, a conservative advance can no longer hide a turn. | `ManeuverPairingTest.a single fix never advances more than one maneuver`, `…an inaccurate fix never advances…` |

**Suite:** 27 classes / **272 tests / 0 failures / 0 errors / 0 skipped** (`--rerun-tasks`), and the previously hanging live-Firebase test now runs offline (B-09). APK md5 `5a93f6367811683ff53963c05b0f080a`, installed and verified on the device.

**Honest limitation:** the rider's live position is masked in `dumpsys location` and the live fix accuracy is ≈93 m, so the *absolute* identity of the on-device maneuver ("Turn left" at 46 m) could not be re-derived independently from the service for that exact point. Proven: the numeric pairing invariant (unit test) and the banner's internal consistency on device (icon = instruction = the distance's target), which the pre-fix build violated.
