package com.example.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.example.data.model.LatLng
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocationUpdate(
    val latLng: LatLng,
    val speedKmh: Double,
    val headingDeg: Float,
    val accuracyMeters: Float,
    val timestampMs: Long = System.currentTimeMillis()
)

class LocationTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<LocationUpdate?>(null)
    val currentLocation: StateFlow<LocationUpdate?> = _currentLocation.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            // Convert m/s to km/h (1 m/s = 3.6 km/h)
            val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).coerceAtLeast(0.0) else 0.0
            val heading = if (loc.hasBearing()) loc.bearing else 0f

            _currentLocation.value = LocationUpdate(
                latLng = LatLng(loc.latitude, loc.longitude),
                speedKmh = speedKmh,
                headingDeg = heading,
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10f,
                timestampMs = loc.time
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(updateIntervalMs: Long = 5000L) {
        if (_isTracking.value) return
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMs)
                .setMinUpdateIntervalMillis(3000L)
                .setMinUpdateDistanceMeters(10f)
                .build()

            fusedClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            _isTracking.value = true
        } catch (e: SecurityException) {
            _isTracking.value = false
        } catch (e: Exception) {
            _isTracking.value = false
        }
    }

    fun stopTracking() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        _isTracking.value = false
    }
}
