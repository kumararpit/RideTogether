package com.example.data.model

/**
 * RideTogether's own maneuver vocabulary.
 *
 * ## Why this exists
 *
 * The navigation engine used by RideTogether (OSRM) reports a manoeuvre as a
 * `(type, modifier)` pair plus an optional exit number and the bearings
 * before/after the manoeuvre. The pair is a *transport* encoding: it is fine
 * for a router, but it is lossy for a rider. "turn" + "slight left" is a bear
 * left; "continue" + "slight left" is a keep left; "exit rotary" + exit `3`
 * is "take the third exit of the roundabout". Collapsing all of that into the
 * two raw strings is what made the old icon mapping fall through to a generic
 * arrow (the B-19 backlog item).
 *
 * This enum is the **RideTogether-original** behavioural vocabulary. It is
 * derived from two public, non-proprietary sources:
 *
 *  1. the OSRM manoeuvre vocabulary (open source, the routing engine this app
 *     already uses), and
 *  2. the *behavioural* categories a navigation UI needs, which were
 *     characterised from the reference implementation's turn-card model
 *     (turn / fork / merge / ramp / roundabout / u-turn / destination / name
 *     change / keep) — see `docs/reverse-engineering/feature-13-lane-guidance.md`
 *     for the evidence and the exact boundary of what was and was not
 *     recoverable.
 *
 * No proprietary asset, icon or wording is reproduced here: every label in
 * this file is RideTogether's own.
 */
enum class ManeuverType {
    /** Start of the route. */
    DEPART,

    /** End of the route (or of a leg, when the plan has intermediate stops). */
    ARRIVE,

    /** A normal left turn. */
    TURN_LEFT,

    /** A gentle left turn ("bear left"). */
    TURN_SLIGHT_LEFT,

    /** A hard left turn. */
    TURN_SHARP_LEFT,

    /** A normal right turn. */
    TURN_RIGHT,

    /** A gentle right turn ("bear right"). */
    TURN_SLIGHT_RIGHT,

    /** A hard right turn. */
    TURN_SHARP_RIGHT,

    /** Stay in the left lane at a diverge ("keep left"). */
    KEEP_LEFT,

    /** Stay in the right lane at a diverge ("keep right"). */
    KEEP_RIGHT,

    /** Carry on along the current road. */
    CONTINUE,

    /** The road changes name but the direction does not. */
    NEW_NAME,

    /** Enter a slip road / ramp onto a faster road. */
    ON_RAMP,

    /** Leave the main road onto a slip road / ramp. */
    OFF_RAMP,

    /** The road splits; the route takes the left branch. */
    FORK_LEFT,

    /** The road splits; the route takes the right branch. */
    FORK_RIGHT,

    /** Join a road from the left. */
    MERGE_LEFT,

    /** Join a road from the right. */
    MERGE_RIGHT,

    /** Enter a roundabout / rotary (the exit itself is a separate step). */
    ROUNDABOUT_ENTER,

    /** Leave a roundabout / rotary onto a numbered exit. */
    ROUNDABOUT_EXIT,

    /** The road ends and the route has to turn onto the road ahead. */
    END_OF_ROAD,

    /** Turn back the way you came. */
    U_TURN,

    /** Board a ferry. */
    FERRY,

    /** The engine reported something this vocabulary does not model. */
    UNKNOWN;

    /** The direction the rider has to take, if the manoeuvre implies one. */
    val turnDirection: TurnDirection
        get() = when (this) {
            TURN_LEFT, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT, KEEP_LEFT, FORK_LEFT, MERGE_LEFT -> TurnDirection.LEFT
            TURN_RIGHT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT, KEEP_RIGHT, FORK_RIGHT, MERGE_RIGHT -> TurnDirection.RIGHT
            U_TURN -> TurnDirection.UTURN
            else -> TurnDirection.AHEAD
        }

    /** True for the manoeuvres that put the rider on a different road. */
    val isTurn: Boolean
        get() = when (this) {
            TURN_LEFT, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT,
            TURN_RIGHT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT,
            KEEP_LEFT, KEEP_RIGHT, U_TURN -> true
            else -> false
        }

    /**
     * The lane arrows that can legally carry this manoeuvre.
     *
     * This is a **safety filter**, not a source of truth: the engine's own
     * `valid` flag decides which lanes are usable, and this set is only used
     * to reject lane data that plainly contradicts the manoeuvre it is
     * attached to (see `LaneGuidance`).
     */
    val permittedLaneArrows: Set<LaneArrow>
        get() = when (this) {
            TURN_LEFT, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT ->
                setOf(LaneArrow.LEFT, LaneArrow.SLIGHT_LEFT, LaneArrow.SHARP_LEFT)
            TURN_RIGHT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT ->
                setOf(LaneArrow.RIGHT, LaneArrow.SLIGHT_RIGHT, LaneArrow.SHARP_RIGHT)
            U_TURN -> setOf(LaneArrow.U_TURN, LaneArrow.LEFT, LaneArrow.RIGHT)
            END_OF_ROAD -> setOf(
                LaneArrow.LEFT, LaneArrow.SLIGHT_LEFT, LaneArrow.SHARP_LEFT,
                LaneArrow.RIGHT, LaneArrow.SLIGHT_RIGHT, LaneArrow.SHARP_RIGHT
            )
            else -> setOf(
                LaneArrow.STRAIGHT, LaneArrow.SLIGHT_LEFT, LaneArrow.SLIGHT_RIGHT,
                LaneArrow.LEFT, LaneArrow.RIGHT
            )
        }

