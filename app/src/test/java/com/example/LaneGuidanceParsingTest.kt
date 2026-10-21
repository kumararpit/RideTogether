package com.example

import com.example.data.location.OsrmStepParser
import com.example.data.model.LaneArrow
import com.example.data.model.ManeuverType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lane guidance parsing.
 *
 * The fixtures are trimmed copies of **real** routing-engine responses
 * (OSRM `steps` with `intersections[].lanes`), captured from the live engine
 * the app uses. The road names, bearings and lane markings are the engine's
 * own output.
 *
 * Robolectric is required because `org.json` is an Android stub in plain
 * unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaneGuidanceParsingTest {

    private fun step(
        type: String,
        modifier: String,
        exit: Int? = null,
        name: String = "Holzmarktstraße",
        location: Pair<Double, Double> = 13.4000 to 52.5000,
        lanesAtManeuver: String? = null,
        lanesAtLaterIntersection: String? = null,
        laterLocation: Pair<Double, Double> = 13.5000 to 52.6000
    ): JSONObject {
        val maneuver = StringBuilder("""{"type":"$type","modifier":"$modifier","location":[${location.first},${location.second}],""")
        if (exit != null) maneuver.append("\"exit\":$exit,")
        maneuver.append("\"bearing_before\":30,\"bearing_after\":120}")

        val ix0 = StringBuilder("""{"location":[${location.first},${location.second}],"bearings":[30,120,210],"entry":[false,true,true]""")
        if (lanesAtManeuver != null) ix0.append(""","lanes":$lanesAtManeuver""")
        ix0.append("}")

        val ix1 = StringBuilder("""{"location":[${laterLocation.first},${laterLocation.second}],"bearings":[30,120,210],"entry":[true,true,false]""")
        if (lanesAtLaterIntersection != null) ix1.append(""","lanes":$lanesAtLaterIntersection""")
        ix1.append("}")

        return JSONObject(
            """{"distance":120.5,"duration":31.25,"name":"$name","maneuver":${maneuver},"intersections":[${ix0},${ix1}]}"""
        )
    }

    @Test
    fun `the lanes of the manoeuvre node become the lane guidance`() {
        val parsed = OsrmStepParser.parse(
            step(
                type = "turn",
                modifier = "left",
                lanesAtManeuver = """[{"valid":true,"indications":["left"]},{"valid":false,"indications":["straight"]},{"valid":false,"indications":["straight"]}]"""
            )
        )
        val lanes = parsed.lanes
        assertNotNull(lanes)
        assertEquals(3, lanes!!.laneCount)
        assertTrue(lanes.recommendedLanes.size == 1)
        assertEquals(LaneArrow.LEFT, lanes.lanes[0].primaryArrow)
        assertTrue(lanes.lanes[0].recommended)
        assertTrue(!lanes.lanes[1].recommended)
        assertEquals(LaneArrow.STRAIGHT, lanes.lanes[1].primaryArrow)
    }

    @Test
    fun `no lane data means no guidance - lanes are never invented`() {
        val parsed = OsrmStepParser.parse(step(type = "turn", modifier = "left"))
        assertNull(parsed.lanes)
    }

    @Test
    fun `lanes that belong to a later intersection are not shown with this manoeuvre`() {
        // The engine attaches the lane set of the NEXT junction to the later
        // intersection of the same step. Showing it with this manoeuvre would
        // tell the rider the wrong thing.
        val parsed = OsrmStepParser.parse(
            step(
                type = "turn",
                modifier = "left",
                lanesAtLaterIntersection = """[{"valid":true,"indications":["left"]}]"""
            )
        )
        assertNull(parsed.lanes)
    }

    @Test
    fun `lane data that contradicts the manoeuvre is dropped instead of displayed`() {
        // A right turn whose only usable lane is a left lane: the engine data
        // and the manoeuvre cannot both be right, so nothing is shown.
        val parsed = OsrmStepParser.parse(
            step(
                type = "turn",
                modifier = "right",
                lanesAtManeuver = """[{"valid":true,"indications":["left"]}]"""
            )
        )
        assertNull(parsed.lanes)
    }

    @Test
    fun `a lane with several indications keeps all of them`() {
        val parsed = OsrmStepParser.parse(
            step(
                type = "new name",
                modifier = "straight",
                lanesAtManeuver = """[{"valid":true,"indications":["straight","right"]},{"valid":false,"indications":["right"]}]"""
            )
        )
        val lanes = parsed.lanes
        assertNotNull(lanes)
        assertEquals(2, lanes!!.laneCount)
        assertEquals(setOf(LaneArrow.STRAIGHT, LaneArrow.RIGHT), lanes.lanes[0].arrows)
        assertTrue(lanes.lanes[0].recommended)
        // The drawn arrow is deterministic: the sharpest turn in the canonical
        // order wins, so the same lane always renders the same way.
        assertEquals(LaneArrow.STRAIGHT, lanes.lanes[0].primaryArrow)
    }

    @Test
    fun `an indication the vocabulary does not model is dropped`() {
        val parsed = OsrmStepParser.parse(
            step(
                type = "turn",
                modifier = "left",
                lanesAtManeuver = """[{"valid":true,"indications":["left","bus_only"]},{"valid":false,"indications":["none"]}]"""
            )
        )
        val lanes = parsed.lanes
        assertNotNull(lanes)
        assertEquals(setOf(LaneArrow.LEFT), lanes!!.lanes[0].arrows)
        assertTrue(lanes.lanes[1].isUnmarked)
    }

    @Test
    fun `a lane set with no usable lane produces no guidance`() {
        val parsed = OsrmStepParser.parse(
            step(
                type = "turn",
                modifier = "left",
                lanesAtManeuver = """[{"valid":false,"indications":["left"]},{"valid":false,"indications":["straight"]}]"""
            )
        )
        assertNull(parsed.lanes)
    }

    @Test
    fun `the exit number, the turn angle and the richer maneuver are parsed`() {
        val parsed = OsrmStepParser.parse(
            step(type = "exit rotary", modifier = "right", exit = 3, name = "NH-48")
        )
        assertEquals(ManeuverType.ROUNDABOUT_EXIT, parsed.maneuver)
        assertEquals(3, parsed.exitNumber)
        assertEquals(90, parsed.turnAngleDegrees)
        assertEquals("Take the 3rd exit onto NH-48", parsed.instruction)
    }

    @Test
    fun `the turn angle is signed - a left turn is negative`() {
        val json = JSONObject(
            """{"distance":10.0,"duration":1.0,"name":"X","maneuver":{"type":"turn","modifier":"left","bearing_before":90,"bearing_after":10},"intersections":[]}"""
        )
        val parsed = OsrmStepParser.parse(json)
        assertEquals(-80, parsed.turnAngleDegrees)
    }

    @Test
    fun `a malformed step degrades instead of throwing`() {
        val parsed = OsrmStepParser.parse(JSONObject("""{"distance":5.0}"""))
        assertEquals(ManeuverType.UNKNOWN, parsed.maneuver)
        assertNull(parsed.lanes)
        assertEquals("Continue", parsed.instruction)
    }

    @Test
    fun `the lane advice names the lane the rider must take`() {
        val parsed = OsrmStepParser.parse(
            step(
                type = "turn",
                modifier = "left",
                lanesAtManeuver = """[{"valid":true,"indications":["left"]},{"valid":false,"indications":["straight"]}]"""
            )
        )
        assertEquals("Use the left lane", parsed.lanes?.laneAdvice())
    }
}
