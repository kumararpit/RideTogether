package com.example.data.repository

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class RideRepository(
    private val locationTracker: LocationTracker,
    val stopDetector: StopDetector = StopDetector(
        stopSpeedThresholdKmh = 5.0,
        resumeSpeedThresholdKmh = 8.0,
        stopDurationThresholdSec = 120L
    )
) {
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

    private var currentUserId = "user_${UUID.randomUUID().toString().take(6)}"
    private var currentUserName = "Rider"

    init {
        // Collect real location updates and evaluate stop detection
        repositoryScope.launch {
            locationTracker.currentLocation.collectLatest { locUpdate ->
                if (locUpdate != null) {
                    processLocationUpdate(locUpdate)
                }
            }
        }
    }

    fun initCurrentUser(id: String, name: String) {
        currentUserId = id
        currentUserName = name
    }

    private fun processLocationUpdate(loc: LocationUpdate) {
        // Run stop detection algorithm on real GPS speed
        val (detectedStatus, stoppedSec) = stopDetector.processSpeed(loc.speedKmh, loc.timestampMs)

        // Only override if not in SOS
        val finalStatus = if (_activeSos.value?.riderId == currentUserId) {
            MemberStatus.EMERGENCY
        } else {
            detectedStatus
        }
        _currentRiderStatus.value = finalStatus

        // Update current user in members list
        val currentList = _members.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == currentUserId }
        val updatedMember = RiderMember(
            id = currentUserId,
            name = currentUserName,
            avatarColorHex = 0xFFFF9800,
            role = if (_currentRide.value?.leaderId == currentUserId) RideRole.LEADER else RideRole.MEMBER,
            status = finalStatus,
            location = loc.latLng,
            speedKmh = loc.speedKmh,
            headingDeg = loc.headingDeg,
            lastUpdatedMs = loc.timestampMs,
            stoppedDurationSec = stoppedSec,
            isCurrentUser = true,
            batteryPct = 90
        )

        if (index >= 0) {
            currentList[index] = updatedMember
        } else {
            currentList.add(updatedMember)
        }
        _members.value = currentList
    }

    fun createRide(
        rideName: String,
        startName: String,
        destName: String,
        startLoc: LatLng,
        destLoc: LatLng
    ): Ride {
        val inviteCode = generateInviteCode()
        val leader = RiderMember(
            id = currentUserId,
            name = currentUserName,
            avatarColorHex = 0xFFFF9800,
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

        val newRide = Ride(
            name = rideName,
            startLocationName = startName,
            destinationName = destName,
            startLocation = startLoc,
            destinationLocation = destLoc,
            leaderId = currentUserId,
            inviteCode = inviteCode,
            status = RideStatus.LOBBY,
            members = listOf(leader)
        )
        _currentRide.value = newRide
        _members.value = listOf(leader)
        return newRide
    }

    fun joinRide(inviteCode: String, userLoc: LatLng): Ride {
        val joinedMember = RiderMember(
            id = currentUserId,
            name = currentUserName,
            avatarColorHex = 0xFF29B6F6,
            role = RideRole.MEMBER,
            status = _currentRiderStatus.value,
            location = userLoc,
            speedKmh = 0.0,
            headingDeg = 0f,
            lastUpdatedMs = System.currentTimeMillis(),
            isCurrentUser = true
        )

        val ride = Ride(
            name = "Group Ride $inviteCode",
            startLocationName = "Start Point",
            destinationName = "Destination",
            startLocation = userLoc,
            destinationLocation = LatLng(userLoc.latitude + 0.15, userLoc.longitude + 0.15),
            leaderId = "leader_user",
            inviteCode = inviteCode.uppercase(),
            status = RideStatus.LOBBY,
            members = listOf(joinedMember)
        )
        _currentRide.value = ride
        _members.value = listOf(joinedMember)
        return ride
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
    }

    fun addPackMember(name: String, offsetLat: Double, offsetLon: Double, role: RideRole = RideRole.MEMBER) {
        val currentUser = _members.value.find { it.isCurrentUser }
        val baseLoc = currentUser?.location ?: LatLng(18.5204, 73.8567)
        val memberLoc = LatLng(baseLoc.latitude + offsetLat, baseLoc.longitude + offsetLon)

        val newMember = RiderMember(
            id = "rider_${UUID.randomUUID().toString().take(6)}",
            name = name,
            avatarColorHex = when (_members.value.size % 4) {
                0 -> 0xFF4CAF50
                1 -> 0xFF29B6F6
                2 -> 0xFFFFCA28
                else -> 0xFFAB47BC
            },
            role = role,
            status = MemberStatus.RIDING,
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

    fun startRide() {
        val ride = _currentRide.value ?: return
        _currentRide.value = ride.copy(status = RideStatus.ACTIVE)
        locationTracker.startTracking()
    }

    fun endRide() {
        val ride = _currentRide.value ?: return
        _currentRide.value = ride.copy(status = RideStatus.COMPLETED)
        locationTracker.stopTracking()
    }

    fun leaveRide() {
        locationTracker.stopTracking()
        _currentRide.value = null
        _members.value = emptyList()
        _activeSos.value = null
        _recentMessage.value = null
        stopDetector.reset()
    }

    fun sendQuickMessage(type: QuickMessageType) {
        val msg = QuickMessage(
            senderId = currentUserId,
            senderName = currentUserName,
            type = type,
            text = type.label,
            timestampMs = System.currentTimeMillis()
        )
        _recentMessage.value = msg

        // If message is "I'm stopping", set status to STOPPED
        if (type == QuickMessageType.IM_STOPPING) {
            updateMemberStatus(currentUserId, MemberStatus.STOPPED)
        } else if (type == QuickMessageType.IM_OKAY && _activeSos.value?.riderId == currentUserId) {
            resolveSos()
        }
    }

    fun triggerSos() {
        val currentUser = _members.value.find { it.isCurrentUser }
        val loc = currentUser?.location ?: LatLng(18.5204, 73.8567)
        val sos = SosEvent(
            riderId = currentUserId,
            riderName = currentUserName,
            location = loc,
            timestampMs = System.currentTimeMillis(),
            isResolved = false
        )
        _activeSos.value = sos
        updateMemberStatus(currentUserId, MemberStatus.EMERGENCY)
    }

    fun resolveSos() {
        val sos = _activeSos.value ?: return
        _activeSos.value = null
        updateMemberStatus(sos.riderId, MemberStatus.RIDING)
    }

    fun updateMemberStatus(riderId: String, status: MemberStatus) {
        val list = _members.value.toMutableList()
        val index = list.indexOfFirst { it.id == riderId }
        if (index >= 0) {
            val member = list[index]
            list[index] = member.copy(
                status = status,
                stoppedDurationSec = if (status == MemberStatus.STOPPED) 1 else 0
            )
            _members.value = list
        }
    }

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
