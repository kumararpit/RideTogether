# The 14-screen redesign

Reference: `RideTogether App UX Showcase.png` (premium dark, charcoal + amber).
This document maps each of the reference screens to the implementation, records
the design system, and states exactly what has been verified where.

## 1. Design tokens (single source of truth)

`ui/theme/Color.kt`, `ui/theme/Type.kt`, `ui/theme/Theme.kt`.

| Token | Value | Use |
| --- | --- | --- |
| `SlateDark900` | `#0B0F12` | app background |
| `SlateDark800` | `#11171C` | cards, sheets |
| `SlateDark700` | `#192128` | raised/inset surfaces |
| `SlateDark600` | `#232D35` | pressed / selected secondary |
| `SlateDark500` | `#29333B` | hairline borders |
| `AmberPrimary` | `#FF9F2F` | the one primary action |
| `AmberDark` | `#E9891D` | pressed amber |
| `TextPrimary` | `#F5F7F8` | headings and values |
| `TextSecondary` | `#A4ADB5` | supporting copy |
| `TextTertiary` | `#6F7A83` | metadata |
| `StatusRidingGreen` | `#35C982` | live/ready/success |
| `StatusStoppedAmber` | `#F5B544` | waiting/paused |
| `StatusEmergencyRed` | `#F05D59` | SOS, destructive |
| `RouteBlue` | `#2587FF` | route line, rider dot |
| `RideGuidanceGreen` | `#0E6B3F` | live maneuver card |

Spacing is the 4 dp scale in `RTSpacing` (`xxs` 4 → `xxl` 32, `page` 20).
Shapes are in `RTShapes` (`control` 12, `card` 18, `sheet` 24, `pill` 50).
Type is the `Typography` scale plus `TabularNumberStyle` for ETA/distance so
numbers do not jitter while riding.

The previous colour names were kept (they are used by hundreds of call sites),
so the palette change restyled every screen at once instead of leaving a mixed
system behind.

## 2. Shared components (`ui/components/DesignSystem.kt`)

`PrimaryButton` (52 dp, amber, near-black label), `SecondaryButton` (52 dp,
outlined), `RideTopBar`, `RTCard`, `SectionHeader`, `StatusChip`,
`RiderAvatar`, `EmptyState`, `LoadingState`, `ErrorState`, `RTDivider`,
`InfoRow`. Every interactive element is at least 48 dp.

## 3. Screen map

| # | Reference | Implementation | Notes |
| --- | --- | --- | --- |
| 1 | Splash & Sign In | `WelcomeScreen` (signed out) + `SignInScreen` | hero art, mark, tagline, Get Started, guest route |
| 2 | Home | `WelcomeScreen` (signed in) | upcoming ride, four quick actions, Discover, account footer |
| 3 | Create Ride | `CreateRideScreen` | ride name, start/destination/stops, route options, Create Ride |
| 4 | Route Alternatives | `RouteOptionSelector` inside Create Ride and Lobby | duration-led cards, Fastest chip, explicit selection |
| 5 | Ride Lobby | `RideLobbyScreen` | map-first route card, real stats only, riders, invite code, Start Ride |
| 6 | Live Navigation | `LiveRideScreen` + `NavigationHud` | green maneuver card, lane strip, compact trip bar |
| 7 | Navigation Menu | `NavigationMenuSheet` | stop, nearby places, overview, group, voice, report, end/leave |
| 8 | Nearby Places | `NearbyPlacesSheet` | search, category chips, distance, rating when OSM has one, priced detours |
| 9 | Place Details | `AmenityDetailsSheet` + `PlaceCard` | photo, name, category, rating, open status, distance, four actions |
| 10 | Add Stop Confirmation | `AddStopConfirmSheet` + `RouteMiniMap` | real route geometry, measured route change, explicit confirm |
| 11 | Updated Route | `UpdatedRouteSheet` | the committed plan, added stop called out, measured cost |
| 12 | Group Ride Map | `LiveRideScreen` map + `RiderBottomSheet` | live markers, gaps, statuses, leader transfer |
| 13 | Ride Completion | `RideSummaryScreen` | hero, completion state, route, 2×2 metrics, real share |
| 14 | Ride History | `RideHistorySheet` | Completed / Upcoming tabs, cards, shareable detail |

## 4. Behaviour that had to survive

- Navigation engine, route provider, reroute, lane guidance, voice: untouched,
  except the HUD's presentation. The compact trip bar expands to the full
  telemetry (traveled, progress, tracking toggle).
- Firestore plan ownership, plan versioning, stop insertion, publish guard: untouched.
- Chat, quick messages, SOS, rider sheet, leadership transfer: untouched.
- Permissions, foreground location service, map camera/gestures: untouched.
- All existing test tags that drove device tests were preserved (`welcome_screen`,
  `create_ride_button`, `activate_ride_card`→`active_ride_card`, `submit_create_ride_button`,
  `nav_trip_hud`, `toggle_follow_mode_button`, `amenity_details_sheet`, …).

## 5. Deliberate adaptations and divergences

1. **Route alternatives are inline, not a separate destination.** The engine
   returns candidates for the same ordered stops after the form is filled, so
   the choice appears where the rider is — styled as the reference's cards.
   A separate screen would have meant a second back stack for one decision.
2. **The map keeps its DAY/NIGHT/AUTO control.** The reference shows a night
   map. RideTogether already had a tested auto day/night behaviour
   (`MapThemeControllerTest` asserts the AUTO default), so the default was not
   changed; the layers control switches to night in one tap.
3. **The readiness checklist was removed from the lobby.** It asserted
   "Permission Granted", "GPS Fixed" and "Group Sync" without measuring any of
   them. Nothing replaced it: the real signals are on the rider rows.
4. **"Discover" shows real content only** — the newest saved place or the most
   recent completed ride — and an honest empty card otherwise. The reference's
   curated "Top routes near you" would have required a dataset the app does
   not have.
5. **No fake toggles.** The navigation menu states that live location is being
   shared (it is) instead of offering a switch that did nothing.
6. **Place tabs (Overview/Photos/Reviews) were not added** because OSM rarely
   carries photos or reviews; the sheet shows the real fields instead.

## 6. Verification

- `./gradlew :app:compileDebugKotlin --offline` — clean.
- `./gradlew :app:testDebugUnitTest --offline --rerun-tasks` — see §6.1.
- Device: RMX3360 (1080×2400, 480 dpi).

### 6.1 Test results

_To be filled from the final run._

### 6.2 Device captures

| Screen | Capture | Status |
| --- | --- | --- |
| Home | `01_home.png` | captured |
| Live Navigation | `02_live_nav.png` | captured |
| others | — | pending (device disconnected) |

### 6.3 Known open items

- The light map at daytime (AUTO mode) is the largest visual divergence from
  the reference's night map; see §5.2.
- The map control rail still has six buttons where the reference has three;
  consolidating them was not done because it is a functional change to the
  renderer's controls, not a restyle.
- The place/prices flow could not be captured live: Overpass was unreachable
  for the amenity query during this pass (see the feature-14 record).
