# Feature 1A — Read-Only Audit: Next-Turn Banner & Maneuver Distance

**Reference:** Google Maps `26.42.01.997557324` (`com.google.android.apps.maps@1068832648@26.42.01.997557324`)
**Subject:** RideTogether multi-waypoint navigation (`/home/arpit/Projects/RideTogether`)
**Date:** 2026-10-20 · **Mode:** audit only — **no source code, test, Gradle, APK, or configuration was changed by this task.**
*(The worktree additionally contains the earlier, separately approved Phase B renderer work, uncommitted, untouched by this audit.)*

---

## 1. Executive verdict

**Confidence: HIGH for the Google Maps presentation layer, HIGH for RideTogether's current behaviour (source-verified + runtime-captured), and NIL for the reference's maneuver-selection algorithm (native, not reconstructible).**

The reference and RideTogether do **not** share a maneuver model, and the difference is not cosmetic:

| | Google Maps 26.42 | RideTogether (current) |
|---|---|---|
| Where the active maneuver is decided | **Native** (`nativeOnLocationChanged` → protobuf guidance state) | Java/Kotlin `NavEngine` on-device |
| What the banner's primary line shows | the **UPCOMING** maneuver (`towards Hanuman Temple Rd`) | the **LAST PERFORMED** maneuver (`Head left`) |
| What the banner's distance refers to | the upcoming maneuver | the **NEXT** maneuver (one ahead of the displayed instruction) |
| Result | instruction ↔ distance agree | **instruction ↔ distance are off by one maneuver** — confirmed at runtime |

**The single highest-impact finding (D1):** RideTogether's banner pairs the *previous* maneuver's instruction and icon with the *next* maneuver's distance. Runtime proof on the physical device: the banner read **`82 m` / `Head left` / `Then: Turn right`**, while the live OSRM service places `depart/left` at **0 m** and `turn/right` at **80.2 m** from the same origin. The rider was 82 m from a **right** turn, but the banner's primary line (and the spoken prompt) said **"Head left"**; the correct instruction was demoted to the grey "Then:" line. This is a **safety-relevant, user-visible defect** and it propagates to the voice prompt.

**Second confirmed defect (D3):** after a reroute the voice prompt can go **silent for the first several maneuvers** of the new route, because the audio layer's announcement history is never reset.

Nothing in the reference's *native* maneuver selection, distance projection, or announcement scheduling was reconstructed — those are provably outside the Java layer (see §2, §5).

---

## 2. Google Maps APK evidence

Classification: **[APK]** decompiled artifact · **[RT]** runtime-observed on device · **[DECI]** decompiled inference · **[UNAV]** unavailable.

