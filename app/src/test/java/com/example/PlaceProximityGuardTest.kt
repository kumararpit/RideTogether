package com.example

import com.example.data.model.LatLng
import com.example.data.place.PlaceProximityGuard
import com.example.data.place.PlaceSource
import com.example.data.place.SelectedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Advisory proximity guard (Phase B, backlog B-05; audit F6-D1 / F6-D2).
 *
 * Contract:
 *  1. a selection far from the reference point is FLAGGED, with the distance
 *     named in the message;
 *  2. a plausible selection is never flagged;
 *  3. the guard is **advisory only** — it never changes a coordinate and never
 *     blocks a legitimate long-distance trip.
 */
class PlaceProximityGuardTest {

    private val pune = LatLng(18.5204, 73.8567)

    private fun place(name: String, lat: Double, lng: Double) = SelectedPlace(
        id = "id-$name",
        name = name,
        address = "Somewhere",
        latitude = lat,
        longitude = lng,
        source = PlaceSource.PHOTON
    )

    @Test
    fun `a distant same-name place is flagged with its distance`() {
        // "Pavana" in Italy — the exact failure observed on device (18,066 km).
        val italy = place("Pavana", 44.1210, 11.0008)
        val warning = PlaceProximityGuard.warningFor(pune, italy)

        assertNotNull("a cross-country match must be flagged", warning)
        assertTrue("the warning must name the distance: $warning", warning!!.contains("km away"))
    }

    @Test
    fun `a nearby place is never flagged`() {
        // Lonavala from Pune — the normal case.
        val lonavala = place("Lonavala", 18.7549, 73.4017)
        assertNull(PlaceProximityGuard.warningFor(pune, lonavala))
    }

    @Test
    fun `a legitimate long-distance trip is advisory not blocked`() {
        // Pune → Leh (~1,600 km) is a real motorcycle tour: it is flagged, but
        // the guard returns a MESSAGE, never a rejection — the caller decides.
        val leh = place("Leh", 34.1526, 77.5771)
        val warning = PlaceProximityGuard.warningFor(pune, leh)
        assertNotNull("a long trip may be flagged", warning)
        // The place itself is untouched — no coordinate rewriting anywhere.
        assertEquals(34.1526, leh.latitude, 0.0)
        assertEquals(77.5771, leh.longitude, 0.0)
    }

    @Test
    fun `the threshold is one thousand kilometres`() {
        assertEquals(1_000_000.0, PlaceProximityGuard.DEFAULT_THRESHOLD_METERS, 0.0)
        assertFalse(PlaceProximityGuard.isImplausiblyDistant(999_000.0))
        assertTrue(PlaceProximityGuard.isImplausiblyDistant(1_001_000.0))
    }

    @Test
    fun `a missing reference point or place yields no warning`() {
        assertNull(PlaceProximityGuard.warningFor(null, place("X", 44.0, 11.0)))
        assertNull(PlaceProximityGuard.warningFor(pune, null))
    }

    @Test
    fun `the distance is measured from the reference point`() {
        val lonavala = place("Lonavala", 18.7549, 73.4017)
        val meters = PlaceProximityGuard.distanceMeters(pune, lonavala)
        // Pune → Lonavala is ~50 km.
        assertTrue("expected ~50 km, got $meters", meters in 45_000.0..60_000.0)
    }

    @Test
    fun `the warning text is human readable for both magnitudes`() {
        val near = PlaceProximityGuard.warningText(1_500_000.0)
        assertTrue(near.contains("1,500 km"))
        val far = PlaceProximityGuard.warningText(9_012_000.0)
        assertTrue(far.contains("9,012 km"))
    }
}
