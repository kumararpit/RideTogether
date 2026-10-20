package com.example.ui.map

import androidx.compose.ui.graphics.Color
import com.example.data.amenities.AmenityType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * RideTogether-original amenity palette (Phase B, backlog B-14).
 *
 * The reference product uses a ~100-entry place-type → icon table with explicit
 * **day/night twins** (`SearchResult.TYPE_<TYPE>[.<region>][.Ad]`, plus
 * `ROADMAP`/`ROADMAP_DARK` style pairs). Google also ships a **coarser
 * category → group** taxonomy (OUTDOORS, FOOD, EMERGENCY, LODGING, GENERIC,
 * SERVICES, RETAIL, ENTERTAINMENT, TRANSIT).
 *
 * This is the independent equivalent: ten RideTogether categories, each with a
 * day and a night variant, chosen so that every marker stays **separable from
 * the map** it is painted on. The two backgrounds are the measured tones of the
 * OSM raster RideTogether draws:
 *  - day: the pale land `#F8F7F7`;
 *  - night: the same raster after the night colour matrix, `#13263C`.
 *
 * No Google colour, icon or artwork is used.
 */
object AmenityPalette {

    enum class Theme { DAY, NIGHT }

    /** Measured day land tone (the brightest surface a marker can sit on). */
    val dayBackground = Color(0xFFF8F7F7)

    /** Measured night land tone (the raster after the night colour matrix). */
    val nightBackground = Color(0xFF13263C)

    /** Minimum contrast ratio a marker fill must reach against its background. */
    const val MIN_CONTRAST_RATIO = 2.2

    private val dayColors: Map<AmenityType, Color> = mapOf(
        AmenityType.FUEL to Color(0xFF1565C0),
        AmenityType.CAFE to Color(0xFFE65100),
        AmenityType.REPAIR to Color(0xFF2E7D32),
        AmenityType.REST_STOP to Color(0xFF6A1B9A),
        AmenityType.CONVENIENCE_STORE to Color(0xFFAD1457),
        AmenityType.HOTEL to Color(0xFF00838F),
        AmenityType.RESTAURANT to Color(0xFF4E342E),
        AmenityType.TOILET to Color(0xFF37474F),
        AmenityType.SCENIC_VIEWPOINT to Color(0xFF4527A0),
        AmenityType.UNKNOWN to Color(0xFF546E7A)
    )

    private val nightColors: Map<AmenityType, Color> = mapOf(
        AmenityType.FUEL to Color(0xFF64B5F6),
        AmenityType.CAFE to Color(0xFFFFB74D),
        AmenityType.REPAIR to Color(0xFF81C784),
        AmenityType.REST_STOP to Color(0xFFBA68C8),
        AmenityType.CONVENIENCE_STORE to Color(0xFFF06292),
        AmenityType.HOTEL to Color(0xFF4DD0E1),
        AmenityType.RESTAURANT to Color(0xFFBCAAA4),
        AmenityType.TOILET to Color(0xFF90A4AE),
        AmenityType.SCENIC_VIEWPOINT to Color(0xFF9FA8DA),
        AmenityType.UNKNOWN to Color(0xFFB0BEC5)
    )

    /** The marker fill for a category in the given theme. */
    fun colorFor(type: AmenityType, theme: Theme): Color =
        (if (theme == Theme.NIGHT) nightColors else dayColors)[type]
            ?: (if (theme == Theme.NIGHT) nightColors else dayColors)[AmenityType.UNKNOWN]
            ?: Color(0xFF546E7A)

    /** The background tone the markers are painted on in the given theme. */
    fun backgroundFor(theme: Theme): Color =
        if (theme == Theme.NIGHT) nightBackground else dayBackground

    /** WCAG-style relative luminance. */
    fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** WCAG-style contrast ratio between two colours (1.0 … 21.0). */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** True when a marker fill is clearly separable from the map background. */
    fun isSeparated(
        color: Color,
        background: Color,
        minRatio: Double = MIN_CONTRAST_RATIO
    ): Boolean = contrastRatio(color, background) >= minRatio
}
