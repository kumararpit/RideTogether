package com.example.data.amenities

import org.json.JSONObject
import java.util.Locale

/**
 * Whether a place is open right now, and the hours it says so.
 *
 * The data comes from the place's own `opening_hours` tag. The parser is
 * deliberately **conservative**: it understands the everyday subset of the
 * format and refuses everything else. A place whose hours cannot be read is
 * shown with **no** status at all — a wrong "Open" is worse than no status.
 *
 * Understood:
 *  - `24/7`
 *  - `Mo-Su 09:00-21:00`, `Mo-Fr 08:00-20:00`, `Sa,Su 10:00-18:00`
 *  - several ranges in one day: `Mo-Su 08:00-12:00,16:00-20:00`
 *  - several rules: `Mo-Fr 08:00-20:00; Sa 09:00-18:00`
 *  - `closed` / `off` (the place is not open)
 *
 * Not understood (and therefore never guessed): public holidays, "sunrise
 * to sunset", month/day rules, "open from ...", the `+`/`-`/`off` suffixes
 * beyond the plain ones.
 */
data class OpenStatus(
    /** The state of the place right now. */
    val state: State,
    /** The short line under the name, e.g. "Open · until 21:00". */
    val text: String
) {
    enum class State { OPEN, CLOSED, UNKNOWN }
}

/**
 * A read-only view of the moment the status is computed for.
 *
 * @param dayOfWeek 1 = Monday … 7 = Sunday.
 * @param minutesOfDay minutes since midnight.
 */
data class LocalTime(val dayOfWeek: Int, val minutesOfDay: Int)

/**
 * A conservative reader of the `opening_hours` tag.
 */
object OpeningHours {

    private val DAY_CODES = mapOf(
        "mo" to 1, "tu" to 2, "we" to 3, "th" to 4, "fr" to 5, "sa" to 6, "su" to 7
    )

    private val DAY_NAMES = mapOf(
        1 to "Monday", 2 to "Tuesday", 3 to "Wednesday", 4 to "Thursday",
        5 to "Friday", 6 to "Saturday", 7 to "Sunday"
    )

    private data class Window(val start: Int, val end: Int)

    private data class Rule(val days: Set<Int>, val windows: List<Window>, val closed: Boolean)

    /**
     * The status of a place with the given `opening_hours` value.
     *
     * @param value the raw tag value.
     * @param now the moment to evaluate.
     * @return the status, or null when the value cannot be read.
     */
    fun status(value: String?, now: LocalTime): OpenStatus? {
        val rules = parse(value) ?: return null
        if (rules.isEmpty()) return null

        // "closed" for the day in question wins over any window.
        val todayRules = rules.filter { now.dayOfWeek in it.days }
        if (todayRules.any { it.closed }) {
            return OpenStatus(OpenStatus.State.CLOSED, "Closed")
        }

        val todayWindows = todayRules.flatMap { it.windows }
        for (window in todayWindows) {
            if (now.minutesOfDay in window.start until window.end) {
                return OpenStatus(OpenStatus.State.OPEN, "Open · until ${formatTime(window.end)}")
            }
        }
        if (todayWindows.isNotEmpty()) {
            // Closed now, but it opens later today.
            val next = todayWindows.filter { it.start > now.minutesOfDay }.minByOrNull { it.start }
            if (next != null) {
                return OpenStatus(OpenStatus.State.CLOSED, "Closed · opens ${formatTime(next.start)}")
            }
        }
        // Closed now; the next opening is on another day.
        for (dayOffset in 1..7) {
            val day = ((now.dayOfWeek - 1 + dayOffset) % 7) + 1
            val dayRules = rules.filter { day in it.days && !it.closed }
            val windows = dayRules.flatMap { it.windows }
            if (windows.isNotEmpty()) {
                val first = windows.minByOrNull { it.start } ?: continue
                val whenText = if (dayOffset == 1) "tomorrow" else DAY_NAMES[day]
                return OpenStatus(OpenStatus.State.CLOSED, "Closed · opens $whenText ${formatTime(first.start)}")
            }
        }
        return OpenStatus(OpenStatus.State.CLOSED, "Closed")
    }

