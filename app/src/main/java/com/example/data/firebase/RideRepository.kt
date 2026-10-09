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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

class RideRepository(
    val locationTracker: LocationTracker,
    val firestore: FirebaseFirestore,
    val stopDetector: StopDetector = StopDetector(
        stopSpeedThresholdKmh = 5.0,
        resumeSpeedThresholdKmh = 8.0,
        stopDurationThresholdSec = 120L
    )
) {
    // Secondary constructor for convenience resolving custom database id
    constructor(context: Context, locationTracker: LocationTracker) : this(
        locationTracker = locationTracker,
        firestore = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = FirebaseAuth.getInstance()
    private val repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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

    private var currentUserId: String = auth.currentUser?.uid ?: "rider_unknown"
    private var currentUserName: String = auth.currentUser?.displayName ?: "Rider"
    private var currentMotorcycleModel: String = ""

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
            while (true) {
                kotlinx.coroutines.delay(5000L)
                checkRidersStaleness()
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
                    "email" to (auth.currentUser?.email ?: ""),
                    "photoUrl" to (auth.currentUser?.photoUrl?.toString() ?: ""),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("users").document(id)
                    .set(userDoc, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, "users/$id")
            }
        }
    }

    fun setMotorcycleModel(model: String) {
        currentMotorcycleModel = model
        initCurrentUser(currentUserId, currentUserName, model)
    }

    private fun checkRidersStaleness() {
        val now = System.currentTimeMillis()
        val currentList = _members.value
        if (currentList.isEmpty()) return

        var changed = false
        val updated = currentList.map { rider ->
            if (rider.isCurrentUser) {
                rider
            } else {
                val diffMs = now - rider.lastUpdatedMs
                val newConn = when {
                    diffMs > 60000L -> com.example.data.model.ConnectionStatus.DISCONNECTED
                    diffMs > 25000L -> com.example.data.model.ConnectionStatus.LOCATION_STALE
                    else -> com.example.data.model.ConnectionStatus.CONNECTED
                }
                if (newConn != rider.connectionStatus) {
                    changed = true
                    rider.copy(connectionStatus = newConn)
                } else {
                    rider
                }
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

        val activeRideId = _currentRide.value?.id ?: return
        val currentUid = auth.currentUser?.uid ?: return

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
                    "batteryPct" to 90,
                    "stoppedDurationSec" to stoppedSec,
                    "lastUpdatedMs" to loc.timestampMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("rides").document(activeRideId)
                    .collection("riders").document(currentUid)
                    .set(riderMap, SetOptions.merge())
                    .await()
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
        val uid = auth.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: error("User must be signed in to create a ride")
        val rideId = UUID.randomUUID().toString()
        val inviteCode = generateInviteCode()

        val rideDoc = mapOf(
            "id" to rideId,
            "name" to rideName,
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

        try {
            firestore.collection("rides").document(rideId).set(rideDoc).await()

            // Add leader as first rider in subcollection
            val leaderRiderDoc = mapOf(
                "id" to uid,
                "userId" to uid,
                "rideId" to rideId,
                "name" to currentUserName,
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
            firestore.collection("rides").document(rideId)
                .collection("riders").document(uid).set(leaderRiderDoc).await()

            val createdRide = Ride(
                id = rideId,
                name = rideName,
                startLocationName = startName,
                destinationName = destName,
                startLocation = startLoc,
                destinationLocation = destLoc,
                leaderId = uid,
                inviteCode = inviteCode,
                status = RideStatus.LOBBY
            )
            _currentRide.value = createdRide

            // Immediately populate leader in local members state
            val leaderMember = RiderMember(
                id = uid,
                name = currentUserName,
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
            _members.value = listOf(leaderMember)

            // Attach real-time Firestore listeners for this ride and riders
            listenToRide(rideId)
            return createdRide
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "rides/$rideId")
            // If offline/local test or transient network failure, still provide local ride state
            val fallbackRide = Ride(
                id = rideId,
                name = rideName,
                startLocationName = startName,
                destinationName = destName,
                startLocation = startLoc,
                destinationLocation = destLoc,
                leaderId = uid,
                inviteCode = inviteCode,
                status = RideStatus.LOBBY
            )
            _currentRide.value = fallbackRide
            if (_members.value.isEmpty()) {
                _members.value = listOf(
                    RiderMember(
                        id = uid,
                        name = currentUserName,
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
                )
            }
            return fallbackRide
        }
    }

    suspend fun joinRide(inviteCode: String, userLoc: LatLng): Result<Ride> {
        val uid = auth.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return Result.failure(IllegalStateException("Please sign in first"))
        val code = inviteCode.trim().uppercase()

        return try {
            val querySnapshot = firestore.collection("rides")
                .whereEqualTo("inviteCode", code)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                _joinError.value = "No ride found with code: $code"
                return Result.failure(IllegalArgumentException("No ride found with code $code"))
            }

            val rideDoc = querySnapshot.documents.first()
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
            firestore.collection("rides").document(rideId)
                .collection("riders").document(uid).set(memberDoc, SetOptions.merge()).await()

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
        val currentUid = auth.currentUser?.uid ?: ""

        // 1. Listen to Ride metadata
        val rideRef = firestore.collection("rides").document(rideId)
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
        val ridersRef = firestore.collection("rides").document(rideId).collection("riders")
        ridersListener = ridersRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, ridersRef.path)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val ridersList = snapshot.documents.mapNotNull { doc ->
                    parseRiderDoc(doc, currentUid)
                }
                _members.value = ridersList
            }
        }

        // 3. Real-time Listen to messages: /rides/{rideId}/messages
        val messagesRef = firestore.collection("rides").document(rideId).collection("messages")
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
        val sosRef = firestore.collection("rides").document(rideId).collection("sos")
        sosListener = sosRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, sosRef.path)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val activeSosDoc = snapshot.documents.firstOrNull {
                    it.getBoolean("isResolved") != true
                }
                if (activeSosDoc != null) {
                    val riderId = activeSosDoc.getString("riderId") ?: ""
                    val riderName = activeSosDoc.getString("riderName") ?: "Rider"
                    val lat = activeSosDoc.getDouble("latitude") ?: 0.0
                    val lng = activeSosDoc.getDouble("longitude") ?: 0.0
                    val ts = activeSosDoc.getLong("timestampMs") ?: System.currentTimeMillis()

                    _activeSos.value = SosEvent(
                        id = activeSosDoc.id,
                        riderId = riderId,
                        riderName = riderName,
                        location = LatLng(lat, lng),
                        timestampMs = ts,
                        isResolved = false
                    )
                } else {
                    _activeSos.value = null
                }
            }
        }
    }

    private fun parseRiderDoc(doc: DocumentSnapshot, currentUid: String): RiderMember? {
        val id = doc.getString("userId") ?: doc.id
        val name = doc.getString("name") ?: "Rider"
        val motorcycle = doc.getString("motorcycleModel") ?: ""
        val roleStr = doc.getString("role") ?: "MEMBER"
        val role = runCatching { RideRole.valueOf(roleStr) }.getOrDefault(RideRole.MEMBER)
        val statusStr = doc.getString("status") ?: "RIDING"
        val status = runCatching { MemberStatus.valueOf(statusStr) }.getOrDefault(MemberStatus.RIDING)
        val lat = doc.getDouble("latitude") ?: return null
        val lng = doc.getDouble("longitude") ?: return null
        val speedKmh = doc.getDouble("speedKmh") ?: 0.0
        val headingDeg = (doc.getDouble("headingDeg") ?: 0.0).toFloat()
        val stoppedSec = doc.getLong("stoppedDurationSec") ?: 0L
        val lastUpdated = doc.getLong("lastUpdatedMs") ?: System.currentTimeMillis()
        val battery = (doc.getLong("batteryPct") ?: 90L).toInt()

        val isCurrentUser = (id == currentUid)

        // Calculate connection & staleness
        val timeDiffMs = System.currentTimeMillis() - lastUpdated
        val connectionStatus = if (isCurrentUser) {
            if (locationTracker.isGpsAvailable.value) com.example.data.model.ConnectionStatus.CONNECTED
            else com.example.data.model.ConnectionStatus.LOCATION_UNAVAILABLE
        } else {
            when {
                timeDiffMs > 60000L -> com.example.data.model.ConnectionStatus.DISCONNECTED
                timeDiffMs > 25000L -> com.example.data.model.ConnectionStatus.LOCATION_STALE
                else -> com.example.data.model.ConnectionStatus.CONNECTED
            }
        }

        val avatarColorHex = when (Math.abs(id.hashCode()) % 5) {
            0 -> 0xFFFF9800
            1 -> 0xFF4CAF50
            2 -> 0xFF29B6F6
            3 -> 0xFFFFCA28
            else -> 0xFFAB47BC
        }

        return RiderMember(
            id = id,
            name = name,
            motorcycleModel = motorcycle,
            avatarColorHex = avatarColorHex,
            role = role,
            status = status,
            connectionStatus = connectionStatus,
            location = LatLng(lat, lng),
            speedKmh = speedKmh,
            headingDeg = headingDeg,
            lastUpdatedMs = lastUpdated,
            stoppedDurationSec = stoppedSec,
            isCurrentUser = isCurrentUser,
            batteryPct = battery
        )
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
                firestore.collection("rides").document(current.id)
                    .update(
                        mapOf(
                            "totalDistanceMeters" to totalDistanceMeters,
                            "totalDurationSeconds" to totalDurationSeconds,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
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
                firestore.collection("rides").document(ride.id)
                    .update(
                        mapOf(
                            "status" to RideStatus.ACTIVE.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/${ride.id}")
            }
        }
    }

    fun endRide() {
        val ride = _currentRide.value ?: return
        _currentRide.value = ride.copy(status = RideStatus.COMPLETED)
        locationTracker.stopTracking()

        repositoryScope.launch {
            try {
                firestore.collection("rides").document(ride.id)
                    .update(
                        mapOf(
                            "status" to RideStatus.COMPLETED.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "rides/${ride.id}")
            }
        }
    }

    fun leaveRide() {
        val activeRideId = _currentRide.value?.id
        val currentUid = auth.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() }

        if (activeRideId != null && currentUid != null) {
            repositoryScope.launch {
                try {
                    firestore.collection("rides").document(activeRideId)
                        .collection("riders").document(currentUid)
                        .delete()
                        .await()
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.DELETE, "rides/$activeRideId/riders/$currentUid")
                }
            }
        }

        detachListeners()
        locationTracker.stopTracking()
        _currentRide.value = null
        _members.value = emptyList()
        _activeSos.value = null
        _recentMessage.value = null
        stopDetector.reset()
    }

    fun sendQuickMessage(type: QuickMessageType) {
        val activeRideId = _currentRide.value?.id ?: return
        val currentUid = auth.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return

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
                firestore.collection("rides").document(activeRideId)
                    .collection("messages").document(msgId)
                    .set(doc)
                    .await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, "rides/$activeRideId/messages/$msgId")
            }
        }
    }

    fun triggerSos() {
        val activeRideId = _currentRide.value?.id ?: return
        val currentUid = auth.currentUser?.uid ?: currentUserId.takeIf { it.isNotBlank() } ?: return

        val currentUserMember = _members.value.find { it.isCurrentUser }
        val loc = currentUserMember?.location ?: locationTracker.currentLocation.value?.latLng ?: LatLng(18.5204, 73.8567)

        val sosId = UUID.randomUUID().toString()
        val event = SosEvent(
            id = sosId,
            riderId = currentUid,
            riderName = currentUserName,
            location = loc,
            timestampMs = System.currentTimeMillis(),
            isResolved = false
        )
        _activeSos.value = event
        _currentRiderStatus.value = MemberStatus.EMERGENCY

        repositoryScope.launch {
            try {
                val doc = mapOf(
                    "id" to sosId,
                    "rideId" to activeRideId,
                    "riderId" to currentUid,
                    "riderName" to currentUserName,
                    "latitude" to loc.latitude,
                    "longitude" to loc.longitude,
                    "isResolved" to false,
                    "timestampMs" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("rides").document(activeRideId)
                    .collection("sos").document(sosId)
                    .set(doc)
                    .await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, "rides/$activeRideId/sos/$sosId")
            }
        }
    }

    fun resolveSos() {
        val activeRideId = _currentRide.value?.id
        val activeEvent = _activeSos.value

        _activeSos.value = null
        _currentRiderStatus.value = MemberStatus.RIDING

        if (activeRideId != null && activeEvent != null) {
            repositoryScope.launch {
                try {
                    firestore.collection("rides").document(activeRideId)
                        .collection("sos").document(activeEvent.id)
                        .update(
                            mapOf(
                                "isResolved" to true,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.UPDATE, "rides/$activeRideId/sos/${activeEvent.id}")
                }
            }
        }
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
}
