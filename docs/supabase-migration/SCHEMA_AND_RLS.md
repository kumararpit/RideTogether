# RideTogether — Supabase PostgreSQL Schema & RLS Design

**Migration:** Firestore → Supabase PostgreSQL  
**Owner:** Database Agent  
**Status:** Design only (no Android or backend implementation changes)  
**Source of truth for field names/types:** `app/src/main/java/com/example/data/model/RideModels.kt`  
**Firestore call sites:** `app/src/main/java/com/example/data/firebase/RideRepository.kt`  
**Authorization model:** `firestore.rules` (hardened, default-deny, membership-gated)

> ⚠️ **Scope note:** This document covers schema, indexes, constraints, RLS, and
> versioned SQL migrations only. It does NOT modify any Android source file,
> ViewModel, repository implementation, or Supabase client code.

---

## 1. Identity constraint — Firebase UIDs preserved

`profiles.firebase_uid` is the **PRIMARY KEY** of `profiles` and the foreign-key
target referenced by `ride_members.firebase_uid`, `ride_messages.sender_id`, and
`sos_incidents.reporter_id`.

**No Supabase `auth.users` identity is created.** The Android app keeps Firebase
Auth (Google Sign-In) as its identity provider and continues to use existing
Firebase UIDs verbatim. Supabase Auth is not enrolled unless explicitly required
by a future phase.

Because `auth.uid()` would be meaningless without Supabase Auth, RLS policies
reference a database session variable set by the trusted backend:

```sql
current_setting('app.current_user_id', true)
```

The API/auth layer (not the APK) is responsible for validating the Firebase ID
token via Firebase Admin SDK (`verifyIdToken(checkRevoked=true)`), then setting
`SET app.current_user_id = '<Firebase UID>'` on the database session before
executing any query. The APK sends only the `anon` key + the Firebase ID token
(as a Bearer token to the trusted backend) — **no service_role key or privileged
credential ships in the APK**, and the schema itself contains no secrets.

## 2. Naming convention — Kotlin camelCase → SQL snake_case

Every Kotlin field name is converted to snake_case in SQL. Mapping table
(reference lines from `RideModels.kt`):

