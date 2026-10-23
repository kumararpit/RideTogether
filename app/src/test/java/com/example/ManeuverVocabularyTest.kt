package com.example

import com.example.data.model.ManeuverType
import com.example.data.model.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maneuver vocabulary: how the routing engine's `(type, modifier)` pair
 * maps onto RideTogether's own vocabulary, and what the rider is told.
 */
class ManeuverVocabularyTest {

    @Test
    fun `the engine pair maps onto the richer vocabulary`() {
        assertEquals(ManeuverType.DEPART, ManeuverType.from("depart", "left"))
        assertEquals(ManeuverType.ARRIVE, ManeuverType.from("arrive", "straight"))
        assertEquals(ManeuverType.TURN_LEFT, ManeuverType.from("turn", "left"))
        assertEquals(ManeuverType.TURN_RIGHT, ManeuverType.from("turn", "right"))
        assertEquals(ManeuverType.TURN_SLIGHT_LEFT, ManeuverType.from("turn", "slight left"))
        assertEquals(ManeuverType.TURN_SLIGHT_RIGHT, ManeuverType.from("turn", "slight right"))
        assertEquals(ManeuverType.TURN_SHARP_LEFT, ManeuverType.from("turn", "sharp left"))
        assertEquals(ManeuverType.TURN_SHARP_RIGHT, ManeuverType.from("turn", "sharp right"))
        assertEquals(ManeuverType.NEW_NAME, ManeuverType.from("new name", "straight"))
        assertEquals(ManeuverType.ON_RAMP, ManeuverType.from("on ramp", "slight right"))
        assertEquals(ManeuverType.OFF_RAMP, ManeuverType.from("off ramp", "slight left"))
        assertEquals(ManeuverType.FORK_RIGHT, ManeuverType.from("fork", "slight right"))
        assertEquals(ManeuverType.MERGE_RIGHT, ManeuverType.from("merge", "slight right"))
        assertEquals(ManeuverType.MERGE_LEFT, ManeuverType.from("merge", "slight left"))
        assertEquals(ManeuverType.ROUNDABOUT_ENTER, ManeuverType.from("roundabout", "right"))
        assertEquals(ManeuverType.ROUNDABOUT_EXIT, ManeuverType.from("exit roundabout", "right"))
        assertEquals(ManeuverType.ROUNDABOUT_EXIT, ManeuverType.from("exit rotary", "right"))
        assertEquals(ManeuverType.END_OF_ROAD, ManeuverType.from("end of road", "left"))
        assertEquals(ManeuverType.FERRY, ManeuverType.from("ferry", "straight"))
    }

    @Test
    fun `a continue with a slight modifier is a keep, not a turn`() {
        // This is the distinction the raw pair cannot express: "turn/slight
        // left" is a bear left, "continue/slight left" is a keep left.
        assertEquals(ManeuverType.KEEP_LEFT, ManeuverType.from("continue", "slight left"))
        assertEquals(ManeuverType.KEEP_RIGHT, ManeuverType.from("continue", "slight right"))
        assertEquals(ManeuverType.CONTINUE, ManeuverType.from("continue", "straight"))
        assertTrue(ManeuverType.KEEP_LEFT.turnDirection == TurnDirection.LEFT)
        assertTrue(ManeuverType.KEEP_RIGHT.turnDirection == TurnDirection.RIGHT)
    }

    @Test
    fun `an unknown pair is unknown, never silently a turn`() {
        assertEquals(ManeuverType.UNKNOWN, ManeuverType.from(null, null))
        assertEquals(ManeuverType.UNKNOWN, ManeuverType.from("", ""))
        assertEquals(ManeuverType.UNKNOWN, ManeuverType.from("teleport", "left"))
        assertEquals(ManeuverType.UNKNOWN, ManeuverType.from("turn", "sideways"))
    }

    @Test
    fun `the instruction names the road the rider ends up on`() {
        assertEquals("Turn left onto Laxmi Chowk Road", ManeuverType.TURN_LEFT.instruction("Laxmi Chowk Road"))
        assertEquals("Bear left onto MH-66", ManeuverType.TURN_SLIGHT_LEFT.instruction("MH-66"))
        assertEquals("Turn sharp right onto Mumbai Hwy", ManeuverType.TURN_SHARP_RIGHT.instruction("Mumbai Hwy"))
        assertEquals("Keep right onto Katraj Bypass", ManeuverType.KEEP_RIGHT.instruction("Katraj Bypass"))
        assertEquals("Continue onto Schloßplatz", ManeuverType.NEW_NAME.instruction("Schloßplatz"))
    }

