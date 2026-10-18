package com.example

import com.example.data.map.MapLighting
import com.example.data.map.MapThemeController
import com.example.data.map.MapThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deterministic MapThemeController tests (Task 18): injected clock, injected zone
 * offset (IST +5:30), virtual-time scheduling — no real time, no network.
 *
 * Reference solar anchors (DaylightCalculator, validated separately):
 *  - Pune 18.5204N 73.8567E, 2024-10-15: sunset ≈ 12:44Z, civil dawn (next day) ≈ 00:37Z.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapThemeControllerTest {

    private val puneLat = 18.5204
    private val puneLng = 73.8567

    companion object {
        // 2024-10-15T00:00Z = 1728950400 (day start).
        private const val MID = 1728950400000L
        // 07:00Z = 12:30 IST → DAY
        private const val PUNE_MIDDAY = MID + 7L * 3600_000
        // 05:30Z = 11:00 IST → DAY (fallback band) / LA 22:30 local → NIGHT
        private const val T_0530 = MID + 5L * 3600_000 + 1800_000
        // 16:00Z = 21:30 IST → NIGHT (~3.2h past the 12:44Z sunset edge)
        private const val T_1600 = MID + 16L * 3600_000
        // Next-day solar anchors (from DaylightCalculator, validated in DaylightCalculatorTest):
        // sunrise (0° horizon crossing) ≈ 00:59.6Z Oct 16.
    }

    @Test
    fun `auto mode resolves lighting from the rider location`() = runTest {
        var clock = PUNE_MIDDAY
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        controller.onRiderLocation(puneLat, puneLng, accuracyMeters = 8f, fixAgeMillis = 0)
        assertEquals(MapLighting.DAY, controller.lighting.value)
        assertEquals(MapThemeMode.AUTO, controller.mode.value)
    }

    @Test
    fun `manual night override wins immediately and auto restores it`() = runTest {
        var clock = PUNE_MIDDAY
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        controller.onRiderLocation(puneLat, puneLng, accuracyMeters = 8f, fixAgeMillis = 0)
        assertEquals(MapLighting.DAY, controller.lighting.value)

        controller.setMode(MapThemeMode.NIGHT)
        assertEquals(MapLighting.NIGHT, controller.lighting.value)

        // Manual DAY override regardless of geography/time.
        controller.setMode(MapThemeMode.DAY)
        assertEquals(MapLighting.DAY, controller.lighting.value)

        // Back to AUTO re-resolves from the geographic context.
        controller.setMode(MapThemeMode.AUTO)
        assertEquals(MapLighting.DAY, controller.lighting.value)
    }

    @Test
    fun `location priority is rider over camera over endpoints`() = runTest {
        var clock = T_0530
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        // Camera center in Los Angeles: 05:30Z = 22:30 PDT → NIGHT.
        controller.onCameraCenter(34.0522, -118.2437)
        assertEquals(MapLighting.NIGHT, controller.lighting.value)

        // Route endpoint in Pune (05:30Z = 11:00 IST → DAY) — camera still outranks it.
        controller.onRouteEndpoints(puneLat, puneLng, 73.1, 19.0)
        assertEquals(MapLighting.NIGHT, controller.lighting.value)

        // A valid rider fix outranks the existing sources → DAY.
        controller.onRiderLocation(puneLat, puneLng, accuracyMeters = 8f, fixAgeMillis = 0)
        assertEquals(MapLighting.DAY, controller.lighting.value)
    }

    @Test
    fun `invalid stale or fabricated fixes are rejected`() = runTest {
        var clock = T_0530
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        // Fabricated (0,0), over-accuracy, and over-stale fixes are all ignored.
        controller.onRiderLocation(0.0, 0.0, accuracyMeters = 8f, fixAgeMillis = 0)
        controller.onRiderLocation(puneLat, puneLng, accuracyMeters = 2000f, fixAgeMillis = 0)
        controller.onRiderLocation(puneLat, puneLng, accuracyMeters = 8f, fixAgeMillis = 11L * 60_000)
        // No valid source → documented timezone fallback; 11:00 IST local hour → DAY.
        assertEquals(MapLighting.DAY, controller.lighting.value)
    }

    @Test
    fun `material-change threshold suppresses sub-threshold context churn`() = runTest {
        var clock = PUNE_MIDDAY
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        controller.onRiderLocation(puneLat, puneLng, accuracyMeters = 8f, fixAgeMillis = 0)
        val lightingBefore = controller.lighting.value

        // Sub-threshold GPS jitters never reset the lighting state.
        controller.onRiderLocation(puneLat + 0.0001, puneLng - 0.0001, accuracyMeters = 8f, fixAgeMillis = 0)
        assertEquals(lightingBefore, controller.lighting.value)

        // A material move (> 0.1°) re-resolves; Pune daytime is stable DAY.
        clock = MID + 18L * 3600_000 // 18:00 UTC = 23:30 IST → NIGHT if re-resolved
        controller.onRiderLocation(puneLat + 0.2, puneLng, accuracyMeters = 8f, fixAgeMillis = 0)
        assertEquals(MapLighting.NIGHT, controller.lighting.value)
    }

    @Test
    fun `scheduled transition resolves night to day at sunrise`() = runTest {
        var clock = T_1600 // 21:30 IST night
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        controller.onCameraCenter(puneLat, puneLng)
        assertEquals(MapLighting.NIGHT, controller.lighting.value)

        // Light/dark only: the single next transition is the 0° sunrise crossing
        // (≈00:59.6Z Oct 16 = T_1600 + 8h59.6m). By the firing the fake clock reads
        // 01:02Z, past the horizon → DAY.
        clock = T_1600 + (9L * 3600_000 + 2L * 60_000) // 01:02Z
        advanceTimeBy((9L * 3600_000 + 2L * 60_000) + 3_000L)
        assertEquals(MapLighting.DAY, controller.lighting.value)
    }

    @Test
    fun `just below the horizon before sunrise still resolves to NIGHT`() = runTest {
        var clock = T_1600 + (8L * 3600_000 + 39L * 60_000) // 00:39Z (elev ≈ -5.5°)
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        controller.onCameraCenter(puneLat, puneLng)
        // Pre-dawn is NIGHT under the light/dark-only definition.
        assertEquals(MapLighting.NIGHT, controller.lighting.value)
    }

    @Test
    fun `polar-day schedules a daily recheck without crashing`() = runTest {
        var clock = 1729_555_200_000L // long before Longyearbyen midwinter
        val mid = 1729000000000L
        clock = mid
        val controller = MapThemeController(
            scope = backgroundScope,
            nowMillis = { clock },
            deviceZoneOffsetSeconds = { 19800 }
        )
        // Longyearbyen on 2024-12-21 (all-night) — daily recheck path with no transitions.
        // 2024-12-21T12:00Z = 1729_555_200_000
        clock = 1729555200000L
        controller.onCameraCenter(78.2236, 15.6267)
        assertEquals(MapLighting.NIGHT, controller.lighting.value)
        advanceTimeBy(48L * 3600_000L + 5_000L)
        assertEquals(MapLighting.NIGHT, controller.lighting.value)
    }
}