| Kotlin field | SQL column | Type | Notes |
|---|---|---|---|
| `Ride.id` (L324) | `rides.id` | `uuid` PK | Firestore doc id → PostgreSQL uuid (`gen_random_uuid()`) |
| `Ride.name` (L326) | `rides.name` | `text NOT NULL` | length 1..100 |
| `Ride.startLocationName` (L327) | `rides.start_location_name` | `text NOT NULL` | default `'Start'` |
| `Ride.destinationName` (L328) | `rides.destination_name` | `text NOT NULL` | default `'Destination'` |
| `Ride.startLocation` (L328, LatLng) | `rides.start_lat` / `rides.start_lng` | `double precision NOT NULL` | CHECK -90..90 / -180..180 |
| `Ride.destinationLocation` | `rides.dest_lat` / `rides.dest_lng` | `double precision NOT NULL` | CHECK -90..90 / -180..180 |
| `Ride.leaderId` (L330) | `rides.leader_id` | `text NOT NULL` | FK → `profiles.firebase_uid` |
| `Ride.inviteCode` (L331) | `rides.invite_code` | `text NOT NULL UNIQUE` | length 4..12 |
| `Ride.status` (L332, RideStatus) | `rides.status` | `text NOT NULL` | CHECK enum |
| `Ride.members` (L333) | `ride_members` (child table) | — | one row per rider |
| `Ride.routePoints` (L334) | `route_geometry.points` (JSONB) | — | see route_geometry |
| `Ride.initialPlannedDistanceMeters` (L335) | `rides.initial_planned_distance_meters` | `double precision NOT NULL DEFAULT 0` | |
| `Ride.totalDistanceMeters` (L336) | `rides.total_distance_meters` | `double precision NOT NULL DEFAULT 0` | |
| `Ride.totalDurationSeconds` (L337) | `rides.total_duration_seconds` | `double precision NOT NULL DEFAULT 0` | |
| `Ride.navigationSteps` (L338) | `route_geometry.steps` (JSONB) | — | see route_geometry |
| `Ride.routeStops` (L344, List<RouteStop>) | `rides.route_stops` | `jsonb NOT NULL DEFAULT '[]'` | JSONB, see §7 |
| `Ride.routePlanVersion` (L346) | `rides.route_plan_version` | `bigint NOT NULL DEFAULT 0` | monotonic plan version |
| `Ride.selectedRouteIndex` (L352) | `rides.selected_route_index` | `integer NOT NULL DEFAULT 0` | |
| `Ride.routeOptions` (L357, List<RouteOptionSummary>) | `rides.route_options` | `jsonb NOT NULL DEFAULT '[]'` | JSONB, see §7 |
| `Ride.actualRecordedDistanceMeters` (L358) | `rides.actual_recorded_distance_meters` | `double precision NOT NULL DEFAULT 0` | |
| `Ride.elapsedDurationSeconds` (L359) | `rides.elapsed_duration_seconds` | `double precision NOT NULL DEFAULT 0` | |
| `Ride.startedAtMs` (L360) | `rides.started_at_ms` | `bigint NOT NULL DEFAULT 0` | wall-clock ms |
| `Ride.createdAtMs` (L361) | `rides.created_at` | `timestamptz NOT NULL DEFAULT now()` | server-managed |
| `RiderMember.id` (L69) | `ride_members.id` | `uuid` PK | |
| `RiderMember.name` (L71) | `ride_members.name` | `text NOT NULL` | length 1..100 |
| `RiderMember.motorcycleModel` (L72) | `ride_members.motorcycle_model` | `text` | default `''` |
| `RiderMember.role` (L73, RideRole) | `ride_members.role` | `text NOT NULL` | CHECK LEADER/MEMBER |
| `RiderMember.status` (L74, MemberStatus) | `ride_members.status` | `text NOT NULL` | CHECK RIDING/STOPPED/EMERGENCY/OFFLINE |
| `RiderMember.membershipStatus` (L75) | `ride_members.membership_status` | `text NOT NULL` | CHECK ACTIVE/LEFT/REMOVED/BANNED |
| `RiderMember.movementState` (L76) | `ride_members.movement_state` | `text` | CHECK RIDING/STOPPED/UNKNOWN |
| `RiderMember.emergencyState` (L77) | `ride_members.emergency_state` | `text` | CHECK NONE/ACTIVE |
| `RiderMember.connectionStatus` (L78) | `ride_members.connection_status` | `text` | CHECK CONNECTED/DISCONNECTED |
| `RiderMember.location` (L79, LatLng) | `ride_members.latitude` / `ride_members.longitude` | `double precision NOT NULL` | CHECK ranges |
| `RiderMember.speedKmh` (L80) | `ride_members.speed_kmh` | `double precision NOT NULL DEFAULT 0` | |
| `RiderMember.headingDeg` (L87) | `ride_members.heading_deg` | `double precision` | nullable (qualified bearing) |
| `RiderMember.headingSource` (L89) | `ride_members.heading_source` | `text` | CHECK UNAVAILABLE/GNSS_COURSE/COMPOSITE |
| `RiderMember.headingAccuracyDeg` (L91) | `ride_members.heading_accuracy_deg` | `double precision` | nullable |
| `RiderMember.accuracyMeters` (L92) | `ride_members.accuracy_meters` | `double precision NOT NULL DEFAULT 10` | |
| `RiderMember.sequenceNumber` (L93) | `ride_members.sequence_number` | `bigint NOT NULL DEFAULT 0` | |
| `RiderMember.lastUpdatedMs` (L94) | `ride_members.last_updated_ms` | `bigint NOT NULL DEFAULT 0` | wall-clock ms |
| `RiderMember.serverTimestampMs` (L95) | `ride_members.server_timestamp_ms` | `bigint NOT NULL DEFAULT 0` | |
| `RiderMember.stoppedDurationSec` (L96) | `ride_members.stopped_duration_sec` | `bigint NOT NULL DEFAULT 0` | |
| `RiderMember.isCurrentUser` (L97) | `ride_members.is_current_user` | `boolean NOT NULL DEFAULT false` | |
| `RiderMember.batteryPct` (L98) | `ride_members.battery_pct` | `integer NOT NULL DEFAULT 90` | CHECK 0..100 |
| `RiderMember.avatarColorHex` (L72) | `ride_members.avatar_color_hex` | `bigint NOT NULL DEFAULT 4291562752` | 0xFFFF9800 |
| `QuickMessage.id` (L129) | `ride_messages.id` | `uuid` PK | |
| `QuickMessage.senderId` (L131) | `ride_messages.sender_id` | `text NOT NULL` | FK → profiles |
| `QuickMessage.senderName` (L132) | `ride_messages.sender_name` | `text NOT NULL` | length 1..100 |
| `QuickMessage.type` (L133, QuickMessageType) | `ride_messages.type` | `text NOT NULL` | CHECK enum |
| `QuickMessage.text` (L134) | `ride_messages.text` | `text NOT NULL` | length 1..200 |
| `QuickMessage.timestampMs` (L135) | `ride_messages.timestamp_ms` | `bigint NOT NULL` | |
| `SosEvent.id` (L138) | `sos_incidents.id` | `uuid` PK | |
| `SosEvent.riderId` (L139) | `sos_incidents.reporter_id` | `text NOT NULL` | FK → profiles |
| `SosEvent.riderName` (L140) | `sos_incidents.reporter_name` | `text NOT NULL` | length 1..100 |
| `SosEvent.location` (L141, LatLng) | `sos_incidents.latitude` / `sos_incidents.longitude` | `double precision NOT NULL` | CHECK ranges |
| `SosEvent.timestampMs` (L142) | `sos_incidents.timestamp_ms` | `bigint NOT NULL` | |
| `SosEvent.isResolved` (L143) | `sos_incidents.is_resolved` | `boolean NOT NULL DEFAULT false` | |
| `SosEvent.acknowledgedBy` (L144, List<String>) | `sos_incidents.acknowledged_by` | `text[] NOT NULL DEFAULT '{}'` | |
| `SosEvent.accuracyMeters` (L145) | `sos_incidents.accuracy_meters` | `double precision NOT NULL DEFAULT 10` | |
| `SosEvent.locationSource` (L146) | `sos_incidents.location_source` | `text NOT NULL DEFAULT 'FRESH_LOCATION_FIX'` | |

