# RideTogether — Supabase Migration: Implementation Audit & Unified Plan

**Phase:** Phase 1 deliverable (implementation plan, not implementation)  
**Status:** Design only — no production source files modified  
**Goal:** Reconcile all four subagent findings into one consistent architecture with file ownership, dependencies, acceptance criteria, and risks.  
**Constraint:** Do NOT start implementation until this plan is approved and the architecture is consistent.

---

## 1. Executive Summary

RideTogether is migrating its ride data layer from Firebase Firestore to Supabase PostgreSQL while preserving:

- **Firebase Authentication** (Google Sign-In via CredentialManager) as the sole identity provider
- **Firebase UIDs** as canonical app identities (never Supabase `auth.users`)
- **Room** as the local offline cache + sync outbox
- **Existing navigation engine** (independent of network)

All sensitive reads/writes are mediated by a trusted backend that verifies Firebase ID tokens. The Android app uses the Supabase anon key only for non-sensitive reads (or not at all for sensitive paths). No service_role key or privileged credential ships in the APK.

## 2. Current Architecture State (Pre-Migration Audit)

### 2.1 Firestore (source system)

| Collection / Subcollection | Purpose | Rules |
|---|---|---|
| `users/{userId}` | User profiles | Owner-only read/write |
| `rides/{rideId}` | Ride metadata | Read by member/leader; create/update/delete by leader; immutable `id`, `createdAt`, `inviteCode` |
| `rides/{rideId}/riders/{riderId}` | Rider roster + telemetry | Read by member/leader; create by leader; owner updates own telemetry while ACTIVE; leader changes role/membershipStatus |
| `rides/{rideId}/messages/{messageId}` | Group quick messages | Read by members; create by sender who is a member; immutable after write |
| `rides/{rideId}/sos/{sosId}` | Emergency SOS | Read by members; create by author who is a member; update by author or leader (lifecycle only) |
| `rides/{rideId}/route/current` | Route geometry | Leader-published polyline |

`firestore.rules` is hardened default-deny, membership-gated (line 289 default deny). All 34 emulator tests T-01..T-21 pass.

### 2.2 Android (current state)

- `RideRepository.kt` (~2156 lines) — single repository touching all 25 Firestore call sites across 6 collections/subcollections, 18 distinct operations
- `RideViewModel.kt` — ViewModel-facing StateFlows (`currentRide`, `members`, `activeSos`, `recentMessage`, `recentMessages`, `messageSendError`, `currentRiderStatus`, `joinError`) — must remain source-compatible
- `SupabaseClient.kt` — okhttp REST client, anon key as Bearer, **no Firebase ID token forwarding**
- `SupabaseRideRepository.kt` — Supabase REST repo, **not wired to production flows**
- `RideDatabase.kt` — Room v12 with `ActiveRideReferenceEntity`, `ActiveRideTelemetryEntity`, `OfflineMessageEntity`, `PeerSessionEntity`
- `RideTogetherApplication.kt` — Firebase init + `SupabaseClient.init(this)` in onCreate
- `supabase-schema.sql` — existing starter schema using `auth.users`/`auth.uid()`/`auth.role()` (violates constraints; superseded)

### 2.3 Known security gaps (security agent findings F-01..F-06)

| ID | Finding | Severity |
|---|---|---|
| F-01 | `SupabaseClient` sends anon key as Bearer, no backend mediation, no Firebase ID token forwarding | High |
| F-02 | `SupabaseRideRepository` relies on RLS using `auth.users`/`auth.uid()`/`auth.role()` — violates Firebase UID preservation | High |
| F-03 | No backend Firebase ID token verification anywhere | High |
| F-04 | `rides` SELECT policy is `auth.role() = 'authenticated'` — any authenticated principal reads all rides | High |
| F-05 | No session variable / `current_setting` pattern for passing Firebase UID into RLS | Medium |
| F-06 | Invite-code join is client-side, no server-side atomic validation, no rate limiting | Medium |

## 3. Subagent Findings Summary

Four independent agents were launched in parallel. All four completed.

