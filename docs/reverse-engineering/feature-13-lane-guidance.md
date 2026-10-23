# Feature 13 — Lane guidance and richer turn instructions

**Subject:** Google Maps `26.42.01.997557324` (`com.google.android.apps.maps`,
SHA-256 `6218d72f48b184eb71f68879bce48cb9ebb2b510be7f7cff88217ea3fd592bb2`).
**Date:** 2026-10-21. **Method:** static decompilation (REA/JADX) + resource
decode (apktool) + a live probe of the routing engine RideTogether uses.
**Status:** investigated **and implemented** (see §5).

---

## 1. What the reference does — observed behaviour

The reference draws a **lane strip** inside the turn card: the lanes of the
carriageway the rider is on, with the lanes the route may use highlighted and
the others dimmed. It also uses a closed maneuver vocabulary with roundabout
exit numbers and exit angles.

### 1.1 The turn-card lane strip

| Element | Evidence | Class |
|---|---|---|
| A dedicated lane-guidance list view inside the step-instruction container | `ev_ff392141989a8d0171e63bd8e9237df1c9e1412ac3ad1bc7921238f8ca8697c3` | `com.google.android.libraries.geo.navcore.turncard.views.TurnCardLaneGuidanceListView` |
| The view is placed in the step-instruction layout under the id `lane_guidance_list` | resource ids `0x7f0b052a` (`id/lane_guidance_list`), layouts `0x7f0e0277` (`step_instruction_lane_guidance_bottom`), `0x7f0e0279` (`step_instruction_lane_guidance_top`), `0x7f0e027a` (`step_instruction_lane_guidance_top_fullscreen`) | `res/values/public.xml` |
| The strip is drawn by a custom `View` (its own `onDraw`/`onMeasure`) | `ev_c28d1863df8faa474a18bed72709b642623d5dab0a615495a555ad66fbcea88a`, `ev_5ceef98e64c940ae456a7092f9af51786590fc7ac14d68b345e5a33602a69939` | same |

**The rendering algorithm (from the decompiled `onDraw`):**

```
for each lane:  if (!lane.recommended) lane.draw(canvas, size, filter_dimmed)
for each lane:  if ( lane.recommended) lane.draw(canvas, size, filter_bright)
```

Two facts follow, and both are behaviours worth copying:

1. **Two passes, recommended last.** The recommended lanes are drawn *after*
   the others and with a second `ColorFilter`, so the highlight can never be
   overdrawn by a dimmed neighbour.
2. **Two colour filters, one per state.** The view holds exactly two
   `ColorFilter` fields — one for the usable lanes, one for the rest
   (`ev_ff392141989a8d0171e63bd8e9237df1c9e1412ac3ad1bc7921238f8ca8697c3`).

**The measurement algorithm (from the decompiled `onMeasure`):**

* the lane glyph size is `max(minSize, min(maxSize, (availableWidth − 2×30dp) / laneCount))`
  — the strip **shrinks to fit** rather than clipping;
* the strip is then **centred** in the available width;
* a **separator** (`lane_tick`) is drawn between lane *groups*.

**The per-lane draw (from the decompiled per-lane object):**

```
canvas.translate(offset, -top - height)
if (mirrored) canvas.scale(-1, 1)      // ← the left/right variants are MIRRORS
drawable.setColorFilter(filter)
drawable.draw(canvas)
```

`ev_b6fbf59f4a96169d50f0859b6d4852f8103322838200527b1f8b880ddd2a8f5a`

The mirroring is why the reference needs only a handful of base glyphs: one
right-turn arrow is drawn once and mirrored for the left.

### 1.2 The lane glyph vocabulary

The lane shape is an 11-valued enum (`ev_8ffb6069273956c1ed573b18e2ef114d35f5bdcb7f780a1b986c0820fd7f1f53`,
`defpackage.blna`) plus a 3-valued direction enum (`ev_50bd0deacc087df6a8e9b7f4c612d185d2eef7da0411eaf95cec74d58c730b9d`,
`defpackage.blnb`). The decompiled `packed-switch` in the view's builder
(`ev_cba7558082c955a69a51874ccdb94f7a56b54ebdb0fa150d0769ad75abd806cf`)
maps each shape to a **drawable resource** and to a **width factor**:

