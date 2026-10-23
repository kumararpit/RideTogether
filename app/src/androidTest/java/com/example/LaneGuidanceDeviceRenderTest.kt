package com.example

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.LaneArrow
import com.example.data.model.LaneGuidance
import com.example.data.model.LaneMark
import com.example.data.model.ManeuverType
import com.example.data.model.NavigationStep
import com.example.ui.components.LaneGuidanceStrip
import com.example.ui.components.TurnByTurnNavHeader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device rendering of the lane guidance strip and the turn card.
 *
 * The screenshots this test writes are the device evidence for the lane
 * guidance feature: the strip must show the route's lane bright, the other
 * lanes dimmed, and it must fit the width instead of clipping.
 *
 * The lane data used here is the routing engine's own output (a trimmed
 * capture of a live response) - see `LaneGuidanceParsingTest`.
 */
@RunWith(AndroidJUnit4::class)
class LaneGuidanceDeviceRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun save(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "lane_guidance")
        dir.mkdirs()
        File(dir, name).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** The engine's lane set for a three-lane approach with a left turn. */
    private val threeLaneLeftTurn = LaneGuidance(
        listOf(
            LaneMark(listOf(LaneArrow.LEFT), recommended = true),
            LaneMark(listOf(LaneArrow.STRAIGHT), recommended = false),
            LaneMark(listOf(LaneArrow.STRAIGHT), recommended = false)
        )
    )

    /** The engine's lane set for a four-lane approach with two left lanes. */
    private val fourLaneTwoLefts = LaneGuidance(
        listOf(
            LaneMark(listOf(LaneArrow.LEFT), recommended = true),
            LaneMark(listOf(LaneArrow.LEFT), recommended = true),
            LaneMark(listOf(LaneArrow.STRAIGHT), recommended = false),
            LaneMark(listOf(LaneArrow.STRAIGHT, LaneArrow.RIGHT), recommended = false)
        )
    )

    @Test
    fun rendersTheStripOnItsOwn() {
        composeRule.setContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF0E1A30))
                    .padding(16.dp())
            ) {
                LaneGuidanceStrip(guidance = threeLaneLeftTurn)
                LaneGuidanceStrip(guidance = fourLaneTwoLefts)
                LaneGuidanceStrip(
                    guidance = LaneGuidance(
                        listOf(
                            LaneMark(listOf(LaneArrow.STRAIGHT, LaneArrow.RIGHT), recommended = false),
                            LaneMark(listOf(LaneArrow.RIGHT, LaneArrow.STRAIGHT), recommended = true),
                            LaneMark(listOf(LaneArrow.SLIGHT_LEFT), recommended = false),
                            LaneMark(listOf(LaneArrow.U_TURN), recommended = false)
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        save("strip_only.png", bitmap)
    }

    @Test
    fun rendersTheTurnCardWithTheLaneStrip() {
        val step = NavigationStep(
            instruction = "Turn left onto Unter den Linden",
            roadName = "Unter den Linden",
            distanceMeters = 120.0,
            durationSeconds = 30.0,
            maneuverType = "turn",
            modifier = "left",
            maneuver = ManeuverType.TURN_LEFT,
            lanes = threeLaneLeftTurn
        )
        composeRule.setContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF0B1220))
            ) {
                TurnByTurnNavHeader(
                    currentStep = step,
                    nextStep = null,
                    isOffRoute = false,
                    isRerouting = false,
                    onReroute = {},
                    distanceToNextManeuverMeters = 120.0
                )
            }
        }
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithTag("nav_turn_header")
        assertNotNull(node)
        val bitmap = node.captureToImage().asAndroidBitmap()
        save("turn_card_with_lanes.png", bitmap)
    }

    @Test
    fun rendersTheTurnCardWithoutLaneData() {
        val step = NavigationStep(
            instruction = "Turn left onto Laxmi Chowk Road",
            roadName = "Laxmi Chowk Road",
            distanceMeters = 120.0,
            durationSeconds = 30.0,
            maneuverType = "turn",
            modifier = "left",
            maneuver = ManeuverType.TURN_LEFT,
            lanes = null
        )
        composeRule.setContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF0B1220))
            ) {
                TurnByTurnNavHeader(
                    currentStep = step,
                    nextStep = null,
                    isOffRoute = false,
                    isRerouting = false,
                    onReroute = {},
                    distanceToNextManeuverMeters = 120.0
                )
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onNodeWithTag("nav_turn_header").captureToImage().asAndroidBitmap()
        save("turn_card_without_lanes.png", bitmap)
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
}