| Agent | Deliverable | Status |
|---|---|---|
| Testing | `docs/supabase-migration/TEST_PLAN.md` — AUTH-01..06, AUTHZ-01..10, LC-01..08, OFF-01..07, MIG-01..07, REG-01..04 | ✅ Complete |
| Security | `docs/supabase-migration/ARCHITECTURE.md` — identity model, findings F-01..F-06, backend token verification, authorization boundary | ✅ Complete |
| Database | `supabase/migrations/001_initial_schema.sql` + rollback; `docs/supabase-migration/SCHEMA_AND_RLS.md` — 9-table schema, RLS, indexes, triggers, realtime | ✅ Complete |
| Android Data | `docs/supabase-migration/ANDROID_DATA_DESIGN.md` — repository boundary, offline outbox, auth integration, 5-phase migration | ✅ Complete |

## 4. Conflicts Identified and Resolved

### 4.1 SCHEMA_AND_RLS.md overwrite (file path conflict)

**Conflict:** Both the security agent and database agent wrote to `docs/supabase-migration/SCHEMA_AND_RLS.md`. The database agent's version (24.8K, full schema design) overwrote the security agent's version (217 lines, RLS-focused with negative security tests N-P1..P5, N-R1..R8, N-M1..M9, N-MS1..MS6, N-S1..S7, N-A1..A8, and SECURITY DEFINER admin functions).

**Resolution:** Keep the database agent's `SCHEMA_AND_RLS.md` as the primary schema design document. The security agent's RLS content (negative security tests, SECURITY DEFINER admin functions, session variable approach) is incorporated into this IMPLEMENTATION_AUDIT.md and should be merged into `SCHEMA_AND_RLS.md` during implementation. See §4.2 for the RLS approach decision, and §9 for the file merge plan.

### 4.2 RLS enforcement approach (session variable vs JWT claim)

**Conflict:** Two mutually exclusive RLS enforcement mechanisms:

| Approach | Mechanism | Source |
|---|---|---|
| **Session variable** | Backend executes `SET app.current_user_id = '<Firebase UID>'` per request; RLS reads `current_setting('app.current_user_id', true)` | Security agent (ARCHITECTURE.md §3, §5) |
| **JWT custom claim** | Supabase JWT carries `firebase_uid` custom claim; RLS reads `auth.jwt ->> 'firebase_uid'` | Database agent (SCHEMA_AND_RLS.md §1, §8) |

**Resolution:** **Session variable approach is the primary design.** Rationale:

1. It does not require minting Supabase JWTs with custom claims — this was an unresolved open question in the database agent's doc (§14 Q1: "Which component mints the Supabase JWT with the firebase_uid custom claim?").
2. It aligns with the security agent's strong recommendation for backend-mediated access with a restricted DB role.
3. The Firebase ID token is verified by the trusted backend, which then sets the session variable — the UID is never client-trusted.
4. `current_setting()` with `true` (missing_ok) returns NULL when unset, which naturally enforces default-deny.

**Migration of database agent's RLS policies:** All `auth.jwt ->> 'firebase_uid'` references in `SCHEMA_AND_RLS.md` and `001_initial_schema.sql` must be replaced with `current_setting('app.current_user_id', true)`. The JWT claim approach remains documented as a documented alternative if a future phase adopts Supabase Auth custom claims.

### 4.3 Column naming inconsistency

**Conflict:** Different agents used different column names for Firebase UID:

| Column | Security agent | Database agent | Android data agent |
|---|---|---|---|
| Profiles PK | `profiles.id` | `profiles.firebase_uid` | — |
| Ride members FK | `ride_members.user_id` | `ride_members.firebase_uid` | `ride_members.user_id` |
| Rides leader FK | `rides.leader_id` | `rides.leader_id` | — |

**Resolution:** Standardize on `firebase_uid` everywhere for explicitness and consistency:

- `profiles.firebase_uid` (text PK — existing Firebase UID preserved verbatim)
- `ride_members.firebase_uid` (text FK → profiles.firebase_uid)
- `rides.leader_id` (text FK → profiles.firebase_uid)

The database agent's schema already uses `firebase_uid`. The security agent's `profiles.id` and android agent's `ride_members.user_id` references must be updated to `firebase_uid`.

### 4.4 Backend mediation scope

**Conflict:** Security agent says ALL sensitive reads/writes go through the trusted backend. Android data agent says sensitive writes go through backend, but non-sensitive reads (fetchRideById, findRideByInviteCode, fetchRouteGeometry) may use Supabase anon key directly.

**Resolution:** Hybrid model consistent with both agents:

