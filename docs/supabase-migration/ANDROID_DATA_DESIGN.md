# Android Data Layer Design — Firebase Firestore → Supabase Migration

**Agent:** Android Data Agent  
**Status:** Analysis + design only (no Kotlin files edited)  
**Repo:** `/home/arpit/Projects/RideTogether`  
**Primary source read:** `app/src/main/java/com/example/data/firebase/RideRepository.kt` (~2156 lines), `RideViewModel.kt`, `SupabaseClient.kt`, `SupabaseRideRepository.kt`, `RideModels.kt`, `RideDatabase.kt`, `AuthRepository.kt`, `RideTogetherApplication.kt`, `supabase-schema.sql`, `firestore.rules`

---

## 1. Executive Summary

This document designs the minimal repository refactoring and offline outbox for migrating RideTogether's cloud persistence from Firebase Firestore to Supabase PostgreSQL, while keeping Firebase Auth for sign-in. It is **analysis + design only** — no Kotlin files are edited here.

Key constraints honoured:
- Firebase Auth stays for Google Sign-In; `getIdToken(true)` supplies the bearer token for trusted-backend calls.
- Sensitive write operations (ride lifecycle, SOS, membership) go through a **trusted backend over HTTPS** with the Firebase ID token — NOT direct Supabase anonymous writes. (Direct Supabase anon writes remain an option only for non-sensitive reads, or for writes where the token is forwarded in the `Authorization` header and RLS/jwt verifies it — this is documented as an explicit decision to revisit.)
- Existing ViewModel-facing contracts (`currentRide`, `members`, `activeSos`, `recentMessages`, `joinError`, `messageSendError` StateFlows) are preserved.
- Existing Room tables (`ActiveRideReferenceEntity`, `ActiveRideTelemetryEntity`, `OfflineMessageEntity`, `PeerSessionEntity`, completed rides, etc.) are reused; a new `sync_outbox` table is added.
- Supabase Realtime (WebSocket) replaces Firestore snapshot listeners for live ride/riders/messages/SOS feeds.

---

## 2. Current State Analysis

### 2.1 RideRepository (Firestore-backed, ~2156 lines)

`RideRepository` is the single repository touching every Firestore call site. It holds:
- StateFlows: `currentRide`, `members`, `activeSos`, `recentMessage`, `recentMessages`, `messageSendError`, `currentRiderStatus`, `joinError`.
- Listener registrations: `rideListener`, `ridersListener`, `messagesListener`, `sosListener` (Firestore `addSnapshotListener`).
- A coroutine `repositoryScope` with three loops: location sync (collectLatest), rider-staleness check (5s), heartbeat (10s).
- Room access via lazy `RideDatabase.getInstance(context)` for `ActiveRideReferenceEntity` and `ActiveRideTelemetryEntity`.
- `currentUserId`/`currentUserName` derived from `FirebaseAuth` at construction (stale after sign-out).
- Retry policies in `SosReliability.kt` (`FirestoreWriteRetryPolicy.MAX_ATTEMPTS = 3`, monotonic backoff ≤ 2s).

### 2.2 Supabase layer already present (not wired to production)

- `SupabaseClient` — okhttp REST client, `request(method, path, body, extraHeaders)`. Initialized in `RideTogetherApplication.onCreate` from `BuildConfig.SUPABASE_URL` / `SUPABASE_ANON_KEY`. **No auth token forwarding** today.
- `SupabaseRideRepository` — Supabase REST repo with `fetchRideById`, `fetchActiveRideForUser`, `findRideByInviteCode`, `createRide`, `upsertRider`, `updateRide`, `updateRideStatus`, `saveRouteGeometry`, `sendQuickMessage`, `createSos`, `resolveSos`, `leaveRide`, plus parse helpers. **Not wired** to `RideViewModel` production flows (only exposed as a lazy `supabaseRepository`).
- `supabase-schema.sql` — tables `profiles`, `rides`, `riders`, `messages`, `sos_events`, `route_geometry` with RLS policies. Realtime publication enabled on `rides`, `riders`, `messages`, `sos_events`.

### 2.3 ViewModel contracts (must be preserved)

`RideViewModel` exposes:
- `currentRide: StateFlow<Ride?>`, `members: StateFlow<List<RiderMember>>`, `activeSos`, `recentMessage`, `recentMessages`, `messageSendError`, `currentRiderStatus`, `joinError`.
- `repository = RideRepository(locationTracker, firestore=db, ...)` — Firestore-backed, created eagerly.
- `supabaseRepository: SupabaseRideRepository?` — lazy, not wired to production.
- Restoration: Room `active_ride_reference` → `restoreActiveRide` (non-terminal check) → Firestore secondary `fetchActiveRideForUser` → commit screen. Signed-out users skip restoration.

### 2.4 Authorization model today (Firestore rules)