**Timestamps:** all `created_at`/`updated_at` columns are `timestamptz NOT NULL
DEFAULT now()` and stored in UTC. Wall-clock millisecond fields from Kotlin
(`lastUpdatedMs`, `startedAtMs`, `timestampMs`) are kept as `bigint` for
client-side parity, while `created_at`/`updated_at` are server-managed
`timestamptz`.

## 3. Tables

### 3.1 `profiles` — replaces `users/{userId}`

PK: `firebase_uid` (text). One row per Firebase user. Soft-delete column
`deleted_at` reserved (never hard-deleted for auditability).

### 3.2 `rides` — replaces `rides/{rideId}`

PK: `id` (uuid, `gen_random_uuid()`). Ride metadata + leader + route plan
summaries. `invite_code` is unique. `leader_id` FK → `profiles.firebase_uid`.

### 3.3 `ride_members` — replaces `rides/{rideId}/riders/{riderId}`

PK: `id` (uuid). **UNIQUE(ride_id, firebase_uid)**. FKs → `rides.id` and
`profiles.firebase_uid`. Current rider telemetry + membership roster only —
**location lives only on this current row; there is no separate precise
location-history table** (§9.2).

### 3.4 `ride_messages` — replaces `rides/{rideId}/messages/{messageId}`

PK: `id` (uuid). FK → `rides.id` and `profiles.firebase_uid` (sender). Immutable
once written (no UPDATE/DELETE at this layer, mirroring
`firestore.rules` L262 `allow update, delete: if false`).

### 3.5 `sos_incidents` — replaces `rides/{rideId}/sos/{sosId}`

PK: `id` (uuid). FK → `rides.id` and `profiles.firebase_uid` (reporter).
`acknowledged_by` is `text[]` (list of Firebase UIDs). `resolved_at` set on
resolution. **Never deleted** (auditability).

### 3.6 `route_geometry` — replaces `rides/{rideId}/route/current`

PK: `ride_id` (uuid, FK → `rides.id`). Leader-published polyline (`points` +
`steps` as JSONB) + aggregates. Kept in a dedicated table so the frequently
re-read `rides` row is never bloated by a large polyline.

### 3.7 `sync_outbox` — outbox / event table

One row per pending outbound device→Supabase operation. `idempotency_key`
unique prevents duplicate execution on retry. Columns: `op_id`, `entity_type`,
`entity_id`, `op_type`, `payload`, `idempotency_key`, `created_at`, `updated_at`,
`retry_count`, `status` (pending/done/failed/dead_letter), `error_message`.

### 3.8 `idempotency_keys` — idempotency table

Records every completed client write keyed by client-generated idempotency key
so a replayed request returns the cached response. `expires_at` default 7 days
for cleanup.

## 4. Indexes

| Table | Index | Purpose |
|---|---|---|
| `rides` | `idx_rides_leader_id(leader_id)` | leader's rides lookup |
| `rides` | `idx_rides_invite_code(invite_code)` | join-by-invite-code lookup |
| `rides` | `idx_rides_status(status)` | active-ride queries (CREATED/LOBBY/ACTIVE) |
| `rides` | `idx_rides_created_at(created_at)` | pagination / listing |
| `rides` | `idx_rides_deleted_at(deleted_at)` WHERE NOT NULL | soft-deleted rides |
| `ride_members` | `idx_ride_members_ride_id(ride_id)` | roster lookup per ride |
| `ride_members` | `idx_ride_members_firebase_uid(firebase_uid)` | user's memberships |
| `ride_members` | `idx_ride_members_ride_status(ride_id, membership_status)` | active members of a ride |
| `ride_members` | `idx_ride_members_deleted_at(deleted_at)` WHERE NOT NULL | soft-deleted members |
| `ride_messages` | **`idx_ride_messages_ride_created_at(ride_id, created_at desc)`** | **pagination: newest-first per ride, cursor on created_at** |
| `ride_messages` | `idx_ride_messages_sender_id(sender_id)` | a user's messages |
| `ride_messages` | `idx_ride_messages_created_at(created_at)` | global time ordering |
| `sos_incidents` | `idx_sos_incidents_ride_id(ride_id)` | a ride's SOS events |
| `sos_incidents` | `idx_sos_incidents_reporter_id(reporter_id)` | a user's SOS events |
| `sos_incidents` | `idx_sos_incidents_unresolved(ride_id, is_resolved)` WHERE false | active (unresolved) SOS per ride |
| `sync_outbox` | `idx_sync_outbox_status_created(status, created_at)` | worker picks pending ops |
| `sync_outbox` | `idx_sync_outbox_entity(entity_type, entity_id)` | per-entity outbox |
| `idempotency_keys` | `idx_idempotency_keys_entity(entity_type, entity_id)` | per-entity idempotency |
| `idempotency_keys` | `idx_idempotency_keys_expires_at(expires_at)` WHERE expires_at < now() | TTL cleanup |

