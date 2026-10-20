package com.example

import com.example.data.map.DaylightCalculator
import com.example.data.map.SolarStatus
import com.example.data.map.MapLightingResolver
import com.example.data.map.MapThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic solar/daylight classification tests (Task 18, Part A).
 * Reference times: Pune (18.5204°N, 73.8567°E), 2024-10-15 — sunrise ≈ 06:31 IST,
 * sunset ≈ 18:15 IST (civil twilight starts/ends ~25 min before/after). All inputs are
 * explicit UTC millis — no dependence on the real clock.
 */
class DaylightCalculatorTest {

    private fun utcAt(
        year: Int, month: Int, day: Int,
        hourUtc: Int, minuteUtc: Int = 0, secondUtc: Int = 0
    ): Long {
        // Explicit civil-date → epoch-day without java.time (Julian Day Number formula):
        val a = (14 - month) / 12
        val yy = year + 4800 - a
        val mm = month + 12 * a - 3
        val jdn = day + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
        val unixDays = jdn - 2440588
        return (unixDays * 86400L + hourUtc * 3600L + minuteUtc * 60L + secondUtc) * 1000L
    }

    private val puneLat = 18.5204
    private val puneLng = 73.8567

    @Test
    fun `midday at Pune resolves to DAY`() {
        // 2024-10-15 12:30 IST = 07:00 UTC
        val millis = utcAt(2024, 10, 15, 7, 0)
        val elevation = DaylightCalculator.solarElevationDeg(puneLat, puneLng, millis)
        assertTrue("midday elevation should be high, was $elevation", elevation in 35.0..75.0)
        assertEquals(com.example.data.map.SolarStatus.DAY, DaylightCalculator.classify(elevation))
    }

    @Test
    fun `deep night at Pune resolves to NIGHT`() {
        // 2024-10-15 21:00 IST = 15:30 UTC
        val millis = utcAt(2024, 10, 15, 15, 30)
        val elevation = DaylightCalculator.solarElevationDeg(puneLat, puneLng, millis)
        assertEquals(com.example.data.map.SolarStatus.NIGHT, DaylightCalculator.classify(elevation))
        assertTrue("night elevation should be well below -6 deg", elevation < -10.0)
    }

    @Test
    fun `sun below horizon just before sunrise resolves to NIGHT - light dark only`() {
        // 06:15 IST = 00:45 UTC, ~16 min before the 06:31 IST sunrise: solar elevation
        // is still below the horizon, so the light/dark-only product definition makes
        // this NIGHT (no grey tier).
        val before = DaylightCalculator.solarElevationDeg(
            puneLat, puneLng, utcAt(2024, 10, 15, 0, 45)
        )
        assertTrue("elevation should be just below the horizon", before < 0.0)
        assertEquals(com.example.data.map.SolarStatus.NIGHT, DaylightCalculator.classify(before))
    }

    @Test
    fun `timeline finds sunset near the known Pune sunset on Oct 15 2024`() {
        // Compute at local noon (06:30 UTC); expected sunset ≈ 12:44 UTC (18:14 IST).
        val timeline = DaylightCalculator.timeline(
            puneLat, puneLng, utcAt(2024, 10, 15, 6, 30)
        )
        val expectedSunsetUtc = utcAt(2024, 10, 15, 12, 44)
        val toleranceMs = 35L * 60_000L
        assertNotNull(timeline?.nextTransitionUtcMillis)
        val next = timeline!!.nextTransitionUtcMillis!!
        val delta = Math.abs(next - expectedSunsetUtc)
        assertTrue("sunset delta ${delta / 60000.0} min exceeded tolerance", delta <= toleranceMs)
        assertEquals(com.example.data.map.SolarStatus.DAY, timeline.status)
    }

