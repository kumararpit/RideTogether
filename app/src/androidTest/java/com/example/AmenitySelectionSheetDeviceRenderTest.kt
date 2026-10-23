package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.AmenityType
import com.example.data.model.LatLng
import com.example.ui.components.AmenityDetailsSheet
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device rendering of the amenity selection sheet.
 *
 * The sheet is the RideTogether equivalent of the reference's place sheet:
 * the place, its distance from the rider, and the action that turns it into a
 * stop of the ride. The capture is the device evidence that the primary
 * action is the in-app "Add as a stop" and that there is no hand-off to
 * another navigation app.
 */
@RunWith(AndroidJUnit4::class)
class AmenitySelectionSheetDeviceRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun save(name: String, bitmap: android.graphics.Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "amenity_selection")
        dir.mkdirs()
        File(dir, name).outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun rendersTheSelectionSheet() {
        val amenity = AmenityEntity(
            osmId = 12345L,
            name = "Captain Coffee",
            type = AmenityType.CAFE,
            lat = 18.5412,
            lon = 73.8931,
            tagsJson = null
        )
        composeRule.setContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1220))
            ) {
                AmenityDetailsSheet(
                    amenity = amenity,
                    riderLocation = LatLng(18.5390, 73.8900),
                    onAddAsStop = { },
                    onDismiss = { }
                )
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onNodeWithTag("amenity_details_sheet").captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        save("amenity_sheet.png", bitmap)
    }
}