## 5. Constraints

### 5.1 Coordinate range CHECK constraints

Every latitude column: `CHECK (latitude >= -90.0 AND latitude <= 90.0)`  
Every longitude column: `CHECK (longitude >= -180.0 AND longitude <= 180.0)`

Applied to `rides.start_lat/lng`, `rides.dest_lat/lng`,
`ride_members.latitude/longitude`, `sos_incidents.latitude/longitude`.

### 5.2 Status / enum CHECK constraints (text-coded)

| Column | Allowed values | Kotlin enum source |
|---|---|---|
| `rides.status` | CREATED, LOBBY, ACTIVE, COMPLETED, CANCELLED | `RideStatus` (RideModels.kt L5-11) |
| `ride_members.role` | LEADER, MEMBER | `RideRole` (L31-34) |
| `ride_members.membership_status` | ACTIVE, LEFT, REMOVED, BANNED | `MembershipStatus` (L57-66) |
| `ride_members.status` | RIDING, STOPPED, EMERGENCY, OFFLINE | `MemberStatus` (L24-29) |
| `ride_members.movement_state` | RIDING, STOPPED, UNKNOWN | `MovementState` (L13-17) |
| `ride_members.emergency_state` | NONE, ACTIVE | `EmergencyState` (L19-22) |
| `ride_members.connection_status` | CONNECTED, DISCONNECTED | `ConnectionStatus` |
| `ride_members.heading_source` | UNAVAILABLE, GNSS_COURSE, COMPOSITE | `HeadingSource` |
| `ride_messages.type` | STOPPED, NEED_FUEL, NEED_HELP, REGROUP, WAIT_FOR_ME, SLOW_DOWN, IM_OKAY, FOOD_STOP, EMERGENCY | `QuickMessageType` (L36-48) |

`battery_pct` has `CHECK (battery_pct >= 0 AND battery_pct <= 100)`.  
Text lengths: `name`/`sender_name`/`reporter_name` 1..100; `text` 1..200;
`invite_code` 4..12.

### 5.3 Referential integrity

- `ride_members.ride_id` → `rides.id` (on delete cascade)
- `ride_members.firebase_uid` → `profiles.firebase_uid`
- `ride_messages.ride_id` → `rides.id` (on delete cascade)
- `ride_messages.sender_id` → `profiles.firebase_uid`
- `sos_incidents.ride_id` → `rides.id` (on delete cascade)
- `sos_incidents.reporter_id` → `profiles.firebase_uid`
- `route_geometry.ride_id` → `rides.id` (on delete cascade, PK)

### 5.4 Uniqueness

- `profiles.firebase_uid` PK
- `rides.invite_code` UNIQUE
- `ride_members` UNIQUE(ride_id, firebase_uid) — one membership per user per ride
- `sync_outbox.idempotency_key` UNIQUE
- `idempotency_keys.idempotency_key` PK

## 6. Database-managed timestamps (UTC)

A `set_updated_at()` PL/pgSQL function sets `new.updated_at = now() at time zone
'UTC'` on every UPDATE. Triggers attach to `profiles`, `rides`, `ride_members`,
`ride_messages`, `sos_incidents`, `route_geometry`, `sync_outbox`. All
`created_at`/`updated_at` columns are `timestamptz NOT NULL DEFAULT now()`.

`now() at time zone 'UTC'` returns a timestamptz interpreted in UTC, so all
stored values are UTC regardless of the database session timezone.

## 7. ride_stops / route_options JSONB handling & route_geometry

- `rides.route_stops` (`jsonb NOT NULL DEFAULT '[]'`) — ordered list of
  `RouteStop` (RideModels.kt L200-214): id, name, address, latitude, longitude,
  role. Stored as JSONB on the ride row because it is small and read together
  with the ride. `route_plan_version` (bigint) guards against stale overwrite
  (leader-authoritative; see `RoutePlanCodec.merge` in the Kotlin layer).