    @Test
    fun `timeline finds sunrise near the known Pune sunrise on Oct 15 2024`() {
        // Compute at 15:30 UTC (21:00 IST night); with the light/dark-only definition
        // the next transition is the next-day horizon (0°) crossing at sunrise
        // ≈ 01:02 UTC Oct 16 (06:32 IST).
        val timeline = DaylightCalculator.timeline(
            puneLat, puneLng, utcAt(2024, 10, 15, 15, 30)
        )
        assertEquals(com.example.data.map.SolarStatus.NIGHT, timeline?.status)
        val next = timeline?.nextTransitionUtcMillis
        val expectedSunriseUtc = utcAt(2024, 10, 16, 1, 2)
        val delta = Math.abs(next!! - expectedSunriseUtc)
        assertTrue("sunrise delta ${delta / 60000.0} min exceeded tolerance", delta <= 35L * 60_000L)
    }

    @Test
    fun `date rollover around local midnight schedules the next-day dawn`() {
        // 23:50 IST = 18:20 UTC Oct 15. Next transition must be next-day dawn (< 24h).
        val timeline = DaylightCalculator.timeline(
            puneLat, puneLng, utcAt(2024, 10, 15, 18, 20)
        )
        assertEquals(com.example.data.map.SolarStatus.NIGHT, timeline?.status)
        val nowUtc = utcAt(2024, 10, 15, 18, 20)
        val next = timeline!!.nextTransitionUtcMillis!!
        assertTrue(next > nowUtc && next - nowUtc < 24L * 3600_000L)
    }

    @Test
    fun `polar day at Tromso midsummer returns POLAR_DAY with no transition`() {
        // Tromsø (69.6492, 18.9553), 2024-06-21 12:00 UTC — midnight sun all day.
        val timeline = DaylightCalculator.timeline(69.6492, 18.9553, utcAt(2024, 6, 21, 12))
        assertEquals(com.example.data.map.SolarStatus.POLAR_DAY, timeline?.status)
        assertNull(timeline?.nextTransitionUtcMillis)
        // Midnight sun: even ~2h after local midnight the sun stays above the horizon.
        val midnightElevation = DaylightCalculator.solarElevationDeg(
            69.6492, 18.9553, utcAt(2024, 6, 21, 0, 0)
        )
        assertTrue("polar-day midnight elevation must stay above 0", midnightElevation > 0.0)
    }

    @Test
    fun `polar night at Longyearbyen midwinter returns POLAR_NIGHT with no transition`() {
        // Longyearbyen (78.22N) midwinter: max solar elevation ≈ -11.7° (well below
        // the -6° twilight floor) the whole day. Tromsø's noon would only reach the
        // twilight band, which the classifier legitimately reports.
        val timeline = DaylightCalculator.timeline(78.2236, 15.6267, utcAt(2024, 12, 21, 12))
        assertEquals(com.example.data.map.SolarStatus.POLAR_NIGHT, timeline?.status)
        assertNull(timeline?.nextTransitionUtcMillis)
    }

    @Test
    fun `tromso midwinter noon stays below the horizon and resolves to NIGHT`() {
        // Tromsø's 2024-12-21 noon solar elevation is ≈ -3° (below the horizon());
        // with the light/dark-only definition this is NIGHT.
        val noon = DaylightCalculator.solarElevationDeg(69.6492, 18.9553, utcAt(2024, 12, 21, 10))
        assertEquals(com.example.data.map.SolarStatus.NIGHT, DaylightCalculator.classify(noon))
    }

    @Test
    fun `invalid coordinates do not crash and return indeterminate`() {
        assertNull(DaylightCalculator.timeline(95.0, 73.0, utcAt(2024, 10, 15, 6)))
        assertNull(DaylightCalculator.timeline(Double.NaN, 73.0, utcAt(2024, 10, 15, 6)))
        assertNull(DaylightCalculator.timeline(18.0, Double.POSITIVE_INFINITY, utcAt(2024, 10, 15, 6)))
        assertTrue(DaylightCalculator.solarElevationDeg(95.0, 73.0, 0L).isNaN())
        assertEquals(
            com.example.data.map.SolarStatus.INDETERMINATE,
            DaylightCalculator.classify(DaylightCalculator.solarElevationDeg(95.0, 73.0, 0L))
        )
    }

