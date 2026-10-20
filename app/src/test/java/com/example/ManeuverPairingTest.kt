package com.example

import com.example.data.location.LocationUpdate
import com.example.data.location.NavAudioGuidance
import com.example.data.location.NavEngine
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteResult
import com.example.data.location.AnnouncementHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Maneuver pairing, guarded advancement and announcement reset
 * (Phase B implementation of backlog B-01, B-02, B-04).
 *
 * The pairing test is the regression for audit finding **F1-D1**: before the
 * fix the banner showed `steps[currentManeuverIndex]` (the maneuver already
 * performed) next to the distance to `steps[currentManeuverIndex + 1]` (the
 * maneuver still ahead) — visible on the device as "82 m / Head left / Then:
 * Turn right" while the rider was 82 m from the *right* turn.
 */
class ManeuverPairingTest {

    /**
     * A three-step route mirroring an OSRM response: step 0 is the "depart"
     * maneuver at the origin, step 1 the first real turn, step 2 the arrival.
     * The fixture deliberately keeps a stretch of route BEYOND the turn so that a
     * rider standing past the turn is not "arrived" (the arrival latch would
     * otherwise short-circuit the maneuver bookkeeping).
     */
    private data class Fixture(
        val engine: NavEngine,
        val origin: LatLng,
        val beforeTurn: LatLng,
        val atTurn: LatLng,
        val pastTurn: LatLng,
        val destination: LatLng
    )

    private fun fixture(): Fixture {
        val origin = LatLng(18.5200, 73.8500)
        val beforeTurn = LatLng(18.5225, 73.8500)   // ~278 m north of the origin
        val atTurn = LatLng(18.5250, 73.8500)       // the turn vertex
        val pastTurn = LatLng(18.5250, 73.8600)     // ~1000 m further along the route
        val destination = LatLng(18.5250, 73.8700)  // ~2000 m further

        val dOriginToTurn = origin.distanceTo(atTurn) * 1000.0
        val dTurnToPast = atTurn.distanceTo(pastTurn) * 1000.0
        val dPastToEnd = pastTurn.distanceTo(destination) * 1000.0

        val step0 = NavigationStep("Head left", "Origin Road", dOriginToTurn, 60.0, "depart", "left")
        val step1 = NavigationStep("Turn right", "Cross Road", dTurnToPast, 90.0, "turn", "right")
        val step2 = NavigationStep("Arrive at destination", "Destination", dPastToEnd, 60.0, "arrive", "straight")

        val engine = NavEngine()
        engine.setRoute(
            RouteResult(
                coordinates = listOf(origin, beforeTurn, atTurn, pastTurn, destination),
                totalDistanceMeters = dOriginToTurn + dTurnToPast + dPastToEnd,
                totalDurationSeconds = 240.0,
                steps = listOf(step0, step1, step2)
            )
        )
        return Fixture(engine, origin, beforeTurn, atTurn, pastTurn, destination)
    }

    private fun fix(latLng: LatLng, accuracy: Float = 5f) =
        LocationUpdate(latLng, speedKmh = 30.0, headingDeg = 0f, accuracyMeters = accuracy)

    @Test
    fun `the shown instruction is the maneuver the distance refers to`() {
        val f = fixture()

        // Before the turn: the upcoming maneuver is "Turn right" and the distance
        // must count down to THAT maneuver's vertex.
        val progress = f.engine.processLocation(fix(f.origin))
        val shown = f.engine.getCurrentStep()

        assertNotNull("a step must be shown", shown)
        assertEquals("Turn right", shown!!.instruction)
        assertEquals(
            "the shown step must be the upcoming maneuver",
            progress.upcomingManeuverIndex,
            f.engine.progress.value.upcomingManeuverIndex
        )
        assertEquals(
            "the distance must refer to the shown step's vertex",
            1,
            progress.upcomingManeuverIndex
        )
    }

    @Test
    fun `the distance decreases toward the upcoming maneuver and never describes the previous one`() {
        val f = fixture()

        val atOrigin = f.engine.processLocation(fix(f.origin))
        val approaching = f.engine.processLocation(fix(f.beforeTurn))

        assertTrue(
            "distance must shrink as the rider approaches (${atOrigin.distanceToNextManeuverMeters} → ${approaching.distanceToNextManeuverMeters})",
            approaching.distanceToNextManeuverMeters < atOrigin.distanceToNextManeuverMeters
        )
        assertEquals("Turn right", f.engine.getCurrentStep()?.instruction)
    }