| Shape | Drawable | Width factor |
|---|---|---|
| unknown | (blank `ShapeDrawable`) | 0.0 |
| straight | `lane_straight` / `lane_straight_tall` | 0.5 |
| slight | `lane_slight` / `lane_slight_tall` | 0.25 |
| normal | `lane_normal` / `lane_normal_short` | 0.25 |
| sharp | `lane_sharp` / `lane_sharp_short` | 0.25 / 0.375 |
| u-turn | `lane_uturn` / `lane_uturn_short` | 0.25 / 0.375 |

Resource ids (`res/values/public.xml`): `lane_normal 0x7f0808c0`,
`lane_normal_short 0x7f0808c2`, `lane_sharp 0x7f0808c5`,
`lane_sharp_short 0x7f0808c7`, `lane_slight 0x7f0808c9`,
`lane_slight_tall 0x7f0808cb`, `lane_straight 0x7f0808cd`,
`lane_straight_tall 0x7f0808cf`, `lane_stub 0x7f0808d1`, `lane_tick 0x7f0808d2`,
`lane_uturn 0x7f0808d3`, `lane_uturn_short 0x7f0808d5`. The glyphs are 24 dp
vector drawables (`res/drawable/lane_slight.xml`, `lane_uturn.xml`, …).

Two behaviours are visible in that table and are worth naming:

* **the arrow width is not the lane width** — a turn arrow is drawn at ~25 % of
  the lane cell, a straight arrow at ~50 %, so a row of lanes reads as a row
  of *lanes*, not as a row of arrows;
* **the left/right pair is one glyph mirrored**, never two drawings.

### 1.3 The public model (the reference's own car-app model)

| Class | Evidence | Content |
|---|---|---|
| `androidx.car.app.navigation.model.LaneDirection` | `ev_b20f9a73ebf7b5f2cabe80edb44d348dc237bc44524613f1620633d251874067` | 10 shapes (`SHAPE_UNKNOWN 1` … `SHAPE_U_TURN_RIGHT 10`) + `isRecommended` |
| `androidx.car.app.navigation.model.Lane` | `ev_c39a4fb14bf65c99442618e0cf1f05fb88a31fc3216728404a2854dc48822450` | a list of `LaneDirection` |
| `androidx.car.app.navigation.model.Step` | `ev_b498b3eafb8f524ef0e9d3cf27721dc40d606f520073f2c6eb7daeb188902659` | `maneuver` + `lanes` + `lanesImage` + `cue` + `road` |
| `androidx.car.app.navigation.model.Maneuver` | `ev_0dd6c033089f6aa0a9a104f3b46c65e22f0e111d9a82945acbbbb0353ae87bc7` | **48 maneuver types** + `roundaboutExitNumber` + `roundaboutExitAngle` + `CarIcon` |

The 48 types decompose into the same behavioural families a rider needs:
`TURN_{NORMAL,SLIGHT,SHARP}_{LEFT,RIGHT}`, `FORK_{LEFT,RIGHT}`,
`MERGE_{LEFT,RIGHT,SIDE_UNSPECIFIED}`, `KEEP_{LEFT,RIGHT}`,
`ON_RAMP_{NORMAL,SLIGHT,SHARP}_{LEFT,RIGHT}`, `OFF_RAMP_{NORMAL,SLIGHT}_{LEFT,RIGHT}`,
`ROUNDABOUT_ENTER_{CW,CCW}[_WITH_ANGLE]`, `ROUNDABOUT_EXIT_{CW,CCW}`,
`ROUNDABOUT_ENTER_AND_EXIT_{CW,CCW}[_WITH_ANGLE]`, `U_TURN_{LEFT,RIGHT}`,
`NAME_CHANGE`, `DESTINATION{,_LEFT,_RIGHT,_STRAIGHT}`, `STRAIGHT`, `DEPART`,
`FERRY_{BOAT,TRAIN}[_LEFT,_RIGHT]`, `UNKNOWN`.

Two API details carry real behaviour: `isExitNumberRequired(type)` and
`isValidTypeWithExitAngle(type)` — the exit number and the exit angle are
**type-dependent**, not free-form.

### 1.4 Lane-aware route line (native, not reproducible)

`com.google.android.apps.gmm.directions.laneaware.NativeLaneAwarePolylineInterpolator`
(`ev_acce3818b45b696b3c5a9e9baeb82a0747924870e658bd2cc3ec7dd9cd2882e7`)
exposes `nativeInterpolateBeziersJni(byte[], float, int): byte[]` — the route
line is **re-shaped per lane** by native code. The APK even ships a developer
HUD for it (`devtools_laneawarepolylinedebughud_content`,
`laneawarepolylinedebughud_*` ids), with a "fallback reason" field — the
feature has documented failure modes.

