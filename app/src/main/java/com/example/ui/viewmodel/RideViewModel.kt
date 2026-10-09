package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.R
import com.example.data.firebase.AuthRepository
import com.example.data.firebase.RideRepository
import com.example.data.location.LocationTracker
import com.example.data.location.OsrmRoutingService
import com.example.data.location.PlaceSearchService
import com.example.data.location.RouteGeometry
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.QuickMessageType
import com.example.data.model.Ride
import com.example.data.model.RiderMember
import com.example.data.model.RouteResult
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

enum class AppScreen {
    WELCOME,
    CREATE_RIDE,
    JOIN_RIDE,
    RIDE_LOBBY,
    LIVE_RIDE,
    RIDE_SUMMARY
}

class RideViewModel(application: Application) : AndroidViewModel(application) {

    val locationTracker = LocationTracker(application)
    val osrmRoutingService = OsrmRoutingService()
    val placeSearchService = PlaceSearchService()

    private val db = run {
        val databaseId = BuildConfig.FIRESTORE_DATABASE_ID.ifBlank {
            try {
                application.getString(R.string.firestore_database_id)
            } catch (e: Exception) {
                "ai-studio-android-ridetoge-ff1218fd-f019-458f-ad73-f84981c73561"
            }
        }
        FirebaseFirestore.getInstance(databaseId)
    }

    val authRepository = AuthRepository()
    val repository = RideRepository(
        locationTracker = locationTracker,
        firestore = db
    )