| Operation type | Path | Authorization |
|---|---|---|
| Sensitive writes (create ride, join, telemetry, message, SOS, end ride, transfer leadership, remove member) | Android → HTTPS → trusted backend → Supabase (service-role) | Backend verifies Firebase ID token, checks membership/leadership, writes with service-role |
| Non-sensitive reads (fetch ride by ID, find by invite code, fetch route geometry) | Android → Supabase REST (anon key) + RLS | RLS with `current_setting('app.current_user_id', true)` |
| Invite-code discovery (read ride metadata before joining) | Android → HTTPS → trusted backend (RPC) | Backend validates invite code, returns limited ride metadata (no membership leak) |
| Realtime subscriptions (rides, riders, messages, SOS) | Android → Supabase Realtime WebSocket | RLS with `current_setting('app.current_user_id', true)` |

**Justification:** Non-sensitive reads via anon key + RLS is acceptable because RLS enforces membership at the DB layer (defense-in-depth). Invite-code discovery through the backend prevents ride metadata leakage to non-members (security findings F-04, F-06).

### 4.5 SupabaseClient.token forwarding

**Conflict:** `SupabaseClient.request()` currently sends the anon key as Bearer with **no Firebase ID token forwarding** (F-01). The android data agent notes this must be fixed before any direct Supabase writes.

**Resolution:** Add `Authorization: Bearer <Firebase ID token>` header forwarding to `SupabaseClient.request()` via an optional `idToken` parameter. This enables direct Supabase writes where the token is forwarded and verified via RLS/jwt, as an alternative to the trusted backend for non-critical paths. Sensitive writes still go through the trusted backend with service-role.

## 5. Unified Target Architecture

### 5.1 Identity model

- Firebase Auth (Google Sign-In via CredentialManager) remains the **sole** authentication provider
- `FirebaseUser.getIdToken(true)` supplies the bearer token for trusted-backend calls
- Firebase UID is the canonical app identity, stored verbatim in `profiles.firebase_uid`
- **No Supabase `auth.users` identity is created.** `auth.uid()`, `auth.role()` are not referenced in schema or RLS.
- Supabase Auth is not enrolled unless explicitly required by a future phase

### 5.2 Data flow

```
Android app (HTTPS + Supabase REST)
     │
     ├─ Sensitive writes ──→ Trusted backend API ──→ Supabase (service-role key)
     │      (Firebase ID token)     (verifyIdToken + membership check)
     │
     ├─ Non-sensitive reads ──→ Supabase REST (anon key + RLS)
     │
     └─ Realtime (WebSocket) ───→ Supabase Realtime (anon key + RLS)
```

### 5.3 Data model (from database agent's schema, with conflict resolutions)

**Tables (9):** `app_config`, `profiles`, `rides`, `ride_members`, `ride_messages`, `sos_incidents`, `route_geometry`, `sync_outbox`, `idempotency_keys`

**Key column conventions:**
- `profiles.firebase_uid` — text PK (existing Firebase UID preserved)
- `ride_members.firebase_uid` — text FK → profiles.firebase_uid
- `ride_members.UNIQUE(ride_id, firebase_uid)` — one membership per user per ride
- `rides.invite_code` — text UNIQUE (4..12 chars)
- All `created_at`/`updated_at` are `timestamptz NOT NULL DEFAULT now()` (UTC)
- Client wall-clock fields (`lastUpdatedMs`, `startedAtMs`, `timestampMs`) remain `bigint`
- CHECK constraints on coordinates (-90..90 / -180..180) and enums (RideStatus, RideRole, MembershipStatus, MemberStatus, MovementState, EmergencyState, ConnectionStatus, HeadingSource, QuickMessageType)
- `battery_pct` CHECK 0..100; text lengths: name 1..100, text 1..200, invite_code 4..12

**RLS policies (session variable approach — replaces `auth.jwt ->> 'firebase_uid'`):**

| Table | Select | Insert | Update | Delete |
|---|---|---|---|---|
| profiles | owner only (`current_setting() = firebase_uid`) | owner only | owner only | none (soft-delete) |
| rides | leader or ACTIVE members | leader only | leader only (immutable keys protected) | leader only (soft-delete via deleted_at) |
| ride_members | owner / leader / ACTIVE members | leader only | owner telemetry (while ACTIVE) or leader role/membership | owner leaves or leader removes |
| ride_messages | ACTIVE members | member, sender_id = caller | none (immutable) | none (immutable) |
| sos_incidents | ACTIVE members | member, reporter_id = caller | leader or reporter (lifecycle fields only) | none (audit-retained) |
| route_geometry | ACTIVE members | leader only | leader only | none |
| sync_outbox | owner (via payload.firebase_uid) | owner | owner | owner |
| idempotency_keys | — | — | — | — (bypass via service-role) |

