package com.example

import com.example.ui.map.MapChromeInsets
import com.example.ui.map.MapOverlayContract
import com.example.ui.map.MapOverlayLayer
import com.example.ui.map.RiderMarkerStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Overlay contract, chrome model and rider-marker presentation rules
 * (Phase B, backlog B-15 / B-16 / B-17).
 */
class MapOverlayContractTest {

    @Test
    fun `the draw order is total and stable`() {
        val order = MapOverlayContract.drawOrder
        assertEquals("every layer must appear exactly once", order.size, order.toSet().size)
        assertEquals("every declared layer must be in the order", MapOverlayLayer.entries.size, order.size)
        // The order must not change between reads.
        assertEquals(order, MapOverlayContract.drawOrder)
    }

    @Test
    fun `map content is drawn below the chrome`() {
        assertTrue(
            "the route must be below the banner",
            MapOverlayContract.isAbove(MapOverlayLayer.MANEUVER_BANNER, MapOverlayLayer.ROUTE)
        )
        assertTrue(
            "riders are above the route",
            MapOverlayContract.isAbove(MapOverlayLayer.RIDERS, MapOverlayLayer.ROUTE)
        )
        assertTrue(
            "sheets are on top of everything",
            MapOverlayContract.isAbove(MapOverlayLayer.SHEETS, MapOverlayLayer.MAP_CONTROLS)
        )
        assertFalse(MapOverlayContract.isAbove(MapOverlayLayer.ROUTE, MapOverlayLayer.RIDERS))
    }

    @Test
    fun `the chrome layers are exactly the banner, the HUD and the controls`() {
        val chrome = MapOverlayLayer.entries.filter { MapOverlayContract.isChrome(it) }
        assertEquals(
            listOf(
                MapOverlayLayer.MANEUVER_BANNER,
                MapOverlayLayer.TRIP_HUD,
                MapOverlayLayer.MAP_CONTROLS
            ),
            chrome
        )
    }

    @Test
    fun `chrome insets detect a label hidden under the banner or the HUD`() {
        // A 1080x2400 map surface: the banner occupies the top 200 px, the trip
        // HUD the bottom 400 px, the control column the right 100 px.
        val insets = MapChromeInsets(topPx = 200f, bottomPx = 400f, rightPx = 100f)
        assertTrue(
            "under the banner",
            insets.overlaps(x = 10f, y = 10f, width = 50f, height = 20f, viewportWidth = 1080f, viewportHeight = 2400f)
        )
        assertTrue(
            "under the HUD (the HUD band starts at 2400 − 400 = 2000 px)",
            insets.overlaps(x = 10f, y = 2050f, width = 50f, height = 20f, viewportWidth = 1080f, viewportHeight = 2400f)
        )
        assertFalse(
            "just above the HUD band",
            insets.overlaps(x = 10f, y = 1900f, width = 50f, height = 20f, viewportWidth = 1080f, viewportHeight = 2400f)
        )
        assertFalse(
            "in the free area",
            insets.overlaps(x = 10f, y = 400f, width = 50f, height = 20f, viewportWidth = 1080f, viewportHeight = 2400f)
        )
    }

    @Test
    fun `empty insets never suppress anything`() {
        val none = MapChromeInsets.None
        assertFalse(
            none.overlaps(x = 0f, y = 0f, width = 10f, height = 10f, viewportWidth = 1080f, viewportHeight = 2400f)
        )
    }
}

class RiderMarkerStyleTest {

    @Test
    fun `accuracy buckets follow the fix quality`() {
        assertEquals(RiderMarkerStyle.AccuracyBucket.GOOD, RiderMarkerStyle.accuracyBucket(5f))
        assertEquals(RiderMarkerStyle.AccuracyBucket.FAIR, RiderMarkerStyle.accuracyBucket(20f))
        assertEquals(RiderMarkerStyle.AccuracyBucket.POOR, RiderMarkerStyle.accuracyBucket(60f))
        assertEquals(RiderMarkerStyle.AccuracyBucket.VERY_POOR, RiderMarkerStyle.accuracyBucket(250f))
    }

    @Test
    fun `a good fix draws no accuracy ring, a coarse one does`() {
        assertEquals(0f, RiderMarkerStyle.accuracyRingAlpha(5f), 0.001f)
        assertTrue(RiderMarkerStyle.accuracyRingAlpha(20f) > 0f)
        assertTrue(
            "a coarser fix must have a stronger ring",
            RiderMarkerStyle.accuracyRingAlpha(250f) > RiderMarkerStyle.accuracyRingAlpha(20f)
        )
    }

    @Test
    fun `freshness buckets and the stale style`() {
        assertEquals(RiderMarkerStyle.FreshnessBucket.LIVE, RiderMarkerStyle.freshnessBucket(5_000L))
        assertEquals(RiderMarkerStyle.FreshnessBucket.AGED, RiderMarkerStyle.freshnessBucket(30_000L))
        assertEquals(RiderMarkerStyle.FreshnessBucket.OLD, RiderMarkerStyle.freshnessBucket(120_000L))
        assertEquals(RiderMarkerStyle.FreshnessBucket.STALE, RiderMarkerStyle.freshnessBucket(600_000L))
        assertFalse(RiderMarkerStyle.isStaleStyle(10_000L))
        assertTrue(RiderMarkerStyle.isStaleStyle(600_000L))
    }

    @Test
    fun `the last seen label appears only for old riders`() {
        assertNull(RiderMarkerStyle.lastSeenLabel(30_000L))
        assertEquals("5 min ago", RiderMarkerStyle.lastSeenLabel(300_000L))
        assertEquals("2 h ago", RiderMarkerStyle.lastSeenLabel(7_200_000L))
    }

    @Test
    fun `no direction arrow is drawn without a qualified bearing`() {
        assertFalse(RiderMarkerStyle.canDrawDirectionArrow(false))
        assertTrue(RiderMarkerStyle.canDrawDirectionArrow(true))
    }

    @Test
    fun `the stacking offset is deterministic and order independent`() {
        val a = RiderMarkerStyle.stackingOffset("rider-a", 0, 2)
        val b = RiderMarkerStyle.stackingOffset("rider-a", 0, 2)
        assertEquals("the same rider must always get the same offset", a, b)
        val c = RiderMarkerStyle.stackingOffset("rider-b", 0, 2)
        assertNotEquals("different riders must be separated", a, c)
        assertEquals("a lone rider is not offset", 0f to 0f, RiderMarkerStyle.stackingOffset("x", 0, 1))
    }

    @Test
    fun `overlapping riders are clustered and distant ones are not`() {
        val positions = listOf(
            "a" to (100f to 100f),
            "b" to (110f to 104f),   // overlapping with a
            "c" to (600f to 600f)    // far away
        )
        val clusters = RiderMarkerStyle.clusterRiders(positions, maxDistancePx = 40f)
        assertEquals("a and b share a cluster", clusters["a"], clusters["b"])
        assertNotEquals("c is on its own", clusters["a"], clusters["c"])
    }

    @Test
    fun `clustering does not depend on the input order`() {
        val positions = listOf(
            "a" to (100f to 100f),
            "b" to (110f to 104f),
            "c" to (600f to 600f)
        )
        val forward = RiderMarkerStyle.clusterRiders(positions, maxDistancePx = 40f)
        val reversed = RiderMarkerStyle.clusterRiders(positions.reversed(), maxDistancePx = 40f)
        assertEquals(forward["a"], reversed["a"])
        assertEquals(forward["b"], reversed["b"])
        assertEquals(forward["c"], reversed["c"])
    }
}
