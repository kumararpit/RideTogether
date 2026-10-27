# Supabase Migration Test Plan

RideTogether — Firebase Firestore → Supabase PostgreSQL migration testing.

**Scope:** TEST PLANNING + AUDIT ONLY. No production source files are modified by this plan. The plan covers authentication, authorization, offline synchronization, Realtime, migration integrity, and navigation regression.

**Status:** Planning stage. Environment limitations documented in §7 mean several backend test categories cannot be executed locally until a live Supabase project, Firebase Admin credentials, and the Supabase CLI / node_modules are provisioned.

---

## 1. Purpose

Validate that migrating ride/lobby/member/message/SOS data from Firestore (rules-gated) to Supabase PostgreSQL (RLS-gated) preserves:
- Authentication (Firebase ID token verification at the Supabase edge)
- Authorization (membership/role-based RLS equivalent to the Firestore rules)
- Offline write + reconnect flush (outbox → Supabase)
- Realtime subscriptions (supabase_realtime publication on rides, riders, messages, sos_events)
- Migration integrity (field mapping, referential integrity, timestamps, idempotent repeat import, rollback/reconciliation)
- Navigation regression (all existing Android unit + navigation tests stay green)

---

## 2. Test Inventory — Audit of Existing Tests

### 2.1 Android unit tests relevant to migration (must remain green — navigation regression baseline)

All live under `app/src/test/java/com/example/`. They run via Gradle (`./gradlew test` / `:app:testDebugUnitTest`) with Robolectric (sdk 34) and fakes only — no live Firebase/emulator required.

| Test file | What it covers | Migration relevance | Must remain green? |
|---|---|---|---|
| `RideLifecyclePolicyTest.kt` | Ride status state machine (legal forward transitions, idempotence, terminal COMPLETED/CANCELLED, no ACTIVE regression) | Ride status transitions map to `rides.status` in Supabase; invalid status transition rejection is a lifecycle test | YES |
| `RideLobbyScreenTest.kt` | Lobby rendering with route + riders; invite code copy/share | Ride metadata + rider roster display | YES |
| `ActiveRideRestoreTest.kt` | Durable active-ride restoration via Room `ActiveRideReferenceEntity`; ViewModel `RESTORING` state; null-Firestore offline path | Active-ride reference survives migration; restore-by-ride-ID is now membership-gated via RLS | YES (navigation regression) |
| `ActiveRideReferenceDaoTest.kt` | Room DAO insert/replace/get/delete/clear for active-ride reference; terminal status stored as-is | Same durable reference; now backed by Postgres `rides` + `profiles` | YES |
| `SosLifecycleAndDeliveryTest.kt` | SOS creation, idempotent duplicate trigger, ack, resolve, leave-ride auto-resolve, null-Firestore failure rollback, location freshness | `sos_events` table + RLS insert/update policies; SOS pending/retry/confirmation | YES |
| `SosAckDeliveryAndDismissalTest.kt` | Suppression state (per-event, clears per session), bounded retry policy (MAX_ATTEMPTS=3, backoff), member dismissal without resolve | SOS ack/resolve RLS update policy; `acknowledged_by` text[] column | YES |
| `SosMeshRelayTest.kt` | Mesh SOS broadcast packet (TTL 7), payload field parsing, ack/resolve packets, malformed/missing-id rejection | Offline→Realtime bridge for SOS; messages/sos_events realtime publication | YES |
| `MeshFirestoreBridgeTest.kt` | Mesh↔Firestore message dedup (registerMesh/FirestoreMessageId), flush undelivered queue, sync Firestore→mesh, skip known IDs | Outbox flush + duplicate/out-of-order event handling; idempotent message sync | YES |
| `NavigationTelemetryRegressionTest.kt` | Avg-speed guard (no divide-by-zero, plausibility ≤250 km/h), GPS distance accumulation/gates, reroute preserves actual distance, endpoint immutability, stale reroute rejection, ETA fallback, history metrics | Navigation regression baseline — must not regress after data moves to Supabase | YES (navigation regression baseline) |
| `NavEngineAndSosTest.kt` | NavEngine route progress/maneuver advancing, distance-to-maneuver, stationary ETA baseline, off-route accuracy filter, SOS fresh location + idempotency, route geometry distance | Navigation + SOS integration | YES |
| `RideViewModelRerouteAndDistanceTest.kt` | ViewModel-level reroute: start/destination immutability, reroute origin = GPS, remaining distance resets, odometer survives reroute, stale reroute rejection, completion distance consistency | Navigation regression baseline; ride completion writes | YES (navigation regression baseline) |
| `RideCompletionAndChatSyncTest.kt` | Chat local echo/dedup/burst ordering/send error surface; ride completion leader-only, idempotent, member observes COMPLETED once, stale ACTIVE cannot reactivate, rejoin completed ride → summary | `rides.status` transitions; message insert RLS (sender cannot be forged); completion authorization | YES |
| `RiderStatusAndSyncAuditTest.kt` | Rider sync policy: connection status boundaries, out-of-order timestamp rejection, invalid coordinates preserved, missing timestamp never manufactured | Rider telemetry update RLS (own telemetry only); out-of-order/duplicate events | YES |
| `ExampleUnitTest.kt` | Trivial smoke test | Sanity | YES |

