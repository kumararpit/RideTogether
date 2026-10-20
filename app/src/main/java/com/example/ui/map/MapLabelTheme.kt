package com.example.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * RT-original map label tokens (audit §6 P3). Parameters were chosen from the
 * measured reference relationships (halo ≈2 dp, near-black stroke halo over
 * light base maps, place-label sizes in the 9–14 sp band), but every color and
 * glyph treatment here is RideTogether's own — no reference artwork, fonts, or
 * styles are reproduced.
 */
object MapLabelTheme {

    /** Halo half-stroke radius used by the 8-direction offset halo painter (dp). */
    const val HALO_RADIUS_DP = 1.0f

    /** Halo ink: RT slate-dark at 75% alpha (passes on light pavement tiles). */
    val HALO_COLOR: Color = Color(0xBF0B0F19)

    /** Waypoint caption styles (START/FINISH). */
    fun waypointStartStyle(): TextStyle = TextStyle(
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black
    )

    fun waypointFinishStyle(): TextStyle = TextStyle(
        // RT amber core keeps the finish caption on-brand while halo preserves contrast.
        color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Black
    )

    /** Deterministic 8-direction halo offsets (unit circles × radius). */
    fun haloOffsets(radiusPx: Float): List<Float> {
        val r = radiusPx
        return listOf(
            r, 0f, -r, 0f, 0f, r, 0f, -r,
            r * 0.7071f, r * 0.7071f, -r * 0.7071f, r * 0.7071f,
            r * 0.7071f, -r * 0.7071f, -r * 0.7071f, -r * 0.7071f
        )
    }

    /** WCAG-ish guard: halo must always be darker than the foreground ink. */
    fun haloDarkerThan(foreground: Color): Boolean {
        val lum = { c: Color -> 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue }
        return lum(HALO_COLOR) <= lum(foreground) - 0.05f
    }
}