**RLS expression:** `current_setting('app.current_user_id', true) = <column>` (replaces `auth.jwt ->> 'firebase_uid' = <column>` everywhere).

**Backend session setup:** Before each user-facing query, the trusted backend executes:
```sql
SET app.current_user_id = '<verified Firebase UID>';
```

### 5.4 Android data layer (from android data agent)

**New interfaces:**
- `RideRemoteDataSource` — implemented by adapted `SupabaseRideRepository` + `SupabaseClient` (adds token forwarding, Realtime, missing methods)
- `RideLocalDataSource` — new `RoomRideLocalDataSource` wrapping existing DAOs + new `SyncOutboxDao`
- `FirebaseTokenProvider` — wraps `FirebaseUser.getIdToken(true)` with 401 refresh handling
- `RideSyncWorker` — WorkManager persistent outbox processor
- `RealtimeRideDataSource` — Supabase Realtime WebSocket subscriptions replacing 4 Firestore `addSnapshotListener` registrations

**Repository boundary:** `RideRepository` becomes the orchestration layer (remote + local + outbox). ViewModel-facing StateFlows preserved.

**Offline outbox (Room migration v13):** `sync_outbox` table with idempotency key, entity type/ID, op type, payload, retry count, status, nextRetryAtMs, user_id. Transactional writes (local state + outbox record atomically in Room). WorkManager retries with exponential backoff + jitter, max 7 retries, then dead-letter. Retry classification: transient (network/5xx/429) → reschedule; permanent (403/404/409) → dead-letter; 401 → token refresh + one retry.

**SOS handling:** Stays visibly pending until server-confirmed; never silently discarded; permanent failure keeps banner + shows error.

**Active ride restoration:** Preserved exactly — Room `active_ride_reference` → `restoreActiveRide` (non-terminal status check) → Supabase secondary query → commit screen. Signed-out users blocked. New: Supabase-side membership validation before reopening.

### 5.5 Migration path (5 phases)

| Phase | Goal | Behavior change |
|---|---|---|
| Phase 0 | Baseline (already in place) | None |
| Phase 1 | Add outbox + local data source | Outbox staged but not flushed; Firestore unchanged |
| Phase 2 | Swap remote source to Supabase (reads first) | Read call sites route to Supabase REST; Firestore for writes temporarily |
| Phase 3 | Replace listeners with Realtime | 4 snapshot listeners → Realtime subscriptions; Firestore listeners removed |
| Phase 4 | Flip writes to Supabase via outbox | Mutating call sites route through outbox; direct Firestore writes removed |
| Phase 5 | Remove Firestore dependency | Delete `firebase.firestore` usage; keep FirebaseAuth + FirebaseTokenProvider |

At each phase, ViewModel-facing StateFlows remain source-compatible.

## 6. File Ownership

