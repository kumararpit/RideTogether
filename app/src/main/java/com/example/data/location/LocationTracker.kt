package com.example.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.model.LatLng
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

data class LocationUpdate(
    val latLng: LatLng,
    val speedKmh: Double,

    /**
     * Qualified heading (0..360) when available: GPS bearing, else course over
     * ground derived from the preceding fix. Null means "no reliable heading" —
     * consumers must preserve their previous heading, never substitute 0°.
     */
    val headingDeg: Float?,

    val accuracyMeters: Float,
    val timestampMs: Long = System.currentTimeMillis()
)

class LocationTimeoutException(message: String = "GPS location request timed out") : Exception(message)

/**
 * Global helper function to request a single location fix with an explicit timeout.
 */
suspend fun requestSingleLocationFix(
    context: Context,
    timeoutMs: Long = 10000L
): Result<LocationUpdate> {
    return LocationTracker(context).requestSingleLocationFix(context, timeoutMs)
}

class LocationTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<LocationUpdate?>(null)
    val currentLocation: StateFlow<LocationUpdate?> = _currentLocation.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _isGpsAvailable = MutableStateFlow(true)
    val isGpsAvailable: StateFlow<Boolean> = _isGpsAvailable.asStateFlow()

    // Last completed fix retained for course-over-ground derivation (Qualified Bearing).
    private var lastFixForCourse: Location? = null
    private var lastFixWallClockMs: Long = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: run {
                _isGpsAvailable.value = false
                return
            }
            _isGpsAvailable.value = true
            // Convert m/s to km/h (1 m/s = 3.6 km/h)
            val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).coerceAtLeast(0.0) else 0.0

            // Qualified Bearing: GPS bearing first; otherwise derive course over
            // ground from the preceding fix — never substitute 0° when missing.
            val prev = lastFixForCourse
            val prevTime = lastFixWallClockMs
            val nowMs = System.currentTimeMillis()
            val derivedHeading: Float? = if (prev == null) {
                null
            } else {
                val displacedEnough = prev.distanceTo(loc) >= CourseOverGround.MIN_DERIVATION_DISTANCE_METERS
                val elapsedEnough = (nowMs - prevTime) >= CourseOverGround.MIN_DERIVATION_INTERVAL_MS
                if (displacedEnough && elapsedEnough) {
                    CourseOverGround.derive(
                        prev = LatLng(prev.latitude, prev.longitude),
                        prevTimestampMs = prevTime,
                        current = LatLng(loc.latitude, loc.longitude),
                        currentTimestampMs = nowMs
                    )
                } else {
                    null
                }
            }
            val heading: Float? = when {
                loc.hasBearing() -> ((loc.bearing % 360f) + 360f) % 360f
                derivedHeading != null -> derivedHeading
                else -> null
            }
            lastFixForCourse = loc
            lastFixWallClockMs = nowMs

            _currentLocation.value = LocationUpdate(
                latLng = LatLng(loc.latitude, loc.longitude),
                speedKmh = speedKmh,
                headingDeg = heading,
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10f,
                timestampMs = loc.time
            )
        }
    }

    fun updateLocationForTesting(loc: LocationUpdate) {
        _currentLocation.value = loc
    }

    @SuppressLint("MissingPermission")
    fun startTracking(updateIntervalMs: Long = 4000L) {
        if (_isTracking.value) return
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMs)
                .setMinUpdateIntervalMillis(3000L)
                .build()

            fusedClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            _isTracking.value = true
            _isGpsAvailable.value = true
        } catch (e: SecurityException) {
            _isTracking.value = false
            _isGpsAvailable.value = false
        } catch (e: Exception) {
            _isTracking.value = false
            _isGpsAvailable.value = false
        }
    }

    fun stopTracking() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        _isTracking.value = false
    }

    /**
     * Requests a single location fix with an explicit timeout.
     * Guarantees that callbacks and cancellation tokens are cleaned up when complete, cancelled, or timed out.
     */
    @SuppressLint("MissingPermission")
    suspend fun requestSingleLocationFix(
        reqContext: Context = context,
        timeoutMs: Long = 10000L
    ): Result<LocationUpdate> = withContext(Dispatchers.IO) {
        val client = LocationServices.getFusedLocationProviderClient(reqContext)
        val cts = CancellationTokenSource()
        var registeredCallback: LocationCallback? = null

        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        cts.cancel()
                        registeredCallback?.let { cb ->
                            try {
                                client.removeLocationUpdates(cb)
                            } catch (_: Exception) {}
                        }
                    }

                    val hasFine = ContextCompat.checkSelfPermission(
                        reqContext,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        reqContext,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasFine && !hasCoarse) {
                        if (continuation.isActive) {
                            continuation.resume(
                                Result.failure(SecurityException("Location permission not granted"))
                            )
                        }
                        return@suspendCancellableCoroutine
                    }

                    val cached = currentLocation.value
                    if (cached != null && (System.currentTimeMillis() - cached.timestampMs) < 3000L) {
                        if (continuation.isActive) {
                            continuation.resume(Result.success(cached))
                        }
                        return@suspendCancellableCoroutine
                    }

                    client.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cts.token
                    ).addOnSuccessListener { location ->
                        if (location != null) {
                            val update = LocationUpdate(
                                latLng = LatLng(location.latitude, location.longitude),
                                speedKmh = if (location.hasSpeed()) (location.speed * 3.6).coerceAtLeast(0.0) else 0.0,
                                headingDeg = if (location.hasBearing()) ((location.bearing % 360f) + 360f) % 360f else null,
                                accuracyMeters = if (location.hasAccuracy()) location.accuracy else 10f,
                                timestampMs = location.time
                            )
                            if (continuation.isActive) {
                                continuation.resume(Result.success(update))
                            }
                        } else {
                            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                                .setMaxUpdates(1)
                                .build()

                            val cb = object : LocationCallback() {
                                override fun onLocationResult(res: LocationResult) {
                                    val loc = res.lastLocation
                                    try {
                                        client.removeLocationUpdates(this)
                                    } catch (_: Exception) {}
                                    if (loc != null) {
                                        val up = LocationUpdate(
                                            latLng = LatLng(loc.latitude, loc.longitude),
                                            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).coerceAtLeast(0.0) else 0.0,
                                            headingDeg = if (loc.hasBearing()) ((loc.bearing % 360f) + 360f) % 360f else null,
                                            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10f,
                                            timestampMs = loc.time
                                        )
                                        if (continuation.isActive) continuation.resume(Result.success(up))
                                    } else {
                                        if (continuation.isActive) continuation.resume(Result.failure(Exception("Null location fix")))
                                    }
                                }
                            }
                            registeredCallback = cb
                            try {
                                client.requestLocationUpdates(request, cb, Looper.getMainLooper())
                            } catch (e: Exception) {
                                if (continuation.isActive) continuation.resume(Result.failure(e))
                            }
                        }
                    }.addOnFailureListener { err ->
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(err))
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            cts.cancel()
            registeredCallback?.let { cb ->
                try {
                    client.removeLocationUpdates(cb)
                } catch (_: Exception) {}
            }
            Result.failure(LocationTimeoutException("GPS location request timed out after ${timeoutMs}ms"))
        } catch (e: Exception) {
            cts.cancel()
            registeredCallback?.let { cb ->
                try {
                    client.removeLocationUpdates(cb)
                } catch (_: Exception) {}
            }
            Result.failure(e)
        }
    }
}

