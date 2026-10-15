package com.example

import com.example.data.amenities.AmenityType
import com.example.ui.components.AmenityIconConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals("Amenity", fallbackName)
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
}