**Not reproducible in RideTogether**: the interpolation itself is native and
proprietary. RideTogether keeps its own (already validated) four-pass route
band.

### 1.5 The data is server-side, not map-side

`res/values/strings.xml`, `OFFLINE_HELP_CONTENT`:

> "In your driving directions, you won't get traffic info, alternate routes,
> or **lane guidance**."

So lane guidance is a property of the **route response**, not of the tiles.
A client that has no lane-bearing route response has no lane guidance — which
is exactly the constraint RideTogether is under (§3).

### 1.6 Voice

The reference's Java corpus contains **no** lane-announcement strings: no
"use lane 2", no "move to the right lane". The lane strip is a **visual**
aid; the spoken prompt describes the manoeuvre, not the lane. RideTogether
follows the same rule (§5.4).

---

## 2. What is **not** obtainable

| Item | Why |
|---|---|
| The lane-interpolation algorithm | native (`nativeInterpolateBeziersJni`) |
| The lane data itself | server-side (the route response) |
| The maneuver-selection algorithm | native (`RouteGuiderJni`) + server-side |
| The turn-card icon set | proprietary assets — **not** copied |
| The `bmce`/`blmz`/`bmci`/`blna`/`blnb`/`bmdf`/`bmcq` type names | obfuscated; the *behaviour* was recovered, the names are not meaningful |
| The exact colour values of the two filters | runtime/theme dependent |

Nothing in the implementation below reproduces a proprietary asset: the
arrows are RideTogether-original drawings and the icons are stock Material
icons.

---

## 3. What the routing engine RideTogether uses actually provides

The app routes through OSRM (`router.project-osrm.org`). A live probe of the
same endpoint the app calls:

```
GET /route/v1/driving/13.388860,52.517037;13.429707,52.515941?steps=true
```

**Manoeuvre data (always present):**

```json
"maneuver": { "type": "turn", "modifier": "left", "bearing_before": 30, "bearing_after": 120 }
```

Observed `type` values: `depart`, `turn`, `continue`, `new name`, `on ramp`,
`off ramp`, `fork`, `merge`, `exit rotary`, `arrive`, `end of road`.
Observed `modifier` values: `left`, `right`, `straight`, `slight left`,
`slight right`, `uturn`, absent.
`exit` (a number) is present for ramp and roundabout exits.
`bearing_before` / `bearing_after` give the turn angle.

**Lane data (present only where the road is mapped with `turn:lanes`):**

```json
"intersections": [
  { "location": [...], "bearings": [...], "entry": [...],
    "lanes": [ { "valid": true,  "indications": ["straight"] },
               { "valid": false, "indications": ["none"] },
               { "valid": false, "indications": ["left"] } ] } ]
```

Observed lane-count distribution over four live routes: 2 lanes ×6, 3 lanes
×7, 4 lanes ×2. Observed indications: `left`, `right`, `straight`, `none`, and
combinations such as `["straight","right"]`.

