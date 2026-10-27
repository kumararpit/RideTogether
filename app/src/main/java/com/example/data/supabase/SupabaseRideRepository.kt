package com.example.data.supabase

import android.util.Log
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.MembershipStatus
import com.example.data.model.QuickMessage
import com.example.data.model.QuickMessageType
import com.example.data.model.Ride
import com.example.data.model.RideRole
import com.example.data.model.RideStatus
import com.example.data.model.RiderMember
import com.example.data.model.RouteOptionSummary
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopCodec
import com.example.data.model.SosEvent
import com.example.data.repository.RideRemoteDataSource
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Supabase-backed data repository for RideTogether.
 *
 * Writes to Supabase via REST API (okhttp). Firebase Auth is still used for
 * Google Sign-In authentication; Supabase is used only for the database layer.
 * RLS policies in [supabase-schema.sql] enforce authorization.
 *
 * Free tier: 500MB database, 2GB bandwidth. No billing required.
 */
class SupabaseRideRepository(
    private val supabaseClient: SupabaseClient,
    private val context: android.content.Context? = null
) : RideRemoteDataSource {
    private val repositoryJob = SupervisorJob()
    private val repositoryScope = CoroutineScope(Dispatchers.Default + repositoryJob)

    private val _currentRide = MutableStateFlow<Ride?>(null)
    val currentRide: StateFlow<Ride?> = _currentRide.asStateFlow()

    private val _members = MutableStateFlow<List<RiderMember>>(emptyList())
    val members: StateFlow<List<RiderMember>> = _members.asStateFlow()

    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError.asStateFlow()

    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter(Map::class.java)

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun log(tag: String, msg: String) = Log.d("SupabaseRepo", "[$tag] $msg")
    private fun logW(tag: String, msg: String) = Log.w("SupabaseRepo", "[$tag] $msg")

    private suspend fun <T> supabaseGet(
        table: String,
        query: String,
        parser: (String) -> T
    ): T {
        val path = if (query.isNotEmpty()) "$table?$query" else table
        return suspendCoroutine { cont ->
            repositoryScope.launch(Dispatchers.IO) {
                try {
                    val body = supabaseClient.request("GET", path)
                    cont.resume(parser(body))
                } catch (e: Exception) {
                    cont.resume(parser(""))
                }
            }
        }
    }

    private suspend fun supabaseMutate(
        method: String,
        table: String,
        body: Map<*, *>?,
        query: String = ""
    ): Boolean {
        val path = if (query.isNotEmpty()) "$table?$query" else table
        val jsonBody = body?.let { mapAdapter.toJson(it) }
        return suspendCoroutine { cont ->
            repositoryScope.launch(Dispatchers.IO) {
                try {
                    val response = supabaseClient.request(method, path, jsonBody)
                    cont.resume(response.isNotEmpty())
                } catch (e: Exception) {
                    cont.resume(false)
                }
            }
        }
    }

    private suspend fun supabaseUpsert(
        table: String,
        body: Map<*, *>?
    ): Boolean {
        return supabaseMutate("POST", table, body, query = "upsert=true")
    }

    // ========================================================================
    // User profile (replaces users/{userId})
    // ========================================================================

    suspend fun initCurrentUser(id: String, name: String, motorcycleModel: String = "") {
        repositoryScope.launch {
            try {
                val userDoc = buildMap<String, Any> {
                    put("id", id)
                    put("name", name)
                    put("motorcycle_model", motorcycleModel)
                    put("email", "")
                    put("photo_url", "")
                    put("updated_at", java.time.Instant.now().toString())
                }
                val ok = supabaseUpsert("profiles", userDoc)
                if (ok) log("initCurrentUser", "Profile upserted for $id")
                else logW("initCurrentUser", "Failed to upsert profile")
            } catch (e: Exception) {
                logW("initCurrentUser", "Exception: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Ride CRUD
    // ========================================================================

    /**
     * Fetch a single ride by ID.
     */
    override suspend fun fetchRideById(rideId: String): Ride? {
        return supabaseGet("rides", "id=eq.$rideId") { body ->
            if (body.isBlank()) null else parseRideList(body).firstOrNull()
        }
    }

    /**
     * Find an active ride by leader ID.
     */
    suspend fun fetchActiveRideForUser(uid: String): Ride? {
        return supabaseGet("rides", "leader_id=eq.$uid&status=in.(CREATED,LOBBY,ACTIVE)") { body ->
            if (body.isBlank()) null else parseRideList(body).firstOrNull()
        }
    }

    /**
     * Find a ride by invite code.
     */
    override suspend fun findRideByInviteCode(code: String): Ride? {
        return supabaseGet("rides", "invite_code=eq.${code.trim().uppercase()}") { body ->
            if (body.isBlank()) null else parseRideList(body).firstOrNull()
        }
    }

    /**
     * Create a new ride + leader rider record.
     */
    suspend fun createRide(
        rideId: String,
        name: String,
        startLocationName: String,
        destLocationName: String,
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        leaderId: String,
        inviteCode: String,
        initialPlannedDistanceMeters: Double,
        routeStops: List<RouteStop>,
        routePlanVersion: Long,
        selectedRouteIndex: Int,
        routeOptions: List<RouteOptionSummary>
    ): Boolean {
        return try {
            val rideDoc = buildMap<String, Any> {
                put("id", rideId)
                put("name", name)
                put("start_location_name", startLocationName)
                put("destination_name", destLocationName)
                put("start_lat", startLat)
                put("start_lng", startLng)
                put("dest_lat", destLat)
                put("dest_lng", destLng)
                put("leader_id", leaderId)
                put("invite_code", inviteCode)
                put("status", RideStatus.LOBBY.name)
                put("total_distance_meters", initialPlannedDistanceMeters)
                put("total_duration_seconds", 0.0)
                put("initial_planned_distance_meters", initialPlannedDistanceMeters)
                put("route_stops", RouteStopCodec.toFirestore(routeStops))
                put("route_plan_version", routePlanVersion)
                put("selected_route_index", selectedRouteIndex)
                put("route_options", com.example.data.model.RoutePlanCodec.optionsToFirestore(routeOptions))
                put("updated_at", java.time.Instant.now().toString())
            }
            val rideOk = supabaseMutate("POST", "rides", rideDoc)

            val nowMs = System.currentTimeMillis()
            val riderDoc = buildMap<String, Any> {
                put("id", leaderId)
                put("user_id", leaderId)
                put("ride_id", rideId)
                put("name", "Rider")
                put("motorcycle_model", "")
                put("role", "LEADER")
                put("membership_status", MembershipStatus.ACTIVE.name)
                put("status", MemberStatus.RIDING.name)
                put("latitude", startLat)
                put("longitude", startLng)
                put("speed_kmh", 0.0)
                put("heading_deg", 0.0)
                put("battery_pct", 95)
                put("stopped_duration_sec", 0L)
                put("last_updated_ms", nowMs)
                put("updated_at", java.time.Instant.now().toString())
            }
            val riderOk = supabaseMutate("POST", "riders", riderDoc)
            log("createRide", "Ride $rideId created (ride=$rideOk, rider=$riderOk)")
            rideOk && riderOk
        } catch (e: Exception) {
            logW("createRide", "Failed: ${e.message}")
            false
        }
    }

    /**
     * Upsert a rider's telemetry (location, status, etc.).
     */
    suspend fun upsertRider(
        rideId: String,
        userId: String,
        name: String,
        motorcycleModel: String,
        role: String,
        status: String,
        lat: Double,
        lng: Double,
        speedKmh: Double,
        headingDeg: Float?,
        accuracyMeters: Float,
        sequenceNumber: Long,
        batteryPct: Int,
        stoppedDurationSec: Long
    ): Boolean {
        return try {
            val nowMs = System.currentTimeMillis()
            val riderDoc = buildMap<String, Any> {
                put("id", userId)
                put("user_id", userId)
                put("ride_id", rideId)
                put("name", name)
                put("motorcycle_model", motorcycleModel)
                put("role", role)
                put("membership_status", MembershipStatus.ACTIVE.name)
                put("status", status)
                put("latitude", lat)
                put("longitude", lng)
                put("speed_kmh", speedKmh)
                put("heading_deg", headingDeg?.toDouble() ?: 0.0)
                put("battery_pct", batteryPct)
                put("stopped_duration_sec", stoppedDurationSec)
                put("last_updated_ms", nowMs)
                put("updated_at", java.time.Instant.now().toString())
            }
            supabaseUpsert("riders", riderDoc)
            true
        } catch (e: Exception) {
            logW("upsertRider", "Failed: ${e.message}")
            false
        }
    }

    /**
     * Update ride metadata fields (distance, duration, route plan, etc.).
     */
    override suspend fun updateRide(rideId: String, updates: Map<String, Any>): Boolean {
        return try {
            val allUpdates = updates + ("updated_at" to java.time.Instant.now().toString())
            supabaseMutate("PATCH", "rides", allUpdates, query = "id=eq.$rideId")
        } catch (e: Exception) {
            logW("updateRide", "Failed: ${e.message}")
            false
        }
    }

    /**
     * Update ride status (e.g., ACTIVE → COMPLETED).
     */
    override suspend fun updateRideStatus(rideId: String, status: RideStatus): Boolean {
        return try {
            val updates = buildMap<String, Any> {
                put("status", status.name)
                put("updated_at", java.time.Instant.now().toString())
            }
            supabaseMutate("PATCH", "rides", updates, query = "id=eq.$rideId")
        } catch (e: Exception) {
            logW("updateRideStatus", "Failed: ${e.message}")
            false
        }
    }

    /**
     * Persist route geometry for a ride.
     */
    override suspend fun saveRouteGeometry(
        rideId: String,
        points: List<Map<String, Any>>,
        steps: List<Map<String, Any>>,
        totalDistanceMeters: Double,
        totalDurationSeconds: Double
    ): Boolean {
        return try {
            val doc = buildMap<String, Any> {
                put("ride_id", rideId)
                put("points", points)
                put("steps", steps)
                put("total_distance_meters", totalDistanceMeters)
                put("total_duration_seconds", totalDurationSeconds)
                put("updated_at", java.time.Instant.now().toString())
            }
            supabaseUpsert("route_geometry", doc)
        } catch (e: Exception) {
            logW("saveRouteGeometry", "Failed: ${e.message}")
            false
        }
    }

    /**
     * Fetch route geometry for a ride.
     */
    override suspend fun fetchRouteGeometry(rideId: String): Map<String, Any>? {
        return supabaseGet("route_geometry", "ride_id=eq.$rideId") { body ->
            if (body.isBlank()) null else {
                val list = moshi.adapter(List::class.java).fromJson(body) as? List<Map<String, Any>>
                list?.firstOrNull()
            }
        }
    }

    // ========================================================================
    // Quick messages
    // ========================================================================

    suspend fun sendQuickMessage(
        rideId: String,
        msgId: String,
        senderId: String,
        senderName: String,
        type: QuickMessageType,
        text: String,
        timestampMs: Long
    ): Boolean {
        return try {
            val doc = buildMap<String, Any> {
                put("id", msgId)
                put("ride_id", rideId)
                put("sender_id", senderId)
                put("sender_name", senderName)
                put("type", type.name)
                put("text", text)
                put("timestamp_ms", timestampMs)
            }
            supabaseMutate("POST", "messages", doc)
            log("sendQuickMessage", "Message $msgId sent")
            true
        } catch (e: Exception) {
            logW("sendQuickMessage", "Failed: ${e.message}")
            false
        }
    }

    // ========================================================================
    // SOS events
    // ========================================================================

    suspend fun createSos(
        rideId: String,
        sosId: String,
        riderId: String,
        riderName: String,
        lat: Double,
        lng: Double,
        accuracyMeters: Float,
        locationSource: String,
        timestampMs: Long
    ): Boolean {
        return try {
            val doc = buildMap<String, Any> {
                put("id", sosId)
                put("ride_id", rideId)
                put("rider_id", riderId)
                put("rider_name", riderName)
                put("latitude", lat)
                put("longitude", lng)
                put("is_resolved", false)
                put("acknowledged_by", emptyList<String>())
                put("timestamp_ms", timestampMs)
                put("accuracy_meters", accuracyMeters.toDouble())
                put("location_source", locationSource)
            }
            supabaseMutate("POST", "sos_events", doc)
            log("createSos", "SOS $sosId created")
            true
        } catch (e: Exception) {
            logW("createSos", "Failed: ${e.message}")
            false
        }
    }

    suspend fun resolveSos(sosId: String, isResolved: Boolean = true): Boolean {
        return try {
            val updates = buildMap<String, Any> {
                put("is_resolved", isResolved)
            }
            supabaseMutate("PATCH", "sos_events", updates, query = "id=eq.$sosId")
        } catch (e: Exception) {
            logW("resolveSos", "Failed: ${e.message}")
            false
        }
    }

    // ========================================================================
    // RideRemoteDataSource interface implementation — adapters and stubs
    // ========================================================================

    /** Adapter: find an active ride by leader ID (delegates to fetchActiveRideForUser). */
    override suspend fun fetchActiveRideForLeader(leaderId: String): Ride? =
        fetchActiveRideForUser(leaderId)

    /** Adapter: upsert a profile (delegates to initCurrentUser). */
    override suspend fun upsertProfile(
        id: String,
        name: String,
        email: String?,
        photoUrl: String?,
        motorcycleModel: String
    ): Boolean {
        initCurrentUser(id, name, motorcycleModel)
        return true
    }

    /** Stub: leadership transfer handled by trusted backend (Phase 4). */
    override suspend fun transferLeadership(rideId: String, newLeaderId: String): Boolean = false

    /** Adapter: upsert rider telemetry from a RiderMember domain object. */
    override suspend fun upsertRider(rideId: String, rider: RiderMember): Boolean =
        upsertRider(
            rideId = rideId,
            userId = rider.id,
            name = rider.name,
            motorcycleModel = rider.motorcycleModel,
            role = rider.role.name,
            status = rider.status.name,
            lat = rider.location.latitude,
            lng = rider.location.longitude,
            speedKmh = rider.speedKmh,
            headingDeg = rider.headingDeg,
            accuracyMeters = rider.accuracyMeters,
            sequenceNumber = rider.sequenceNumber,
            batteryPct = rider.batteryPct,
            stoppedDurationSec = rider.stoppedDurationSec
        )

    /** Stub: membership changes handled by trusted backend (Phase 4). */
    override suspend fun setRiderMembership(
        rideId: String,
        userId: String,
        status: MembershipStatus
    ): Boolean = false

    /** Adapter: send a quick message from a QuickMessage domain object. */
    override suspend fun sendMessage(rideId: String, msg: QuickMessage): Boolean =
        sendQuickMessage(
            rideId = rideId,
            msgId = msg.id,
            senderId = msg.senderId,
            senderName = msg.senderName,
            type = msg.type,
            text = msg.text,
            timestampMs = msg.timestampMs
        )

    /** Adapter: create an SOS from a SosEvent domain object. */
    override suspend fun createSos(rideId: String, sos: SosEvent): Boolean =
        createSos(
            rideId = rideId,
            sosId = sos.id,
            riderId = sos.riderId,
            riderName = sos.riderName,
            lat = sos.location.latitude,
            lng = sos.location.longitude,
            accuracyMeters = sos.accuracyMeters,
            locationSource = sos.locationSource,
            timestampMs = sos.timestampMs
        )

    /** Stub: SOS acknowledgment handled by trusted backend (Phase 4). */
    override suspend fun acknowledgeSos(sosId: String, ackingUserId: String): Boolean = false

    /** Adapter: resolve SOS (delegates to resolveSos(sosId, true)). */
    override suspend fun resolveSos(sosId: String): Boolean = resolveSos(sosId, true)

    // ========================================================================
    // Realtime subscriptions — Supabase Realtime (WebSocket) (Phase 3)
    // ========================================================================

    override fun subscribeToRide(rideId: String, onChange: (Ride) -> Unit): () -> Unit {
        return supabaseClient.realtime.subscribe("rides", "id=eq.$rideId") { record ->
            val ride = parseRide(record)
            if (ride != null) onChange(ride)
        }
    }

    override fun subscribeToRiders(rideId: String, onChange: (List<RiderMember>) -> Unit): () -> Unit {
        // Local rider cache keyed by rider id, so individual postgres_changes events
        // can be accumulated into a full member list snapshot.
        val riderCache = ConcurrentHashMap<String, RiderMember>()
        val lock = Any()

        return supabaseClient.realtime.subscribe("riders", "ride_id=eq.$rideId") { record ->
            val rider = parseRider(record)
            if (rider == null) return@subscribe

            synchronized(lock) {
                riderCache[rider.id] = rider
                // Emit a snapshot copy so callers see a consistent list.
                onChange(riderCache.values.toList())
            }
        }
    }

    override fun subscribeToMessages(rideId: String, onChange: (QuickMessage) -> Unit): () -> Unit {
        return supabaseClient.realtime.subscribe("messages", "ride_id=eq.$rideId") { record ->
            val msg = parseQuickMessage(record)
            if (msg != null) onChange(msg)
        }
    }

    override fun subscribeToSos(rideId: String, onChange: (SosEvent) -> Unit): () -> Unit {
        return supabaseClient.realtime.subscribe("sos_events", "ride_id=eq.$rideId") { record ->
            val sos = parseSosEvent(record)
            if (sos != null) onChange(sos)
        }
    }

    // ========================================================================
    // RideRemoteDataSource: createRide adapter
    // ========================================================================

    /** Adapter: create ride + leader rider from domain objects (delegates to the flat-param createRide). */
    override suspend fun createRide(ride: Ride, leaderRider: RiderMember): Boolean =
        createRide(
            rideId = ride.id,
            name = ride.name,
            startLocationName = ride.startLocationName,
            destLocationName = ride.destinationName,
            startLat = ride.startLocation.latitude,
            startLng = ride.startLocation.longitude,
            destLat = ride.destinationLocation.latitude,
            destLng = ride.destinationLocation.longitude,
            leaderId = leaderRider.id,
            inviteCode = ride.inviteCode,
            initialPlannedDistanceMeters = ride.initialPlannedDistanceMeters,
            routeStops = ride.routeStops,
            routePlanVersion = ride.routePlanVersion,
            selectedRouteIndex = ride.selectedRouteIndex,
            routeOptions = ride.routeOptions
        )

    // ========================================================================
    // Leave ride
    // ========================================================================

    suspend fun leaveRide(rideId: String, userId: String): Boolean {
        return try {
            val updates = buildMap<String, Any> {
                put("membership_status", MembershipStatus.LEFT.name)
            }
            supabaseMutate("PATCH", "riders", updates, query = "id=eq.$userId&ride_id=eq.$rideId")
        } catch (e: Exception) {
            logW("leaveRide", "Failed: ${e.message}")
            false
        }
    }

    // ========================================================================
    // Parsing helpers (Supabase JSON → domain model)
    // ========================================================================

    private fun parseRideList(json: String): List<Ride> {
        val list = moshi.adapter(List::class.java).fromJson(json) as? List<Map<String, Any>>
            ?: return emptyList()
        return list.mapNotNull { parseRide(it) }
    }

    private fun parseRide(data: Map<String, Any>?): Ride? {
        if (data == null) return null
        return try {
            Ride(
                id = data["id"] as? String ?: "",
                name = data["name"] as? String ?: "Group Ride",
                startLocationName = data["start_location_name"] as? String ?: "Start",
                destinationName = data["destination_name"] as? String ?: "Destination",
                startLocation = LatLng(
                    (data["start_lat"] as? Number)?.toDouble() ?: 18.5204,
                    (data["start_lng"] as? Number)?.toDouble() ?: 73.8567
                ),
                destinationLocation = LatLng(
                    (data["dest_lat"] as? Number)?.toDouble() ?: 18.7546,
                    (data["dest_lng"] as? Number)?.toDouble() ?: 73.4062
                ),
                leaderId = data["leader_id"] as? String ?: "",
                inviteCode = data["invite_code"] as? String ?: "",
                status = runCatching { RideStatus.valueOf(data["status"] as? String ?: "LOBBY") }
                    .getOrDefault(RideStatus.LOBBY),
                initialPlannedDistanceMeters = (data["initial_planned_distance_meters"] as? Number)?.toDouble() ?: 0.0,
                totalDistanceMeters = (data["total_distance_meters"] as? Number)?.toDouble() ?: 0.0,
                totalDurationSeconds = (data["total_duration_seconds"] as? Number)?.toDouble() ?: 0.0,
                actualRecordedDistanceMeters = (data["actual_recorded_distance_meters"] as? Number)?.toDouble() ?: 0.0,
                elapsedDurationSeconds = (data["elapsed_duration_seconds"] as? Number)?.toDouble() ?: 0.0,
                startedAtMs = (data["started_at_ms"] as? Number)?.toLong() ?: 0L,
                routeStops = RouteStopCodec.fromFirestore(data["route_stops"] as? List<*>),
                routePlanVersion = (data["route_plan_version"] as? Number)?.toLong() ?: 0L,
                selectedRouteIndex = (data["selected_route_index"] as? Number)?.toInt() ?: 0,
                routeOptions = com.example.data.model.RoutePlanCodec.optionsFromFirestore(data["route_options"] as? List<*>)
            )
        } catch (e: Exception) {
            logW("parseRide", "Failed: ${e.message}")
            null
        }
    }

    /**
     * Parse a Supabase riders table record into a RiderMember domain object.
     * Mirrors RideRepository.parseRiderDoc for Firestore snapshots.
     */
    private fun parseRider(data: Map<String, Any>?): RiderMember? {
        if (data == null) return null
        return try {
            val id = data["user_id"] as? String ?: data["id"] as? String ?: ""
            val name = data["name"] as? String ?: "Rider"
            val motorcycle = data["motorcycle_model"] as? String ?: ""
            val roleStr = data["role"] as? String ?: "MEMBER"
            val role = if (roleStr.equals("LEADER", ignoreCase = true)) RideRole.LEADER else RideRole.MEMBER
            val statusStr = data["status"] as? String ?: "RIDING"
            val status = runCatching { MemberStatus.valueOf(statusStr) }.getOrDefault(MemberStatus.RIDING)
            val lat = (data["latitude"] as? Number)?.toDouble() ?: 0.0
            val lng = (data["longitude"] as? Number)?.toDouble() ?: 0.0
            val speedKmh = (data["speed_kmh"] as? Number)?.toDouble() ?: 0.0
            val headingDeg = (data["heading_deg"] as? Number)?.toFloat()
            val accuracyMeters = (data["accuracy_meters"] as? Number)?.toFloat() ?: 10f
            val lastUpdatedMs = (data["last_updated_ms"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val stoppedSec = (data["stopped_duration_sec"] as? Number)?.toLong() ?: 0L
            val batteryPct = (data["battery_pct"] as? Number)?.toInt() ?: 85
            val membershipStr = data["membership_status"] as? String ?: "ACTIVE"
            val membershipStatus = runCatching { MembershipStatus.valueOf(membershipStr) }
                .getOrDefault(MembershipStatus.ACTIVE)

            RiderMember(
                id = id,
                name = name,
                motorcycleModel = motorcycle,
                role = role,
                status = status,
                membershipStatus = membershipStatus,
                location = LatLng(lat, lng),
                speedKmh = speedKmh,
                headingDeg = headingDeg,
                accuracyMeters = accuracyMeters,
                lastUpdatedMs = lastUpdatedMs,
                stoppedDurationSec = stoppedSec,
                batteryPct = batteryPct
            )
        } catch (e: Exception) {
            logW("parseRider", "Failed: ${e.message}")
            null
        }
    }

    /**
     * Parse a Supabase messages table record into a QuickMessage domain object.
     */
    private fun parseQuickMessage(data: Map<String, Any>?): QuickMessage? {
        if (data == null) return null
        return try {
            val senderId = data["sender_id"] as? String ?: return null
            val senderName = data["sender_name"] as? String ?: "Rider"
            val typeStr = data["type"] as? String ?: "IM_OKAY"
            val type = runCatching { QuickMessageType.valueOf(typeStr) }.getOrDefault(QuickMessageType.IM_OKAY)
            val text = data["text"] as? String ?: type.label
            val ts = (data["timestamp_ms"] as? Number)?.toLong() ?: System.currentTimeMillis()
            QuickMessage(
                id = data["id"] as? String ?: UUID.randomUUID().toString(),
                senderId = senderId,
                senderName = senderName,
                type = type,
                text = text,
                timestampMs = ts
            )
        } catch (e: Exception) {
            logW("parseQuickMessage", "Failed: ${e.message}")
            null
        }
    }

    /**
     * Parse a Supabase sos_events table record into a SosEvent domain object.
     */
    private fun parseSosEvent(data: Map<String, Any>?): SosEvent? {
        if (data == null) return null
        return try {
            val riderId = data["rider_id"] as? String ?: return null
            val riderName = data["rider_name"] as? String ?: "Rider"
            val lat = (data["latitude"] as? Number)?.toDouble() ?: 0.0
            val lng = (data["longitude"] as? Number)?.toDouble() ?: 0.0
            val ts = (data["timestamp_ms"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val resolved = (data["is_resolved"] as? Boolean) ?: false
            val acc = (data["accuracy_meters"] as? Number)?.toFloat() ?: 10f
            val source = data["location_source"] as? String ?: "FRESH_LOCATION_FIX"
            val acks = (data["acknowledged_by"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            SosEvent(
                id = data["id"] as? String ?: UUID.randomUUID().toString(),
                riderId = riderId,
                riderName = riderName,
                location = LatLng(lat, lng),
                timestampMs = ts,
                isResolved = resolved,
                acknowledgedBy = acks,
                accuracyMeters = acc,
                locationSource = source
            )
        } catch (e: Exception) {
            logW("parseSosEvent", "Failed: ${e.message}")
            null
        }
    }

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = SecureRandom()
        return (1..6).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    fun cleanup() {
        repositoryJob.cancelChildren()
    }
}