    @Test
    fun `a road without a name still produces a usable instruction`() {
        assertEquals("Turn left", ManeuverType.TURN_LEFT.instruction(""))
        assertEquals("Turn left", ManeuverType.TURN_LEFT.instruction(null))
        assertEquals("Continue", ManeuverType.CONTINUE.instruction(""))
    }

    @Test
    fun `a ramp exit number is announced as a road exit`() {
        assertEquals("Take exit 5 onto Old Mumbai Hwy", ManeuverType.OFF_RAMP.instruction("Old Mumbai Hwy", 5))
        assertEquals("Take the ramp (exit 5) onto Expressway", ManeuverType.ON_RAMP.instruction("Expressway", 5))
        assertEquals("Take the ramp onto Expressway", ManeuverType.ON_RAMP.instruction("Expressway", null))
    }

    @Test
    fun `a roundabout exit number is announced as an ordinal`() {
        assertEquals("Take the 2nd exit onto NH-48", ManeuverType.ROUNDABOUT_EXIT.instruction("NH-48", 2))
        assertEquals("Take the 3rd exit onto NH-48", ManeuverType.ROUNDABOUT_EXIT.instruction("NH-48", 3))
        assertEquals("Take the 1st exit onto NH-48", ManeuverType.ROUNDABOUT_EXIT.instruction("NH-48", 1))
        assertEquals("Take the 11th exit onto NH-48", ManeuverType.ROUNDABOUT_EXIT.instruction("NH-48", 11))
        assertEquals("Exit the roundabout onto NH-48", ManeuverType.ROUNDABOUT_EXIT.instruction("NH-48", null))
        assertEquals("Enter the roundabout", ManeuverType.ROUNDABOUT_ENTER.instruction("NH-48"))
    }

    @Test
    fun `the arrival instruction carries the stop name`() {
        assertEquals("Arrive at stop: Cafe A", ManeuverType.ARRIVE.instruction("Road", stopName = "Cafe A"))
        assertEquals("Arrive at destination", ManeuverType.ARRIVE.instruction("Road"))
    }

    @Test
    fun `a fork and a merge are different manoeuvres`() {
        assertEquals("Keep left at the fork onto A", ManeuverType.FORK_LEFT.instruction("A"))
        assertEquals("Keep right at the fork onto B", ManeuverType.FORK_RIGHT.instruction("B"))
        assertEquals("Merge left onto C", ManeuverType.MERGE_LEFT.instruction("C"))
        assertEquals("Merge right onto D", ManeuverType.MERGE_RIGHT.instruction("D"))
    }

    @Test
    fun `the spoken form drops characters the voice would read out`() {
        val spoken = ManeuverType.OFF_RAMP.voiceInstruction("Old Mumbai Hwy", 5)
        assertTrue("comma" !in spoken)
        assertTrue(spoken.startsWith("Take exit 5"))
    }

    @Test
    fun `permitted lane arrows follow the direction of the manoeuvre`() {
        assertTrue(LaneArrowIsPermitted(ManeuverType.TURN_LEFT, "LEFT"))
        assertTrue(!LaneArrowIsPermitted(ManeuverType.TURN_LEFT, "RIGHT"))
        assertTrue(LaneArrowIsPermitted(ManeuverType.TURN_RIGHT, "SHARP_RIGHT"))
        assertTrue(!LaneArrowIsPermitted(ManeuverType.TURN_RIGHT, "LEFT"))
        assertTrue(LaneArrowIsPermitted(ManeuverType.CONTINUE, "STRAIGHT"))
        assertTrue(LaneArrowIsPermitted(ManeuverType.U_TURN, "U_TURN"))
    }

    private fun LaneArrowIsPermitted(maneuver: ManeuverType, arrowName: String): Boolean {
        val arrow = com.example.data.model.LaneArrow.values().first { it.name == arrowName }
        return arrow in maneuver.permittedLaneArrows
    }
}
