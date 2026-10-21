package com.example.data.location

import com.example.data.model.LaneArrow
import com.example.data.model.LaneGuidance
import com.example.data.model.LaneMark
import com.example.data.model.ManeuverType
import org.json.JSONObject

/**
 * Parses one routing-engine step into RideTogether's richer maneuver model.
 *
 * The parser is **pure and total**: it never throws on a malformed step, and
 * every field it cannot understand degrades to a safe default instead of
 * being guessed.
 *
 * ## Lane data — where it comes from
 *
 * The engine reports the lane marking of the carriageway the rider is on at
 * each intersection as
 * `intersections[i].lanes = [{ "valid": bool, "indications": ["left", …] }]`.
 * The lanes that belong to a **manoeuvre** are the ones attached to the
 * manoeuvre's own node, which the engine reports as `maneuver.location`; the
 * lanes attached to the other intersections of the same step belong to the
 * *next* manoeuvre and must not be shown with this one (the same
 * "one instruction, one distance" discipline the banner follows — audit F1-D1).
 *
 * `valid` is the engine's own verdict about which lanes the route may use and
 * is copied verbatim. RideTogether never invents a lane, never counts lanes
 * and never highlights a lane the engine did not mark as valid. If the lane
 * data contradicts the manoeuvre it is attached to, the guidance is dropped
 * rather than displayed.
 */
object OsrmStepParser {

    /** Everything the parser could extract from one engine step. */
    data class ParsedStep(
        val engineType: String,
        val engineModifier: String,
        val maneuver: ManeuverType,
        val exitNumber: Int?,
        val bearingBefore: Int?,
        val bearingAfter: Int?,
        /** Signed turn angle in degrees (negative = left, positive = right). */
        val turnAngleDegrees: Int?,
        val roadName: String,
        val distanceMeters: Double,
        val durationSeconds: Double,
        /** The lane guidance for THIS manoeuvre, or null when there is none. */
        val lanes: LaneGuidance?,
        /** The rider-facing instruction (without any stop context). */
        val instruction: String
    )

    /** Parses one step object. */
    fun parse(step: JSONObject): ParsedStep {
        val maneuverObj = step.optJSONObject("maneuver")
        // A step without a maneuver object is malformed: it is reported as
        // UNKNOWN rather than being turned into a fabricated "turn".
        val engineType = maneuverObj?.optString("type", "unknown").orEmpty().ifBlank { "unknown" }
        val engineModifier = maneuverObj?.optString("modifier", "unknown").orEmpty().ifBlank { "unknown" }
        val exitNumber = maneuverObj?.optIntOrNull("exit")
        val bearingBefore = maneuverObj?.optIntOrNull("bearing_before")
        val bearingAfter = maneuverObj?.optIntOrNull("bearing_after")
        val maneuver = ManeuverType.from(engineType, engineModifier)
        // The road name is reported exactly as the engine reported it. A
        // nameless road stays nameless — a placeholder name would put a road
        // in the instruction that does not exist.
        val roadName = step.optString("name", "")
        val turnAngle = if (bearingBefore != null && bearingAfter != null) {
            normalizeDegrees(bearingAfter - bearingBefore)
        } else {
            null
        }
        val lanes = parseLanes(step, maneuver)
        return ParsedStep(
            engineType = engineType,
            engineModifier = engineModifier,
            maneuver = maneuver,
            exitNumber = exitNumber,
            bearingBefore = bearingBefore,
            bearingAfter = bearingAfter,
            turnAngleDegrees = turnAngle,
            roadName = roadName,
            distanceMeters = step.optDouble("distance", 0.0),
            durationSeconds = step.optDouble("duration", 0.0),
            lanes = lanes,
            instruction = maneuver.instruction(roadName, exitNumber)
        )
    }

