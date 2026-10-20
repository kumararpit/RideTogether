# Feature 06 — Place Search & Destination Selection

**Audit type:** read-only. **Target:** enhancement worktree `work/google-maps-feature-enhancements` @ `ef60bff`.
**Reference:** Google Maps 26.42.01.997557324.
**No application source, test, Gradle, schema, or build artifact was modified. No proprietary endpoint was called.**

---

## 1. Summary

The reference's search stack is a **server-ranked gRPC service**; the client's contribution is request shaping, a de-duplication rule, an offline/online merge, and a strict rule that **coordinates are never invented**. RideTogether's Photon-based stack is **client-ranked** but has the same three virtues that matter: an explicit state machine, selection integrity, and a validated location bias.

The one open risk named by the mission — *"a short stop name can resolve to a geographically distant place"* — is **confirmed** and is a *presentation/confirmability* problem, not a ranking problem.

---

## 2. Reference evidence

| ID | Claim | Class | Evidence |
|---|---|---|---|
| F6-A1 | Search/suggest/geocode are **gRPC-Lite services**, not HTTP endpoints | APK-confirmed | `defpackage/ccxk.java:22` → `google.internal.mothership.maps.mobilemaps.search.v1.MobileMapsSearchService/Search`; `defpackage/ayym.java:54` → `…suggest.v1.MobileMapsSuggestService/Suggest` (+ `DeleteSearchHistorySuggest` at `:31`); `defpackage/aywl.java:34,57,80,103,126` → `MobileMapsGeocodeService` with `Geocode`, `GetExhaustiveSearch`, `GetLocationDetails`, `Reveal`, `ReverseGeocode`; the full method surface is enumerated in `defpackage/azah.java:117-292` (`GetSearchOutput`, `InstoreSuggest`, `SnapToPlace`, `GetPlacePreview`, `GetPlace`, `NearbySearch`, `SearchTransitStations`, `ListCategories`, …) |
| F6-A2 | **Ranking is server-side**; the client preserves the server's order | Decompiled-inference | `defpackage/axre.java:190-270` (`c(cqbh, boolean, List)`) iterates `cqbfVar.b` in index order; the only client-side reordering is the offline/online merge (F6-A3) and the prefix filter (F6-A4) |
| F6-A3 | **Offline/online merge rule**: the first **2** offline suggestions are placed **first**, then the remaining online suggestions, de-duplicated | Decompiled-inference | `defpackage/axuc.java:485-598` (merge `:514-582`); offline slice `defpackage/aytf.java:1765-1780` (`bS` copies only `i < 2`) |
| F6-A4 | A **local prefix cache** re-ranks: cached responses are keyed by normalised query prefix; on a miss with a shorter prefix cached, cached suggestions whose matched token equals the newly typed suffix are filtered in | Decompiled-inference | `defpackage/axry.java:38-202` (`:42` skip distance class 11, `:53-73` latency buckets by query length 2/3/4/5 and response time <200 ms / <350 ms) |
| F6-A5 | **De-duplication** treats two suggestions as the same place when they share a feature id, a fingerprint, or are within **50 m** | Decompiled-inference | `defpackage/aytf.java:3289-3315` (`cy`/`cz`; `cabb.d(...) <= 50.0d`); a second key uses (title, subtitle, secondary text, distance class) in `defpackage/axuc.java:527-565` |
| F6-A6 | **Disambiguation of same-named places is achieved by *displaying* server-supplied context** — address/subtitle, secondary text, place type, and a server-supplied **distance class** — not by a separate disambiguation screen | Decompiled-inference (high) | row construction `defpackage/bekm.java:279-389` (`cjav.c`=title, `cjav.d`=subtitle/address, `cjav.e`=secondary text, `cjav.f`=place id, `cjav.h`=location **only when** `(cevo.b & 16) != 0`); the `DISAMBIGUATION_*` strings belong to the *report-a-map-problem* flow, not to search |
| F6-A7 | **Coordinates are never invented**: if the suggestion carries no location, the client **re-queries by the suggestion's own subtitle text** rather than fabricating a coordinate | Decompiled-inference (high) | `defpackage/ajtf.java:33-126` (check `:47`, coordinate `:85,96,112`); the home/work centroid click carries a **feature id and no lat/lng** (`defpackage/qss.java:41` `SuggestionCentroidClick(type=HOME/WORK, centroidFeatureId=…, suggestionAddress=…)`) |
| F6-A8 | The request carries the **caret position**, the origin, restrictions and a suggest mode | APK-confirmed | `defpackage/axre.java:13-186`; `defpackage/axty.java:39-40` `"QueryEdit(queryText=…, completionPoint=…, editTimestampMs=…)"`; mode enum `defpackage/cqbi.java:6-8` — `DEFAULT_SEARCH(0)`, `LOCAL_GUIDE_LOCATION(1)`, `STREET_NUMBER(2)` |
| F6-A9 | The search-box state machine is explicit and serialisable | APK-confirmed | `defpackage/axtq.java:573-624` — `suggestions`, `suggestionsVisible`, `searchRequest`, `initialSuggestMode`, `suggestMode`, `allowRefinementSuggestions`, `restrictions`, `snapToPlaceCenter`, `showSuggestionsFromSnapToPlace`, `shouldShowSearchForQuerySuggestion`, `shouldShowAskMapsSuggestion`, `viewportBounds`, `keyboardTransition`, `imeOptions`, `inputType` |
| F6-A10 | "Search this area" is a distinct, bounded operation: an empty result set is returned when the radius ≤ 0 | Decompiled-inference | `defpackage/ales.java:21-51` (radius check + context attach + dispatch); request model `defpackage/ceid.java:5-18` |
| F6-A11 | A separate **accessibility** search path exists (Tactile) | APK-confirmed | `defpackage/aoum.java:78,85,92,99,106,113` — `TactileSearchRequestProto`, `TactileSuggestRequestProto`, `TactilePlaceDetailsRequestProto`, `TactileCategoriesRequestProto` |
| F6-A12 | The reference supports **refinement** ("Search nearby", "Search this area") and a "choose destination" step | APK-confirmed | `res/values/strings.xml:1498` `CATEGORICAL_SEARCH_SUGGESTION_ANNOTATION_SEARCH_NEARBY`, `:1500` `CATEGORICAL_SELECTOR_FILTER_TITLE`="Nearby places", `:1453` `CAR_SEARCH_THIS_AREA_BUTTON`="Search this area", `:2120` `DESTINATION_REFINEMENT_CARD_HEADER_TITLE`="Choose destination", `:4678/4679` `NO_RESULTS_CONNECT_TO_INTERNET` / `NO_RESULTS_FOR_THIS_AREA`, `:2885` `GENERIC_EMPTY_VIEWPORT_TITLE`="No information in this area" |

