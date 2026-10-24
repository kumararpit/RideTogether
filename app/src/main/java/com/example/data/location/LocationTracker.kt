package com.example.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.model.HeadingSource
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
     * Qualified heading (0..360) when available: a GNSS course that passed the
     * speed / accuracy / age gate, or a course derived from the preceding fix.
     *
     * Null means "no reliable heading" — consumers must preserve their previous
     * heading, never substitute 0° (north). Google Maps deletes the bearing at
     * the source in exactly this situation (`aizg.k()`).
     */
    val headingDeg: Float?,

    val accuracyMeters: Float,
    val timestampMs: Long = System.currentTimeMillis(),

    /** Which estimator produced [headingDeg]. */
    val headingSource: HeadingSource =
        if (headingDeg != null) HeadingSource.GNSS_COURSE else HeadingSource.UNAVAILABLE,

    /** Expected angular error in degrees, or null when the source cannot report one. */
    val headingAccuracyDeg: Float? = null,

    /** Speed uncertainty reported by the provider (m/s), when available. */
    val speedAccuracyMps: Float? = null,

    /**
     * Independent activity-recognition "moving" signal. Google Maps uses this to
     * lower the 3.0 m/s course floor to ~1.0 m/s for walking/pushing.
     */
    val activityMoving: Boolean = false
) {
    /** Convenience view of the heading with its provenance. */
    fun toHeadingEstimate(): com.example.data.model.HeadingEstimate? =
        headingDeg?.let {
            com.example.data.model.HeadingEstimate(
                degrees = it,
                source = headingSource,
                accuracyDeg = headingAccuracyDeg,
                timestampMs = timestampMs
            )
        }
}

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

            val nowMs = System.currentTimeMillis()
            val previous = lastFixForCourse
            val previousTime = lastFixWallClockMs
            val activityMoving = ActivityRecognitionMonitor.isMoving
            val resolved = resolveHeading(
                loc = loc,
                previous = previous,
                previousTimeMs = previousTime,
                nowMs = nowMs,
                activityMoving = activityMoving
            )

            lastFixForCourse = loc
            lastFixWallClockMs = nowMs

            _currentLocation.value = LocationUpdate(
                latLng = LatLng(loc.latitude, loc.longitude),
                speedKmh = speedKmh,
                headingDeg = resolved.headingDeg,
                accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10f,
                timestampMs = if (loc.time > 0L) loc.time else nowMs,
                headingSource = resolved.source,
                headingAccuracyDeg = resolved.accuracyDeg,
                speedAccuracyMps = resolved.speedAccuracyMps,
                activityMoving = activityMoving
            )
        }
    }

    /**
     * Resolves the qualified heading for one fix.
     *
     * Order matches Google Maps: a GNSS course that passes the gate wins;
     * otherwise a course derived from the previous fix is used if it also passes
     * the gate; otherwise the heading is *absent* (never 0°/north).
     */
    private fun resolveHeading(
        loc: Location,
        previous: Location?,
        previousTimeMs: Long,
        nowMs: Long,
        activityMoving: Boolean = false
    ): ResolvedHeading {
        val speedMps = if (loc.hasSpeed()) loc.speed else 0f
        val speedAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            loc.hasSpeedAccuracy()
        ) {
            loc.speedAccuracyMetersPerSecond
        } else {
            null
        }
        val bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            loc.hasBearingAccuracy()
        ) {
            loc.bearingAccuracyDegrees
        } else {
            null
        }
        val fixAgeMs = if (loc.time > 0L) (nowMs - loc.time).coerceAtLeast(0L) else 0L
        val bearing = if (loc.hasBearing()) loc.bearing else null

        val gnssQualified = BearingGate.qualifiesCourse(
            headingDeg = bearing,
            speedMps = speedMps,
            bearingAccuracyDeg = bearingAccuracy,
            speedAccuracyMps = speedAccuracy,
            activityMoving = activityMoving,
            ageMs = fixAgeMs
        )
        if (gnssQualified && bearing != null) {
            return ResolvedHeading(
                headingDeg = HeadingMath.normalize360(bearing),
                source = HeadingSource.GNSS_COURSE,
                accuracyDeg = bearingAccuracy,
                speedAccuracyMps = speedAccuracy,
                activityMoving = activityMoving
            )
        }

        if (previous != null && BearingGate.qualifiesDerived(speedMps, fixAgeMs)) {
            val distanceMeters = previous.distanceTo(loc).toDouble()
            val derived = CourseOverGround.derive(
                prev = LatLng(previous.latitude, previous.longitude),
                prevTimestampMs = previousTimeMs,
                current = LatLng(loc.latitude, loc.longitude),
                currentTimestampMs = nowMs
            )
            if (derived != null) {
                return ResolvedHeading(
                    headingDeg = derived,
                    source = HeadingSource.DERIVED_COURSE,
                    accuracyDeg = CourseOverGround.estimatedBearingAccuracyDeg(
                        positionAccuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10f,
                        distanceMeters = distanceMeters
                    ),
                    speedAccuracyMps = speedAccuracy,
                    activityMoving = activityMoving
                )
            }
        }

        return ResolvedHeading(
            headingDeg = null,
            source = HeadingSource.UNAVAILABLE,
            accuracyDeg = null,
            speedAccuracyMps = speedAccuracy,
            activityMoving = activityMoving
        )
    }

    private data class ResolvedHeading(
        val headingDeg: Float?,
        val source: HeadingSource,
        val accuracyDeg: Float?,
        val speedAccuracyMps: Float?,
        val activityMoving: Boolean = false
    )

    /** Single-shot fix used by the "use my location" flows. Same gate as tracking. */
    private fun buildSingleFixUpdate(loc: Location): LocationUpdate {
        val nowMs = System.currentTimeMillis()
        val activityMoving = ActivityRecognitionMonitor.isMoving
        val resolved = resolveHeading(
            loc = loc,
            previous = null,
            previousTimeMs = 0L,
            nowMs = nowMs,
            activityMoving = activityMoving
        )
        return LocationUpdate(
            latLng = LatLng(loc.latitude, loc.longitude),
            speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6).coerceAtLeast(0.0) else 0.0,
            headingDeg = resolved.headingDeg,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 10f,
            timestampMs = if (loc.time > 0L) loc.time else nowMs,
            headingSource = resolved.source,
            headingAccuracyDeg = resolved.accuracyDeg,
            speedAccuracyMps = resolved.speedAccuracyMps,
            activityMoving = activityMoving
        )
    }

    fun updateLocationForTesting(loc: LocationUpdate) {
        _currentLocation.value = loc
    }

    @SuppressLint("MissingPermission")
    fun startTracking(updateIntervalMs: Long = 4000L) {
        if (_isTracking.value) return
        // Optional accelerator: without the ACTIVITY_RECOGNITION permission this
        // silently no-ops and the heading gate falls back to speed alone.
        ActivityRecognitionMonitor.start(context)
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
        ActivityRecognitionMonitor.stop(context)
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
                            val update = buildSingleFixUpdate(location)
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
                                        val up = buildSingleFixUpdate(loc)
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