`firestore.rules` is default-deny with membership-gated reads/writes:
- `users/{userId}`: owner-only.
- `rides/{rideId}`: read by member/leader; create/update/delete by leader only; immutable keys `id`, `createdAt`, `inviteCode`.
- `rides/{rideId}/riders/{riderId}`: read by member/leader; create by leader; owner updates own telemetry while ACTIVE (may set `membershipStatus=LEFT`); leader may change `role`/`membershipStatus` only; delete owner or leader.
- `messages`: read by members; create by sender who is a member; immutable after write.
- `sos_events`: read by members; create by author who is a member; update by author or leader (lifecycle fields only).

The Supabase schema mirrors this with RLS (see `supabase-schema.sql`).

---

## 3. Firestore Call-Site Inventory

Every Firestore call site in `RideRepository.kt` is enumerated below. There are **25 call sites** across **6 collections/subcollections**, grouped into **18 distinct operations**. Each is mapped to a migration target: **Supabase REST (via backend)**, **Room local**, **Supabase Realtime**, or **Removed**.

| # | Collection / doc | Operation | Purpose (from RideRepository.kt) | Migration target |
|---|------------------|-----------|----------------------------------|------------------|
| 1 | `users/{id}` | SET (merge) | `initCurrentUser` — write/update user profile (name, email, photoUrl, motorcycleModel, timestamps) | Supabase REST `profiles` upsert (via `initCurrentUser`), token-forwarded |
| 2 | `rides/{rideId}` | GET | `fetchRideById` — load single ride doc by ID (any ride, not just leader) for restoration | Supabase REST `rides?id=eq.xxx` |
| 3 | `rides` (query) | GET | `fetchActiveRideForUser` — `whereIn(status, [CREATED,LOBBY,ACTIVE])` + filter `leaderId == uid` (leader-oriented active ride lookup) | Supabase REST `rides?leader_id=eq.x&status=in.(...)` |
| 4 | `rides/{rideId}` | SET | `createRide` — create ride document | Supabase REST `rides` insert (leader-only), token-forwarded |
| 5 | `rides` (query) | GET ×4 | `joinRide` — invite-code lookup: `whereEqualTo(inviteCode)` ×3 (raw/lower/trim variants) + fallback `limit(100).get()` scan | Supabase REST `rides?invite_code=eq.x` (indexed) |
| 6 | `rides/{rideId}` | LIST (snapshot) | `listenToRide` ride listener — real-time ride metadata | Supabase Realtime on `rides` |
| 7 | `rides/{rideId}` | UPDATE | `updateRoute` — persist total distance/duration + planned distance | Supabase REST `rides` PATCH `id=eq.x` (via outbox) |
| 8 | `rides/{rideId}` | UPDATE | `updateRoutePlan` — publish route plan (stops, version, selection, options) | Supabase REST `rides` PATCH `id=eq.x` (via outbox) |
| 9 | `rides/{rideId}` | UPDATE | `startRide` — transition status → ACTIVE | Supabase REST `rides` PATCH `id=eq.x` (via outbox) |
| 10 | `rides/{rideId}` | TRANSACTION + UPDATE fallback | `endRide` — transaction `status=COMPLETED` + `elapsedDurationSeconds`; fallback queued update | Supabase REST `rides` PATCH `id=eq.x` (via outbox, idempotent) |
| 11 | `rides/{rideId}` | UPDATE | `transferLeadership` — update `leaderId` | Supabase REST `rides` PATCH `id=eq.x` (via outbox, leader-only) |
| 12 | `rides/{id}/riders/{uid}` | UPDATE | `sendRiderHeartbeat` — refresh `lastUpdatedMs` + `updatedAt` | Supabase REST `riders` PATCH (via outbox, throttled) |
| 13 | `rides/{id}/riders/{uid}` | SET (merge) | `processLocationUpdate` — rider telemetry/location sync (every GPS fix) | Supabase REST `riders` upsert (via outbox, coalesced/throttled) |
| 14 | `rides/{id}/riders/{uid}` | SET (merge) | `joinRide` — register joining member document | Supabase REST `riders` insert (leader-created), token-forwarded |
| 15 | `rides/{id}/riders/{uid}` | UPDATE | `leaveRide` — set `membershipStatus = LEFT` | Supabase REST `riders` PATCH (via outbox) |
| 16 | `rides/{id}/riders/{newLeaderId}` + `{currentUid}` | UPDATE ×2 | `transferLeadership` — set role LEADER/MEMBER | Supabase REST `riders` PATCH (via outbox) |
| 17 | `rides/{id}/riders` | LIST (snapshot) | `listenToRide` riders listener — real-time all riders roster | Supabase Realtime on `riders` |
| 18 | `rides/{id}/messages/{msgId}` | SET | `sendQuickMessage` — send group message (stable id, retry loop) | Supabase REST `messages` insert (via outbox, idempotent) |
| 19 | `rides/{id}/messages` | LIST (snapshot) | `listenToRide` messages listener — real-time group chat | Supabase Realtime on `messages` |
| 20 | `rides/{id}/sos/{sosId}` | SET | `triggerSos` — create SOS event | Supabase REST `sos_events` insert (via outbox; SOS stays pending until server-acked) |
| 21 | `rides/{id}/sos/{sosId}` | TRANSACTION | `acknowledgeSos` — `arrayUnion(acknowledgedBy, uid)` | Supabase REST `sos_events` PATCH `id=eq.x` (append ack) |
| 22 | `rides/{id}/sos/{sosId}` | TRANSACTION | `resolveSosInternal` — set `isResolved=true` + `resolvedAtMs` | Supabase REST `sos_events` PATCH `id=eq.x` |
| 23 | `rides/{id}/sos` | LIST (snapshot) | `listenToRide` SOS listener — real-time SOS feed | Supabase Realtime on `sos_events` |
| 24 | `rides/{id}/route/current` | SET | `updateRoute` — persist route geometry (polyline + steps) | Supabase REST `route_geometry` upsert `ride_id=eq.x` |
| 25 | `rides/{id}/route/current` | GET | `fetchRouteGeometry` — read persisted route geometry on restore | Supabase REST `route_geometry?ride_id=eq.x` |