### 2.2 Firestore rules emulator tests to be REPLACED by Supabase RLS tests

`firestore.test.js` (run via `npm test` = `node firestore.test.js`), using `@firebase/rules-unit-testing ^6.0.0` against the Firestore emulator (`firestore.rules`). Baseline from memory: phase6-diff-review-complete — 34 emulator tests T-01..T-21 pass; default-deny at line 289 of `firestore.rules`; all channels membership-gated.

| Test group | Tests | Supabase RLS equivalent |
|---|---|---|
| T-01 Ride read | leader can read own ride; nonmember cannot read by ID; unauthenticated cannot read | `rides` select policy (leader or ACTIVE member) |
| T-02 Rider read | member can read roster; nonmember cannot | `riders` select policy |
| T-03 Message read | member can read messages; nonmember cannot | `messages` select policy |
| T-04 SOS read | member can read SOS; nonmember cannot | `sos_events` select policy |
| T-05 Profile read | user can read own profile; cannot read another's | `profiles` select policy (auth.uid() = id) |
| T-06 Message send (member) | member can send quick message | `messages` insert policy (sender_id = auth.uid, member) |
| T-07 Message send (nonmember) | nonmember cannot send | `messages` insert denied |
| T-08 Message impersonation | member cannot forge senderId != auth.uid | `messages` insert `with check (auth.uid()::text = sender_id)` |
| T-09 Rider write (self) | member can update own telemetry, no role change | `riders` update "Riders can update own telemetry" |
| T-10 Rider write (other/tamper) | member cannot write another rider's doc or self-promote to LEADER | `riders` update policies block non-owner/role change |
| T-11 Rider join | leader can add member; nonmember cannot self-register | `riders` insert "Ride leaders can insert riders" |
| T-12 Leave + post-leave read | member can delete own rider doc; former member cannot read after leave | `riders` delete own membership; select denies non-ACTIVE |
| T-13 SOS write (member) | member can trigger SOS | `sos_events` insert (rider_id = auth.uid, member) |
| T-14 SOS write (nonmember) | nonmember cannot trigger SOS | `sos_events` insert denied |
| T-15 Ride status change (leader) | leader can start ride | `rides` update leader-only |
| T-16 Ride status change (non-leader) | member cannot change status | `rides` update denied for non-leader |
| T-17 Ride field edit (nonmember) | nonmember cannot edit inviteCode | `rides` update denied; immutable keys |
| T-18 Mesh flush | member sender allowed; nonmember denied | `messages` insert policy |
| T-19 Active-ride restore | leader can restore by ID; nonmember cannot | `rides` select membership-gated |
| T-20 Tombstone leave | member can set membershipStatus=LEFT; denied after | `riders` update + select (membership_status ≠ ACTIVE denied) |
| T-21 Leader removal | leader can set membershipStatus=REMOVED; removed denied | `riders` update leader-only + select denied |

**Note:** `firestore.rules` currently has a product caveat (lines 161-166): ride-doc read is open to authenticated callers for invite-code discovery, but the actual rules (line 176) gate read on `isMemberOrLeader`. The Supabase schema's `rides` select policy (line 66-68 of `supabase-schema.sql`) is currently **permissive** — `auth.role() = 'authenticated'` allows any authenticated user to read any ride. This is a known gap to be hardened (tracked as a release blocker in memory). The RLS test plan must include a test that nonmembers cannot read a ride once the policy is tightened.

### 2.3 Tests NOT part of this plan (out of scope)
- Instrumentation tests in `androidTest/` (require device/emulator).
- Map/tile/rendering/OSRM/navigation engine unit tests unrelated to data migration (covered by existing suite, run for regression only).
- BLE/mesh transport unit tests (AndroidBleTransportTest, MeshRouterTest, etc.) — run for regression only.

---

## 3. New Tests to Add