| ID | Subject | Class | Evidence (file · class · method) | What it establishes |
|---|---|---|---|---|
| G-1 | Navigation engine is native | **[APK]** | `sources/com/google/android/libraries/geo/navcore/guider/jni/RouteGuiderJni.java` | `nativeCreateGuider(byte[],byte[],byte[],long)`, `nativeOnLocationChanged(long[] jArr, int i, byte[] bArr, boolean z) → byte[]`, `nativeBuildTripGuidanceState(long) → cgce` (proto), `nativeGetSecondsToNextDestination`, `nativeGetRemainingMetersToNextDestination`, `nativeUpdateTrafficData`. **Maneuver selection and distance-to-maneuver are computed in native code**; the Java layer only consumes the returned state. The constructor is a JADX failure ("Method not decompiled … instructions count: 1676") — recorded as a decompilation failure. |
| G-2 | Guidance state is a protobuf | **[APK]** | `sources/defpackage/cgce.java` (extends `cmrf`, implements `cmsk`) | `TripGuidanceState`: int fields `b,c,d,e,f`, booleans `g,h,i,s`, ints `j,k,n,o`, doubles `l,m`, two list fields `p,q` (`cgbq`), `r` (`cgcf`), `t` (`cgbz`). Field names are obfuscated; the `newMessageInfo` descriptor string in `dynamicMethod` encodes field numbers/types. The **structure** is recoverable, the **semantics** are not. |
| G-3 | Next-turn distance view | **[APK]** | `.../navcore/turncard/views/TurnCardStepDistanceTextView.java` | Model `blmu` (a=value string, b=unit string, c=?, d=int). The view renders `value + unit` as a `SpannableString` with **two typographic tiers**: number → `AbsoluteSizeSpan(bmcm.a)` + `StyleSpan(bmcm.b)`; unit → `AbsoluteSizeSpan(bmcm.f)` + `StyleSpan(bmcm.g)`. It **hides itself** (`setVisibility(GONE)`) when the model is empty, and **re-renders only when the model changes** (`!blmuVar.equals(this.a) || this.e`). Sizes come from a theme bundle (`bmck.a().a().k`) ⇒ themable. |
| G-4 | Distance display model | **[APK]** | `sources/defpackage/blmu.java` | Immutable `(String value, String unit, String, int)` with `equals`/`hashCode` over all four fields ⇒ the view's equality check is a true content diff, so the banner re-lays out only on a real distance change. |
| G-5 | Next-step instruction view | **[APK]** | `.../turncard/views/TurnCardNextStepInstructionView.java` | Holds `Optional<blne>` (next-step model) + `Optional` icon; renders the **maneuver drawable at 30 dp** (`this.b = 30; this.c = 30`), scales it to the intrinsic aspect, and appends it inline with the instruction text (`aiop.c(...)`), styled by `bmcm` and the string resource `2132025798` = `TURN_CARD_THEN`. |
| G-6 | Cue ("street name") view | **[APK]** | `.../turncard/views/TurnCardStepCueTextView.java` | Builds the cue line from an `ImmutableList` of cue models with **inline drawables scaled 1.2×** (`bncs(drawable, 1.2f)`) plus `AbsoluteSizeSpan`/`StyleSpan` spans; de-duplicates repeated cue names via a `HashSet`/`HashMap`. |
| G-7 | Turn-card view wiring | **[APK]** | `sources/defpackage/biwi.java` | Resource-ID → view mapping (ids resolved against the apktool-decoded `public.xml`): `distance_text` = 2131428162 → `TurnCardStepDistanceTextView`; `maneuver_image` = 2131428833 → `TurnCardStepManeuverImageView`; `bottom_cue_text` = 2131427720 → `TurnCardStepCueTextView`; `distance_cue_separator_text` = 2131428161; `step_cue_container` = 2131430128; `step_instruction_container_outline` = 2131430135. |
| G-8 | "Then" secondary instruction | **[APK]** | `resources/res/values/strings.xml` | `TURN_CARD_THEN` = `"Then {0}"`, `TURN_CARD_THEN_INSTRUCTION` = `"Then %1$s"` — the reference's secondary-instruction format is literally "Then …". |
| G-9 | Turn-card layout variants | **[APK]** | `resources/res/layout*/` | `adaptive_turn_card_current_step_layout.xml`, `step_container.xml`, `step_container_solo.xml`, `step_container_next_step_full_width.xml`, `step_instruction_content_default_single_line.xml`, `step_instruction_content_default_multi_line.xml`, `step_instruction_content_small_layout_multi_line.xml`, `step_instruction_lane_guidance_bottom.xml`, `navigation_stepcueview_internal.xml`, `long_step_instruction_content.xml` ⇒ the reference ships **several turn-card states** (solo step, with next step, full-width next step, single/multi-line, lane guidance, long step). |
| G-10 | Instruction text generation is native | **[APK]** | `.../geo/shared/client/guidance/modelstranslator/jni/NativeTripTranslatorJni.java`; `.../geo/guidance/jni/NativeHelper.java` | Localized instruction strings ("towards X", "Turn right onto Y") are produced by a native translator; `NativeHelper` lazily loads the native library behind a `ListenableFuture` and registers exception classes. |
| G-11 | Runtime banner (device) | **[RT]** | `/tmp/opencode/aud_gm_10_nav.png` — device fbe12520 (RMX3360, 1080×2400 @3×), Google Maps 26.42.01, 2026-10-19 15:29, nav mode, route "Your location → Lonavala (56 min, 52 km)" | Banner: dark-teal card `#015f61`; arrow glyph; primary **"towards Hanuman Temple Rd"** (street-name band ≈59 px cap-height ≈19.7 dp; the "Rd" suffix ≈42 px ≈14 dp — a two-tier hierarchy inside one line); attached secondary card **"Then ↱"**. A separate blue **next-street pill** ("Hanuman Temple Rd") is anchored on the map at the upcoming maneuver point, and the trip bar carries "56 min · 52 km · 16:25". **Observed semantics:** the primary line names the road being travelled towards, the secondary "Then" card names the *following* maneuver, and the distance-to-maneuver is **not** in the banner in this state (it lives on the map pill / trip bar). The *near-turn* banner state — where the reference is expected to show a distance next to the maneuver — was **not captured** (stationary device), so the reference's distance↔instruction pairing is **not** directly observable; see §5. |
| G-12 | Voice/announcement layer | **[UNAV]** | Corpus-wide search of `sources/com/google/android/apps/gmm/**` for `TextToSpeech`, `synthesizeToFile`, `QUEUE_ADD`, "announce*" → **no matches** | The reference's announcement scheduling (thresholds, re-announcement, duplicate suppression) is **not present in the Java corpus** — it is native, in a separately shipped component, or dynamically loaded. **No claim is made about it.** |
| G-13 | Native binaries | **[UNAV]** | `/tmp/opencode/gmaps-res/lib/arm64-v8a/` → `libgmm-jni.so`, `libgoogle3.so`, `libbase_native.so`, … | Not disassembled in this audit (no native tooling); recorded as a limitation. |