    @Test
    fun `after passing the maneuver the next step is shown`() {
        val f = fixture()
        f.engine.processLocation(fix(f.origin))
        f.engine.processLocation(fix(f.beforeTurn))

        val afterTurn = f.engine.processLocation(fix(f.pastTurn))
        // The turn is behind the rider: the arrival step is now the upcoming one
        // and the distance counts down to the destination.
        assertEquals("Arrive at destination", f.engine.getCurrentStep()?.instruction)
        assertTrue(
            "distance must now refer to the remaining route",
            afterTurn.distanceToNextManeuverMeters > 0
        )
        assertNull("no further step exists", f.engine.getNextStep())
    }

    @Test
    fun `the distance always matches the polyline distance to the shown maneuver`() {
        val f = fixture()
        val progress = f.engine.processLocation(fix(f.origin))
        val step = f.engine.getCurrentStep()
        assertNotNull(step)

        // The distance must be the road distance to the shown maneuver's vertex —
        // not the straight-line distance to the previous step's vertex.
        val expectedRoadDistance = f.origin.distanceTo(f.atTurn) * 1000.0
        assertTrue(
            "expected the road distance to the turn (~$expectedRoadDistance m), got ${progress.distanceToNextManeuverMeters}",
            abs(progress.distanceToNextManeuverMeters - expectedRoadDistance) < 60.0
        )
    }

    @Test
    fun `a single fix never advances more than one maneuver`() {
        val f = fixture()
        f.engine.processLocation(fix(f.origin))

        // One fix far beyond the turn must not skip multiple maneuvers.
        val jumped = f.engine.processLocation(fix(f.pastTurn))
        assertEquals(
            "the performed-maneuver counter may advance at most one step per fix",
            1,
            jumped.currentManeuverIndex
        )
    }

    @Test
    fun `an inaccurate fix never advances the performed maneuver counter`() {
        val f = fixture()
        f.engine.processLocation(fix(f.origin))

        // A stale/poor fix (>30 m accuracy) must not advance the counter.
        val stale = f.engine.processLocation(fix(f.atTurn, accuracy = 80f))
        assertEquals("a poor fix must not advance the maneuver", 0, stale.currentManeuverIndex)
        assertTrue("the stale state must be flagged", stale.isStaleGps)
    }

    @Test
    fun `an inaccurate fix still updates the upcoming maneuver from the projection`() {
        val f = fixture()
        f.engine.processLocation(fix(f.origin))

        // The projection is monotonic and independent of the performed counter,
        // so guidance stays correct even when a fix is inaccurate.
        val stale = f.engine.processLocation(fix(f.pastTurn, accuracy = 80f))
        assertTrue(
            "the upcoming maneuver must still follow the projected position",
            stale.upcomingManeuverIndex >= 1
        )
    }

    @Test
    fun `a route with no steps falls back safely`() {
        val engine = NavEngine()
        val pts = listOf(LatLng(18.52, 73.85), LatLng(18.55, 73.85))
        engine.setRoute(RouteResult(pts, 3300.0, 200.0, emptyList()))

        val progress = engine.processLocation(fix(pts[0]))
        assertNull("no step can be shown", engine.getCurrentStep())
        assertEquals(0, progress.upcomingManeuverIndex)
        assertTrue("distance falls back to the remaining route", progress.distanceToNextManeuverMeters > 0)
    }

    @Test
    fun `announcement history is reset when a new route is installed`() {
        // B-02: the audio layer suppresses any maneuver index that is not
        // greater than the last spoken one. A new route restarts the maneuver
        // indices at 0, so the suppression must be cleared with the route —
        // otherwise the new route's first maneuvers are silent.
        val history = AnnouncementHistory()

        assertEquals("nothing announced yet", -1, history.lastSpokenManeuverIndex)
        assertTrue(
            "the first maneuver of a route must be announced",
            history.shouldAnnounce(0)
        )

        history.markSpoken(0)
        history.markSpoken(3)
        assertEquals(3, history.lastSpokenManeuverIndex)

        assertTrue(
            "a maneuver below the history must be suppressed (new route, index 0)",
            !history.shouldAnnounce(0)
        )
        assertTrue(
            "the same maneuver must never be announced twice",
            !history.shouldAnnounce(3)
        )
        assertTrue(
            "a new maneuver must be announced",
            history.shouldAnnounce(4)
        )

        // A new route clears the history, so its first maneuver is announced again.
        history.reset()
        assertEquals(-1, history.lastSpokenManeuverIndex)
        assertTrue(
            "after a route change the first maneuver must be announced again",
            history.shouldAnnounce(0)
        )
    }
}