Tests are grouped by category. Each entry lists the scenario and the expected assertion. Backend tests require a live Supabase project (see §7).

### 3.1 Authentication (backend) — Firebase ID token → Supabase

These tests exercise the Supabase Auth / JWT verification seam (GoTrue) that accepts Firebase ID tokens. Requires Firebase Admin credentials + a configured Supabase JWT secret / custom claims mapping.

| ID | Test | Expected |
|---|---|---|
| AUTH-01 | Valid Firebase ID token accepted | Request with a valid, unexpired Firebase ID token for the correct project is authenticated (`auth.uid()` set, `auth.role() = 'authenticated'`). |
| AUTH-02 | Expired token rejected | Token with expired `exp` claim → 401 / `auth.uid()` null; RLS denies. |
| AUTH-03 | Malformed token rejected | Garbage / non-JWT string → 401; not authenticated. |
| AUTH-04 | Wrong Firebase project (audience/issuer mismatch) rejected | Valid token issued for a different Firebase project → rejected (aud/iss do not match configured project). |
| AUTH-05 | Missing token rejected | No Authorization header → unauthenticated; all RLS select/insert denied by default-deny. |
| AUTH-06 | Revoked token rejected (where supported) | Token revoked in Firebase / added to Supabase blocklist → 401. (Mark "where supported" — Supabase does not natively revoke Firebase JWTs; document the seam and any custom revocation check.) |

### 3.2 Authorization (backend + RLS)

| ID | Test | Expected |
|---|---|---|
| AUTHZ-01 | Nonmember cannot read a ride | Authenticated user with no `riders` row for the ride → `SELECT` on `rides` denied (once policy tightened to membership-gated). |
| AUTHZ-02 | Former member cannot read private data | After `membership_status = LEFT`/`REMOVED`, SELECT on `riders`/`messages`/`sos_events` for that ride denied. |
| AUTHZ-03 | Member cannot become leader | A MEMBER `UPDATE` on `riders` setting `role = 'LEADER'` is denied (only leader may change role). |
| AUTHZ-04 | Member cannot modify another member | User A `UPDATE`/`INSERT` on a `riders` row whose `user_id` ≠ auth.uid is denied. |
| AUTHZ-05 | Message sender cannot be forged | `INSERT` into `messages` with `sender_id ≠ auth.uid()::text` is denied by `with check`. |
| AUTHZ-06 | SOS reporter cannot be forged | `INSERT` into `sos_events` with `rider_id ≠ auth.uid()::text` is denied. |
| AUTHZ-07 | Unauthorized SOS access denied | Nonmember SELECT/INSERT on `sos_events` denied; non-author `UPDATE` to lifecycle fields denied. |
| AUTHZ-08 | Unauthenticated access denied | All table access without a JWT → denied (default-deny / `auth.role()` checks). |
| AUTHZ-09 | Leader can update own ride | `UPDATE` `rides` by `leader_id = auth.uid()` succeeds; immutable keys (`id`, `created_at`, `invite_code`) cannot change. |
| AUTHZ-10 | Leader can insert riders (authorized join) | `INSERT` into `riders` by ride leader succeeds; non-leader `INSERT` denied. |

### 3.3 Lifecycle & concurrency

| ID | Test | Expected |
|---|---|---|
| LC-01 | Create and join ride | Leader creates a ride (`rides` row) and adds a member (`riders` row); both visible to members. |
| LC-02 | Duplicate join | Leader inserts same member twice → second `INSERT` fails on primary key / idempotent no-op; roster unchanged. |
| LC-03 | Concurrent join requests | Two concurrent `INSERT`s for distinct members in the same ride both succeed; no lost updates. |
| LC-04 | Leave and end ride | Member deletes own `riders` row (leave); leader sets `rides.status = 'COMPLETED'` (end); post-leave reads denied. |
| LC-05 | Leader transfer | Leader changes a member's `role` to `LEADER` and own role to `MEMBER`; new leader can update ride, old leader cannot. |
| LC-06 | Invalid status transition rejected | `rides.status` transition e.g. `COMPLETED → ACTIVE` or `ACTIVE → CREATED` rejected (mirrors `RideLifecyclePolicy`). |
| LC-07 | Duplicate retried operation (idempotency) | Same send/trigger/end retried with same id → no duplicate row; result identical to first. |
| LC-08 | Ride creation sets immutable fields | `id`, `created_at`, `invite_code` set on insert and cannot be changed by subsequent updates. |

### 3.4 Offline & Realtime