**What the reference does *not* reveal (recorded, not inferred):** which maneuver is active, how distance-to-maneuver is projected onto the polyline, when a maneuver is considered passed, the announcement thresholds/re-announce policy, and the duplicate-suppression rule. All of these live behind `nativeOnLocationChanged` / `nativeBuildTripGuidanceState`.

---

## 3. RideTogether — current call path (source-verified)

```
LocationTracker / RideLocationService
  └─ LocationUpdate(latLng, speedKmh, headingDeg, accuracyMeters)
       └─ RideViewModel.updateNavigationProgress(locUpdate)            [RideViewModel.kt:507]
            ├─ NavEngine.processLocation(locUpdate)  → NavigationProgress   [NavEngine.kt:102]
            │    ├─ arrival latch (≤35 m to destination or ≤25 m remaining)   [:115-135]
            │    ├─ off-route: distanceToPolyline > 120 m (accuracy ≤30 m)    [:138-149]
            │    ├─ closestIdx = forward-window search [currentSeg, +60]      [:152-175]
            │    ├─ leg advancement (≤1 leg/fix, accuracy-gated, crossing)    [:191-204]
            │    ├─ maneuver advancement: while (closestIdx ≥ stepVertex[i+1]
            │    │        || straight-line(user, stepVertex[i+1]) ≤ 25 m) i++  [:210-221]
            │    ├─ distanceToNextManeuver = Σ polyline(closestIdx → stepVertex[i+1])   [:230-235]
            │    │        (fallback: straight-line to the target vertex)
            │    └─ ETA: EMA speed (≥8 km/h) else route average             [:237-274]
            ├─ VOICE: navAudioGuidance.speakManeuver(                      [RideViewModel.kt:513]
            │      instruction = navEngine.getCurrentStep(),               ← steps[currentManeuverIndex]
            │      maneuverIndex = navProg.currentManeuverIndex,
            │      distanceMeters = navProg.distanceToNextManeuverMeters)  ← to steps[current+1]
            │    )   → suppressed if maneuverIndex ≤ lastSpokenManeuverIndex  [NavAudioGuidance.kt:119]
            └─ adaptive off-route → rerouteFromCurrentLocation()          [:543-548]
                   └─ navEngine.setRoute(result)   (resets engine-side announce counter ONLY)  [:772]

Rendering:
  LiveRideScreen: navProgress by collectAsState()            [LiveRideScreen.kt:86]
    ├─ currentStep = navEngine.getCurrentStep()   ← LIVE read of _progress.value   [:89]
    ├─ nextStep    = navEngine.getNextStep()      ← LIVE read                       [:90]
    └─ TurnByTurnNavHeader(currentStep, nextStep, distanceToNextManeuverMeters)  [:178-184]
         → NavigationHud.kt: 52 dp icon tile · 26 sp Black distance · 14 sp instruction
           · 11 sp "Then: …" secondary
```

**Maneuver ↔ geometry association:** `NavEngine.computeStepVertexIndices` (`:295-324`) maps each OSRM step to a route vertex by **matching cumulative step distance against cumulative polyline distance** (nearest vertex by |Δ|), with step 0 pinned to vertex 0. It is *not* derived from OSRM's own per-step geometry, so on dense or duplicated geometry the association can be off by a vertex or two.

**Distance-to-maneuver source:** **projected route position** — `closestIdx` (forward-window projection) then polyline length from there to the target vertex. This is the correct family of computation; only its *target* is wrong (§4).

