package com.example.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
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
    onCreateRide: (name: String, start: String, destination: String, startCoords: LatLng?, destCoords: LatLng?, intermediateStops: List<com.example.data.place.SelectedPlace>) -> Unit,
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

    // Route estimate preview
    var routeDistanceText by remember { mutableStateOf("Calculating...") }
    var routeDurationText by remember { mutableStateOf("Estimating...") }
    var isEstimatingRoute by remember { mutableStateOf(false) }

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
                val result = routingService.fetchRoute(
                    startCoords,
                    destCoords,
                    intermediateSelections.map { LatLng(it.latitude, it.longitude) }
                )
                routeDistanceText = result.formattedDistance
                routeDurationText = result.formattedDuration
            } catch (_: Exception) {
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
                .padding(horizontal = 24.dp)
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
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("back_from_create_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create Group Ride",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Ride Name Field
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "RIDE NAME *",
                            color = AmberPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = rideName,
                            onValueChange = {
                                rideName = it
                                nameError = it.isBlank()
                            },
                            placeholder = { Text("e.g. Pune → Lonavala", color = Color(0xFF78909C)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.TwoWheeler,
                                    contentDescription = null,
                                    tint = AmberPrimary
                                )
                            },
                            isError = nameError,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AmberPrimary,
                                unfocusedBorderColor = SlateDark700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ride_name_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (nameError) {
                            Text(
                                text = "Ride name is required",
                                color = Color(0xFFFF5252),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Start Location Search Field
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "START POINT (MAP SEARCH)",
                                color = AmberPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // Quick Button: Use Current GPS
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
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
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
                                    fontSize = 10.sp,
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
                    }
                }

                // Swap Start & Destination Button
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            cancelGpsRequestAndMarkManual()
                            // Swap full field states atomically (text + selection).
                            val swapStartQuery = destSearchState.query
                            val swapStartPlace = destSearchState.selectedPlace
                            val swapDestQuery = startSearchState.query
                            val swapDestPlace = startSearchState.selectedPlace
                            startSearchEngine.replaceState(swapStartQuery, swapStartPlace)
                            destSearchEngine.replaceState(swapDestQuery, swapDestPlace)
                            rideName = autoRideName(swapStartQuery, swapDestQuery)
                        },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberPrimary.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("swap_start_dest_button")
                    ) {
                        Icon(imageVector = Icons.Default.SwapVert, contentDescription = "Swap", tint = AmberPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Swap Start & Destination", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                // Destination Search Field
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "DESTINATION (MAP SEARCH)",
                            color = AmberPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

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
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Intermediate Stops (Maps-style ordered waypoint planning) ──
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "INTERMEDIATE STOPS (IN ORDER)",
                                color = AmberPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${stopDrafts.size} added",
                                color = Color(0xFF78909C),
                                fontSize = 10.sp
                            )
                        }

                        stopDrafts.forEachIndexed { rowIndex, id ->
                            val engine = stopEngine(id)
                            val rowState = stopStates[id] ?: PlaceSearchUiState.Idle()
                            val selection = rowState.selectedPlace

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "STOP ${rowIndex + 1}",
                                    color = Color(0xFF78909C),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(52.dp)
                                )
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
                        Text(
                            text = "+ ADD STOP",
                            color = AmberPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SlateDark800)
                                .clickable(enabled = stopDrafts.size < 8) { addStopDraft() }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("add_stop_button")
                        )
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

                Spacer(modifier = Modifier.height(14.dp))

                // Road-Following Route Preview Card
                Surface(
                    color = SlateDark800.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmberPrimary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AmberPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Directions, contentDescription = null, tint = AmberPrimary, modifier = Modifier.size(24.dp))
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OSRM ROAD ROUTE ESTIMATE",
                                color = AmberPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$routeDistanceText • $routeDurationText",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isEstimatingRoute) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AmberPrimary, strokeWidth = 2.dp)
                                }
                            }
                            Text(
                                text = "Real highway paths will be traced on the live map",
                                color = Color(0xFF90A4AE),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
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
                val isFormValid = rideName.isNotBlank() &&
                    startSearchState.selectedPlace != null &&
                    destSearchState.selectedPlace != null &&
                    allStopsConfirmed
                Button(
                    onClick = {
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
                            intermediateSelections
                        )
                    },
                    enabled = !isCreating && isFormValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("submit_create_ride_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color.Black,
                        disabledContainerColor = AmberPrimary.copy(alpha = 0.4f),
                        disabledContentColor = Color.Black.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.Black,
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
                            text = "Create Ride & Open Lobby",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
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
