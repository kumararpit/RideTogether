package com.example.data.map

import com.example.data.location.OsrmRoutingService
import com.example.data.model.LatLng
import com.example.data.model.RiderMember
import com.example.data.model.RouteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.tan

interface MapProvider {
    fun showMap()
    fun addRiderMarker(rider: RiderMember)
    fun updateRiderMarker(rider: RiderMember)
    fun removeRiderMarker(riderId: String)
    fun drawRoute(points: List<LatLng>)
    fun setCenter(latLng: LatLng, zoom: Float)
    fun fitBounds(points: List<LatLng>)

    /**
     * Calculates road-following route from OSRM and updates route line.
     */
    suspend fun calculateAndDrawRoute(start: LatLng, destination: LatLng): RouteResult
}

/**
 * MapLibre / OpenStreetMap engine implementation with integrated OSRM road routing.
 */
class MapLibreMapProvider(
    private val routingService: OsrmRoutingService = OsrmRoutingService()
) : MapProvider {

    private val _routePoints = MutableStateFlow<List<LatLng>>(emptyList())
    val routePoints: StateFlow<List<LatLng>> = _routePoints.asStateFlow()

    private val _riderMarkers = MutableStateFlow<Map<String, RiderMember>>(emptyMap())
    val riderMarkers: StateFlow<Map<String, RiderMember>> = _riderMarkers.asStateFlow()

    private val _center = MutableStateFlow(LatLng(18.5204, 73.8567))
    val center: StateFlow<LatLng> = _center.asStateFlow()

    private val _zoom = MutableStateFlow(14f)
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    var isMapVisible: Boolean = false
        private set

    override fun showMap() {
        isMapVisible = true
    }

    override fun addRiderMarker(rider: RiderMember) {
        val current = _riderMarkers.value.toMutableMap()
        current[rider.id] = rider
        _riderMarkers.value = current
    }

    override fun updateRiderMarker(rider: RiderMember) {
        val current = _riderMarkers.value.toMutableMap()
        current[rider.id] = rider
        _riderMarkers.value = current
    }

    override fun removeRiderMarker(riderId: String) {
        val current = _riderMarkers.value.toMutableMap()
        current.remove(riderId)
        _riderMarkers.value = current
    }

    override fun drawRoute(points: List<LatLng>) {
        _routePoints.value = points
    }

    override fun setCenter(latLng: LatLng, zoom: Float) {
        _center.value = latLng
        _zoom.value = zoom
    }

    override fun fitBounds(points: List<LatLng>) {
        if (points.isNotEmpty()) {
            val avgLat = points.map { it.latitude }.average()
            val avgLng = points.map { it.longitude }.average()
            _center.value = LatLng(avgLat, avgLng)
        }
    }

    override suspend fun calculateAndDrawRoute(start: LatLng, destination: LatLng): RouteResult {
        val result = routingService.fetchRoute(start, destination)
        drawRoute(result.coordinates)
        return result
    }
}

object TileMath {
    // Compliant User-Agent header per OpenStreetMap Tile Usage Policy
    const val OSM_USER_AGENT = "RideTogether-Android-Personal-App/1.0 (arpitkumar1101@gmail.com)"

    fun lonToTileX(lon: Double, zoom: Int): Double {
        val n = 1 shl zoom
        return (lon + 180.0) / 360.0 * n
    }

    fun latToTileY(lat: Double, zoom: Int): Double {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - asinh(tan(latRad)) / PI) / 2.0 * n
    }

    fun tileXToLon(x: Double, zoom: Int): Double {
        val n = 1 shl zoom
        return x / n * 360.0 - 180.0
    }

    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = 1 shl zoom
        val n2 = PI - (2.0 * PI * y) / n
        return Math.toDegrees(atan(sinh(n2)))
    }

    fun getOsmTileUrl(zoom: Int, x: Int, y: Int): String {
        val subdomains = listOf("a", "b", "c")
        val sub = subdomains[Math.floorMod(x + y, subdomains.size)]
        return "https://$sub.tile.openstreetmap.org/$zoom/$x/$y.png"
    }

    fun getTopoTileUrl(zoom: Int, x: Int, y: Int): String {
        val subdomains = listOf("a", "b", "c")
        val sub = subdomains[Math.floorMod(x + y, subdomains.size)]
        return "https://$sub.tile.opentopomap.org/$zoom/$x/$y.png"
    }
}
