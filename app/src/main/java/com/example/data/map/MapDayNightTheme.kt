package com.example.data.map

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Manual theme override offered by the map style control.
 * AUTO follows the daylight state at the geographic context being navigated.
 */
enum class MapThemeMode { AUTO, DAY, NIGHT }

/**
 * The resolved visual appearance supplied to the map renderer as stable state.
 *
 * Light/dark only — no dusk tier. Note on the renderer: RideTogether draws the OSM
 * standard raster tile set, which is a fixed light "day carto" style. There is no
 * separate dark tile source, so NIGHT is a renderer-level color transformation of
 * the day imagery (documented in docs/MAP_DAY_NIGHT_THEME.md). DAY passes the
 * tiles through unfiltered.
 */
enum class MapLighting { DAY, NIGHT }

/**
 * Raw solar classification before mapping to a renderable lighting.
 * POLAR_* marks windows where no horizon crossing happens within the scan horizon.
 */
enum class SolarStatus { DAY, NIGHT, POLAR_DAY, POLAR_NIGHT, INDETERMINATE }

/**
 * Pure, testable solar-position math (NOAA/Meeus low-precision algorithm).
 *
 * Classification threshold (documented product definition, light/dark only):
 *  - DAY:   apparent solar elevation >= 0° (the physical horizon)
 *  - NIGHT: elevation < 0° (the map is dark on the pre-dawn/post-sunset side)
 *
 * Coordinates/units: all inputs/outputs are plain primitives — no Android or
 * java.time dependency (minSdk 24, no desugaring), so this runs identically on the
 * JVM in unit tests and on device. All inputs are UTC epoch millis.
 */
object DaylightCalculator {

    data class DaylightTimeline(
        val status: SolarStatus,
        /** Next horizon (0°) crossing (sunrise/sunset), or null for polar windows. */
        val nextTransitionUtcMillis: Long?
    )

    /** Apparent (refraction-corrected) solar elevation in degrees. NaN for invalid inputs. */
    fun solarElevationDeg(latDeg: Double, lngDeg: Double, utcMillis: Long): Double {
        if (!latDeg.isFinite() || !lngDeg.isFinite() || latDeg !in -90.0..90.0) return Double.NaN

        val rad = PI / 180.0
        // Fractional days since the J2000.0 epoch. Unix epoch → Julian day: /86400000 + 2440587.5
        val n = utcMillis / 86400000.0 + 2440587.5 - 2451545.0

        val meanLongitude = normalize360(280.460 + 0.9856474 * n)
        val meanAnomaly = normalize360(357.528 + 0.9856003 * n)
        val eclipticLongitude = meanLongitude +
            1.915 * sin(meanAnomaly * rad) +
            0.020 * sin(2.0 * meanAnomaly * rad)
        val obliquity = 23.439 - 0.0000004 * n

        val declination = asin(sin(obliquity * rad) * sin(eclipticLongitude * rad))
        val rightAscension = atan2(
            cos(obliquity * rad) * sin(eclipticLongitude * rad),
            cos(eclipticLongitude * rad)
        )

        // Greenwich mean sidereal time in degrees
        val gmst = normalize360(280.46061837 + 360.98564736629 * n)
        val hourAngle = normalize180(gmst + lngDeg - rightAscension / rad)

        val elevation = asin(
            sin(latDeg * rad) * sin(declination) +
                cos(latDeg * rad) * cos(declination) * cos(hourAngle * rad)
        ) / rad

        return elevation + refractionCorrectionDeg(elevation)
    }

    /** Bennett refraction approximation in degrees; zero far below the horizon. */
    private fun refractionCorrectionDeg(elevationDeg: Double): Double {
        if (elevationDeg <= -5.0 || !elevationDeg.isFinite()) return 0.0
        val arcmin = 1.02 / tan((elevationDeg + 10.3 / (elevationDeg + 5.11)) * (PI / 180.0))
        return arcmin / 60.0
    }

    /**
     * Classifies the current instant and finds the next transition within the scan
     * window by sampling the elevation curve (default: 10-minute steps over 24h,
     * refined by bisection to ~30s). The single boundary is the physical horizon
     * (0° apparent elevation). Returns null for invalid coordinates.
     *
     * If no 0° crossing happens inside the window the status is reported as
     * POLAR_DAY / POLAR_NIGHT (e.g. Tromsø midsummer / midwinter).
     */
    fun timeline(
        latDeg: Double,
        lngDeg: Double,
        utcMillis: Long,
        scanWindowMillis: Long = 24L * 3600_000L,
        scanStepMillis: Long = 600_000L
    ): DaylightTimeline? {
        if (latDeg.isNaN() || lngDeg.isNaN() || !latDeg.isFinite() || !lngDeg.isFinite()) return null
        if (latDeg !in -90.0..90.0) return null

        val nowStatus = classify(solarElevationDeg(latDeg, lngDeg, utcMillis))

        var previousMillis = utcMillis
        var previousElevation = solarElevationDeg(latDeg, lngDeg, utcMillis)
        val horizonMillis = utcMillis + scanWindowMillis

        while (previousMillis < horizonMillis) {
            val nextMillis = kotlin.math.min(horizonMillis, previousMillis + scanStepMillis)
            val nextElevation = solarElevationDeg(latDeg, lngDeg, nextMillis)

            // Crossing of the 0° (horizon) boundary on the rising/falling curve.
            if ((previousElevation) * (nextElevation) <= 0 && previousElevation != nextElevation) {
                val crossingMillis = bisectBoundary(
                    latDeg, lngDeg, previousMillis, nextMillis, 0.0
                )
                // A crossing right at `utcMillis` boundary edge (equal) is not a future event.
                if (crossingMillis > utcMillis) {
                    return DaylightTimeline(nowStatus, crossingMillis)
                }
            }

            previousMillis = nextMillis
            previousElevation = nextElevation
        }

        // No transition inside the scan horizon.
        val polarStatus = if (nowStatus == SolarStatus.DAY) SolarStatus.POLAR_DAY else SolarStatus.POLAR_NIGHT
        return DaylightTimeline(polarStatus, null)
    }

