package com.example

import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.AmenityType
import com.example.data.amenities.LocalTime
import com.example.data.amenities.OpenStatus
import com.example.data.amenities.PlaceCardBuilder
import com.example.ui.components.addStopLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The place card: what it shows, and — just as important — what it refuses
 * to show.
 *
 * Robolectric is required because the cached tags are read with `org.json`,
 * which is an Android stub in plain unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceCardTest {

    private fun amenity(
        name: String? = "Captain Coffee",
        type: AmenityType = AmenityType.CAFE,
        tags: Map<String, String> = emptyMap()
    ): AmenityEntity {
        val tagsJson = if (tags.isEmpty()) null else tags.entries.joinToString(
            separator = ",", prefix = "{", postfix = "}"
        ) { "\"${it.key}\":\"${it.value}\"" }
        return AmenityEntity(
            osmId = 1L,
            type = type,
            lat = 18.5412,
            lon = 73.8931,
            name = name,
            tagsJson = tagsJson
        )
    }

    @Test
    fun `the card shows the name, the category and the distance`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(),
            categoryName = "Cafe",
            distanceMeters = 407.0,
            now = null
        )
        assertEquals("Captain Coffee", card.name)
        assertEquals("Cafe", card.categoryName)
        assertEquals("407 m", card.distanceText)
    }

    @Test
    fun `a far place shows the distance in kilometres`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(),
            categoryName = "Cafe",
            distanceMeters = 2450.0,
            now = null
        )
        assertEquals("2.5 km", card.distanceText)
    }

    @Test
    fun `a rating is shown when the data carries one`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(tags = mapOf("stars" to "4.5")),
            categoryName = "Cafe",
            distanceMeters = null,
            now = null
        )
        assertEquals(4.5, card.rating!!, 0.001)
        assertEquals("4.5", card.ratingText)
    }

    @Test
    fun `a place without a rating shows no rating`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(),
            categoryName = "Cafe",
            distanceMeters = null,
            now = null
        )
        assertNull(card.rating)
        assertNull(card.ratingText)
    }

    @Test
    fun `an impossible rating is ignored`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(tags = mapOf("stars" to "9")),
            categoryName = "Cafe",
            distanceMeters = null,
            now = null
        )
        assertNull(card.rating)
    }

    @Test
    fun `the review count is shown when the data carries one`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(tags = mapOf("stars" to "4.1", "review_count" to "19402")),
            categoryName = "Cafe",
            distanceMeters = null,
            now = null
        )
        assertEquals(19402, card.reviewCount)
    }

    @Test
    fun `the opening status is shown when the hours can be read`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(tags = mapOf("opening_hours" to "Mo-Su 09:00-21:00")),
            categoryName = "Cafe",
            distanceMeters = null,
            now = LocalTime(dayOfWeek = 3, minutesOfDay = 10 * 60)
        )
        assertEquals(OpenStatus.State.OPEN, card.openStatus?.state)
    }

    @Test
    fun `unreadable hours produce no status`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(tags = mapOf("opening_hours" to "sunrise to sunset")),
            categoryName = "Cafe",
            distanceMeters = null,
            now = LocalTime(dayOfWeek = 3, minutesOfDay = 10 * 60)
        )
        assertNull(card.openStatus)
    }

    @Test
    fun `a photo is used only when the data carries a usable url`() {
        val withPhoto = PlaceCardBuilder.build(
            amenity = amenity(tags = mapOf("image" to "https://example.org/place.jpg")),
            categoryName = "Cafe",
            distanceMeters = null,
            now = null
        )
        assertEquals("https://example.org/place.jpg", withPhoto.imageUrl)

        val withJunk = PlaceCardBuilder.build(
            amenity = amenity(tags = mapOf("image" to "not-a-url")),
            categoryName = "Cafe",
            distanceMeters = null,
            now = null
        )
        assertNull(withJunk.imageUrl)
    }

    @Test
    fun `the contact details are read when they are there`() {
        val card = PlaceCardBuilder.build(
            amenity = amenity(
                tags = mapOf(
                    "addr:full" to "12 Laxmi Chowk, Pune",
                    "phone" to "+91 98765 43210",
                    "website" to "https://example.org"
                )
            ),
            categoryName = "Cafe",
            distanceMeters = null,
            now = null
        )
        assertEquals("12 Laxmi Chowk, Pune", card.address)
        assertEquals("+91 98765 43210", card.phone)
        assertEquals("https://example.org", card.website)
    }

    @Test
    fun `the add-stop action carries the price of the stop when it is known`() {
        assertEquals("Add stop", addStopLabel(detourSeconds = null, isEstimating = false))
        assertEquals("Add stop…", addStopLabel(detourSeconds = null, isEstimating = true))
        assertEquals("Add stop · +3 min detour", addStopLabel(detourSeconds = 180.0, isEstimating = false))
        assertEquals("Add stop", addStopLabel(detourSeconds = 5.0, isEstimating = false))
    }

    @Test
    fun `the tags of a place are read from the cache`() {
        val tags = PlaceCardBuilder.parseTags("""{"name":"Captain Coffee","cuisine":"Cafe"}""")
        assertEquals(2, tags.size)
        assertEquals("Captain Coffee", tags["name"])
        assertNotNull(PlaceCardBuilder.parseTags(null))
    }
}
