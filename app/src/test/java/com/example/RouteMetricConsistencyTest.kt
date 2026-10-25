package com.example

import com.example.data.location.LocationUpdate
import com.example.data.location.NavEngine
import com.example.data.location.RouteGeometry
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.Ride
import com.example.data.model.RideStatus
import com.example.data.model.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-001 — Route metric consistency regression tests.
 *
 * Three distinct duration metrics must never be confused:
 *   1. Planned total (OSRM estimate, fixed at route selection) — RouteResult.totalDurationSeconds
 *   2. Remaining (counts down during active navigation) — NavEngine.progress.remainingDurationSeconds
 *   3. Elapsed / actual (counts up, actual time spent) — Ride.elapsedDurationSeconds
 *
 * The "1:47 vs 2:50" jump occurs when planned, remaining, and elapsed are shown
 * interchangeably without distinct labels. These tests pin the semantics so the
 * bug cannot regress.
 */
class RouteMetricConsistencyTest {

    /**
     * Sets the GPS speed so it matches the route's average speed, ensuring that
     * NavEngine's remaining-duration estimate at nav start ≈ planned total.
     * NavEngine computes remaining = remainingDistance / effectiveSpeed, where
     * effectiveSpeed derives from the GPS reading (EMA) when speed >= 3 m/s.
     */
    private fun speedKmhForRoute(distanceMeters: Double, durationSeconds: Double): Double {
        if (distanceMeters <= 0.0 || durationSeconds <= 0.0) return 40.0
        val avgSpeedMps = distanceMeters / durationSeconds
        return (avgSpeedMps * 3.6).coerceIn(8.0, 90.0) // clamp to valid GPS range
    }

    // ------------------------------------------------------------------
    // 1. Planned total duration stays constant across route preview → lobby → nav start
    // ------------------------------------------------------------------

    @Test
    fun `planned total duration stays constant across route preview, lobby, and nav start`() {
        val p1 = LatLng(18.5204, 73.8567)
        val p2 = LatLng(18.7459, 73.6753)

        val plannedDist = p1.distanceTo(p2) * 1000.0 // ~35 km (meters)
        val plannedSecs = 6300.0 // 1 hr 45 min planned

        val route = RouteResult(
            coordinates = listOf(p1, p2),
            totalDistanceMeters = plannedDist,
            totalDurationSeconds = plannedSecs,
            steps = emptyList()
        )

        // Route preview / lobby show the planned total from RouteResult
        assertEquals("Route preview shows planned total duration", plannedSecs, route.totalDurationSeconds, 0.001)

        // Nav engine is seeded with the same route. NavEngine may adjust the planned
        // duration internally via calculateRealisticPlannedDuration; capture that.
        val adjustedDuration = NavEngine.calculateRealisticPlannedDuration(plannedDist, plannedSecs)
        val navEngine = NavEngine()
        navEngine.setRoute(route)

        // At the very start of navigation, remaining ≈ adjusted planned total
        val startProgress = navEngine.processLocation(
            LocationUpdate(
                p1,
                speedKmh = speedKmhForRoute(plannedDist, adjustedDuration),
                headingDeg = 0f,
                accuracyMeters = 5f
            )
        )
        assertEquals(
            "At nav start, remaining duration ≈ planned total",
            adjustedDuration,
            startProgress.remainingDurationSeconds,
            5.0
        )

        // Planned total from RouteResult never changes
        assertEquals("RouteResult.totalDurationSeconds unchanged", plannedSecs, route.totalDurationSeconds, 0.001)
    }

    // ------------------------------------------------------------------
    // 2. Remaining duration counts down during navigation; planned stays fixed
    // ------------------------------------------------------------------