    /** Parses the tag into rules, or null when anything cannot be read. */
    private fun parse(value: String?): List<Rule>? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.equals("24/7", ignoreCase = true)) {
            return listOf(Rule(setOf(1, 2, 3, 4, 5, 6, 7), listOf(Window(0, 24 * 60)), closed = false))
        }
        if (raw.equals("closed", ignoreCase = true) || raw.equals("off", ignoreCase = true)) {
            return listOf(Rule(setOf(1, 2, 3, 4, 5, 6, 7), emptyList(), closed = true))
        }

        val rules = mutableListOf<Rule>()
        for (part in raw.split(";")) {
            val rule = parseRule(part.trim()) ?: return null
            rules.add(rule)
        }
        return rules
    }

    private fun parseRule(part: String): Rule? {
        if (part.isEmpty()) return null
        val lower = part.lowercase(Locale.US)
        if (lower == "closed" || lower == "off") {
            return Rule(setOf(1, 2, 3, 4, 5, 6, 7), emptyList(), closed = true)
        }
        // "Mo-Su 09:00-21:00" / "Mo-Fr 08:00-20:00; open" / "Sa 10:00-18:00"
        val tokens = part.split(" ", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("open", ignoreCase = true) }
        if (tokens.isEmpty()) return null

        val dayTokens = mutableListOf<String>()
        val timeTokens = mutableListOf<String>()
        for (token in tokens) {
            // A time always carries a colon ("09:00-21:00"); a day selector
            // never does ("Mo-Su", "Sa,Su").
            if (token.contains(":")) timeTokens.add(token) else dayTokens.add(token)
        }
        val days = parseDays(dayTokens.joinToString(",")) ?: return null
        if (timeTokens.isEmpty()) return null
        val windows = mutableListOf<Window>()
        for (token in timeTokens) {
            val window = parseWindow(token) ?: return null
            windows.add(window)
        }
        return Rule(days, windows, closed = false)
    }

    /** Parses "Mo-Su", "Mo-Fr", "Sa,Su", "Mo" into a set of day numbers. */
    private fun parseDays(text: String): Set<Int>? {
        if (text.isEmpty()) return null
        val days = mutableSetOf<Int>()
        for (piece in text.split(",")) {
            val p = piece.trim().lowercase(Locale.US)
            if (p.isEmpty()) return null
            if (p.contains("-")) {
                val range = p.split("-")
                if (range.size != 2) return null
                val from = DAY_CODES[range[0].trim()] ?: return null
                val to = DAY_CODES[range[1].trim()] ?: return null
                var day = from
                var guard = 0
                while (guard < 8) {
                    days.add(day)
                    if (day == to) break
                    day = if (day == 7) 1 else day + 1
                    guard++
                }
            } else {
                days.add(DAY_CODES[p] ?: return null)
            }
        }
        return days.ifEmpty { null }
    }

    /** Parses "09:00-21:00" (also "09:00" alone is not a window → null). */
    private fun parseWindow(text: String): Window? {
        val t = text.trim().lowercase(Locale.US)
        val dash = t.indexOf('-')
        if (dash <= 0) return null
        val start = parseTime(t.substring(0, dash)) ?: return null
        val end = parseTime(t.substring(dash + 1)) ?: return null
        if (end <= start) return null
        return Window(start, end)
    }

    private fun parseTime(text: String): Int? {
        val t = text.trim()
        val parts = t.split(":")
        if (parts.size != 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..24 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }

    private fun formatTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format(Locale.US, "%d:%02d", h, m)
    }
}

/**
 * Everything the place card can show about a place.
 *
 * Every field is optional: the card shows what the data has and nothing
 * else. A place without a rating does not get a rating, and a place whose
 * hours cannot be read does not get an "Open" badge.
 */
data class PlaceCard(
    val name: String?,
    val categoryName: String,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val distanceMeters: Double? = null,
    val openStatus: OpenStatus? = null,
    val imageUrl: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val website: String? = null
) {
    /** The distance as the rider reads it. */
    val distanceText: String?
        get() = distanceMeters?.let {
            if (it >= 1000) String.format(Locale.US, "%.1f km", it / 1000.0)
            else "${Math.round(it).toInt()} m"
        }

    /** The rating as the rider reads it, e.g. "4.1". */
    val ratingText: String?
        get() = rating?.let { String.format(Locale.US, "%.1f", it) }
}

/**
 * Builds the place card of an amenity from its cached tags.
 *
 * The tags are the place's own OpenStreetMap tags; the card never invents a
 * rating, an opening status or a photo.
 */
object PlaceCardBuilder {

    /** Builds the card of an amenity. */
    fun build(
        amenity: AmenityEntity,
        categoryName: String,
        distanceMeters: Double?,
        now: LocalTime?
    ): PlaceCard {
        val tags = parseTags(amenity.tagsJson)
        return PlaceCard(
            name = amenity.name?.trim()?.takeIf { it.isNotEmpty() },
            categoryName = categoryName,
            rating = rating(tags),
            reviewCount = reviewCount(tags),
            distanceMeters = distanceMeters,
            openStatus = now?.let { OpeningHours.status(tags["opening_hours"], it) },
            imageUrl = imageUrl(tags),
            address = tags["addr:full"] ?: tags["addr:street"],
            phone = tags["phone"] ?: tags["contact:phone"],
            website = tags["website"] ?: tags["contact:website"]
        )
    }

    /**
     * The rating of the place, when the data carries one.
     *
     * OpenStreetMap has no review count in general; a handful of tags carry a
     * rating (`stars`, `rating`), and that is what is shown. A place without
     * one shows no rating at all.
     */
    fun rating(tags: Map<String, String>): Double? {
        val raw = tags["stars"] ?: tags["rating"] ?: return null
        val value = raw.trim().toDoubleOrNull() ?: return null
        if (value <= 0.0 || value > 5.0) return null
        return value
    }

    /** The review count, when the data carries one. */
    fun reviewCount(tags: Map<String, String>): Int? {
        val raw = tags["review_count"] ?: tags["reviews"] ?: return null
        return raw.trim().toIntOrNull()?.takeIf { it > 0 }
    }

    /** The photo of the place, when the data carries a usable URL. */
    fun imageUrl(tags: Map<String, String>): String? {
        val raw = tags["image"] ?: tags["contact:photo"] ?: tags["contact:image"] ?: return null
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return url
    }

    /** Reads the cached tag map. */
    fun parseTags(tagsJson: String?): Map<String, String> {
        if (tagsJson.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(tagsJson)
            val out = HashMap<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = json.optString(key, "")
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