### 3.1 Target mapping summary

| Target | Call sites | Rationale |
|--------|-----------|-----------|
| Supabase REST (via backend, token-forwarded) | 1, 4, 14 (profile/ride-creation/join) | Sensitive writes requiring authorization |
| Supabase REST (idempotent, via outbox) | 2, 3, 5, 7–13, 15–16, 18, 20–22, 24 | Data-plane reads/writes queued in outbox when offline |
| Supabase Realtime | 6, 17, 19, 23 | Live listeners replaced by WebSocket subscriptions |
| Room local | Room writes already local (`persistActiveRideReference`, `clearActiveRideReference`, telemetry); no change needed | Local state stays local |
| Removed | none (all call sites retained in some form) | — |

---

## 4. Repository Boundary Design

### 4.1 Design principles

- **Adapt, don't duplicate.** The existing `SupabaseClient`, `SupabaseRideRepository`, Room DAOs, and `RideDatabase` are reused. New interfaces sit *around* them rather than rewriting them.
- **Single production repository.** `RideRepository` becomes the orchestration layer (remote + local + outbox). `SupabaseRideRepository` becomes the concrete `RideRemoteDataSource` implementation.
- **ViewModel contract preserved.** All StateFlows and method signatures consumed by `RideViewModel` remain source-compatible.
- **Offline-first.** Writes are accepted locally first (Room + outbox), then synced to Supabase when connectivity returns. Navigation engine is independent of network.

### 4.2 Interfaces

#### `RideRemoteDataSource` (new interface)

Abstracts cloud reads/writes. Implemented by `SupabaseRideRepository` (adapted) and, in future, by a trusted-backend HTTP client for sensitive ops.

```kotlin
interface RideRemoteDataSource {
    // --- Profiles (users/{id} → profiles) ---
    suspend fun upsertProfile(id: String, name: String, email: String?, photoUrl: String?,
                              motorcycleModel: String): Boolean

    // --- Rides ---
    suspend fun fetchRideById(rideId: String): Ride?
    suspend fun fetchActiveRideForLeader(leaderId: String): Ride?
    suspend fun findRideByInviteCode(code: String): Ride?
    suspend fun createRide(ride: Ride, leaderRider: RiderMember): Boolean
    suspend fun updateRide(rideId: String, updates: Map<String, Any>): Boolean
    suspend fun updateRideStatus(rideId: String, status: RideStatus): Boolean
    suspend fun transferLeadership(rideId: String, newLeaderId: String): Boolean

    // --- Riders (telemetry + membership) ---
    suspend fun upsertRider(rideId: String, rider: RiderMember): Boolean
    suspend fun setRiderMembership(rideId: String, userId: String, status: MembershipStatus): Boolean

    // --- Messages ---
    suspend fun sendMessage(rideId: String, msg: QuickMessage): Boolean

    // --- SOS ---
    suspend fun createSos(rideId: String, sos: SosEvent): Boolean
    suspend fun acknowledgeSos(sosId: String, ackingUserId: String): Boolean
    suspend fun resolveSos(sosId: String): Boolean

    // --- Route geometry ---
    suspend fun saveRouteGeometry(rideId: String, geometry: StoredRouteGeometry): Boolean
    suspend fun fetchRouteGeometry(rideId: String): StoredRouteGeometry?

    // --- Realtime (replaces Firestore snapshot listeners) ---
    fun subscribeToRide(rideId: String, onChange: (Ride) -> Unit): () -> Unit
    fun subscribeToRiders(rideId: String, onChange: (List<RiderMember>) -> Unit): () -> Unit
    fun subscribeToMessages(rideId: String, onChange: (QuickMessage) -> Unit): () -> Unit
    fun subscribeToSos(rideId: String, onChange: (SosEvent) -> Unit): () -> Unit
}
```

`SupabaseRideRepository` already implements most of these methods (`fetchRideById`, `fetchActiveRideForUser`, `findRideByInviteCode`, `createRide`, `upsertRider`, `updateRide`, `updateRideStatus`, `saveRouteGeometry`, `fetchRouteGeometry`, `sendQuickMessage`, `createSos`, `resolveSos`, `leaveRide`). It needs:
- A constructor accepting a `FirebaseTokenProvider` so it can forward the ID token for sensitive ops.
- Realtime subscription methods (Supabase Realtime WebSocket) — not yet present.
- `transferLeadership`, `setRiderMembership`, `acknowledgeSos` — not yet present.