**Visual vs. voice snapshot:** voice uses the `NavigationProgress` returned by the *same* `processLocation` call ⇒ internally consistent. The **HUD mixes snapshots**: `distanceToNextManeuverMeters` comes from the *collected* `navProgress`, while `currentStep`/`nextStep` are *live reads* of `_progress.value` during composition (`LiveRideScreen.kt:86-90`). A progress emission landing between collection and composition yields a banner whose distance and instruction come from different states.

---

## 4. Maneuver-distance & transition comparison

| Aspect | Google Maps 26.42 | RideTogether | Verdict |
|---|---|---|---|
| Active maneuver decision | native (`nativeOnLocationChanged`) | `NavEngine` while-loop on `closestIdx` | different model; RT's is Java-visible and testable |
| Banner primary line | the road/maneuver context being travelled towards ("towards Hanuman Temple Rd") | the **last performed** maneuver ("Head left") | **D1 — RT's instruction lags the distance by one maneuver** |
| Banner distance target | not in the banner in the captured state (map pill / trip bar) | `steps[current+1]` (one ahead of the shown instruction) | **D1 — the pairing is internally inconsistent** |
| Secondary line | "Then ↱" = the maneuver *after* the next | "Then: Turn right" = the maneuver the distance actually refers to | **D1 — semantic inversion** |
| Icon | 30 dp maneuver drawable inline with the text (G-5) | 52 dp tile with 28 dp icon, derived from the same lagging step | consistent with D1; icon lags the distance too |
| Distance typography | number and unit in **two style tiers** (G-3) | single style, `"82 m"` / `"%.1f km"` at 26 sp | cosmetic gap (RT is not wrong) |
| Distance units | metric/imperial by locale | metric only | product scope, not a defect |
| Distance refresh | model-diffed re-render (G-3) | recomputed every fix | acceptable; RT re-renders on every GPS tick even when the value is unchanged |
| Maneuver transition | native | advance when `closestIdx ≥ vertex` **or** straight-line ≤ 25 m; **multiple maneuvers can advance in one fix** | **D2** |
| Duplicate voice suppression | not inspectable (G-12) | index-based, one shot per maneuver, `lastSpokenManeuverIndex` | RT: no re-announcement ladder (design difference); **D3** on reroute |
| Voice content | native translator (G-10) | `"In {N} meters, {instruction}"` (100 m buckets >500 m, exact 100–500 m, bare instruction ≤100 m) | RT speaks the **lagging** instruction → D1 reaches the ear |
| Audio focus | not inspectable | transient-may-duck, `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, abandoned on done/error | RT is sound here |

---

## 5. Confirmed defects vs. hypotheses vs. unverified

### Confirmed (source-verified, with runtime corroboration where noted)

**D1 — Banner and voice pair the previous maneuver's instruction with the next maneuver's distance (off-by-one).** *Confidence: HIGH.*
`NavEngine.kt:210-235` — the distance targets `stepVertexIndices[currentManeuverIndex + 1]`, while `getCurrentStep()` (`NavEngine.kt:326-330`) returns `steps[currentManeuverIndex]` and the HUD/voice pair them.
*Runtime corroboration:* device fbe12520, installed build md5 `556a7b4590affa971e6bd35d2a951752` (verified on device), 2026-10-20 04:07 → banner **`82 m` / `Head left` / `Then: Turn right`** (`/tmp/opencode/1a_state.png`, crop `1a_banner2.png`). Live OSRM ground truth for the same origin (4-waypoint request, `code: Ok`, 3 legs): `step[0] = depart/left @ 0.0 m`, `step[1] = turn/right @ 80.2 m` (`/tmp/opencode/osrm_mw.json`). The 82 m belongs to the *turn right*; the primary line says *Head left*. The maneuver icon (left-turn arrow) lags the same way.
*Impact:* the rider is told to go left when the next maneuver is right; the correct instruction is demoted to a grey 11 sp "Then:" line. Voice inherits the same wrong instruction.

**D2 — A single fix can advance several maneuvers at once, and maneuver advancement is not accuracy-gated.** *Confidence: HIGH (code).*
`NavEngine.kt:210-221` — a `while` loop advances the index without a per-fix cap, and the 25 m test uses a **straight-line** distance to the maneuver vertex. Contrast the leg-advance path (`NavEngine.kt:191-204`), which is explicitly capped at one leg per fix and accuracy-gated, and `NavEngineMultiLegTest` ("closely spaced stops are never skipped by a single fix", "stale or inaccurate fixes cannot falsely advance stops"). No equivalent guard exists for maneuvers.
*Impact:* a jumpy or stale fix that projects far ahead can skip intermediate maneuvers (their instructions are then never shown or spoken); a hairpin or parallel road within 25 m can advance a maneuver early. **Not runtime-reproduced** (stationary device) — code-level confirmation only.

**D3 — After a reroute the voice can be silent for the first maneuvers of the new route.** *Confidence: HIGH (code).*
`NavAudioGuidance.speakManeuver` refuses any `maneuverIndex <= lastSpokenManeuverIndex` (`:119`). `rerouteFromCurrentLocation` calls `navEngine.setRoute(result)` (`RideViewModel.kt:772`), which resets the **engine-side** counter (`NavEngine.kt:69`) but **never** calls `navAudioGuidance.resetAnnounceHistory()` — a method that exists (`:158`) and has **no call site** anywhere in the app. A new route's maneuver indices restart at 0, so every maneuver at or below the previous route's maximum is silently skipped.

**D4 — Dead announcement state.** *Confidence: HIGH.* `NavEngine._lastAnnouncedManeuverIndex` / `markManeuverAnnounced()` (`:61-62`, `:353-357`) are never invoked by the app (only by `NavEngineAndSosTest`), so the engine-level duplicate-suppression that the class implies does not exist. Suppression lives solely in the audio layer (D3).

**D5 — HUD mixes progress snapshots.** *Confidence: MEDIUM-HIGH (code).* `LiveRideScreen.kt:86` collects `navProgress`; `:89-90` re-read `navEngine.progress.value` live for the step texts. A concurrent emission can pair a distance from state *N* with an instruction from state *N+1*.

**D6 — No re-announcement ladder.** *Confidence: HIGH (code), impact = design gap.* Each maneuver is spoken at most once, ever (`NavAudioGuidance.kt:119`). The reference's ladder is not inspectable (G-12), so this is a *comparison gap*, not a proven deviation; a rider who misses a prompt gets no reminder.

### Hypotheses (plausible, not established)

- **H1** — the reference likely re-announces the same maneuver at descending distance thresholds (a common navigation convention) and suppresses repeats per maneuver; **no evidence in this corpus** (G-12). Not actionable without evidence.
- **H2** — the reference may switch the banner between states (solo step / with-next-step / full-width next step / lane guidance) by context (G-9 shows the layouts exist). The *selection rule* is native and unobserved.
- **H3** — RT's cumulative-distance step→vertex matching (`NavEngine.kt:295-324`) may mis-associate maneuvers when the merged multi-leg polyline contains near-duplicate geometry (self-overlap, a U-turn) — plausible from the algorithm, not reproduced.
- **H4** — the 25 m straight-line maneuver threshold may fire early on hairpins/parallel carriageways; consistent with D2's mechanism, not measured on device.

### Unverified (cannot be established in this environment)

- Reference behavior for **approaching-turn / near-turn / post-turn / rerouting** banner states — the device is stationary and no location-simulation facility is available. **Not captured, not claimed.** In particular, the reference's *near-turn* banner (where a distance is expected next to the maneuver) was never observed, so the reference's own instruction↔distance pairing is **not** directly comparable; the D1 finding rests on RideTogether's **internal** inconsistency, which is fully verifiable without the reference.
- Reference **distance-to-maneuver** semantics (projected vs. cumulative), announcement thresholds, and duplicate-suppression rule (native, G-1/G-12).
- Any **native** behavior of the reference's maneuver selection (G-1, G-13).
- RT **voice** output on-device: the installed build's TTS announcements were not captured (no audio capture path exercised, and the device is stationary so only the first prompt could fire). The *text* was verified in source.

---

## 6. Prioritized implementation backlog (proposals only — not implemented)

> Priority reflects safety/user impact. **All items require explicit approval before implementation.** Camera behaviour, qualified-bearing semantics, bounded render-only dead reckoning, multi-waypoint ordering/leg boundaries, and Firestore sync are **out of scope and must not change**.

**B1 (P0) — Pair the instruction with the distance it describes.**
Fix the semantic so the banner's primary instruction, its maneuver icon, and the spoken prompt all refer to the **same** upcoming maneuver as the displayed distance. Concretely: introduce an explicit "upcoming maneuver" concept in `NavEngine` (e.g. `upcomingManeuverIndex` = the first step whose vertex is not yet passed) and drive `getCurrentStep()`/HUD/voice from it — or, equivalently, retarget the distance to `stepVertexIndices[currentManeuverIndex]`. **Do not** change the meaning of `currentManeuverIndex` without checking its consumers (`LiveRideScreen`, `NavigationHud`, `getNextStep`, `getNextStop`, `NavEngineMultiLegTest`).
*Risk:* medium — the index is consumed by the HUD, the "Then:" line, and the voice; the multi-leg tests pin the leg semantics but not the maneuver pairing.
*Invariant safety:* route/leg/waypoint semantics untouched.

**B2 (P0) — Reset the voice announcement history on route change.**
Call `navAudioGuidance.resetAnnounceHistory()` wherever a new route is installed (`navEngine.setRoute` call sites: initial route, reroute, and any restore path) — or, more robustly, key the suppression on `(routeVersion, maneuverIndex)` so a new route can never inherit the previous route's suppression.
*Risk:* low. *Invariant safety:* none touched.

**B3 (P1) — Cap maneuver advancement at one per fix and gate it on accuracy.**
Mirror the existing leg-advance discipline: advance at most one maneuver per fix; require an accuracy-reliable fix (≤30 m) to advance; replace the straight-line 25 m test with a *projected* test (advance when the projected position passes the maneuver vertex, or when the along-route distance to it drops below a small threshold).
*Risk:* medium — could delay a maneuver advance by one fix cycle; must not stall progression (add a bounded "force-advance after N fixes at the same vertex" rule).

**B4 (P1) — Make the banner and the voice read one snapshot.**
Expose the upcoming/current/next step as part of the `NavigationProgress` snapshot (e.g. `activeStepIndex`, `nextStepIndex`) so the HUD and the voice consume identical state; remove the live `_progress.value` reads in `LiveRideScreen.kt:89-90`.
*Risk:* low-medium — `NavigationProgress` is a public data class consumed by tests and the HUD; extending it is additive.

**B5 (P2) — Distance presentation parity (optional, cosmetic).**
Split the distance into number + unit with two typographic tiers (reference G-3), and skip re-rendering when the formatted value is unchanged. Also consider `distance_cue_separator_text`-style separation of the distance from the street cue.
*Risk:* low. *Note:* purely visual; no navigation semantics.

**B6 (P2) — Optional re-announcement ladder (design decision, needs product input).**
If B1 lands, a ladder (e.g. first announcement at ≤X m, a reminder at ≤Y m, a final cue at ≤Z m) with per-maneuver dedup could be added. **Deliberately deferred**: the reference's policy is not inspectable (G-12), so this must be an RT-original design, not an imitation.

**B7 (P3) — Remove dead announcement state (D4).** Delete or wire `NavEngine._lastAnnouncedManeuverIndex`/`markManeuverAnnounced`; today they are test-only.

**B8 (P3) — Harden step→vertex association (H3).** Consider using OSRM's per-step geometry/waypoint indices (available in the same response) instead of cumulative-distance matching; add a test with self-overlapping geometry.

---

## 7. Exact regression tests to add (with B1–B4)

All are pure JVM tests (no device required) unless marked.

1. `ManeuverPairingTest` — *B1*: with a two-step route (depart/left then turn/right at a known offset), assert that the step returned as the **upcoming** step is the one whose along-route distance equals `progress.distanceToNextManeuverMeters` (within 1 m), for a rider at the start and for a rider just before the turn. **This test fails on today's code** (it encodes the defect).
2. `ManeuverPairingTest.voice uses the same step as the banner` — *B1/B4*: drive the ViewModel's announcement path with a fake audio sink; assert the spoken instruction equals the HUD's primary instruction for the same fix.
3. `ManeuverAdvanceCapTest` — *B3*: a single fix that projects far past several maneuver vertices advances the index by **at most one**; a stale fix (accuracy > 30 m) advances **none**; a U-turn/parallel-road geometry does not advance early.
4. `ManeuverAdvanceProgressTest` — *B3*: repeated fixes at the same position eventually advance (no stall) — guards against a too-strict threshold.
5. `AnnouncementResetOnRerouteTest` — *B2*: after `rerouteFromCurrentLocation` (or `navEngine.setRoute` + the ViewModel path), the first maneuver of the new route **is** announced; and a maneuver index lower than the previous route's maximum is not suppressed.
6. `HudSnapshotConsistencyTest` — *B4*: the HUD's distance and instruction come from the same `NavigationProgress` instance (assert on the snapshot's fields, not on separate live reads).
7. `ManeuverDistanceMonotonicTest` — extend the existing `test 7` in `NavigationProgressAndEtaTest` to assert monotonic *non-increasing* distance across a dense fix sequence, including the straight-line fallback branch.
8. `MultiLegManeuverBoundaryTest` — *B1 + invariants*: with a 3-leg route, the maneuver at a **leg boundary** is the one shown when the rider is just before the waypoint, and the leg index does not change (leg boundaries and waypoint ordering preserved).

**Device acceptance criteria (to be executed on the physical device, once implementation is approved and a build is installed):**
- **AC-1** — Route with ≥3 maneuvers (Kamshet → Talegaon → Lonavala, the verified 3-leg OSRM route, `code: Ok`, legs = 3). At a standstill ~80 m before the first turn, the banner must read the **upcoming** maneuver (e.g. "Turn right" + "82 m"), with the performed maneuver (or "Then:") in the secondary line — matching the reference's semantics (G-11).
- **AC-2** — The spoken prompt for the same fix must name the **same** maneuver as the banner's primary line.
- **AC-3** — After a manual reroute (↻) on the live ride, the **first** maneuver of the new route must be announced (no silence).
- **AC-4** — Multi-waypoint: arriving at an intermediate stop advances the leg and the banner continues with the next leg's first maneuver, with waypoint order unchanged (regression guard for the §1 invariants).
- **AC-5** — Legacy ride (no `routeStops`, e.g. code P3W5YP) still launches and navigates with a correct banner/voice pairing.
- **AC-6** — Camera, bearing, dead reckoning, and telemetry behaviour unchanged (existing suites green; no new dead-reckoning or bearing logic touched).

**Regression suites that must stay green:** `NavEngineAndSosTest`, `NavigationProgressAndEtaTest`, `NavigationTelemetryRegressionTest`, `NavEngineMultiLegTest`, `MultiWaypointOsrmParsingTest`, `RideViewModelRerouteAndDistanceTest`, `RouteStopCodecTest`, `RideViewModelMultiWaypointTest`.

---

## 8. Tool failures & evidence limitations

| Limitation | Detail | Impact |
|---|---|---|
| Native code not analyzed | `libgmm-jni.so`, `libgoogle3.so` present but not disassembled (no native tooling in this environment) | The reference's maneuver-selection and distance algorithms are **out of reach**; no claim is made |
| `RouteGuiderJni` constructor not decompiled | JADX: "Method not decompiled … instructions count: 1676" (catch-all exception ladder) | The guider's Java-side setup contract is unreadable |
| Voice/announcement layer absent | No `TextToSpeech`/announcement classes under `com/google/android/apps/gmm/**` in the corpus | Reference announcement timing & duplicate suppression **unverified** (G-12) |
| Proto semantics unavailable | `cgce` field names obfuscated; descriptor string encodes numbers/types only | Guidance-state structure known, semantics not |
| `jadx` CLI absent / REA providers unavailable | The pre-existing JADX corpus was used; resources were recovered with standalone `apktool d -s` (the corpus's `resources/` was empty) | No fresh decompilation of the DEX was performed; resource IDs were resolved from the apktool output |
| Obfuscated `defpackage` (65,254 classes) | Most guidance mappers are obfuscated; only the turn-card views, `blmu`, `biwi` and the JNI bridges were readable | The proto→display-model mapping path is only partially traced |
| **No motion on the device** | The device is stationary; no mock-location provider is available on this image | Reference and RT **approaching / near-turn / post-turn / rerouting** banner states could not be captured — **marked unverified, not estimated** |
| RT voice not captured on device | Only the first prompt could fire on a stationary device; no audio capture path was exercised | Voice *text* verified in source; voice *audio* and timing unverified |
| Single device | No second device for member/leader comparison | Not required for this feature |

---

## 9. Statement of non-modification

**This audit changed no source code, no test, no Gradle file, no APK, and no configuration.** All actions were read-only inspection of the decompiled corpus, the apktool-decoded resources, the RideTogether sources, and read-only device capture (screenshots; no ride, route, or app state was altered by this audit). The working tree's uncommitted state is unchanged by this task. No implementation work (B1–B8) has been started; it awaits explicit approval.
