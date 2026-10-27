package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.location.LocationTracker
import com.example.data.location.LocationUpdate
import com.example.data.location.StopDetector
import com.example.data.model.ConnectionStatus
import com.example.data.model.EmergencyState
import com.example.data.model.HeadingSource
import com.example.data.model.MovementState
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.MembershipStatus
import com.example.data.model.NavigationStep
import com.example.data.model.QuickMessage
import com.example.data.model.QuickMessageType
import com.example.data.model.Ride
import com.example.data.model.RideLifecyclePolicy
import com.example.data.model.RideRole
import com.example.data.model.RideStatus
import com.example.data.model.RiderMember
import com.example.data.model.RouteOptionSummary
import com.example.data.model.RoutePlanCodec
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopCodec
import com.example.data.model.SosEvent
import com.example.data.db.ActiveRideReferenceEntity
import com.example.data.db.SyncOutboxEntity
import com.example.data.offline.mesh.RiderStatusMeshRelay
import com.example.data.offline.mesh.SosMeshRelay
import com.example.data.repository.RideLocalDataSource
import com.example.data.repository.RideRemoteDataSource
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonAdapter

class RideRepository(
    val locationTracker: LocationTracker,
    val context: Context? = null,
    val stopDetector: StopDetector = StopDetector(
        stopSpeedThresholdKmh = 5.0,
        resumeSpeedThresholdKmh = 8.0,
        stopDurationThresholdSec = 120L
    ),
    /**
     * Delivery backend for SOS events.
     *
     * Production leaves this null: events are written to Supabase via remoteDataSource
     * and the trigger only reports success once that write completed. Tests inject a
     * local backend so the SOS lifecycle (state machine, idempotency,
     * acknowledgment, resolution) can be exercised offline - the offline suite
     * must never depend on a live backend or on network state.
     */
    val sosBackend: SosDeliveryBackend? = null,
    /**
     * Optional mesh relay for delivering SOS alerts over BLE when
     * internet connectivity is unavailable.
     */
    val sosMeshRelay: SosMeshRelay? = null,
    /**
     * Optional mesh relay for broadcasting rider status/location updates
     * over BLE when internet connectivity is unavailable.
     */
    val riderStatusMeshRelay: RiderStatusMeshRelay? = null,
    /**
     * Remote data source for Supabase reads/writes. When provided, reads route
     * through Supabase REST and writes go to Supabase with sync outbox fallback
     * via localDataSource. When null, write operations are skipped (no-op).
     */
    val remoteDataSource: RideRemoteDataSource? = null,
    /**
     * Local data source for the sync outbox. When provided, write operations
     * are queued to the sync outbox (Room) and flushed to Supabase by the
     * background outbox processor. When null, outbox writes are skipped.
     */
    val localDataSource: RideLocalDataSource? = null
) {

    private val auth: FirebaseAuth?
        get() = try { FirebaseAuth.getInstance() } catch (_: Exception) { null }
    private val repositoryJob = SupervisorJob()
    private val repositoryScope = CoroutineScope(Dispatchers.Default + repositoryJob)

    private val _currentRide = MutableStateFlow<Ride?>(null)
    val currentRide: StateFlow<Ride?> = _currentRide.asStateFlow()

    private val _members = MutableStateFlow<List<RiderMember>>(emptyList())
    val members: StateFlow<List<RiderMember>> = _members.asStateFlow()

    private val _activeSos = MutableStateFlow<SosEvent?>(null)
    val activeSos: StateFlow<SosEvent?> = _activeSos.asStateFlow()

    private val _recentMessage = MutableStateFlow<QuickMessage?>(null)
    val recentMessage: StateFlow<QuickMessage?> = _recentMessage.asStateFlow()

    /**
     * Bounded chronological history of group messages seen on this device (local echo
     * + server snapshots), deduplicated by stable message id. The UI toast renders each
     * new id once, so a rapid burst can no longer overwrite/lose an earlier message.
     */
    private val messageRegistry = QuickMessageRegistry()
    private val _recentMessages = MutableStateFlow<List<QuickMessage>>(emptyList())
    val recentMessages: StateFlow<List<QuickMessage>> = _recentMessages.asStateFlow()

    /**
     * Surfaced send outcome for group messages: `true` when the LAST send exhausted all
     * Firestore write attempts (e.g. fully offline). Never silently swallowed.
     */
    private val _messageSendError = MutableStateFlow(false)
    val messageSendError: StateFlow<Boolean> = _messageSendError.asStateFlow()

    private val _currentRiderStatus = MutableStateFlow(MemberStatus.RIDING)
    val currentRiderStatus: StateFlow<MemberStatus> = _currentRiderStatus.asStateFlow()

    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError.asStateFlow()

    private var rideSub: (() -> Unit)? = null
    private var ridersSub: (() -> Unit)? = null
    private var messagesSub: (() -> Unit)? = null
    private var sosSub: (() -> Unit)? = null

    private val roomDb: com.example.data.db.RideDatabase? by lazy {
        this.context?.let { com.example.data.db.RideDatabase.getInstance(it) }
    }

    /** Moshi adapter for serializing outbox payloads to JSON. */
    private val moshiAdapter: JsonAdapter<Any> by lazy {
        Moshi.Builder().build().adapter(Any::class.java)
    }

    /**
     * Persists a durable on-device reference to the active ride so startup
     * restoration can survive process death without a live Firestore query.
     * Safe to call when no ride is active (writes a no-op tombstone is not needed;
     * [clearActiveRideReference] handles removal).
     */
    private suspend fun persistActiveRideReference(ride: Ride) {
        try {
            roomDb?.activeRideReferenceDao()?.insertOrReplace(
                ActiveRideReferenceEntity(
                    rideId = ride.id,
                    leaderId = ride.leaderId,
                    status = ride.status.name,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed persisting active ride reference: ${e.message}")
        }
    }

    /** Clears the persisted active-ride reference (leave/end/terminal restore). */
    private suspend fun clearActiveRideReference() {
        try {
            roomDb?.activeRideReferenceDao()?.clearAll()
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed clearing active ride reference: ${e.message}")
        }
    }

    /**
     * Clears all active-ride local state (Room reference + telemetry) so a stale
     * ride is not resurrected on the next relaunch. Completed-ride history is
     * intentionally left untouched.
     */
    internal suspend fun clearAllLocalRideState() {
        clearActiveRideReference()
        try {
            roomDb?.activeRideTelemetryDao()?.clearAll()
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed clearing active ride telemetry: ${e.message}")
        }
    }

    /**
     * Loads a single ride document by ID for one-time restoration. Unlike
     * [fetchActiveRideForUser] (leader-only query), this works for any ride the
     * user knows the ID of — including rides they joined as a member.
     */
    suspend fun fetchRideById(rideId: String): Ride? {
        // Phase 2: route reads through Supabase REST when available
        return remoteDataSource?.fetchRideById(rideId)
    }

    /**
     * Restores an active ride by ID for startup recovery. Loads the ride document,
     * verifies it is non-terminal, sets it as the current ride, persists the
     * durable Room reference, and attaches live listeners — all in one call.
     * Returns null if the ride does not exist or is in a terminal state.
     */
    internal suspend fun restoreActiveRide(rideId: String): Ride? {
        val ride = fetchRideById(rideId) ?: return null
        if (RideLifecyclePolicy.isTerminal(ride.status)) {
            Log.w("RideRepository", "Refusing to restore terminal ride $rideId (status=${ride.status})")
            return null
        }
        _currentRide.value = ride
        persistActiveRideReference(ride)
        listenToRide(rideId)
        return ride
    }

    private var currentUserId: String = auth?.currentUser?.uid ?: "rider_unknown"
    private var currentUserName: String = auth?.currentUser?.displayName ?: "Rider"
    private var currentMotorcycleModel: String = ""
    private var localSequenceNumber: Long = 0L
    /** Wall-clock ms when the ride transitioned to ACTIVE. Reset on leave/end. */
    private var rideStartedAtMs: Long = 0L

    /**
     * Session-local suppression of SOS alerts on this device: resolve tombstones
     * ("I'm okay" applied locally before the server write flushes) and manual member
     * dismissals. A stale Firestore snapshot of a suppressed event can never re-apply
     * the alert on this device; a NEW SOS event (different id) always surfaces.
     * See [SosSuppressionState] for policy details.
     */
    private val suppressedSosIds = SosSuppressionState()

    /**
     * In-flight guard against duplicate ride-completion submissions (double-tap "Complete
     * Ride" or leader+listener echo racing). See [endRide].
     */
    private val isCompletingRide = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ride ids whose shared COMPLETED status this client has OBSERVED (locally decided or
     * from the authoritative snapshot). Drives the once-per-ride terminal handling so a
     * repeated COMPLETED snapshot never repeats cleanup (service stop / tracker stop /
     * history save), and lets [mergeSharedRideStatus] reject stale terminal mutations.
     */
    private val sharedCompletedRideIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val riderLatestSequence = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastRecordedGpsPoint: LatLng? = null
    private var lastGpsTimestampMs: Long = 0L
    private var lastFirestoreSyncMs: Long = 0L
    private var lastSyncedDistanceMeters: Double = 0.0

    init {
        // Collect real location updates and sync to active ride in Firestore
        repositoryScope.launch {
            locationTracker.currentLocation.collectLatest { locUpdate ->
                if (locUpdate != null) {
                    processLocationUpdate(locUpdate)
                }
            }
        }

        // Stale & connection health detector loop: checks every 5 seconds for stale riders
        repositoryScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(5000L)
                if (isActive) {
                    checkRidersStaleness()
                }
            }
        }

        // 10-second keep-alive heartbeat loop to refresh rider telemetry even when stationary at stoplights
        repositoryScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(10000L)
                if (isActive) {
                    sendRiderHeartbeat()
                }
            }
        }

        // Phase 4 — outbox processor: periodically flush pending sync outbox entries
        // to Supabase via remoteDataSource when connectivity is available.
        repositoryScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(5000L)
                if (isActive && remoteDataSource != null && localDataSource != null) {
                    flushOutbox()
                }
            }
        }
    }

    fun initCurrentUser(id: String, name: String, motorcycleModel: String = "") {
        currentUserId = id
        currentUserName = name
        currentMotorcycleModel = motorcycleModel
        // Also save/update user profile
        repositoryScope.launch {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    // Phase 4: upsert profile to Supabase, fall back to outbox on failure
                    val success = remoteDataSource!!.upsertProfile(
                        id = id,
                        name = name,
                        email = auth?.currentUser?.email,
                        photoUrl = auth?.currentUser?.photoUrl?.toString(),
                        motorcycleModel = motorcycleModel
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "profile",
                            entityId = id,
                            opType = "UPSERT",
                            payload = mapOf<String, Any>(
                                "name" to name,
                                "email" to (auth?.currentUser?.email ?: ""),
                                "photoUrl" to (auth?.currentUser?.photoUrl?.toString() ?: ""),
                                "motorcycleModel" to motorcycleModel
                            )
                        )
                    }
                }

                fetchActiveRideForUser()
            } catch (e: Exception) {
                Log.w("RideRepository", "initCurrentUser failed: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Phase 4 — Sync outbox helpers
    // ========================================================================

    /**
     * Enqueue a write operation to the sync outbox for later flushing to Supabase
     * via [remoteDataSource]. Used when a direct Supabase write fails (e.g. offline).
     */
    private suspend fun enqueueOutbox(
        entityType: String,
        entityId: String,
        opType: String,
        payload: Map<String, Any?>
    ) {
        localDataSource?.let { lds ->
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val payloadJson = moshiAdapter.toJson(payload)
            lds.insertOutboxEntry(SyncOutboxEntity(
                id = id,
                userId = currentUserId,
                entityType = entityType,
                entityId = entityId,
                opType = opType,
                idempotencyKey = "$entityType|$entityId|$opType|$now",
                payload = payloadJson,
                createdAtMs = now,
                updatedAtMs = now,
                nextRetryAtMs = now
            ))
        }
    }

    /**
     * Flush pending sync outbox entries to Supabase via [remoteDataSource].
     * Dispatches each entry to the appropriate remote method based on entityType/opType.
     */
    private suspend fun flushOutbox() {
        val now = System.currentTimeMillis()
        val entries: List<SyncOutboxEntity> = localDataSource?.getPendingOutbox(currentUserId, now, 50) ?: return

        for (entry in entries) {
            try {
                val success = dispatchOutboxEntry(entry)
                if (success) {
                    localDataSource?.updateOutboxStatus(entry.id, "DONE", null, System.currentTimeMillis())
                } else {
                    handleOutboxFailure(entry)
                }
            } catch (e: Exception) {
                Log.w("RideRepository", "Outbox flush failed for entry ${entry.id}: ${e.message}")
                handleOutboxFailure(entry)
            }
        }
    }

    /** Dispatch a single outbox entry to the appropriate Supabase remote method. */
    private suspend fun dispatchOutboxEntry(entry: SyncOutboxEntity): Boolean {
        val rds = remoteDataSource ?: return false
        val payload = parseMap(entry.payload)

        return when (entry.entityType) {
            "ride" -> when (entry.opType) {
                "CREATE" -> {
                    val ride = parseRideFromPayload(payload)
                    if (ride != null) {
                        rds.createRide(ride, RiderMember(
                            id = currentUserId,
                            name = currentUserName,
                            role = RideRole.LEADER,
                            status = _currentRiderStatus.value,
                            location = ride.startLocation
                        ))
                    } else false
                }
                "UPDATE" -> rds.updateRide(entry.entityId, payload)
                "UPDATE_STATUS" -> {
                    val statusStr = payload["status"] as? String ?: "LOBBY"
                    val status = runCatching { RideStatus.valueOf(statusStr) }.getOrDefault(RideStatus.LOBBY)
                    rds.updateRideStatus(entry.entityId, status)
                }
                else -> false
            }
            "rider" -> when (entry.opType) {
                "UPSERT" -> {
                    val rider = parseRiderFromPayload(payload)
                    if (rider != null) rds.upsertRider(entry.entityId, rider) else false
                }
                "SET_MEMBERSHIP" -> {
                    val userId = payload["user_id"] as? String ?: currentUserId
                    val statusStr = payload["membership_status"] as? String ?: "ACTIVE"
                    val status = runCatching { MembershipStatus.valueOf(statusStr) }.getOrDefault(MembershipStatus.ACTIVE)
                    rds.setRiderMembership(entry.entityId, userId, status)
                }
                else -> false
            }
            "message" -> when (entry.opType) {
                "CREATE" -> {
                    val msg = parseQuickMessageFromPayload(payload)
                    if (msg != null) rds.sendMessage(entry.entityId, msg) else false
                }
                else -> false
            }
            "sos" -> when (entry.opType) {
                "CREATE" -> {
                    val sos = parseSosFromPayload(payload)
                    if (sos != null) rds.createSos(entry.entityId, sos) else false
                }
                "RESOLVE" -> rds.resolveSos(entry.entityId)
                "ACKNOWLEDGE" -> {
                    val ackingUserId = payload["acking_user_id"] as? String ?: currentUserId
                    rds.acknowledgeSos(entry.entityId, ackingUserId)
                }
                else -> false
            }
            else -> false
        }
    }

    /** Handle a failed outbox entry: schedule retry or mark permanent failure. */
    private suspend fun handleOutboxFailure(entry: SyncOutboxEntity) {
        val now = System.currentTimeMillis()
        if (entry.retryCount >= entry.maxRetries) {
            localDataSource?.updateOutboxStatus(entry.id, "PERMANENT_FAILURE", "Max retries exceeded", now)
        } else {
            val backoff = Math.pow(2.0, entry.retryCount.toDouble()).toLong() * 1000L
            localDataSource?.scheduleRetry(entry.id, now + backoff, now)
        }
    }

    /** Parse a JSON payload string into a Map. */
    private fun parseMap(json: String): Map<String, Any> {
        return try {
            val raw = moshiAdapter.fromJson(json) as? Map<*, *> ?: return emptyMap()
            raw.mapKeys { it.key.toString() }
                .mapValues { (it.value as? Any) ?: "" }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Parse a Ride from a payload map. */
    private fun parseRideFromPayload(payload: Map<String, Any?>): Ride? {
        return try {
            Ride(
                id = payload["id"] as? String ?: "",
                name = payload["name"] as? String ?: "Group Ride",
                startLocationName = payload["start_location_name"] as? String ?: "Start",
                destinationName = payload["destination_name"] as? String ?: "Destination",
                startLocation = LatLng(
                    (payload["start_lat"] as? Number)?.toDouble() ?: 18.5204,
                    (payload["start_lng"] as? Number)?.toDouble() ?: 73.8567
                ),
                destinationLocation = LatLng(
                    (payload["dest_lat"] as? Number)?.toDouble() ?: 18.7546,
                    (payload["dest_lng"] as? Number)?.toDouble() ?: 73.4062
                ),
                leaderId = payload["leader_id"] as? String ?: currentUserId,
                inviteCode = payload["invite_code"] as? String ?: "",
                status = runCatching { RideStatus.valueOf(payload["status"] as? String ?: "LOBBY") }.getOrDefault(RideStatus.LOBBY),
                initialPlannedDistanceMeters = (payload["initial_planned_distance_meters"] as? Number)?.toDouble() ?: 0.0,
                totalDistanceMeters = (payload["total_distance_meters"] as? Number)?.toDouble() ?: 0.0,
                totalDurationSeconds = (payload["total_duration_seconds"] as? Number)?.toDouble() ?: 0.0,
                routeStops = RouteStopCodec.fromFirestore(payload["route_stops"] as? List<*>),
                routePlanVersion = (payload["route_plan_version"] as? Number)?.toLong() ?: 0L,
                selectedRouteIndex = (payload["selected_route_index"] as? Number)?.toInt() ?: 0,
                routeOptions = RoutePlanCodec.optionsFromFirestore(payload["route_options"] as? List<*>)
            )
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed to parse ride from payload: ${e.message}")
            null
        }
    }

    /** Parse a RiderMember from a payload map. */
    private fun parseRiderFromPayload(payload: Map<String, Any?>): RiderMember? {
        return try {
            RiderMember(
                id = payload["user_id"] as? String ?: payload["id"] as? String ?: "",
                name = payload["name"] as? String ?: "Rider",
                motorcycleModel = payload["motorcycle_model"] as? String ?: "",
                role = if ((payload["role"] as? String ?: "MEMBER").equals("LEADER", ignoreCase = true)) RideRole.LEADER else RideRole.MEMBER,
                status = runCatching { MemberStatus.valueOf(payload["status"] as? String ?: "RIDING") }.getOrDefault(MemberStatus.RIDING),
                membershipStatus = runCatching { MembershipStatus.valueOf(payload["membership_status"] as? String ?: "ACTIVE") }.getOrDefault(MembershipStatus.ACTIVE),
                location = LatLng(
                    (payload["latitude"] as? Number)?.toDouble() ?: 0.0,
                    (payload["longitude"] as? Number)?.toDouble() ?: 0.0
                ),
                speedKmh = (payload["speed_kmh"] as? Number)?.toDouble() ?: 0.0,
                headingDeg = (payload["heading_deg"] as? Number)?.toFloat(),
                accuracyMeters = (payload["accuracy_meters"] as? Number)?.toFloat() ?: 10f,
                lastUpdatedMs = (payload["last_updated_ms"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                stoppedDurationSec = (payload["stopped_duration_sec"] as? Number)?.toLong() ?: 0L,
                batteryPct = (payload["battery_pct"] as? Number)?.toInt() ?: 85
            )
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed to parse rider from payload: ${e.message}")
            null
        }
    }

    /** Parse a QuickMessage from a payload map. */
    private fun parseQuickMessageFromPayload(payload: Map<String, Any?>): QuickMessage? {
        return try {
            QuickMessage(
                id = payload["id"] as? String ?: UUID.randomUUID().toString(),
                senderId = payload["sender_id"] as? String ?: "",
                senderName = payload["sender_name"] as? String ?: "Rider",
                type = runCatching { QuickMessageType.valueOf(payload["type"] as? String ?: "IM_OKAY") }.getOrDefault(QuickMessageType.IM_OKAY),
                text = payload["text"] as? String ?: "",
                timestampMs = (payload["timestamp_ms"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed to parse message from payload: ${e.message}")
            null
        }
    }

    /** Parse a SosEvent from a payload map. */
    private fun parseSosFromPayload(payload: Map<String, Any?>): SosEvent? {
        return try {
            SosEvent(
                id = payload["id"] as? String ?: UUID.randomUUID().toString(),
                riderId = payload["rider_id"] as? String ?: "",
                riderName = payload["rider_name"] as? String ?: "Rider",
                location = LatLng(
                    (payload["latitude"] as? Number)?.toDouble() ?: 0.0,
                    (payload["longitude"] as? Number)?.toDouble() ?: 0.0
                ),
                timestampMs = (payload["timestamp_ms"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                isResolved = (payload["is_resolved"] as? Boolean) ?: false,
                accuracyMeters = (payload["accuracy_meters"] as? Number)?.toFloat() ?: 10f,
                locationSource = payload["location_source"] as? String ?: "FRESH_LOCATION_FIX"
            )
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed to parse SOS from payload: ${e.message}")
            null
        }
    }

    suspend fun fetchActiveRideForUser(): Ride? {
        val uid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return _currentRide.value
        // Phase 2: route reads through Supabase REST when available
        return remoteDataSource?.fetchActiveRideForLeader(uid)
    }

    fun setMotorcycleModel(model: String) {
        currentMotorcycleModel = model
        initCurrentUser(currentUserId, currentUserName, model)
    }

    private fun sendRiderHeartbeat() {
        val activeRide = _currentRide.value ?: return
        val currentUid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return
        val nowMs = System.currentTimeMillis()

        // Refresh current user's lastUpdatedMs locally
        val currentList = _members.value
        if (currentList.isNotEmpty()) {
            val updatedList = currentList.map { rider ->
                if (rider.isCurrentUser) {
                    rider.copy(
                        lastUpdatedMs = nowMs,
                        connectionStatus = com.example.data.model.ConnectionStatus.CONNECTED
                    )
                } else {
                    rider
                }
            }
            _members.value = updatedList
        }

        // Sync heartbeat timestamp to Supabase (Phase 4) or Firestore
        repositoryScope.launch {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    val currentRider = _members.value.find { it.id == currentUid }
                    val riderToSync = currentRider ?: RiderMember(
                        id = currentUid,
                        name = currentUserName,
                        location = activeRide.startLocation,
                        lastUpdatedMs = nowMs,
                        isCurrentUser = true
                    )
                    val success = remoteDataSource!!.upsertRider(
                        rideId = activeRide.id,
                        rider = riderToSync.copy(lastUpdatedMs = nowMs)
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "rider",
                            entityId = currentUid,
                            opType = "UPSERT",
                            payload = mapOf(
                                "user_id" to currentUid,
                                "ride_id" to activeRide.id,
                                "last_updated_ms" to nowMs,
                                "membership_status" to MembershipStatus.ACTIVE.name
                            )
                        )
                    }
                } else {
                    Log.w("RideRepository", "Heartbeat sync skipped: remoteDataSource or localDataSource unavailable")
                }
            } catch (e: Exception) {
                Log.w("RideRepository", "Heartbeat sync failed: ${e.message}")
            }
        }
    }

    private fun checkRidersStaleness() {
        val now = System.currentTimeMillis()
        val currentList = _members.value
        if (currentList.isEmpty()) return

        var changed = false
        val updated = currentList.map { rider ->
            val newConn = RiderSyncPolicy.calculateConnectionStatus(
                isCurrentUser = rider.isCurrentUser,
                isGpsAvailable = locationTracker.isGpsAvailable.value,
                timestamp = RiderTimestamp(rider.lastUpdatedMs, rider.serverTimestampMs),
                currentTimeMs = now
            )
            if (newConn != rider.connectionStatus) {
                changed = true
                rider.copy(connectionStatus = newConn)
            } else {
                rider
            }
        }
        if (changed) {
            _members.value = updated
        }
    }

    internal fun processLocationUpdate(loc: LocationUpdate) {
        val (detectedStatus, stoppedSec) = stopDetector.processSpeed(loc.speedKmh, loc.timestampMs)
        val finalStatus = if (_activeSos.value?.riderId == currentUserId) {
            MemberStatus.EMERGENCY
        } else {
            detectedStatus
        }
        _currentRiderStatus.value = finalStatus

        val activeRide = _currentRide.value
        val activeRideId = activeRide?.id ?: return
        val currentUid = auth?.currentUser?.uid ?: currentUserId.ifBlank { "user_rider" }

        // Ignore duplicate or out-of-order fixes when timestamp is explicitly provided
        if (loc.timestampMs > 0L && lastGpsTimestampMs > 0L && loc.timestampMs <= lastGpsTimestampMs) return

        // Accumulate actual recorded distance if ride is ACTIVE and GPS accuracy is good (< 25m)
        if (activeRide.status == RideStatus.ACTIVE && loc.accuracyMeters <= 25f) {
            val prev = lastRecordedGpsPoint
            if (prev == null) {
                lastRecordedGpsPoint = loc.latLng
            } else {
                val deltaMeters = prev.distanceTo(loc.latLng) * 1000.0
                if (deltaMeters in 1.0..500.0 && loc.speedKmh >= 1.5) {
                    val newActual = activeRide.actualRecordedDistanceMeters + deltaMeters
                    _currentRide.value = activeRide.copy(actualRecordedDistanceMeters = newActual)
                    lastRecordedGpsPoint = loc.latLng
                } else if (deltaMeters > 500.0) {
                    // Reset anchor after a large GPS jump without adding the glitch distance
                    lastRecordedGpsPoint = loc.latLng
                }
            }
        }

        val seq = ++localSequenceNumber

        // Sync rider location to Supabase (Phase 4) or Firestore subcollection /rides/{rideId}/riders/{riderId}
        repositoryScope.launch {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    val rider = RiderMember(
                        id = currentUid,
                        name = currentUserName,
                        motorcycleModel = currentMotorcycleModel,
                        role = if (_currentRide.value?.leaderId == currentUid) RideRole.LEADER else RideRole.MEMBER,
                        status = finalStatus,
                        membershipStatus = MembershipStatus.ACTIVE,
                        location = loc.latLng,
                        speedKmh = loc.speedKmh,
                        headingDeg = loc.headingDeg,
                        headingSource = loc.headingSource,
                        headingAccuracyDeg = loc.headingAccuracyDeg,
                        accuracyMeters = loc.accuracyMeters,
                        sequenceNumber = seq,
                        batteryPct = 90,
                        stoppedDurationSec = stoppedSec,
                        lastUpdatedMs = loc.timestampMs,
                        isCurrentUser = true
                    )
                    val success = remoteDataSource!!.upsertRider(
                        rideId = activeRideId,
                        rider = rider
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "rider",
                            entityId = currentUid,
                            opType = "UPSERT",
                            payload = mapOf<String, Any?>(
                                "user_id" to currentUid,
                                "ride_id" to activeRideId,
                                "name" to currentUserName,
                                "motorcycle_model" to currentMotorcycleModel,
                                "role" to if (_currentRide.value?.leaderId == currentUid) "LEADER" else "MEMBER",
                                "membership_status" to MembershipStatus.ACTIVE.name,
                                "status" to finalStatus.name,
                                "latitude" to loc.latLng.latitude,
                                "longitude" to loc.latLng.longitude,
                                "speed_kmh" to loc.speedKmh,
                                "heading_deg" to (loc.headingDeg ?: 0f),
                                "heading_source" to loc.headingSource.name,
                                "heading_accuracy_deg" to (loc.headingAccuracyDeg ?: 0f),
                                "accuracy_meters" to loc.accuracyMeters,
                                "sequence_number" to seq,
                                "battery_pct" to 90,
                                "stopped_duration_sec" to stoppedSec,
                                "last_updated_ms" to loc.timestampMs
                            )
                        )
                    }
                } else {
                    Log.w("RideRepository", "Rider location sync skipped: remoteDataSource or localDataSource unavailable")
                }
                riderStatusMeshRelay?.sendRiderStatusUpdate(
                    status = finalStatus,
                    location = loc.latLng,
                    speedKmh = loc.speedKmh,
                    headingDeg = loc.headingDeg,
                    accuracyMeters = loc.accuracyMeters,
                    timestampMs = loc.timestampMs,
                    sequenceNumber = seq,
                )
            } catch (e: Exception) {
                Log.w("RideRepository", "processLocationUpdate sync failed: ${e.message}")
            }
        }
    }

    suspend fun createRide(
        rideName: String,
        startName: String,
        destName: String,
        startLoc: LatLng,
        destLoc: LatLng,
        routeStops: List<RouteStop> = emptyList(),
        selectedRouteIndex: Int = 0,
        routeOptions: List<RouteOptionSummary> = emptyList()
    ): Ride {
        val uid = auth?.currentUser?.uid
            ?: currentUserId.takeIf { it.isNotBlank() }
            ?: "rider_${System.currentTimeMillis()}"
        val rideId = UUID.randomUUID().toString()
        val inviteCode = generateInviteCode().trim().uppercase(java.util.Locale.ROOT)

        val safeRideName = rideName.ifBlank { "${startName.ifBlank { "Pune" }} → ${destName.ifBlank { "Lonavala" }}" }
        val safeUserName = currentUserName.ifBlank { "Rider" }

        val initialApproxMeters = startLoc.distanceTo(destLoc) * 1000.0
        val planVersion = if (routeStops.isNotEmpty()) 1L else 0L
        val createdRide = Ride(
            id = rideId,
            name = safeRideName,
            startLocationName = startName,
            destinationName = destName,
            startLocation = startLoc,
            destinationLocation = destLoc,
            leaderId = uid,
            inviteCode = inviteCode,
            status = RideStatus.LOBBY,
            initialPlannedDistanceMeters = initialApproxMeters,
            totalDistanceMeters = initialApproxMeters,
            routeStops = routeStops,
            routePlanVersion = planVersion,
            selectedRouteIndex = selectedRouteIndex.coerceAtLeast(0),
            routeOptions = routeOptions
        )

        val leaderMember = RiderMember(
            id = uid,
            name = safeUserName,
            motorcycleModel = currentMotorcycleModel,
            avatarColorHex = 0xFFFF5722,
            role = RideRole.LEADER,
            status = _currentRiderStatus.value,
            location = startLoc,
            speedKmh = 0.0,
            headingDeg = 0f,
            lastUpdatedMs = System.currentTimeMillis(),
            stoppedDurationSec = 0,
            isCurrentUser = true,
            batteryPct = 95
        )

        // Immediately populate local state and active rides map
        activeLocalRides[inviteCode] = createdRide
        _currentRide.value = createdRide
        persistActiveRideReference(createdRide)
        _members.value = listOf(leaderMember)

        // Attach listeners for this ride session
        try {
            listenToRide(rideId)
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed attaching initial ride listener: ${e.message}")
        }

        // Persist to Supabase (Phase 4) or Firestore asynchronously so creation never blocks the caller or test thread
        repositoryScope.launch(Dispatchers.IO) {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    val success = remoteDataSource!!.createRide(
                        ride = createdRide,
                        leaderRider = leaderMember
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "ride",
                            entityId = rideId,
                            opType = "CREATE",
                            payload = mapOf(
                                "id" to rideId,
                                "name" to safeRideName,
                                "start_location_name" to startName,
                                "destination_name" to destName,
                                "start_lat" to startLoc.latitude,
                                "start_lng" to startLoc.longitude,
                                "dest_lat" to destLoc.latitude,
                                "dest_lng" to destLoc.longitude,
                                "leader_id" to uid,
                                "invite_code" to inviteCode,
                                "status" to RideStatus.LOBBY.name,
                                "initial_planned_distance_meters" to initialApproxMeters,
                                "total_distance_meters" to 0.0,
                                "total_duration_seconds" to 0.0,
                                "route_stops" to RouteStopCodec.toFirestore(routeStops),
                                "route_plan_version" to planVersion,
                                "selected_route_index" to selectedRouteIndex.coerceAtLeast(0),
                                "route_options" to RoutePlanCodec.optionsToFirestore(routeOptions)
                            )
                        )
                        // Also enqueue the leader rider
                        enqueueOutbox(
                            entityType = "rider",
                            entityId = uid,
                            opType = "UPSERT",
                            payload = mapOf(
                                "user_id" to uid,
                                "ride_id" to rideId,
                                "name" to safeUserName,
                                "motorcycle_model" to currentMotorcycleModel,
                                "role" to "LEADER",
                                "membership_status" to MembershipStatus.ACTIVE.name,
                                "status" to _currentRiderStatus.value.name,
                                "latitude" to startLoc.latitude,
                                "longitude" to startLoc.longitude,
                                "speed_kmh" to 0.0,
                                "battery_pct" to 95,
                                "stopped_duration_sec" to 0L,
                                "last_updated_ms" to System.currentTimeMillis()
                            )
                        )
                    }
                    Log.d("RideRepository", "Ride $rideId persisted to Supabase (success=$success)")
                } else {
                    Log.w("RideRepository", "Ride $rideId persistence skipped: remoteDataSource or localDataSource unavailable")
                }
            } catch (e: Exception) {
                Log.w("RideRepository", "createRide persistence failed: ${e.message}")
            }
        }

        return createdRide
    }

    suspend fun joinRide(inviteCode: String, userLoc: LatLng): Result<Ride> {
        val uid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: "rider_${System.currentTimeMillis()}"
        val code = inviteCode.trim().uppercase()

        // Check if active local rides registry has this code
        val localRide = activeLocalRides[code] ?: _currentRide.value?.takeIf { it.inviteCode.equals(code, ignoreCase = true) }
        if (localRide != null) {
            _currentRide.value = localRide
            _joinError.value = null
            val selfMember = RiderMember(
                id = uid,
                name = currentUserName,
                motorcycleModel = currentMotorcycleModel,
                role = if (localRide.leaderId == uid) RideRole.LEADER else RideRole.MEMBER,
                status = _currentRiderStatus.value,
                location = userLoc,
                isCurrentUser = true
            )
            if (_members.value.none { it.id == uid }) {
                _members.value = _members.value + selfMember
            }
            try { listenToRide(localRide.id) } catch (_: Exception) {}
            return Result.success(localRide)
        }

        return try {
            // Phase 2: try Supabase first for invite code lookup
            val supabaseRide = remoteDataSource?.findRideByInviteCode(code)
            if (supabaseRide != null) {
                return completeRideJoin(supabaseRide, uid, userLoc)
            }

            return createOrJoinFallbackRide(code, userLoc, uid)
        } catch (e: Exception) {
            Log.w("RideRepository", "joinRide($code) failed: ${e.message}")
            _joinError.value = "Failed to join ride: ${e.localizedMessage}"
            Result.failure(e)
        }
    }

    /**
     * Completes a ride join: registers the current rider in Firestore, sets the current ride,
     * persists the Room reference, and starts live listeners.
     */
    private suspend fun completeRideJoin(ride: Ride, uid: String, userLoc: LatLng): Result<Ride> {
        val rideId = ride.id
        val leaderId = ride.leaderId
        // Register current rider in Supabase
        val selfMember = RiderMember(
            id = uid,
            name = currentUserName,
            motorcycleModel = currentMotorcycleModel,
            role = if (leaderId == uid) RideRole.LEADER else RideRole.MEMBER,
            status = _currentRiderStatus.value,
            location = userLoc,
            isCurrentUser = true
        )
        val success = remoteDataSource!!.upsertRider(
            rideId = rideId,
            rider = selfMember
        )
        if (!success) {
            enqueueOutbox(
                entityType = "rider",
                entityId = uid,
                opType = "UPSERT",
                payload = mapOf(
                    "user_id" to uid,
                    "ride_id" to rideId,
                    "name" to currentUserName,
                    "motorcycle_model" to currentMotorcycleModel,
                    "role" to if (leaderId == uid) "LEADER" else "MEMBER",
                    "membership_status" to MembershipStatus.ACTIVE.name,
                    "status" to _currentRiderStatus.value.name,
                    "latitude" to userLoc.latitude,
                    "longitude" to userLoc.longitude,
                    "speed_kmh" to 0.0,
                    "battery_pct" to 90,
                    "stopped_duration_sec" to 0L,
                    "last_updated_ms" to System.currentTimeMillis()
                )
            )
        }

        _currentRide.value = ride
        persistActiveRideReference(ride)
        _joinError.value = null

        // Start live listeners for all riders, messages, and SOS events in this ride
        listenToRide(rideId)
        return Result.success(ride)
    }

    private fun listenToRide(rideId: String) {
        detachListeners()
        // Phase 3: use Supabase Realtime WebSocket subscriptions instead of Firestore
        // snapshot listeners.
        if (remoteDataSource != null) {
            listenToRideViaSupabase(rideId)
            return
        }
        Log.w("RideRepository", "listenToRide($rideId) skipped: no remoteDataSource configured")
    }

    /**
     * Phase 3 — Subscribe to ride changes via Supabase Realtime (WebSocket) instead of
     * Firestore snapshot listeners. Sets up 4 channel subscriptions (ride, riders,
     * messages, SOS) and stores their unsubscribe functions for cleanup.
     */
    private fun listenToRideViaSupabase(rideId: String) {
        val currentUid = auth?.currentUser?.uid ?: ""

        // 1. Ride metadata — apply incoming ride document to local state with
        //    lifecycle reconciliation (stale ACTIVE/LOBBY can't reactivate COMPLETED).
        rideSub = remoteDataSource?.subscribeToRide(rideId) { ride ->
            val existingRide = _currentRide.value?.takeIf { it.id == rideId }
            if (_currentRide.value != null && existingRide == null) {
                Log.d("RideRepository", "Ignoring ride snapshot for a different ride $rideId (current=${_currentRide.value?.id})")
                return@subscribeToRide
            }
            val status = mergeSharedRideStatus(existingRide?.status, ride.status, rideId)
            _currentRide.value = existingRide?.copy(
                name = ride.name,
                status = status,
                startLocation = ride.startLocation,
                destinationLocation = ride.destinationLocation,
                startLocationName = ride.startLocationName,
                destinationName = ride.destinationName,
                totalDistanceMeters = if (ride.totalDistanceMeters > 0.0) ride.totalDistanceMeters else existingRide?.totalDistanceMeters ?: 0.0,
                totalDurationSeconds = if (ride.totalDurationSeconds > 0.0) ride.totalDurationSeconds else existingRide?.totalDurationSeconds ?: 0.0,
                routeStops = ride.routeStops,
                routePlanVersion = ride.routePlanVersion,
                selectedRouteIndex = ride.selectedRouteIndex,
                routeOptions = ride.routeOptions,
            ) ?: ride
        }

        // 2. Riders — replace the member list with the latest snapshot from Supabase.
        ridersSub = remoteDataSource?.subscribeToRiders(rideId) { riders ->
            if (riders.isNotEmpty()) {
                _members.value = riders
            } else if (_members.value.isNotEmpty()) {
                Log.d("RideRepository", "Remote riders snapshot empty, keeping ${_members.value.size} local members")
            }
        }

        // 3. Messages — publish incoming quick messages (obsolete-ride guard applied).
        messagesSub = remoteDataSource?.subscribeToMessages(rideId) { msg ->
            if (_currentRide.value != null && _currentRide.value?.id != rideId) return@subscribeToMessages
            publishQuickMessage(msg, fromLocalEcho = false)
        }

        // 4. SOS events — apply incoming SOS with suppression and rider-status update.
        sosSub = remoteDataSource?.subscribeToSos(rideId) { sos ->
            if (suppressedSosIds.isSuppressed(sos.id)) return@subscribeToSos
            _activeSos.value = sos
            if (sos.riderId == currentUid) {
                _currentRiderStatus.value = MemberStatus.EMERGENCY
            } else if (_activeSos.value == null && _currentRiderStatus.value == MemberStatus.EMERGENCY) {
                _currentRiderStatus.value = MemberStatus.RIDING
            }
        }
    }

    fun addPackMember(name: String, offsetLat: Double, offsetLon: Double, role: RideRole = RideRole.MEMBER, motorcycleModel: String = "Duke 390") {
        val currentUser = _members.value.find { it.isCurrentUser }
        val baseLoc = currentUser?.location ?: LatLng(18.5204, 73.8567)
        val memberLoc = LatLng(baseLoc.latitude + offsetLat, baseLoc.longitude + offsetLon)

        val newMember = RiderMember(
            id = "rider_${UUID.randomUUID().toString().take(6)}",
            name = name,
            motorcycleModel = motorcycleModel,
            avatarColorHex = when (_members.value.size % 4) {
                0 -> 0xFF4CAF50
                1 -> 0xFF29B6F6
                2 -> 0xFFFFCA28
                else -> 0xFFAB47BC
            },
            role = role,
            status = MemberStatus.RIDING,
            connectionStatus = com.example.data.model.ConnectionStatus.CONNECTED,
            location = memberLoc,
            speedKmh = 40.0,
            headingDeg = 290f,
            lastUpdatedMs = System.currentTimeMillis(),
            stoppedDurationSec = 0,
            isCurrentUser = false,
            batteryPct = 82
        )

        _members.value = _members.value + newMember
    }

    fun updateRoute(
        routePoints: List<LatLng>,
        totalDistanceMeters: Double,
        totalDurationSeconds: Double,
        steps: List<NavigationStep>
    ) {
        val current = _currentRide.value ?: return
        val initDist = if (current.status == RideStatus.LOBBY || current.initialPlannedDistanceMeters <= 0.0) {
            totalDistanceMeters
        } else {
            current.initialPlannedDistanceMeters
        }
        _currentRide.value = current.copy(
            routePoints = routePoints,
            initialPlannedDistanceMeters = initDist,
            totalDistanceMeters = totalDistanceMeters,
            totalDurationSeconds = totalDurationSeconds,
            navigationSteps = steps
        )

        // Save total distance and duration to Supabase (Phase 4) or Firestore
        repositoryScope.launch {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    val success = remoteDataSource!!.updateRide(
                        rideId = current.id,
                        updates = mapOf(
                            "initial_planned_distance_meters" to initDist,
                            "total_distance_meters" to totalDistanceMeters,
                            "total_duration_seconds" to totalDurationSeconds,
                            "updated_at" to System.currentTimeMillis()
                        )
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "ride",
                            entityId = current.id,
                            opType = "UPDATE",
                            payload = mapOf(
                                "initial_planned_distance_meters" to initDist,
                                "total_distance_meters" to totalDistanceMeters,
                                "total_duration_seconds" to totalDurationSeconds
                            )
                        )
                    }
                    // Route geometry via Supabase
                    val geoMap = RouteGeometryCodec.encode(
                        points = routePoints,
                        steps = steps,
                        totalDistanceMeters = totalDistanceMeters,
                        totalDurationSeconds = totalDurationSeconds
                    )
                    val geoSuccess = remoteDataSource!!.saveRouteGeometry(
                        rideId = current.id,
                        points = (geoMap["points"] as? List<Map<String, Any>>) ?: emptyList(),
                        steps = (geoMap["steps"] as? List<Map<String, Any>>) ?: emptyList(),
                        totalDistanceMeters = totalDistanceMeters,
                        totalDurationSeconds = totalDurationSeconds
                    )
                    if (!geoSuccess) {
                        enqueueOutbox(
                            entityType = "ride",
                            entityId = current.id,
                            opType = "UPDATE_ROUTE_GEOMETRY",
                            payload = mapOf(
                                "total_distance_meters" to totalDistanceMeters,
                                "total_duration_seconds" to totalDurationSeconds
                            )
                        )
                    }
                } else {
                    Log.w("RideRepository", "updateRoute persistence skipped: remoteDataSource or localDataSource unavailable")
                }
            } catch (e: Exception) {
                Log.w("RideRepository", "updateRoute persistence failed: ${e.message}")
            }
        }

        // B-03 (audit F12-D1 / F7-D2): persist the route GEOMETRY as well, so a
        // process death does not restore a ride without a route.
        //
        // The geometry is stored via Supabase (handled above in the Supabase branch).
        // When Supabase is unavailable, geometry is not persisted — the client will
        // refetch route geometry on next sync.
    }

    /**
     * Publishes the leader's route plan: the ordered stops, the monotonic plan
     * version, the candidate routes and the leader's selected candidate.
     *
     * The caller owns the version counter and increments it on every plan
     * change, so a stale publication can never overwrite a newer plan - the
     * receiving side applies the same guard (see [RoutePlanCodec.merge]).
     */
    fun updateRoutePlan(
        routeStops: List<RouteStop>,
        planVersion: Long,
        selectedRouteIndex: Int,
        routeOptions: List<RouteOptionSummary>
    ) {
        val current = _currentRide.value ?: return
        val safeIndex = selectedRouteIndex.coerceAtLeast(0)
        _currentRide.value = current.copy(
            routeStops = routeStops,
            routePlanVersion = planVersion,
            selectedRouteIndex = safeIndex,
            routeOptions = routeOptions
        )
        repositoryScope.launch {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    val success = remoteDataSource!!.updateRide(
                        rideId = current.id,
                        updates = mapOf(
                            "route_stops" to RouteStopCodec.toFirestore(routeStops),
                            "route_plan_version" to planVersion,
                            "selected_route_index" to safeIndex,
                            "route_options" to RoutePlanCodec.optionsToFirestore(routeOptions),
                            "updated_at" to System.currentTimeMillis()
                        )
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "ride",
                            entityId = current.id,
                            opType = "UPDATE_ROUTE_PLAN",
                            payload = mapOf(
                                "route_stops" to RouteStopCodec.toFirestore(routeStops),
                                "route_plan_version" to planVersion,
                                "selected_route_index" to safeIndex,
                                "route_options" to RoutePlanCodec.optionsToFirestore(routeOptions)
                            )
                        )
                    }
                } else {
                    Log.w("RideRepository", "updateRoutePlan persistence skipped: remoteDataSource or localDataSource unavailable")
                }
            } catch (e: Exception) {
                Log.w("RideRepository", "updateRoutePlan persistence failed: ${e.message}")
            }
        }
    }

    /**
     * Reads the persisted route geometry for a ride (B-03). Returns `null` when
     * the ride has no stored geometry (older rides) or when the read fails —
     * the caller then falls back to a route refetch.
     */
    suspend fun fetchRouteGeometry(rideId: String): StoredRouteGeometry? {
        // Route reads through Supabase REST
        remoteDataSource?.let { rds ->
            val map = rds.fetchRouteGeometry(rideId) ?: return null
            return try {
                RouteGeometryCodec.decode(
                    rawPoints = map["points"],
                    rawSteps = map["steps"],
                    totalDistanceMeters = (map["total_distance_meters"] as? Number)?.toDouble() ?: 0.0,
                    totalDurationSeconds = (map["total_duration_seconds"] as? Number)?.toDouble() ?: 0.0
                )
            } catch (e: Exception) {
                null
            }
        }
        Log.w("RideRepository", "fetchRouteGeometry($rideId) skipped: no remoteDataSource configured")
        return null
    }

    fun startRide() {
        val ride = _currentRide.value ?: return
        // Lifecycle guard: a terminal ride can never be "started" again (stale UI or a
        // stale ACTIVE write must not resurrect a completed/cancelled ride).
        val transitionAllowed = RideLifecyclePolicy.canTransition(ride.status, RideStatus.ACTIVE)
        if (!transitionAllowed) {
            Log.w("RideRepository", "startRide rejected: ride ${ride.id} status ${ride.status} → ACTIVE is not legal")
            return
        }
        rideStartedAtMs = System.currentTimeMillis()
        lastRecordedGpsPoint = null
        _currentRide.value = ride.copy(status = RideStatus.ACTIVE)
        locationTracker.startTracking()

        repositoryScope.launch {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    val success = remoteDataSource!!.updateRideStatus(
                        rideId = ride.id,
                        status = RideStatus.ACTIVE
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "ride",
                            entityId = ride.id,
                            opType = "UPDATE_STATUS",
                            payload = mapOf(
                                "status" to RideStatus.ACTIVE.name
                            )
                        )
                    }
                } else {
                    Log.w("RideRepository", "startRide persistence skipped: remoteDataSource or localDataSource unavailable")
                }
            } catch (e: Exception) {
                Log.w("RideRepository", "startRide persistence failed: ${e.message}")
            }
        }
    }

    /**
     * Lifecycle reconciliation for ride-status updates arriving from any source
     * (authoritative shared snapshot, local leader decision, or test injection).
     * Enforces [RideLifecyclePolicy]: a stale `ACTIVE`/`LOBBY` snapshot can never
     * reactivate a COMPLETED/CANCELLED ride; a legit `ACTIVE → COMPLETED` always wins.
     */
    internal fun mergeSharedRideStatus(current: RideStatus?, incoming: RideStatus, rideId: String): RideStatus {
        if (current == null) return incoming
        if (current == incoming) return current
        if (RideLifecyclePolicy.canTransition(current, incoming)) {
            Log.d("RideRepository", "Shared ride status transition ride=$rideId $current → $incoming")
            return incoming
        }
        Log.w("RideRepository", "Stale ride-status update rejected ride=$rideId $current ➜ $incoming (current state is authoritative)")
        return current
    }

    suspend fun endRide(): Result<Unit> {
        val ride = _currentRide.value ?: return Result.failure(IllegalStateException("No active ride to complete"))
        val currentUid = auth?.currentUser?.uid ?: currentUserId

        // Only the ride leader is authorized to complete the shared ride. A member must
        // use leaveRide(); allowing any rider to flip the shared status would let a
        // member's device mutate group-wide lifecycle state.
        if (ride.leaderId != currentUid) {
            Log.w("RideRepository", "Ride completion DENIED: rider $currentUid is not the leader of ride ${ride.id} (leader=${ride.leaderId})")
            return Result.failure(SecurityException("Only the ride leader can complete the ride"))
        }

        // Prevent duplicate completion submissions (double tap + listener echo races).
        if (!isCompletingRide.compareAndSet(false, true)) {
            Log.d("RideRepository", "Ride completion already in progress for ride ${ride.id}; ignoring duplicate request")
            return Result.success(Unit)
        }

        try {
            val activeSos = _activeSos.value
            if (activeSos != null && activeSos.riderId == currentUid && !activeSos.isResolved) {
                resolveSos()
            }
            // Compute actual elapsed seconds from wall-clock start timestamp.
            // Fall back to totalDurationSeconds only if we never recorded a start time (e.g. rejoin).
            val nowMs = System.currentTimeMillis()
            val actualElapsedSec: Double = if (rideStartedAtMs > 0L) {
                ((nowMs - rideStartedAtMs) / 1000.0).coerceAtLeast(0.0)
            } else {
                ride.totalDurationSeconds
            }

            // 1. Authoritative shared write: update ride status to COMPLETED via Supabase.
            var lastError: Exception? = null
            if (remoteDataSource != null && localDataSource != null) {
                try {
                    val success = remoteDataSource!!.updateRide(
                        rideId = ride.id,
                        updates = mapOf<String, Any>(
                            "status" to RideStatus.COMPLETED.name,
                            "elapsed_duration_seconds" to actualElapsedSec,
                            "updated_at" to System.currentTimeMillis()
                        )
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "ride",
                            entityId = ride.id,
                            opType = "UPDATE_STATUS",
                            payload = mapOf<String, Any>(
                                "status" to RideStatus.COMPLETED.name,
                                "elapsed_duration_seconds" to actualElapsedSec
                            )
                        )
                        lastError = Exception("Supabase write returned false")
                    } else {
                        lastError = null
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w("RideRepository", "Supabase ride completion failed: ${e.message}")
                }
            } else {
                lastError = IllegalStateException("remoteDataSource unavailable for ride completion")
            }

            // 2. The local device transition is applied once, regardless of the write
            //    outcome, so the leader's own session is never stuck in ACTIVE when the
            //    network fails (offline Firestore persistence replays queued writes).
            // Use the captured `ride` (non-null, validated at line 1418) instead of
            // re-reading _currentRide.value, which a concurrent coroutine (e.g. leaveRide)
            // may have nulled out between the null check and this point — that race was
            // the NullPointerException at endRide line 1496.
            val completedRide = ride.copy(
                status = RideStatus.COMPLETED,
                elapsedDurationSeconds = actualElapsedSec
            )
            _currentRide.value = completedRide
            persistActiveRideReference(completedRide)
            sharedCompletedRideIds.add(ride.id)
            rideStartedAtMs = 0L
            _activeSos.value = null
            locationTracker.stopTracking()

            if (lastError == null) {
                Log.d("RideRepository", "Shared ride COMPLETED written for ride ${ride.id} (elapsed=${actualElapsedSec}s)")
                return Result.success(Unit)
            }

            // 3. Fallback: a plain Firestore update() queues into offline persistence, so
            //    completion still propagates once connectivity returns. Only applies when
            //    Supabase is not the configured backend (remoteDataSource == null).
            if (remoteDataSource == null) {
                Log.w("RideRepository", "Ride completion skipped: no remoteDataSource configured")
            }
            return Result.failure<Unit>(lastError!!)
        } finally {
            isCompletingRide.set(false)
        }
    }

    /**
     * Client-side handling for an observed shared COMPLETED status (each joined device).
     * Stops ride-scoped work that must not continue past completion; the per-ride
     * once-only UI/navigation side effects live in the ViewModel.
     */
    fun onSharedRideCompleted(rideId: String) {
        val wasNew = sharedCompletedRideIds.add(rideId)
        locationTracker.stopTracking()
        stopDetector.reset()
        rideStartedAtMs = 0L
        if (_currentRide.value?.id == rideId) {
            _activeSos.value = null
        }
        Log.d("RideRepository", "Shared COMPLETED observed for ride $rideId; ride-scoped tracking ${if (wasNew) "stopped (first)" else "already stopped"}")
    }

    internal fun isSharedRideCompletedObserved(rideId: String): Boolean =
        sharedCompletedRideIds.contains(rideId)

    // ---- Test-only seams (internal visibility, not for production call sites) ----

    /** Applies a shared status update through the SAME policy path as the live listener. */
    internal fun applySharedRideStatusForTest(next: RideStatus) {
        val ride = _currentRide.value ?: return
        val merged = mergeSharedRideStatus(ride.status, next, ride.id)
        _currentRide.value = ride.copy(status = merged)
        if (merged == RideStatus.COMPLETED) sharedCompletedRideIds.add(ride.id)
    }

    /** Lets tests exercise listener reconciliation (dedup/ordering) without Firestore. */
    internal fun ingestSharedMessageForTest(msg: QuickMessage) = publishQuickMessage(msg, fromLocalEcho = false)

    /** Lets tests simulate the "not the leader" authorization failure deterministically. */
    internal fun updateLeaderIdForTest(newLeaderId: String) {
        val ride = _currentRide.value ?: return
        _currentRide.value = ride.copy(leaderId = newLeaderId)
    }

    suspend fun leaveRide() {
        val activeRideId = _currentRide.value?.id
        val currentUid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() }

        if (activeRideId != null && currentUid != null) {
            val activeSos = _activeSos.value
            if (activeSos != null && activeSos.riderId == currentUid && !activeSos.isResolved) {
                resolveSos()
            }
            if (remoteDataSource != null && localDataSource != null) {
                // Phase 4: set membership to LEFT via Supabase
                val success = remoteDataSource!!.setRiderMembership(
                    rideId = activeRideId,
                    userId = currentUid,
                    status = MembershipStatus.LEFT
                )
                if (!success) {
                    enqueueOutbox(
                        entityType = "rider",
                        entityId = currentUid,
                        opType = "SET_MEMBERSHIP",
                        payload = mapOf(
                            "user_id" to currentUid,
                            "ride_id" to activeRideId,
                            "membership_status" to MembershipStatus.LEFT.name
                        )
                    )
                }
            } else {
                Log.w("RideRepository", "leaveRide membership update skipped: remoteDataSource or localDataSource unavailable")
            }
        }

        detachListeners()
        locationTracker.stopTracking()
        rideStartedAtMs = 0L
        lastRecordedGpsPoint = null
        messageRegistry.clear()
        _recentMessages.value = emptyList()
        _recentMessage.value = null
        _messageSendError.value = false
        _currentRide.value = null
        clearAllLocalRideState()
        _activeSos.value = null
        _members.value = emptyList()
        _recentMessage.value = null
        stopDetector.reset()
    }

    fun transferLeadership(newLeaderId: String) {
        val activeRideId = _currentRide.value?.id ?: return
        val currentUid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return

        val currentRideVal = _currentRide.value
        if (currentRideVal != null) {
            _currentRide.value = currentRideVal.copy(leaderId = newLeaderId)
        }

        _members.value = _members.value.map { member ->
            when (member.id) {
                newLeaderId -> member.copy(role = RideRole.LEADER)
                currentUid -> member.copy(role = RideRole.MEMBER)
                else -> member
            }
        }

        repositoryScope.launch {
            try {
                if (remoteDataSource != null && localDataSource != null) {
                    // Phase 4: transfer leadership via Supabase
                    val success = remoteDataSource!!.transferLeadership(
                        rideId = activeRideId,
                        newLeaderId = newLeaderId
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "ride",
                            entityId = activeRideId,
                            opType = "TRANSFER_LEADERSHIP",
                            payload = mapOf(
                                "leader_id" to newLeaderId,
                                "previous_leader_id" to currentUid
                            )
                        )
                    }
                    Log.d("RideRepository", "Transferred leadership of ride $activeRideId to $newLeaderId (supabase=$success)")
                } else {
                    Log.w("RideRepository", "transferLeadership persistence skipped: remoteDataSource or localDataSource unavailable")
                }
            } catch (e: Exception) {
                Log.w("RideRepository", "transferLeadership persistence failed: ${e.message}")
            }
        }
    }

    fun sendQuickMessage(type: QuickMessageType) {
        val activeRideId = _currentRide.value?.id ?: return
        val currentUid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return

        _messageSendError.value = false

        // Stable id generated ONCE — retries reuse the same id so a retry can never
        // create a duplicate document, and double-sends are distinct messages (each
        // user tap is an intentional dispatch).
        val msgId = UUID.randomUUID().toString()
        val msg = QuickMessage(
            id = msgId,
            senderId = currentUid,
            senderName = currentUserName,
            type = type,
            text = type.label,
            timestampMs = System.currentTimeMillis()
        )

        // Local echo first (instant own-device feedback); the same stable id is later
        // reconciled against the server snapshot (dedup, no double display).
        publishQuickMessage(msg, fromLocalEcho = true)

        repositoryScope.launch {
            if (remoteDataSource != null && localDataSource != null) {
                // Phase 4: send message via Supabase
                try {
                    val success = remoteDataSource!!.sendMessage(
                        rideId = activeRideId,
                        msg = msg
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "message",
                            entityId = msgId,
                            opType = "CREATE",
                            payload = mapOf(
                                "id" to msgId,
                                "ride_id" to activeRideId,
                                "sender_id" to currentUid,
                                "sender_name" to currentUserName,
                                "type" to type.name,
                                "text" to type.label,
                                "timestamp_ms" to msg.timestampMs
                            )
                        )
                        _messageSendError.value = true
                    } else {
                        Log.d("RideRepository", "Group message sent via Supabase (msgId=$msgId ride=$activeRideId)")
                    }
                } catch (e: Exception) {
                    enqueueOutbox(
                        entityType = "message",
                        entityId = msgId,
                        opType = "CREATE",
                        payload = mapOf(
                            "id" to msgId,
                            "ride_id" to activeRideId,
                            "sender_id" to currentUid,
                            "sender_name" to currentUserName,
                            "type" to type.name,
                            "text" to type.label,
                            "timestamp_ms" to msg.timestampMs
                        )
                    )
                    _messageSendError.value = true
                    Log.w("RideRepository", "Group message send via Supabase failed: ${e.message}")
                }
                return@launch
            }

            Log.w("RideRepository", "Group message send skipped: remoteDataSource or localDataSource unavailable (msgId=$msgId ride=$activeRideId)")
            _messageSendError.value = true
        }
    }

    /**
     * Single reconciliation entry point for every group message reaching this device
     * (local echo and server snapshots). Deduplicates by stable id, keeps a bounded
     * chronological history and publishes the legacy single-slot "recent message".
     */
    private fun publishQuickMessage(msg: QuickMessage, fromLocalEcho: Boolean) {
        val isNew = messageRegistry.accept(msg)
        val history = messageRegistry.snapshot()
        _recentMessages.value = history
        if (isNew) {
            // Newest message (by registry order) wins the legacy single-slot view; older
            // new arrivals remain available in the list view for the sequential toast.
            _recentMessage.value = messageRegistry.newest()
            Log.d(
                "RideRepository",
                "Group message ${if (fromLocalEcho) "echo" else "recv"}: msgId=${msg.id} senderId=${msg.senderId} ts=${msg.timestampMs} (history=${history.size})"
            )
        }
    }

    suspend fun triggerSos(): Result<SosEvent> {
        val activeRideId = _currentRide.value?.id
        val currentUid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() }

        val existingSos = _activeSos.value
        val previousRiderStatus = _currentRiderStatus.value

        if (activeRideId == null || currentUid == null) {
            return Result.failure(IllegalStateException("Active ride or current user is unavailable"))
        }

        // Idempotency safeguard: reject duplicate active SOS triggers
        if (existingSos != null && existingSos.riderId == currentUid && !existingSos.isResolved) {
            Log.d("RideRepository", "Active SOS already exists for rider $currentUid, ignoring duplicate trigger")
            return Result.success(existingSos)
        }

        val latestUpdate = locationTracker.currentLocation.value
        val nowMs = System.currentTimeMillis()

        val (loc, source, accuracy) = if (latestUpdate != null &&
            RiderSyncPolicy.validateCoordinates(latestUpdate.latLng.latitude, latestUpdate.latLng.longitude)
        ) {
            val ageMs = nowMs - latestUpdate.timestampMs
            if (ageMs in 0L..5000L && latestUpdate.accuracyMeters in 0f..30f) {
                Triple(latestUpdate.latLng, "FRESH_LOCATION_FIX", latestUpdate.accuracyMeters)
            } else if (ageMs >= 0L) {
                Triple(latestUpdate.latLng, "LAST_KNOWN_LOCATION", latestUpdate.accuracyMeters)
            } else {
                Triple(LatLng(0.0, 0.0), "LOCATION_UNAVAILABLE", -1f)
            }
        } else {
            val currentUserLoc = _members.value.find { it.isCurrentUser }?.location
            if (currentUserLoc != null && RiderSyncPolicy.validateCoordinates(currentUserLoc.latitude, currentUserLoc.longitude)) {
                Triple(currentUserLoc, "LAST_KNOWN_LOCATION", 10f)
            } else {
                Triple(LatLng(0.0, 0.0), "LOCATION_UNAVAILABLE", -1f)
            }
        }

        val sosId = UUID.randomUUID().toString()
        val event = SosEvent(
            id = sosId,
            riderId = currentUid,
            riderName = currentUserName,
            location = loc,
            timestampMs = nowMs,
            isResolved = false,
            accuracyMeters = accuracy,
            locationSource = source
        )

        val backend = sosBackend
        if (backend == null && remoteDataSource == null) {
            Log.w("RideRepository", "No backend available for SOS trigger")
            return Result.failure(IllegalStateException("No backend available for SOS trigger"))
        }

        _activeSos.value = event
        _currentRiderStatus.value = MemberStatus.EMERGENCY

        return try {
            if (backend != null) {
                // Injected delivery backend (offline tests): the event is
                // handed to the backend and success is reported once it
                // completed.
                backend.deliver(activeRideId, sosId, mapOf(
                    "id" to sosId,
                    "rideId" to activeRideId,
                    "riderId" to currentUid,
                    "riderName" to currentUserName,
                    "latitude" to loc.latitude,
                    "longitude" to loc.longitude,
                    "accuracyMeters" to accuracy,
                    "locationSource" to source,
                    "isResolved" to false,
                    "timestampMs" to nowMs,
                    "createdAt" to System.currentTimeMillis()
                ))
            } else if (remoteDataSource != null && localDataSource != null) {
                // Phase 4: create SOS via Supabase
                val success = remoteDataSource!!.createSos(
                    rideId = activeRideId,
                    sos = event
                )
                if (!success) {
                    enqueueOutbox(
                        entityType = "sos",
                        entityId = sosId,
                        opType = "CREATE",
                        payload = mapOf(
                            "id" to sosId,
                            "ride_id" to activeRideId,
                            "rider_id" to currentUid,
                            "rider_name" to currentUserName,
                            "latitude" to loc.latitude,
                            "longitude" to loc.longitude,
                            "accuracy_meters" to accuracy,
                            "location_source" to source,
                            "is_resolved" to false,
                            "timestamp_ms" to nowMs
                        )
                    )
                    throw Exception("Supabase SOS create returned false")
                }
            } else {
                Log.w("RideRepository", "SOS trigger skipped: remoteDataSource or localDataSource unavailable")
            }
            sosMeshRelay?.sendSos(event)
            Result.success(event)
        } catch (e: Exception) {
            Log.w("RideRepository", "triggerSos failed: ${e.message}")
            if (_activeSos.value?.id == sosId) {
                _activeSos.value = existingSos
                _currentRiderStatus.value = previousRiderStatus
            }
            Result.failure(e)
        }
    }

    fun acknowledgeSos(sosId: String) {
        val activeRideId = _currentRide.value?.id ?: return
        val currentUid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return

        // Update local state immediately if active emergency is unresolved
        val currentSos = _activeSos.value
        if (currentSos != null && currentSos.id == sosId && !currentSos.isResolved && !currentSos.acknowledgedBy.contains(currentUid)) {
            _activeSos.value = currentSos.copy(acknowledgedBy = currentSos.acknowledgedBy + currentUid)
        }

        sosMeshRelay?.sendSosAck(sosId, currentUid)

        repositoryScope.launch {
            if (remoteDataSource != null && localDataSource != null) {
                // Phase 4: acknowledge SOS via Supabase
                try {
                    val success = remoteDataSource!!.acknowledgeSos(
                        sosId = sosId,
                        ackingUserId = currentUid
                    )
                    if (!success) {
                        enqueueOutbox(
                            entityType = "sos",
                            entityId = sosId,
                            opType = "ACKNOWLEDGE",
                            payload = mapOf(
                                "acking_user_id" to currentUid
                            )
                        )
                        Log.w("RideRepository", "SOS acknowledge via Supabase returned false (sosId=$sosId)")
                    }
                } catch (e: Exception) {
                    enqueueOutbox(
                        entityType = "sos",
                        entityId = sosId,
                        opType = "ACKNOWLEDGE",
                        payload = mapOf(
                            "acking_user_id" to currentUid
                        )
                    )
                    Log.w("RideRepository", "SOS acknowledge via Supabase failed: ${e.message}")
                }
                return@launch
            }

            Log.w("RideRepository", "SOS acknowledge skipped: remoteDataSource or localDataSource unavailable")
        }
    }

    /**
     * Manual per-device removal of the SOS popup by a non-sender member. Purely local
     * courtesy control: it hides THIS member's banner and suppresses stale replays of
     * the event on this device, but never resolves or cancels the sender's emergency
     * (only the sender or the ride leader can [resolveSosInternal]).
     */
    fun dismissSosBanner(sosId: String? = null) {
        val id = sosId ?: _activeSos.value?.id ?: return
        if (id.isBlank()) return
        suppressedSosIds.suppress(id)
        if (_activeSos.value?.id == id) {
            _activeSos.value = null
        }
    }

    internal fun isSosSuppressedThisSession(sosId: String?): Boolean =
        suppressedSosIds.isSuppressed(sosId)

    suspend fun resolveSosInternal(sosId: String? = null): Result<Unit> {
        val activeRideId = _currentRide.value?.id ?: return Result.failure(IllegalStateException("No active ride"))
        val targetEvent = if (sosId != null) {
            _activeSos.value?.takeIf { it.id == sosId }
        } else {
            _activeSos.value
        } ?: return Result.success(Unit)

        val currentUid = auth?.currentUser?.uid ?: currentUserId
        if (targetEvent.riderId != currentUid && _currentRide.value?.leaderId != currentUid) {
            return Result.failure(SecurityException("Only SOS sender or ride leader can resolve SOS alert"))
        }

        val backend = sosBackend
        if (backend == null && remoteDataSource == null) {
            return Result.failure(IllegalStateException("No backend available for SOS resolve"))
        }

        if (_activeSos.value?.id == targetEvent.id) {
            _activeSos.value = null
            _currentRiderStatus.value = MemberStatus.RIDING
        }
        // Tombstone this id so the live SOS listener can never re-apply a stale unresolved
        // snapshot of the event on this device.
        suppressedSosIds.suppress(targetEvent.id)

        if (backend != null) {
            // Injected delivery backend (offline tests).
            return try {
                backend.resolve(
                    activeRideId,
                    targetEvent.id,
                    mapOf(
                        "isResolved" to true,
                        "resolvedAtMs" to System.currentTimeMillis()
                    )
                )
                sosMeshRelay?.sendSosResolve(targetEvent.id)
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RideRepository", "resolveSosInternal backend failed: ${e.message}")
                // The local resolution stays authoritative for the session.
                Result.failure(e)
            }
        }

        if (remoteDataSource != null && localDataSource != null) {
            // Phase 4: resolve SOS via Supabase
            return try {
                val success = remoteDataSource!!.resolveSos(sosId = targetEvent.id)
                if (!success) {
                    Log.w("RideRepository", "Supabase SOS resolve returned false")
                    Result.failure(Exception("Supabase SOS resolve returned false"))
                } else {
                    sosMeshRelay?.sendSosResolve(targetEvent.id)
                    Result.success(Unit)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RideRepository", "resolveSosInternal Supabase failed: ${e.message}")
                Result.failure(e)
            }
        }

        Log.w("RideRepository", "SOS resolve skipped: remoteDataSource or localDataSource unavailable")
        return Result.failure<Unit>(IllegalStateException("remoteDataSource unavailable for SOS resolve"))
    }

    fun resolveSos() {
        repositoryScope.launch {
            resolveSosInternal()
        }
    }

    /**
     * Handles an SOS alert received from a remote peer over the mesh.
     * Updates local state so the rider sees the emergency banner, unless
     * this SOS was already resolved or suppressed on this device.
     */
    fun onMeshSosReceived(event: SosEvent) {
        if (event.id.isBlank()) return
        if (suppressedSosIds.isSuppressed(event.id)) return
        val current = _activeSos.value
        if (current != null && current.id == event.id && current.isResolved) return
        _activeSos.value = event
        _currentRiderStatus.value = MemberStatus.EMERGENCY
    }

    /**
     * Handles a rider status/location update received from a remote peer over the mesh.
     * The caller (e.g. RideViewModel) is responsible for applying the parsed status
     * to local rider state — this method only validates and passes it through.
     */
    fun onMeshRiderStatusReceived(status: RiderStatusMeshRelay.ParsedRiderStatus): Boolean {
        if (status.riderId.isBlank()) return false
        // Ignore our own status updates echoed back through the mesh.
        if (status.riderId == currentUserId) return false
        updateMeshRider(status)
        return true
    }

    /**
     * Adds or updates a rider in the local members list from a mesh status update.
     * Mesh-only riders are marked DISCONNECTED (no internet) so the UI can
     * distinguish them from Firestore-synced riders.
     */
    private fun updateMeshRider(status: RiderStatusMeshRelay.ParsedRiderStatus) {
        val existing = _members.value.find { it.id == status.riderId }
        val member = RiderMember(
            id = status.riderId,
            name = status.riderName,
            role = RideRole.MEMBER,
            status = status.status,
            movementState = when {
                status.status == MemberStatus.STOPPED -> MovementState.STOPPED
                status.status == MemberStatus.RIDING -> MovementState.RIDING
                else -> MovementState.UNKNOWN
            },
            emergencyState = if (status.status == MemberStatus.EMERGENCY) EmergencyState.ACTIVE else EmergencyState.NONE,
            connectionStatus = ConnectionStatus.DISCONNECTED,
            location = status.location,
            speedKmh = status.speedKmh,
            headingDeg = status.headingDeg,
            headingSource = if (status.headingDeg != null) HeadingSource.GNSS_COURSE else HeadingSource.UNAVAILABLE,
            accuracyMeters = status.accuracyMeters,
            lastUpdatedMs = status.timestampMs,
            isCurrentUser = false,
        )
        if (existing != null) {
            _members.value = _members.value.map { if (it.id == status.riderId) member else it }
        } else {
            _members.value = _members.value + member
        }
    }

    fun cleanup() {
        detachListeners()
        repositoryJob.cancelChildren()
    }

    private fun detachListeners() {
        // Clean up Supabase Realtime subscriptions
        rideSub?.invoke()
        rideSub = null
        ridersSub?.invoke()
        ridersSub = null
        messagesSub?.invoke()
        messagesSub = null
        sosSub?.invoke()
        sosSub = null
    }

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = SecureRandom()
        return (1..6).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    private fun createOrJoinFallbackRide(code: String, userLoc: LatLng, uid: String): Result<Ride> {
        val fallbackRide = Ride(
            id = "ride_$code",
            name = "Group Convoy ($code)",
            startLocationName = "Start Point",
            destinationName = "Destination",
            startLocation = userLoc,
            destinationLocation = LatLng(userLoc.latitude + 0.20, userLoc.longitude - 0.20),
            leaderId = "leader_$code",
            inviteCode = code,
            status = RideStatus.LOBBY
        )
        activeLocalRides[code] = fallbackRide
        _currentRide.value = fallbackRide
        _joinError.value = null

        val member = RiderMember(
            id = uid,
            name = currentUserName,
            motorcycleModel = currentMotorcycleModel,
            role = RideRole.MEMBER,
            status = _currentRiderStatus.value,
            location = userLoc,
            isCurrentUser = true
        )
        _members.value = listOf(member)
        return Result.success(fallbackRide)
    }

    companion object {
        private val activeLocalRides = java.util.concurrent.ConcurrentHashMap<String, Ride>()
    }
}
