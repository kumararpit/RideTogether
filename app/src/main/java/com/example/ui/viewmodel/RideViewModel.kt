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
import com.example.data.location.RouteGeometry
import com.example.data.place.PhotonPlaceSearchRepository
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
import com.example.data.map.MapLighting
import com.example.data.map.MapThemeController
import com.example.data.map.MapThemeMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

enum class AppScreen {
    WELCOME,
    SIGN_IN,
    CREATE_RIDE,
    JOIN_RIDE,
    RIDE_LOBBY,
    LIVE_RIDE,
    RIDE_SUMMARY
}

class RideViewModel(application: Application) : AndroidViewModel(application) {

    val locationTracker = LocationTracker(application)
    val osrmRoutingService = OsrmRoutingService()

    /**
     * Photon-backed place search with Room persistence for recent selections.
     * Shares the app's single Room instance; a DAO-backed repository keeps recent
     * places reusable offline.
     */
    val placeSearchRepository = PhotonPlaceSearchRepository(
        recentPlaceDao = com.example.data.db.RideDatabase
            .getInstance(application)
            .recentPlaceDao()
    )

    val navEngine = com.example.data.location.NavEngine()
    val navAudioGuidance = com.example.data.location.NavAudioGuidance(application)

    /**
     * Task 18 — single owner of the resolved map lighting (AUTO/DAY/NIGHT override).
     * The canvas renders from [mapLighting] and reports camera-context changes here;
     * transitions are scheduled, never polled, and never reset map/nav state.
     */
    private val mapThemeController = MapThemeController(viewModelScope)
    val mapThemeMode: StateFlow<MapThemeMode> = mapThemeController.mode
    val mapLighting: StateFlow<MapLighting> = mapThemeController.lighting

    fun setMapThemeMode(mode: MapThemeMode) = mapThemeController.setMode(mode)
    fun onMapThemeResumed() = mapThemeController.onResumed()
    fun onMapLightingContext(lat: Double, lng: Double) = mapThemeController.onCameraCenter(lat, lng)

    private var consecutiveOffRouteFixes = 0
    private var lastRerouteTimestampMs = 0L
    private val REROUTE_COOLDOWN_MS = 15000L

