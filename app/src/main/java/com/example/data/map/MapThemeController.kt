package com.example.data.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone

/**
 * Single owner of the resolved map lighting state (Task 18).
 *
 * Location priority (documented product order) when resolving AUTO lighting:
 *  1. Valid current rider location (validated bounds/accuracy/staleness here).
 *  2. Valid map-camera center (fed from RideMapCanvas on *material* change only).
 *  3. Valid route endpoint (origin, else destination).
 *  4. Documented local-clock fallback based on the device timezone.
 *
 * Guarantees:
 *  - No recompute on every GPS update: material-change threshold is ~0.1° (~11 km).
 *  - Transitions are lifecycle-aware single scheduled recomputes (no timers/loops):
 *    the next solar-boundary crossing schedules exactly one delayed recomputation.
 *  - Manual DAY/NIGHT overrides resolve immediately and never consult solar math.
 *  - Theme state never resets cameras, caches, tile identity or navigation.
 */
class MapThemeController(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deviceZoneOffsetSeconds: (utcMillis: Long) -> Int =
        { TimeZone.getDefault().getOffset(it) / 1000 }
) {

    companion object {
        /** Material-change threshold in degrees (~11 km) — avoids per-GPS-update churn. */
        const val MATERIAL_CHANGE_DEG = 0.1
        const val RIDER_MAX_ACCURACY_METERS = 500f
        const val RIDER_MAX_FIX_AGE_MILLIS = 10L * 60_000L
        private const val RESCHEDULE_GRACE_MILLIS = 100L
    }

    private data class LocationCandidate(val lat: Double, val lng: Double)

    private val _mode = MutableStateFlow(MapThemeMode.AUTO)
    val mode: StateFlow<MapThemeMode> = _mode.asStateFlow()

    /** Preserves the pre-existing dark default until the first authoritative resolve. */
    private val _lighting = MutableStateFlow(MapLighting.NIGHT)
    val lighting: StateFlow<MapLighting> = _lighting.asStateFlow()

    private var riderLocation: LocationCandidate? = null
    private var cameraLocation: LocationCandidate? = null
    private var endpointLocation: LocationCandidate? = null

    private var lastFingerprint: String? = null
    private var pendingTransitionJob: Job? = null

    fun setMode(newMode: MapThemeMode) {
        if (_mode.value == newMode) return
        _mode.value = newMode
        recompute()
    }

    /** Rider fix; invalid/stale/inaccurate fixes never replace a previously valid one. */
    fun onRiderLocation(lat: Double, lng: Double, accuracyMeters: Float, fixAgeMillis: Long) {
        if (!isValidFix(lat, lng, accuracyMeters, fixAgeMillis)) return
        val next = LocationCandidate(lat, lng)
        val previous = riderLocation
        if (previous != null && !isMaterialChange(previous, next)) return
        riderLocation = next
        recompute()
    }

    /** Camera center from the map canvas (reporting is already material-change-gated). */
    fun onCameraCenter(lat: Double, lng: Double) {
        if (!isValidBounds(lat, lng)) return
        val next = LocationCandidate(lat, lng)
        val previous = cameraLocation
        if (previous != null && !isMaterialChange(previous, next)) {
            cameraLocation = next
            return
        }
        cameraLocation = next
        recompute()
    }

    /** Route origin preferred; destination used when the origin is not available. */
    fun onRouteEndpoints(
        startLat: Double?, startLng: Double?,
        destLat: Double?, destLng: Double?
    ) {
        val next = when {
            startLat != null && startLng != null && isValidBounds(startLat, startLng) ->
                LocationCandidate(startLat, startLng)
            destLat != null && destLng != null && isValidBounds(destLat, destLng) ->
                LocationCandidate(destLat, destLng)
            else -> null
        }
        val previous = endpointLocation
        if (previous == next) return
        if (previous != null && next != null && !isMaterialChange(previous, next)) return
        endpointLocation = next
        recompute()
    }

    fun onResumed() {
        recompute()
    }

    fun dispose() {
        pendingTransitionJob?.cancel()
        pendingTransitionJob = null
    }

    init {
        // Resolve once at construction so the renderer always receives a concrete
        // appearance (timezone fallback until a geographic context arrives) instead
        // of the dark placeholder default.
        recompute()
    }

    /**
     * Immediate manual-override / context resolution, scheduling at most one delayed
     * recomputation at the next solar boundary (or a daily recheck for polar windows).
     */
    private fun recompute() {
        pendingTransitionJob?.cancel()
        pendingTransitionJob = null

        val now = nowMillis()
        val geo = resolveGeoCandidate()
        val resolved = MapLightingResolver.resolveDetailed(
            mode = _mode.value,
            latDeg = geo?.lat,
            lngDeg = geo?.lng,
            utcMillis = now,
            deviceZoneOffsetSeconds = deviceZoneOffsetSeconds(now)
        )

        val localEpochDay = floorDiv(now + zoneOffsetForDay(geo, now) * 1000L, 86_400_000L)
        val fingerprint = listOfNotNull(
            _mode.value.name,
            geo?.lat?.let { (it * 10).toInt().toString() },
            geo?.lng?.let { (it * 10).toInt().toString() },
            localEpochDay.toString()
        ).joinToString("|")
        lastFingerprint = fingerprint

        _lighting.value = resolved.lighting
        // Manual DAY/NIGHT overrides resolve immediately and never depend on solar
        // scheduling; their lighting only changes when the mode or context changes.
        if (_mode.value == MapThemeMode.AUTO) {
            scheduleNextTransition(now, resolved.nextTransitionUtcMillis, geo)
        }
    }

    private fun zoneOffsetForDay(geo: LocationCandidate?, now: Long): Int {
        // Date-material fingerprints use the map location's longitude band when
        // available (a rider may cross timezones); otherwise the device timezone.
        return if (geo != null && _mode.value == MapThemeMode.AUTO) {
            MapLightingResolver.approximateZoneOffsetSeconds(geo.lng)
        } else {
            deviceZoneOffsetSeconds(now)
        }
    }

    private fun scheduleNextTransition(now: Long, transitionMillis: Long?, geo: LocationCandidate?) {
        val delayMillis: Long = when {
            transitionMillis != null && transitionMillis > now ->
                transitionMillis - now + RESCHEDULE_GRACE_MILLIS
            else -> {
                // Polar window (no crossing within the scan horizon): one daily recheck
                // anchored to the next local midnight of the rendering location.
                val offsetSec = zoneOffsetForDay(geo, now)
                val currentDayStart = floorDiv(now + offsetSec * 1000L, 86_400_000L) * 86_400_000L
                val nextLocalMidnightUtc = currentDayStart + 86_400_000L - offsetSec * 1000L
                (nextLocalMidnightUtc - now).coerceIn(60_000L, 48L * 3_600_000L)
            }
        }
        pendingTransitionJob = scope.launch {
            delay(delayMillis)
            pendingTransitionJob = null
            recompute()
        }
    }

    private fun resolveGeoCandidate(): LocationCandidate? {
        // Priority: rider > camera > route endpoint (all already validated on entry).
        return riderLocation ?: cameraLocation ?: endpointLocation
    }

    private fun isValidFix(lat: Double, lng: Double, accuracyMeters: Float, fixAgeMillis: Long): Boolean {
        if (!isValidBounds(lat, lng)) return false
        if (accuracyMeters !in 0f..RIDER_MAX_ACCURACY_METERS) return false
        if (fixAgeMillis < 0 || fixAgeMillis > RIDER_MAX_FIX_AGE_MILLIS) return false
        // Reject the fabricated (0,0): exact zero/zero is never a genuine rider position.
        if (lat == 0.0 && lng == 0.0) return false
        return true
    }

    private fun isValidBounds(lat: Double, lng: Double): Boolean {
        if (!lat.isFinite() || !lng.isFinite()) return false
        return lat in -85.0..85.0 && lng in -180.0..180.0
    }

    private fun isMaterialChange(a: LocationCandidate, b: LocationCandidate): Boolean {
        val dLat = kotlin.math.abs(a.lat - b.lat)
        val dLng = kotlin.math.abs(a.lng - b.lng)
        return dLat >= MATERIAL_CHANGE_DEG || dLng >= MATERIAL_CHANGE_DEG
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        val r = a % b
        return if (r < 0) q - 1 else q
    }
}
