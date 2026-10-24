package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.location.LocationTracker
import com.example.data.location.LocationUpdate
import com.example.data.location.StopDetector
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
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
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
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

class RideRepository(
    val locationTracker: LocationTracker,
    val firestore: FirebaseFirestore?,
    val context: Context? = null,
    val stopDetector: StopDetector = StopDetector(
        stopSpeedThresholdKmh = 5.0,
        resumeSpeedThresholdKmh = 8.0,
        stopDurationThresholdSec = 120L
    ),
    /**
     * Delivery backend for SOS events.
     *
     * Production leaves this null: events are written to Firestore and the
     * trigger only reports success once that write completed. Tests inject a
     * local backend so the SOS lifecycle (state machine, idempotency,
     * acknowledgment, resolution) can be exercised offline - the offline suite
     * must never depend on a live backend or on network state.
     */
    val sosBackend: SosDeliveryBackend? = null
) {
    // Secondary constructor for convenience resolving default database
    constructor(context: Context, locationTracker: LocationTracker) : this(
        locationTracker = locationTracker,
        firestore = try {
            FirebaseFirestore.getInstance()
        } catch (_: Exception) {
            null
        },
        context = context
    )

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

    private var rideListener: ListenerRegistration? = null
    private var ridersListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private var sosListener: ListenerRegistration? = null

    private val roomDb: com.example.data.db.RideDatabase? by lazy {
        this.context?.let { com.example.data.db.RideDatabase.getInstance(it) }
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
    }

    fun initCurrentUser(id: String, name: String, motorcycleModel: String = "") {
        currentUserId = id
        currentUserName = name
        currentMotorcycleModel = motorcycleModel
        // Also save/update user doc in Firestore
        repositoryScope.launch {
            try {
                val userDoc = mapOf(
                    "id" to id,
                    "name" to name,
                    "motorcycleModel" to motorcycleModel,
                    "email" to (auth?.currentUser?.email ?: ""),
                    "photoUrl" to (auth?.currentUser?.photoUrl?.toString() ?: ""),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore?.collection("users")?.document(id)
                    ?.set(userDoc, SetOptions.merge())
                    ?.await()

                fetchActiveRideForUser()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, "users/$id")
            }
        }
    }

    suspend fun fetchActiveRideForUser(): Ride? {
        val uid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return _currentRide.value
        val fs = firestore ?: return _currentRide.value

        return try {
            val snapshot = fs.collection("rides")
                .whereIn("status", listOf("CREATED", "LOBBY", "ACTIVE"))
                .get()
                .await()

            val activeDoc = snapshot.documents.find { doc ->
                val leaderId = doc.getString("leaderId") ?: ""
                leaderId == uid
            }

            if (activeDoc != null) {
                // Never restore a ride from a cached snapshot over an in-session ride with a
                // different id — a startup fetch ran asynchronously may otherwise clobber a
                // ride the user just created/joined this session with stale session data.
                val localCurrent = _currentRide.value
                if (localCurrent != null && localCurrent.id != activeDoc.id) {
                    return localCurrent
                }
                val rideId = activeDoc.id
                val rideName = activeDoc.getString("name") ?: "Group Ride"
                val startLocName = activeDoc.getString("startLocationName") ?: "Start"
                val destLocName = activeDoc.getString("destinationName") ?: "Destination"
                val startLat = activeDoc.getDouble("startLat") ?: 18.5204
                val startLng = activeDoc.getDouble("startLng") ?: 73.8567
                val destLat = activeDoc.getDouble("destLat") ?: 18.7546
                val destLng = activeDoc.getDouble("destLng") ?: 73.4062
                val leaderId = activeDoc.getString("leaderId") ?: ""
                val code = activeDoc.getString("inviteCode") ?: ""
                val statusStr = activeDoc.getString("status") ?: "LOBBY"
                val status = runCatching { RideStatus.valueOf(statusStr) }.getOrDefault(RideStatus.LOBBY)

                val totalDist = activeDoc.getDouble("totalDistanceMeters") ?: 0.0
                val totalDur = activeDoc.getDouble("totalDurationSeconds") ?: 0.0
                // Preserve the planned distance of the in-flight local ride for the same id,
                // mirroring listenToRide(): a same-session refresh must never clobber the
                // immutable trip origin/route plan with a Firestore snapshot that predates
                // the planned-distance write.
                val preservedLocalPlanned = _currentRide.value
                    ?.takeIf { it.id == activeDoc.id }
                    ?.initialPlannedDistanceMeters
                    ?.takeIf { it > 0.0 }
                val initPlannedDist = activeDoc.getDouble("initialPlannedDistanceMeters")
                    ?.takeIf { it > 0.0 }
                    ?: preservedLocalPlanned
                    ?: totalDist
                val startedAt = activeDoc.getLong("startedAtMs") ?: 0L

                val localTelemetry = try { roomDb?.activeRideTelemetryDao()?.getTelemetry(rideId) } catch (_: Exception) { null }
                val restoredStartAt = if (startedAt > 0L) startedAt else (localTelemetry?.startedAtMs ?: 0L)
                if (restoredStartAt > 0L) {
                    rideStartedAtMs = restoredStartAt
                }

                val restoredActualDist = maxOf(
                    activeDoc.getDouble("actualRecordedDistanceMeters") ?: 0.0,
                    localTelemetry?.actualRecordedDistanceMeters ?: 0.0
                )

                val tLat = localTelemetry?.lastGpsLat
                val tLng = localTelemetry?.lastGpsLng
                if (tLat != null && tLng != null) {
                    lastRecordedGpsPoint = LatLng(tLat, tLng)
                    lastGpsTimestampMs = localTelemetry.lastGpsTimestampMs
                }

                val restoredRide = Ride(
                    id = rideId,
                    name = rideName,
                    startLocationName = startLocName,
                    destinationName = destLocName,
                    startLocation = LatLng(startLat, startLng),
                    destinationLocation = LatLng(destLat, destLng),
                    leaderId = leaderId,
                    inviteCode = code,
                    status = status,
                    initialPlannedDistanceMeters = initPlannedDist,
                    totalDistanceMeters = totalDist,
                    totalDurationSeconds = totalDur,
                    routeStops = RouteStopCodec.fromFirestore(
                        activeDoc.get("routeStops") as? List<*>
                    ),
                    routePlanVersion = activeDoc.getLong("routePlanVersion") ?: 0L,
                    selectedRouteIndex = (activeDoc.get("selectedRouteIndex") as? Number)?.toInt() ?: 0,
                    routeOptions = RoutePlanCodec.optionsFromFirestore(
                        activeDoc.get("routeOptions") as? List<*>
                    ),
                    actualRecordedDistanceMeters = restoredActualDist,
                    startedAtMs = restoredStartAt
                )
                _currentRide.value = restoredRide
                listenToRide(rideId)
                restoredRide
            } else {
                _currentRide.value
            }
        } catch (e: Exception) {
            Log.w("RideRepository", "fetchActiveRideForUser failed: ${e.message}")
            _currentRide.value
        }
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

        // Sync heartbeat timestamp to Firestore
        repositoryScope.launch {
            try {
                firestore?.collection("rides")?.document(activeRide.id)
                    ?.collection("riders")?.document(currentUid)
                    ?.update(
                        mapOf(
                            "lastUpdatedMs" to nowMs,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )?.await()
            } catch (e: Exception) {
                Log.w("RideRepository", "Heartbeat sync skipped/failed: ${e.message}")
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

        // Sync rider location to Firestore subcollection /rides/{rideId}/riders/{riderId}
        repositoryScope.launch {
            try {
                val riderMap = mapOf(
                    "id" to currentUid,
                    "userId" to currentUid,
                    "rideId" to activeRideId,
                    "name" to currentUserName,
                    "motorcycleModel" to currentMotorcycleModel,
                    "role" to if (_currentRide.value?.leaderId == currentUid) "LEADER" else "MEMBER",
                    "status" to finalStatus.name,
                    "latitude" to loc.latLng.latitude,
                    "longitude" to loc.latLng.longitude,
                    "speedKmh" to loc.speedKmh,
                    "headingDeg" to loc.headingDeg,
                    "headingSource" to loc.headingSource.name,
                    "headingAccuracyDeg" to loc.headingAccuracyDeg,
                    "accuracyMeters" to loc.accuracyMeters,
                    "sequenceNumber" to seq,
                    "batteryPct" to 90,
                    "stoppedDurationSec" to stoppedSec,
                    "lastUpdatedMs" to loc.timestampMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore?.collection("rides")?.document(activeRideId)
                    ?.collection("riders")?.document(currentUid)
                    ?.set(riderMap, SetOptions.merge())
                    ?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/$activeRideId/riders/$currentUid")
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
        _members.value = listOf(leaderMember)

        val rideDoc = mapOf(
            "id" to rideId,
            "name" to safeRideName,
            "startLocationName" to startName,
            "destinationName" to destName,
            "startLat" to startLoc.latitude,
            "startLng" to startLoc.longitude,
            "destLat" to destLoc.latitude,
            "destLng" to destLoc.longitude,
            "leaderId" to uid,
            "inviteCode" to inviteCode,
            "status" to RideStatus.LOBBY.name,
            "initialPlannedDistanceMeters" to initialApproxMeters,
            "totalDistanceMeters" to 0.0,
            "totalDurationSeconds" to 0.0,
            // Leader's ordered route plan (absent only as a literal empty list):
            // legacy ride readers treat a missing/empty field as no-waypoint ride.
            "routeStops" to RouteStopCodec.toFirestore(routeStops),
            "routePlanVersion" to planVersion,
            // The leader's chosen candidate route and the candidate list it was
            // chosen from. Members re-fetch the geometry for the selected
            // candidate, so only the summaries travel on the ride document.
            "selectedRouteIndex" to selectedRouteIndex.coerceAtLeast(0),
            "routeOptions" to RoutePlanCodec.optionsToFirestore(routeOptions),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val leaderRiderDoc = mapOf(
            "id" to uid,
            "userId" to uid,
            "rideId" to rideId,
            "name" to safeUserName,
            "motorcycleModel" to currentMotorcycleModel,
            "role" to "LEADER",
            "status" to _currentRiderStatus.value.name,
            "latitude" to startLoc.latitude,
            "longitude" to startLoc.longitude,
            "speedKmh" to 0.0,
            "headingDeg" to 0f,
            "batteryPct" to 95,
            "stoppedDurationSec" to 0L,
            "lastUpdatedMs" to System.currentTimeMillis(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        // Attach listeners for this ride session
        try {
            listenToRide(rideId)
        } catch (e: Exception) {
            Log.w("RideRepository", "Failed attaching initial ride listener: ${e.message}")
        }

        // Persist to Firestore asynchronously so creation never blocks the caller or test thread
        repositoryScope.launch(Dispatchers.IO) {
            try {
                firestore?.collection("rides")?.document(rideId)?.set(rideDoc)?.await()
                firestore?.collection("rides")?.document(rideId)
                    ?.collection("riders")?.document(uid)?.set(leaderRiderDoc)?.await()
                Log.d("RideRepository", "Successfully persisted ride $rideId to Firestore")
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, "rides/$rideId")
            }
        }

        return createdRide
    }

    suspend fun joinRide(inviteCode: String, userLoc: LatLng): Result<Ride> {
        val uid = auth?.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: "rider_${System.currentTimeMillis()}"
        val code = inviteCode.trim().uppercase()

        if (code == "ABC123") {
            // Instantly supply preview demo ride so test code chip works reliably
            val demoRide = Ride(
                id = "demo_ride_abc123",
                name = "Western Ghats Adventure",
                startLocationName = "Pune",
                destinationName = "Lonavala",
                startLocation = userLoc,
                destinationLocation = LatLng(userLoc.latitude + 0.23, userLoc.longitude - 0.45),
                leaderId = "leader_demo",
                inviteCode = "ABC123",
                status = RideStatus.LOBBY
            )
            _currentRide.value = demoRide
            _joinError.value = null
            return Result.success(demoRide)
        }

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
            val fs = firestore
            if (fs == null) {
                return createOrJoinFallbackRide(code, userLoc, uid)
            }

            val codeLower = code.lowercase(java.util.Locale.ROOT)
            val codeRaw = inviteCode.trim()

            var matchedDoc: DocumentSnapshot? = null
            try {
                val q1 = fs.collection("rides")
                    .whereEqualTo("inviteCode", code)
                    .get()
                    .await()
                matchedDoc = q1.documents.firstOrNull()
            } catch (e: Exception) {
                Log.w("RideRepository", "Firestore query q1 failed: ${e.message}")
            }

            if (matchedDoc == null) {
                try {
                    val q2 = fs.collection("rides")
                        .whereEqualTo("inviteCode", codeLower)
                        .get()
                        .await()
                    matchedDoc = q2.documents.firstOrNull()
                } catch (e: Exception) {
                    Log.w("RideRepository", "Firestore query q2 failed: ${e.message}")
                }
            }

            if (matchedDoc == null) {
                try {
                    val q3 = fs.collection("rides")
                        .whereEqualTo("inviteCode", codeRaw)
                        .get()
                        .await()
                    matchedDoc = q3.documents.firstOrNull()
                } catch (e: Exception) {
                    Log.w("RideRepository", "Firestore query q3 failed: ${e.message}")
                }
            }

            if (matchedDoc == null) {
                try {
                    val allRides = fs.collection("rides").limit(100).get().await()
                    matchedDoc = allRides.documents.find { doc ->
                        val ic = doc.getString("inviteCode").orEmpty().trim()
                        ic.equals(code, ignoreCase = true) ||
                        ic.equals(codeLower, ignoreCase = true) ||
                        ic.equals(codeRaw, ignoreCase = true)
                    }
                } catch (e: Exception) {
                    Log.w("RideRepository", "Fallback scan failed: ${e.message}")
                }
            }

            if (matchedDoc == null) {
                return createOrJoinFallbackRide(code, userLoc, uid)
            }

            val rideDoc = matchedDoc
            val rideId = rideDoc.id
            val rideName = rideDoc.getString("name") ?: "Group Ride"
            val startLocName = rideDoc.getString("startLocationName") ?: "Start"
            val destLocName = rideDoc.getString("destinationName") ?: "Destination"
            val startLat = rideDoc.getDouble("startLat") ?: userLoc.latitude
            val startLng = rideDoc.getDouble("startLng") ?: userLoc.longitude
            val destLat = rideDoc.getDouble("destLat") ?: (userLoc.latitude + 0.1)
            val destLng = rideDoc.getDouble("destLng") ?: (userLoc.longitude + 0.1)
            val leaderId = rideDoc.getString("leaderId") ?: ""
            val statusStr = rideDoc.getString("status") ?: "LOBBY"
            val status = runCatching { RideStatus.valueOf(statusStr) }.getOrDefault(RideStatus.LOBBY)

            // Register current rider in Firestore /rides/{rideId}/riders/{uid}
            val memberDoc = mapOf(
                "id" to uid,
                "userId" to uid,
                "rideId" to rideId,
                "name" to currentUserName,
                "motorcycleModel" to currentMotorcycleModel,
                "role" to if (leaderId == uid) "LEADER" else "MEMBER",
                "status" to _currentRiderStatus.value.name,
                "latitude" to userLoc.latitude,
                "longitude" to userLoc.longitude,
                "speedKmh" to 0.0,
                "headingDeg" to 0f,
                "batteryPct" to 90,
                "stoppedDurationSec" to 0L,
                "lastUpdatedMs" to System.currentTimeMillis(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            firestore?.collection("rides")?.document(rideId)
                ?.collection("riders")?.document(uid)?.set(memberDoc, SetOptions.merge())?.await()

            val joinedRide = Ride(
                id = rideId,
                name = rideName,
                startLocationName = startLocName,
                destinationName = destLocName,
                startLocation = LatLng(startLat, startLng),
                destinationLocation = LatLng(destLat, destLng),
                leaderId = leaderId,
                inviteCode = code,
                status = status
            )
            _currentRide.value = joinedRide
            _joinError.value = null

            // Start live listeners for all riders, messages, and SOS events in this ride
            listenToRide(rideId)
            Result.success(joinedRide)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "rides?inviteCode=$code")
            _joinError.value = "Failed to join ride: ${e.localizedMessage}"
            Result.failure(e)
        }
    }

    private fun listenToRide(rideId: String) {
        detachListeners()
        val fs = firestore ?: return
        val currentUid = auth?.currentUser?.uid ?: ""

        // 1. Listen to Ride metadata
        val rideRef = fs.collection("rides").document(rideId)
        rideListener = rideRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.GET, rideRef.path)
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val name = snapshot.getString("name") ?: "Group Ride"
                val startLocName = snapshot.getString("startLocationName") ?: "Start"
                val destLocName = snapshot.getString("destinationName") ?: "Destination"
                val startLat = snapshot.getDouble("startLat") ?: 18.5204
                val startLng = snapshot.getDouble("startLng") ?: 73.8567
                val destLat = snapshot.getDouble("destLat") ?: 18.7546
                val destLng = snapshot.getDouble("destLng") ?: 73.4062
                val leaderId = snapshot.getString("leaderId") ?: ""
                val code = snapshot.getString("inviteCode") ?: ""
                val statusStr = snapshot.getString("status") ?: "LOBBY"
                val incomingStatus = runCatching { RideStatus.valueOf(statusStr) }.getOrDefault(RideStatus.LOBBY)

                // Ignore snapshots belonging to a ride this client already left (obsolete
                // listener callback must not mutate the current ride's metadata).
                val existingRide = _currentRide.value?.takeIf { it.id == rideId }
                if (_currentRide.value != null && existingRide == null) {
                    Log.d("RideRepository", "Ignoring ride-doc snapshot for a different ride $rideId (current=${_currentRide.value?.id})")
                    return@addSnapshotListener
                }

                // Canonical shared lifecycle reconciliation: a stale snapshot can never
                // move a COMPLETED/CANCELLED ride back to ACTIVE/LOBBY.
                val status = mergeSharedRideStatus(existingRide?.status, incomingStatus, rideId)

                val totalDist = snapshot.getDouble("totalDistanceMeters")
                    ?.takeIf { it > 0.0 }
                    ?: existingRide?.totalDistanceMeters
                    ?: 0.0
                val totalDur = snapshot.getDouble("totalDurationSeconds")
                    ?.takeIf { it > 0.0 }
                    ?: existingRide?.totalDurationSeconds
                    ?: 0.0

                val snapshotStartedAt = snapshot.getLong("startedAtMs") ?: 0L
                val localTelemetry = try {
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                        roomDb?.activeRideTelemetryDao()?.getTelemetry(rideId)
                    }
                } catch (_: Exception) { null }
                val restoredStartedAt = if (snapshotStartedAt > 0L) {
                    snapshotStartedAt
                } else if ((existingRide?.startedAtMs ?: 0L) > 0L) {
                    existingRide!!.startedAtMs
                } else {
                    localTelemetry?.startedAtMs ?: 0L
                }

                if (restoredStartedAt > 0L && rideStartedAtMs <= 0L) {
                    rideStartedAtMs = restoredStartedAt
                }

                val initialPlannedDist = snapshot.getDouble("initialPlannedDistanceMeters")
                    ?.takeIf { it > 0.0 }
                    ?: existingRide?.initialPlannedDistanceMeters?.takeIf { it > 0.0 }
                    ?: totalDist
                val preservedActualDist = maxOf(
                    existingRide?.actualRecordedDistanceMeters ?: 0.0,
                    localTelemetry?.actualRecordedDistanceMeters ?: 0.0,
                    snapshot.getDouble("actualRecordedDistanceMeters") ?: 0.0
                )
                // Preserve the larger elapsed duration: local (in-progress ride) wins over Firestore
                // unless Firestore has a non-zero completed value (e.g. after rejoin).
                val remoteElapsedSec = snapshot.getDouble("elapsedDurationSeconds") ?: 0.0
                val preservedElapsedSec = maxOf(
                    existingRide?.elapsedDurationSeconds ?: 0.0,
                    remoteElapsedSec
                )

                // Leader-authoritative route-plan merge: adopt a snapshot's stops
                // only when its plan version is strictly newer than the locally
                // known version (leader publications); stale snapshots are
                // rejected and keep the current shared plan untouched.
                val incomingPlanVersion = snapshot.getLong("routePlanVersion") ?: 0L
                val incomingStops = RouteStopCodec.fromFirestore(
                    snapshot.get("routeStops") as? List<*>
                )
                val incomingSelectedRouteIndex =
                    (snapshot.get("selectedRouteIndex") as? Number)?.toInt() ?: 0
                val incomingRouteOptions = RoutePlanCodec.optionsFromFirestore(
                    snapshot.get("routeOptions") as? List<*>
                )
                // The stops, the candidate list and the leader's selection are
                // one plan: they travel together through the version guard, so a
                // stale snapshot can never pair new stops with an old selection.
                val mergedPlan = RoutePlanCodec.merge(
                    localVersion = existingRide?.routePlanVersion ?: 0L,
                    localStops = existingRide?.routeStops ?: emptyList(),
                    localSelectedRouteIndex = existingRide?.selectedRouteIndex ?: 0,
                    localOptions = existingRide?.routeOptions ?: emptyList(),
                    incomingVersion = incomingPlanVersion,
                    incomingStops = incomingStops,
                    incomingSelectedRouteIndex = incomingSelectedRouteIndex,
                    incomingOptions = incomingRouteOptions
                )
                val mergedPlanVersion = mergedPlan.version
                val mergedRouteStops = mergedPlan.stops
                val mergedSelectedRouteIndex = mergedPlan.selectedRouteIndex
                val mergedRouteOptions = mergedPlan.options

                val baseRide = existingRide ?: Ride(
                    id = rideId,
                    name = name,
                    startLocationName = startLocName,
                    destinationName = destLocName,
                    startLocation = LatLng(startLat, startLng),
                    destinationLocation = LatLng(destLat, destLng),
                    leaderId = leaderId,
                    inviteCode = code,
                    status = status,
                    initialPlannedDistanceMeters = initialPlannedDist,
                    totalDistanceMeters = totalDist,
                    totalDurationSeconds = totalDur,
                    routeStops = mergedRouteStops,
                    routePlanVersion = mergedPlanVersion,
                    selectedRouteIndex = mergedSelectedRouteIndex,
                    routeOptions = mergedRouteOptions,
                    actualRecordedDistanceMeters = preservedActualDist,
                    elapsedDurationSeconds = preservedElapsedSec,
                    startedAtMs = restoredStartedAt
                )

                _currentRide.value = baseRide.copy(
                    name = name,
                    status = status,
                    initialPlannedDistanceMeters = initialPlannedDist,
                    totalDistanceMeters = if (totalDist > 0.0) totalDist else baseRide.totalDistanceMeters,
                    totalDurationSeconds = if (totalDur > 0.0) totalDur else baseRide.totalDurationSeconds,
                    routeStops = mergedRouteStops,
                    routePlanVersion = mergedPlanVersion,
                    selectedRouteIndex = mergedSelectedRouteIndex,
                    routeOptions = mergedRouteOptions,
                    actualRecordedDistanceMeters = preservedActualDist,
                    elapsedDurationSeconds = preservedElapsedSec,
                    startedAtMs = restoredStartedAt
                )
            }
        }

        // 2. Real-time Listen to ALL riders in this ride: /rides/{rideId}/riders
        val ridersRef = fs.collection("rides").document(rideId).collection("riders")
        ridersListener = ridersRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, ridersRef.path)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val ridersList = snapshot.documents.mapNotNull { doc ->
                    parseRiderDoc(doc, currentUid)
                }
                if (ridersList.isNotEmpty()) {
                    _members.value = ridersList
                } else if (_members.value.isNotEmpty()) {
                    // Keep existing local members if remote snapshot is temporarily empty
                    Log.d("RideRepository", "Remote riders snapshot empty, keeping ${_members.value.size} local members")
                }
            }
        }

        // 3. Real-time Listen to messages: /rides/{rideId}/messages
        val messagesRef = fs.collection("rides").document(rideId).collection("messages")
        messagesListener = messagesRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                // Explicit listener-error handling (never silently swallowed): damage is
                // bounded — no message is dropped locally, and the error is surfaced.
                handleFirestoreError(error, OperationType.LIST, messagesRef.path)
                _messageSendError.value = true
                return@addSnapshotListener
            }
            // Obsolete-ride guard: a callback racing a ride switch must not publish
            // another ride's messages into the current one.
            if (_currentRide.value != null && _currentRide.value?.id != rideId) return@addSnapshotListener
            if (snapshot != null) {
                snapshot.documents.forEach { doc ->
                    parseQuickMessageDoc(doc)?.let { msg -> publishQuickMessage(msg, fromLocalEcho = false) }
                }
            }
        }

        // 4. Real-time Listen to SOS events: /rides/{rideId}/sos
        val sosRef = fs.collection("rides").document(rideId).collection("sos")
        sosListener = sosRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, sosRef.path)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val activeSosDoc = snapshot.documents.filter {
                    it.getBoolean("isResolved") != true
                }.maxWithOrNull(
                    compareBy<DocumentSnapshot> { it.getLong("timestampMs") ?: 0L }
                        .thenBy { it.id }
                )
                // Suppress tombstoned/resolved and member-dismissed events so stale or
                // replayed snapshots can never re-apply a locally-removed alert.
                val suppressedDoc = activeSosDoc?.takeIf { suppressedSosIds.isSuppressed(it.id) }
                if (!snapshot.isEmpty && activeSosDoc != null && suppressedDoc == null) {
                    val riderId = activeSosDoc.getString("riderId") ?: ""
                    val riderName = activeSosDoc.getString("riderName") ?: "Rider"
                    val lat = activeSosDoc.getDouble("latitude") ?: 0.0
                    val lng = activeSosDoc.getDouble("longitude") ?: 0.0
                    val ts = activeSosDoc.getLong("timestampMs") ?: System.currentTimeMillis()

                    val acks = (activeSosDoc.get("acknowledgedBy") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    val acc = (activeSosDoc.getDouble("accuracyMeters") ?: 10.0).toFloat()
                    val source = activeSosDoc.getString("locationSource") ?: "FRESH_LOCATION_FIX"

                    val event = SosEvent(
                        id = activeSosDoc.id,
                        riderId = riderId,
                        riderName = riderName,
                        location = LatLng(lat, lng),
                        timestampMs = ts,
                        isResolved = false,
                        acknowledgedBy = acks,
                        accuracyMeters = acc,
                        locationSource = source
                    )
                    _activeSos.value = event
                    if (riderId == currentUid) {
                        _currentRiderStatus.value = MemberStatus.EMERGENCY
                    }
                } else {
                    _activeSos.value = null
                    if (_currentRiderStatus.value == MemberStatus.EMERGENCY) {
                        _currentRiderStatus.value = MemberStatus.RIDING
                    }
                }
            }
        }
    }

    private fun parseRiderDoc(doc: DocumentSnapshot, currentUid: String): RiderMember? {
        val id = doc.getString("userId") ?: doc.getString("id") ?: doc.id
        val name = doc.getString("name") ?: "Rider"
        val motorcycle = doc.getString("motorcycleModel") ?: ""

        val rideLeaderId = _currentRide.value?.leaderId ?: ""
        val roleStr = doc.getString("role") ?: "MEMBER"
        val isLeader = (id == rideLeaderId) || roleStr.equals("LEADER", ignoreCase = true)
        val role = if (isLeader) RideRole.LEADER else RideRole.MEMBER

        val statusStr = doc.getString("status") ?: "RIDING"
        val status = runCatching { MemberStatus.valueOf(statusStr) }.getOrDefault(MemberStatus.RIDING)

        val defaultLat = locationTracker.currentLocation.value?.latLng?.latitude ?: _currentRide.value?.startLocation?.latitude ?: 18.5204
        val defaultLng = locationTracker.currentLocation.value?.latLng?.longitude ?: _currentRide.value?.startLocation?.longitude ?: 73.8567

        val rawLat = doc.getDouble("latitude") ?: doc.getDouble("startLat")
        val rawLng = doc.getDouble("longitude") ?: doc.getDouble("startLng")

        val speedKmh = doc.getDouble("speedKmh") ?: 0.0
        // Qualified Bearing: missing/invalid heading stays null (never 0° north).
        val headingDeg = doc.getDouble("headingDeg")
            ?.toFloat()
            ?.takeIf { it.isFinite() && it >= 0f }
        val headingSource = doc.getString("headingSource")
            ?.let { raw -> runCatching { com.example.data.model.HeadingSource.valueOf(raw) }.getOrNull() }
            ?: if (headingDeg != null) com.example.data.model.HeadingSource.GNSS_COURSE
            else com.example.data.model.HeadingSource.UNAVAILABLE
        val headingAccuracyDeg = doc.getDouble("headingAccuracyDeg")
            ?.toFloat()
            ?.takeIf { it.isFinite() && it >= 0f }
        val stoppedSec = doc.getLong("stoppedDurationSec") ?: 0L
        val rawLastUpdated = doc.getLong("lastUpdatedMs") ?: doc.getDouble("lastUpdatedMs")?.toLong()
        val serverTimestampMs = doc.getTimestamp("updatedAt")?.toDate()?.time
        val battery = (doc.getLong("batteryPct") ?: 90L).toInt()
        val seq = doc.getLong("sequenceNumber") ?: 0L
        val accuracy = (doc.getDouble("accuracyMeters") ?: 10.0).toFloat()

        val existing = _members.value.find { it.id == id }
        val prevSeq = riderLatestSequence[id] ?: -1L
        val isCurrentUser = (id == currentUid)

        val avatarColorHex = when (Math.abs(id.hashCode()) % 5) {
            0 -> 0xFFFF9800
            1 -> 0xFF4CAF50
            2 -> 0xFF29B6F6
            3 -> 0xFFFFCA28
            else -> 0xFFAB47BC
        }

        val emergencyState = if (status == MemberStatus.EMERGENCY || _activeSos.value?.riderId == id) {
            com.example.data.model.EmergencyState.ACTIVE
        } else {
            com.example.data.model.EmergencyState.NONE
        }

        val resultMember = RiderSyncPolicy.processRiderSnapshot(
            id = id,
            name = name,
            motorcycleModel = motorcycle,
            avatarColorHex = avatarColorHex,
            role = role,
            status = status,
            emergencyState = emergencyState,
            rawLat = rawLat,
            rawLng = rawLng,
            defaultLat = defaultLat,
            defaultLng = defaultLng,
            speedKmh = speedKmh,
            headingDeg = headingDeg,
            headingSource = headingSource,
            headingAccuracyDeg = headingAccuracyDeg,
            accuracyMeters = accuracy,
            sequenceNumber = seq,
            rawLastUpdatedMs = rawLastUpdated,
            serverTimestampMs = serverTimestampMs,
            stoppedDurationSec = stoppedSec,
            batteryPct = battery,
            existing = existing,
            prevSeq = prevSeq,
            isCurrentUser = isCurrentUser,
            isGpsAvailable = locationTracker.isGpsAvailable.value,
            currentTimeMs = System.currentTimeMillis()
        )

        if (resultMember != null && seq > 0 && seq >= prevSeq) {
            riderLatestSequence[id] = seq
        }

        return resultMember
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

        // Save total distance and duration to Firestore
        repositoryScope.launch {
            try {
                firestore?.collection("rides")?.document(current.id)
                    ?.update(
                        mapOf(
                            "initialPlannedDistanceMeters" to initDist,
                            "totalDistanceMeters" to totalDistanceMeters,
                            "totalDurationSeconds" to totalDurationSeconds,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/${current.id}")
            }
        }

        // B-03 (audit F12-D1 / F7-D2): persist the route GEOMETRY as well, so a
        // process death does not restore a ride without a route.
        //
        // The geometry is stored in a dedicated subcollection document
        // (`rides/{rideId}/route`) rather than in the ride document itself: the
        // ride document is re-read by every listener on every member/location
        // update, so a large polyline there would be re-parsed constantly (and
        // would push the document towards Firestore's 1 MiB limit).
        repositoryScope.launch {
            try {
                firestore?.collection("rides")?.document(current.id)
                    ?.collection("route")?.document("current")
                    ?.set(
                        RouteGeometryCodec.encode(
                            points = routePoints,
                            steps = steps,
                            totalDistanceMeters = totalDistanceMeters,
                            totalDurationSeconds = totalDurationSeconds
                        ) + mapOf("updatedAt" to FieldValue.serverTimestamp())
                    )?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/${current.id}/route")
            }
        }
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
                firestore?.collection("rides")?.document(current.id)
                    ?.update(
                        mapOf(
                            "routeStops" to RouteStopCodec.toFirestore(routeStops),
                            "routePlanVersion" to planVersion,
                            "selectedRouteIndex" to safeIndex,
                            "routeOptions" to RoutePlanCodec.optionsToFirestore(routeOptions),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/${current.id}")
            }
        }
    }

    /**
     * Reads the persisted route geometry for a ride (B-03). Returns `null` when
     * the ride has no stored geometry (older rides) or when the read fails —
     * the caller then falls back to a route refetch.
     */
    suspend fun fetchRouteGeometry(rideId: String): StoredRouteGeometry? {
        val fs = firestore ?: return null
        return try {
            val snapshot = fs.collection("rides").document(rideId)
                .collection("route").document("current").get().await()
            if (!snapshot.exists()) return null
            val geometry = RouteGeometryCodec.decode(
                rawPoints = snapshot.get("points"),
                rawSteps = snapshot.get("steps"),
                totalDistanceMeters = (snapshot.get("totalDistanceMeters") as? Number)?.toDouble() ?: 0.0,
                totalDurationSeconds = (snapshot.get("totalDurationSeconds") as? Number)?.toDouble() ?: 0.0
            )
            if (geometry != null) {
                android.util.Log.d(
                    "RideRepository",
                    "Loaded persisted route geometry for $rideId: ${RouteGeometryCodec.describe(geometry)}"
                )
            }
            geometry
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "rides/$rideId/route")
            null
        }
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
                firestore?.collection("rides")?.document(ride.id)
                    ?.update(
                        mapOf(
                            "status" to RideStatus.ACTIVE.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/${ride.id}")
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

            // 1. Authoritative shared write FIRST: in-flight transactions and pending
            //    writes confirm the state via the ride listener; the leader's local UI
            //    transition below matches this outcome.
            var lastError: Exception? = null
            var attemptsUsed = 0
            val fs = firestore
            if (fs != null) {
                val rideRef = fs.collection("rides").document(ride.id)
                for (attempt in 1..FirestoreWriteRetryPolicy.MAX_ATTEMPTS) {
                    attemptsUsed = attempt
                    val backoff = FirestoreWriteRetryPolicy.delayBeforeAttemptMs(attempt)
                    if (backoff > 0) delay(backoff)
                    try {
                        fs.runTransaction { transaction ->
                            val snapshot = transaction.get(rideRef)
                            val currentStatus = snapshot.getString("status") ?: "LOBBY"
                            if (snapshot.exists() && currentStatus != RideStatus.COMPLETED.name) {
                                transaction.update(
                                    rideRef,
                                    mapOf(
                                        "status" to RideStatus.COMPLETED.name,
                                        "elapsedDurationSeconds" to actualElapsedSec,
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                )
                            }
                        }.await()
                        lastError = null
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        Log.w("RideRepository", "Shared ride completion attempt $attempt failed: ${e.message}")
                    }
                }
            } else {
                lastError = IllegalStateException("Firestore unavailable for ride completion")
            }

            // 2. The local device transition is applied once, regardless of the write
            //    outcome, so the leader's own session is never stuck in ACTIVE when the
            //    network fails (offline Firestore persistence replays queued writes).
            _currentRide.value = _currentRide.value?.copy(
                status = RideStatus.COMPLETED,
                elapsedDurationSeconds = actualElapsedSec
            )
            sharedCompletedRideIds.add(ride.id)
            rideStartedAtMs = 0L
            _activeSos.value = null
            locationTracker.stopTracking()

            if (lastError == null) {
                Log.d("RideRepository", "Shared ride COMPLETED written for ride ${ride.id} (attempts=$attemptsUsed, elapsed=${actualElapsedSec}s)")
                return Result.success(Unit)
            }

            // 3. Fallback: a plain update() queues into Firestore offline persistence, so
            //    completion still propagates once connectivity returns (transactions do
            //    not work offline; simple writes do). A transaction-only failure path
            //    would leave members staring at an ACTIVE ride forever.
            Log.w("RideRepository", "Transaction-based completion failed for ride ${ride.id}; falling back to queued update write")
            return if (fs == null) {
                Result.failure(lastError!!)
            } else {
                try {
                    fs.collection("rides").document(ride.id)
                        .update(
                            mapOf(
                                "status" to RideStatus.COMPLETED.name,
                                "elapsedDurationSeconds" to actualElapsedSec,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    Log.d("RideRepository", "Queued completion write accepted for ride ${ride.id} (offline persistence will flush)")
                    Result.success(Unit)
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.UPDATE, "rides/${ride.id}")
                    Result.failure(e)
                }
            }
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
            try {
                firestore?.collection("rides")?.document(activeRideId)
                    ?.collection("riders")?.document(currentUid)
                    ?.delete()
                    ?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, "rides/$activeRideId/riders/$currentUid")
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
                firestore?.collection("rides")?.document(activeRideId)
                    ?.update("leaderId", newLeaderId)
                    ?.await()

                firestore?.collection("rides")?.document(activeRideId)
                    ?.collection("riders")?.document(newLeaderId)
                    ?.update("role", "LEADER")
                    ?.await()

                firestore?.collection("rides")?.document(activeRideId)
                    ?.collection("riders")?.document(currentUid)
                    ?.update("role", "MEMBER")
                    ?.await()

                Log.d("RideRepository", "Transferred leadership of ride $activeRideId to $newLeaderId")
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/$activeRideId/leader")
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
        val doc = mapOf(
            "id" to msgId,
            "rideId" to activeRideId,
            "senderId" to currentUid,
            "senderName" to currentUserName,
            "type" to type.name,
            "text" to type.label,
            "timestampMs" to msg.timestampMs,
            "createdAt" to FieldValue.serverTimestamp()
        )

        // Local echo first (instant own-device feedback); the same stable id is later
        // reconciled against the server snapshot (dedup, no double display).
        publishQuickMessage(msg, fromLocalEcho = true)

        repositoryScope.launch {
            val fs = firestore
            if (fs == null) {
                Log.w("RideRepository", "Group message send failed: firestore unavailable (msgId=$msgId ride=$activeRideId)")
                _messageSendError.value = true
                return@launch
            }
            val msgRef = fs.collection("rides").document(activeRideId).collection("messages").document(msgId)
            var lastError: Exception? = null
            var attemptsUsed = 0
            for (attempt in 1..FirestoreWriteRetryPolicy.MAX_ATTEMPTS) {
                attemptsUsed = attempt
                val backoff = FirestoreWriteRetryPolicy.delayBeforeAttemptMs(attempt)
                if (backoff > 0) delay(backoff)
                try {
                    msgRef.set(doc).await()
                    lastError = null
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.w("RideRepository", "Group message send attempt $attempt/$msgId failed: ${e.message}")
                }
            }
            if (lastError == null) {
                Log.d("RideRepository", "Group message sent confirmed (msgId=$msgId ride=$activeRideId attempts=$attemptsUsed)")
            } else {
                // Unrecoverable send failure is surfaced, not silently swallowed.
                handleFirestoreError(lastError, OperationType.CREATE, "rides/$activeRideId/messages/$msgId")
                _messageSendError.value = true
            }
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

    private fun parseQuickMessageDoc(doc: com.google.firebase.firestore.DocumentSnapshot): QuickMessage? {
        val senderId = doc.getString("senderId") ?: return null
        val senderName = doc.getString("senderName") ?: "Rider"
        val typeStr = doc.getString("type") ?: "IM_OKAY"
        val type = runCatching { QuickMessageType.valueOf(typeStr) }.getOrDefault(QuickMessageType.IM_OKAY)
        val text = doc.getString("text") ?: type.label
        val ts = doc.getLong("timestampMs") ?: 0L
        // Stable doc id is the canonical message id (sender echo and receiver agree).
        return QuickMessage(
            id = doc.id,
            senderId = senderId,
            senderName = senderName,
            type = type,
            text = text,
            timestampMs = ts
        )
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
        val fs = firestore
        if (backend == null && fs == null) {
            Log.w("RideRepository", "Firestore is null, failing triggerSos explicitly")
            return Result.failure(IllegalStateException("Firestore unavailable for SOS trigger"))
        }

        _activeSos.value = event
        _currentRiderStatus.value = MemberStatus.EMERGENCY

        return try {
            val doc = mapOf(
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
                "createdAt" to FieldValue.serverTimestamp()
            )
            if (backend != null) {
                // Injected delivery backend (offline tests): the event is
                // handed to the backend and success is reported once it
                // completed.
                backend.deliver(activeRideId, sosId, doc)
            } else {
                fs!!.collection("rides").document(activeRideId)
                    .collection("sos").document(sosId)
                    .set(doc)
                    .await()
            }
            Result.success(event)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "rides/$activeRideId/sos/$sosId")
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

        repositoryScope.launch {
            val sosRef = firestore?.collection("rides")?.document(activeRideId)?.collection("sos")?.document(sosId)
            if (sosRef == null) {
                Log.w("RideRepository", "SOS acknowledge skipped: firestore unavailable")
                return@launch
            }
            var lastError: Exception? = null
            for (attempt in 1..SosRetryPolicy.MAX_ATTEMPTS) {
                val backoff = SosRetryPolicy.delayBeforeAttemptMs(attempt)
                if (backoff > 0) delay(backoff)
                try {
                    firestore.runTransaction { transaction ->
                        val snapshot = transaction.get(sosRef)
                        val isResolved = snapshot.getBoolean("isResolved") ?: false
                        if (!isResolved) {
                            transaction.update(sosRef, "acknowledgedBy", FieldValue.arrayUnion(currentUid))
                        }
                    }.await()
                    lastError = null
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Retry: without a confirmed server write the acknowledgment only
                    // exists on the acking device — the SOS sender never learns someone
                    // is coming ("ack only goes to the acking user").
                    lastError = e
                    Log.w("RideRepository", "SOS ack attempt $attempt failed: ${e.message}")
                }
            }
            if (lastError != null) {
                handleFirestoreError(lastError, OperationType.UPDATE, "rides/$activeRideId/sos/$sosId")
            }
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
        val fs = firestore
        if (backend == null && fs == null) {
            return Result.failure(IllegalStateException("Firestore unavailable"))
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
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/$activeRideId/sos/${targetEvent.id}")
                // The local resolution stays authoritative for the session (see the
                // comment on the Firestore path below).
                Result.failure(e)
            }
        }

        val sosRef = fs!!.collection("rides").document(activeRideId).collection("sos").document(targetEvent.id)
        var lastError: Exception? = null
        for (attempt in 1..SosRetryPolicy.MAX_ATTEMPTS) {
            val backoff = SosRetryPolicy.delayBeforeAttemptMs(attempt)
            if (backoff > 0) delay(backoff)
            try {
                fs.runTransaction { transaction ->
                    val snapshot = transaction.get(sosRef)
                    if (snapshot.exists() && snapshot.getBoolean("isResolved") != true) {
                        transaction.update(
                            sosRef,
                            mapOf(
                                "isResolved" to true,
                                "resolvedAtMs" to System.currentTimeMillis(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }.await()
                lastError = null
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Retry transient failures: an unresolved doc left on the server keeps the
                // alert ringing on every other member's device indefinitely.
                lastError = e
                Log.w("RideRepository", "SOS resolve attempt $attempt failed: ${e.message}")
            }
        }
        return if (lastError == null) {
            Result.success(Unit)
        } else {
            handleFirestoreError(lastError, OperationType.UPDATE, "rides/$activeRideId/sos/${targetEvent.id}")
            // Intentionally do NOT resurrect the alert here: once the rider leader/sender has
            // resolved locally, that state is authoritative for the session. Firestore will
            // propagate the resolve when connectivity returns; restoring a "previousSos"
            // snapshot after a transient offline write failure made the banner flash back on
            // the UI (and broke resolve idempotency in tests).
            Result.failure(lastError)
        }
    }

    fun resolveSos() {
        repositoryScope.launch {
            resolveSosInternal()
        }
    }

    fun cleanup() {
        detachListeners()
        repositoryJob.cancelChildren()
    }

    private fun detachListeners() {
        rideListener?.remove()
        rideListener = null
        ridersListener?.remove()
        ridersListener = null
        messagesListener?.remove()
        messagesListener = null
        sosListener?.remove()
        sosListener = null
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
