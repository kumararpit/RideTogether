# Photon Place Search — Implementation Notes

RideTogether uses [Photon](https://photon.komoot.io) — the open-source geocoder backed by the
Komoot/OSM data pipeline — for start-location and destination autocomplete on the
**Create Ride** screen. It is a drop-in replacement for the previous Nominatim-based search
(Nominatim is not an autocomplete service and its usage policy forbids browser-like
autocomplete traffic).

## Endpoint

| Item | Value |
|---|---|
| Default base URL | `https://photon.komoot.io/api/` |
| Request | `GET /api/?q=<query>&limit=8&lang=en[&lat=..&lon=..]` |
| HTTPS | Always (no cleartext) |
| API key | None required / none embedded |

- The base URL is a constructor parameter (`PhotonPlaceSearchRepository`), so a self-hosted
  Photon deployment can be configured without code changes.
- Requests are built with OkHttp `HttpUrl` typed query parameters — user input is never
  string-concatenated into URLs.
- A `User-Agent` identifying the app is set per the existing OSM tooling convention
  (`TileMath.OSM_USER_AGENT`).
- Connect/read timeouts: 5 s (bounded latency for autocomplete).

## Public-service limitations (important)

The default endpoint is the **free public Photon service**, not a paid production API:

1. **No SLA / availability guarantee.** Requests can be throttled, rejected (HTTP 429) or fail
   outright; the app treats `429`, 5xx, offline and unparseable responses as *distinct,
   recoverable* states with a manual retry — it never retries in a loop and never fabricates
   results.
2. **Rate limits.** The public endpoint rate-limits abusive clients. This client self-limits:
   300 ms debounce, cancellation of superseded queries, a 2-character minimum before the first
   request, and a hard cap of 8 suggestions (`limit=8`).
3. **OSM data is not Google-grade coverage or ranking.** Photon returns features from
   OpenStreetMap: expect strong coverage of streets, cities and traveller POIs and gaps (or
   rougher ranking) for businesses/named localities that OSM has not mapped. Photon also
   provides *search*, not *verified route-ability*: a place can load into the picker yet OSRM
   may still fail or produce a fallback straight-line route.
4. **Location bias ≠ filter.** When a fresh, accurate GPS fix exists, `lat/lon` bias skews
   relevance toward the area (`SearchLocationBias`); results outside the area are never
   suppressed, so global searches (e.g. Goa from Maharashtra) keep working. A fix that is
   stale (>30 s), coarse (>±50 m) or a `(0,0)` placeholder is dropped — no fabricated bias.
5. **Recommendation for production traffic:** before scaling users, point the app at a
   self-hosted Photon instance behind your own availability monitoring. No code changes are
   required beyond overriding the base URL.

## Privacy

- Only the typed query and, when a valid fresh fix exists, the biased coordinate rounding are
  transmitted to Photon. No ride history, member identities, or full location traces are sent;
  the app never logs location histories or place selections.
- Recent *selected* places are stored only in the local Room database on the device.

## Data flow

```
CreateRideScreen (2 fields)
   └─ PlaceSearchEngine (per field: debounce → repository → supersede guard → selection state)
        └─ PhotonPlaceSearchRepository (OkHttp GET /api/ → GeoJSON parse → local ranking/dedup)
             ├─ RecentPlaceDao (Room `recent_places`, bounded 50, offline reuse)
             └─ SelectedPlace (stable OSM id, numeric lat/lng — the only thing routed)
```

Selection integrity: a suggestion becomes a **selected place** only when the user taps it.
Editing the field text invalidates the selection (`selectedPlace == null`), and the Create
button requires a confirmed selection for both fields — an edited query can never be routed
with previously selected coordinates.