    /**
     * The rider-facing instruction for this manoeuvre.
     *
     * @param roadName the road the manoeuvre leads onto (may be blank).
     * @param exitNumber the engine's exit number, if it reported one. For a
     *   roundabout this is the **ordinal** of the exit ("take the 2nd exit");
     *   for a ramp it is the road's exit number ("take exit 5").
     * @param stopName the name of the stop this manoeuvre arrives at, when the
     *   plan has intermediate stops.
     */
    fun instruction(roadName: String?, exitNumber: Int? = null, stopName: String? = null): String {
        val road = roadName?.trim().orEmpty()
        val onto = if (road.isNotEmpty()) " onto $road" else ""
        return when (this) {
            DEPART -> if (road.isNotEmpty()) "Head out on $road" else "Head out"
            ARRIVE -> if (!stopName.isNullOrBlank()) "Arrive at stop: $stopName" else "Arrive at destination"
            TURN_LEFT -> "Turn left$onto"
            TURN_SLIGHT_LEFT -> "Bear left$onto"
            TURN_SHARP_LEFT -> "Turn sharp left$onto"
            TURN_RIGHT -> "Turn right$onto"
            TURN_SLIGHT_RIGHT -> "Bear right$onto"
            TURN_SHARP_RIGHT -> "Turn sharp right$onto"
            KEEP_LEFT -> "Keep left$onto"
            KEEP_RIGHT -> "Keep right$onto"
            CONTINUE -> "Continue$onto"
            NEW_NAME -> "Continue onto $road".let { if (road.isEmpty()) "Continue" else it }
            ON_RAMP -> if (exitNumber != null) "Take the ramp (exit $exitNumber)$onto" else "Take the ramp$onto"
            OFF_RAMP -> if (exitNumber != null) "Take exit $exitNumber$onto" else "Take the ramp$onto"
            FORK_LEFT -> "Keep left at the fork$onto"
            FORK_RIGHT -> "Keep right at the fork$onto"
            MERGE_LEFT -> "Merge left$onto"
            MERGE_RIGHT -> "Merge right$onto"
            ROUNDABOUT_ENTER -> "Enter the roundabout"
            ROUNDABOUT_EXIT -> if (exitNumber != null) {
                "Take the ${ordinal(exitNumber)} exit$onto"
            } else {
                "Exit the roundabout$onto"
            }
            END_OF_ROAD -> "Turn at the end of the road$onto"
            U_TURN -> "Make a U-turn$onto"
            FERRY -> "Take the ferry$onto"
            UNKNOWN -> "Continue$onto"
        }
    }

    /** Spoken form of the instruction — no punctuation the TTS would read out. */
    fun voiceInstruction(roadName: String?, exitNumber: Int? = null, stopName: String? = null): String =
        instruction(roadName, exitNumber, stopName)
            .replace(",", "")
            .replace("/", " ")
            .trim()

    companion object {
        /**
         * Maps the routing engine's `(type, modifier)` pair onto this
         * vocabulary.
         *
         * The mapping is intentionally total: an unrecognised pair becomes
         * [UNKNOWN] rather than being silently treated as a plain turn.
         *
         * @param type the engine's manoeuvre type, e.g. `"turn"`.
         * @param modifier the engine's manoeuvre modifier, e.g. `"slight left"`.
         */
        fun from(type: String?, modifier: String?): ManeuverType {
            val t = type?.trim()?.lowercase().orEmpty()
            val m = modifier?.trim()?.lowercase().orEmpty()
            return when (t) {
                "depart" -> DEPART
                "arrive" -> ARRIVE
                "turn" -> when (m) {
                    "left" -> TURN_LEFT
                    "right" -> TURN_RIGHT
                    "slight left" -> TURN_SLIGHT_LEFT
                    "slight right" -> TURN_SLIGHT_RIGHT
                    "sharp left" -> TURN_SHARP_LEFT
                    "sharp right" -> TURN_SHARP_RIGHT
                    "uturn" -> U_TURN
                    "straight" -> CONTINUE
                    else -> UNKNOWN
                }
                "new name" -> NEW_NAME
                "continue" -> when (m) {
                    "slight left" -> KEEP_LEFT
                    "slight right" -> KEEP_RIGHT
                    "uturn" -> U_TURN
                    else -> CONTINUE
                }
                "on ramp" -> ON_RAMP
                "off ramp" -> OFF_RAMP
                "fork" -> when (m) {
                    "right", "slight right" -> FORK_RIGHT
                    "left", "slight left" -> FORK_LEFT
                    else -> CONTINUE
                }
                "merge" -> when (m) {
                    "right", "slight right" -> MERGE_RIGHT
                    "left", "slight left" -> MERGE_LEFT
                    else -> CONTINUE
                }
                "roundabout", "rotary" -> ROUNDABOUT_ENTER
                "exit roundabout", "exit rotary" -> ROUNDABOUT_EXIT
                "end of road" -> END_OF_ROAD
                "ferry" -> FERRY
                else -> UNKNOWN
            }
        }

        /** "1st", "2nd", "3rd", "4th" … for roundabout exit numbers. */
        fun ordinal(n: Int): String {
            if (n < 0) return "$n"
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
            return "$n$suffix"
        }
    }
}

/** The direction a manoeuvre takes the rider. */
enum class TurnDirection { LEFT, RIGHT, AHEAD, UTURN }
