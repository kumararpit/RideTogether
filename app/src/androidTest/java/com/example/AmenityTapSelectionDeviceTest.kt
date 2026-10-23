package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.AmenityType
import com.example.data.model.LatLng
import com.example.ui.components.RideMapCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * On-device verification of the amenity **selection** behaviour.
 *
 * The map is rendered with a synthetic amenity set and the taps are performed
 * on the real device, so the whole path is exercised: the renderer places the
 * markers, the tap handler resolves the tap against the drawn markers, and the
 * selection/expansion callbacks fire.
 *
 * The Overpass endpoint is frequently unavailable (documented in the master
 * report), so a synthetic set is the only deterministic way to test the
 * interaction; the data pipeline itself is covered by the offline suite.
 */
@RunWith(AndroidJUnit4::class)
class AmenityTapSelectionDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** The point the map is centred on; a marker there lands in the centre. */
    private val centre = LatLng(18.54180, 73.89350)

    private fun amenity(id: Long, name: String, lat: Double, lon: Double) = AmenityEntity(
        osmId = id,
        type = AmenityType.CAFE,
        lat = lat,
        lon = lon,
        name = name,
        tagsJson = null
    )

    @Test
    fun tappingAMarkerSelectsThatPlace() {
        var selected: AmenityEntity? = null
        var expanded: List<Long>? = null

        composeRule.setContent {
            RideMapCanvas(
                route = listOf(centre, LatLng(18.5430, 73.8950)),
                members = emptyList(),
                selectedRider = null,
                onSelectRider = { },
                isFollowRiderMode = false,
                amenities = listOf(amenity(11L, "Captain Coffee", centre.latitude, centre.longitude)),
                selectedAmenity = null,
                onSelectAmenity = { selected = it },
                onExpandAmenityCluster = { expanded = it },
                initialStartLocation = centre
            )
        }
        composeRule.waitForIdle()

        val root = composeRule.onRoot()
        val centreX = root.fetchSemanticsNode().size.width / 2f
        val centreY = root.fetchSemanticsNode().size.height / 2f
        root.performTouchInput { click(Offset(centreX, centreY)) }
        composeRule.waitForIdle()

        assertNotNull("the marker under the tap must be selected", selected)
        assertEquals(11L, selected!!.osmId)
        assertNull("a single marker is a selection, not an expansion", expanded)
    }

    @Test
    fun tappingAClusterExpandsIt() {
        var selected: AmenityEntity? = null
        var expanded: List<Long>? = null

        composeRule.setContent {
            RideMapCanvas(
                route = listOf(centre, LatLng(18.5430, 73.8950)),
                members = emptyList(),
                selectedRider = null,
                onSelectRider = { },
                isFollowRiderMode = false,
                // Two amenities a few metres apart: they are one cluster.
                amenities = listOf(
                    amenity(21L, "Corner Cafe", centre.latitude, centre.longitude),
                    amenity(22L, "Corner Cafe 2", centre.latitude + 0.00004, centre.longitude)
                ),
                selectedAmenity = null,
                onSelectAmenity = { selected = it },
                onExpandAmenityCluster = { expanded = it },
                initialStartLocation = centre
            )
        }
        composeRule.waitForIdle()

        val root = composeRule.onRoot()
        val centreX = root.fetchSemanticsNode().size.width / 2f
        val centreY = root.fetchSemanticsNode().size.height / 2f
        root.performTouchInput { click(Offset(centreX, centreY)) }
        composeRule.waitForIdle()

        assertNotNull("a cluster must expand", expanded)
        assertTrue(expanded!!.containsAll(listOf(21L, 22L)))
        assertNull("a cluster is an expansion, not a selection", selected)
    }
}