#### `RideLocalDataSource` (new interface)

Abstracts Room. Implemented by a new `RoomRideLocalDataSource` wrapping existing DAOs.

```kotlin
interface RideLocalDataSource {
    // Active ride reference (existing ActiveRideReferenceDao)
    suspend fun getActiveRideReference(): ActiveRideReferenceEntity?
    suspend fun insertOrReplaceActiveRideReference(entity: ActiveRideReferenceEntity)
    suspend fun clearAllActiveRideReferences()

    // Active ride telemetry (existing ActiveRideTelemetryDao)
    suspend fun getTelemetry(rideId: String): ActiveRideTelemetryEntity?
    suspend fun insertOrReplaceTelemetry(entity: ActiveRideTelemetryEntity)
    suspend fun clearAllTelemetry()

    // --- NEW: sync outbox (new table + DAO) ---
    suspend fun insertOutboxEntry(entry: SyncOutboxEntry)
    suspend fun getPendingOutbox(limit: Int): List<SyncOutboxEntry>
    suspend fun getOutboxEntry(id: String): SyncOutboxEntry?
    suspend fun updateOutboxStatus(id: String, status: OutboxStatus, error: String?)
    suspend fun deleteOutboxEntry(id: String)
    suspend fun clearOutboxForUser(userId: String)   // sign-out / account switch
    suspend fun pruneObsoleteGps(olderThanMs: Long)   // coalesce location updates
}
```

Reuses existing `ActiveRideReferenceDao`, `ActiveRideTelemetryDao`, `RideDatabase`.

#### `RideRepository` (refactored, the production orchestration layer)

Becomes the single source of truth consumed by `RideViewModel`. Orchestrates `RideRemoteDataSource` + `RideLocalDataSource` + outbox + `FirebaseTokenProvider`. Keeps all existing StateFlows and public method signatures.

```kotlin
interface RideRepository {
    // ViewModel-facing flows (unchanged signatures)
    val currentRide: StateFlow<Ride?>
    val members: StateFlow<List<RiderMember>>
    val activeSos: StateFlow<SosEvent?>
    val recentMessage: StateFlow<QuickMessage?>
    val recentMessages: StateFlow<List<QuickMessage>>
    val messageSendError: StateFlow<Boolean>
    val currentRiderStatus: StateFlow<MemberStatus>
    val joinError: StateFlow<String?>

    // Lifecycle
    fun initCurrentUser(id: String, name: String, motorcycleModel: String)
    fun setMotorcycleModel(model: String)
    suspend fun fetchRideById(rideId: String): Ride?
    internal suspend fun restoreActiveRide(rideId: String): Ride?
    suspend fun fetchActiveRideForUser(): Ride?
    fun processLocationUpdate(loc: LocationUpdate)
    fun sendRiderHeartbeat()
    fun checkRidersStaleness()

    // Ride mutations
    suspend fun createRide(...): Ride
    suspend fun joinRide(inviteCode: String, userLoc: LatLng): Result<Ride>
    fun startRide()
    suspend fun endRide(): Result<Unit>
    fun onSharedRideCompleted(rideId: String)
    fun leaveRide()
    fun transferLeadership(newLeaderId: String)

    // Messages
    fun sendQuickMessage(type: QuickMessageType)

    // SOS
    suspend fun triggerSos(): Result<SosEvent>
    fun acknowledgeSos(sosId: String)
    fun dismissSosBanner(sosId: String?)
    suspend fun resolveSosInternal(sosId: String? = null): Result<Unit>
    fun resolveSos()
    fun onMeshSosReceived(event: SosEvent)
    fun onMeshRiderStatusReceived(status: RiderStatusMeshRelay.ParsedRiderStatus): Boolean

    // Route
    fun updateRoute(routePoints, totalDistanceMeters, totalDurationSeconds, steps)
    fun updateRoutePlan(routeStops, planVersion, selectedRouteIndex, routeOptions)
    suspend fun fetchRouteGeometry(rideId: String): StoredRouteGeometry?

    // Lifecycle
    fun cleanup()
    internal suspend fun clearAllLocalRideState()
    internal fun isSharedRideCompletedObserved(rideId: String): Boolean
    internal fun applySharedRideStatusForTest(next: RideStatus)
    internal fun ingestSharedMessageForTest(msg: QuickMessage)
    internal fun updateLeaderIdForTest(newLeaderId: String)
}
```

#### `FirebaseTokenProvider` (new interface)

Obtains a fresh Firebase ID token and handles expiration / 401.

```kotlin
interface FirebaseTokenProvider {
    /** Returns a fresh ID token, refreshing if expired or null. */
    suspend fun getFreshIdToken(): String?

    /** Call on HTTP 401 to force a token refresh before retry. */
    fun onUnauthorized()
}
```