| ID | Test | Expected |
|---|---|---|
| OFF-01 | Offline write and reconnect (outbox flush) | Write queued locally while offline; on reconnect, outbox flushed to Supabase; rows appear; no duplicates. |
| OFF-02 | Process death during pending synchronization | Process killed mid-flush; on restart, in-flight outbox items resumed; no data loss, no duplication. |
| OFF-03 | Token refresh | Expired user session refreshed; subsequent Supabase requests use new token; RLS re-evaluates with new `auth.uid()`. |
| OFF-04 | Duplicate and out-of-order events | Messages/SOS arriving out of order or duplicated are deduped by id; older event timestamp does not overwrite newer accepted state (mirrors `RiderSyncPolicy`). |
| OFF-05 | Member removal during subscription | Member removed (REMOVED) while subscribed → realtime subscription payload stops delivering that ride's private data to the removed user's client. |
| OFF-06 | Account switching with pending operations | Switching auth context aborts/replays pending outbox operations under the new user's identity; no cross-user write. |
| OFF-07 | SOS pending/retry/confirmation | SOS insert → pending state; retry on failure bounded (MAX_ATTEMPTS=3); on success, acknowledged/resolved state replicated; on permanent failure, local alert rolled back (mirrors `SosLifecycleAndDeliveryTest`). |

### 3.5 Migration integrity (Firestore → Postgres)

| ID | Test | Expected |
|---|---|---|
| MIG-01 | Counts and field mapping | Row counts in Postgres match Firestore export for `users→profiles`, `rides`, `riders`, `messages`, `sos`; field names/types mapped per `supabase-schema.sql`. |
| MIG-02 | Referential integrity (FKs) | Every `riders.ride_id` references an existing `rides.id`; every `messages.ride_id`/`sos_events.ride_id`/`route_geometry.ride_id` references `rides.id`; `ON DELETE CASCADE` behaves. |
| MIG-03 | Duplicate IDs | Import of a dataset containing duplicate primary keys rejects duplicates without corrupting valid rows. |
| MIG-04 | Timestamp conversion | `serverTimestamp()` sentinel → `timestamptz`; `created_at`/`updated_at` populated; `timestamp_ms`/`last_updated_ms` bigint preserved. |
| MIG-05 | Invalid records rejected | Records violating field-shape validation (e.g. bad role, missing required field) are rejected/quarantined, not imported. |
| MIG-06 | Repeat import without duplication | Re-running the import is idempotent — existing rows updated/skipped, no duplicate rows, counts stable. |
| MIG-07 | Rollback and reconciliation | A failed import can be rolled back; reconciliation report lists mismatched/missing rows between source and target. |

### 3.6 Regression

| ID | Test | Expected |
|---|---|---|
| REG-01 | Run ALL existing Android unit tests | `./gradlew test` pass/fail baseline preserved; no test weakened or deleted. |
| REG-02 | Navigation regression baseline | The navigation-specific tests (`NavigationTelemetryRegressionTest`, `NavEngineAndSosTest`, `RideViewModelRerouteAndDistanceTest`, `RideLifecyclePolicyTest`) all pass — these are the navigation regression baseline. |
| REG-03 | Firestore emulator rules tests (pre-migration baseline) | `npm test` (T-01..T-21) pass against current `firestore.rules` before cutover. |
| REG-04 | Supabase RLS tests (post-migration) | RLS equivalents of T-01..T-21 pass against the Supabase schema after cutover. |

---

## 4. How to Run

### Android unit tests (local, no server needed)
```
cd /home/arpit/Projects/RideTogether
./gradlew test
# or scoped:
./gradlew :app:testDebugUnitTest
```
These use Robolectric + fakes; no Firebase emulator, no Supabase, no device required.

### Firestore rules emulator tests (pre-migration baseline; requires Firebase emulator)
```
cd /home/arpit/Projects/RideTogether
npm test
# i.e. node firestore.test.js
```
Requires `@firebase/rules-unit-testing` installed (devDependency in `package.json`) and the Firestore emulator reachable at `FIRESTORE_EMULATOR_HOST` (default `127.0.0.1:8085`). Per memory: `node_modules` and firebase-cli are NOT yet provisioned, and no Firebase Admin credentials exist, so this cannot run locally today.

### Supabase RLS / policy tests (to be defined; requires live Supabase project)
No test runner or backend suite exists yet. Candidate approaches:
- **pgTAP** unit tests run directly against a Postgres instance with RLS enabled (set `role` / `SET ROLE` to simulate users).
- **Supabase CLI** (`supabase db test` / `supabase gen keys`) against a local Supabase stack (Docker) or a staging project.
- A **backend test suite** (e.g. Node/Python) hitting the Supabase REST/GraphQL endpoint with JWTs for each AUTH/AUTHZ scenario.