    @Test
    fun `resolver maps statuses to lighting for AUTO mode`() {
        val day = MapLightingResolver.resolveDetailed(
            MapThemeMode.AUTO, puneLat, puneLng, utcAt(2024, 10, 15, 7), 19800
        )
        assertEquals(com.example.data.map.MapLighting.DAY, day.lighting)
        assertTrue(day.usedGeographicSource)

        val night = MapLightingResolver.resolveDetailed(
            MapThemeMode.AUTO, puneLat, puneLng, utcAt(2024, 10, 15, 15, 30), 19800
        )
        assertEquals(com.example.data.map.MapLighting.NIGHT, night.lighting)

        val nightDusk = MapLightingResolver.resolveDetailed(
            MapThemeMode.AUTO, puneLat, puneLng, utcAt(2024, 10, 15, 0, 45), 19800
        )
        // Light/dark only: pre-dawn (elevation < 0°) is NIGHT.
        assertEquals(com.example.data.map.MapLighting.NIGHT, nightDusk.lighting)
        // Light/dark only: pre-dawn (elevation < 0°) is NIGHT.
        assertEquals(com.example.data.map.MapLighting.NIGHT, nightDusk.lighting)
    }
    @Test
    fun `manual overrides bypass solar calculation`() {
        for (utcMillis in listOf(utcAt(2024, 10, 15, 7), utcAt(2024, 10, 15, 15, 30))) {
            val day = MapLightingResolver.resolveDetailed(
                MapThemeMode.DAY, puneLat, puneLng, utcMillis, 19800
            )
            val night = MapLightingResolver.resolveDetailed(
                MapThemeMode.NIGHT, puneLat, puneLng, utcMillis, 19800
            )
            assertEquals(com.example.data.map.MapLighting.DAY, day.lighting)
            assertEquals(com.example.data.map.MapLighting.NIGHT, night.lighting)
        }
    }

    @Test
    fun `missing location falls back to the documented local-hour band`() {
        val istOffset = 19800
        // 15:30 UTC + 5:30 = 21:00 local → NIGHT
        assertEquals(
            com.example.data.map.MapLighting.NIGHT,
            MapLightingResolver.fallbackLighting(utcAt(2024, 10, 15, 15, 30), istOffset)
        )
        // 03:00 UTC + 5:30 = 08:30 local → DAY
        assertEquals(
            com.example.data.map.MapLighting.DAY,
            MapLightingResolver.fallbackLighting(utcAt(2024, 10, 15, 3), istOffset)
        )
        // Boundaries: 07:00 local → DAY; 06:xx local → NIGHT; 18:59→DAY; 19:00→NIGHT.
        val dayStart = utcAt(2024, 10, 15, 1, 30)// 07:00 IST
        val beforeDay = utcAt(2024, 10, 15, 1, 10) // 06:40 IST
        val dayEnd = utcAt(2024, 10, 15, 13, 29, 59) // 18:59:59 IST
        val afterDay = utcAt(2024, 10, 15, 13, 30) // 19:00 IST
        assertEquals(com.example.data.map.MapLighting.DAY, MapLightingResolver.fallbackLighting(dayStart, istOffset))
        assertEquals(com.example.data.map.MapLighting.NIGHT, MapLightingResolver.fallbackLighting(beforeDay, istOffset))
        assertEquals(com.example.data.map.MapLighting.DAY, MapLightingResolver.fallbackLighting(dayEnd, istOffset))
        assertEquals(com.example.data.map.MapLighting.NIGHT, MapLightingResolver.fallbackLighting(afterDay, istOffset))
    }

    @Test
    fun `longitude band offset approximation is documented and bounded`() {
        assertEquals(5 * 3600, MapLightingResolver.approximateZoneOffsetSeconds(73.8567))
        assertEquals(-8 * 3600, MapLightingResolver.approximateZoneOffsetSeconds(-122.4194))
        assertEquals(0, MapLightingResolver.approximateZoneOffsetSeconds(Double.NaN))
        assertEquals(0, MapLightingResolver.approximateZoneOffsetSeconds(200.0))
    }

    private fun assertNotNull(value: Any?) {
        if (value == null) throw AssertionError("expected non-null")
    }
}