Production implementation wraps `FirebaseAuth.getInstance().currentUser?.getIdToken(true)`:

```kotlin
class FirebaseAuthTokenProvider : FirebaseTokenProvider {
    override suspend fun getFreshIdToken(): String? {
        return FirebaseAuth.getInstance().currentUser
            ?.getIdToken(true)   // forceRefresh = true
            ?.token
    }
    override fun onUnauthorized() {
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)  // fire refresh
    }
}
```

#### `RideSyncWorker` (new, WorkManager)

Persistent outbox processor. Runs when the app is backgrounded or the device is offline-then-online.

```kotlin
class RideSyncWorker(context, params) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pending = localDataSource.getPendingOutbox(limit = 50)
        for (entry in pending) {
            val result = syncOne(entry)   // classify transient vs permanent
            when (result) {
                OutboxSyncResult.SUCCESS -> localDataSource.deleteOutboxEntry(entry.id)
                OutboxSyncResult.TRANSIENT ->  // bump retryCount, exponential backoff + jitter, reschedule
                OutboxSyncResult.PERMANENT ->  // move to dead-letter, surface to user, stop retrying
            }
        }
        return if (pending.isEmpty()) Result.success() else Result.retry()
    }
}
```

#### `RealtimeRideDataSource` (new)

Wraps Supabase Realtime WebSocket channels for `rides`, `riders`, `messages`, `sos_events`. Replaces the four `addSnapshotListener` registrations in `listenToRide`.

### 4.3 Mapping to existing architecture

| New interface | Existing class(es) it adapts / reuses | Notes |
|---------------|--------------------------------------|-------|
| `RideRemoteDataSource` | `SupabaseRideRepository` + `SupabaseClient` | Add token forwarding + Realtime + missing methods |
| `RideLocalDataSource` | `RideDatabase`, `ActiveRideReferenceDao`, `ActiveRideTelemetryDao`, new `SyncOutboxDao` | Room v12 → add migration v13 for `sync_outbox` |
| `FirebaseTokenProvider` | `AuthRepository` (FirebaseAuth) | Thin wrapper around `getIdToken(true)` |
| `RideSyncWorker` | `WorkManager` (new dep) | Persistent retries |
| `RealtimeRideDataSource` | Supabase Realtime (WebSocket) | New; replaces snapshot listeners |
| `RideRepository` | Existing `RideRepository` | Refactored to orchestrate the above |

### 4.4 Where auth token enters

- `RideRemoteDataSource` impls that perform **sensitive writes** (createRide, updateRideStatus, transferLeadership, setRiderMembership, createSos, resolveSos, acknowledgeSos) receive the ID token from `FirebaseTokenProvider` and send it to a trusted backend over HTTPS.
- The trusted backend verifies the Firebase ID token, then writes to Supabase **with a service-role key** (server-side), bypassing client RLS but enforcing its own membership/authorization checks.
- Non-sensitive reads (fetchRideById, findRideByInviteCode, fetchRouteGeometry) may use Supabase anon key directly (as today), or via the backend — decision left to implementation.

> **Design decision to document:** Sensitive ops do NOT use direct Supabase anonymous writes from the client. If direct Supabase writes are ever used for sensitive ops, the Firebase ID token MUST be forwarded in the `Authorization: Bearer <id_token>` header and the backend/RLS must verify it. `SupabaseClient.request()` currently does not forward any auth token — this must be added before any sensitive write uses the client directly.

---

## 5. Auth Integration

### 5.1 Token flow

1. User signs in via Google Sign-In (`AuthRepository.signInWithGoogle`) → `FirebaseUser`.
2. `FirebaseTokenProvider.getFreshIdToken()` calls `FirebaseUser.getIdToken(true)` → fresh ID token (auto-refreshes near expiry).
3. The ViewModel / repository passes the token to `RideRemoteDataSource` for each sensitive write.
4. `RideRemoteDataSource` sends `POST /api/...` (trusted backend) with `Authorization: Bearer <id_token>`.
5. Trusted backend verifies the Firebase ID token (Firebase Admin SDK), extracts `uid`, enforces membership, then writes to Supabase with service-role key.
6. On HTTP 401, `FirebaseTokenProvider.onUnauthorized()` forces a token refresh and the write is retried once.

### 5.2 Signed-out user handling

- `currentUserId`/`currentUserName` in `RideRepository` must be re-derived from `FirebaseAuth` on each operation (not cached at construction), because Firebase Auth persists sessions across app relaunches and the cached value can go stale after sign-out.
- Startup restoration (Section 7) refuses to restore for `auth.currentUser == null`.
- On sign-out: `RideSyncWorker` queue is cleared for that user (`clearOutboxForUser`), listeners detached, local ride state cleared (`clearAllLocalRideState`).

---

## 6. Offline Outbox Design (Room-backed sync outbox)

### 6.1 Goals

