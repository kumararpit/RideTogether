package com.example.data.map

import com.example.data.model.LatLng
import com.example.data.model.RiderMember
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
}

object TileMath {
    // Compliant User-Agent header per OSM Tile Usage Policy
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
        return "https://tile.openstreetmap.org/$zoom/$x/$y.png"
    }

    fun getCartoDarkTileUrl(zoom: Int, x: Int, y: Int): String {
        // High contrast dark adventure style
        val subdomains = listOf("a", "b", "c", "d")
        val sub = subdomains[(x + y) % subdomains.size]
        return "https://cartodb-basemaps-$sub.global.ssl.fastly.net/dark_all/$zoom/$x/$y.png"
    }
}