- `rides.route_options` (`jsonb NOT NULL DEFAULT '[]'`) — list of
  `RouteOptionSummary` (L298-321): index, distanceMeters, durationSeconds.
  Summaries only (no geometry) travel on the ride row; full geometry lives in
  `route_geometry`.
- `route_geometry.points` / `route_geometry.steps` (JSONB) — the full polyline
  and navigation steps, persisted by `updateRoute` (RideRepository.kt L1299-1314)
  to `rides/{rideId}/route/current`. Mapped to the dedicated `route_geometry`
  table so the hot `rides` row stays small.
- `route_geometry` is written only by the ride leader (RLS policy), read by
  active members.

## 8. RLS policy design (Supabase, `anon` key from APK)

All tables have `ALTER TABLE ... ENABLE ROW LEVEL SECURITY`. Policies reference
`current_setting('app.current_user_id', true)` (session variable set by the trusted
backend via `SET config` from the verified Firebase ID token — see §1).

Key policies:
- **profiles**: owner-only select/insert/update on `firebase_uid = current_setting('app.current_user_id', true)`.
- **rides**: select by leader or ACTIVE members; insert/update by leader only;
  immutable keys (`id`, `created_at`, `invite_code`) never change on update;
  soft-delete via `deleted_at` (no hard DELETE for auditability).
- **ride_members**: select by owner / leader / ACTIVE members; insert by leader
  only; update split into owner-telemetry (while ACTIVE, no self-promote to
  LEADER, may set membership_status=LEFT) and leader-role/membership changes
  (identity fields immutable); delete = owner leaves or leader removes.
- **ride_messages**: select/insert by ACTIVE members; sender_id must equal the
  caller; **no update/delete** (immutable).
- **sos_incidents**: select/insert by ACTIVE members (reporter = caller); update
  by leader or reporter, lifecycle fields only; **no delete**.
- **route_geometry**: select by ACTIVE members; all writes by leader.
- **sync_outbox** / **idempotency_keys**: owner-scoped via the `firebase_uid`
  embedded in `payload`.

The policies mirror the hardened `firestore.rules` authorization model
(default-deny, membership-gated communication channels, leader-gated mutations).

## 9. Operational rules

### 9.1 Transactions required (backend)

The following flows must be atomic (backend transaction or equivalent), because
the Android client cannot span multiple Supabase rows in one transaction
through the REST/anon path:

| Flow | Atomic unit | Rationale |
|---|---|---|
| Ride creation | `rides` row + leader `ride_members` row + `profiles` upsert | A ride must always have its leader membership |
| Join ride | `ride_members` insert (MEMBER) inside a ride-existence check | Never grant membership without a valid ride |
| Leave ride | `ride_members` membership_status → LEFT (+ optional row removal) | Consistent roster state |
| End/complete ride | `rides.status` → COMPLETED + `elapsed_duration_seconds` + `updated_at` | Single terminal transition |
| Transfer leadership | `rides.leader_id` + two `ride_members.role` updates | Atomic role swap |
| SOS resolve | `sos_incidents.is_resolved` + `resolved_at` + `acknowledged_by` | Consistent emergency lifecycle |

The Kotlin `RideRepository` already uses `FirebaseFirestore.runTransaction` for
`endRide` (L1478-1491) and `acknowledgeSos`/`resolveSosInternal` (L1898-1908,
L1997-2009). In the Postgres port these become explicit SQL transactions on the
backend (or a Supabase Edge Function), never from the Android app.

### 9.2 No precise location-history retention

Location (latitude/longitude) is stored **only** on the current
`ride_members` row, overwritten on each heartbeat/location update
(`processLocationUpdate`, RideRepository.kt L565-592). There is **no**
separate precise location-history table. This avoids unnecessary retention of
precise location history. Historical aggregates (e.g. `total_distance_meters`,
`actual_recorded_distance_meters` on `rides`) are retained as rolled-up
numbers, not as raw GPS traces.

### 9.3 Auditability — no deletion of required records

- `ride_messages`: immutable (no UPDATE/DELETE) — matches `firestore.rules`
  L262.
- `sos_incidents`: never hard-deleted; `is_resolved` + `resolved_at` mark
  lifecycle.
- `ride_members`: soft-delete preferred (`membership_status` → LEFT/REMOVED/
  BANNED, `deleted_at` set) rather than hard DELETE, so the roster audit trail
  survives a member leaving.
- `rides`: soft-delete via `deleted_at` (leader only); hard DELETE policy
  exists only so a REST client can issue DELETE that the DB layer rewrites into
  an update.
- `profiles`: never hard-deleted.

### 9.4 No privileged credential in APK

