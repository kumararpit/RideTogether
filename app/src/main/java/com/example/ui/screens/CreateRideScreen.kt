package com.example.ui.screens

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.LocationTracker
import com.example.data.location.OsrmRoutingService
import com.example.data.model.LatLng
import com.example.data.place.PlaceSearchFailure
import com.example.data.place.PlaceSearchRepository
import com.example.data.place.PlaceSearchUiState
import com.example.data.place.PlaceProximityGuard
import com.example.data.place.PhotonPlaceSearchRepository
import com.example.data.place.SelectedPlace
import com.example.data.place.PlaceSource
import com.example.data.place.SearchLocationBias
import com.example.ui.components.RouteItineraryCard
import com.example.ui.components.RouteOptionSelector
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.SurfaceCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Create-Ride screen. Search (start & destination) runs through the Photon
 * repository via [PlaceSearchUiState] engines: debounced autocomplete, typed
 * selection integrity, loading/empty/offline states and recent-place reuse.
 * Layout and styling intentionally follow the original screen.
 */
@Composable
fun CreateRideScreen(
    onBack: () -> Unit,
    onCreateRide: (name: String, start: String, destination: String, startCoords: LatLng?, destCoords: LatLng?, intermediateStops: List<com.example.data.place.SelectedPlace>, selectedRouteIndex: Int, routeOptions: List<com.example.data.model.RouteOptionSummary>) -> Unit,
    currentGpsLocation: LatLng? = null,
    placeSearchRepository: PlaceSearchRepository = remember { PhotonPlaceSearchRepository() },
    locationBiasProvider: () -> SearchLocationBias? = { null },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val routingService = remember { OsrmRoutingService() }
    val locationTracker = remember(context) { LocationTracker(context) }

    // One engine per field: independent state for query text, suggestions and the
    // selected structured place (whose exact coordinates feed route calculation).
    val startSearchEngine = remember(placeSearchRepository) {
        com.example.data.place.PlaceSearchEngine(
            repository = placeSearchRepository,
            scope = coroutineScope,
            locationBiasProvider = locationBiasProvider
        )
    }
    val destSearchEngine = remember(placeSearchRepository) {
        com.example.data.place.PlaceSearchEngine(
            repository = placeSearchRepository,
            scope = coroutineScope,
            locationBiasProvider = locationBiasProvider
        )
    }
    val startSearchState by startSearchEngine.state.collectAsState()
    val destSearchState by destSearchEngine.state.collectAsState()

    // ── Intermediate stops (ordered multi-waypoint planning) ──────────────────
    // One engine per stop row (same pattern as start/destination above); drafts
    // are identified by a stable id that survives reordering and removal.
    var nextStopDraftId by remember { mutableStateOf(1L) }
    var stopDrafts by remember { mutableStateOf(listOf<Long>()) }
    val stopEngines = remember { mutableMapOf<Long, com.example.data.place.PlaceSearchEngine>() }
    var stopStates by remember { mutableStateOf(mapOf<Long, PlaceSearchUiState>()) }

    fun stopEngine(id: Long): com.example.data.place.PlaceSearchEngine =
        stopEngines.getOrPut(id) {
            com.example.data.place.PlaceSearchEngine(
                repository = placeSearchRepository,
                scope = coroutineScope,
                locationBiasProvider = locationBiasProvider
            )
        }

    fun addStopDraft() {
        val id = nextStopDraftId++
        stopEngine(id)
        stopDrafts = stopDrafts + id
    }

    fun removeStopDraft(id: Long) {
        stopEngines.remove(id)
        stopStates = stopStates - id
        stopDrafts = stopDrafts - id
    }

    fun moveStopDraft(id: Long, delta: Int) {
        val index = stopDrafts.indexOf(id)
        val target = index + delta
        if (index < 0 || target < 0 || target >= stopDrafts.size) return
        val mutable = stopDrafts.toMutableList()
        mutable.removeAt(index)
        mutable.add(target, id)
        stopDrafts = mutable
    }

    // Observation of per-stop engine states (selection + suggestions).
    val stopStateObservers = stopDrafts.toList()
    LaunchedEffect(stopStateObservers) {
        for (id in stopStateObservers) {
            launch {
                stopEngine(id).state.collect { state ->
                    stopStates = stopStates + (id to state)
                }
            }
        }
        // Note: collectors for removed rows end when this effect re-runs.
    }

    // Confirmed intermediate selections in the current draft order — the exact
    // coordinates fed to routing. A typed-but-unconfirmed row can never route.
    val intermediateSelections: List<com.example.data.place.SelectedPlace> =
        stopDrafts.mapNotNull { id -> stopStates[id]?.selectedPlace }

    val allStopsConfirmed = stopDrafts.all { id -> stopStates[id]?.selectedPlace != null }


    // Derived field values — text, suggestions and selection all live in the engine.
    val startLocationText = startSearchState.query
    val destinationText = destSearchState.query
    val startCoords = startSearchState.selectedPlace?.let { LatLng(it.latitude, it.longitude) }
    val destCoords = destSearchState.selectedPlace?.let { LatLng(it.latitude, it.longitude) }
    val isSearchingStart = startSearchState is PlaceSearchUiState.Loading
    val isSearchingDest = destSearchState is PlaceSearchUiState.Loading

    var startLocationAccuracyMeters by remember { mutableStateOf<Float?>(null) }

    var isGpsFixLoading by remember { mutableStateOf(false) }
    var gpsFixError by remember { mutableStateOf<String?>(null) }
    var gpsFixJob by remember { mutableStateOf<Job?>(null) }
    var gpsRequestId by remember { mutableStateOf(0) }

    fun cancelGpsRequestAndMarkManual() {
        gpsFixJob?.cancel()
        gpsRequestId++
        isGpsFixLoading = false
        gpsFixError = null
        startLocationAccuracyMeters = null
    }

    var rideName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    fun autoRideName(start: String, dest: String): String =
        listOf(start.trim(), dest.trim()).filter { it.isNotBlank() }.joinToString(" → ")

    // Swap the two endpoint fields atomically (text + selection).
    fun swapEnds() {
        cancelGpsRequestAndMarkManual()
        val swapStartQuery = destSearchState.query
        val swapStartPlace = destSearchState.selectedPlace
        val swapDestQuery = startSearchState.query
        val swapDestPlace = startSearchState.selectedPlace
        startSearchEngine.replaceState(swapStartQuery, swapStartPlace)
        destSearchEngine.replaceState(swapDestQuery, swapDestPlace)
        rideName = autoRideName(swapStartQuery, swapDestQuery)
    }

    // Route estimate preview
    var routeDistanceText by remember { mutableStateOf("Calculating...") }
    var routeDurationText by remember { mutableStateOf("Estimating...") }
    var isEstimatingRoute by remember { mutableStateOf(false) }

    // Candidate routes for the same ordered stops: the engine may return more
    // than one road path, and the leader picks the one the group rides.
    var previewOptions by remember { mutableStateOf<List<com.example.data.model.RouteOption>>(emptyList()) }
    var selectedRouteIndex by remember { mutableStateOf(0) }
    var previewRoute by remember { mutableStateOf<com.example.data.model.RouteResult?>(null) }
    var showRouteAlternatives by remember { mutableStateOf(false) }
    // Which row is open for editing; everything else stays a compact plan row.
    var activeField by remember { mutableStateOf<String?>(null) }
    var focusNewStop by remember { mutableStateOf(false) }
    var draggingStopId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(stopDrafts, focusNewStop) {
        if (focusNewStop) {
            activeField = stopDrafts.lastOrNull()?.let { "stop-$it" }
            focusNewStop = false
        }
    }

    fun applyPreviewSelection(options: List<com.example.data.model.RouteOption>, index: Int) {
        if (options.isEmpty()) return
        val safeIndex = index.coerceIn(0, options.lastIndex)
        val chosen = options.firstOrNull { it.index == safeIndex } ?: options.first()
        selectedRouteIndex = safeIndex
        previewRoute = chosen.route
        routeDistanceText = chosen.formattedDistance
        routeDurationText = chosen.formattedDuration
    }

    // Shared scroll controller for the ride-form column (auto-reveal of search
    // results, see LaunchedEffect below).
    val screenScrollState = rememberScrollState()

    // Recalculate route summary when the selected coordinates change — including
    // every intermediate stop (order-sensitive: the OSRM request serializes them
    // exactly in the user-defined order).
    LaunchedEffect(startCoords, destCoords, intermediateSelections) {
        if (startCoords != null && destCoords != null) {
            isEstimatingRoute = true
            try {
                val options = routingService.fetchRouteOptions(
                    startCoords,
                    destCoords,
                    intermediateSelections.map { LatLng(it.latitude, it.longitude) }
                )
                previewOptions = options
                applyPreviewSelection(options, selectedRouteIndex)
            } catch (_: Exception) {
                // No road route: fall back to a clearly-labelled straight-line
                // estimate. It is never presented as a road route.
                previewOptions = emptyList()
                previewRoute = null
                val distKm = startCoords.distanceTo(destCoords)
                routeDistanceText = String.format(java.util.Locale.getDefault(), "%.1f km (est)", distKm)
                val mins = (distKm / 50.0 * 60).toInt().coerceAtLeast(1)
                routeDurationText = "${mins} min"
            }
            isEstimatingRoute = false
        }
    }

    // Maps-benchmarked behavior: when either field's search settles into a
    // suggestion list (network results / offline recents / no-results / error row),
    // reveal it. Measured on device: the fixed bottom section (route preview +
    // Create button) draws over the tail of the scrollable column, so with the
    // keyboard open the freshly laid-out suggestions sit below the fold (layout
    // probe placed them at y≈1233 px behind the fixed cards) and are invisible
    // until the user manually scrolls. Google Maps shows results immediately
    // under the query field; auto-reveal reproduces that without touching any
    // search-state machinery.
    LaunchedEffect(startSearchState, destSearchState) {
        val startReveals = startSearchState is PlaceSearchUiState.Results ||
            startSearchState is PlaceSearchUiState.NoResults ||
            startSearchState is PlaceSearchUiState.Failure
        val destReveals = destSearchState is PlaceSearchUiState.Results ||
            destSearchState is PlaceSearchUiState.NoResults ||
            destSearchState is PlaceSearchUiState.Failure
        val startState = startSearchState
        val destState = destSearchState
        val bothBlankIdle = startState is PlaceSearchUiState.Idle && destState is PlaceSearchUiState.Idle &&
            startState.query.isBlank() && destState.query.isBlank()
        when {
            startReveals || destReveals -> screenScrollState.animateScrollTo(screenScrollState.maxValue)
            bothBlankIdle -> screenScrollState.animateScrollTo(0)
        }
    }

    val submitRide: () -> Unit = {
        val finalName = if (rideName.isBlank()) {
            "${startLocationText} → ${destinationText}"
        } else {
            rideName
        }
        isCreating = true
        onCreateRide(
            finalName,
            startLocationText,
            destinationText,
            startCoords,
            destCoords,
            intermediateSelections,
            selectedRouteIndex,
            previewOptions.map { option ->
                com.example.data.model.RouteOptionSummary(
                    index = option.index,
                    distanceMeters = option.distanceMeters,
                    durationSeconds = option.durationSeconds
                )
            }
        )
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("create_ride_screen"),
        color = SlateDark900
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(screenScrollState)
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("back_from_create_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Create Ride",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    // Balances the back button so the title stays centred.
                    Spacer(modifier = Modifier.width(48.dp))
                }

                Spacer(modifier = Modifier.height(14.dp))

                // The route card: reference order is origin, stops, then
                // destination, all in one surface so the plan reads as one object.
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                // Start Location Search Field
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (activeField == "start") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(com.example.ui.theme.StatusRidingGreen.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(com.example.ui.theme.StatusRidingGreen)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Starting point",
                                        color = com.example.ui.theme.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Search or use your location",
                                        color = com.example.ui.theme.TextTertiary,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            // Quick Button: Use Current GPS
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SlateDark800)
                                    .clickable(enabled = !isGpsFixLoading) {
                                        gpsFixJob?.cancel()
                                        val curReqId = ++gpsRequestId
                                        isGpsFixLoading = true
                                        gpsFixError = null

                                        gpsFixJob = coroutineScope.launch {
                                            val result = locationTracker.requestSingleLocationFix(context, timeoutMs = 8000L)
                                            if (curReqId == gpsRequestId) {
                                                isGpsFixLoading = false
                                                result.onSuccess { locUpdate ->
                                                    val fix = locUpdate.latLng
                                                    if (fix.latitude.isFinite() && fix.longitude.isFinite() &&
                                                        fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0
                                                    ) {
                                                        startSearchEngine.onPlaceSelected(
                                                            SelectedPlace(
                                                                id = "current_location:${fix.latitude},${fix.longitude}",
                                                                name = "Current GPS Location",
                                                                address = "±${locUpdate.accuracyMeters.toInt()} m accuracy",
                                                                latitude = fix.latitude,
                                                                longitude = fix.longitude,
                                                                source = PlaceSource.CURRENT_LOCATION
                                                            )
                                                        )
                                                        startLocationAccuracyMeters = locUpdate.accuracyMeters
                                                        rideName = autoRideName(
                                                            "Current Location",
                                                            destSearchState.selectedPlace?.name.orEmpty()
                                                        )
                                                        gpsFixError = null
                                                    } else {
                                                        gpsFixError = "GPS fix rejected: invalid coordinates"
                                                    }
                                                }.onFailure { err ->
                                                    gpsFixError = err.localizedMessage ?: "GPS fix timed out"
                                                    if (currentGpsLocation != null) {
                                                        startSearchEngine.onPlaceSelected(
                                                            SelectedPlace(
                                                                id = "current_location:$currentGpsLocation",
                                                                name = "Current GPS Location",
                                                                address = null,
                                                                latitude = currentGpsLocation.latitude,
                                                                longitude = currentGpsLocation.longitude,
                                                                source = PlaceSource.CURRENT_LOCATION
                                                            )
                                                        )
                                                        startLocationAccuracyMeters = 10f
                                                        rideName = autoRideName(
                                                            "Current Location",
                                                            destSearchState.selectedPlace?.name.orEmpty()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isGpsFixLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        color = AmberPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = null,
                                        tint = StatusRidingGreen,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isGpsFixLoading) "Locating..." else "Use GPS",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = startLocationText,
                            onValueChange = { query ->
                                cancelGpsRequestAndMarkManual()
                                startSearchEngine.onQueryChanged(query)
                            },
                            placeholder = { Text("Search city, town, landmark", color = Color(0xFF78909C)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = StatusRidingGreen
                                )
                            },
                            trailingIcon = {
                                if (isSearchingStart) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AmberPrimary, strokeWidth = 2.dp)
                                } else if (startLocationText.isNotBlank()) {
                                    IconButton(onClick = {
                                        cancelGpsRequestAndMarkManual()
                                        startSearchEngine.clearQuery()
                                    }) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AmberPrimary,
                                unfocusedBorderColor = SlateDark700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_location_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // GPS Error Message
                        if (gpsFixError != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "GPS Note: $gpsFixError",
                                color = Color(0xFFFF8A80),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Coordinates Confirmation & Accuracy Badge
                        if (startCoords != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val accuracy = startLocationAccuracyMeters
                            val (badgeText, badgeColor, badgeIcon) = when {
                                accuracy == null -> Triple(
                                    "Mapped: ${String.format("%.4f, %.4f", startCoords.latitude, startCoords.longitude)} (${startSearchState.selectedPlace?.source ?: "Manual"})",
                                    StatusRidingGreen,
                                    Icons.Default.CheckCircle
                                )
                                accuracy <= 30f -> Triple(
                                    "Mapped: ${String.format("%.4f, %.4f", startCoords.latitude, startCoords.longitude)} (±${accuracy.toInt()}m • High Precision)",
                                    StatusRidingGreen,
                                    Icons.Default.CheckCircle
                                )
                                accuracy <= 100f -> Triple(
                                    "Mapped: ${String.format("%.4f, %.4f", startCoords.latitude, startCoords.longitude)} (±${accuracy.toInt()}m • Coarse Fix)",
                                    AmberPrimary,
                                    Icons.Default.CheckCircle
                                )
                                else -> Triple(
                                    "Mapped: ${String.format("%.4f, %.4f", startCoords.latitude, startCoords.longitude)} (±${accuracy.toInt()}m • Low Precision)",
                                    Color(0xFFFF9800),
                                    Icons.Default.Warning
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = badgeIcon,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = badgeText,
                                    color = badgeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }

                        // Search / suggestion state (results, empty, error)
                        SearchResultsArea(
                            state = startSearchState,
                            accentColor = StatusRidingGreen,
                            testTagPrefix = "start_search",
                            onPlaceSelected = { place ->
                                cancelGpsRequestAndMarkManual()
                                startSearchEngine.onPlaceSelected(place)
                                rideName = autoRideName(place.name, destinationText)
                            },
                            onRetry = { startSearchEngine.retry() }
                        )
                        } else {
                            PlanRow(
                                label = "Starting point",
                                value = startSearchState.selectedPlace?.name
                                    ?: if (currentGpsLocation != null) "My Location"
                                    else "Choose starting point",
                                marker = {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(com.example.ui.theme.StatusRidingGreen.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(com.example.ui.theme.StatusRidingGreen)
                                        )
                                    }
                                },
                                onClick = { activeField = "start" }
                            )
                        }

                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                        androidx.compose.material3.HorizontalDivider(color = SlateDark700)

                // ── Intermediate Stops (Maps-style ordered waypoint planning) ──
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        stopDrafts.forEachIndexed { rowIndex, id ->
                            val engine = stopEngine(id)
                            val rowState = stopStates[id] ?: PlaceSearchUiState.Idle()
                            val selection = rowState.selectedPlace
                            val currentDrafts by rememberUpdatedState(stopDrafts)

                            key(id) {
                            if (activeField == "stop-$id") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selection != null) AmberPrimary else AmberPrimary.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${rowIndex + 1}",
                                        color = if (selection != null) Color(0xFF1A1205) else AmberPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                // Reorder controls: smallest stable interaction —
                                // up/down arrows keep a stable id per row (drag
                                // reordering deliberately omitted in this slice).
                                Column {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Move stop ${rowIndex + 1} up",
                                        tint = if (rowIndex > 0) AmberPrimary else Color(0xFF546E7A),
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable(enabled = rowIndex > 0) { moveStopDraft(id, -1) }
                                            .testTag("stop_${rowIndex}_up")
                                    )
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Move stop ${rowIndex + 1} down",
                                        tint = if (rowIndex < stopDrafts.lastIndex) AmberPrimary else Color(0xFF546E7A),
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable(enabled = rowIndex < stopDrafts.lastIndex) { moveStopDraft(id, +1) }
                                            .testTag("stop_${rowIndex}_down")
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = rowState.query,
                                    onValueChange = { engine.onQueryChanged(it) },
                                    placeholder = { Text("Search a stop on the map", color = Color(0xFF78909C)) },
                                    leadingIcon = {
                                        if (selection != null) {
                                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Stop confirmed", tint = AmberPrimary, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AmberPrimary,
                                        unfocusedBorderColor = SlateDark700,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("stop_${rowIndex}_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove stop ${rowIndex + 1}",
                                    tint = Color(0xFFFF8A80),
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { removeStopDraft(id) }
                                        .testTag("stop_${rowIndex}_remove")
                                )
                            }

                            SearchResultsArea(
                                state = rowState,
                                accentColor = AmberPrimary,
                                testTagPrefix = "stop_${rowIndex}_search",
                                onPlaceSelected = { place -> engine.onPlaceSelected(place) },
                                onRetry = { engine.retry() }
                            )
                            } else {
                                PlanRow(
                                    label = "Stop",
                                    value = selection?.name
                                        ?: rowState.query.ifBlank { "Choose a stop" },
                                    marker = {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(
                                                    if (selection != null) AmberPrimary
                                                    else AmberPrimary.copy(alpha = 0.18f)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${rowIndex + 1}",
                                                color = if (selection != null) Color(0xFF1A1205) else AmberPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    },
                                    onClick = { activeField = "stop-$id" },
                                    rowModifier = if (draggingStopId == id) {
                                        Modifier.background(AmberPrimary.copy(alpha = 0.10f))
                                    } else {
                                        Modifier
                                    },
                                    trailing = {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .pointerInput(id) {
                                                    val rowHeightPx = 60.dp.toPx()
                                                    var accumulated = 0f
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            accumulated = 0f
                                                            draggingStopId = id
                                                        },
                                                        onDragEnd = { draggingStopId = null },
                                                        onDragCancel = { draggingStopId = null },
                                                        onDrag = { change, amount ->
                                                            change.consume()
                                                            accumulated += amount.y
                                                            val drafts = currentDrafts
                                                            val index = drafts.indexOf(id)
                                                            if (index < 0) return@detectDragGestures
                                                            val threshold = rowHeightPx * 0.55f
                                                            when {
                                                                accumulated > threshold && index < drafts.lastIndex -> {
                                                                    val mutable = drafts.toMutableList()
                                                                    mutable.removeAt(index)
                                                                    mutable.add(index + 1, id)
                                                                    stopDrafts = mutable
                                                                    accumulated -= rowHeightPx
                                                                }
                                                                accumulated < -threshold && index > 0 -> {
                                                                    val mutable = drafts.toMutableList()
                                                                    mutable.removeAt(index)
                                                                    mutable.add(index - 1, id)
                                                                    stopDrafts = mutable
                                                                    accumulated += rowHeightPx
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                                .clickable { activeField = "stop-$id" }
                                                .testTag("stop_${rowIndex}_handle"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Menu,
                                                contentDescription = "Drag to reorder stop ${rowIndex + 1}",
                                                tint = if (draggingStopId == id) AmberPrimary else TextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                )
                            }
                            }

                        }

                        if (stopDrafts.isNotEmpty() && !allStopsConfirmed) {
                            Text(
                                text = "Confirm every stop by tapping a search result — unconfirmed stops are excluded from the route.",
                                color = Color(0xFFFFB74D),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = stopDrafts.size < 8) { addStopDraft() }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 4.dp)
                                .testTag("add_stop_button"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(AmberPrimary.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = AmberPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (stopDrafts.size < 8) "Add stop" else "Stop limit reached",
                                color = if (stopDrafts.size < 8) AmberPrimary else com.example.ui.theme.TextTertiary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (stopDrafts.isNotEmpty()) {
                            val stopNames = stopDrafts.mapIndexedNotNull { idx, id ->
                                stopStates[id]?.selectedPlace?.let { "${idx + 1}. ${it.name}" }
                            }
                            if (stopNames.size == stopDrafts.size) {
                                Text(
                                    text = "Route: Start → ${stopNames.joinToString(" → ")} → Destination",
                                    color = Color(0xFF90A4AE),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }

                        androidx.compose.material3.HorizontalDivider(color = SlateDark700)

                // Destination Search Field
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (activeField == "dest") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(com.example.ui.theme.StatusEmergencyRed.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Flag,
                                    contentDescription = null,
                                    tint = com.example.ui.theme.StatusEmergencyRed,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Destination",
                                    color = com.example.ui.theme.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Town, ghat or landmark",
                                    color = com.example.ui.theme.TextTertiary,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = destinationText,
                            onValueChange = { query ->
                                destSearchEngine.onQueryChanged(query)
                            },
                            placeholder = { Text("Search destination town, ghat, landmark", color = Color(0xFF78909C)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Flag,
                                    contentDescription = null,
                                    tint = AmberPrimary
                                )
                            },
                            trailingIcon = {
                                if (isSearchingDest) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AmberPrimary, strokeWidth = 2.dp)
                                } else if (destinationText.isNotBlank()) {
                                    IconButton(onClick = { destSearchEngine.clearQuery() }) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AmberPrimary,
                                unfocusedBorderColor = SlateDark700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("destination_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Selection-confirmation notice: typed text without a selected
                        // place can never be routed with stale coordinates.
                        if (destinationText.isNotBlank() && destSearchState.selectedPlace == null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Select a suggestion to confirm the destination",
                                color = AmberPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        } else if (destCoords != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = AmberPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Mapped: ${String.format("%.4f, %.4f", destCoords.latitude, destCoords.longitude)}",
                                    color = AmberPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }

                        // Advisory proximity warning (Phase B, B-05): a same-named
                        // place in another country must be visible before the
                        // route is built. The warning NEVER changes the selected
                        // coordinates and NEVER blocks the ride — it can be
                        // dismissed with "Use anyway".
                        val destinationProximityWarning = PlaceProximityGuard.warningFor(
                            from = startCoords,
                            place = destSearchState.selectedPlace
                        )
                        var proximityWarningDismissed by remember(destSearchState.selectedPlace?.id) {
                            mutableStateOf(false)
                        }
                        if (destinationProximityWarning != null && !proximityWarningDismissed) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = destinationProximityWarning,
                                    color = Color(0xFFFFB300),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Use anyway",
                                    color = AmberPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { proximityWarningDismissed = true }
                                        .testTag("destination_proximity_dismiss")
                                )
                            }
                        }

                        // Search / suggestion state (results, empty, error)
                        SearchResultsArea(
                            state = destSearchState,
                            accentColor = AmberPrimary,
                            testTagPrefix = "dest_search",
                            onPlaceSelected = { place ->
                                destSearchEngine.onPlaceSelected(place)
                                rideName = autoRideName(startLocationText, place.name)
                            },
                            onRetry = { destSearchEngine.retry() }
                        )
                        } else {
                            PlanRow(
                                label = "Destination",
                                value = destSearchState.selectedPlace?.name
                                    ?: destinationText.ifBlank { "Choose destination" },
                                marker = {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(com.example.ui.theme.StatusEmergencyRed.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Flag,
                                            contentDescription = null,
                                            tint = com.example.ui.theme.StatusEmergencyRed,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                },
                                onClick = { activeField = "dest" }
                            )
                        }

                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // The reference's two shortcuts under the route card.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            addStopDraft()
                            focusNewStop = true
                        },
                        enabled = stopDrafts.size < 8,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("add_from_map_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SlateDark700),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddLocationAlt,
                            contentDescription = null,
                            tint = AmberPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Add from map",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { activeField = "dest" },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("find_places_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SlateDark700),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Find places",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // Route Options: the reference row. The subtitle carries the
                // engine's real numbers; no invented "scenic" preferences.
                val chosenOption = previewOptions.firstOrNull { it.index == selectedRouteIndex }
                    ?: previewOptions.firstOrNull()
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            if (chosenOption != null) {
                                Modifier
                                    .clickable { showRouteAlternatives = true }
                                    .testTag("open_route_alternatives")
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.AltRoute,
                            contentDescription = null,
                            tint = if (chosenOption != null) AmberPrimary else TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Route Options",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = chosenOption?.let { option ->
                                    if (previewOptions.size == 1) {
                                        "Fastest · ${option.formattedDistance} · ${option.formattedDuration}"
                                    } else {
                                        "${previewOptions.size} routes · ${option.formattedDistance} · ${option.formattedDuration}"
                                    }
                                } ?: "Set both ends to compare routes",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

            }

            // Create Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            ) {
                // Route integrity: both origin and destination must be confirmed
                // selections with valid coordinates — an edited-but-unconfirmed
                // query can never be routed with previously selected coordinates.
                // Every intermediate stop row must equally carry a confirmed place.
                val isFormValid = startSearchState.selectedPlace != null &&
                    destSearchState.selectedPlace != null &&
                    allStopsConfirmed
                Button(
                    onClick = {
                        if (previewOptions.isEmpty()) {
                            submitRide()
                        } else {
                            showRouteAlternatives = true
                        }
                    },
                    enabled = !isCreating && isFormValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("submit_create_ride_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color(0xFF1A1205),
                        disabledContainerColor = SlateDark700,
                        disabledContentColor = Color(0xFF1A1205).copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF1A1205),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Opening Lobby...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Find Routes",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showRouteAlternatives && previewOptions.isNotEmpty()) {
        com.example.ui.components.RouteAlternativesSheet(
            options = previewOptions.map { option ->
                com.example.data.model.RouteOptionSummary(
                    index = option.index,
                    distanceMeters = option.distanceMeters,
                    durationSeconds = option.durationSeconds
                )
            },
            selectedIndex = selectedRouteIndex,
            points = previewRoute?.coordinates.orEmpty(),
            start = startCoords,
            destination = destCoords,
            onSelect = { index -> applyPreviewSelection(previewOptions, index) },
            onConfirm = {
                showRouteAlternatives = false
                submitRide()
            },
            onDismiss = { showRouteAlternatives = false }
        )
    }
}

/**
 * Renders the field's search state: suggestion rows (with a RECENT caption when
 * served from the offline cache), the no-results case, and a recoverable
 * offline/service-error row with a retry action. Loading is already reflected by
 * the field's trailing spinner.
 */
@Composable
private fun SearchResultsArea(
    state: PlaceSearchUiState,
    accentColor: Color,
    testTagPrefix: String,
    onPlaceSelected: (SelectedPlace) -> Unit,
    onRetry: () -> Unit
) {
    when (state) {
        is PlaceSearchUiState.Results -> {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateDark800)
                    .border(1.dp, SlateDark700, RoundedCornerShape(10.dp))
            ) {
                if (state.fromRecents) {
                    Text(
                        text = "RECENT PLACES (OFFLINE)",
                        color = Color(0xFF78909C),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                for (place in state.suggestions) {
                    SuggestionRow(
                        place = place,
                        accentColor = accentColor,
                        testTag = "${testTagPrefix}_row",
                        onClick = { onPlaceSelected(place) }
                    )
                }
            }
        }
        is PlaceSearchUiState.NoResults -> {
            if (state.query.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTagPrefix}_empty"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Place, contentDescription = null, tint = Color(0xFF78909C), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "No places matched \"${state.query.trim()}\". Try more letters or another name.",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                }
            }
        }
        is PlaceSearchUiState.Failure -> {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${testTagPrefix}_error"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = searchFailureMessage(state.failure),
                    color = Color(0xFFFF8A80),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "RETRY",
                    color = AmberPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onRetry)
                        .padding(4.dp)
                        .testTag("${testTagPrefix}_retry")
                )
            }
            if (state.selectedPlace != null) {
                Text(
                    text = "Confirmed selection kept: ${state.selectedPlace.name} (${state.selectedPlace.coordinateText()})",
                    color = Color(0xFF90A4AE),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun SuggestionRow(
    place: SelectedPlace,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (place.source == PlaceSource.CURRENT_LOCATION) Icons.Default.History else Icons.Default.Place,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = place.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (!place.address.isNullOrBlank()) {
                Text(
                    text = place.address,
                    color = Color(0xFF90A4AE),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

private fun searchFailureMessage(failure: PlaceSearchFailure): String = when (failure) {
    PlaceSearchFailure.NETWORK_UNAVAILABLE -> "No internet connection — suggestions unavailable."
    PlaceSearchFailure.RATE_LIMITED -> "Search service is busy (rate-limited). Try again shortly."
    PlaceSearchFailure.SERVER_ERROR -> "Search service temporarily unavailable."
    PlaceSearchFailure.MALFORMED_RESPONSE -> "Search service returned an unreadable response."
}


/**
 * One compact row of the plan card (reference screen 3): a marker, a small
 * label, the chosen value and an optional control on the right. The whole row
 * is the tap target, so the editor opens from anywhere on it.
 */
@Composable
private fun PlanRow(
    label: String,
    value: String,
    marker: @Composable () -> Unit,
    onClick: () -> Unit,
    valueColor: Color = Color.White,
    rowModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        marker()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = TextTertiary, fontSize = 11.sp)
            Text(
                text = value,
                color = valueColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}