These are "to be defined" pending provisioning (see §7).

---

## 5. Pass/Fail Reporting Format

For each test category, record:

| Category | Command | Expected | Actual | Status | Notes |
|---|---|---|---|---|---|
| Android unit tests | `./gradlew test` | All green (baseline) | | | |
| Navigation regression | `./gradlew test --tests '*Navigation*' --tests '*Reroute*' ...` | All green | | | baseline |
| Firestore emulator rules | `npm test` | T-01..T-21 all pass | | | pre-migration |
| Supabase RLS | `<to be defined>` | RLS equivalents of T-01..T-21 pass | | | post-migration |
| Auth (backend) | `<to be defined>` | AUTH-01..06 behave per §3.1 | | | |
| Authorization (backend + RLS) | `<to be defined>` | AUTHZ-01..10 behave per §3.2 | | | |
| Lifecycle & concurrency | `<to be defined>` | LC-01..08 behave per §3.3 | | | |
| Offline & Realtime | `<to be defined>` | OFF-01..07 behave per §3.4 | | | |
| Migration integrity | `<to be defined>` | MIG-01..07 behave per §3.5 | | | |

**Status values:** `PASS`, `FAIL`, `NOT RUN (env limitation)`, `DEFINED BUT NOT IMPLEMENTED`.

**Rule:** Do not weaken or delete any existing test to make migration easier. If a production behavior changes, update the test to assert the new (correct) behavior and record the change.

---

## 6. Supabase RLS Policy Mapping Reference (from `supabase-schema.sql`)

| Table | Select policy | Insert policy | Update policy | Delete policy |
|---|---|---|---|---|
| `profiles` | own profile (`auth.uid() = id`) | own profile | own profile | (none) |
| `rides` | **authenticated** (permissive — see gap below) | leader (`auth.uid() = leader_id`) | leader | leader |
| `riders` | leader or ACTIVE member of ride | leader only | own telemetry (owner); membership (leader) | own membership |
| `messages` | ACTIVE member of ride | member, `sender_id = auth.uid()` | (none: immutable) | (none) |
| `sos_events` | ACTIVE member of ride | member, `rider_id = auth.uid()` | author or leader (lifecycle fields only) | (none) |
| `route_geometry` | ACTIVE member of ride | leader (`for all`) | leader | (none) |

**Known gap:** `rides` select policy is currently `auth.role() = 'authenticated'` (any authenticated user can read any ride). The Firestore equivalent gates ride read on `isMemberOrLeader`. AUTHZ-01 cannot pass until this policy is tightened to membership-gated. Tracked as a release blocker (mirrors memory: "membershipStatus schema is Phase 2 gap" / invite-code trusted backend function).

---

## 7. Environment Limitations

- **No live Supabase project** — backend Auth/Authorization/Offline/Realtime/Migration tests cannot run against Supabase yet.
- **No Firebase Admin credentials** — cannot mint/verify Firebase ID tokens for AUTH-01..06 locally.
- **No `node_modules` / firebase-cli** — `npm test` (firestore.test.js emulator tests) cannot run locally yet.
- **No Android emulator/device necessarily available** — `androidTest` instrumentation tests cannot run; unit tests via Robolectric can.
- **No Supabase CLI** — local Supabase stack / pgTAP RLS tests not available yet.

These limitations mean §3 categories 3.1 (Authentication backend), 3.2 (Authorization backend+RLS), 3.4 (Offline & Realtime against Supabase), and 3.5 (Migration integrity against Postgres) are **planned but not executable** until the above are provisioned. Categories 3.3 (Lifecycle & concurrency, where unit-testable against fakes), 3.6 (Regression via `./gradlew test`), and the Firestore emulator baseline (once node_modules is installed) can proceed.

---

## 8. Deliverables Summary

- **Test plan:** `docs/supabase-migration/TEST_PLAN.md` (this file) — no production source files modified.
- **Test inventory:** §2 (existing Android unit tests + Firestore emulator T-01..T-21 to be replaced by RLS tests).
- **New tests:** §3 (Authentication AUTH-01..06, Authorization AUTHZ-01..10, Lifecycle LC-01..08, Offline & Realtime OFF-01..07, Migration MIG-01..07, Regression REG-01..04).
- **Run commands:** §4 (`./gradlew test`, `npm test`, Supabase RLS "to be defined").
- **Pass/fail reporting:** §5 (per-category table; actual results to be filled during implementation).
- **Environment limitations:** §7.