The schema file and RLS policies contain no `service_role` key, no secret, and
no hardcoded credential. The APK uses the Supabase `anon` key with RLS.
Authentication is established by the auth layer validating the Firebase ID token
and issuing a Supabase JWT with a `firebase_uid` custom claim. The Android app
continues to use Firebase Auth (Google Sign-In) and existing Firebase UIDs.

## 10. Id mapping — Firestore doc id → PostgreSQL id

- `profiles.firebase_uid`: Firestore `users/{userId}` doc id (`userId`, a
  Firebase UID string) → `profiles.firebase_uid` text PK. **Preserved verbatim.**
- `rides.id`: Firestore `rides/{rideId}` doc id → `rides.id` uuid PK
  (`gen_random_uuid()`). The Firestore doc id string is **not** reused as the
  SQL PK; the mapping must be maintained by the sync layer
  (`sync_outbox.entity_id` / a `firestore_doc_id` column if needed). The Kotlin
  `Ride.id` is a `String` UUID (RideModels.kt L324).
- `ride_members.id`: Firestore `riders/{riderId}` doc id → `ride_members.id`
  uuid PK.
- `ride_messages.id`: Firestore `messages/{messageId}` doc id →
  `ride_messages.id` uuid PK. The stable message id is used for dedup
  (`QuickMessageRegistry`, RideRepository.kt L120).
- `sos_incidents.id`: Firestore `sos/{sosId}` doc id → `sos_incidents.id` uuid PK.

## 11. Timestamp conversion — serverTimestamp → timestamptz

Kotlin writes `FieldValue.serverTimestamp()` (a Firestore sentinel resolved
server-side) for `created_at`/`updatedAt`. In Postgres:

- `created_at` / `updated_at` are `timestamptz NOT NULL DEFAULT now()`,
  managed by the `set_updated_at()` trigger on updates. The client never
  supplies these values.
- Client wall-clock fields (`lastUpdatedMs`, `startedAtMs`, `timestampMs`)
  remain `bigint` (epoch ms) for client-side parity with the Kotlin
  `RiderMember.lastUpdatedMs` (L94), `Ride.startedAtMs` (L360),
  `QuickMessage.timestampMs` (L135), `SosEvent.timestampMs` (L142).
- `serverTimestampMs` (`RiderMember`, L95) is kept as `bigint` for the
  client-side staleness/reconciliation logic (`RiderTimestamp`, RideRepository.kt
  L513).

## 12. Migration tooling & execution

**Recommended tool:** Supabase CLI migrations (`supabase db push` / `supabase
migration up`), which natively run versioned SQL files in
`supabase/migrations/`. Alternatives: Flyway or `pg-meta`, pointed at the same
SQL directory.

**How migrations run:** applied by a backend startup job or a separate
migration CI/CD step against the Supabase Postgres database. **Migrations are
NEVER run from the Android app** — the app only reads/writes data through the
anon key + RLS.

**Files:**
- `supabase/migrations/001_initial_schema.sql` — create tables, indexes, RLS,
  triggers, realtime publication.
- `supabase/migrations/001_initial_schema.rollback.sql` — destructive drop of
  all objects (development/staging only; production rollback should be a
  forward migration, not a drop).

**Migration ordering:** `001_initial_schema.sql` is the baseline. Future
migrations (`002_...`, `003_...`) must be additive wherever possible
(additive-only to avoid downtime), and each must ship with a corresponding
rollback for non-production environments.

## 13. Files created

| File | Purpose |
|---|---|
| `supabase/migrations/001_initial_schema.sql` | Initial schema: `app_config`, `profiles`, `rides`, `ride_members`, `ride_messages`, `sos_incidents`, `route_geometry`, `sync_outbox`, `idempotency_keys`; indexes; RLS policies; `updated_at` triggers; realtime publication. |
| `supabase/migrations/001_initial_schema.rollback.sql` | Drop all objects in reverse order (dev/staging only). |
| `docs/supabase-migration/SCHEMA_AND_RLS.md` | This design document. |

## 14. Open questions

1. **Session variable provisioning:** The trusted backend must set
   `app.current_user_id` per request via `SET config`. Which component is
   responsible for this — a middleware layer, a Supabase Edge Function, or the
   backend API route handler?
2. **Firestore doc id mapping:** Is a separate `firestore_doc_id` text column
   needed on each table to preserve the original Firestore document id for
   sync/reconciliation, or is `sync_outbox.entity_id` sufficient?
3. **Realtime:** Is Supabase Realtime enabled on the target project? The
   publication is declared in the schema but is a no-op unless the Realtime
   add-on is active.
4. **Soft-delete enforcement:** Should DELETE on `rides`/`ride_members` be
   physically blocked at the DB level (rewrite to UPDATE via a rule/trigger)
   rather than relying on RLS + API convention? The current schema keeps a
   DELETE policy for REST-client compatibility.
