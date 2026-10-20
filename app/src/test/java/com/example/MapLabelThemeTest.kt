package com.example

import androidx.compose.ui.graphics.Color
import com.example.ui.map.MapLabelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map label tokens (Phase B, audit §6 P3) — halo geometry and contrast guard.
 */
class MapLabelThemeTest {

    @Test
    fun `halo offsets cover eight directions`() {
        val offsets = MapLabelTheme.haloOffsets(4f)
        assertEquals("eight directions × two components", 16, offsets.size)
        // Cardinal points at exactly the radius.
        assertTrue(offsets.contains(4f) && offsets.contains(0f) && offsets.contains(-4f))
        // Diagonals are shorter but non-zero.
        val diagonal = offsets.filter { it != 0f && kotlin.math.abs(it) != 4f }
        assertTrue("diagonal halo offsets must exist", diagonal.isNotEmpty())
    }

    @Test
    fun `halo radius scales linearly`() {
        val small = MapLabelTheme.haloOffsets(1f)
        val large = MapLabelTheme.haloOffsets(2f)
        for (i in small.indices) {
            assertEquals(small[i] * 2f, large[i], 0.0001f)
        }
    }

    @Test
    fun `halo ink is darker than the waypoint foregrounds`() {
        assertTrue(
            "halo must be darker than the white START caption",
            MapLabelTheme.haloDarkerThan(Color.White)
        )
        assertTrue(
            "halo must be darker than the amber FINISH caption",
            MapLabelTheme.haloDarkerThan(MapLabelTheme.waypointFinishStyle().color)
        )
    }

    @Test
    fun `waypoint styles keep the intended sizes and weights`() {
        assertEquals(11f, MapLabelTheme.waypointStartStyle().fontSize.value, 0.01f)
        assertEquals(11f, MapLabelTheme.waypointFinishStyle().fontSize.value, 0.01f)
    }

    @Test
    fun `halo radius is a sane device independent value`() {
        assertTrue(MapLabelTheme.HALO_RADIUS_DP > 0f)
        assertTrue(MapLabelTheme.HALO_RADIUS_DP <= 2f)
    }
}