| File | Owner | Purpose | Status |
|---|---|---|---|
| `supabase/migrations/001_initial_schema.sql` | Database agent | Initial schema: 9 tables, RLS, indexes, triggers, realtime | ✅ Created (needs RLS expression update: session variable) |
| `supabase/migrations/001_initial_schema.rollback.sql` | Database agent | Destructive rollback (dev/staging only) | ✅ Created |
| `docs/supabase-migration/SCHEMA_AND_RLS.md` | Database agent (+security agent content to merge) | Full schema design doc | ✅ Created (needs security agent's negative tests + SECURITY DEFINER functions merged) |
| `docs/supabase-migration/ARCHITECTURE.md` | Security agent | Security architecture: identity model, findings, backend token verification | ✅ Created |
| `docs/supabase-migration/TEST_PLAN.md` | Testing agent | Test plan: AUTH-01..06, AUTHZ-01..10, LC-01..08, OFF-01..07, MIG-01..07, REG-01..04 | ✅ Created |
| `docs/supabase-migration/ANDROID_DATA_DESIGN.md` | Android data agent | Android data layer design: repository boundary, outbox, migration path | ✅ Created |
| `docs/supabase-migration/IMPLEMENTATION_AUDIT.md` | Lead agent (this doc) | Unified implementation plan | This document |
| `app/src/main/java/com/example/data/supabase/SupabaseClient.kt` | Android data agent | Add Firebase ID token forwarding | Not yet modified |
| `app/src/main/java/com/example/data/supabase/SupabaseRideRepository.kt` | Android data agent | Adapt to RideRemoteDataSource; add missing methods | Not yet modified |
| `app/src/main/java/com/example/data/firebase/RideRepository.kt` | Android data agent | Refactor to orchestration layer | Not yet modified |
| `app/src/main/java/com/example/ui/viewmodel/RideViewModel.kt` | Android data agent | No changes until final cutover | Not yet modified |
| `firestore.rules` | Security agent | Existing hardened rules; not modified by migration | Existing |
| `firestore.test.js` | Testing agent | Existing emulator tests T-01..T-21 (pre-migration baseline) | Existing |
| `supabase-schema.sql` | Security agent | Existing starter schema (auth.users-based, superseded) | Existing (superseded) |

## 7. Dependencies

### 7.1 New dependencies to add

| Dependency | Purpose | Where |
|---|---|---|
| `androidx.work:work-runtime-ktx` | RideSyncWorker persistent outbox processor | `app/build.gradle.kts` |
| Supabase Realtime client (or WebSocket wrapper) | RealtimeRideDataSource | `app/build.gradle.kts` or custom okhttp WebSocket |
| Firebase Admin SDK (backend) | verifyIdToken(checkRevoked=true) | Trusted backend (Node/Go/Python) |
| `@supabase/supabase-js` or `pgTAP` | Supabase RLS testing | Test environment |

### 7.2 Existing dependencies (reused)

- `okhttp` — SupabaseClient HTTP client
- `moshi` — JSON parsing
- `room` — local database + sync_outbox (migration v13)
- `kotlinx.coroutines` — async operations
- `firebase-auth` — Google Sign-In + ID token
- `firebase-firestore` — existing data source (removed in Phase 5)

### 7.3 Environment provisioning required

| Item | Purpose | Status |
|---|---|---|
| Live Supabase project | RLS/Auth/Offline/Realtime/Migration tests | Not provisioned |
| Firebase Admin credentials | Backend token verification | Not available |
| `node_modules` / firebase-cli | Firestore emulator tests (`npm test`) | Not installed |
| Supabase CLI | Local Supabase stack / pgTAP RLS tests | Not installed |

## 8. Acceptance Criteria

### 8.1 Architecture consistency (gates before implementation)

- [ ] All RLS policies use `current_setting('app.current_user_id', true)` — no `auth.jwt ->> 'firebase_uid'` or `auth.uid()`/`auth.role()` references
- [ ] All Firebase UID columns named `firebase_uid` — no `profiles.id` or `ride_members.user_id`
- [ ] `SupabaseClient.request()` supports Firebase ID token forwarding
- [ ] `rides` select policy is membership-gated (not `auth.role() = 'authenticated'`) — fixes F-04
- [ ] No service_role key or privileged credential in APK — fixes F-01, F-07
- [ ] Backend verifies Firebase ID token with `checkRevoked=true` — fixes F-03
- [ ] All sensitive writes go through trusted backend — fixes F-01

### 8.2 Test acceptance criteria

| Category | Command | Expected | Status |
|---|---|---|---|
| Android unit tests | `./gradlew test` | All green (baseline preserved) | Must pass |
| Navigation regression | `./gradlew test --tests '*Navigation*'` | All green | Must pass |
| Firestore emulator rules | `npm test` | T-01..T-21 all pass | Pre-migration baseline |
| Supabase RLS tests | `<to be defined>` | RLS equivalents of T-01..T-21 pass | Post-migration |
| Auth (backend) | `<to be defined>` | AUTH-01..06 behave per TEST_PLAN §3.1 | Requires provisioning |
| Authorization (backend + RLS) | `<to be defined>` | AUTHZ-01..10 behave per TEST_PLAN §3.2 | Requires provisioning |
| Lifecycle & concurrency | `<to be defined>` | LC-01..08 behave per TEST_PLAN §3.3 | Requires provisioning |
| Offline & Realtime | `<to be defined>` | OFF-01..07 behave per TEST_PLAN §3.4 | Requires provisioning |
| Migration integrity | `<to be defined>` | MIG-01..07 behave per TEST_PLAN §3.5 | Requires provisioning |
| Regression | REG-01..04 | All existing tests remain green | Must pass |

**Rule:** Do not weaken or delete any existing test to make migration easier. If production behavior changes, update the test to assert the new (correct) behavior and record the change.

### 8.3 Security acceptance criteria

- [ ] Default-deny RLS on all tables (no permissive policies)
- [ ] All channels membership-gated (rides, ride_members, ride_messages, sos_incidents, route_geometry)
- [ ] Invite-code discovery via trusted backend RPC (no ride metadata leakage)
- [ ] SECURITY DEFINER admin functions with fixed search_path (validate_invite_code, join_ride, end_ride, transfer_leadership, remove_member)
- [ ] Negative security tests pass: N-P1..P5 (profiles), N-R1..R8 (rides), N-M1..M9 (ride_members), N-MS1..MS6 (messages), N-S1..S7 (sos), N-A1..A8 (admin functions)
- [ ] Immutable fields enforced: `rides.id`, `created_at`, `invite_code` never change on update
- [ ] No hard DELETE on audit tables (ride_messages, sos_incidents, profiles)
- [ ] Soft-delete via `deleted_at` on rides and ride_members

## 9. File Merge Plan (SCHEMA_AND_RLS.md)

The security agent's SCHEMA_AND_RLS.md content was overwritten by the database agent's version. The following content from the security agent's version must be merged into `docs/supabase-migration/SCHEMA_AND_RLS.md`:

1. **RLS expression:** Replace `auth.jwt ->> 'firebase_uid'` with `current_setting('app.current_user_id', true)` in all policies
2. **Negative security tests:** Add N-P1..P5, N-R1..R8, N-M1..M9, N-MS1..MS6, N-S1..S7, N-A1..A8 test cases
3. **SECURITY DEFINER admin functions:** Add `validate_invite_code`, `join_ride`, `end_ride`, `transfer_leadership`, `remove_member` with fixed `search_path`
4. **Authorization boundary table:** Include the backend vs RLS responsibility matrix
5. **Secrets management:** Document that service_role key never ships in APK

This merge should be done during implementation, not before plan approval.

## 10. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| RLS approach mismatch between agents | Medium | High | Session variable approach chosen as primary; JWT documented as alternative |
| SCHEMA_AND_RLS.md content loss (security agent version overwritten) | Medium | Medium | Merge security agent's negative tests + SECURITY DEFINER functions into schema doc |
| No trusted backend exists yet | High | High | Build backend token verification before any sensitive Supabase access |
| SupabaseClient.request() no token forwarding | Medium | High | Add idToken header parameter before direct Supabase writes |
| Room migration v13 collision | Low | Medium | Confirm no other pending schema changes collide with v13 |
| Realtime + BLE mesh coexistence | Medium | Medium | Pause Realtime when mesh is active transport (open question) |
| Invite-code discovery membership leak | Medium | High | Route through trusted backend RPC, not direct anon key read |
| Outbox entry size (route geometry polylines) | Low | Medium | Route geometry writes may bypass outbox (leader-only, less latency-sensitive) |
| Cross-device state conflicts | Low | Medium | Apply sequence-number guard (RiderSyncPolicy.shouldAcceptUpdate) before enqueue |
| WorkManager dependency not present | Low | Low | Add `androidx.work:work-runtime-ktx` to build.gradle.kts |

## 11. Migration Rollout Order

1. **Approve this IMPLEMENTATION_AUDIT.md** — architecture must be consistent before implementation
2. **Merge SCHEMA_AND_RLS.md** — incorporate security agent's negative tests + SECURITY DEFINER functions; update RLS expressions to session variable approach
3. **Fix SupabaseClient.request()** — add Firebase ID token forwarding
4. **Implement Phase 0/1** — outbox table (Room v13), local data source, token provider (no behavior change)
5. **Implement Phase 2** — swap reads to Supabase REST
6. **Implement Phase 3** — replace listeners with Realtime
7. **Implement Phase 4** — flip writes via outbox
8. **Implement Phase 5** — remove Firestore dependency
9. **Run all acceptance tests** — Android unit tests, navigation regression, Supabase RLS tests
10. **Production rollout** — only after all acceptance criteria pass

## 12. Release Blockers (documented from prior phases)

- Invite-code needs trusted backend function (no server-side atomic validation today)
- `membershipStatus` schema is a Phase 2 gap (not yet enforced in all paths)
- `node_modules`/firebase-cli not yet provisioned (blocks `npm test` emulator baseline)
- No Firebase Admin credentials (blocks backend token verification testing)
- No live Supabase project (blocks RLS/Auth/Offline/Realtime/Migration tests)

---

**Next step:** Present this plan to the user for approval. No implementation begins until approved.