5. **Idempotency TTL:** Is a 7-day `expires_at` on `idempotency_keys` adequate,
   or should it be configurable/longer?
6. **Route geometry size:** Should `route_geometry.points` be subject to a
   size cap (TOAST-friendly) given the large polyline concern cited in
   RideRepository.kt L1296-1298?

## 15. Negative security tests

The following negative tests must pass before the schema is considered secure.
Each test attempts an unauthorized operation and expects the RLS policy to deny it.

### 15.1 Profiles — N-P1..P5

| ID | Scenario | Expected |
|---|---|---|
| N-P1 | User A reads profile of user B (`firebase_uid = B`) | SELECT denied |
| N-P2 | User A inserts profile with `firebase_uid = B` | INSERT denied |
| N-P3 | User A updates profile of user B | UPDATE denied |
| N-P4 | Unauthenticated request (no JWT) | All access denied |
| N-P5 | User A deletes profile of user B | DELETE denied |

### 15.2 Rides — N-R1..R8

| ID | Scenario | Expected |
|---|---|---|
| N-R1 | Nonmember reads ride by ID | SELECT denied |
| N-R2 | Nonmember inserts ride with forged `leader_id` | INSERT denied |
| N-R3 | Member updates ride they don't lead | UPDATE denied |
| N-R4 | Member changes immutable key (`id`, `created_at`, `invite_code`) | UPDATE denied |
| N-R5 | Nonmember deletes ride | DELETE denied |
| N-R6 | Unauthenticated request reads rides | SELECT denied |
| N-R7 | Leader inserts ride with duplicate `invite_code` | INSERT denied (UNIQUE constraint) |
| N-R8 | User writes ride with `leader_id` ≠ caller UID | INSERT denied |

### 15.3 Ride members — N-M1..M9

| ID | Scenario | Expected |
|---|---|---|
| N-M1 | Nonmember inserts rider row for a ride | INSERT denied |
| N-M2 | Member writes another member's telemetry | UPDATE denied |
| N-M3 | Member self-promotes role to LEADER | UPDATE denied |
| N-M4 | Non-leader changes another member's `membership_status` | UPDATE denied |
| N-M5 | Non-ACTIVE member reads ride roster | SELECT denied |
| N-M6 | Nonmember reads ride_members by ride_id | SELECT denied |
| N-M7 | User deletes another member's row | DELETE denied |
| N-M8 | Unauthenticated request | All access denied |
| N-M9 | Insert rider with `ride_id` ≠ existing ride | INSERT denied (FK violation) |

### 15.4 Ride messages — N-MS1..MS6

| ID | Scenario | Expected |
|---|---|---|
| N-MS1 | Nonmember reads messages for a ride | SELECT denied |
| N-MS2 | Member sends message with forged `sender_id` | INSERT denied |
| N-MS3 | Member updates another member's message | UPDATE denied |
| N-MS4 | Member deletes any message | DELETE denied |
| N-MS5 | Non-ACTIVE member inserts message | INSERT denied |
| N-MS6 | Unauthenticated request | All access denied |

### 15.5 SOS incidents — N-S1..S7

| ID | Scenario | Expected |
|---|---|---|
| N-S1 | Nonmember reads SOS incidents for a ride | SELECT denied |
| N-S2 | Member creates SOS with forged `reporter_id` | INSERT denied |
| N-S3 | Non-author/non-leader updates SOS lifecycle | UPDATE denied |
| N-S4 | Member deletes SOS incident | DELETE denied |
| N-S5 | Non-ACTIVE member creates SOS | INSERT denied |
| N-S6 | SOS update modifies immutable fields (`id`, `reporter_id`, `latitude`, `longitude`) | UPDATE denied |
| N-S7 | Unauthenticated request | All access denied |

### 15.6 Admin functions — N-A1..A8

| ID | Scenario | Expected |
|---|---|---|
| N-A1 | Non-leader calls `join_ride` | Execution denied |
| N-A2 | Non-leader calls `end_ride` | Execution denied |
| N-A3 | Non-leader calls `transfer_leadership` | Execution denied |
| N-A4 | Non-leader calls `remove_member` | Execution denied |
| N-A5 | `validate_invite_code` with non-existent code | Returns error / null |
| N-A6 | `join_ride` for ride that doesn't exist | Execution denied |
| N-A7 | `end_ride` on already COMPLETED ride | Execution denied (idempotent no-op) |
| N-A8 | `remove_member` for user not in ride | Execution denied |

## 16. SECURITY DEFINER admin functions

The following admin functions run with `SECURITY DEFINER` and a fixed
`search_path` to prevent search_path injection. They are executed by the
trusted backend using the service-role key (never from the APK).