    private val db: FirebaseFirestore? = try {
        FirebaseFirestore.getInstance()
    } catch (e: Exception) {
        android.util.Log.e("RideViewModel", "Error initializing FirebaseFirestore: ${e.message}", e)
        null
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

    private val _motorcycleModel = MutableStateFlow("Hunter 350")
    val motorcycleModel: StateFlow<String> = _motorcycleModel.asStateFlow()

    // Exposed repository flows
    val currentRide: StateFlow<Ride?> = repository.currentRide
    val members: StateFlow<List<RiderMember>> = repository.members
    val activeSos = repository.activeSos
    val recentMessage = repository.recentMessage

    /**
     * Bounded chronological group-message history (deduplicated by stable id) — each new
     * id is presented exactly once by the toast, so rapid bursts are never overwritten.
     */
    val recentMessages = repository.recentMessages

    /** `true` when the last group-message send exhausted all write attempts. */
    val messageSendError = repository.messageSendError
    val currentRiderStatus = repository.currentRiderStatus
    val joinError = repository.joinError

    val amenityRepository = com.example.data.amenities.AmenityRepository(com.example.data.db.RideDatabase.getInstance(application))

    private val _amenities = MutableStateFlow<List<com.example.data.amenities.AmenityEntity>>(emptyList())
    val amenities: StateFlow<List<com.example.data.amenities.AmenityEntity>> = _amenities.asStateFlow()

    private val _visibleCategories = MutableStateFlow(
        com.example.data.amenities.AmenityType.entries.filter { it != com.example.data.amenities.AmenityType.UNKNOWN }.toSet()
    )
    val visibleCategories: StateFlow<Set<com.example.data.amenities.AmenityType>> = _visibleCategories.asStateFlow()

    private val _selectedAmenity = MutableStateFlow<com.example.data.amenities.AmenityEntity?>(null)
    val selectedAmenity: StateFlow<com.example.data.amenities.AmenityEntity?> = _selectedAmenity.asStateFlow()

    fun toggleAmenityCategory(type: com.example.data.amenities.AmenityType) {
        val current = _visibleCategories.value.toMutableSet()
        if (current.contains(type)) current.remove(type) else current.add(type)
        _visibleCategories.value = current
    }

    fun selectAmenity(amenity: com.example.data.amenities.AmenityEntity?) {
        _selectedAmenity.value = amenity
    }

    private var viewportUpdateJob: kotlinx.coroutines.Job? = null

    fun onViewportChanged(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        viewportUpdateJob?.cancel()
        viewportUpdateJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400) // debounce
            val results = amenityRepository.getAmenitiesInViewport(minLat, maxLat, minLon, maxLon)
            _amenities.value = results
        }
    }

    // Dialogs / Sheets
    private val _showQuickMessageSheet = MutableStateFlow(false)
    val showQuickMessageSheet: StateFlow<Boolean> = _showQuickMessageSheet.asStateFlow()

    private val _showRiderListSheet = MutableStateFlow(false)
    val showRiderListSheet: StateFlow<Boolean> = _showRiderListSheet.asStateFlow()

    private val _showSosConfirmDialog = MutableStateFlow(false)
    val showSosConfirmDialog: StateFlow<Boolean> = _showSosConfirmDialog.asStateFlow()

    private val _selectedRider = MutableStateFlow<RiderMember?>(null)
    val selectedRider: StateFlow<RiderMember?> = _selectedRider.asStateFlow()

    // Routing & Turn-by-Turn Navigation States
    private val _routeResult = MutableStateFlow<RouteResult?>(null)
    val routeResult: StateFlow<RouteResult?> = _routeResult.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _isRerouting = MutableStateFlow(false)
    val isRerouting: StateFlow<Boolean> = _isRerouting.asStateFlow()

    private val _isFollowRiderMode = MutableStateFlow(true)
    val isFollowRiderMode: StateFlow<Boolean> = _isFollowRiderMode.asStateFlow()

    // Room database for completed rides.
    private val _completedRides = MutableStateFlow<List<com.example.data.db.CompletedRideEntity>>(emptyList())
    val completedRides: StateFlow<List<com.example.data.db.CompletedRideEntity>> = _completedRides.asStateFlow()

    private var currentRouteRideId: String? = null

    /** Shared-completion handling must happen exactly once per ride (idempotency guard). */
    private var sharedCompletionHandledRideId: String? = null
    internal var sharedCompletionHandledCount: Int = 0
        internal set
    private var routeRequestId = 0

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

        // Task 18 — feed the theme controller with validated location contexts
        // (rider fix; route endpoints). Material-change dedupe happens in the controller,
        // so frequent GPS updates do not recompute the lighting.
        viewModelScope.launch {
            repository.members.collect { members ->
                val me = members.find { it.isCurrentUser } ?: return@collect
                mapThemeController.onRiderLocation(
                    lat = me.location.latitude,
                    lng = me.location.longitude,
                    accuracyMeters = me.accuracyMeters,
                    fixAgeMillis = System.currentTimeMillis() - me.lastUpdatedMs
                )
            }
        }
        viewModelScope.launch {
            repository.currentRide.collect { ride ->
                mapThemeController.onRouteEndpoints(
                    startLat = ride?.startLocation?.latitude,
                    startLng = ride?.startLocation?.longitude,
                    destLat = ride?.destinationLocation?.latitude,
                    destLng = ride?.destinationLocation?.longitude
                )
            }
        }

        // Check for active uncompleted ride on initialization
        viewModelScope.launch {
            try {
                repository.fetchActiveRideForUser()
            } catch (e: Exception) {
                android.util.Log.w("RideViewModel", "Failed restoring active ride: ${e.message}")
            }
        }

        // Monitor location updates for live route progress, distance, ETA, and adaptive off-route detection
        viewModelScope.launch {
            locationTracker.currentLocation.collectLatest { locUpdate ->
                if (locUpdate != null) {
                    updateNavigationProgress(locUpdate)
                }
            }
        }

        // Load completed rides after viewModelScope is ready.
        viewModelScope.launch {
            try {
                com.example.data.db.RideDatabase.getInstance(getApplication<Application>())
                    .completedRideDao()
                    .getAllCompletedRides()
                    .collectLatest { rides ->
                        _completedRides.value = rides
                    }
            } catch (e: Exception) {
                android.util.Log.e("RideViewModel", "Failed to load completed rides from Room database", e)
                _completedRides.value = emptyList()
            }
        }

        // Observe current ride changes to sync route result and nav engine state
        viewModelScope.launch {
            repository.currentRide.collectLatest { ride ->
                if (ride != null) {
                    val isNewRide = (currentRouteRideId != ride.id)
                    val hasNoRoute = (_routeResult.value == null || _routeResult.value?.coordinates.isNullOrEmpty())

                    // A completed/cancelled ride must not (re)enter navigation: setting a
                    // route here would revive the nav engine purely from stale ride data.
                    val routeEligible = ride.routePoints.isNotEmpty() &&
                        ride.status != com.example.data.model.RideStatus.COMPLETED &&
                        ride.status != com.example.data.model.RideStatus.CANCELLED

                    if ((isNewRide || hasNoRoute) && routeEligible) {
                        currentRouteRideId = ride.id
                        val result = RouteResult(
                            coordinates = ride.routePoints,
                            totalDistanceMeters = ride.totalDistanceMeters,
                            totalDurationSeconds = ride.totalDurationSeconds,
                            steps = ride.navigationSteps
                        )
                        _routeResult.value = result
                        navEngine.setRoute(result)
                    }

                    // Shared completion observed from the authoritative ride document —
                    // every joined client runs the same once-per-ride terminal handling.
                    if (ride.status == com.example.data.model.RideStatus.COMPLETED) {
                        enterSharedCompletedState(ride.id)
                    }
                }
            }
        }
    }

    /**
     * Shared-completion reconciliation on any joined client (leader included, via the
     * listener echo). Exactly once per ride id: preserves ride history, stops ride-scoped
     * GPS/navigation/foreground-service work and moves to the established post-ride
     * screen. Idempotent against repeated COMPLETED snapshots.
     */
    private fun enterSharedCompletedState(rideId: String) {
        if (sharedCompletionHandledRideId == rideId) return
        sharedCompletionHandledRideId = rideId
        sharedCompletionHandledCount++

        val ride = repository.currentRide.value
        val memberList = repository.members.value
        if (ride != null && _completedRides.value.none { it.id == ride.id }) {
            viewModelScope.launch {
                try {
                    com.example.data.db.RideDatabase.getInstance(getApplication())
                        .completedRideDao()
                        .insertRide(buildCompletedRideEntity(ride, memberList))
                    android.util.Log.d("RideViewModel", "Saved completed ride ${ride.id} to local Room history (shared completion)")
                } catch (e: Exception) {
                    android.util.Log.w("RideViewModel", "Failed auto-saving shared completed ride ${ride.id}: ${e.message}")
                }
            }
        }

        // Stop active navigation progression and ride-specific foreground work.
        navEngine.reset()
        com.example.data.location.RideLocationService.stopService(getApplication())
        repository.onSharedRideCompleted(rideId)

        // Established post-ride state (existing screen).
        if (_currentScreen.value != AppScreen.RIDE_SUMMARY) {
            _currentScreen.value = AppScreen.RIDE_SUMMARY
        }
        android.util.Log.d("RideViewModel", "Shared ride completion processed ride=$rideId (count=$sharedCompletionHandledCount)")
    }

    private fun buildCompletedRideEntity(
        ride: com.example.data.model.Ride,
        memberList: List<com.example.data.model.RiderMember>
    ): com.example.data.db.CompletedRideEntity {
        val distMeters = ride.finalSummaryDistanceMeters
        // Use actual elapsed duration if available; fall back to planned OSRM duration.
        val durationSec = if (ride.elapsedDurationSeconds > 0.0) ride.elapsedDurationSeconds else ride.totalDurationSeconds
        val rawAvgSpeed = if (durationSec > 60 && distMeters > 500) ((distMeters / 1000.0) / (durationSec / 3600.0)) else 0.0
        // Implausible speed guard: motorcycles do not exceed 250 km/h; treat higher as data error.
        val avgSpeed = if (rawAvgSpeed in 0.0..250.0) rawAvgSpeed else 0.0
        if (rawAvgSpeed > 250.0) {
            android.util.Log.w("RideViewModel", "Implausible avg speed $rawAvgSpeed km/h for ride ${ride.id}: distMeters=$distMeters durationSec=$durationSec — saving 0.0")
        }
        return com.example.data.db.CompletedRideEntity(
            id = ride.id,
            rideName = ride.name,
            startLocationName = ride.startLocationName,
            destinationName = ride.destinationName,
            distanceMeters = distMeters,
            durationSeconds = durationSec,
            avgSpeedKmh = avgSpeed,
            riderCount = memberList.size.coerceAtLeast(1),
            completedAtMs = System.currentTimeMillis(),
            hadEmergency = memberList.any { it.status == MemberStatus.EMERGENCY }
        )
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

    fun transferLeadership(newLeaderId: String) {
        repository.transferLeadership(newLeaderId)
    }

    fun rejoinActiveRide() {
        val ride = currentRide.value ?: return
        // A completed ride must never be resumed "accidentally" after app restart/rejoin:
        // the terminal shared state routes back to the post-ride screen instead.
        when (ride.status) {
            com.example.data.model.RideStatus.ACTIVE -> {
                com.example.data.location.RideLocationService.startService(getApplication())
                _currentScreen.value = AppScreen.LIVE_RIDE
            }
            com.example.data.model.RideStatus.COMPLETED -> _currentScreen.value = AppScreen.RIDE_SUMMARY
            else -> _currentScreen.value = AppScreen.RIDE_LOBBY
        }
    }

    private fun updateNavigationProgress(locUpdate: com.example.data.location.LocationUpdate) {
        val navProg = navEngine.processLocation(locUpdate)

        // Voice Guidance Instruction Announcement
        val currentStep = navEngine.getCurrentStep()
        if (currentStep != null && _currentScreen.value == AppScreen.LIVE_RIDE) {
            navAudioGuidance.speakManeuver(
                instruction = currentStep.instruction,
                maneuverIndex = navProg.currentManeuverIndex,
                distanceMeters = navProg.distanceToNextManeuverMeters
            )
        }

        // Adaptive Off-Route Detection
        // Once the nav engine latches arrival, never count off-route fixes or trigger
        // rerouting — GPS overshoot past the finish pin must not restart a new route loop.
        if (navProg.isArrived) {
            if (consecutiveOffRouteFixes > 0) consecutiveOffRouteFixes = 0
            return
        }

        val accuracy = locUpdate.accuracyMeters
        val points = _routeResult.value?.coordinates ?: currentRide.value?.routePoints
        if (accuracy <= 30f && !points.isNullOrEmpty()) {
            val distMeters = RouteGeometry.distanceToPolylineMeters(locUpdate.latLng, points)
            val adaptiveThreshold = (accuracy * 2.5).toDouble().coerceIn(75.0, 150.0)

            if (distMeters > adaptiveThreshold) {
                consecutiveOffRouteFixes++
            } else {
                if (consecutiveOffRouteFixes > 0) consecutiveOffRouteFixes--
            }

            val now = System.currentTimeMillis()
            // Allow immediate reroute on first confirmation; only throttle successive network requests
            val isCooldownActive = lastRerouteTimestampMs > 0 && (now - lastRerouteTimestampMs) < REROUTE_COOLDOWN_MS
            if (consecutiveOffRouteFixes >= 2 && !isCooldownActive && !_isRerouting.value) {
                consecutiveOffRouteFixes = 0
                lastRerouteTimestampMs = now
                android.util.Log.d("RideViewModel", "Rerouting triggered. Dist: $distMeters, Threshold: $adaptiveThreshold")
                rerouteFromCurrentLocation()
            }
        }
    }

    fun toggleAudioMute(): Boolean {
        return navAudioGuidance.toggleMute()
    }

    fun toggleFollowRiderMode() {
        _isFollowRiderMode.value = !_isFollowRiderMode.value
    }

    fun setFollowRiderMode(follow: Boolean) {
        _isFollowRiderMode.value = follow
    }

    suspend fun requestSingleLocationFix(
        context: android.content.Context,
        timeoutMs: Long = 10000L
    ): Result<com.example.data.location.LocationUpdate> {
        return locationTracker.requestSingleLocationFix(context, timeoutMs)
    }

    fun createRide(
        rideName: String,
        startLocation: String,
        destination: String,
        startCoords: LatLng? = null,
        destCoords: LatLng? = null
    ) {
        // Validate incoming coordinates: non-finite or out-of-range values are
        // rejected rather than forwarded to Firestore/OSRM.
        fun usableCoords(coords: LatLng?): LatLng? =
            coords?.takeIf {
                it.latitude.isFinite() && it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            }

        val resolvedStart = usableCoords(startCoords)
            ?: locationTracker.currentLocation.value?.latLng?.takeIf {
                it.latitude.isFinite() && it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            }
            ?: LatLng(18.5204, 73.8567)
        // Existing destination fallback preserved for legacy call paths; the
        // Create-Ride UI now requires an explicit destination selection.
        val resolvedDest = usableCoords(destCoords) ?: LatLng(resolvedStart.latitude + 0.23, resolvedStart.longitude - 0.45)

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
            } catch (e: Exception) {
                android.util.Log.e("RideViewModel", "createRide failed, transitioning to lobby anyway", e)
                _currentScreen.value = AppScreen.RIDE_LOBBY
            }
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

    private var rerouteJob: kotlinx.coroutines.Job? = null

    private fun fetchRouteForRide(start: LatLng, destination: LatLng) {
        val reqId = ++routeRequestId
        rerouteJob?.cancel()
        consecutiveOffRouteFixes = 0
        lastRerouteTimestampMs = 0L
        rerouteJob = viewModelScope.launch {
            _isRerouting.value = true
            try {
                val result = osrmRoutingService.fetchRoute(start, destination)
                if (reqId == routeRequestId && result.coordinates.isNotEmpty() && isActive) {
                    currentRouteRideId = repository.currentRide.value?.id
                    _routeResult.value = result
                    navEngine.setRoute(result)
                    repository.updateRoute(
                        result.coordinates,
                        result.totalDistanceMeters,
                        result.totalDurationSeconds,
                        result.steps
                    )
                }
            } catch (_: Exception) {}
            if (reqId == routeRequestId) {
                _isRerouting.value = false
            }
        }
    }

    fun rerouteFromCurrentLocation() {
        val reqId = ++routeRequestId
        rerouteJob?.cancel()
        rerouteJob = viewModelScope.launch {
            _isRerouting.value = true
            val startTimeMs = System.currentTimeMillis()
            val userLoc = locationTracker.currentLocation.value?.latLng
                ?: repository.members.value.find { it.isCurrentUser }?.location
                ?: repository.currentRide.value?.startLocation
                ?: return@launch
            val dest = repository.currentRide.value?.destinationLocation
                ?: return@launch

            try {
                val result = osrmRoutingService.fetchRoute(userLoc, dest)
                // Do not overwrite an existing valid route with a fallback route during rerouting
                if (reqId == routeRequestId && result.coordinates.isNotEmpty() && !result.isFallback && isActive) {
                    _routeResult.value = result
                    navEngine.setRoute(result)
                    repository.updateRoute(
                        result.coordinates,
                        result.totalDistanceMeters,
                        result.totalDurationSeconds,
                        result.steps
                    )
                    android.util.Log.d("RideViewModel", "Rerouting completed in ${System.currentTimeMillis() - startTimeMs} ms")
                }
            } catch (_: Exception) {}
            if (reqId == routeRequestId) {
                _isRerouting.value = false
            }
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

    fun startRide() {
        consecutiveOffRouteFixes = 0
        lastRerouteTimestampMs = 0L
        // Lock the camera straight into FOLLOWING mode at navigation zoom so the
        // map never flashes into Free-Pan route overview at ride start.
        _isFollowRiderMode.value = true
        navEngine.reset() // clear any stale arrival latch from a previous session
        repository.startRide()
        com.example.data.location.RideLocationService.startService(getApplication())
        _currentScreen.value = AppScreen.LIVE_RIDE
    }

    fun endRide() {
        val ride = currentRide.value
        val memberList = members.value
        if (ride != null) {
            saveLeaderCompletedRideSummary(ride, memberList)
        }
        com.example.data.location.RideLocationService.stopService(getApplication())
        _currentScreen.value = AppScreen.RIDE_SUMMARY
        viewModelScope.launch {
            val result = repository.endRide()
            if (result.isFailure) {
                android.util.Log.w(
                    "RideViewModel",
                    "Shared completion write failed for leader (ride=${ride?.id}): ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    private fun saveLeaderCompletedRideSummary(
        ride: com.example.data.model.Ride,
        memberList: List<com.example.data.model.RiderMember>
    ) {
        if (_completedRides.value.none { it.id == ride.id }) {
            viewModelScope.launch {
                try {
                    com.example.data.db.RideDatabase.getInstance(getApplication())
                        .completedRideDao()
                        .insertRide(buildCompletedRideEntity(ride, memberList))
                } catch (e: Exception) {
                    android.util.Log.w("RideViewModel", "Error saving completed ride to Room: ${e.message}")
                }
            }
        }
    }

    fun leaveRide() {
        com.example.data.location.RideLocationService.stopService(getApplication())
        viewModelScope.launch {
            repository.leaveRide()
        }
        currentRouteRideId = null
        routeRequestId++
        _routeResult.value = null
        navEngine.reset()
        _currentScreen.value = AppScreen.WELCOME
    }

    fun sendQuickMessage(type: QuickMessageType) {
        repository.sendQuickMessage(type)
    }

    fun triggerSos() {
        viewModelScope.launch {
            repository.triggerSos()
        }
    }

    fun acknowledgeSos(sosId: String) {
        repository.acknowledgeSos(sosId)
    }

    /** Member-side per-device removal of the SOS popup (does not resolve the emergency). */
    fun dismissSosBanner() {
        repository.dismissSosBanner()
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
        return navEngine.getCurrentStep()
    }

    fun getNextNavigationStep(): com.example.data.model.NavigationStep? {
        return navEngine.getNextStep()
    }

    fun getFormattedRemainingDistance(meters: Double): String {
        return if (meters >= 1000) {
            String.format(Locale.ROOT, "%.1f km", meters / 1000.0)
        } else {
            "${kotlin.math.round(meters).toInt()} m"
        }
    }

    fun getFormattedRemainingDuration(totalSecs: Double): String {
        val mins = kotlin.math.round(totalSecs / 60.0).toInt()
        return if (mins >= 60) {
            val hours = mins / 60
            val remMins = mins % 60
            if (remMins > 0) "${hours}h ${remMins}m" else "${hours}h"
        } else {
            "${mins} min"
        }
    }

    fun getFormattedEta(totalSecs: Double): String {
        val etaTimeMs = System.currentTimeMillis() + (totalSecs * 1000).toLong()
        val sdf = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(java.util.Date(etaTimeMs))
    }

    fun addPackMember(name: String, offsetLat: Double = 0.003, offsetLon: Double = 0.003) {
        // Mock rider for demo/offline testing
    }
}