**Not found (documented):** no HTTP autocomplete endpoint in the corpus (`complete/search`, `places/v1`, `place/autocomplete`, `predict/submit`, `tbm=map` → no hits); no client-side ranking formula; no dedicated disambiguation screen. Ranking and the 50 m/distance-class semantics live **server-side** ⇒ **unavailable** as an algorithm.

---

## 3. RideTogether (worktree) — verified search stack

| Concern | Implementation | Location | Assessment |
|---|---|---|---|
| State machine | `PlaceSearchUiState`: `Idle / Loading / Results / NoResults / Failure` with an explicit `selectedPlace` carried in every state | `data/place/PlaceSearchEngine.kt:18-62` | **matches the reference's explicitness** (F6-A9) |
| Debounce + supersede | 1 debounce window, monotonic `searchSequence`, stale responses dropped | `PlaceSearchEngine.kt:94-129,177-195` | sound |
| **Selection integrity** | editing the text invalidates the selection (`retainedSelectionFor` requires an exact name match) | `PlaceSearchEngine.kt:171-175` | **matches F6-A7's spirit** — a selected place is never silently re-resolved |
| Coordinates are the truth | `SelectedPlace` carries `latitude/longitude` as the only routing input; display strings are never identifiers | `data/place/SelectedPlace.kt:11-25` | sound |
| **Locality context available** | the parser extracts street, housenumber, postcode, district, city, county, state, country and builds a formatted address | `data/place/PhotonPlaceParser.kt:21-35,67-77` | **the data for disambiguation is present** |
| Ranking | tier-first (exact → prefix → contains → address-contains → provider relevance) with distance as a tie-breaker inside a tier; stable sort preserves provider order; dedup by id, then by name+rounded coordinate | `data/place/PlaceSearchRanking.kt:56-113` | **RT-original and well-reasoned** (the reference's ranking is server-side and unavailable) |
| Location bias | validated hint only: fresh ≤30 s, accuracy ≤50 m, in-bounds, never (0,0); missing bias ⇒ global search, never blocked | `PlaceSearchRanking.kt:15-45` | sound |
| Offline | recent places are surfaced for short queries | `PlaceSearchEngine.kt:103-120` | sound |
| Failure taxonomy | `PlaceSearchFailure` distinguishes offline / rate-limit / server / parse; manual retry, no automatic retry loops | `PlaceSearchEngine.kt:51-58,131-137` | sound |
| UI: destination | the suggestion row **shows the address** under the name | `ui/screens/CreateRideScreen.kt:839-870` | **good** — the locality *is* shown for the destination |
| UI: intermediate stop | the main-repo stop editor shows the **name only** after confirmation ⇒ a distant match becomes invisible | main-repo `CreateRideScreen` (read-only inspection) + device capture `d8` | **gap F6-D3** |

---

## 4. Confirmed gaps

| ID | Severity | Item | Evidence |
|---|---|---|---|
| F6-D1 | P1 | **A confirmed stop can hide a distant place.** The destination path shows the address, but the intermediate-stop path (main-repo WIP) shows only the name: a Photon match in another country is invisible until the route estimate explodes (observed: "Pavana" → Italy ⇒ 18,066 km estimate). | device capture `d8`; Photon probe ("Pavana" → Italia 44.12/11.00, España, Nederland, Honduras) |
| F6-D2 | P1 | **No distance/proximity warning** for an implausibly distant selection. The reference at least supplies a *distance class* per suggestion (F6-A6) and RT's own ranking already computes a distance to the bias — but neither is surfaced to the user. | worktree `PlaceSearchRanking.distanceMeters` exists but is used only for ordering |
| F6-D3 | P2 | **No ≥50 m duplicate rule for identical place names** (the reference merges same-name suggestions within 50 m); RT dedups by name+rounded coordinate (5 dp ≈ 1 m), so two genuinely distinct places with the same name both survive — and the *user* must disambiguate them, with no locality shown in the stop path. | worktree `PlaceSearchRanking.kt:88-98` vs F6-A5 |
| F6-D4 | P2 | **No "refine" affordance** ("search nearby", "search this area", "choose destination") for an unhelpful result set; the user can only retype. | worktree search engine (states `NoResults`/`Failure` only); reference F6-A12 |
| F6-D5 | P2 | **No "no information in this area" affordance** — an empty result is a bare empty list. | worktree `PlaceSearchUiState.NoResults`; reference `GENERIC_EMPTY_VIEWPORT_TITLE` |
| F6-D6 | P3 | The suggestion list does not show the **place type/category** (Photon's `osmKey/osmValue` is parsed into `SelectedPlace.category` but never displayed) — a cheap disambiguation cue. | worktree `PhotonPlaceParser.kt:75` vs the suggestion row |
| F6-D7 | P3 | No **distance-class** concept (the reference has a server-provided proximity bucket) — RT could compute an equivalent bucket from the bias distance. | F6-A6 |

---

## 5. Proposed original implementation (not implemented)

- **S6-1 (P1) — Locality-aware stop confirmation.** Show the confirmed stop's **locality** (city/district/state) next to the name, exactly as the destination path already shows the address. Uses data RT already parses. No behaviour change to coordinates.
- **S6-2 (P1) — Proximity warning (advisory, never blocking).** When a selected place is farther than a threshold from the current location / route corridor, show a warning chip ("This is ~9,000 km away — is that intended?") with **Confirm / Change** actions. **Never** alter the coordinates and **never** prohibit a legitimate long-distance trip (mission constraint).
- **S6-3 (P2) — Show the place type** in the suggestion row (from the already-parsed `category`).
- **S6-4 (P2) — Same-name disambiguation by ≥50 m proximity** (RT-original threshold matching the reference's *rule shape*), keeping distinct same-name places at different locations.
- **S6-5 (P2) — Empty/unsupported-result affordance** with the locality of the searched area and a "search nearby" hint.
- **S6-6 (P3) — Offline suggestion merge**: RT currently shows recents only for short queries; the reference merges *offline* suggestions into the top of the online list (offline top-2 first). A Photon/offline-cache analogue is possible (e.g. recent places promoted when the provider is unreachable).

---

## 6. Tests to add / keep green

- Keep green: `PlaceSearchEngineTest`, `PhotonPlaceSearchTest` (22), `PlaceSearchRankingTest` (worktree), `RecentPlaceDaoTest`.
- NEW `StopLocalityDisplayTest` — a confirmed stop row exposes the locality string; the destination path is unchanged.
- NEW `DistantSelectionWarningTest` — a selection beyond the threshold produces a warning (and **not** a coordinate change, and **not** a hard block); a nearby selection produces none.
- NEW `SameNameDisambiguationTest` — two same-name places within 50 m merge; two beyond 50 m both remain.
- NEW `SuggestionRowContentTest` — the row shows name + locality + type; the ranking order is unchanged by the new fields.

**Invariants affected:** #7 (place selection — never silently replace a selected destination or stop with a guessed location) is *strengthened* by S6-1/S6-2; the coordinates of a selected place must never change as a result of a warning.