    private fun bisectBoundary(
        latDeg: Double,
        lngDeg: Double,
        tLow: Long,
        tHigh: Long,
        boundary: Double
    ): Long {
        var low = tLow
        var high = tHigh
        while (high - low > 30_000L) {
            val mid = low + (high - low) / 2
            val midElevation = solarElevationDeg(latDeg, lngDeg, mid)
            val lowElevation = solarElevationDeg(latDeg, lngDeg, low)
            // Preserve the monotonic segment direction toward the boundary
            if ((lowElevation - boundary) * (midElevation - boundary) <= 0) {
                high = mid
            } else {
                low = mid
            }
        }
        return (low + high) / 2
    }

    fun classify(apparentElevationDeg: Double): SolarStatus = when {
        apparentElevationDeg.isNaN() -> SolarStatus.INDETERMINATE
        apparentElevationDeg >= 0.0 -> SolarStatus.DAY
        else -> SolarStatus.NIGHT
    }

    private fun normalize360(deg: Double): Double {
        val v = deg % 360.0
        return if (v < 0) v + 360.0 else v
    }

    private fun normalize180(deg: Double): Double {
        var v = normalize360(deg)
        if (v > 180.0) v -= 360.0
        return v
    }
}

/**
 * Maps the resolved solar status to a renderable [MapLighting], and defines the
 * documented behavior when no geographic context is available.
 */
object MapLightingResolver {

    /**
     * Fallback when no valid geographic location is available (documented approximation):
     * use the device's own local wall clock — DAY from 07:00 through 18:59 inclusive,
     * NIGHT otherwise. No twilight band without geography.
     */
    const val FALLBACK_DAY_START_LOCAL_HOUR = 7
    const val FALLBACK_DAY_END_LOCAL_HOUR = 18

    data class ResolvedLighting(
        val lighting: MapLighting,
        val nextTransitionUtcMillis: Long?,
        val usedGeographicSource: Boolean
    )

    /**
     * Longitude-band approximation of the civil UTC offset for a location
     * (offset = round(lng / 15°) hours). A documented approximation: it follows
     * solar time bands rather than political borders / DST rules, which is what
     * solar classification needs.
     */
    fun approximateZoneOffsetSeconds(lngDeg: Double): Int {
        if (!lngDeg.isFinite() || lngDeg !in -180.0..180.0) return 0
        return (lngDeg / 15.0).roundToInt() * 3600
    }

    /** Local hour-of-day for the given UTC instant at the given fixed offset. */
    fun localHourOfDay(utcMillis: Long, zoneOffsetSeconds: Int): Int {
        val shiftedHours = floor((utcMillis + zoneOffsetSeconds * 1000L) / 3_600_000.0).toLong()
        return floorMod(shiftedHours, 24L).toInt()
    }

    private fun floorMod(a: Long, b: Long): Long {
        val r = a % b
        return if (r < 0) r + b else r
    }

    /**
     * Resolves the lighting for the manual mode or (AUTO) at the given geographic
     * context. Without a valid geography, the documented local-clock fallback applies.
     */
    fun resolveDetailed(
        mode: MapThemeMode,
        latDeg: Double?,
        lngDeg: Double?,
        utcMillis: Long,
        deviceZoneOffsetSeconds: Int
    ): ResolvedLighting {
        return when (mode) {
            MapThemeMode.DAY -> ResolvedLighting(MapLighting.DAY, null, false)
            MapThemeMode.NIGHT -> ResolvedLighting(MapLighting.NIGHT, null, false)
            MapThemeMode.AUTO -> {
                val timeline = if (latDeg != null && lngDeg != null) {
                    DaylightCalculator.timeline(latDeg, lngDeg, utcMillis)
                } else null
                val status = timeline?.status ?: SolarStatus.INDETERMINATE
                val lighting = when (status) {
                    SolarStatus.DAY, SolarStatus.POLAR_DAY -> MapLighting.DAY
                    SolarStatus.NIGHT, SolarStatus.POLAR_NIGHT -> MapLighting.NIGHT
                    SolarStatus.INDETERMINATE -> fallbackLighting(utcMillis, deviceZoneOffsetSeconds)
                }
                ResolvedLighting(
                    lighting = lighting,
                    nextTransitionUtcMillis = timeline?.nextTransitionUtcMillis,
                    usedGeographicSource = timeline != null
                )
            }
        }
    }

    fun fallbackLighting(utcMillis: Long, zoneOffsetSeconds: Int): MapLighting {
        val hour = localHourOfDay(utcMillis, zoneOffsetSeconds)
        return if (hour in FALLBACK_DAY_START_LOCAL_HOUR..FALLBACK_DAY_END_LOCAL_HOUR) {
            MapLighting.DAY
        } else {
            MapLighting.NIGHT
        }
    }
}