    /**
     * Extracts the lane guidance of the manoeuvre this step describes.
     *
     * Returns null when the engine supplied no lane data for the manoeuvre
     * node, or when the supplied data contradicts the manoeuvre.
     */
    fun parseLanes(step: JSONObject, maneuver: ManeuverType): LaneGuidance? {
        val intersections = step.optJSONArray("intersections") ?: return null
        if (intersections.length() == 0) return null

        val maneuverLocation = step.optJSONObject("maneuver")?.optJSONArray("location")
        val target = findManeuverIntersection(intersections, maneuverLocation) ?: return null

        val rawLanes = target.optJSONArray("lanes") ?: return null
        if (rawLanes.length() == 0) return null

        val marks = mutableListOf<LaneMark>()
        for (i in 0 until rawLanes.length()) {
            val lane = rawLanes.optJSONObject(i) ?: continue
            val indications = lane.optJSONArray("indications")
            val arrows = LinkedHashSet<LaneArrow>()
            if (indications != null) {
                for (j in 0 until indications.length()) {
                    val arrow = LaneArrow.parse(indications.optString(j))
                    // "none" means the lane carries no usable marking; it is
                    // modelled as an absent arrow, not as an arrow.
                    if (arrow != null && arrow != LaneArrow.NONE) arrows.add(arrow)
                }
            }
            val valid = lane.optBoolean("valid", false)
            marks.add(LaneMark(arrows = arrows, recommended = valid))
        }
        if (marks.isEmpty()) return null

        val guidance = LaneGuidance(marks)
        if (!guidance.hasRecommendedLane) return null

        // Safety net: the engine's `valid` flag is authoritative, but a lane
        // set that cannot carry this manoeuvre at all would be actively
        // misleading, so it is dropped instead of displayed.
        val consistent = guidance.recommendedLanes.any { mark ->
            mark.arrows.any { it in maneuver.permittedLaneArrows }
        }
        if (!consistent) return null

        return guidance
    }

    /**
     * Finds the intersection that carries the manoeuvre.
     *
     * The engine reports the manoeuvre node as `maneuver.location`; the
     * intersection at the same coordinates is the one whose lane data belongs
     * to this manoeuvre. The lookup is by location (not by index) so a
     * response that orders the intersections differently can never pair the
     * wrong lanes with the instruction. When the location cannot be matched,
     * the single-intersection case is the only safe fallback.
     */
    private fun findManeuverIntersection(
        intersections: org.json.JSONArray,
        maneuverLocation: org.json.JSONArray?
    ): JSONObject? {
        if (maneuverLocation != null && maneuverLocation.length() >= 2) {
            val lon = maneuverLocation.optDouble(0, Double.NaN)
            val lat = maneuverLocation.optDouble(1, Double.NaN)
            if (!lon.isNaN() && !lat.isNaN()) {
                for (i in 0 until intersections.length()) {
                    val ix = intersections.optJSONObject(i) ?: continue
                    val loc = ix.optJSONArray("location") ?: continue
                    if (loc.length() < 2) continue
                    if (samePosition(loc.optDouble(0, Double.NaN), loc.optDouble(1, Double.NaN), lon, lat)) {
                        return ix
                    }
                }
            }
        }
        // No location match: only trust the first intersection when the step
        // has exactly one (then it can only be the manoeuvre node).
        return if (intersections.length() == 1) intersections.optJSONObject(0) else null
    }

    private fun samePosition(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Boolean {
        if (lon1.isNaN() || lat1.isNaN() || lon2.isNaN() || lat2.isNaN()) return false
        // ~1 cm tolerance: the engine echoes the same node coordinates.
        return kotlin.math.abs(lon1 - lon2) < 1e-7 && kotlin.math.abs(lat1 - lat2) < 1e-7
    }

    /** Normalizes an angle difference into (-180, 180]. */
    fun normalizeDegrees(delta: Int): Int {
        var d = delta % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }
}

/** Reads an optional integer from a JSON object (absent or null → null). */
private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key, 0)
}
