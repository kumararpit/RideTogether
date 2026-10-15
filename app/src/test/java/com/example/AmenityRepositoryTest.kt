package com.example

import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.AmenityType
import com.example.data.amenities.Center
import com.example.data.amenities.OverpassElement
import com.example.data.amenities.OverpassResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AmenityRepositoryTest {

    @Test
    fun `test amenity parsing and category mapping`() {
        val elements = listOf(
            OverpassElement("node", 101L, 18.52, 73.85, null, mapOf("amenity" to "fuel", "name" to "Shell")),
            OverpassElement("node", 102L, 18.53, 73.86, null, mapOf("amenity" to "cafe", "name" to "Starbucks")),
            OverpassElement("way", 103L, null, null, Center(18.54, 73.87), mapOf("shop" to "motorcycle_repair")),
            OverpassElement("node", 104L, 18.55, 73.88, null, mapOf("highway" to "rest_area"))
        )
        val response = OverpassResponse(elements)

        val amenities = mutableListOf<AmenityEntity>()
        response.elements?.forEach { el ->
            val lat = el.lat ?: el.center?.lat
            val lon = el.lon ?: el.center?.lon
            if (lat != null && lon != null) {
                val tags = el.tags ?: emptyMap()
                val type = when {
                    tags["amenity"] == "fuel" -> AmenityType.FUEL
                    tags["amenity"] == "cafe" -> AmenityType.CAFE
                    tags["shop"] == "motorcycle_repair" || tags["amenity"] == "motorcycle_repair" -> AmenityType.REPAIR
                    tags["highway"] == "rest_area" -> AmenityType.REST_STOP
                    else -> AmenityType.UNKNOWN
                }
                if (type != AmenityType.UNKNOWN) {
                    amenities.add(
                        AmenityEntity(
                            osmId = el.id,
                            type = type,
                            lat = lat,
                            lon = lon,
                            name = tags["name"],
                            tagsJson = "{}"
                        )
                    )
                }
            }
        }

        assertEquals(4, amenities.size)
        assertEquals(AmenityType.FUEL, amenities[0].type)
        assertEquals("Shell", amenities[0].name)
        assertEquals(AmenityType.CAFE, amenities[1].type)
        assertEquals(AmenityType.REPAIR, amenities[2].type)
        assertEquals(AmenityType.REST_STOP, amenities[3].type)
    }

    @Test
    fun `test handling of malformed or unknown amenities`() {
        val elements = listOf(
            OverpassElement("node", 201L, null, null, null, mapOf("amenity" to "fuel")), // Missing lat/lon
            OverpassElement("node", 202L, 18.52, 73.85, null, mapOf("amenity" to "unknown_type")), // Unknown
            OverpassElement("node", 203L, 18.53, 73.86, null, mapOf("amenity" to "cafe")) // Valid
        )
        val response = OverpassResponse(elements)

        val amenities = response.elements?.mapNotNull { el ->
            val lat = el.lat ?: el.center?.lat ?: return@mapNotNull null
            val lon = el.lon ?: el.center?.lon ?: return@mapNotNull null
            val tags = el.tags ?: emptyMap()
            val type = when {
                tags["amenity"] == "fuel" -> AmenityType.FUEL
                tags["amenity"] == "cafe" -> AmenityType.CAFE
                else -> AmenityType.UNKNOWN
            }
            if (type == AmenityType.UNKNOWN) return@mapNotNull null
            AmenityEntity(el.id, type, lat, lon, tags["name"], "{}")
        } ?: emptyList()

        assertEquals(1, amenities.size)
        assertEquals(203L, amenities[0].osmId)
        assertEquals(AmenityType.CAFE, amenities[0].type)
    }
}
