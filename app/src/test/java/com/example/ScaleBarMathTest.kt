package com.example

import com.example.ui.map.ScaleBarMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic scale bar math (Phase B, audit §6 P4) — the bar must never claim a
 * distance the viewport does not actually span.
 */
class ScaleBarMathTest {

    @Test
    fun `meters per pixel halves with every zoom level`() {
        val lat = 18.6
        val z14 = ScaleBarMath.metersPerPixel(lat, 14f)
        val z15 = ScaleBarMath.metersPerPixel(lat, 15f)
        val z16 = ScaleBarMath.metersPerPixel(lat, 16f)
        assertEquals(z14 / 2.0, z15, 1e-6)
        assertEquals(z15 / 2.0, z16, 1e-6)
    }

    @Test
    fun `meters per pixel grows with latitude magnitude`() {
        val equator = ScaleBarMath.metersPerPixel(0.0, 15f)
        val mid = ScaleBarMath.metersPerPixel(45.0, 15f)
        val pole = ScaleBarMath.metersPerPixel(80.0, 15f)
        assertTrue(equator > mid)
        assertTrue(mid > pole)
    }

    @Test
    fun `density aware conversion divides by pixels per dp`() {
        val perDp = ScaleBarMath.metersPerPixel(18.6, 16f)
        val perPx = ScaleBarMath.metersPerScreenPixel(18.6, 16f, pxPerDp = 3f)
        assertEquals(perDp / 3.0, perPx, 1e-9)
    }

    @Test
    fun `chosen segment fits the bar budget`() {
        // 56 dp budget on a 3× screen at zoom 16 near Pune.
        val perPx = ScaleBarMath.metersPerScreenPixel(18.6, 16f, pxPerDp = 3f)
        val segment = ScaleBarMath.chooseSegment(perPx, barWidthDp = 56f)

        assertTrue("segment must come from the snapping set", segment.meters in ScaleBarMath.SEGMENT_METERS)
        assertTrue(
            "segment width ${segment.widthPx} must fit the budget",
            segment.widthPx <= 56f * 1.15f
        )
    }

    @Test
    fun `segment width matches the real ground distance it represents`() {
        val perPx = ScaleBarMath.metersPerScreenPixel(18.6, 16f, pxPerDp = 3f)
        val segment = ScaleBarMath.chooseSegment(perPx, barWidthDp = 56f)
        val impliedMeters = segment.widthPx * perPx
        assertEquals(segment.meters.toDouble(), impliedMeters, segment.meters * 0.02)
    }

    @Test
    fun `zooming in selects a smaller segment`() {
        val wide = ScaleBarMath.chooseSegment(
            ScaleBarMath.metersPerScreenPixel(18.6, 12f, 3f), 56f
        )
        val tight = ScaleBarMath.chooseSegment(
            ScaleBarMath.metersPerScreenPixel(18.6, 18f, 3f), 56f
        )
        assertTrue("zooming in must shorten the represented distance", tight.meters < wide.meters)
    }

    @Test
    fun `caption uses metres below a kilometre and kilometres above`() {
        assertEquals("500 m", ScaleBarMath.caption(500))
        assertEquals("50 m", ScaleBarMath.caption(50))
        assertEquals("1 km", ScaleBarMath.caption(1_000))
        assertEquals("2 km", ScaleBarMath.caption(2_000))
        assertEquals("5 km", ScaleBarMath.caption(5_000))
    }
}
