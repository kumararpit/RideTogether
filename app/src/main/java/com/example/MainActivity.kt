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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.ui.theme.AmberPrimary
import com.example.ui.screens.CreateRideScreen
import com.example.ui.screens.JoinRideScreen
import com.example.ui.screens.LiveRideScreen
import com.example.ui.screens.RideLobbyScreen
import com.example.ui.screens.RideSummaryScreen
import com.example.ui.screens.SignInScreen
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
                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    if (fineLocationGranted || coarseLocationGranted) {
                        viewModel.locationTracker.startTracking()
                    }
                }

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
    val motorcycleModel by viewModel.motorcycleModel.collectAsState()
    val ride by viewModel.currentRide.collectAsState()
    val members by viewModel.members.collectAsState()
    val joinError by viewModel.joinError.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val isAuthLoading by viewModel.isAuthLoading.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val context = LocalContext.current

    var pendingScreen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AppScreen?>(null) }

    LaunchedEffect(currentUser) {
        if (currentUser != null && currentScreen == AppScreen.SIGN_IN && pendingScreen != null) {
            val target = pendingScreen!!
            pendingScreen = null
            viewModel.navigateTo(target)
        }
    }

    when (currentScreen) {
        AppScreen.WELCOME -> {
            WelcomeScreen(
                currentName = userName,
                onNameChange = { viewModel.setUserName(it) },
                currentMotorcycle = motorcycleModel,
                onMotorcycleChange = { viewModel.setMotorcycleModel(it) },
                onCreateRideClick = {
                    if (currentUser != null) {
                        viewModel.navigateTo(AppScreen.CREATE_RIDE)
                    } else {
                        pendingScreen = AppScreen.CREATE_RIDE
                        viewModel.navigateTo(AppScreen.SIGN_IN)
                    }
                },
                onJoinRideClick = {
                    if (currentUser != null) {
                        viewModel.navigateTo(AppScreen.JOIN_RIDE)
                    } else {
                        pendingScreen = AppScreen.JOIN_RIDE
                        viewModel.navigateTo(AppScreen.SIGN_IN)
                    }
                },
                onSignOutClick = { viewModel.signOut(context) },
                isSignedIn = (currentUser != null),
                userEmail = currentUser?.email
            )
        }

        AppScreen.SIGN_IN -> {
            BackHandler {
                pendingScreen = null
                viewModel.navigateTo(AppScreen.WELCOME)
            }
            SignInScreen(
                isLoading = isAuthLoading,
                errorMessage = authError,
                onSignInClick = { viewModel.signInWithGoogle(context) },
                onBack = {
                    pendingScreen = null
                    viewModel.navigateTo(AppScreen.WELCOME)
                }
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
                },
                errorMessage = joinError
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
                    routePoints = viewModel.getRoutePoints(),
                    onStartRide = { viewModel.startRide() },
                    onBack = { viewModel.navigateTo(AppScreen.WELCOME) }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AmberPrimary)
                }
            }
        }

        AppScreen.LIVE_RIDE -> {
            BackHandler {
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
