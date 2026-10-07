package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.location.LocationTracker
import com.example.data.location.OsrmRoutingService
import com.example.data.location.PlaceSearchService
import com.example.data.location.RouteGeometry
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.NavigationStep
import com.example.data.model.QuickMessage
import com.example.data.model.QuickMessageType
import com.example.data.model.Ride
import com.example.data.model.RiderMember
import com.example.data.model.RouteResult
import com.example.data.model.SosEvent
import com.example.data.repository.RideRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class AppScreen {
    WELCOME,
    CREATE_RIDE,
    JOIN_RIDE,
    RIDE_LOBBY,
    LIVE_RIDE,
    RIDE_SUMMARY
}

class RideViewModel(application: Application) : AndroidViewModel(application) {

    val locationTracker = LocationTracker(application.applicationContext)
    val repository = RideRepository(locationTracker)
    val placeSearchService = PlaceSearchService()
    val osrmRoutingService = OsrmRoutingService()

    // Navigation Screen State
    private val _currentScreen = MutableStateFlow(AppScreen.WELCOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    // Current User Profile
    private val _userName = MutableStateFlow("Arpit")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userId = MutableStateFlow("arpit_${UUID.randomUUID().toString().take(4)}")
    val userId: StateFlow<String> = _userId.asStateFlow()

    // State from repository
    val currentRide: StateFlow<Ride?> = repository.currentRide
    val members: StateFlow<List<RiderMember>> = repository.members
    val activeSos: StateFlow<SosEvent?> = repository.activeSos
    val recentMessage: StateFlow<QuickMessage?> = repository.recentMessage
    val currentRiderStatus: StateFlow<MemberStatus> = repository.currentRiderStatus

    // UI Dialog & Sheet states
    private val _showRidersSheet = MutableStateFlow(false)
    val showRidersSheet: StateFlow<Boolean> = _showRidersSheet.asStateFlow()

    private val _showQuickMessageSheet = MutableStateFlow(false)
    val showQuickMessageSheet: StateFlow<Boolean> = _showQuickMessageSheet.asStateFlow()

    private val _showSosConfirmDialog = MutableStateFlow(false)
    val showSosConfirmDialog: StateFlow<Boolean> = _showSosConfirmDialog.asStateFlow()

    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet.asStateFlow()

    private val _selectedRider = MutableStateFlow<RiderMember?>(null)
    val selectedRider: StateFlow<RiderMember?> = _selectedRider.asStateFlow()

    // Routing & Turn-by-Turn Navigation States
    private val _routeResult = MutableStateFlow<RouteResult?>(null)
    val routeResult: StateFlow<RouteResult?> = _routeResult.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _isRerouting = MutableStateFlow(false)
    val isRerouting: StateFlow<Boolean> = _isRerouting.asStateFlow()

    private val _isOffRoute = MutableStateFlow(false)
    val isOffRoute: StateFlow<Boolean> = _isOffRoute.asStateFlow()

    private val _remainingDistanceMeters = MutableStateFlow(0.0)
    val remainingDistanceMeters: StateFlow<Double> = _remainingDistanceMeters.asStateFlow()

    private val _remainingDurationSeconds = MutableStateFlow(0.0)
    val remainingDurationSeconds: StateFlow<Double> = _remainingDurationSeconds.asStateFlow()

    private val _isFollowRiderMode = MutableStateFlow(true)
    val isFollowRiderMode: StateFlow<Boolean> = _isFollowRiderMode.asStateFlow()

    init {
        repository.initCurrentUser(_userId.value, _userName.value)
        viewModelScope.launch {
            repository.members.collectLatest { list ->
                val selId = _selectedRider.value?.id
                if (selId != null) {
                    _selectedRider.value = list.find { it.id == selId }
                }
            }
        }

        // Monitor location updates for live route progress, distance, ETA, and off-route detection
        viewModelScope.launch {
            locationTracker.currentLocation.collectLatest { locUpdate ->
                if (locUpdate != null) {
                    updateNavigationProgress(locUpdate.latLng, locUpdate.speedKmh)
                }
            }
        }
    }

    private fun updateNavigationProgress(userLoc: LatLng, speedKmh: Double) {
        val route = _routeResult.value?.coordinates ?: currentRide.value?.routePoints
        if (route.isNullOrEmpty()) return

        // 1. Closest point on route
        val closestIdx = RouteGeometry.findClosestPointIndex(userLoc, route)
        if (closestIdx >= 0) {
            val distToRouteM = userLoc.distanceTo(route[closestIdx]) * 1000.0

            // Off-route check (> 250 meters away from route line)
            _isOffRoute.value = distToRouteM > 250.0

            // Remaining distance along route
            val remDistMeters = RouteGeometry.remainingDistanceMeters(closestIdx, route)
            _remainingDistanceMeters.value = remDistMeters

            // Remaining duration & ETA calculation
            val effectiveSpeedMps = if (speedKmh > 10.0) {
                (speedKmh * 1000.0) / 3600.0
            } else {
                // Average urban/highway motorcycle cruising speed (50 km/h = 13.9 m/s)
                13.9
            }
            val estDurationSec = if (effectiveSpeedMps > 0) remDistMeters / effectiveSpeedMps else 0.0
            _remainingDurationSeconds.value = estDurationSec

            // Step advancement: advance step when rider approaches next maneuver
            val steps = _routeResult.value?.steps ?: currentRide.value?.navigationSteps ?: emptyList()
            if (steps.isNotEmpty() && _currentStepIndex.value < steps.size - 1) {
                // If progress along route has moved past a fraction of the total points
                val progressFraction = closestIdx.toDouble() / route.size.toDouble()
                val targetStepIndex = (progressFraction * steps.size).toInt().coerceIn(0, steps.size - 1)
                if (targetStepIndex > _currentStepIndex.value) {
                    _currentStepIndex.value = targetStepIndex
                }
            }
        }
    }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun setUserName(name: String) {
        if (name.isNotBlank()) {
            _userName.value = name.trim()
            repository.initCurrentUser(_userId.value, _userName.value)
        }
    }

    fun toggleFollowRiderMode() {
        _isFollowRiderMode.value = !_isFollowRiderMode.value
    }

    fun setFollowRiderMode(follow: Boolean) {
        _isFollowRiderMode.value = follow
    }

    fun createRide(
        rideName: String,
        startLocation: String,
        destination: String,
        startCoords: LatLng? = null,
        destCoords: LatLng? = null
    ) {
        val userLoc = locationTracker.currentLocation.value?.latLng ?: LatLng(18.5204, 73.8567)
        val resolvedStart = startCoords ?: userLoc
        val resolvedDest = destCoords ?: LatLng(resolvedStart.latitude + 0.23, resolvedStart.longitude - 0.45)

        val newRide = repository.createRide(
            rideName = rideName.ifBlank { "${startLocation.ifBlank { "Pune" }} → ${destination.ifBlank { "Lonavala" }}" },
            startName = startLocation.ifBlank { "Pune" },
            destName = destination.ifBlank { "Lonavala" },
            startLoc = resolvedStart,
            destLoc = resolvedDest
        )
        _currentScreen.value = AppScreen.RIDE_LOBBY

        // Immediately fetch road-following route from OSRM
        fetchRouteForRide(resolvedStart, resolvedDest)
    }

    fun joinRideWithCode(code: String) {
        val trimmed = code.trim().uppercase(Locale.ROOT).ifBlank { "ABC123" }
        val userLoc = locationTracker.currentLocation.value?.latLng ?: LatLng(18.5204, 73.8567)
        val ride = repository.joinRide(trimmed, userLoc)
        _currentScreen.value = AppScreen.RIDE_LOBBY

        fetchRouteForRide(ride.startLocation, ride.destinationLocation)
    }

    private fun fetchRouteForRide(start: LatLng, destination: LatLng) {
        viewModelScope.launch {
            _isRerouting.value = true
            try {
                val result = osrmRoutingService.fetchRoute(start, destination)
                _routeResult.value = result
                _currentStepIndex.value = 0
                _remainingDistanceMeters.value = result.totalDistanceMeters
                _remainingDurationSeconds.value = result.totalDurationSeconds
                repository.updateRoute(
                    result.coordinates,
                    result.totalDistanceMeters,
                    result.totalDurationSeconds,
                    result.steps
                )
            } catch (_: Exception) {}
            _isRerouting.value = false
        }
    }

    /**
     * Reroutes from current GPS location to the destination using OSRM.
     */
    fun rerouteFromCurrentLocation() {
        viewModelScope.launch {
            _isRerouting.value = true
            val userLoc = locationTracker.currentLocation.value?.latLng
                ?: repository.members.value.find { it.isCurrentUser }?.location
                ?: repository.currentRide.value?.startLocation
                ?: LatLng(18.5204, 73.8567)
            val dest = repository.currentRide.value?.destinationLocation
                ?: LatLng(18.7546, 73.4062)

            try {
                val result = osrmRoutingService.fetchRoute(userLoc, dest)
                _routeResult.value = result
                _currentStepIndex.value = 0
                _isOffRoute.value = false
                _remainingDistanceMeters.value = result.totalDistanceMeters
                _remainingDurationSeconds.value = result.totalDurationSeconds
                repository.updateRoute(
                    result.coordinates,
                    result.totalDistanceMeters,
                    result.totalDurationSeconds,
                    result.steps
                )
            } catch (_: Exception) {}
            _isRerouting.value = false
        }
    }

    fun addPackMember(name: String) {
        val offsets = listOf(
            Pair(0.005, 0.004),
            Pair(-0.004, -0.003),
            Pair(0.008, -0.006),
            Pair(-0.007, 0.005)
        )
        val rand = offsets.random()
        repository.addPackMember(name, rand.first, rand.second)
    }

    fun startRide() {
        repository.startRide()
        // Ensure route exists
        if (_routeResult.value == null) {
            val ride = repository.currentRide.value
            if (ride != null) {
                fetchRouteForRide(ride.startLocation, ride.destinationLocation)
            }
        }
        _currentScreen.value = AppScreen.LIVE_RIDE
    }

    fun endRide() {
        repository.endRide()
        _currentScreen.value = AppScreen.RIDE_SUMMARY
    }

    fun leaveRide() {
        repository.leaveRide()
        _selectedRider.value = null
        _routeResult.value = null
        _currentScreen.value = AppScreen.WELCOME
    }

    fun openRidersSheet(open: Boolean) {
        _showRidersSheet.value = open
    }

    fun openQuickMessageSheet(open: Boolean) {
        _showQuickMessageSheet.value = open
    }

    fun openSosConfirmDialog(open: Boolean) {
        _showSosConfirmDialog.value = open
    }

    fun openSettingsSheet(open: Boolean) {
        _showSettingsSheet.value = open
    }

    fun selectRider(rider: RiderMember?) {
        _selectedRider.value = rider
    }

    fun sendQuickMessage(type: QuickMessageType) {
        repository.sendQuickMessage(type)
        _showQuickMessageSheet.value = false
    }

    fun triggerSos() {
        _showSosConfirmDialog.value = false
        repository.triggerSos()
    }

    fun resolveSos() {
        repository.resolveSos()
    }

    fun getRoutePoints(): List<LatLng> {
        val resultCoords = _routeResult.value?.coordinates
        if (!resultCoords.isNullOrEmpty()) return resultCoords

        val rideCoords = repository.currentRide.value?.routePoints
        if (!rideCoords.isNullOrEmpty()) return rideCoords

        val ride = repository.currentRide.value ?: return emptyList()
        return listOf(ride.startLocation, ride.destinationLocation)
    }

    fun getCurrentNavigationStep(): NavigationStep? {
        val steps = _routeResult.value?.steps ?: repository.currentRide.value?.navigationSteps ?: return null
        val idx = _currentStepIndex.value
        return if (idx in steps.indices) steps[idx] else steps.firstOrNull()
    }

    fun getNextNavigationStep(): NavigationStep? {
        val steps = _routeResult.value?.steps ?: repository.currentRide.value?.navigationSteps ?: return null
        val nextIdx = _currentStepIndex.value + 1
        return if (nextIdx in steps.indices) steps[nextIdx] else null
    }

    fun getFormattedEta(): String {
        val durationSec = _remainingDurationSeconds.value
        val arrivalMs = System.currentTimeMillis() + (durationSec * 1000).toLong()
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date(arrivalMs))
    }

    fun getFormattedRemainingDuration(): String {
        val mins = kotlin.math.round(_remainingDurationSeconds.value / 60.0).toInt()
        return if (mins >= 60) {
            val hours = mins / 60
            val remMins = mins % 60
            if (remMins > 0) "${hours} hr ${remMins} min" else "${hours} hr"
        } else {
            "${mins.coerceAtLeast(1)} min"
        }
    }

    fun getFormattedRemainingDistance(): String {
        val m = _remainingDistanceMeters.value
        return if (m >= 1000.0) {
            String.format(Locale.getDefault(), "%.1f km", m / 1000.0)
        } else {
            "${kotlin.math.round(m).toInt()} m"
        }
    }
}