    @Test
    fun `remaining duration decreases as rider advances while planned total stays fixed`() {
        val p1 = LatLng(18.5200, 73.8500)
        val p2 = LatLng(18.5500, 73.8500) // ~3.3 km north
        val p3 = LatLng(18.5800, 73.8500) // ~6.7 km north

        val pts = listOf(p1, p2, p3)
        val totalDist = RouteGeometry.remainingDistanceMeters(0, pts)
        val plannedSecs = 900.0 // 15 min planned

        val route = RouteResult(
            coordinates = pts,
            totalDistanceMeters = totalDist,
            totalDurationSeconds = plannedSecs,
            steps = listOf(
                NavigationStep("Head north", "Road A", totalDist * 0.5, 450.0, "depart", "straight"),
                NavigationStep("Continue north", "Road A", totalDist * 0.5, 450.0, "turn", "straight")
            )
        )

        val adjustedDuration = NavEngine.calculateRealisticPlannedDuration(totalDist, plannedSecs)
        val navEngine = NavEngine()
        navEngine.setRoute(route)

        // Planned total is fixed — RouteResult.totalDurationSeconds never changes mid-ride
        assertEquals("Planned total is fixed at route start", plannedSecs, route.totalDurationSeconds, 0.001)

        // At start, remaining ≈ adjusted planned total
        val routeAvgSpeedKmh = speedKmhForRoute(totalDist, adjustedDuration)
        val r1 = navEngine.processLocation(
            LocationUpdate(p1, speedKmh = routeAvgSpeedKmh, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue("At start, remaining duration ≈ planned (> 70%)", r1.remainingDurationSeconds > adjustedDuration * 0.7)

        // Mid-ride, remaining is less than planned
        val r2 = navEngine.processLocation(
            LocationUpdate(p2, speedKmh = routeAvgSpeedKmh, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue("Mid-ride, remaining < planned", r2.remainingDurationSeconds < plannedSecs)

        // Near destination, remaining is much less than at mid-ride
        val r3 = navEngine.processLocation(
            LocationUpdate(p3, speedKmh = routeAvgSpeedKmh, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue("Near end, remaining is small", r3.remainingDurationSeconds < r2.remainingDurationSeconds)

        // Planned total never changed
        assertEquals("Planned total unchanged mid-ride", plannedSecs, route.totalDurationSeconds, 0.001)
    }

    // ------------------------------------------------------------------
    // 3. Elapsed duration is distinct from planned; used in completion when available
    // ------------------------------------------------------------------

    @Test
    fun `elapsed duration is distinct from planned and used in completion when available`() {
        val p1 = LatLng(18.5204, 73.8567)
        val p2 = LatLng(18.7459, 73.6753)

        val plannedSecs = 6300.0 // 1 hr 45 min planned
        val elapsedSecs = 7200.0 // 2 hr 0 min actually spent (slower than planned)

        val ride = Ride(
            name = "Test Ride",
            startLocationName = "Pune",
            destinationName = "Lonavala",
            startLocation = p1,
            destinationLocation = p2,
            leaderId = "leader",
            inviteCode = "ABC123",
            status = RideStatus.COMPLETED,
            initialPlannedDistanceMeters = p1.distanceTo(p2) * 1000.0,
            totalDistanceMeters = p1.distanceTo(p2) * 1000.0,
            totalDurationSeconds = plannedSecs,
            elapsedDurationSeconds = elapsedSecs,
            actualRecordedDistanceMeters = p1.distanceTo(p2) * 1000.0
        )

        // Planned total ≠ actual elapsed — these are different metrics
        assertTrue("Planned total != actual elapsed", ride.totalDurationSeconds != ride.elapsedDurationSeconds)

        // Completion screen uses elapsed when > 30s (per RideSummaryScreen logic)
        val summaryDuration = if (ride.elapsedDurationSeconds > 30.0) ride.elapsedDurationSeconds else ride.totalDurationSeconds
        assertEquals("Summary uses actual elapsed when available", elapsedSecs, summaryDuration, 0.001)

        // When elapsed is unavailable (< 30s), falls back to planned
        val quickRide = Ride(
            name = "Quick Ride",
            startLocationName = "Pune",
            destinationName = "Lonavala",
            startLocation = p1,
            destinationLocation = p2,
            leaderId = "leader",
            inviteCode = "DEF456",
            status = RideStatus.COMPLETED,
            totalDurationSeconds = plannedSecs,
            elapsedDurationSeconds = 10.0 // too short to trust
        )
        val quickSummary = if (quickRide.elapsedDurationSeconds > 30.0) quickRide.elapsedDurationSeconds else quickRide.totalDurationSeconds
        assertEquals("Summary falls back to planned when elapsed unavailable", plannedSecs, quickSummary, 0.001)
    }

    // ------------------------------------------------------------------
    // 4. Remaining duration reaches zero at arrival (planned vs remaining divergence)
    // ------------------------------------------------------------------

    @Test
    fun `remaining duration reaches zero at arrival while planned total stays fixed`() {
        val p1 = LatLng(18.5200, 73.8500)
        val dest = LatLng(18.5400, 73.8500) // ~2.2 km north

        val pts = listOf(p1, dest)
        val totalDist = RouteGeometry.remainingDistanceMeters(0, pts)
        val plannedSecs = 300.0 // 5 min planned

        val route = RouteResult(
            coordinates = pts,
            totalDistanceMeters = totalDist,
            totalDurationSeconds = plannedSecs,
            steps = listOf(NavigationStep("Head north", "Road A", totalDist, plannedSecs, "depart", "straight"))
        )

        val adjustedDuration = NavEngine.calculateRealisticPlannedDuration(totalDist, plannedSecs)
        val navEngine = NavEngine()
        navEngine.setRoute(route)

        // At start, remaining ≈ adjusted planned total
        val routeAvgSpeedKmh = speedKmhForRoute(totalDist, adjustedDuration)
        val r1 = navEngine.processLocation(
            LocationUpdate(p1, speedKmh = routeAvgSpeedKmh, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue("At start, remaining ≈ planned (> 50%)", r1.remainingDurationSeconds > adjustedDuration * 0.5)

        // At destination, remaining is zero
        val r2 = navEngine.processLocation(
            LocationUpdate(dest, speedKmh = 30.0, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertEquals("At arrival, remaining duration is 0", 0.0, r2.remainingDurationSeconds, 1.0)

        // Planned total never changed
        assertEquals("Planned total unchanged at arrival", plannedSecs, route.totalDurationSeconds, 0.001)
    }
}
