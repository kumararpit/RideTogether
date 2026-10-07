package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.location.LocationTracker
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.QuickMessage
import com.example.data.model.QuickMessageType
import com.example.data.model.Ride
import com.example.data.model.RiderMember
import com.example.data.model.SosEvent
import com.example.data.repository.RideRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    fun createRide(rideName: String, startLocation: String, destination: String) {
        val userLoc = locationTracker.currentLocation.value?.latLng ?: LatLng(18.5204, 73.8567)
        val destLoc = LatLng(userLoc.latitude + 0.23, userLoc.longitude - 0.45)

        repository.createRide(
            rideName = rideName.ifBlank { "Pune → Lonavala" },
            startName = startLocation.ifBlank { "Pune" },
            destName = destination.ifBlank { "Lonavala" },
            startLoc = userLoc,
            destLoc = destLoc
        )
        _currentScreen.value = AppScreen.RIDE_LOBBY
    }

    fun joinRideWithCode(code: String) {
        val trimmed = code.trim().uppercase(Locale.ROOT).ifBlank { "ABC123" }
        val userLoc = locationTracker.currentLocation.value?.latLng ?: LatLng(18.5204, 73.8567)
        repository.joinRide(trimmed, userLoc)
        _currentScreen.value = AppScreen.RIDE_LOBBY
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
        _currentScreen.value = AppScreen.LIVE_RIDE
    }

    fun endRide() {
        repository.endRide()
        _currentScreen.value = AppScreen.RIDE_SUMMARY
    }

    fun leaveRide() {
        repository.leaveRide()
        _selectedRider.value = null
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
        val ride = repository.currentRide.value ?: return emptyList()
        return listOf(ride.startLocation, ride.destinationLocation)
    }
}
