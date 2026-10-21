package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.LaneGuidance
import com.example.ui.map.LaneArrowGeometry

/**
 * The lane strip shown under the turn instruction.
 *
 * It draws the lanes of the carriageway the rider is on and highlights the
 * lanes the routing engine marked as usable for the upcoming manoeuvre. The
 * dimmed lanes are the ones the rider must **not** take — that is the whole
 * point of the strip, so they are drawn (not hidden) but at a low contrast.
 *
 * The strip is only rendered when the engine supplied lane data for the
 * manoeuvre. When it did not, the caller passes null and nothing is drawn:
 * RideTogether never invents a lane layout.
 *
 * The geometry is [LaneArrowGeometry] (RideTogether-original drawings); the
 * left/right variants are mirrors of one another, so a mirrored arrow is
 * always exactly the mirror of its counterpart.
 */
@Composable
fun LaneGuidanceStrip(
    guidance: LaneGuidance?,
    modifier: Modifier = Modifier,
    cellWidth: Dp = 26.dp,
    glyphHeight: Dp = 26.dp,
    recommendedColor: Color = Color.White,
    dimmedColor: Color = Color.White.copy(alpha = 0.32f),
    strokeWidth: Dp = 2.2.dp
) {
    if (guidance == null || guidance.isEmpty) return

    val description = guidance.laneAdvice() ?: "Lane guidance"

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lane_guidance_strip")
            .semantics { contentDescription = description }
    ) {
        val laneCount = guidance.laneCount
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(glyphHeight)
        ) {
            val availablePx = size.width
            val cellPx = cellWidth.toPx()
            val neededPx = laneCount * cellPx
            // Shrink the strip to fit instead of clipping it: a clipped arrow
            // is a wrong arrow.
            val scale = if (neededPx > availablePx && neededPx > 0f) availablePx / neededPx else 1f
            val drawnCell = cellPx * scale
            val stroke = Stroke(
                width = strokeWidth.toPx() * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            val startX = (availablePx - drawnCell * laneCount) / 2f
            val glyphScale = drawnCell / LaneArrowGeometry.BOX

            guidance.lanes.forEachIndexed { index, mark ->
                val color = if (mark.recommended) recommendedColor else dimmedColor
                val originX = startX + index * drawnCell
                val strokes = LaneArrowGeometry.strokesForLane(mark.arrows)
                strokes.forEach { glyphStroke ->
                    val path = androidx.compose.ui.graphics.Path().apply {
                        glyphStroke.points.forEachIndexed { i, point ->
                            val x = originX + point.x * glyphScale
                            val y = point.y * glyphScale
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    drawPath(path, color, style = stroke)
                }
            }
        }
    }
}

/** The offset of a glyph point, exposed for tests and previews. */
internal fun glyphOffset(x: Float, y: Float, originX: Float, scale: Float): Offset =
    Offset(originX + x * scale, y * scale)