- Accept writes offline; persist locally + enqueue an outbox record atomically.
- Persistent retries via WorkManager with exponential backoff + jitter.
- Idempotent backend operations (stable idempotency keys) so retries never duplicate.
- Retry classification: transient (network) vs permanent (403/404/409/schema) → dead-letter.
- SOS stays visibly pending until server-acknowledged; never silently discard unconfirmed SOS.
- Coalesce obsolete GPS updates; throttle location writes; navigation engine independent of network.
- Safe cancellation on sign-out / account switch; stale-write rejection; legal state-transition checks.

### 6.2 Outbox schema (new Room migration v13)

```kotlin
@Entity(tableName = "sync_outbox",
    indices = [
        Index(value = ["user_id", "status", "created_at_ms"]),
        Index(value = ["idempotency_key"], unique = true)
    ])
data class SyncOutboxEntry(
    @PrimaryKey val id: String,                      // UUID, stable per logical op
    val userId: String,                              // queue isolation per signed-in user
    val entityType: String,                          // "ride" | "rider" | "message" | "sos" | "route_geometry" | "profile"
    val entityId: String,                            // target entity id (rideId, riderId, msgId, sosId, …)
    val opType: String,                              // "CREATE" | "UPDATE" | "UPSERT" | "PATCH" | "DELETE"
    val idempotencyKey: String,                      // stable key sent to backend for dedup
    val payload: String,                             // JSON serialized payload
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val retryCount: Int = 0,
    val maxRetries: Int = 7,
    val status: String = "PENDING",                  // PENDING | IN_FLIGHT | DONE | PERMANENT_FAILURE | DEAD_LETTER
    val lastError: String?,
    val nextRetryAtMs: Long
)

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncOutboxEntry)
    @Query("SELECT * FROM sync_outbox WHERE status = 'PENDING' AND nextRetryAtMs <= :nowMs AND user_id = :userId ORDER BY created_at_ms ASC LIMIT :limit")
    suspend fun getPendingForUser(userId: String, nowMs: Long, limit: Int): List<SyncOutboxEntry>
    @Query("SELECT * FROM sync_outbox WHERE id = :id")
    suspend fun getById(id: String): SyncOutboxEntry?
    @Query("UPDATE sync_outbox SET status = :status, lastError = :error, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String?, nowMs: Long)
    @Query("UPDATE sync_outbox SET retryCount = retryCount + 1, nextRetryAtMs = :nextRetry, status = 'PENDING', updatedAtMs = :nowMs WHERE id = :id")
    suspend fun scheduleRetry(id: String, nextRetryMs: Long, nowMs: Long)
    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteById(id: String)
    @Query("DELETE FROM sync_outbox WHERE user_id = :userId")
    suspend fun clearForUser(userId: String)
    @Query("DELETE FROM sync_outbox WHERE status IN ('DONE','PERMANENT_FAILURE') AND createdAtMs < :cutoff")
    suspend fun pruneDoneOlderThan(cutoffMs: Long)
}
```

Migration v13 adds `sync_outbox` table. Entity added to `RideDatabase.entities`, version bumped to 13, `MIGRATION_12_13` added.

### 6.3 Transactional write pattern

Every mutating repository method follows this pattern (example: `processLocationUpdate`):

```kotlin
// 1. Apply optimistic local state immediately (UI stays responsive)
_currentRide.value = updatedRide
// 2. Persist local state + outbox record ATOMICALLY in a Room transaction
runInTransaction {
    activeRideTelemetryDao.insertOrReplace(telemetry)   // local telemetry
    syncOutboxDao.insert(SyncOutboxEntry(               // outbox record
        id = uuid, entityType = "rider", entityId = rideId,
        opType = "UPSERT", idempotencyKey = idempotencyKey,
        payload = json, createdAtMs = now, updatedAtMs = now,
        userId = currentUserId, status = "PENDING", nextRetryAtMs = now
    ))
}
// 3. Fire-and-forget enqueue WorkManager one-off sync (best effort when online)
RideSyncWorker.enqueue()
```

The Room transaction guarantees: either both local state and outbox record persist, or neither. The UI reflects local state immediately; the backend converges eventually.

### 6.4 WorkManager retry policy

- Trigger: `OneTimeWorkRequest` for `RideSyncWorker`, enqueued after every outbox insert, plus a periodic resync on connectivity change (ConnectivityBroadcastReceiver → enqueue).
- Backoff: exponential with jitter. Attempt `n` delay = `base * 2^(n-1) + random(0..jitter)`, capped (e.g. base 1s, cap 60s, jitter ±25%).
- Max retries: 7 per entry. After exhaustion → `PERMANENT_FAILURE` / dead-letter, surface error to UI (`messageSendError` for messages, equivalent for ride/SOS).
- Retry classification:
  - **Transient** (network: `UnknownHostException`, `SocketTimeoutException`, `ConnectException`, HTTP 5xx, 429): reschedule with backoff.
  - **Permanent** (HTTP 401 → token refresh then retry once; 403/404/409/422 → permanent): mark dead-letter, stop retrying, surface to user.
- Idempotency: `idempotencyKey` sent to backend; backend dedups by key so a retried `CREATE` never creates a duplicate document. Updates are applied as idempotent PATCHs keyed by entity ID.