**Where the lanes sit:** the lanes of a *manoeuvre* are the ones attached to
the **manoeuvre's own node**. Verified empirically over four routes: for
every step, `maneuver.location` equals `intersections[0].location` exactly,
and the lanes of the *later* intersections of the same step belong to the
**next** manoeuvre. The Pune→Lonavala route (the app's own test route) has
**no** lane data at all, because those roads are not mapped with `turn:lanes`.

**Consequence:** lane guidance in RideTogether is *data-dependent* and will
simply be absent on most Indian roads until the underlying OSM data is
mapped. The feature therefore has to be honest about absence — which is the
core of the implementation.

---

## 4. The gap in RideTogether (before this change)

* The maneuver was carried as the raw `(type, modifier)` pair
  (`NavigationStep.maneuverType` / `.modifier`) and the icon was chosen by
  `String.contains` — so a *bear left* and a *keep left* drew the same
  arrow, and a *fork*, a *merge* and a *ramp* all drew a generic arrow
  (backlog item B-19).
* The instruction was built by string concatenation
  (`"Take $modifier fork$roadSuffix"`, `"Take roundabout exit$roadSuffix"`) —
  no exit numbers, no ordinals, no roundabout exits.
* There was no lane concept at all.

---

## 5. The implementation

### 5.1 The maneuver vocabulary — `data/model/ManeuverType.kt`

A RideTogether-original, closed vocabulary of **24** manoeuvres
(`DEPART`, `ARRIVE`, `TURN_{LEFT,SLIGHT_LEFT,SHARP_LEFT,RIGHT,…}`,
`KEEP_{LEFT,RIGHT}`, `CONTINUE`, `NEW_NAME`, `ON_RAMP`, `OFF_RAMP`,
`FORK_{LEFT,RIGHT}`, `MERGE_{LEFT,RIGHT}`, `ROUNDABOUT_{ENTER,EXIT}`,
`END_OF_ROAD`, `U_TURN`, `FERRY`, `UNKNOWN`), mapped from the engine's
`(type, modifier)` pair by `ManeuverType.from`.

It is deliberately **smaller** than the reference's 48 types: the extra
reference types are combinations the engine never produces (a "sharp left
on-ramp" is a ramp with a left modifier, and the ramp already carries the
direction). The split that matters is the one the raw pair cannot express:

* `turn` + `slight left` → `TURN_SLIGHT_LEFT` (bear left) — a *turn*
* `continue` + `slight left` → `KEEP_LEFT` — a *diverge*

Both are the same two characters, and they need different icons and different
instructions. This is the exact case the old `String.contains` mapping got
wrong.

### 5.2 The instruction text

`ManeuverType.instruction(roadName, exitNumber, stopName)` produces
RideTogether's own wording:

| Manoeuvre | Instruction |
|---|---|
| `TURN_LEFT` | "Turn left onto Laxmi Chowk Road" |
| `TURN_SLIGHT_LEFT` | "Bear left onto MH-66" |
| `KEEP_RIGHT` | "Keep right onto Katraj Bypass" |
| `OFF_RAMP` + exit 5 | "Take exit 5 onto Old Mumbai Hwy" |
| `ROUNDABOUT_EXIT` + exit 3 | "Take the 3rd exit onto NH-48" |
| `ROUNDABOUT_ENTER` | "Enter the roundabout" |
| `FORK_RIGHT` | "Keep right at the fork onto B" |
| `MERGE_LEFT` | "Merge left onto C" |
| `U_TURN` | "Make a U-turn onto X" |
| `ARRIVE` + stop | "Arrive at stop: Cafe A" |

A road the engine did not name stays unnamed — no placeholder road is put
into an instruction.

### 5.3 Lane guidance — `data/model/LaneGuidance.kt` + `data/location/OsrmStepParser.kt`

**The rules, in order:**

1. **Only the manoeuvre's own node.** The lanes are looked up by matching
   `maneuver.location` against `intersections[].location` (≈1 cm tolerance).
   The lanes of a later intersection belong to the next manoeuvre and are
   never shown with this one — the same "one instruction, one data" rule the
   banner follows (audit F1-D1).
2. **`valid` is copied verbatim.** The engine's verdict about which lanes the
   route may use is the only source of the highlight. RideTogether never
   decides this itself.
3. **No lane data → no guidance.** The guidance is `null` and the UI renders
   nothing. RideTogether never invents a lane, never counts lanes and never
   guesses a carriageway.
4. **Contradiction → dropped.** If no usable lane can carry the manoeuvre
   (a right turn whose only usable lane is a left lane), the guidance is
   dropped rather than displayed.
5. **Unknown indications are dropped**, and `"none"` is modelled as *no
   marking*, not as an arrow.

### 5.4 The strip — `ui/components/LaneGuidanceStrip.kt` + `ui/map/LaneArrowGeometry.kt`

* Drawn in a `Canvas` from **RideTogether-original** arrow geometry
  (`LaneArrowGeometry`): a shaft and a head per arrow, in a 24×24 unit box.
* The left/right pairs are produced by **mirroring** one right-hand glyph
  (`LaneArrowGeometry.mirror`), exactly as the reference does, so the two can
  never disagree.
* The strip **shrinks to fit** the available width instead of clipping, and
  is centred.
* The usable lanes are drawn in white, the others at 32 % white — the
  two-state contrast of the reference, expressed with two colours rather
  than two `ColorFilter`s.
* The strip is placed at the **top** of the turn card's instruction column
  (the reference's `step_instruction_lane_guidance_top` placement).
* Accessibility: the strip publishes a description ("Use the left lane").
* **The lane strip is not spoken.** The reference has no lane announcements
  either (§1.6); the voice says the manoeuvre.

### 5.5 Persistence

`RouteGeometryCodec` now round-trips the maneuver, the exit number, the turn
angle and the lane guidance, so a ride restored after process death keeps its
lane strip. The encoding is additive: a document written before this change
decodes to the defaults, and a document with a malformed lane list decodes to
no lane guidance rather than to a wrong one.

---

## 6. Verification

### 6.1 Offline tests

| Check | Result |
|---|---|
| `ManeuverVocabularyTest` | 11 tests — vocabulary mapping, instruction text, exit ordinals, unknown handling |
| `LaneGuidanceParsingTest` | 12 tests — real engine fixtures: manoeuvre-node matching, no-data ⇒ no guidance, contradiction ⇒ dropped, multi-indication lanes, unknown indications, the movement of the route drawn first |
| `LaneArrowGeometryTest` | 7 tests — glyph bounds, exact mirroring, blank lane, u-turn distinctness, combined arrow |
| `OsrmLaneGuidanceIntegrationTest` | 5 tests — the **whole** service path over a verbatim live engine capture: the richer maneuver reaches the steps, the lanes of a later intersection are not shown with this manoeuvre, a multi-indication lane keeps all of them, the engine's `valid` flag decides the highlight, and a step without lane data carries no guidance |
| Full offline suite | 58 classes / 493 tests / 0 failures / 0 errors / 0 skipped |

### 6.2 On-device rendering — RMX3360, 1080×2400, 3.0× density

`LaneGuidanceDeviceRenderTest` (instrumented, `app/src/androidTest`) renders the
**real** turn card and the **real** strip on the device and writes the
captures to the device (`files/lane_guidance/*.png`). Three tests, all green;
the captures were pulled and inspected:

* the strip shows the route's lane **bright** and the other lanes **dimmed**;
* a lane that may be used for two movements is drawn as **one arrow with a
  branch**, never as two arrows (which would read as two lanes);
* the strip sits at the top of the turn card's instruction column, above the
  distance, in the placement the reference uses;
* with no lane data the card is unchanged — no strip, no gap.

Captures from the device run: `/tmp/opencode/lane_shots2/strip_only.png`,
`turn_card_with_lanes.png`, `turn_card_without_lanes.png`; the live-app
captures are `/tmp/opencode/live_09_reroute.png` (turn card on the live
route) and `/tmp/opencode/live_08_rejoin.png` (the live ride screen).

### 6.3 Live app run — real route, real device

The installed build was driven through the real flow on the device. The live
ride screen re-armed a **live OSRM route** (Pune → Lonavala, 49.8 km, 39 min):

* the turn card shows **"82 m / Turn right"** with the vocabulary icon
  (Material `TurnRight`) instead of the old string-matched arrow;
* the secondary line shows **"Then: Turn at the end of the road…"** — the new
  `END_OF_ROAD` instruction, which the old text builder could not produce
  (it said "Continue left onto …");
* the voice announced the new instruction:
  `NavAudioGuidance: Spoke maneuver [1]: Turn right`;
* **no lane strip is shown — and that is correct**: the engine has no lane
  data for this road.

### 6.4 The data limit, measured

13 Indian routes (Pune, Mumbai, Delhi, Bangalore, Hyderabad, Chennai,
Kolkata, Jaipur, Ahmedabad) were probed against the live engine:
**0 lane-bearing intersections in total**. Lane data exists only where
OpenStreetMap is mapped with `turn:lanes`, which in India it very largely is
not. The feature is therefore correct but stays invisible on most Indian
roads until the underlying data exists — which is exactly why the
implementation refuses to invent lanes.

### 6.5 What is still **not** verified

A **live in-app** lane strip could not be produced on this device: the
rider's real position is in Pune (no lane data), and a mock position could
not be injected — the device refuses the shell `MOCK_LOCATION` appop and
`pm grant` is blocked by its policy. The strip is therefore verified as
*rendering* (instrumented, real UI, real device, real screenshots) and as
*data* (live engine response through the whole service path), but it has not
been observed inside a live ride.

---

## 7. Open items

| Item | Note |
|---|---|
| Lane data coverage | OSRM lane data only exists where OSM has `turn:lanes`; most Indian roads have none. A self-hosted OSRM with lane data would widen coverage but not create it. |
| Lane-aware route line | native in the reference; deliberately not reproduced. |
| Turn-angle use | the signed turn angle is parsed and persisted but not yet used to refine the icon (e.g. distinguishing a sharp turn from a normal one when the engine's modifier is coarse). |
| Lane guidance in the itinerary card | the itinerary lists maneuvers as text; the lane strip is only in the live turn card. |
