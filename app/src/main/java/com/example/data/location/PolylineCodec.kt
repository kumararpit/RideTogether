package com.example.data.location

import com.example.data.model.LatLng
import kotlin.math.abs
import kotlin.math.round

/**
 * Decoder/encoder for the **encoded polyline** format (the same format Google
 * Maps uses for route geometry, precision 5 by default).
 *
 * Why this exists: the routing engine can return the overview geometry either
 * as a GeoJSON coordinate array (verbose) or as an encoded polyline string
 * (compact, ~5-8x smaller). The encoded form is what the reference
 * implementation carries on the wire, so adopting it keeps the payloads small
 * and the geometry interchangeable with the reference format.
 *
 * The format: signed integers, each split into 5-bit chunks with a sign
 * extension (value << 1, negated for negatives), written as ASCII characters
 * offset by 63 and joined with ';'; each polyline is a sequence of lat/lng
 * pairs separated by ','.
 */
object PolylineCodec {

    /** Precision 5 = ~1.1 m at the equator (the usual web-service default). */
    const val DEFAULT_PRECISION: Int = 5

    /**
     * Decodes an encoded polyline into [LatLng] points (latitude first).
     *
     * Malformed input never throws: the decoder stops at the first
     * undecodable chunk and returns the points decoded so far. A truncated or
     * corrupted geometry therefore degrades to a shorter route instead of
     * crashing the ride.
     */
    fun decode(encoded: String, precision: Int = DEFAULT_PRECISION): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val factor = Math.pow(10.0, precision.toDouble())
        val points = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var byte: Int
            do {
                if (index >= encoded.length) return points
                byte = encoded[index].code - 63
                index++
                if (byte < 0) return points
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            shift = 0
            result = 0
            do {
                if (index >= encoded.length) return points
                byte = encoded[index].code - 63
                index++
                if (byte < 0) return points
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            points.add(LatLng(lat / factor, lng / factor))
        }
        return points
    }

    /**
     * Encodes points into the encoded-polyline format (inverse of [decode]).
     * Used by tests and by the offline fallback path; the production request
     * asks the engine for the encoded form directly.
     */
    fun encode(points: List<LatLng>, precision: Int = DEFAULT_PRECISION): String {
        if (points.isEmpty()) return ""
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for (point in points) {
            val lat = round(point.latitude * factor).toLong()
            val lng = round(point.longitude * factor).toLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lng - prevLng, sb)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }

    /** True when the two polylines describe the same vertices (test helper). */
    fun samePoints(a: List<LatLng>, b: List<LatLng>, tolerance: Double = 1e-6): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { i ->
            abs(a[i].latitude - b[i].latitude) < tolerance &&
                abs(a[i].longitude - b[i].longitude) < tolerance
        }
    }
}