### 6.5 GPS coalescing & throttling (outbox-specific)

`processLocationUpdate` fires on every GPS fix (high frequency). To avoid flooding the outbox:
- **Coalescing:** Only enqueue a rider outbox entry if ≥ N seconds elapsed since the last enqueued entry for the same ride/user, OR the distance moved ≥ threshold (e.g. 10 m), OR status changed. Intermediate fixes update only in-memory `_members` / local telemetry.
- **Throttling:** Cap outbox rider writes to at most 1 per 10–30 seconds per ride when offline; burst on reconnect.
- **Navigation engine independence:** The nav engine (`NavEngine`) reads from `_routeResult` / local ride state, never from the outbox or network. Offline route guidance continues uninterrupted.

### 6.6 SOS handling (never silently discard)

- `triggerSos()`: set `_activeSos.value` locally immediately (visible pending), persist local state, enqueue outbox `CREATE` with `idempotencyKey = sosId`. The banner stays visible until the server write is confirmed (`DONE`) or a permanent failure is recorded.
- On reconnect / worker retry: retry the SOS `CREATE` until confirmed. Never mark a SOS `DONE` without a successful server write.
- `acknowledgeSos` / `resolveSosInternal`: enqueue outbox `PATCH` entries; local state updated optimistically but the outbox record keeps `PENDING` until server confirms.
- If permanent failure: keep `_activeSos.value` visible, show an error ("SOS not confirmed — check connection"), do NOT silently clear.

### 6.7 Reconnection reconciliation

When connectivity returns (or worker runs):
1. Drain `sync_outbox` for the current user in `created_at_ms` order.
2. For each entry, send to backend with idempotency key.
3. On success → `DONE`, delete entry.
4. On transient failure → `scheduleRetry` with backoff.
5. On permanent failure → `PERMANENT_FAILURE`, dead-letter, surface error.

**Stale-write rejection:** Before applying an outbox write, the backend checks legal state transitions (e.g. cannot transition COMPLETED → ACTIVE; cannot update a ride whose `membershipStatus` is LEFT/REMOVED/BANNED). Rejected writes are marked permanent failure, not silently retried forever.

**Duplicate handling:** Idempotency keys make retries safe. On the client, `QuickMessage` dedup by stable `id` (existing `QuickMessageRegistry`) prevents double display of echo + server snapshot.

### 6.8 Sign-out / account switch safety

- `RideViewModel.signOut()` → `RideRepository.leaveRide()` → `clearAllLocalRideState()` + detach listeners.
- Additionally `syncOutboxDao.clearForUser(previousUserId)` — cancels all pending outbox entries for the signing-out user so a different account's device never flushes the previous user's queue.
- `FirebaseTokenProvider.getFreshIdToken()` returns null when signed out → sensitive writes are rejected locally before enqueue.

---

## 7. Active Ride Restoration

Preserve existing behavior exactly; add Supabase-side membership validation.

Flow (in `RideViewModel` init, already implemented, unchanged in contract):
1. If `authRepository.currentUser == null` → `RestorationState.NONE` (never restore for signed-out/never-signed-in users — prevents a stale Room reference surfacing a private ride to someone who never signed in this session).
2. Read Room `active_ride_reference` (`getReference()`).
3. If reference exists and age ≤ 24h and status not COMPLETED/CANCELLED → call `repository.restoreActiveRide(reference.rideId)`:
   - `fetchRideById(rideId)` → verify doc exists and `RideLifecyclePolicy.isTerminal(status) == false`.
   - If terminal/missing → clear reference, return null.
   - Else set `_currentRide`, `persistActiveRideReference`, attach listeners (Realtime), return ride.
4. Else → secondary check: `fetchActiveRideForUser()` (leader-oriented query).
5. Commit navigation only after restoration resolves (`RESTORED` / `NONE` / `FAILED`).

**Supabase-side membership validation (new):** The restore path must validate that the restoring user is an ACTIVE member of the ride (a rider row with `user_id = auth.uid` and `membership_status = 'ACTIVE'`) before reopening. This replaces the Firestore `isMember` rule. In the trusted-backend model, the restore call sends the ID token and the backend enforces membership via RLS/row check. A user who is not a member (or never signed in) cannot restore a private ride.

**Reference freshness:** The 24h staleness cap (`MAX_ACTIVE_RIDE_REFERENCE_AGE_MS`) and terminal-status check prevent resurrecting a ride completed on another device. `sharedCompletedRideIds` / `sharedCompletionHandledRideId` idempotency guards prevent repeated terminal handling.

---

## 8. Migration Path (RideRepository → new boundary)

### Phase 0 — Baseline (already in place)
- `SupabaseClient` + `SupabaseRideRepository` exist; `SupabaseClient.init(this)` called in `RideTogetherApplication.onCreate`; `supabaseRepository` lazy exposed in `RideViewModel` (not wired).
- Room v12 with active ride reference + telemetry.

