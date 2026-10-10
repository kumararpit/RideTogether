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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TwoWheeler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.OsrmRoutingService
import com.example.data.location.PlaceSearchResult
import com.example.data.location.PlaceSearchService
import com.example.data.model.LatLng
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.SurfaceCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CreateRideScreen(
    onBack: () -> Unit,
    onCreateRide: (name: String, start: String, destination: String, startCoords: LatLng?, destCoords: LatLng?) -> Unit,
    currentGpsLocation: LatLng? = null,
    placeSearchService: PlaceSearchService = remember { PlaceSearchService() },
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val routingService = remember { OsrmRoutingService() }

    val defaultStart = currentGpsLocation ?: LatLng(18.5204, 73.8567)
    val defaultDest = LatLng(18.7546, 73.4062)

    var rideName by remember { mutableStateOf("Pune → Lonavala") }
    var startLocationText by remember { mutableStateOf("Pune") }
    var startCoords by remember { mutableStateOf<LatLng?>(defaultStart) }

    var destinationText by remember { mutableStateOf("Lonavala") }
    var destCoords by remember { mutableStateOf<LatLng?>(defaultDest) }

    var nameError by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    // Search state for Start
    var startSearchResults by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var isSearchingStart by remember { mutableStateOf(false) }
    var showStartDropdown by remember { mutableStateOf(false) }
    var startDebounceJob by remember { mutableStateOf<Job?>(null) }

    // Search state for Destination
    var destSearchResults by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var isSearchingDest by remember { mutableStateOf(false) }
    var showDestDropdown by remember { mutableStateOf(false) }
    var destDebounceJob by remember { mutableStateOf<Job?>(null) }

    // Route estimate preview
    var routeDistanceText by remember { mutableStateOf("Calculating...") }
    var routeDurationText by remember { mutableStateOf("Estimating...") }
    var isEstimatingRoute by remember { mutableStateOf(false) }

    // Recalculate route summary when coordinates change
    LaunchedEffect(startCoords, destCoords) {
        if (startCoords != null && destCoords != null) {
            isEstimatingRoute = true
            try {
                val result = routingService.fetchRoute(startCoords!!, destCoords!!)
                routeDistanceText = result.formattedDistance
                routeDurationText = result.formattedDuration
            } catch (_: Exception) {
                val distKm = startCoords!!.distanceTo(destCoords!!)
                routeDistanceText = String.format(java.util.Locale.getDefault(), "%.1f km (est)", distKm)
                val mins = (distKm / 50.0 * 60).toInt().coerceAtLeast(1)
                routeDurationText = "${mins} min"
            }
            isEstimatingRoute = false
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
                    .verticalScroll(rememberScrollState())
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
                                    .clickable {
                                        if (currentGpsLocation != null) {
                                            startCoords = currentGpsLocation
                                            startLocationText = "Current GPS Location"
                                            rideName = "Current Location → $destinationText"
                                            showStartDropdown = false
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MyLocation,
                                    contentDescription = null,
                                    tint = StatusRidingGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Use GPS",
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
                                startLocationText = query
                                showStartDropdown = true
                                startDebounceJob?.cancel()
                                startDebounceJob = coroutineScope.launch {
                                    delay(250)
                                    isSearchingStart = true
                                    startSearchResults = placeSearchService.searchPlaces(query)
                                    isSearchingStart = false
                                }
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
                                    IconButton(onClick = { startLocationText = ""; showStartDropdown = false }) {
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

                        // Coordinates Confirmation Badge
                        if (startCoords != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = StatusRidingGreen, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Mapped: ${String.format("%.4f, %.4f", startCoords!!.latitude, startCoords!!.longitude)}",
                                    color = StatusRidingGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Search Results Dropdown List
                        if (showStartDropdown && startSearchResults.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SlateDark800)
                                    .border(1.dp, SlateDark700, RoundedCornerShape(10.dp))
                            ) {
                                for (place in startSearchResults.take(4)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                startLocationText = place.name
                                                startCoords = place.latLng
                                                rideName = "${place.name} → $destinationText"
                                                showStartDropdown = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(imageVector = Icons.Default.Place, contentDescription = null, tint = StatusRidingGreen, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(text = place.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text(text = place.displayName, color = Color(0xFF90A4AE), fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

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
                                destinationText = query
                                showDestDropdown = true
                                destDebounceJob?.cancel()
                                destDebounceJob = coroutineScope.launch {
                                    delay(250)
                                    isSearchingDest = true
                                    destSearchResults = placeSearchService.searchPlaces(query)
                                    isSearchingDest = false
                                }
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
                                    IconButton(onClick = { destinationText = ""; showDestDropdown = false }) {
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

                        // Coordinates Confirmation Badge
                        if (destCoords != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = AmberPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Mapped: ${String.format("%.4f, %.4f", destCoords!!.latitude, destCoords!!.longitude)}",
                                    color = AmberPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Search Results Dropdown List
                        if (showDestDropdown && destSearchResults.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SlateDark800)
                                    .border(1.dp, SlateDark700, RoundedCornerShape(10.dp))
                            ) {
                                for (place in destSearchResults.take(4)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                destinationText = place.name
                                                destCoords = place.latLng
                                                rideName = "$startLocationText → ${place.name}"
                                                showDestDropdown = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(imageVector = Icons.Default.Place, contentDescription = null, tint = AmberPrimary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(text = place.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text(text = place.displayName, color = Color(0xFF90A4AE), fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }
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
                Button(
                    onClick = {
                        val finalName = if (rideName.isBlank()) {
                            "${startLocationText.ifBlank { "Pune" }} → ${destinationText.ifBlank { "Lonavala" }}"
                        } else {
                            rideName
                        }
                        isCreating = true
                        onCreateRide(
                            finalName,
                            startLocationText,
                            destinationText,
                            startCoords,
                            destCoords
                        )
                    },
                    enabled = !isCreating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("submit_create_ride_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color.Black,
                        disabledContainerColor = AmberPrimary.copy(alpha = 0.6f),
                        disabledContentColor = Color.Black.copy(alpha = 0.7f)
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