```sql
-- Validate an invite code and return limited ride metadata (no membership leak)
CREATE OR REPLACE FUNCTION validate_invite_code(code text)
RETURNS TABLE (ride_id uuid, ride_name text, status text)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = 'public'
AS $$
BEGIN
    RETURN QUERY
    SELECT id, name, status
    FROM rides
    WHERE invite_code = code
      AND deleted_at IS NULL;
END;
$$;

-- Atomically join a ride (leader creates member row)
CREATE OR REPLACE FUNCTION join_ride(ride_id uuid, member_uid text, member_name text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = 'public'
AS $$
DECLARE
    ride_exists boolean;
BEGIN
    SELECT EXISTS (SELECT 1 FROM rides WHERE id = ride_id AND deleted_at IS NULL)
    INTO ride_exists;
    IF NOT ride_exists THEN
        RETURN false;
    END IF;
    INSERT INTO ride_members (ride_id, firebase_uid, name, role, membership_status)
    VALUES (ride_id, member_uid, member_name, 'MEMBER', 'ACTIVE')
    ON CONFLICT (ride_id, firebase_uid) DO NOTHING;
    RETURN true;
END;
$$;

-- End/complete a ride (leader only)
CREATE OR REPLACE FUNCTION end_ride(ride_id uuid, leader_uid text, elapsed_seconds double precision)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = 'public'
AS $$
DECLARE
    is_leader boolean;
BEGIN
    SELECT leader_id = leader_uid INTO is_leader
    FROM rides WHERE id = ride_id;
    IF NOT is_leader THEN
        RETURN false;
    END IF;
    UPDATE rides
    SET status = 'COMPLETED',
        elapsed_duration_seconds = elapsed_seconds,
        updated_at = now() at time zone 'UTC'
    WHERE id = ride_id AND status <> 'COMPLETED';
    RETURN true;
END;
$$;

-- Transfer leadership (leader only)
CREATE OR REPLACE FUNCTION transfer_leadership(ride_id uuid, old_leader_uid text, new_leader_uid text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = 'public'
AS $$
DECLARE
    is_leader boolean;
    new_member_exists boolean;
BEGIN
    SELECT leader_id = old_leader_uid INTO is_leader
    FROM rides WHERE id = ride_id;
    IF NOT is_leader THEN
        RETURN false;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM ride_members
        WHERE ride_id = transfer_leadership.ride_id
          AND firebase_uid = new_leader_uid
          AND membership_status = 'ACTIVE'
    ) INTO new_member_exists;
    IF NOT new_member_exists THEN
        RETURN false;
    END IF;
    UPDATE ride_members
    SET role = 'MEMBER'
    WHERE ride_id = transfer_leadership.ride_id
      AND firebase_uid = old_leader_uid;
    UPDATE ride_members
    SET role = 'LEADER'
    WHERE ride_id = transfer_leadership.ride_id
      AND firebase_uid = new_leader_uid;
    UPDATE rides
    SET leader_id = new_leader_uid,
        updated_at = now() at time zone 'UTC'
    WHERE id = ride_id;
    RETURN true;
END;
$$;

-- Remove a member (leader only)
CREATE OR REPLACE FUNCTION remove_member(ride_id uuid, leader_uid text, target_uid text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = 'public'
AS $$
DECLARE
    is_leader boolean;
BEGIN
    SELECT leader_id = leader_uid INTO is_leader
    FROM rides WHERE id = ride_id;
    IF NOT is_leader THEN
        RETURN false;
    END IF;
    UPDATE ride_members
    SET membership_status = 'REMOVED',
        deleted_at = now() at time zone 'UTC'
    WHERE ride_id = remove_member.ride_id
      AND firebase_uid = target_uid
      AND membership_status = 'ACTIVE';
    RETURN true;
END;
$$;
```

## 17. Authorization boundary (backend vs RLS)

| Operation | Android client | Trusted backend API | PostgreSQL RLS |
|---|---|---|---|
| Google Sign-In | CredentialManager → Firebase ID token | — | — |
| Verify identity | — | Firebase Admin SDK `verifyIdToken(checkRevoked=true)` | — |
| Set session variable | — | `SET app.current_user_id = <Firebase UID>` | `current_setting()` read |
| Read ride data | HTTPS request + ID token | Membership check → query | `current_setting()` filter |
| Write ride data | HTTPS request + ID token | Leader/member check → write | `current_setting()` filter |
| Admin / migration | — | Service-role (backend only) | Bypasses RLS (admin only) |

## 18. Secrets management

- The APK must **never** embed: PostgreSQL password, Supabase service-role key/secret,
  or any backend admin credential.
- Android may hold only the Supabase **anon key** (for public REST access) and calls
  the trusted backend over HTTPS for sensitive operations. The Firebase API key is
  already embedded — it is a public client-side identifier, not a secret.
- Service-role key and backend secrets live only in the backend runtime environment.
- RLS policies and schema contain no `service_role` key, no secret, and no hardcoded
  credential.