### Phase 1 — Add outbox + local data source (no behavior change)
- Add `sync_outbox` table (Room migration v13) + `SyncOutboxDao`.
- Add `RoomRideLocalDataSource` implementing `RideLocalDataSource`.
- Add `FirebaseTokenProvider` + `FirebaseAuthTokenProvider`.
- `RideRepository` keeps Firestore as the remote source; outbox writes are *staged* but not yet flushed to Supabase. Existing Firestore calls unchanged.

### Phase 2 — Swap remote source to Supabase (reads first)
- Wire `RideRepository.remoteDataSource = SupabaseRideRepository` (adapted to `RideRemoteDataSource`).
- Route read call sites (fetchRideById, fetchActiveRideForUser, findRideByInviteCode, fetchRouteGeometry) to Supabase REST.
- Keep Firestore for writes temporarily, or flip per-collection.

### Phase 3 — Replace listeners with Realtime
- Replace the four `addSnapshotListener` registrations in `listenToRide` with `RealtimeRideDataSource` subscriptions (rides, riders, messages, sos_events).
- Remove Firestore listener registrations.

### Phase 4 — Flip writes to Supabase via outbox
- Route mutating call sites through `RideLocalDataSource` + outbox; `RideSyncWorker` drains to Supabase.
- Remove direct Firestore writes.

### Phase 5 — Remove Firestore dependency
- Delete `firebase.firestore` usage from `RideRepository`; keep `FirebaseAuth` + `FirebaseTokenProvider` for auth.
- Remove `useEmulator` test hooks from production paths (tests inject `sosBackend` / fakes as today).

At each phase, ViewModel-facing StateFlows remain source-compatible, so `RideViewModel` needs no changes until the final cutover.

---

## 9. Open Questions

1. **Trusted backend vs direct Supabase anon writes:** The design defaults to sending sensitive writes through a trusted backend over HTTPS with the Firebase ID token. Is a trusted backend function available, or should direct Supabase writes with forwarded ID token + RLS be used instead? `SupabaseClient.request()` currently forwards no auth token — this must be added if direct writes are chosen.

2. **Realtime connection management:** Supabase Realtime WebSocket requires maintaining a persistent connection. How does this interact with the existing BLE mesh (`MeshManager`, `MeshForegroundService`) for offline rides? Should Realtime subscriptions be paused when mesh is the active transport?

3. **WorkManager dependency:** Is `androidx.work` already a Gradle dependency? If not, it must be added to `app/build.gradle.kts`.

4. **Room migration versioning:** RideDatabase is at version 12. Adding `sync_outbox` requires migration 12→13. Confirm no other pending schema changes collide with v13.

5. **Invite-code lookup without membership:** Today's Firestore rules allow authenticated callers to read ride metadata (needed for invite-code discovery before membership). Supabase RLS on `rides` currently allows `select` to all authenticated users (`auth.role() = 'authenticated'`). Is this acceptable, or should invite-code resolution go through a trusted backend function (RPC) to avoid leaking ride metadata to non-members?

6. **Outbox entry size limit:** Rider telemetry payloads are small, but route geometry polylines can be large. Should `route_geometry` writes go through the outbox at all, or be written directly (they're leader-only and less latency-sensitive)?

7. **Testing strategy:** Tests currently inject `sosBackend` for SOS and use the Firestore emulator. How are outbox + WorkManager behaviors tested (Robolectric + `WorkManagerTestInit`, or fake `CoroutineScope`)?

8. **Token refresh on 401 mid-ride:** If the Firebase ID token expires during a long ride, every outbox write hitting 401 must trigger `onUnauthorized()` + refresh + retry. Is there a central place to intercept 401s, or does each `RideRemoteDataSource` method handle it?

9. **Cross-device state conflicts:** When two members write conflicting rider telemetry concurrently, Supabase last-write-wins (or RLS). Is client-side conflict resolution needed (e.g. sequence-number guard from `RiderSyncPolicy.shouldAcceptUpdate`) applied before enqueueing?

---

## 10. Deliverables checklist

- [x] Complete call-site inventory (25 sites, 6 collections, mapped to targets) — Section 3
- [x] Repository boundary interfaces (`RideRemoteDataSource`, `RideLocalDataSource`, `RideRepository`, `FirebaseTokenProvider`, `RideSyncWorker`, `RealtimeRideDataSource`) mapped to existing classes — Section 4
- [x] ViewModel-facing contracts preserved — Sections 4.2, 8
- [x] Offline outbox schema + transactional write + WorkManager retries + backoff/jitter + idempotency + retry classification + permanent failure + reconciliation + sign-out cancellation + stale-write rejection + legal state transitions + GPS coalescing/throttling + SOS-pending-until-acked — Section 6
- [x] Auth integration (Firebase ID token → trusted backend over HTTPS; direct-Supabase alternative documented) — Sections 4.4, 5
- [x] Active ride restoration (Room reference + non-terminal verify + membership validate; signed-out blocked) — Section 7
- [x] Migration path (5 phases, ViewModel-compatible) — Section 8
- [x] Open questions — Section 9

No Kotlin files were edited. Next step (when authorized): implement Phase 0/1 (outbox table, local data source, token provider) without changing existing Firestore behavior.