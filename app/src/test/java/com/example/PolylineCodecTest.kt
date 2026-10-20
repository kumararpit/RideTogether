package com.example

import com.example.data.location.PolylineCodec
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encoded-polyline codec contract.
 *
 * The reference implementation carries route geometry as an encoded polyline;
 * the app requests the same encoding from the routing engine, so the decoder
 * must reproduce the documented vectors exactly and must never throw on a
 * corrupted payload (a truncated geometry degrades to a shorter route instead
 * of crashing the ride).
 */
class PolylineCodecTest {

    @Test
    fun `decodes the documented reference vectors`() {
        val decoded = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].latitude, 1e-5)
        assertEquals(-120.2, decoded[0].longitude, 1e-5)
        assertEquals(40.7, decoded[1].latitude, 1e-5)
        assertEquals(-120.95, decoded[1].longitude, 1e-5)
        assertEquals(43.252, decoded[2].latitude, 1e-5)
        assertEquals(-126.453, decoded[2].longitude, 1e-5)
    }

    @Test
    fun `round trips a route polyline`() {
        val points = listOf(
            LatLng(18.5204, 73.8567),
            LatLng(18.5300, 73.8600),
            LatLng(18.5410, 73.8450),
            LatLng(18.5500, 73.8300)
        )
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertEquals(points.size, decoded.size)
        assertTrue(PolylineCodec.samePoints(points, decoded, 1e-5))
    }

    @Test
    fun `round trips negative and fractional coordinates`() {
        val points = listOf(
            LatLng(-33.8688, 151.2093),
            LatLng(-33.8700, 151.2100),
            LatLng(-33.8725, 151.2080)
        )
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertTrue(PolylineCodec.samePoints(points, decoded, 1e-5))
    }

    @Test
    fun `empty input decodes to no points`() {
        assertTrue(PolylineCodec.decode("").isEmpty())
        assertEquals("", PolylineCodec.encode(emptyList()))
    }

    @Test
    fun `corrupted payload degrades instead of throwing`() {
        // A truncated payload must yield the points decoded so far.
        val decoded = PolylineCodec.decode("_p~iF~ps|U_ulL")
        assertTrue(decoded.size <= 2)
        // An out-of-range character must not crash the decoder either.
        val garbage = PolylineCodec.decode("\u0000\u0001\u00ff")
        assertTrue(garbage.isEmpty() || garbage.size == 1)
    }
}