    val currentUser: StateFlow<FirebaseUser?> = authRepository.authStateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, authRepository.currentUser)

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    // Navigation and screen state
    private val _currentScreen = MutableStateFlow(AppScreen.WELCOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _userId = MutableStateFlow(authRepository.currentUser?.uid ?: "user_rider")
    val userId: StateFlow<String> = _userId.asStateFlow()

    private val _userName = MutableStateFlow(authRepository.currentUser?.displayName ?: "Rider")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _motorcycleModel = MutableStateFlow("Triumph Tiger 900")
    val motorcycleModel: StateFlow<String> = _motorcycleModel.asStateFlow()

    // Exposed repository flows
    val currentRide: StateFlow<Ride?> = repository.currentRide
    val members: StateFlow<List<RiderMember>> = repository.members
    val activeSos = repository.activeSos
    val recentMessage = repository.recentMessage
    val currentRiderStatus = repository.currentRiderStatus
    val joinError = repository.joinError

    // Dialogs / Sheets
    private val _showQuickMessageSheet = MutableStateFlow(false)
    val showQuickMessageSheet: StateFlow<Boolean> = _showQuickMessageSheet.asStateFlow()

    private val _showRiderListSheet = MutableStateFlow(false)
    val showRiderListSheet: StateFlow<Boolean> = _showRiderListSheet.asStateFlow()

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
        // Sync user state on auth change
        viewModelScope.launch {
            currentUser.collectLatest { fbUser ->
                if (fbUser != null) {
                    _userId.value = fbUser.uid
                    val name = fbUser.displayName?.ifBlank { "Rider" } ?: "Rider"
                    _userName.value = name
                    repository.initCurrentUser(fbUser.uid, name, _motorcycleModel.value)
                }
            }
        }

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

    fun signInWithGoogle(context: android.content.Context) {
        viewModelScope.launch {
            _isAuthLoading.value = true
            _authError.value = null
            val result = authRepository.signInWithGoogle(context)
            _isAuthLoading.value = false
            result.onSuccess { user ->
                _userId.value = user.uid
                val name = user.displayName ?: "Rider"
                _userName.value = name
                repository.initCurrentUser(user.uid, name)
            }.onFailure { ex ->
                _authError.value = ex.localizedMessage ?: "Sign-in cancelled or failed"
            }
        }
    }

    fun signOut(context: android.content.Context) {
        viewModelScope.launch {
            authRepository.signOut(context)
            leaveRide()
            _currentScreen.value = AppScreen.WELCOME
        }
    }

    private fun updateNavigationProgress(userLoc: LatLng, speedKmh: Double) {
        val route = _routeResult.value?.coordinates ?: currentRide.value?.routePoints
        if (route.isNullOrEmpty()) return

        val closestIdx = RouteGeometry.findClosestPointIndex(userLoc, route)
        if (closestIdx >= 0) {
            val distToRouteM = userLoc.distanceTo(route[closestIdx]) * 1000.0
            _isOffRoute.value = distToRouteM > 250.0

            val remDistMeters = RouteGeometry.remainingDistanceMeters(closestIdx, route)
            _remainingDistanceMeters.value = remDistMeters

            val effectiveSpeedMps = if (speedKmh > 10.0) {
                (speedKmh * 1000.0) / 3600.0
            } else {
                35.0 * 1000.0 / 3600.0
            }
            _remainingDurationSeconds.value = if (effectiveSpeedMps > 0) {
                remDistMeters / effectiveSpeedMps
            } else {
                0.0
            }

            val steps = _routeResult.value?.steps ?: currentRide.value?.navigationSteps
            if (!steps.isNullOrEmpty()) {
                val totalMeters = _routeResult.value?.totalDistanceMeters ?: remDistMeters
                val traversedMeters = (totalMeters - remDistMeters).coerceAtLeast(0.0)
                var accumulated = 0.0
                var stepIdx = 0
                for (i in steps.indices) {
                    accumulated += steps[i].distanceMeters
                    if (accumulated >= traversedMeters) {
                        stepIdx = i
                        break
                    }
                }
                _currentStepIndex.value = stepIdx.coerceIn(0, steps.size - 1)
            }
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

        viewModelScope.launch {
            try {
                val newRide = repository.createRide(
                    rideName = rideName.ifBlank { "${startLocation.ifBlank { "Pune" }} → ${destination.ifBlank { "Lonavala" }}" },
                    startName = startLocation.ifBlank { "Pune" },
                    destName = destination.ifBlank { "Lonavala" },
                    startLoc = resolvedStart,
                    destLoc = resolvedDest
                )
                _currentScreen.value = AppScreen.RIDE_LOBBY
                fetchRouteForRide(resolvedStart, resolvedDest)
            } catch (_: Exception) {}
        }
    }

    fun joinRideWithCode(code: String) {
        val trimmed = code.trim().uppercase(Locale.ROOT)
        val userLoc = locationTracker.currentLocation.value?.latLng ?: LatLng(18.5204, 73.8567)

        viewModelScope.launch {
            val result = repository.joinRide(trimmed, userLoc)
            result.onSuccess { ride ->
                _currentScreen.value = AppScreen.RIDE_LOBBY
                fetchRouteForRide(ride.startLocation, ride.destinationLocation)
            }
        }
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
                _remainingDistanceMeters.value = result.totalDistanceMeters
                _remainingDurationSeconds.value = result.totalDurationSeconds
                repository.updateRoute(
                    result.coordinates,
                    result.totalDistanceMeters,
                    result.totalDurationSeconds,
                    result.steps
                )
                _isOffRoute.value = false
            } catch (_: Exception) {}
            _isRerouting.value = false
        }
    }

    fun setUserName(name: String) {
        if (name.isNotBlank()) {
            _userName.value = name
            repository.initCurrentUser(_userId.value, name, _motorcycleModel.value)
        }
    }

    fun setMotorcycleModel(model: String) {
        _motorcycleModel.value = model
        repository.setMotorcycleModel(model)
    }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun selectRider(rider: RiderMember?) {
        _selectedRider.value = rider
    }

    fun openQuickMessageSheet(open: Boolean) {
        _showQuickMessageSheet.value = open
    }

    fun openRiderListSheet(open: Boolean) {
        _showRiderListSheet.value = open
    }

    fun openSosDialog(open: Boolean) {
        _showSosConfirmDialog.value = open
    }

    fun openSettingsSheet(open: Boolean) {
        _showSettingsSheet.value = open
    }

    fun startRide() {
        repository.startRide()
        _currentScreen.value = AppScreen.LIVE_RIDE
    }

    fun endRide() {
        repository.endRide()
        _currentScreen.value = AppScreen.RIDE_SUMMARY
    }

    fun leaveRide() {
        repository.leaveRide()
        _routeResult.value = null
        _currentScreen.value = AppScreen.WELCOME
    }

    fun sendQuickMessage(type: QuickMessageType) {
        repository.sendQuickMessage(type)
    }

    fun triggerSos() {
        repository.triggerSos()
    }

    fun resolveSos() {
        repository.resolveSos()
    }

    val showRidersSheet: StateFlow<Boolean> = _showRiderListSheet
    fun openRidersSheet(open: Boolean) = openRiderListSheet(open)
    fun openSosConfirmDialog(open: Boolean) = openSosDialog(open)

    fun getRoutePoints(): List<LatLng> {
        return _routeResult.value?.coordinates
            ?: currentRide.value?.routePoints
            ?: emptyList()
    }

    fun getCurrentNavigationStep(): com.example.data.model.NavigationStep? {
        val steps = _routeResult.value?.steps ?: currentRide.value?.navigationSteps
        val idx = _currentStepIndex.value
        return if (!steps.isNullOrEmpty() && idx in steps.indices) steps[idx] else null
    }

    fun getNextNavigationStep(): com.example.data.model.NavigationStep? {
        val steps = _routeResult.value?.steps ?: currentRide.value?.navigationSteps
        val nextIdx = _currentStepIndex.value + 1
        return if (!steps.isNullOrEmpty() && nextIdx in steps.indices) steps[nextIdx] else null
    }

    fun getFormattedRemainingDistance(): String {
        val meters = _remainingDistanceMeters.value
        return if (meters >= 1000) {
            String.format(Locale.ROOT, "%.1f km", meters / 1000.0)
        } else {
            "${kotlin.math.round(meters).toInt()} m"
        }
    }

    fun getFormattedRemainingDuration(): String {
        val totalSecs = _remainingDurationSeconds.value
        val mins = kotlin.math.round(totalSecs / 60.0).toInt()
        return if (mins >= 60) {
            val hours = mins / 60
            val remMins = mins % 60
            if (remMins > 0) "${hours}h ${remMins}m" else "${hours}h"
        } else {
            "${mins} min"
        }
    }

    fun getFormattedEta(): String {
        val totalSecs = _remainingDurationSeconds.value
        val etaTimeMs = System.currentTimeMillis() + (totalSecs * 1000).toLong()
        val sdf = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(java.util.Date(etaTimeMs))
    }

    fun addPackMember(name: String, offsetLat: Double = 0.003, offsetLon: Double = 0.003) {
        // Mock rider for demo/offline testing
    }
}
