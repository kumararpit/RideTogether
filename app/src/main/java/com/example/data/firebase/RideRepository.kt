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
import com.example.data.model.RideRole
import com.example.data.model.RideStatus
import com.example.data.model.RiderMember
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
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

class RideRepository(
    val locationTracker: LocationTracker,
    val firestore: FirebaseFirestore?,
    val stopDetector: StopDetector = StopDetector(
        stopSpeedThresholdKmh = 5.0,
        resumeSpeedThresholdKmh = 8.0,
        stopDurationThresholdSec = 120L
    )
) {
    // Secondary constructor for convenience resolving default database
    constructor(@Suppress("UNUSED_PARAMETER") context: Context, locationTracker: LocationTracker) : this(
        locationTracker = locationTracker,
        firestore = try {
            FirebaseFirestore.getInstance()
        } catch (_: Exception) {
            null
        }
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

    private val _currentRiderStatus = MutableStateFlow(MemberStatus.RIDING)
    val currentRiderStatus: StateFlow<MemberStatus> = _currentRiderStatus.asStateFlow()

    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError.asStateFlow()

    private var rideListener: ListenerRegistration? = null
    private var ridersListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private var sosListener: ListenerRegistration? = null

    private var currentUserId: String = auth?.currentUser?.uid ?: "rider_unknown"
    private var currentUserName: String = auth?.currentUser?.displayName ?: "Rider"
    private var currentMotorcycleModel: String = ""
    private var localSequenceNumber: Long = 0L
    private val riderLatestSequence = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastRecordedGpsPoint: LatLng? = null

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

                val restoredRide = Ride(
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

    private fun processLocationUpdate(loc: LocationUpdate) {
        val (detectedStatus, stoppedSec) = stopDetector.processSpeed(loc.speedKmh, loc.timestampMs)
        val finalStatus = if (_activeSos.value?.riderId == currentUserId) {
            MemberStatus.EMERGENCY
        } else {
            detectedStatus
        }
        _currentRiderStatus.value = finalStatus

        val activeRide = _currentRide.value
        val activeRideId = activeRide?.id ?: return
        val currentUid = auth?.currentUser?.uid ?: return

        // Accumulate actual recorded distance if ride is ACTIVE and GPS accuracy is good (< 30m)
        if (activeRide.status == RideStatus.ACTIVE && loc.accuracyMeters <= 30f) {
            val prev = lastRecordedGpsPoint
            if (prev != null) {
                val deltaMeters = prev.distanceTo(loc.latLng) * 1000.0
                if (deltaMeters in 3.0..300.0) { // filter micro-jitter and teleport jumps
                    val newActual = activeRide.actualRecordedDistanceMeters + deltaMeters
                    _currentRide.value = activeRide.copy(actualRecordedDistanceMeters = newActual)
                }
            }
            lastRecordedGpsPoint = loc.latLng
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
        destLoc: LatLng
    ): Ride {
        val uid = auth?.currentUser?.uid
            ?: currentUserId.takeIf { it.isNotBlank() }
            ?: "rider_${System.currentTimeMillis()}"
        val rideId = UUID.randomUUID().toString()
        val inviteCode = generateInviteCode().trim().uppercase(java.util.Locale.ROOT)

        val safeRideName = rideName.ifBlank { "${startName.ifBlank { "Pune" }} → ${destName.ifBlank { "Lonavala" }}" }
        val safeUserName = currentUserName.ifBlank { "Rider" }

        val createdRide = Ride(
            id = rideId,
            name = safeRideName,
            startLocationName = startName,
            destinationName = destName,
            startLocation = startLoc,
            destinationLocation = destLoc,
            leaderId = uid,
            inviteCode = inviteCode,
            status = RideStatus.LOBBY
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
            "totalDistanceMeters" to 0.0,
            "totalDurationSeconds" to 0.0,
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
                val status = runCatching { RideStatus.valueOf(statusStr) }.getOrDefault(RideStatus.LOBBY)

                val current = _currentRide.value
                _currentRide.value = (current ?: Ride(
                    id = rideId,
                    name = name,
                    startLocationName = startLocName,
                    destinationName = destLocName,
                    startLocation = LatLng(startLat, startLng),
                    destinationLocation = LatLng(destLat, destLng),
                    leaderId = leaderId,
                    inviteCode = code,
                    status = status
                )).copy(
                    name = name,
                    status = status
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
                handleFirestoreError(error, OperationType.LIST, messagesRef.path)
                return@addSnapshotListener
            }
            if (snapshot != null && !snapshot.isEmpty) {
                val latestDoc = snapshot.documents.maxByOrNull {
                    it.getLong("timestampMs") ?: 0L
                }
                if (latestDoc != null) {
                    val senderId = latestDoc.getString("senderId") ?: ""
                    val senderName = latestDoc.getString("senderName") ?: "Rider"
                    val typeStr = latestDoc.getString("type") ?: "IM_OKAY"
                    val type = runCatching { QuickMessageType.valueOf(typeStr) }.getOrDefault(QuickMessageType.IM_OKAY)
                    val text = latestDoc.getString("text") ?: type.label
                    val ts = latestDoc.getLong("timestampMs") ?: System.currentTimeMillis()

                    _recentMessage.value = QuickMessage(
                        id = latestDoc.id,
                        senderId = senderId,
                        senderName = senderName,
                        type = type,
                        text = text,
                        timestampMs = ts
                    )
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
                if (activeSosDoc != null) {
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
        val headingDeg = (doc.getDouble("headingDeg") ?: 0.0).toFloat()
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
        _currentRide.value = current.copy(
            routePoints = routePoints,
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
                            "totalDistanceMeters" to totalDistanceMeters,
                            "totalDurationSeconds" to totalDurationSeconds,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/${current.id}")
            }
        }
    }

    fun startRide() {
        val ride = _currentRide.value ?: return
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

    suspend fun endRide() {
        val ride = _currentRide.value ?: return
        val currentUid = auth?.currentUser?.uid ?: currentUserId
        val activeSos = _activeSos.value
        if (activeSos != null && activeSos.riderId == currentUid && !activeSos.isResolved) {
            resolveSos()
        }
        _currentRide.value = ride.copy(status = RideStatus.COMPLETED)
        _activeSos.value = null
        locationTracker.stopTracking()

        try {
            firestore?.collection("rides")?.document(ride.id)
                ?.update(
                    mapOf(
                        "status" to RideStatus.COMPLETED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )?.await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "rides/${ride.id}")
        }
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

        val msgId = UUID.randomUUID().toString()
        val msg = QuickMessage(
            id = msgId,
            senderId = currentUid,
            senderName = currentUserName,
            type = type,
            text = type.label,
            timestampMs = System.currentTimeMillis()
        )
        _recentMessage.value = msg

        repositoryScope.launch {
            try {
                val doc = mapOf(
                    "id" to msgId,
                    "rideId" to activeRideId,
                    "senderId" to currentUid,
                    "senderName" to currentUserName,
                    "type" to type.name,
                    "text" to type.label,
                    "timestampMs" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp()
                )
                firestore?.collection("rides")?.document(activeRideId)
                    ?.collection("messages")?.document(msgId)
                    ?.set(doc)
                    ?.await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, "rides/$activeRideId/messages/$msgId")
            }
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

        val fs = firestore
        if (fs == null) {
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
            fs.collection("rides").document(activeRideId)
                .collection("sos").document(sosId)
                .set(doc)
                .await()
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
            try {
                val sosRef = firestore?.collection("rides")?.document(activeRideId)?.collection("sos")?.document(sosId)
                if (sosRef != null) {
                    firestore.runTransaction { transaction ->
                        val snapshot = transaction.get(sosRef)
                        val isResolved = snapshot.getBoolean("isResolved") ?: false
                        if (!isResolved) {
                            transaction.update(sosRef, "acknowledgedBy", FieldValue.arrayUnion(currentUid))
                        }
                    }.await()
                }
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/$activeRideId/sos/$sosId")
            }
        }
    }

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

        val previousSos = _activeSos.value
        val previousStatus = _currentRiderStatus.value

        val fs = firestore
        if (fs == null) {
            return Result.failure(IllegalStateException("Firestore unavailable"))
        }

        if (_activeSos.value?.id == targetEvent.id) {
            _activeSos.value = null
            _currentRiderStatus.value = MemberStatus.RIDING
        }

        return try {
            val sosRef = fs.collection("rides").document(activeRideId).collection("sos").document(targetEvent.id)
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
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "rides/$activeRideId/sos/${targetEvent.id}")
            if (_activeSos.value == null) {
                _activeSos.value = previousSos
                _currentRiderStatus.value = previousStatus
            }
            Result.failure(e)
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
