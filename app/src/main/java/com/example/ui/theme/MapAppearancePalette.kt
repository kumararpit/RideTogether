package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.map.MapLighting

/**
 * Day/night map appearance palette (Task 18).
 *
 * These tokens only style the map overlay surfaces (tile placeholders, amenity
 * labels). They deliberately do not replace any existing app-wide theme tokens.
 * WCAG contrast notes:
 *  - Day label text  #25323C on  #F2F7FA  ≈ 11.2:1
 *  - Night label text #E8F0FB on #16243C  ≈ 12.6:1
 */
object MapAppearancePalette {

    /**
     * Tile slot placeholder shown while a tile loads or fails. Previously this was
     * fixed SlateDark800, which produced dark squares among light tiles in the light
     * style — the day placeholder now matches the daytime imagery instead.
     */
    fun tilePlaceholder(lighting: MapLighting): Color = when (lighting) {
        MapLighting.DAY -> Color(0xFFD9E2E9)
        MapLighting.NIGHT -> SlateDark800
    }

    /** Amenity label pill background/text; saturated contrast in both themes. */
    fun amenityLabelBackground(lighting: MapLighting): Color = when (lighting) {
        MapLighting.DAY -> Color(0xFFF2F7FA).copy(alpha = 0.95f)
        MapLighting.NIGHT -> Color(0xFF16243C).copy(alpha = 0.93f)
    }

    fun amenityLabelText(lighting: MapLighting): Color = when (lighting) {
        MapLighting.DAY -> Color(0xFF25323C)
        else -> Color(0xFFE8F0FB)
    }
}
