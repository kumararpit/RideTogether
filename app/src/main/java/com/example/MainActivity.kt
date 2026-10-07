package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.screens.CreateRideScreen
import com.example.ui.screens.JoinRideScreen
import com.example.ui.screens.LiveRideScreen
import com.example.ui.screens.RideLobbyScreen
import com.example.ui.screens.RideSummaryScreen
import com.example.ui.screens.WelcomeScreen
import com.example.ui.theme.RideTogetherTheme
import com.example.ui.theme.SlateDark900
import com.example.ui.viewmodel.AppScreen
import com.example.ui.viewmodel.RideViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: RideViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RideTogetherTheme(darkTheme = true) {
                // Location permission launcher
                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    if (fineLocationGranted || coarseLocationGranted) {
                        viewModel.locationTracker.startTracking()
                    }
                }

                // Check permissions on start
                LaunchedEffect(Unit) {
                    val hasFine = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasFine) {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else {
                        viewModel.locationTracker.startTracking()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SlateDark900
                ) {
                    RideTogetherApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun RideTogetherApp(viewModel: RideViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val ride by viewModel.currentRide.collectAsState()
    val members by viewModel.members.collectAsState()

    when (currentScreen) {
        AppScreen.WELCOME -> {
            WelcomeScreen(
                currentName = userName,
                onNameChange = { viewModel.setUserName(it) },
                onCreateRideClick = { viewModel.navigateTo(AppScreen.CREATE_RIDE) },
                onJoinRideClick = { viewModel.navigateTo(AppScreen.JOIN_RIDE) }
            )
        }

        AppScreen.CREATE_RIDE -> {
            BackHandler {
                viewModel.navigateTo(AppScreen.WELCOME)
            }
            CreateRideScreen(
                onBack = { viewModel.navigateTo(AppScreen.WELCOME) },
                onCreateRide = { name, start, dest, startCoords, destCoords ->
                    viewModel.createRide(name, start, dest, startCoords, destCoords)
                },
                currentGpsLocation = viewModel.locationTracker.currentLocation.value?.latLng,
                placeSearchService = viewModel.placeSearchService
            )
        }

        AppScreen.JOIN_RIDE -> {
            BackHandler {
                viewModel.navigateTo(AppScreen.WELCOME)
            }
            JoinRideScreen(
                onBack = { viewModel.navigateTo(AppScreen.WELCOME) },
                onJoinRide = { code ->
                    viewModel.joinRideWithCode(code)
                }
            )
        }

        AppScreen.RIDE_LOBBY -> {
            BackHandler {
                viewModel.navigateTo(AppScreen.WELCOME)
            }
            if (ride != null) {
                RideLobbyScreen(
                    ride = ride!!,
                    members = members,
                    onStartRide = { viewModel.startRide() },
                    onBack = { viewModel.navigateTo(AppScreen.WELCOME) }
                )
            } else {
                viewModel.navigateTo(AppScreen.WELCOME)
            }
        }

        AppScreen.LIVE_RIDE -> {
            BackHandler {
                // Prevent accidental back exit while riding; open settings to leave
                viewModel.openSettingsSheet(true)
            }
            LiveRideScreen(viewModel = viewModel)
        }

        AppScreen.RIDE_SUMMARY -> {
            BackHandler {
                viewModel.leaveRide()
            }
            RideSummaryScreen(
                ride = ride,
                members = members,
                onBackToHome = { viewModel.leaveRide() }
            )
        }
    }
}
