package com.example

import com.example.data.amenities.AmenityType
import com.example.ui.components.AmenityIconConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmenityIconConfigTest {

    @Test
    fun `test category color mapping for all amenity types`() {
        AmenityType.entries.forEach { type ->
            val color = AmenityIconConfig.getCategoryColor(type)
            assertNotNull(color)
        }
    }

    @Test
    fun `test category vector icon mapping for all amenity types`() {
        AmenityType.entries.forEach { type ->
            val icon = AmenityIconConfig.getCategoryIcon(type)
            assertNotNull(icon)
        }
    }

    @Test
    fun `test unknown amenity type fallback`() {
        val fallbackColor = AmenityIconConfig.getCategoryColor(AmenityType.UNKNOWN)
        val fallbackIcon = AmenityIconConfig.getCategoryIcon(AmenityType.UNKNOWN)
        val fallbackName = AmenityIconConfig.getCategoryDisplayName(AmenityType.UNKNOWN)

        assertNotNull(fallbackColor)
        assertNotNull(fallbackIcon)
        assertEquals("Place", fallbackName)
    }

    @Test
    fun `test category display names`() {
        assertEquals("Fuel Station", AmenityIconConfig.getCategoryDisplayName(AmenityType.FUEL))
        assertEquals("Cafe", AmenityIconConfig.getCategoryDisplayName(AmenityType.CAFE))
        assertEquals("Motorcycle Repair", AmenityIconConfig.getCategoryDisplayName(AmenityType.REPAIR))
        assertEquals("Rest Stop", AmenityIconConfig.getCategoryDisplayName(AmenityType.REST_STOP))
        assertEquals("Convenience Store", AmenityIconConfig.getCategoryDisplayName(AmenityType.CONVENIENCE_STORE))
        assertEquals("Hotel", AmenityIconConfig.getCategoryDisplayName(AmenityType.HOTEL))
        assertEquals("Restaurant", AmenityIconConfig.getCategoryDisplayName(AmenityType.RESTAURANT))
        assertEquals("Restroom", AmenityIconConfig.getCategoryDisplayName(AmenityType.TOILET))
        assertEquals("Scenic Viewpoint", AmenityIconConfig.getCategoryDisplayName(AmenityType.SCENIC_VIEWPOINT))
    }

    @Test
    fun `every category has its own recognisable symbol`() {
        // A rider must be able to tell the categories apart on the map: the
        // symbols are the whole point of the pin, and two categories sharing
        // one symbol would make the map unreadable.
        val icons = AmenityType.entries.associateWith { AmenityIconConfig.getCategoryIcon(it) }
        val distinct = icons.values.toSet()
        assertEquals(
            "two categories share the same symbol",
            icons.size,
            distinct.size
        )
    }

    @Test
    fun `the rest stop is not signed as a car park`() {
        // The old mapping drew a parking sign for a roadside rest stop, which
        // is the wrong symbol for a motorcycle rider.
        val restStop = AmenityIconConfig.getCategoryIcon(AmenityType.REST_STOP)
        val parking = AmenityIconConfig.getCategoryIcon(AmenityType.CONVENIENCE_STORE)
        assertTrue(restStop != parking)
    }

    @Test
    fun `the sub category reads the data when it is there`() {
        val tags = mapOf("cuisine" to "North Indian")
        assertEquals("North Indian", AmenityIconConfig.subCategory(AmenityType.RESTAURANT, tags))
        assertEquals(null, AmenityIconConfig.subCategory(AmenityType.TOILET, tags))
    }
}
