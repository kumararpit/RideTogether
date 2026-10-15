package com.example

import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.AmenityType
import com.example.data.amenities.AmenityTypeConverter
import com.example.data.amenities.Center
import com.example.data.amenities.OverpassElement
import com.example.data.amenities.OverpassResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AmenityRepositoryTest {

    @Test
    fun `test amenity parsing and category mapping for nodes and ways`() {
        val elements = listOf(
            OverpassElement("node", 101L, 18.52, 73.85, null, mapOf("amenity" to "fuel", "name" to "Shell")),
            OverpassElement("node", 102L, 18.53, 73.86, null, mapOf("amenity" to "cafe", "name" to "Starbucks")),
            OverpassElement("way", 103L, null, null, Center(18.54, 73.87), mapOf("shop" to "motorcycle_repair")),
            OverpassElement("way", 104L, null, null, Center(18.55, 73.88), mapOf("highway" to "rest_area")),
            OverpassElement("way", 105L, null, null, Center(18.56, 73.89), mapOf("shop" to "supermarket", "name" to "DMart")),
            OverpassElement("way", 106L, null, null, Center(18.57, 73.90), mapOf("tourism" to "hotel", "name" to "Taj")),
            OverpassElement("node", 107L, 18.58, 73.91, null, mapOf("amenity" to "restaurant", "name" to "Dhaba")),
            OverpassElement("node", 108L, 18.59, 73.92, null, mapOf("amenity" to "toilets")),
            OverpassElement("way", 109L, null, null, Center(18.60, 73.93), mapOf("tourism" to "viewpoint", "name" to "Sunset Point"))
        )
        val response = OverpassResponse(elements)

        val amenities = response.elements?.mapNotNull { el ->
            val lat = el.lat ?: el.center?.lat ?: return@mapNotNull null
            val lon = el.lon ?: el.center?.lon ?: return@mapNotNull null
            val tags = el.tags ?: emptyMap()
            val type = when {
                tags["amenity"] == "fuel" -> AmenityType.FUEL
                tags["amenity"] == "cafe" -> AmenityType.CAFE
                tags["shop"] == "motorcycle_repair" || tags["amenity"] == "motorcycle_repair" -> AmenityType.REPAIR
                tags["highway"] == "rest_area" || tags["amenity"] == "rest_stop" -> AmenityType.REST_STOP
                tags["shop"] == "convenience" || tags["shop"] == "supermarket" -> AmenityType.CONVENIENCE_STORE
                tags["tourism"] == "hotel" || tags["tourism"] == "motel" -> AmenityType.HOTEL
                tags["amenity"] == "restaurant" || tags["amenity"] == "fast_food" -> AmenityType.RESTAURANT
                tags["amenity"] == "toilets" -> AmenityType.TOILET
                tags["tourism"] == "viewpoint" -> AmenityType.SCENIC_VIEWPOINT
                else -> AmenityType.UNKNOWN
            }
            if (type == AmenityType.UNKNOWN) return@mapNotNull null
            AmenityEntity(el.id, type, lat, lon, tags["name"], "{}")
        }?.distinctBy { it.osmId } ?: emptyList()

        assertEquals(9, amenities.size)
        assertEquals(AmenityType.FUEL, amenities[0].type)
        assertEquals("Shell", amenities[0].name)
        assertEquals(AmenityType.REPAIR, amenities[2].type)
        assertEquals(18.54, amenities[2].lat, 0.001)
        assertEquals(AmenityType.CONVENIENCE_STORE, amenities[4].type)
        assertEquals("DMart", amenities[4].name)
        assertEquals(AmenityType.HOTEL, amenities[5].type)
        assertEquals(AmenityType.SCENIC_VIEWPOINT, amenities[8].type)
    }

    @Test
    fun `test deduplication of duplicate OSM identities`() {
        val elements = listOf(
            OverpassElement("node", 101L, 18.52, 73.85, null, mapOf("amenity" to "fuel", "name" to "Shell")),
            OverpassElement("node", 101L, 18.52, 73.85, null, mapOf("amenity" to "fuel", "name" to "Shell"))
        )
        val response = OverpassResponse(elements)

        val amenities = response.elements?.mapNotNull { el ->
            val lat = el.lat ?: el.center?.lat ?: return@mapNotNull null
            val lon = el.lon ?: el.center?.lon ?: return@mapNotNull null
            AmenityEntity(el.id, AmenityType.FUEL, lat, lon, el.tags?.get("name"), "{}")
        }?.distinctBy { it.osmId } ?: emptyList()

        assertEquals(1, amenities.size)
        assertEquals(101L, amenities[0].osmId)
    }

    @Test
    fun `test amenity type converter serialization and unknown string fallback`() {
        val converter = AmenityTypeConverter()
        assertEquals("FUEL", converter.fromAmenityType(AmenityType.FUEL))
        assertEquals(AmenityType.FUEL, converter.toAmenityType("FUEL"))
        assertEquals(AmenityType.HOTEL, converter.toAmenityType("HOTEL"))
        assertEquals(AmenityType.UNKNOWN, converter.toAmenityType("NON_EXISTENT_TYPE"))
    }
}
